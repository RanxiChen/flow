package flow.l1d

import chisel3._
import chisel3.util._
import flow.coherence._
import flow.config.BreezeMemGeometry
import flow.interface.{BreezeMmuAccess, L1DOp, L1DRespKind}
import flow.mmu.sv39.{MmuCmd, TlbResp, TreePlru}

/** Load/store/atomic cache with a single MSHR and a separate completion pipeline.
  * CPU S1/S2 retain their backend-aligned slots during s2Hold. Internal work
  * shares the SRAM ports, but can finish an older miss while CPU S2 waits.
  */
class L1DCache(g: BreezeMemGeometry) extends Module {
  val p = L1DParams(g)
  val io = IO(new L1DIO(p))
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

  val tags = SyncReadMem(p.sets, Vec(p.ways, new L1TagEntry(p)))
  val data = Seq.fill(p.ways)(SyncReadMem(p.sets * p.wordsPerLine, Vec(8, UInt(8.W))))
  val plru = RegInit(VecInit(Seq.fill(p.sets)(0.U(p.plruBits.W))))
  val initIdx = RegInit(0.U((p.idxBits + 1).W))
  val initDone = initIdx === p.sets.U
  val miss = Module(new L1DMiss(p))
  val probe = Module(new L1DProbe(p))
  val mmio = Module(new L1DMmio(p))
  val pma = Module(new flow.platform.PMAChecker)
  val pmp = Module(new flow.mmu.BreezePmpChecker(64))

  val cpu1 = RegInit(0.U.asTypeOf(new L1S1(p)))
  val cpu2 = RegInit(0.U.asTypeOf(new L1S2(p)))
  val internal1 = RegInit(0.U.asTypeOf(new L1S1(p)))
  val internal2 = RegInit(0.U.asTypeOf(new L1S2(p)))
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
  val cpuRetry = cpu2.valid && cpu2.req.core.op =/= L1DOp.Fence &&
    (cpu2.translationMiss || cpu2.snapInvalid || cpu2.needsRecheck)
  val resourcesClear = !miss.io.status.mshrValid && !miss.io.status.wbValid
  val retryReady = cpuRetry && !atomicWait && !amoRmw && !recheckIssued && resourcesClear && !probe.io.pending.valid && !cpuKill

  def conflict(k: UInt): Bool = {
    val s1Store = cpu1.valid && storeLike(cpu1.req.core.op) && key(cpu1.req.core.vaddr) === k
    val s2Store = cpu2.valid && storeLike(cpu2.req.core.op) && key(cpu2.req.core.vaddr) === k
    s1Store || s2Store || (ps.valid && key(ps.paddr) === k)
  }
  // Rechecking the held Store must not conflict with itself or younger CPU S1.
  val retryConflict = ps.valid && key(ps.paddr) === key(cpu2.req.core.vaddr)
  // A held younger Store is not older than the MSHR's committed request.
  val replayConflict = ps.valid && key(ps.paddr) === key(miss.io.s0Req.bits.replayReq.paddr)
  val cpuLoad = io.core.req.bits.op === L1DOp.Load || io.core.req.bits.op === L1DOp.LR
  val cpuConflict = cpuLoad && conflict(key(io.core.req.bits.vaddr))
  // Held untranslated X/Y stores are younger than the PTE read needed for X.
  val ptwConflict = ps.valid && key(ps.paddr) === key(io.ptw.req.bits.paddr)

  val s0Req = WireDefault(0.U.asTypeOf(new L1PipeReq(p)))
  val selected = WireDefault(L1Src.Cpu)
  val candidate = WireDefault(false.B)
  // Trap is derived from this cache's response in the integrated backend.
  // It clears state at the edge, never feeds the response/hold decision.
  val rsvProbeHeld = rsvValid && rsvTimer =/= 0.U && probe.io.pending.addr === rsvLine
  val incomingAtomic = io.core.req.bits.op === L1DOp.LR || io.core.req.bits.op === L1DOp.SC ||
    io.core.req.bits.op === L1DOp.AMO
  val needsDrain = io.core.req.bits.op === L1DOp.AMO || (incomingAtomic && io.core.req.bits.rl)
  val olderDrained = !cpu1.valid && !cpu2.valid && !internal1.valid && !internal2.valid &&
    !miss.io.status.mshrValid && !ps.valid
  val cpuEntryOpen = initDone && !cpuRetry && !recheckIssued && !mmio.io.active && !acquireBusy &&
    (!probe.io.pending.valid || rsvProbeHeld) && !wholeBusy && (!needsDrain || olderDrained)
  val canPtw = initDone && resourcesClear && !ptwOutstanding && !ptwConflict && !ps.valid &&
    (!cpu2.valid || cpuRetry) && (!cpu1.valid || cpuRetry) && !internal1.valid && !internal2.valid
  // Do not start a whole-line read across the two local RMW beats. During
  // miss/requery waits this guard opens again, so grants can depend on probes.
  val localAmo = Wire(Bool())

  // A non-line internal query reserves its completion lane until S2. Only
  // beats of the already-owned whole-line operation overlap in that lane.
  when(initDone && (wholeBusy || (!internal1.valid && !internal2.valid))) {
    when(probe.io.s0Req.valid && !localAmo && (!wholeBusy || wholeOwner === L1Src.Probe)) {
      candidate := true.B; selected := L1Src.Probe
      s0Req.hasPaddr := true.B
      s0Req.paddr := probe.io.pending.addr ## 0.U(p.offBits.W)
      s0Req.physicalAddress := s0Req.paddr
      s0Req.idx := idx(s0Req.paddr)
      s0Req.word := probe.io.s0Req.bits
      s0Req.beat := probe.io.s0Req.bits
      s0Req.lastBeat := probe.io.s0Req.bits === (p.wordsPerLine - 1).U
    }.elsewhen(miss.io.s0Req.valid && miss.io.s0Req.bits.install && (!wholeBusy || wholeOwner === L1Src.Refill)) {
      candidate := true.B; selected := L1Src.Refill
      s0Req.hasPaddr := true.B
      s0Req.idx := miss.io.s0Req.bits.idx
      s0Req.word := miss.io.s0Req.bits.beat
      s0Req.beat := miss.io.s0Req.bits.beat
      s0Req.lastBeat := miss.io.s0Req.bits.installIsAckE || s0Req.beat === (p.wordsPerLine - 1).U
    }.elsewhen(miss.io.s0Req.valid && miss.io.s0Req.bits.wbRead && (!wholeBusy || wholeOwner === L1Src.WbRead)) {
      candidate := true.B; selected := L1Src.WbRead
      s0Req.hasPaddr := true.B
      s0Req.idx := miss.io.s0Req.bits.idx
      s0Req.word := miss.io.s0Req.bits.beat
      s0Req.beat := miss.io.s0Req.bits.beat
      s0Req.lastBeat := s0Req.beat === (p.wordsPerLine - 1).U
    }.elsewhen(!wholeBusy) {
      when(miss.io.s0Req.valid && miss.io.s0Req.bits.replay && !replayConflict && !ps.valid) {
        candidate := true.B; selected := L1Src.Replay
        s0Req := miss.io.s0Req.bits.replayReq
      }.elsewhen(io.ptw.req.valid && canPtw) {
        candidate := true.B; selected := L1Src.Ptw
        s0Req.hasPaddr := true.B
        s0Req.physicalAddress := io.ptw.req.bits.paddr
        s0Req.paddr := io.ptw.req.bits.paddr(p.paddrBits - 1, 0)
        s0Req.idx := idx(s0Req.paddr)
        s0Req.word := word(s0Req.paddr)
        s0Req.core.op := L1DOp.Load
        s0Req.core.size := 3.U
      }.elsewhen(retryReady && !retryConflict && io.tlb.req.ready) {
        candidate := true.B; selected := L1Src.Recheck
        s0Req := cpu2.req
        s0Req.hasPaddr := false.B
      }.elsewhen(io.core.req.valid && cpuEntryOpen && !cpuConflict && !cpuKill) {
        candidate := true.B; selected := L1Src.Cpu
        s0Req.core := io.core.req.bits
        s0Req.idx := idx(io.core.req.bits.vaddr)
        s0Req.word := word(io.core.req.bits.vaddr)
        when(io.core.req.bits.op === L1DOp.Fence) { s0Req.hasPaddr := true.B }
      }
    }
  }
  s0Req.src := selected
  val isCpuIssue = selected === L1Src.Cpu
  val needsTlb = (isCpuIssue || selected === L1Src.Recheck) && !s0Req.hasPaddr
  val cpuFire = candidate && isCpuIssue && cpu1Advance && (!needsTlb || io.tlb.req.ready)
  val internalFire = candidate && !isCpuIssue && internal1Advance && (!needsTlb || io.tlb.req.ready)
  val s0Fire = cpuFire || internalFire
  io.core.req.ready := cpuFire
  io.ptw.req.ready := s0Fire && selected === L1Src.Ptw
  io.tlb.req.valid := candidate && needsTlb && Mux(isCpuIssue, cpu1Advance, internal1Advance) && !cpuKill
  io.tlb.req.bits.vaddr := s0Req.core.vaddr
  io.tlb.req.bits.cmd := Mux(storeLike(s0Req.core.op), MmuCmd.Store, MmuCmd.Load)
  io.tlb.kill := cpuKill
  when(cpuFire && (io.core.req.bits.op === L1DOp.AMO || (incomingAtomic && io.core.req.bits.aq))) {
    acquireBusy := true.B
  }
  miss.io.s0Grant := s0Fire && (selected === L1Src.Refill || selected === L1Src.WbRead || selected === L1Src.Replay)
  probe.io.s0Grant := s0Fire && selected === L1Src.Probe
  when(s0Fire && selected === L1Src.Recheck) { recheckIssued := true.B }
  when(io.ptw.req.fire) { ptwOutstanding := true.B }

  val wholeIssue = internalFire && (selected === L1Src.Probe || selected === L1Src.Refill || selected === L1Src.WbRead)
  when(wholeIssue && !wholeBusy) { wholeBusy := true.B; wholeOwner := selected }
  when(internal2.valid && internalAdvance && internal2.req.lastBeat &&
    internal2.req.src === wholeOwner) { wholeBusy := false.B }

  val tagRead = tags.read(s0Req.idx, s0Fire)
  val dataRead = VecInit(data.map(_.read(s0Req.idx ## s0Req.word, s0Fire).asUInt))
  val cpuFresh = RegNext(cpuFire, false.B)
  val cpuTagHeld = Reg(Vec(p.ways, new L1TagEntry(p)))
  val cpuDataHeld = Reg(Vec(p.ways, UInt(64.W)))
  val cpuTlbHeld = Reg(new TlbResp)
  val cpuTlbValidHeld = RegInit(false.B)
  when(cpuFresh) {
    cpuTagHeld := tagRead; cpuDataHeld := dataRead
    cpuTlbHeld := io.tlb.resp.bits; cpuTlbValidHeld := io.tlb.resp.valid
  }
  val cpuTags = Mux(cpuFresh, tagRead, cpuTagHeld)
  val cpuData = Mux(cpuFresh, dataRead, cpuDataHeld)
  val cpuTlb = Mux(cpuFresh, io.tlb.resp.bits, cpuTlbHeld)
  val cpuTlbValid = Mux(cpuFresh, io.tlb.resp.valid, cpuTlbValidHeld)

  val internalFresh = RegNext(internalFire, false.B)
  val internalTagHeld = Reg(Vec(p.ways, new L1TagEntry(p)))
  val internalDataHeld = Reg(Vec(p.ways, UInt(64.W)))
  val internalTlbHeld = Reg(new TlbResp)
  val internalTlbValidHeld = RegInit(false.B)
  when(internalFresh) {
    internalTagHeld := tagRead; internalDataHeld := dataRead
    internalTlbHeld := io.tlb.resp.bits; internalTlbValidHeld := io.tlb.resp.valid
  }
  val internalTags = Mux(internalFresh, tagRead, internalTagHeld)
  val internalData = Mux(internalFresh, dataRead, internalDataHeld)
  val internalTlb = Mux(internalFresh, io.tlb.resp.bits, internalTlbHeld)
  val internalTlbValid = Mux(internalFresh, io.tlb.resp.valid, internalTlbValidHeld)

  when(cpu1Advance) {
    cpu1.valid := cpuFire
    cpu1.req := s0Req
    cpu1.snapInvalid := false.B
  }
  when(cpuAdvance) {
    cpu2.valid := cpu1.valid && !cpuKill
    cpu2.req := cpu1.req
    cpu2.physicalAddress := Mux(cpu1.req.hasPaddr, cpu1.req.physicalAddress, cpuTlb.paddr)
    cpu2.paddr := Mux(cpu1.req.hasPaddr, cpu1.req.paddr, cpuTlb.paddr(p.paddrBits - 1, 0))
    cpu2.pageFault := !cpu1.req.hasPaddr && cpuTlbValid && cpuTlb.pageFault
    cpu2.accessFault := !cpu1.req.hasPaddr && cpuTlbValid && cpuTlb.accessFault
    cpu2.translationMiss := !cpu1.req.hasPaddr && (!cpuTlbValid || cpuTlb.miss)
    cpu2.snapInvalid := cpu1.snapInvalid
    cpu2.needsRecheck := false.B
    cpu2.tagVec := cpuTags
    cpu2.dataVec := cpuData
  }
  when(internal1Advance) {
    internal1.valid := internalFire
    internal1.req := s0Req
    internal1.snapInvalid := false.B
  }
  when(internalAdvance) {
    internal2.valid := internal1.valid
    internal2.req := internal1.req
    internal2.physicalAddress := Mux(internal1.req.hasPaddr, internal1.req.physicalAddress, internalTlb.paddr)
    internal2.paddr := Mux(internal1.req.hasPaddr, internal1.req.paddr, internalTlb.paddr(p.paddrBits - 1, 0))
    internal2.pageFault := !internal1.req.hasPaddr && internalTlbValid && internalTlb.pageFault
    internal2.accessFault := !internal1.req.hasPaddr && internalTlbValid && internalTlb.accessFault
    internal2.translationMiss := !internal1.req.hasPaddr && (!internalTlbValid || internalTlb.miss)
    internal2.snapInvalid := internal1.snapInvalid
    internal2.needsRecheck := false.B
    internal2.tagVec := internalTags
    internal2.dataVec := internalData
  }
  val recheckReturns = internal2.valid && internal2.req.src === L1Src.Recheck && internalAdvance
  when(recheckReturns && cpu2.valid && !io.core.s2Kill) {
    cpu2 := internal2
    cpu2.req.src := L1Src.Cpu
    cpu2.needsRecheck := false.B
    recheckIssued := false.B
  }
  when(cpuKill) { cpu1.valid := false.B }
  when(io.core.s2Kill) { cpu2.valid := false.B; recheckIssued := false.B }
  when(io.core.s2Kill && internal1.req.src === L1Src.Recheck) { internal1.valid := false.B }
  when(io.core.s2Kill && internal2.req.src === L1Src.Recheck) { internal2.valid := false.B }

  // One S2 executor owns side effects. Completion work has priority over a
  // CPU snapshot; the CPU slots hold rather than losing a backend response.
  val s2 = Wire(new L1S2(p))
  s2 := Mux(internal2.valid, internal2, cpu2)
  val fromInternal = internal2.valid
  val replay = fromInternal && s2.req.src === L1Src.Replay
  val ptw = s2.req.src === L1Src.Ptw || (replay && s2.req.replayPtw)
  val rechecking = fromInternal && s2.req.src === L1Src.Recheck
  val normalInternal = fromInternal && !replay && !ptw && !rechecking
  val hitVec = VecInit((0 until p.ways).map(w => s2.tagVec(w).state =/= L1State.I &&
    s2.tagVec(w).tag === tag(s2.paddr) && !(miss.io.status.wayLocked &&
      miss.io.status.mshrWay === w.U && idx(miss.io.status.mshrLineAddr ## 0.U(p.offBits.W)) === s2.req.idx &&
      !(s2.req.src === L1Src.Probe && upgrading && miss.io.status.mshrState === MshrState.Wait &&
        !probe.io.pending.owner))))
  val hit = hitVec.asUInt.orR
  val hitWay = OHToUInt(hitVec)
  val hitState = s2.tagVec(hitWay).state
  val writable = hitState === L1State.E || hitState === L1State.M
  localAmo := amoRmw || (!fromInternal && cpu2.valid && cpu2.req.core.op === L1DOp.AMO &&
    hit && writable && !atomicWait && !cpuRetry)
  val hitWord = s2.dataVec(hitWay)
  val isStore = !ptw && storeLike(s2.req.core.op)
  val sameMshr = miss.io.status.mshrValid && miss.io.status.mshrLineAddr === line(s2.paddr)
  val sameWb = miss.io.status.wbValid && miss.io.status.wbLineAddr === line(s2.paddr)
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
  val upgrade = hit && hitState === L1State.S && (isStore || isLr)
  val allocWay = Mux(upgrade, hitWay, victimWay)
  val victimEntry = if (p.ways == 1) s2.tagVec(0) else s2.tagVec(allocWay)
  val victimValid = !upgrade && victimEntry.state =/= L1State.I
  val victimAddress = if (p.idxBits == 0) victimEntry.tag else victimEntry.tag ## s2.req.idx

  val installNow = internalFire && selected === L1Src.Refill
  val installLast = installNow && s0Req.lastBeat
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
  val canAllocate = miss.io.status.canAllocate && !ptwOutstanding && !tagBusy && !probe.io.s0Req.valid &&
    (!victimValid || !miss.io.status.wbValid)
  val ptwCanAllocate = miss.io.status.canAllocate && !tagBusy && (!victimValid || !miss.io.status.wbValid)

  pma.io.query.addr := s2.physicalAddress
  pma.io.query.sizeLog2 := Mux(ptw, 3.U, s2.req.core.size)
  pma.io.query.accessType := Mux(isStore, flow.platform.PMAAccessType.Store, flow.platform.PMAAccessType.Load)
  pmp.io.addr := s2.physicalAddress
  pmp.io.sizeLog2 := Mux(ptw, 3.U, s2.req.core.size)
  pmp.io.access := Mux(isStore, BreezeMmuAccess.Store, BreezeMmuAccess.Load)
  pmp.io.privilege := Mux(ptw, 1.U, Mux(io.core.csr.mprv, io.core.csr.mpp, io.core.csr.privilege))
  pmp.io.context := io.core.csr
  val atomicDenied = !ptw && ((s2.req.core.op === L1DOp.LR || s2.req.core.op === L1DOp.SC) && !pma.io.result.rsrvOk ||
    s2.req.core.op === L1DOp.AMO && !pma.io.result.amoOk)
  val highAddress = (s2.physicalAddress >> p.paddrBits).orR
  val alignmentMask = (1.U(9.W) << Mux(ptw, 3.U, s2.req.core.size)) - 1.U
  val misaligned = (s2.req.core.vaddr(2, 0) & alignmentMask(2, 0)).orR && !ptw
  val permissionFault = s2.pageFault || s2.accessFault || highAddress || !pma.io.result.allowed ||
    !pmp.io.allowed || atomicDenied || (ptw && pma.io.result.device) ||
    (pma.io.result.device && !ptw && s2.req.core.op =/= L1DOp.Load && s2.req.core.op =/= L1DOp.Store)
  val outcome = WireDefault(L1S2Outcome.None)
  when(s2.valid) {
    when(normalInternal || rechecking) { outcome := L1S2Outcome.Internal }
      .elsewhen(replay) {
        // Replay has already passed all access checks before commitment.
        when(blockingAtomic && atomicWait && s2.req.replayError) { outcome := L1S2Outcome.Exc }
          .elsewhen(isAmo && atomicWait) { outcome := L1S2Outcome.ToAmo }
          .elsewhen(!s2.req.replayError && s2.req.core.op === L1DOp.Store && !psRoom) {
          outcome := L1S2Outcome.Hold
        }.otherwise { outcome := Mux(ptw, L1S2Outcome.PtwResp, L1S2Outcome.Done) }
      }.elsewhen(!fromInternal && (internal1.valid || wholeBusy)) { outcome := L1S2Outcome.Hold }
      .elsewhen(amoRmw) { outcome := Mux(psCompletes, L1S2Outcome.Done, L1S2Outcome.Hold) }
      .elsewhen(atomicWait) { outcome := L1S2Outcome.Hold }
      .elsewhen(s2.req.core.op === L1DOp.Fence && !ptw) {
        outcome := Mux(io.core.drained, L1S2Outcome.Done, L1S2Outcome.Hold)
      }.elsewhen(s2.translationMiss || s2.snapInvalid || s2.needsRecheck ||
        (!fromInternal && externalMutation && s2.req.idx === externalSet)) { outcome := L1S2Outcome.Recheck
      }.elsewhen(misaligned || permissionFault) { outcome := L1S2Outcome.Exc }
      .elsewhen(pma.io.result.device) {
        outcome := Mux(mmio.io.done.valid, Mux(mmio.io.done.bits.error, L1S2Outcome.Exc, L1S2Outcome.Done), L1S2Outcome.ToMmio)
      }.elsewhen(isSc) {
        // Permissions precede reservation failure; SC never allocates MSHR.
        outcome := Mux(!scSuccess || psRoom, L1S2Outcome.Done, L1S2Outcome.Hold)
      }.elsewhen(sameMshr || sameWb) { outcome := L1S2Outcome.Hold }
      .elsewhen(ptw) {
        outcome := Mux(hit, L1S2Outcome.PtwResp, Mux(ptwCanAllocate, L1S2Outcome.Mshr, L1S2Outcome.Hold))
      }.elsewhen(s2.req.core.op === L1DOp.Load) {
        outcome := Mux(hit, L1S2Outcome.Done, Mux(canAllocate, L1S2Outcome.Mshr, L1S2Outcome.Hold))
      }.elsewhen(s2.req.core.op === L1DOp.Store) {
        outcome := Mux(hit && writable, Mux(psRoom, L1S2Outcome.Done, L1S2Outcome.Hold),
          Mux(canAllocate, L1S2Outcome.Mshr, L1S2Outcome.Hold))
      }.elsewhen(isLr || isAmo) {
        outcome := Mux(hit && writable,
          Mux(isLr, L1S2Outcome.Done, Mux(psRoom, L1S2Outcome.ToAmo, L1S2Outcome.Hold)),
          Mux(canAllocate, L1S2Outcome.Mshr, L1S2Outcome.Hold))
      }.otherwise { outcome := L1S2Outcome.Hold }
  }
  val undecided = outcome === L1S2Outcome.Hold || outcome === L1S2Outcome.Recheck ||
    outcome === L1S2Outcome.ToMmio || outcome === L1S2Outcome.ToAmo ||
    (blockingAtomic && outcome === L1S2Outcome.Mshr)
  // Admission reserves the single internal result's capacity. In particular,
  // PTW is accepted only with free miss resources, and replay waits for PS.
  // An internal wait here would prevent that same lane finishing the miss.
  internalHold := false.B
  val atomicReplayResp = replay && atomicWait && (isLr || s2.req.replayError)
  cpuHold := cpu2.valid && !atomicReplayResp && (internal2.valid || undecided)
  io.core.s2Hold := cpuHold
  // Resource waits must requery SRAM when they resume; do not reuse a tag
  // snapshot acquired before the older refill/probe/writeback completed.
  // A hit in S still needs the MSHR for GetM. Mark that wait as well, so a
  // held younger S1 Store cannot prevent the probe that frees L2's set.
  when(cpu2.valid && !atomicWait && !amoRmw && cpu2.req.core.op =/= L1DOp.Fence && !fromInternal && outcome === L1S2Outcome.Hold &&
    (sameMshr || sameWb || ((!hit || upgrade) && !miss.io.status.canAllocate))) { cpu2.needsRecheck := true.B }

  val rawLoad = Mux(!fromInternal && mmio.io.done.valid, mmio.io.done.bits.rdata, hitWord)
  val loadData = formatLoad(rawLoad, s2.paddr, s2.req.core.size, s2.req.core.signed, s2.req.core.isFlw)
  val atomicData = formatLoad(hitWord, s2.paddr, s2.req.core.size, true.B, false.B)
  amoAlu.io.func := s2.req.core.amoFunc
  amoAlu.io.isWord := s2.req.core.size === 2.U
  amoAlu.io.oldOperand := hitWord >> (s2.paddr(2, 0) ## 0.U(3.W))
  amoAlu.io.rs2 := s2.req.core.wdata
  val cpuDecided = atomicReplayResp || (cpu2.valid && !fromInternal &&
    (outcome === L1S2Outcome.Done || (outcome === L1S2Outcome.Mshr && !blockingAtomic) || outcome === L1S2Outcome.Exc))
  io.core.resp.valid := cpuDecided
  io.core.resp.bits.kind := MuxCase(L1DRespKind.Done, Seq(
    (outcome === L1S2Outcome.Mshr) -> L1DRespKind.Mshr,
    (outcome === L1S2Outcome.Exc) -> L1DRespKind.Exc))
  io.core.resp.bits.data := Mux(amoRmw, amoOld, Mux(isSc, Mux(scSuccess, 0.U, 1.U), Mux(isLr, atomicData, loadData)))
  io.core.resp.bits.excCause := Mux(misaligned, Mux(isStore, 6.U, 4.U),
    Mux(s2.pageFault, Mux(isStore, 15.U, 13.U), Mux(isStore, 7.U, 5.U)))
  io.core.resp.bits.tval := s2.req.core.vaddr

  val allocates = s2.valid && outcome === L1S2Outcome.Mshr && (ptw || !io.core.s2Kill)
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

  io.ptw.resp.valid := s2.valid && ptw && (outcome === L1S2Outcome.PtwResp || outcome === L1S2Outcome.Exc)
  io.ptw.resp.bits.data := hitWord
  io.ptw.resp.bits.accessFault := outcome === L1S2Outcome.Exc || (replay && s2.req.replayError)
  when(io.ptw.resp.valid) { ptwOutstanding := false.B }
  miss.io.wbReadBeat.valid := internal2.valid && internal2.req.src === L1Src.WbRead
  miss.io.wbReadBeat.bits := (if (p.ways == 1) internal2.dataVec(0) else internal2.dataVec(miss.io.status.wbWay))
  probe.io.s2Beat.valid := internal2.valid && internal2.req.src === L1Src.Probe
  probe.io.s2Beat.bits.beat := internal2.req.beat
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
  val cpu2StoreProbe = cpu2.valid && storeLike(cpu2.req.core.op) && line(cpu2.paddr) === probeLine
  probe.io.hold := holdForMiss || holdForWb || psSameProbe || rsvProbeHeld ||
    (amoRmw && line(cpu2.paddr) === probeLine)
  // Registered wait reasons avoid feeding S2's arbitration result back into
  // its own ready decision. A newly blocked store is parked on the next edge.
  // An LR/AMO waiting for its grant has no local write in flight. In
  // particular, a sharer Inv during GetM upgrade must be able to produce
  // the Ack that the grant depends on. Miss/PS/RMW holds still apply above.
  val cpuAlreadyWaiting = cpuRetry || atomicWait || wholeBusy || internal1.valid || internal2.valid
  probe.io.startOk := (!cpu1StoreProbe || cpuAlreadyWaiting) &&
    (!cpu2StoreProbe || cpuRetry || atomicWait) && !psSameProbe
  probe.io.initDone := initDone

  val amoStarts = s2.valid && isAmo && outcome === L1S2Outcome.ToAmo &&
    (!replay || atomicWait) && !s2.req.replayError && !io.core.s2Kill
  val stores = s2.valid && !ptw && !pma.io.result.device && !(replay && s2.req.replayError) &&
    ((s2.req.core.op === L1DOp.Store && outcome === L1S2Outcome.Done && (replay || !io.core.s2Kill)) ||
      (isSc && scSuccess && outcome === L1S2Outcome.Done && !io.core.s2Kill) || amoStarts)
  when(psCompletes) { ps.valid := false.B; psAtomic := false.B }
  when(stores) {
    ps.valid := true.B
    psAtomic := amoStarts
    ps.idx := s2.req.idx
    ps.word := s2.req.word
    ps.way := hitWay
    ps.paddr := s2.paddr
    val bytes = (1.U(4.W) << s2.req.core.size)(3, 0)
    ps.mask := ((((1.U(9.W) << bytes) - 1.U)(7, 0)) << s2.paddr(2, 0))(7, 0)
    ps.data := (Mux(amoStarts, amoAlu.io.newOperand, s2.req.core.wdata) << (s2.paddr(2, 0) ## 0.U(3.W)))(63, 0)
    ps.setDirty := hitState === L1State.E
  }
  when(amoStarts) { amoRmw := true.B; amoOld := atomicData }
  when(rsvTimer =/= 0.U) { rsvTimer := rsvTimer - 1.U }
  val lrCompletes = cpuDecided && isLr && outcome === L1S2Outcome.Done && !io.core.s2Kill
  when(lrCompletes) { rsvValid := true.B; rsvLine := line(s2.paddr); rsvTimer := p.rsvWindow.U }
  val scCompletes = cpuDecided && isSc && !io.core.s2Kill
  val probeStarts = probe.io.s0Grant && probe.io.s0Req.bits === 0.U
  when(io.core.trapClearRsv || scCompletes ||
    (probeStarts && ((rsvValid && probeLine === rsvLine) || (lrCompletes && probeLine === line(s2.paddr)))) ||
    (allocates && victimValid && rsvValid && victimAddress === rsvLine)) {
    rsvValid := false.B; rsvTimer := 0.U
  }

  val tagWrite = WireDefault(false.B)
  val tagIdx = WireDefault(0.U(p.idxW.W))
  val tagWay = WireDefault(0.U(p.wayBits.W))
  val tagValue = WireDefault(0.U.asTypeOf(new L1TagEntry(p)))
  when(psWrites && ps.setDirty) {
    tagWrite := true.B; tagIdx := ps.idx; tagWay := ps.way
    tagValue.tag := tag(ps.paddr); tagValue.state := L1State.M
  }
  when(allocates && victimValid) {
    tagWrite := true.B; tagIdx := s2.req.idx; tagWay := allocWay
    tagValue := victimEntry; tagValue.state := L1State.I
  }
  when(probe.io.tagUpdate.valid) {
    tagWrite := true.B; tagIdx := probeIdx; tagWay := probe.io.tagUpdate.bits.way
    tagValue.tag := tag(probePaddr); tagValue.state := probe.io.tagUpdate.bits.newState
  }
  when(installLast) {
    tagWrite := true.B; tagIdx := miss.io.s0Req.bits.idx; tagWay := miss.io.s0Req.bits.way
    tagValue := miss.io.s0Req.bits.installTag
    when(tagValue.state =/= L1State.I) {
      plru(tagIdx) := TreePlru.touch(plru(tagIdx), tagWay, p.ways)
    }
  }
  // Use one masked write port for both initialization and runtime updates.
  // Mixing an unmasked port with a masked port loses the per-way mask when
  // the mutually exclusive ports are combined by memory lowering.
  when(!initDone || tagWrite) {
    tags.write(Mux(initDone, tagIdx, initIdx),
      VecInit(Seq.fill(p.ways)(Mux(initDone, tagValue, 0.U.asTypeOf(tagValue)))),
      Mux(initDone, UIntToOH(tagWay, p.ways), Fill(p.ways, 1.U(1.W))).asBools)
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
  when(s2.valid && hit && outcome === L1S2Outcome.Done && !io.core.s2Kill && !installLast) {
    plru(s2.req.idx) := TreePlru.touch(plru(s2.req.idx), hitWay, p.ways)
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
    when(cpu2.valid && cpu2.req.idx === changedSet && cpuHold && !recheckReturns) { cpu2.snapInvalid := true.B }
    when(internalFire && selected === L1Src.Recheck && s0Req.idx === changedSet) { internal1.snapInvalid := true.B }
    when(internal1.valid && internal1.req.src === L1Src.Recheck && internal1.req.idx === changedSet && internalAdvance) {
      internal2.snapInvalid := true.B
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

  mmio.io.start.valid := !fromInternal && cpu2.valid && outcome === L1S2Outcome.ToMmio && !mmio.io.active && !io.core.s2Kill
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
  io.events.hit_under_miss := cpuDecided && outcome === L1S2Outcome.Done && hit && miss.io.status.mshrValid && !sameMshr
  io.events.mshr_busy_cycles := miss.io.status.mshrValid
  io.events.mshr_full_stall := cpuHold && !hit && !miss.io.status.canAllocate
  io.events.same_line_stall := cpuHold && (sameMshr || sameWb)
  io.events.s0_conflict_stall := io.core.req.valid && cpuConflict && cpuEntryOpen
  io.events.writeback_dirty := miss.io.put.fire && miss.io.put.bits.hasData
  io.events.writeback_clean := miss.io.put.fire && !miss.io.put.bits.hasData
  io.events.probe_received := io.coh.snp.fire
  io.events.probe_held_cycles := probe.io.pending.valid && probe.io.hold
  io.events.lr_count := lrCompletes
  io.events.sc_fail := scCompletes && outcome === L1S2Outcome.Done && !scSuccess
  io.events.mmio_read := mmio.io.axi.ar.fire
  io.events.mmio_write := mmio.io.axi.aw.fire
  io.events.mmio_cycles := mmio.io.busy
  assert(PopCount(hitVec) <= 1.U, "two ways hit the same tag")
  when(allocates) { assert(!miss.io.status.wayLocked, "allocation selected a locked way") }
  when(stores) { assert(psRoom && hit && writable, "Store committed without PS capacity/ownership") }
  when(lrCompletes) { assert(hit && writable, "LR reservation without exclusive ownership") }
  when(atomicReplayResp) { assert(cpu2.valid, "atomic replay without held CPU owner") }
  when(internal2.valid) {
    assert(!undecided || (outcome === L1S2Outcome.ToAmo && psRoom),
      "internal result has no reserved completion capacity")
  }
  when(amoRmw) { assert(psCompletes, "AMO RMW protection exceeded two local beats") }
  when(cpu1.valid && !cpu1Advance) { assert(cpuHold, "CPU S1 held without s2Hold") }
}
