package flow.l1d

import chisel3._
import chisel3.util._
import flow.coherence._
import flow.config.BreezeMemGeometry
import flow.interface.{BreezeMmuAccess, L1DOp, L1DRespKind}
import flow.mmu.sv39.{MmuCmd, TlbResp, TreePlru}

/** Load/store/atomic cache with a single MSHR and a separate completion pipeline.
  * CPU S1/S2/S3 retain their backend-aligned slots during s2Hold. Internal work
  * shares the SRAM ports, but can finish an older miss while CPU S2 waits.
  */
class L1DCache(g: BreezeMemGeometry, withPmpCandidate: Boolean = false) extends Module {
  val p = L1DParams(g)
  val io = IO(new L1DIO(p, withPmpCandidate))
  private def idx(a: UInt): UInt = if (p.idxBits == 0) 0.U(p.idxW.W) else a(p.offBits + p.idxBits - 1, p.offBits)
  private def word(a: UInt): UInt = a(p.offBits - 1, 3)
  private def line(a: UInt): UInt = a(p.paddrBits - 1, p.offBits)
  private def tag(a: UInt): UInt = a(p.paddrBits - 1, p.offBits + p.idxBits)
  private def key(a: UInt): UInt = a(p.conflictHi, p.conflictLo)
  private def storeLike(op: L1DOp.Type): Bool = op === L1DOp.Store || op === L1DOp.SC || op === L1DOp.AMO
  private def formatLoad(data: UInt, pa: UInt, size: UInt, signed: Bool, flw: Bool): UInt = {
    val shifted = data >> (pa(2, 0) ## 0.U(3.W))
    val ext = MuxLookup(size, shifted)(Seq(
      0.U -> Mux(signed, shifted(7, 0).asSInt.pad(64).asUInt, shifted(7, 0).pad(64)),
      1.U -> Mux(signed, shifted(15, 0).asSInt.pad(64).asUInt, shifted(15, 0).pad(64)),
      2.U -> Mux(signed, shifted(31, 0).asSInt.pad(64).asUInt, shifted(31, 0).pad(64))))
    Mux(flw, "hffffffff".U(32.W) ## shifted(31, 0), ext)
  }

  // Each way/set group owns its read/write decode and storage. Avoid a
  // single late write enable/address driving every bit of the tag array.
  val tagBanks = math.min(8, p.sets)
  val tagRows = p.sets / tagBanks
  val tagRowBits = log2Ceil(tagRows)
  private def tagBank(a: UInt): UInt =
    if (tagBanks == 1) 0.U(1.W) else a(p.idxBits - 1, tagRowBits)
  private def tagRow(a: UInt): UInt =
    if (tagRows == 1) 0.U(1.W) else a(tagRowBits - 1, 0)
  val tags = Seq.tabulate(tagBanks, p.ways) { (_, _) => SyncReadMem(tagRows, new L1TagEntry(p)) }
  val data = Seq.fill(p.ways)(SyncReadMem(p.sets * p.wordsPerLine, Vec(8, UInt(8.W))))
  val plru = RegInit(VecInit(Seq.fill(p.sets)(0.U(p.plruBits.W))))
  val initIdx = RegInit(0.U((p.idxBits + 1).W))
  val initDone = initIdx === p.sets.U
  val miss = Module(new L1DMiss(p))
  val probe = Module(new L1DProbe(p))
  val mmio = Module(new L1DMmio(p))

  val cpu1 = RegInit(0.U.asTypeOf(new L1S1(p)))
  val cpu2 = RegInit(0.U.asTypeOf(new L1S2(p)))
  val cpu3 = RegInit(0.U.asTypeOf(new L1S2(p)))
  val internal1 = RegInit(0.U.asTypeOf(new L1S1(p)))
  val internal2 = RegInit(0.U.asTypeOf(new L1S2(p)))
  val internal3 = RegInit(0.U.asTypeOf(new L1S2(p)))
  // CSRFile registers this one-bit event together with its new state. C1-B
  // also covers a held CPU item or one captured at that same update edge.
  val contextChanged = io.core.csr.permissionEvent
  val internalRefresh = internal3.valid && contextChanged &&
    (internal3.req.src === L1Src.Ptw || internal3.req.src === L1Src.Recheck)
  val cpuPermissionStale = cpu3.needsRecheck || contextChanged
  val ps = RegInit(0.U.asTypeOf(new L1PendingStore(p)))
  val rsvValid = RegInit(false.B)
  val rsvLine = Reg(UInt(p.lineAddrBits.W))
  val rsvTimer = RegInit(0.U(log2Ceil(p.rsvWindow + 1).W))
  // Keep the CPU owner at S2 across LR/AMO misses. Kill drops the owner,
  // while the accepted coherence transaction still installs and replays.
  val atomicWait = RegInit(false.B)
  val acquireBusy = RegInit(false.B)
  val amoRmw = RegInit(false.B)
  val amoOld = Reg(UInt(64.W))
  val psAtomic = RegInit(false.B)
  val amoAlu = Module(new flow.cache.BreezeAmoAlu)
  val recheckIssued = RegInit(false.B)
  val ptwOutstanding = RegInit(false.B)
  val upgrading = RegInit(false.B)
  val wholeBusy = RegInit(false.B)
  val wholeOwner = Reg(L1Src())
  val cpuHold = Wire(Bool())
  val internalHold = Wire(Bool())
  val cpuAdvance = !cpuHold
  val cpu1Advance = cpuAdvance
  val internalAdvance = !internalHold
  val internal1Advance = !internal1.valid || internalAdvance
  val cpuKill = io.core.s1Kill || io.core.s2Kill

  // Retain the request in CPU S2 while requery runs through the completion
  // lane. CPU S1 retains Y, including a dropped TLB response after X misses.
  // Neither X nor Y reserves an MSHR or a way before translation completes.
  val cpuRetry = cpu3.valid && cpu3.req.core.op =/= L1DOp.Fence &&
    (cpu3.translationMiss || cpu3.snapInvalid || cpu3.needsRecheck || cpuPermissionStale)
  val resourcesClear = !miss.io.status.mshrValid && !miss.io.status.wbValid
  val retryReady = cpuRetry && !atomicWait && !amoRmw && !recheckIssued && resourcesClear && !probe.io.pending.valid && !cpuKill

  def conflict(k: UInt): Bool = {
    val s1Store = cpu1.valid && storeLike(cpu1.req.core.op) && key(cpu1.req.core.vaddr) === k
    val s2Store = cpu3.valid && storeLike(cpu3.req.core.op) && key(cpu3.req.core.vaddr) === k
    val translatingStore = cpu2.valid && storeLike(cpu2.req.core.op) && key(cpu2.req.core.vaddr) === k
    s1Store || translatingStore || s2Store || (ps.valid && key(ps.paddr) === k)
  }
  // Rechecking the held Store must not conflict with itself or younger CPU S1.
  val retryConflict = ps.valid && key(ps.paddr) === key(cpu3.req.core.vaddr)
  // A held younger Store is not older than the MSHR's committed request.
  val replayConflict = ps.valid && key(ps.paddr) === key(miss.io.s0Req.bits.replayReq.paddr)
  val cpuLoad = io.core.req.bits.op === L1DOp.Load || io.core.req.bits.op === L1DOp.LR
  val cpuConflict = cpuLoad && conflict(key(io.core.req.bits.vaddr))
  // Held untranslated X/Y stores are younger than the PTE read needed for X.
  val ptwConflict = ps.valid && key(ps.paddr) === key(io.ptw.req.bits.paddr)

  val s0Req = WireDefault(0.U.asTypeOf(new L1PipeReq(p)))
  val selected = WireDefault(L1Src.Cpu)
  // Trap is derived from this cache's response in the integrated backend.
  // It clears state at the edge, never feeds the response/hold decision.
  val rsvProbeHeld = rsvValid && rsvTimer =/= 0.U && probe.io.pending.addr === rsvLine
  val incomingAtomic = io.core.req.bits.op === L1DOp.LR || io.core.req.bits.op === L1DOp.SC ||
    io.core.req.bits.op === L1DOp.AMO
  val needsDrain = io.core.req.bits.op === L1DOp.AMO || (incomingAtomic && io.core.req.bits.rl)
  val olderDrained = !cpu1.valid && !cpu2.valid && !cpu3.valid && !internal1.valid && !internal2.valid && !internal3.valid &&
    !miss.io.status.mshrValid && !ps.valid
  val cpuEntryOpen = initDone && !cpuRetry && !recheckIssued && !mmio.io.active && !acquireBusy &&
    (!probe.io.pending.valid || rsvProbeHeld) && !wholeBusy && (!needsDrain || olderDrained)
  val canPtw = initDone && resourcesClear && !ptwOutstanding && !ptwConflict && !ps.valid &&
    (!cpu3.valid || cpuRetry) && (!cpu2.valid || cpuRetry) && (!cpu1.valid || cpuRetry) &&
    !internal1.valid && !internal2.valid && !internal3.valid
  // Do not start a whole-line read across the two local RMW beats. During
  // miss/requery waits this guard opens again, so grants can depend on probes.
  val localAmo = Wire(Bool())

  // Each source computes admission independently. Higher-priority maintenance
  // grants never pass through CPU kill or the selected request's TLB mux.
  val laneOpen = initDone && (wholeBusy || (!internal1.valid && !internal2.valid && !internal3.valid))
  val requests = VecInit(Seq(
    laneOpen && probe.io.s0Req.valid && !localAmo && (!wholeBusy || wholeOwner === L1Src.Probe),
    laneOpen && miss.io.s0Req.valid && miss.io.s0Req.bits.install && (!wholeBusy || wholeOwner === L1Src.Refill),
    laneOpen && miss.io.s0Req.valid && miss.io.s0Req.bits.wbRead && (!wholeBusy || wholeOwner === L1Src.WbRead),
    laneOpen && !wholeBusy && miss.io.s0Req.valid && miss.io.s0Req.bits.replay && !replayConflict && !ps.valid,
    laneOpen && !wholeBusy && io.ptw.req.valid && canPtw,
    laneOpen && !wholeBusy && retryReady && !retryConflict && io.tlb.req.ready,
    laneOpen && !wholeBusy && io.core.req.valid && cpuEntryOpen && !cpuConflict && !cpuKill))
  val chosen = PriorityEncoderOH(requests.asUInt)
  val candidates = Wire(Vec(7, new L1PipeReq(p)))
  candidates(0) := 0.U.asTypeOf(new L1PipeReq(p))
    candidates(0).src := L1Src.Probe
    candidates(0).hasPaddr := true.B
    candidates(0).paddr := probe.io.pending.addr ## 0.U(p.offBits.W)
    candidates(0).physicalAddress := candidates(0).paddr
    candidates(0).idx := idx(candidates(0).paddr)
    candidates(0).word := probe.io.s0Req.bits
    candidates(0).beat := probe.io.s0Req.bits
    candidates(0).lastBeat := probe.io.s0Req.bits === (p.wordsPerLine - 1).U

  candidates(1) := 0.U.asTypeOf(new L1PipeReq(p))
    candidates(1).src := L1Src.Refill
    candidates(1).hasPaddr := true.B
    candidates(1).idx := miss.io.s0Req.bits.idx
    candidates(1).word := miss.io.s0Req.bits.beat
    candidates(1).beat := miss.io.s0Req.bits.beat
    candidates(1).lastBeat := miss.io.s0Req.bits.installIsAckE || candidates(1).beat === (p.wordsPerLine - 1).U

  candidates(2) := 0.U.asTypeOf(new L1PipeReq(p))
    candidates(2).src := L1Src.WbRead
    candidates(2).hasPaddr := true.B
    candidates(2).idx := miss.io.s0Req.bits.idx
    candidates(2).word := miss.io.s0Req.bits.beat
    candidates(2).beat := miss.io.s0Req.bits.beat
    candidates(2).lastBeat := candidates(2).beat === (p.wordsPerLine - 1).U

  candidates(3) := 0.U.asTypeOf(new L1PipeReq(p))
    candidates(3) := miss.io.s0Req.bits.replayReq
    candidates(3).src := L1Src.Replay

  candidates(4) := 0.U.asTypeOf(new L1PipeReq(p))
    candidates(4).src := L1Src.Ptw
    candidates(4).hasPaddr := true.B
    candidates(4).physicalAddress := io.ptw.req.bits.paddr
    candidates(4).paddr := io.ptw.req.bits.paddr(p.paddrBits - 1, 0)
    candidates(4).idx := idx(candidates(4).paddr)
    candidates(4).word := word(candidates(4).paddr)
    candidates(4).core.op := L1DOp.Load
    candidates(4).core.size := 3.U

  candidates(5) := 0.U.asTypeOf(new L1PipeReq(p))
    candidates(5) := cpu3.req
    candidates(5).src := L1Src.Recheck
    candidates(5).hasPaddr := false.B

  candidates(6) := 0.U.asTypeOf(new L1PipeReq(p))
    candidates(6).src := L1Src.Cpu
    candidates(6).core := io.core.req.bits
    candidates(6).idx := idx(io.core.req.bits.vaddr)
    candidates(6).word := word(io.core.req.bits.vaddr)
    when(io.core.req.bits.op === L1DOp.Fence) { candidates(6).hasPaddr := true.B }
  s0Req := Mux1H(chosen.asBools, candidates)
  selected := Mux(chosen.orR, s0Req.src, L1Src.Cpu)
  val sourceEnds = candidates.map { r =>
    flow.mmu.BreezePmpDecode.accessEnd(
      Mux(r.hasPaddr, r.physicalAddress, r.core.vaddr)(6, 0),
      Mux(r.src === L1Src.Ptw || r.replayPtw, 3.U, r.core.size))
  }
  val selectedEnd = Mux1H(chosen.asBools, sourceEnds)
  val cpuNeedsTlb = io.core.req.bits.op =/= L1DOp.Fence
  val cpuFire = chosen(6) && cpu1Advance && (!cpuNeedsTlb || io.tlb.req.ready)
  val probeFire = chosen(0) && internal1Advance
  val refillFire = chosen(1) && internal1Advance
  val wbReadFire = chosen(2) && internal1Advance
  val replayFire = chosen(3) && internal1Advance
  val ptwFire = chosen(4) && internal1Advance
  val recheckFire = chosen(5) && internal1Advance && io.tlb.req.ready
  val internalFire = probeFire || refillFire || wbReadFire || replayFire || ptwFire || recheckFire
  val s0Fire = cpuFire || internalFire
  io.core.req.ready := cpuFire
  io.ptw.req.ready := ptwFire
  io.tlb.req.valid := ((chosen(6) && cpuNeedsTlb && cpu1Advance) ||
    (chosen(5) && internal1Advance)) && !cpuKill
  io.tlb.req.bits.vaddr := Mux(chosen(5), cpu3.req.core.vaddr, io.core.req.bits.vaddr)
  io.tlb.req.bits.cmd := Mux(storeLike(Mux(chosen(5), cpu3.req.core.op, io.core.req.bits.op)), MmuCmd.Store, MmuCmd.Load)
  io.tlb.kill := cpuKill
  when(cpuFire && (io.core.req.bits.op === L1DOp.AMO || (incomingAtomic && io.core.req.bits.aq))) {
    acquireBusy := true.B
  }
  miss.io.s0Grant := refillFire || wbReadFire || replayFire
  probe.io.s0Grant := probeFire
  when(recheckFire) { recheckIssued := true.B }
  when(io.ptw.req.fire) { ptwOutstanding := true.B }

  val wholeIssue = probeFire || refillFire || wbReadFire
  when(wholeIssue && !wholeBusy) { wholeBusy := true.B; wholeOwner := selected }
  when(internal3.valid && internalAdvance && internal3.req.lastBeat &&
    internal3.req.src === wholeOwner) { wholeBusy := false.B }

  val tagReadBank = RegEnable(tagBank(s0Req.idx), s0Fire)
  val tagReads = VecInit((0 until tagBanks).map { bank =>
    VecInit((0 until p.ways).map { way =>
      tags(bank)(way).read(tagRow(s0Req.idx), s0Fire && tagBank(s0Req.idx) === bank.U)
    })
  })
  val tagRead = if (tagBanks == 1) tagReads(0) else tagReads(tagReadBank)
  val dataRead = VecInit(data.map(_.read(s0Req.idx ## s0Req.word, s0Fire).asUInt))
  val cpuFresh = RegNext(cpuFire, false.B)
  val cpuTagHeld = Reg(Vec(p.ways, new L1TagEntry(p)))
  val cpuDataHeld = Reg(Vec(p.ways, UInt(64.W)))
  val cpuTlbHeld = Reg(new TlbResp)
  val cpuCandidateHeld = Reg(UInt(64.W))
  val cpuTlbValidHeld = RegInit(false.B)
  when(cpuFresh) {
    cpuTagHeld := tagRead; cpuDataHeld := dataRead
    cpuTlbHeld := io.tlb.resp.bits; cpuTlbValidHeld := io.tlb.resp.valid
    cpuCandidateHeld := io.tlb.candidatePaddr.getOrElse(io.tlb.resp.bits.paddr)
  }
  val cpuTags = Mux(cpuFresh, tagRead, cpuTagHeld)
  val cpuData = Mux(cpuFresh, dataRead, cpuDataHeld)
  val cpuTlb = Mux(cpuFresh, io.tlb.resp.bits, cpuTlbHeld)
  val cpuTlbValid = Mux(cpuFresh, io.tlb.resp.valid, cpuTlbValidHeld)
  val cpuCandidate = Mux(cpuFresh,
    io.tlb.candidatePaddr.getOrElse(io.tlb.resp.bits.paddr), cpuCandidateHeld)

  val internalFresh = RegNext(internalFire, false.B)
  val internalTagHeld = Reg(Vec(p.ways, new L1TagEntry(p)))
  val internalDataHeld = Reg(Vec(p.ways, UInt(64.W)))
  val internalTlbHeld = Reg(new TlbResp)
  val internalCandidateHeld = Reg(UInt(64.W))
  val internalTlbValidHeld = RegInit(false.B)
  when(internalFresh) {
    internalTagHeld := tagRead; internalDataHeld := dataRead
    internalTlbHeld := io.tlb.resp.bits; internalTlbValidHeld := io.tlb.resp.valid
    internalCandidateHeld := io.tlb.candidatePaddr.getOrElse(io.tlb.resp.bits.paddr)
  }
  val internalTags = Mux(internalFresh, tagRead, internalTagHeld)
  val internalData = Mux(internalFresh, dataRead, internalDataHeld)
  val internalTlb = Mux(internalFresh, io.tlb.resp.bits, internalTlbHeld)
  val internalTlbValid = Mux(internalFresh, io.tlb.resp.valid, internalTlbValidHeld)
  val internalCandidate = Mux(internalFresh,
    io.tlb.candidatePaddr.getOrElse(io.tlb.resp.bits.paddr), internalCandidateHeld)

  val cpuPhysical = Mux(cpu1.req.hasPaddr, cpu1.req.physicalAddress, cpuCandidate)
  val internalPhysical = Mux(internal1.req.hasPaddr, internal1.req.physicalAddress, internalCandidate)
  // Each lane checks registered PA before its S2->S3 boundary.
  // A changed-context internal item retains its slot for one refresh edge;
  // this lane's checker is reused, without selecting between CPU/internal S2.
  def permissionCheck(address: UInt, req: L1PipeReq,
                      end: flow.mmu.BreezePmpAccessEnd): (Bool, flow.platform.PMAResult) = {
    val isPtw = req.src === L1Src.Ptw || (req.src === L1Src.Replay && req.replayPtw)
    val isWrite = !isPtw && storeLike(req.core.op)
    val pma = Module(new flow.platform.PMAChecker)
    val pmp = Module(new flow.mmu.BreezePmpChecker(64, decoded = true,
      precomputedEnd = withPmpCandidate))
    pma.io.query.addr := address
    pma.io.query.sizeLog2 := Mux(isPtw, 3.U, req.core.size)
    pma.io.query.accessType := Mux(isWrite, flow.platform.PMAAccessType.Store, flow.platform.PMAAccessType.Load)
    pmp.io.addr := address
    pmp.io.sizeLog2 := Mux(isPtw, 3.U, req.core.size)
    pmp.io.access := Mux(isWrite, BreezeMmuAccess.Store, BreezeMmuAccess.Load)
    pmp.io.privilege := Mux(isPtw, 1.U, Mux(io.core.csr.mprv, io.core.csr.mpp, io.core.csr.privilege))
    pmp.io.context := io.core.csr
    pmp.io.accessEnd.foreach(_ := end)
    (pmp.io.allowed, pma.io.result)
  }
  val (cpuPmpAllowed, cpuPma) = permissionCheck(cpu2.physicalAddress, cpu2.req, cpu2.pmpEnd)
  val internalCheckAddress = Mux(internalRefresh, internal3.physicalAddress, internal2.physicalAddress)
  val internalCheckReq = Mux(internalRefresh, internal3.req, internal2.req)
  val internalCheckEnd = Mux(internalRefresh, internal3.pmpEnd, internal2.pmpEnd)
  val (internalPmpAllowed, internalPma) = permissionCheck(internalCheckAddress, internalCheckReq, internalCheckEnd)
  def capturePermission(slot: L1S2, address: UInt, allowed: Bool, pma: flow.platform.PMAResult): Unit = {
    slot.pmpAllowed := allowed
    slot.pmaAllowed := pma.allowed
    slot.pmaDevice := pma.device
    slot.pmaAmoOk := pma.amoOk
    slot.pmaRsrvOk := pma.rsrvOk
    slot.highAddress := (address >> p.paddrBits).orR
  }
  // These are the values the miss/writeback address registers will hold at
  // the S2->S3 edge. A simultaneous S2 allocation forwards into the compare
  // bits, rather than putting victim/address selection in the next S2 cone.
  val nextMshrLine = Mux(miss.io.alloc.valid, miss.io.alloc.bits.lineAddr, miss.io.status.mshrLineAddr)
  val wbAllocates = miss.io.alloc.valid && miss.io.alloc.bits.victimValid
  val nextWbLine = Mux(wbAllocates, miss.io.alloc.bits.victimLineAddr, miss.io.status.wbLineAddr)
  def captureLineMatches(slot: L1S2, address: UInt): Unit = {
    slot.sameMshrLine := line(address) === nextMshrLine
    slot.sameWbLine := line(address) === nextWbLine
    slot.sameMshrSet := idx(address) === idx(nextMshrLine ## 0.U(p.offBits.W))
  }

  def captureRead(slot: L1S2, in: L1S1, physical: UInt, tlb: TlbResp,
                  tlbValid: Bool, tags: Vec[L1TagEntry], words: Vec[UInt]): Unit = {
    slot.req := in.req
    slot.physicalAddress := physical
    slot.pmpEnd := in.pmpEnd
    slot.paddr := Mux(in.req.hasPaddr, in.req.paddr, tlb.paddr(p.paddrBits - 1, 0))
    slot.pageFault := !in.req.hasPaddr && tlbValid && tlb.pageFault
    slot.accessFault := !in.req.hasPaddr && tlbValid && tlb.accessFault
    slot.translationMiss := !in.req.hasPaddr && (!tlbValid || tlb.miss)
    slot.snapInvalid := in.snapInvalid
    slot.needsRecheck := in.needsRecheck || contextChanged
    slot.tagVec := tags
    slot.dataVec := words
    val bytes = (1.U(4.W) << in.req.core.size)(3, 0)
    val offset = Mux(in.req.hasPaddr, in.req.paddr(2, 0), in.req.core.vaddr(2, 0))
    slot.storeMask := ((((1.U(9.W) << bytes) - 1.U)(7, 0)) << offset)(7, 0)
    slot.storeDataAligned := (in.req.core.wdata << (offset ## 0.U(3.W)))(63, 0)
  }
  def captureHit(slot: L1S2, in: L1S2): Unit = {
    val matches = VecInit(in.tagVec.map(e => e.state =/= L1State.I && e.tag === tag(in.paddr)))
    slot.tagMatch := matches
    slot.hitData := Mux1H(matches, in.dataVec)
  }
  when(cpu1Advance) {
    cpu1.valid := cpuFire
    cpu1.req := s0Req
    cpu1.snapInvalid := false.B
    cpu1.needsRecheck := contextChanged
    cpu1.pmpEnd := selectedEnd
  }
  when(cpuAdvance) {
    cpu2.valid := cpu1.valid && !cpuKill
    captureRead(cpu2, cpu1, cpuPhysical, cpuTlb, cpuTlbValid, cpuTags, cpuData)
    cpu3 := cpu2
    cpu3.valid := cpu2.valid && !cpuKill
    cpu3.needsRecheck := cpu2.needsRecheck || contextChanged
    capturePermission(cpu3, cpu2.physicalAddress, cpuPmpAllowed, cpuPma)
    captureHit(cpu3, cpu2)
    captureLineMatches(cpu3, cpu2.paddr)
  }
  when(internal1Advance) {
    internal1.valid := internalFire
    internal1.req := s0Req
    internal1.snapInvalid := false.B
    internal1.needsRecheck := contextChanged
    internal1.pmpEnd := selectedEnd
  }
  when(internalAdvance) {
    internal2.valid := internal1.valid
    captureRead(internal2, internal1, internalPhysical, internalTlb,
      internalTlbValid, internalTags, internalData)
    internal3 := internal2
    internal3.valid := internal2.valid
    internal3.needsRecheck := internal2.req.src === L1Src.Recheck &&
      (internal2.needsRecheck || contextChanged)
    capturePermission(internal3, internal2.physicalAddress, internalPmpAllowed, internalPma)
    captureHit(internal3, internal2)
    captureLineMatches(internal3, internal2.paddr)
  }
  when(contextChanged) {
    when(cpu1.valid && !cpu1Advance) { cpu1.needsRecheck := true.B }
    when(cpu2.valid && !cpuAdvance) { cpu2.needsRecheck := true.B }
    when(internal1.valid && !internal1Advance) { internal1.needsRecheck := true.B }
    when(internal2.valid && !internalAdvance) { internal2.needsRecheck := true.B }
  }
  when(internalRefresh) {
    capturePermission(internal3, internal3.physicalAddress, internalPmpAllowed, internalPma)
  }
  when(cpu3.valid && contextChanged && !cpuAdvance) {
    cpu3.needsRecheck := true.B
  }
  // A held slot's address is fixed, but internal completion work may allocate
  // new ownership. Refresh only the match bits at that register mutation.
  when(cpuHold && miss.io.alloc.valid) {
    cpu3.sameMshrLine := line(cpu3.paddr) === miss.io.alloc.bits.lineAddr
    cpu3.sameMshrSet := cpu3.req.idx === idx(miss.io.alloc.bits.lineAddr ## 0.U(p.offBits.W))
    when(wbAllocates) { cpu3.sameWbLine := line(cpu3.paddr) === miss.io.alloc.bits.victimLineAddr }
  }
  when(internalHold && miss.io.alloc.valid) {
    internal3.sameMshrLine := line(internal3.paddr) === miss.io.alloc.bits.lineAddr
    internal3.sameMshrSet := internal3.req.idx === idx(miss.io.alloc.bits.lineAddr ## 0.U(p.offBits.W))
    when(wbAllocates) { internal3.sameWbLine := line(internal3.paddr) === miss.io.alloc.bits.victimLineAddr }
  }
  val recheckReturns = internal3.valid && internal3.req.src === L1Src.Recheck && internalAdvance
  when(recheckReturns && cpu3.valid && !io.core.s2Kill) {
    cpu3 := internal3
    cpu3.req.src := L1Src.Cpu
    cpu3.needsRecheck := internal3.needsRecheck || contextChanged
    recheckIssued := false.B
  }
  when(cpuKill) { cpu1.valid := false.B; cpu2.valid := false.B }
  when(io.core.s2Kill) { cpu3.valid := false.B; recheckIssued := false.B }
  when(io.core.s2Kill && internal1.req.src === L1Src.Recheck) { internal1.valid := false.B }
  when(io.core.s2Kill && internal2.req.src === L1Src.Recheck) { internal2.valid := false.B }
  when(io.core.s2Kill && internal3.req.src === L1Src.Recheck) { internal3.valid := false.B }

  // One S3 executor owns side effects. Completion work has priority over a
  // CPU snapshot; the CPU slots hold rather than losing a backend response.
  val s2 = Wire(new L1S2(p))
  s2 := Mux(internal3.valid, internal3, cpu3)
  val fromInternal = internal3.valid
  val replay = fromInternal && s2.req.src === L1Src.Replay
  val ptw = s2.req.src === L1Src.Ptw || (replay && s2.req.replayPtw)
  val rechecking = fromInternal && s2.req.src === L1Src.Recheck
  val normalInternal = fromInternal && !replay && !ptw && !rechecking
  val hitVec = VecInit((0 until p.ways).map(w => s2.tagMatch(w) && !(miss.io.status.wayLocked &&
      miss.io.status.mshrWay === w.U && s2.sameMshrSet &&
      !(s2.req.src === L1Src.Probe && upgrading && miss.io.status.mshrState === MshrState.Wait &&
        !probe.io.pending.owner))))
  val hit = hitVec.asUInt.orR
  val hitWay = OHToUInt(hitVec)
  val hitState = Mux1H(hitVec, s2.tagVec.map(_.state.asUInt)).asTypeOf(L1State())
  val writable = (0 until p.ways).map(w => hitVec(w) &&
    (s2.tagVec(w).state === L1State.E || s2.tagVec(w).state === L1State.M)).reduce(_ || _)
  val sharedHit = (0 until p.ways).map(w => hitVec(w) && s2.tagVec(w).state === L1State.S).reduce(_ || _)
  localAmo := amoRmw || (!fromInternal && cpu3.valid && cpu3.req.core.op === L1DOp.AMO &&
    hit && writable && !atomicWait && !cpuRetry)
  val hitWord = s2.hitData
  val isStore = !ptw && storeLike(s2.req.core.op)
  val sameMshr = miss.io.status.mshrValid && s2.sameMshrLine
  val sameWb = miss.io.status.wbValid && s2.sameWbLine
  val invalidWays = VecInit((0 until p.ways).map(w => s2.tagVec(w).state === L1State.I &&
    !(miss.io.status.wayLocked && miss.io.status.mshrWay === w.U &&
      idx(miss.io.status.mshrLineAddr ## 0.U(p.offBits.W)) === s2.req.idx)))
  val replacement = TreePlru.victim(plru(s2.req.idx), p.ways)
  val victimWay = Mux(invalidWays.asUInt.orR, PriorityEncoder(invalidWays), replacement)
  val isLr = !ptw && s2.req.core.op === L1DOp.LR
  val isSc = !ptw && s2.req.core.op === L1DOp.SC
  val isAmo = !ptw && s2.req.core.op === L1DOp.AMO
  val blockingAtomic = isLr || isAmo
  val scSuccess = rsvValid && rsvLine === line(s2.paddr)
  val upgrade = sharedHit && (isStore || isLr)
  val allocWay = Mux(upgrade, hitWay, victimWay)
  val allocWayOH = Mux(upgrade, hitVec.asUInt,
    Mux(invalidWays.asUInt.orR, PriorityEncoderOH(invalidWays.asUInt), UIntToOH(replacement, p.ways)))
  val victimEntry = if (p.ways == 1) s2.tagVec(0) else s2.tagVec(allocWay)
  val victimValid = !upgrade && victimEntry.state =/= L1State.I
  val victimAddress = if (p.idxBits == 0) victimEntry.tag else victimEntry.tag ## s2.req.idx

  val installNow = refillFire
  val installLast = refillFire && (miss.io.s0Req.bits.installIsAckE ||
    miss.io.s0Req.bits.beat === (p.wordsPerLine - 1).U)
  val psWriteBlocked = wholeBusy || miss.io.status.mshrState === MshrState.Install || probe.io.tagUpdate.valid
  val psCompletes = ps.valid && !psWriteBlocked
  val psWrites = psCompletes && !(psAtomic && io.core.s2Kill)
  // These decision inputs must not depend on S0's kill-qualified grant:
  // a WB fault produces s2Kill from resp in the same cycle.
  val installing = miss.io.status.mshrState === MshrState.Install
  val tagBusy = installing || probe.io.tagUpdate.valid || (psCompletes && ps.setDirty)
  val psRoom = !ps.valid || psCompletes
  val externalMutation = installing || probe.io.tagUpdate.valid || (psCompletes && ps.setDirty)
  val externalSet = Mux(installing, idx(miss.io.status.mshrLineAddr ## 0.U(p.offBits.W)),
    Mux(probe.io.tagUpdate.valid, idx(probe.io.pending.addr ## 0.U(p.offBits.W)), ps.idx))
  // With an idle MSHR no way is locked. Allocation needs a writeback slot
  // iff all ways are valid and the request cannot upgrade its shared hit.
  // This Boolean capacity decision never selects victim/allocWay/tag data.
  val hasInvalidWay = s2.tagVec.map(_.state === L1State.I).reduce(_ || _)
  val ownershipUpgrade = sharedHit && (isStore || isLr)
  val wbCapacity = !miss.io.status.wbValid || hasInvalidWay || ownershipUpgrade
  val canAllocate = miss.io.status.canAllocate && !ptwOutstanding && !tagBusy && !probe.io.s0Req.valid && wbCapacity
  val ptwCanAllocate = miss.io.status.canAllocate && !tagBusy && wbCapacity

  val atomicDenied = !ptw && ((s2.req.core.op === L1DOp.LR || s2.req.core.op === L1DOp.SC) && !s2.pmaRsrvOk ||
    s2.req.core.op === L1DOp.AMO && !s2.pmaAmoOk)
  val highAddress = s2.highAddress
  val alignmentMask = (1.U(9.W) << Mux(ptw, 3.U, s2.req.core.size)) - 1.U
  val misaligned = (s2.req.core.vaddr(2, 0) & alignmentMask(2, 0)).orR && !ptw
  val permissionFault = s2.pageFault || s2.accessFault || highAddress || !s2.pmaAllowed ||
    !s2.pmpAllowed || atomicDenied || (ptw && s2.pmaDevice) ||
    (s2.pmaDevice && !ptw && s2.req.core.op =/= L1DOp.Load && s2.req.core.op =/= L1DOp.Store)
  // Qualify the original priority regions without encoding one shared
  // outcome and decoding it again for completion, hold and side effects.
  // Internal refresh owns its slot; replay bypasses fresh access checks.
  val internalService = normalInternal || rechecking
  val replayDecision = s2.valid && !internalRefresh && !internalService && replay
  val accessDecision = s2.valid && !internalRefresh && !internalService && !replay
  val cpuLaneBusy = !fromInternal && (internal1.valid || internal2.valid || wholeBusy)
  val rmwDecision = accessDecision && !cpuLaneBusy && amoRmw
  val afterAtomicWait = accessDecision && !cpuLaneBusy && !amoRmw && !atomicWait
  val isFence = !ptw && s2.req.core.op === L1DOp.Fence
  val fenceDecision = afterAtomicWait && isFence
  val lookupDecision = afterAtomicWait && !isFence
  val snapshotStale = s2.translationMiss || s2.snapInvalid || s2.needsRecheck ||
    (!fromInternal && cpuPermissionStale) ||
    (!fromInternal && externalMutation && s2.req.idx === externalSet)
  val lookupFault = misaligned || permissionFault
  val checkedAccess = lookupDecision && !snapshotStale && !lookupFault
  val mmioDecision = checkedAccess && s2.pmaDevice
  // Permissions precede reservation failure; SC never allocates an MSHR.
  val scDecision = checkedAccess && !s2.pmaDevice && isSc
  val cacheDecision = checkedAccess && !s2.pmaDevice && !isSc
  val lineBusy = sameMshr || sameWb
  val cacheReady = cacheDecision && !lineBusy

  // Hits, PS capacity and miss capacity have separate local decisions.
  // Allocation remains independent of victim selection and write data.
  val cacheLoad = !ptw && s2.req.core.op === L1DOp.Load
  val cacheStore = !ptw && s2.req.core.op === L1DOp.Store
  val cacheOp = ptw || cacheLoad || cacheStore || blockingAtomic
  val ownedHit = hit && writable
  val cacheNeedsMiss = ((ptw || cacheLoad) && !hit) ||
    ((cacheStore || blockingAtomic) && !ownedHit)
  val cacheHasCapacity = (ptw && ptwCanAllocate) || (!ptw && canAllocate)
  val cacheHitDone = (cacheLoad && hit) || (ownedHit && (isLr || (cacheStore && psRoom)))
  val cacheHitAmo = ownedHit && isAmo && psRoom
  val cacheWait = (cacheNeedsMiss && !cacheHasCapacity) ||
    (ownedHit && (cacheStore || isAmo) && !psRoom) || !cacheOp

  // Replay errors win over AMO setup; a failed Store replay does not wait
  // for PS and must not capture a store. PTW replay retains its own response.
  val replayFault = blockingAtomic && atomicWait && s2.req.replayError
  val replayAmo = !replayFault && isAmo && atomicWait
  val replayWait = !replayFault && !replayAmo && !s2.req.replayError &&
    s2.req.core.op === L1DOp.Store && !psRoom
  val replayDone = replayDecision && !replayFault && !replayAmo && !replayWait

  val s2Done = (replayDone && !ptw) || (rmwDecision && psCompletes) ||
    (fenceDecision && io.core.drained) ||
    (mmioDecision && mmio.io.done.valid && !mmio.io.done.bits.error) ||
    (scDecision && (!scSuccess || psRoom)) || (cacheReady && cacheHitDone)
  val s2Miss = cacheReady && cacheNeedsMiss && cacheHasCapacity
  val s2Fault = (replayDecision && replayFault) ||
    (lookupDecision && !snapshotStale && lookupFault) ||
    (mmioDecision && mmio.io.done.valid && mmio.io.done.bits.error)
  val s2Recheck = (s2.valid && internalRefresh) || (lookupDecision && snapshotStale)
  val s2Mmio = mmioDecision && !mmio.io.done.valid
  val s2Amo = (replayDecision && replayAmo) || (cacheReady && cacheHitAmo)
  val s2PtwResp = (replayDone && ptw) || (cacheReady && ptw && hit)
  val s2Wait = (replayDecision && replayWait) || (accessDecision && cpuLaneBusy) ||
    (rmwDecision && !psCompletes) ||
    (accessDecision && !cpuLaneBusy && !amoRmw && atomicWait) ||
    (fenceDecision && !io.core.drained) || (scDecision && scSuccess && !psRoom) ||
    (cacheDecision && lineBusy) || (cacheReady && cacheWait)
  // Assertion-only consumers disappear when synthesis removes assertions.
  // Name the instances so the routed netlist can prove their absence.
  val shadowPmp = Module(new flow.mmu.BreezePmpChecker(64))
  val shadowPma = Module(new flow.platform.PMAChecker)
  shadowPmp.io.addr := s2.physicalAddress
  shadowPmp.io.sizeLog2 := Mux(ptw, 3.U, s2.req.core.size)
  shadowPmp.io.access := Mux(isStore, BreezeMmuAccess.Store, BreezeMmuAccess.Load)
  shadowPmp.io.privilege := Mux(ptw, 1.U, Mux(io.core.csr.mprv, io.core.csr.mpp, io.core.csr.privilege))
  shadowPmp.io.context := io.core.csr
  shadowPma.io.query.addr := s2.physicalAddress
  shadowPma.io.query.sizeLog2 := Mux(ptw, 3.U, s2.req.core.size)
  shadowPma.io.query.accessType := Mux(isStore, flow.platform.PMAAccessType.Store, flow.platform.PMAAccessType.Load)
  // Replay is checked at commitment. Stale snapshots are not used: CPU
  // reissues through Recheck, internal PTW/Recheck holds for a refresh edge.
  val permissionUsed = s2.valid && !replay && !normalInternal && !rechecking &&
    !internalRefresh && !(!fromInternal && cpuPermissionStale) &&
    !s2.translationMiss && !s2.needsRecheck && !s2.snapInvalid &&
    !atomicWait && !amoRmw && s2.req.core.op =/= L1DOp.Fence
  when(permissionUsed) {
    assert(s2.pmpAllowed === shadowPmp.io.allowed &&
      s2.pmaAllowed === shadowPma.io.result.allowed &&
      s2.pmaDevice === shadowPma.io.result.device &&
      s2.pmaAmoOk === shadowPma.io.result.amoOk &&
      s2.pmaRsrvOk === shadowPma.io.result.rsrvOk &&
      s2.highAddress === (s2.physicalAddress >> p.paddrBits).orR,
      "[SOC3 M1] S2 permission snapshot differs from current context")
  }
  val undecided = s2Wait || s2Recheck || s2Mmio || s2Amo || (blockingAtomic && s2Miss)
  // Admission reserves the single internal result's capacity. In particular,
  // PTW is accepted only with free miss resources, and replay waits for PS.
  // An internal wait here would prevent that same lane finishing the miss.
  internalHold := internalRefresh
  val atomicReplayResp = replay && atomicWait && (isLr || s2.req.replayError)
  cpuHold := cpu3.valid && !atomicReplayResp && (internal3.valid || undecided)
  io.core.s2Hold := cpuHold
  // Resource waits must requery SRAM when they resume; do not reuse a tag
  // snapshot acquired before the older refill/probe/writeback completed.
  // A hit in S still needs the MSHR for GetM. Mark that wait as well, so a
  // held younger S1 Store cannot prevent the probe that frees L2's set.
  when(cpu3.valid && !atomicWait && !amoRmw && cpu3.req.core.op =/= L1DOp.Fence && !fromInternal && s2Wait &&
    (sameMshr || sameWb || ((!hit || ownershipUpgrade) && !miss.io.status.canAllocate))) { cpu3.needsRecheck := true.B }

  val rawLoad = Mux(!fromInternal && mmio.io.done.valid, mmio.io.done.bits.rdata, hitWord)
  val loadData = formatLoad(rawLoad, s2.paddr, s2.req.core.size, s2.req.core.signed, s2.req.core.isFlw)
  val atomicData = formatLoad(hitWord, s2.paddr, s2.req.core.size, true.B, false.B)
  amoAlu.io.func := s2.req.core.amoFunc
  amoAlu.io.isWord := s2.req.core.size === 2.U
  amoAlu.io.oldOperand := hitWord >> (s2.paddr(2, 0) ## 0.U(3.W))
  amoAlu.io.rs2 := s2.req.core.wdata
  val cpuDecided = atomicReplayResp || (cpu3.valid && !fromInternal &&
    (s2Done || (s2Miss && !blockingAtomic) || s2Fault))
  io.core.resp.valid := cpuDecided
  io.core.resp.bits.kind := MuxCase(L1DRespKind.Done, Seq(
    s2Miss -> L1DRespKind.Mshr,
    s2Fault -> L1DRespKind.Exc))
  io.core.resp.bits.data := Mux(amoRmw, amoOld, Mux(isSc, Mux(scSuccess, 0.U, 1.U), Mux(isLr, atomicData, loadData)))
  io.core.resp.bits.excCause := Mux(misaligned, Mux(isStore, 6.U, 4.U),
    Mux(s2.pageFault, Mux(isStore, 15.U, 13.U), Mux(isStore, 7.U, 5.U)))
  io.core.resp.bits.tval := s2.req.core.vaddr

  val allocates = s2.valid && s2Miss && (ptw || !io.core.s2Kill)
  miss.io.alloc.valid := allocates
  miss.io.alloc.bits := 0.U.asTypeOf(miss.io.alloc.bits)
  miss.io.alloc.bits.lineAddr := line(s2.paddr)
  miss.io.alloc.bits.isGetM := isStore || isLr
  miss.io.alloc.bits.isLr := isLr
  miss.io.alloc.bits.upgrade := upgrade
  miss.io.alloc.bits.way := allocWay
  miss.io.alloc.bits.src := Mux(ptw, MshrSrc.Ptw, MshrSrc.Cpu)
  miss.io.alloc.bits.req := s2.req.core
  miss.io.alloc.bits.word := s2.req.word
  miss.io.alloc.bits.victimValid := victimValid
  miss.io.alloc.bits.victimLineAddr := victimAddress
  miss.io.alloc.bits.victimDirty := victimEntry.state === L1State.M
  val replayFinishes = replay && !internalHold
  when(allocates && blockingAtomic) { atomicWait := true.B }
  when(replayFinishes && blockingAtomic) { atomicWait := false.B }
  when(cpuDecided && (isAmo || ((isLr || isSc) && s2.req.core.aq))) { acquireBusy := false.B }
  when(cpuDecided) { amoRmw := false.B }
  when(io.core.s2Kill || (io.core.s1Kill && cpu1.valid &&
    (cpu1.req.core.op === L1DOp.AMO || cpu1.req.core.aq))) { acquireBusy := false.B }
  when(io.core.s2Kill) { atomicWait := false.B; amoRmw := false.B }
  miss.io.replayDone := replayFinishes
  miss.io.replayLoad.valid := replayFinishes && !ptw && s2.req.core.op === L1DOp.Load
  miss.io.replayLoad.bits.rd := s2.req.core.rd
  miss.io.replayLoad.bits.data := loadData
  miss.io.replayLoad.bits.error := s2.req.replayError
  io.core.late <> miss.io.late
  when(replayFinishes && !s2.req.replayError) { assert(hit, "MSHR replay must hit") }

  io.ptw.resp.valid := s2.valid && ptw && (s2PtwResp || s2Fault)
  io.ptw.resp.bits.data := hitWord
  io.ptw.resp.bits.accessFault := s2Fault || (replay && s2.req.replayError)
  when(io.ptw.resp.valid) { ptwOutstanding := false.B }
  miss.io.wbReadBeat.valid := internal3.valid && internal3.req.src === L1Src.WbRead
  miss.io.wbReadBeat.bits := (if (p.ways == 1) internal3.dataVec(0) else internal3.dataVec(miss.io.status.wbWay))
  probe.io.s2Beat.valid := internal3.valid && internal3.req.src === L1Src.Probe
  probe.io.s2Beat.bits.beat := internal3.req.beat
  probe.io.s2Beat.bits.data := hitWord
  probe.io.s2Beat.bits.hitWay := hitWay
  probe.io.s2Beat.bits.localState := Mux(hit, hitState, L1State.I)

  val probeLine = probe.io.pending.addr
  val probePaddr = probeLine ## 0.U(p.offBits.W)
  val probeIdx = idx(probePaddr)
  // S is retained during an upgrade until an owner/sharer probe arrives.
  when(allocates) { upgrading := upgrade }
  when(probe.io.tagUpdate.valid && line(probePaddr) === miss.io.status.mshrLineAddr &&
    probe.io.tagUpdate.bits.newState === L1State.I) { upgrading := false.B }
  val waitingGrant = miss.io.status.mshrState === MshrState.Wait || miss.io.status.mshrState === MshrState.Install ||
    miss.io.status.mshrState === MshrState.Replay
  val holdForMiss = miss.io.status.mshrValid && probeLine === miss.io.status.mshrLineAddr &&
    waitingGrant && (!(upgrading && miss.io.status.mshrState === MshrState.Wait) || probe.io.pending.owner)
  val holdForWb = miss.io.status.wbValid && probeLine === miss.io.status.wbLineAddr
  val psSameProbe = ps.valid && line(ps.paddr) === probeLine
  // Until CPU S1 advances, conservatively compare its VIPT set. This also
  // avoids putting the kill-qualified TLB Valid on the probe/S2 ready path.
  val cpu1ProbeAddressMatch = cpu1.req.idx === probeIdx
  val cpu1StoreProbe = cpu1.valid && storeLike(cpu1.req.core.op) && cpu1ProbeAddressMatch
  val cpu3StoreProbe = cpu3.valid && storeLike(cpu3.req.core.op) && line(cpu3.paddr) === probeLine
  val cpu2StoreProbe = cpu2.valid && storeLike(cpu2.req.core.op) && cpu2.req.idx === probeIdx
  probe.io.hold := holdForMiss || holdForWb || psSameProbe || rsvProbeHeld ||
    (amoRmw && line(cpu3.paddr) === probeLine)
  // Registered wait reasons avoid feeding S2's arbitration result back into
  // its own ready decision. A newly blocked store is parked on the next edge.
  // An LR/AMO waiting for its grant has no local write in flight. In
  // particular, a sharer Inv during GetM upgrade must be able to produce
  // the Ack that the grant depends on. Miss/PS/RMW holds still apply above.
  // FENCE parks the younger S1 request until older memory work drains.
  // That request cannot write while FENCE owns S2; probes must still pass
  // so the older MSHR can receive the grant that FENCE is waiting for.
  val fenceWaiting = cpu3.valid && cpu3.req.core.op === L1DOp.Fence
  val cpuAlreadyWaiting = cpuRetry || atomicWait || fenceWaiting || wholeBusy || internal1.valid || internal2.valid || internal3.valid
  probe.io.startOk := (!cpu1StoreProbe || cpuAlreadyWaiting) &&
    (!cpu2StoreProbe || cpuAlreadyWaiting) &&
    (!cpu3StoreProbe || cpuRetry || atomicWait) && !psSameProbe
  probe.io.initDone := initDone

  val amoStarts = s2.valid && isAmo && s2Amo &&
    (!replay || atomicWait) && !s2.req.replayError && !io.core.s2Kill
  val stores = s2.valid && !ptw && !s2.pmaDevice && !(replay && s2.req.replayError) &&
    ((s2.req.core.op === L1DOp.Store && s2Done && (replay || !io.core.s2Kill)) ||
      (isSc && scSuccess && s2Done && !io.core.s2Kill) || amoStarts)
  when(psCompletes) { ps.valid := false.B; psAtomic := false.B }
  when(stores) {
    ps.valid := true.B
    psAtomic := amoStarts
  }
  when(psRoom) {
    ps.idx := s2.req.idx
    ps.word := s2.req.word
    ps.way := hitWay
    ps.paddr := s2.paddr
    ps.mask := s2.storeMask
    val amoAligned = (amoAlu.io.newOperand << (s2.paddr(2, 0) ## 0.U(3.W)))(63, 0)
    ps.data := Mux(isAmo, amoAligned, s2.storeDataAligned)
    ps.setDirty := hitState === L1State.E
  }
  when(amoStarts) { amoRmw := true.B; amoOld := atomicData }
  when(rsvTimer =/= 0.U) { rsvTimer := rsvTimer - 1.U }
  val lrCompletes = cpuDecided && isLr && s2Done && !io.core.s2Kill
  when(lrCompletes) { rsvValid := true.B; rsvLine := line(s2.paddr); rsvTimer := p.rsvWindow.U }
  val scCompletes = cpuDecided && isSc && !io.core.s2Kill
  val probeStarts = probe.io.s0Grant && probe.io.s0Req.bits === 0.U
  when(io.core.trapClearRsv || scCompletes ||
    (probeStarts && ((rsvValid && probeLine === rsvLine) || (lrCompletes && probeLine === line(s2.paddr)))) ||
    (allocates && victimValid && rsvValid && victimAddress === rsvLine)) {
    rsvValid := false.B; rsvTimer := 0.U
  }

  // Preserve install > probe > allocation > PS priority. Candidate payloads
  // and row/bank/way decode are independent of the final source authorization.
  val tagRequests = Seq(psWrites && ps.setDirty, allocates && victimValid,
    probe.io.tagUpdate.valid, installLast)
  val tagGrants = tagRequests.indices.map { source =>
    tagRequests(source) && !tagRequests.drop(source + 1).foldLeft(false.B)(_ || _)
  }
  val tagIndices = Seq(ps.idx, s2.req.idx, probeIdx, miss.io.s0Req.bits.idx)
  val tagWays = Seq(ps.way, allocWay, probe.io.tagUpdate.bits.way, miss.io.s0Req.bits.way)
  val tagMasks = Seq(UIntToOH(ps.way, p.ways), allocWayOH,
    UIntToOH(probe.io.tagUpdate.bits.way, p.ways), UIntToOH(miss.io.s0Req.bits.way, p.ways))
  val psTag = WireDefault(0.U.asTypeOf(new L1TagEntry(p)))
  psTag.tag := tag(ps.paddr); psTag.state := L1State.M
  val evictedTag = WireDefault(victimEntry)
  evictedTag.state := L1State.I
  val probeTag = WireDefault(0.U.asTypeOf(new L1TagEntry(p)))
  probeTag.tag := tag(probePaddr); probeTag.state := probe.io.tagUpdate.bits.newState
  val tagValues = Seq(psTag, evictedTag, probeTag, miss.io.s0Req.bits.installTag)
  // Shared metadata consumers retain their original one selected update.
  val tagWrite = tagGrants.reduce(_ || _)
  val tagIdx = Mux1H(tagGrants, tagIndices)
  val tagWay = Mux1H(tagGrants, tagWays)
  val tagMask = Mux1H(tagGrants, tagMasks)
  val tagValue = Mux1H(tagGrants, tagValues)
  val installPlru = installLast && miss.io.s0Req.bits.installTag.state =/= L1State.I
  val hitPlru = s2.valid && hit && s2Done && !io.core.s2Kill && !installLast
  val installWayOH = UIntToOH(miss.io.s0Req.bits.way, p.ways)
  // Data candidates use each set's own state and early way selection. The
  // late completion/kill grant controls only the local write authorization.
  if (p.ways > 1) for (set <- 0 until p.sets) {
    val candidates = (0 until p.ways).map(w => TreePlru.touch(plru(set), w.U, p.ways))
    val installed = Mux1H(installWayOH.asBools, candidates)
    val touched = Mux1H(hitVec.toSeq, candidates)
    when(installPlru && miss.io.s0Req.bits.idx === set.U) { plru(set) := installed }
      .elsewhen(hitPlru && s2.req.idx === set.U) { plru(set) := touched }
  }
  for (bank <- 0 until tagBanks; way <- 0 until p.ways) {
    val selects = tagGrants.indices.map { source =>
      tagGrants(source) && tagMasks(source)(way) && tagBank(tagIndices(source)) === bank.U
    }
    val initializing = !initDone && tagBank(initIdx) === bank.U
    when(initializing || selects.reduce(_ || _)) {
      // Exactly one unmasked port per local memory, including initialization.
      tags(bank)(way).write(
        Mux(initializing, tagRow(initIdx), Mux1H(selects, tagIndices.map(tagRow))),
        Mux(initializing, 0.U.asTypeOf(new L1TagEntry(p)), Mux1H(selects, tagValues)))
    }
  }
  when(!initDone) {
    initIdx := initIdx + 1.U
  }
  for (w <- 0 until p.ways) {
    val refillWrite = installNow && !miss.io.s0Req.bits.installIsAckE && miss.io.s0Req.bits.way === w.U
    val storeWrite = psWrites && ps.way === w.U
    // Refill uses the same byte-masked port, with every byte enabled.
    when(refillWrite || storeWrite) {
      data(w).write(Mux(refillWrite, miss.io.s0Req.bits.idx ## miss.io.s0Req.bits.beat, ps.idx ## ps.word),
        Mux(refillWrite, miss.io.s0Req.bits.installData, ps.data).asTypeOf(Vec(8, UInt(8.W))),
        Mux(refillWrite, "hff".U(8.W), ps.mask).asBools)
    }
  }

  val invalidatesSnapshot = installNow || probe.io.tagUpdate.valid || (allocates && victimValid) ||
    (psWrites && ps.setDirty)
  val changedSet = Mux(installNow, miss.io.s0Req.bits.idx, Mux(probe.io.tagUpdate.valid, probeIdx,
    Mux(allocates && victimValid, s2.req.idx, ps.idx)))
  // Include same-edge reads. Held snapshots remember mutation until requery.
  when(invalidatesSnapshot) {
    when(cpu1.valid && cpu1.req.idx === changedSet && !cpu1Advance) { cpu1.snapInvalid := true.B }
    when(cpuFire && s0Req.idx === changedSet) { cpu1.snapInvalid := true.B }
    when(cpu1.valid && cpu1.req.idx === changedSet && cpuAdvance) { cpu2.snapInvalid := true.B }
    when(cpu2.valid && cpu2.req.idx === changedSet && cpuAdvance) { cpu3.snapInvalid := true.B }
    when(cpu2.valid && cpu2.req.idx === changedSet && !cpuAdvance) { cpu2.snapInvalid := true.B }
    when(cpu3.valid && cpu3.req.idx === changedSet && cpuHold && !recheckReturns) { cpu3.snapInvalid := true.B }
    when(internalFire && selected === L1Src.Recheck && s0Req.idx === changedSet) { internal1.snapInvalid := true.B }
    when(internal1.valid && internal1.req.src === L1Src.Recheck && internal1.req.idx === changedSet && internalAdvance) {
      internal2.snapInvalid := true.B
    }
    when(internal1.valid && internal1.req.src === L1Src.Recheck && internal1.req.idx === changedSet && !internal1Advance) {
      internal1.snapInvalid := true.B
    }
    when(internal2.valid && internal2.req.src === L1Src.Recheck && internal2.req.idx === changedSet) {
      when(internalAdvance) { internal3.snapInvalid := true.B }
        .otherwise { internal2.snapInvalid := true.B }
    }
  }

  io.coh.req <> miss.io.req
  miss.io.rspDown.valid := io.coh.rspDown.valid
  miss.io.rspDown.bits := io.coh.rspDown.bits
  io.coh.rspDown.ready := true.B
  probe.io.snp <> io.coh.snp
  // L2 always accepts RSPup, but retain the selected payload if a test agent
  // applies backpressure; a later probe answer must not replace a held Put.
  val upHeld = RegInit(false.B)
  val upChoice = Reg(Bool())
  val chooseProbe = Mux(upHeld, upChoice, probe.io.ack.valid)
  io.coh.rspUp.valid := Mux(chooseProbe, probe.io.ack.valid, miss.io.put.valid)
  io.coh.rspUp.bits := Mux(chooseProbe, probe.io.ack.bits, miss.io.put.bits)
  probe.io.ack.ready := io.coh.rspUp.ready && chooseProbe
  miss.io.put.ready := io.coh.rspUp.ready && !chooseProbe
  when(io.coh.rspUp.valid && !io.coh.rspUp.ready && !upHeld) { upHeld := true.B; upChoice := chooseProbe }
  when(io.coh.rspUp.fire) { upHeld := false.B }

  mmio.io.start.valid := !fromInternal && cpu3.valid && s2Mmio && !mmio.io.active && !io.core.s2Kill
  mmio.io.start.bits.paddr := s2.paddr
  mmio.io.start.bits.isWrite := s2.req.core.op === L1DOp.Store
  mmio.io.start.bits.size := s2.req.core.size
  mmio.io.start.bits.signed := s2.req.core.signed
  mmio.io.start.bits.wdata := s2.req.core.wdata
  mmio.io.start.bits.rd := s2.req.core.rd
  mmio.io.start.bits.isFlw := s2.req.core.isFlw
  // A completion is only consumed when the held CPU S2 owns the executor.
  mmio.io.s2Kill := io.core.s2Kill
  mmio.io.doneConsume := !fromInternal && cpuDecided
  mmio.io.drained := io.core.drained
  io.mmio <> mmio.io.axi
  io.core.mmioBusy := mmio.io.busy
  io.core.drained := !miss.io.status.mshrValid && !ps.valid

  io.events := 0.U.asTypeOf(io.events)
  io.events.load_access := io.core.req.fire && cpuLoad
  io.events.store_access := io.core.req.fire && storeLike(io.core.req.bits.op)
  io.events.load_miss := allocates && !ptw && !isStore
  io.events.store_miss := allocates && !ptw && isStore
  io.events.upgrade := allocates && upgrade
  io.events.ptw_access := io.ptw.req.fire
  io.events.ptw_miss := allocates && ptw
  io.events.hit_under_miss := cpuDecided && s2Done && hit && miss.io.status.mshrValid && !sameMshr
  io.events.mshr_busy_cycles := miss.io.status.mshrValid
  io.events.mshr_full_stall := cpuHold && !hit && !miss.io.status.canAllocate
  io.events.same_line_stall := cpuHold && (sameMshr || sameWb)
  io.events.s0_conflict_stall := io.core.req.valid && cpuConflict && cpuEntryOpen
  io.events.writeback_dirty := miss.io.put.fire && miss.io.put.bits.hasData
  io.events.writeback_clean := miss.io.put.fire && !miss.io.put.bits.hasData
  io.events.probe_received := io.coh.snp.fire
  io.events.probe_held_cycles := probe.io.pending.valid && probe.io.hold
  io.events.lr_count := lrCompletes
  io.events.sc_fail := scCompletes && s2Done && !scSuccess
  io.events.mmio_read := mmio.io.axi.ar.fire
  io.events.mmio_write := mmio.io.axi.aw.fire
  io.events.mmio_cycles := mmio.io.busy
  assert(PopCount(hitVec) <= 1.U, "two ways hit the same tag")
  when(allocates) { assert(!miss.io.status.wayLocked, "allocation selected a locked way") }
  when(stores) { assert(psRoom && hit && writable, "Store committed without PS capacity/ownership") }
  when(lrCompletes) { assert(hit && writable, "LR reservation without exclusive ownership") }
  when(atomicReplayResp) { assert(cpu3.valid, "atomic replay without held CPU owner") }
  when(internal3.valid) {
    // A context refresh already owns this slot; it is not a wait for miss
    // capacity. Permit only that precise local Recheck, with no side effect.
    val reservedRefresh = internalRefresh && s2Recheck &&
      internalHold && !internalAdvance && !allocates && !io.ptw.resp.valid
    assert(!undecided || (s2Amo && psRoom) || reservedRefresh,
      "internal result has no reserved completion capacity")
    when(internalRefresh) {
      assert(reservedRefresh, "permission refresh lost its slot or produced a side effect")
    }
  }
  when(amoRmw) { assert(psCompletes, "AMO RMW protection exceeded two local beats") }
  when(cpu1.valid && !cpu1Advance) { assert(cpuHold, "CPU S1 held without s2Hold") }
}
