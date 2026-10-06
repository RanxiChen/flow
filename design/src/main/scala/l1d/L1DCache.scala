package flow.l1d

import chisel3._
import chisel3.util._
import flow.cache.BreezeAmoAlu
import flow.coherence._
import flow.config.BreezeMemGeometry
import flow.interface.{L1DOp, L1DRespKind}
import flow.mmu.sv39.{MmuCmd, TreePlru}

/** Breeze v1 L1D top: S0–S2 + PS, arrays, S0 arbitration, S2 decision (l1d-rtl-spec).
  *
  * SKELETON (feat/v1-mem-skeleton). Structure, registers, arrays and the
  * cycle-level skeleton follow the spec; every `TODO` is filled in during the
  * simulate-and-fix phase. Pipeline rule (B01 ruling): S1/S2 hold only while
  * S2 cannot decide, and every such cycle raises core.s2Hold.
  */
class L1DCache(g: BreezeMemGeometry) extends Module {
  val p = L1DParams(g)
  val io = IO(new L1DIO(p))

  // ===========================================================================
  // Helpers
  // ===========================================================================
  private def idxOfAddr(a: UInt): UInt = a(p.offBits + p.idxW - 1, p.offBits)
  private def wordOfAddr(a: UInt): UInt = a(p.offBits - 1, 3)
  private def lineOf(pa: UInt): UInt = pa(p.paddrBits - 1, p.offBits)
  private def tagOfPa(pa: UInt): UInt = pa(p.paddrBits - 1, p.offBits + p.idxBits)
  private def confKey(a: UInt): UInt = a(p.conflictHi, p.conflictLo)
  private def isStoreLike(op: L1DOp.Type): Bool = op === L1DOp.Store || op === L1DOp.SC || op === L1DOp.AMO

  // ===========================================================================
  // Arrays (§2)
  // ===========================================================================
  val tagArr = SyncReadMem(p.sets, Vec(p.ways, new L1TagEntry(p)))
  val dataArr = Seq.fill(p.ways)(SyncReadMem(p.sets * p.wordsPerLine, Vec(8, UInt(8.W))))
  val plru = RegInit(VecInit(Seq.fill(p.sets)(0.U(p.plruBits.W))))

  // Reset initialisation: one set per cycle, all ways I (§2).
  val initIdx = RegInit(0.U((p.idxBits + 1).W))
  val initDone = initIdx === p.sets.U

  // ===========================================================================
  // Sub-blocks
  // ===========================================================================
  val miss = Module(new L1DMiss(p))
  val probe = Module(new L1DProbe(p))
  val mmio = Module(new L1DMmio(p))
  val amoAlu = Module(new BreezeAmoAlu)
  val pma = Module(new flow.platform.PMAChecker)
  val pmp = Module(new flow.mmu.BreezePmpChecker(64))

  // ===========================================================================
  // Pipeline registers (§3)
  // ===========================================================================
  val s1 = RegInit(0.U.asTypeOf(new L1S1(p)))
  val s2 = RegInit(0.U.asTypeOf(new L1S2(p)))
  val ps = RegInit(0.U.asTypeOf(new L1PendingStore(p)))

  // Translation wait (§7.1): X and Y. TODO: entry/exit and in-order reissue.
  val xlatWait = Reg(Vec(2, new L1PipeReq(p)))
  val xlatValid = RegInit(VecInit(Seq.fill(2)(false.B)))
  val xlatClosed = xlatValid.asUInt.orR

  // Recheck queue (§6.1 item 5): snapshot-invalidated or released-hold requests.
  // TODO: depth and age order; v1 needs at most S1+S2 = 2 entries.
  val recheck = Reg(Vec(2, new L1PipeReq(p)))
  val recheckValid = RegInit(VecInit(Seq.fill(2)(false.B)))

  // Reservation (§8.1)
  val rsvValid = RegInit(false.B)
  val rsvLine = Reg(UInt(p.lineAddrBits.W))
  val rsvTimer = RegInit(0.U(log2Ceil(p.rsvWindow + 1).W))

  // AMO exclusive path (§8.3)
  val amoState = RegInit(AmoState.Idle)

  // ===========================================================================
  // S2 decision outputs used by earlier stages (computed below)
  // ===========================================================================
  val s2Stall = Wire(Bool())        // S2 holds this cycle
  val s2Advance = !s2Stall          // S2 consumes its slot (or is empty)
  val s1Advance = !s1.valid || s2Advance
  val s0CanIssue = s1Advance

  // ===========================================================================
  // S0: arbitration (§6.1)
  // ===========================================================================
  val s0Sel = Wire(L1Src())
  val s0Req = Wire(new L1PipeReq(p))
  val s0Fire = Wire(Bool())
  s0Req := 0.U.asTypeOf(s0Req)

  // Conflict check (§4): S1/S2 store-like by vaddr[11:3], PS by pa[11:3].
  private def conflicts(key: UInt): Bool = {
    val s1c = s1.valid && isStoreLike(s1.req.core.op) && confKey(s1.req.core.vaddr) === key
    val s2c = s2.valid && isStoreLike(s2.req.core.op) && confKey(s2.req.core.vaddr) === key
    val psc = ps.valid && confKey(ps.paddr) === key
    s1c || s2c || psc
  }

  val cpuKey = confKey(io.core.req.bits.vaddr)
  val cpuIsLoadLike = io.core.req.bits.op === L1DOp.Load || io.core.req.bits.op === L1DOp.LR
  val cpuConflict = cpuIsLoadLike && conflicts(cpuKey)
  val ptwConflict = conflicts(io.ptw.req.bits.paddr)
  // TODO: replay and recheck also go through the conflict check.

  val probeWantsS0 = probe.io.s0Req.valid
  // A whole-line op owns S0 until its last beat; while a probe waits, rechecks
  // and CPU requests are closed (§10.1).
  val wholeLineBusy = WireDefault(false.B) // TODO: beat counter for install/wbRead/probe
  val cpuEntryOpen = initDone && !xlatClosed && !mmio.io.active && amoState === AmoState.Idle &&
    !probe.io.pending.valid && !wholeLineBusy
  // TODO: aq/rl gating (§8.2): rl waits drained, aq closes entry until done.

  val cand = Wire(Vec(L1Src.all.length, Bool()))
  cand(L1Src.Init.litValue.toInt) := !initDone
  cand(L1Src.Probe.litValue.toInt) := probeWantsS0
  cand(L1Src.Refill.litValue.toInt) := miss.io.s0Req.valid && miss.io.s0Req.bits.install
  cand(L1Src.WbRead.litValue.toInt) := miss.io.s0Req.valid && miss.io.s0Req.bits.wbRead
  cand(L1Src.Replay.litValue.toInt) := miss.io.s0Req.valid && miss.io.s0Req.bits.replay
  cand(L1Src.Ptw.litValue.toInt) := io.ptw.req.valid && !ptwConflict && initDone
  cand(L1Src.Recheck.litValue.toInt) := recheckValid.asUInt.orR && !probe.io.pending.valid
  cand(L1Src.Cpu.litValue.toInt) := io.core.req.valid && cpuEntryOpen && !cpuConflict
  val selOH = PriorityEncoderOH(cand)
  val anyCand = cand.asUInt.orR
  s0Sel := L1Src(OHToUInt(selOH))

  // CPU and recheck requests need the dTLB in the same cycle.
  val needsTlb = s0Sel === L1Src.Cpu || s0Sel === L1Src.Recheck
  s0Fire := anyCand && s0CanIssue && (!needsTlb || io.tlb.req.ready)

  // Build the S0 request from the winning source.
  switch(s0Sel) {
    is(L1Src.Cpu) {
      s0Req.core := io.core.req.bits
      s0Req.idx := idxOfAddr(io.core.req.bits.vaddr)
      s0Req.word := wordOfAddr(io.core.req.bits.vaddr)
    }
    is(L1Src.Ptw) {
      s0Req.paddr := io.ptw.req.bits.paddr
      s0Req.hasPaddr := true.B
      s0Req.idx := idxOfAddr(io.ptw.req.bits.paddr)
      s0Req.word := wordOfAddr(io.ptw.req.bits.paddr)
    }
    is(L1Src.Refill, L1Src.WbRead) {
      s0Req.hasPaddr := true.B
      s0Req.idx := miss.io.s0Req.bits.idx
      s0Req.word := miss.io.s0Req.bits.beat
      s0Req.beat := miss.io.s0Req.bits.beat
      s0Req.lastBeat := miss.io.s0Req.bits.beat === (p.wordsPerLine - 1).U
    }
    is(L1Src.Replay) {
      s0Req := miss.io.s0Req.bits.replayReq
    }
    is(L1Src.Probe) {
      s0Req.hasPaddr := true.B
      s0Req.paddr := probe.io.pending.addr ## 0.U(p.offBits.W)
      s0Req.idx := probe.io.pending.addr(p.idxW - 1, 0)
      s0Req.word := probe.io.s0Req.bits
      s0Req.beat := probe.io.s0Req.bits
      s0Req.lastBeat := probe.io.s0Req.bits === (p.wordsPerLine - 1).U
    }
    is(L1Src.Recheck) {
      s0Req := recheck(0) // TODO: oldest first
    }
  }
  s0Req.src := s0Sel

  // Handshakes
  io.core.req.ready := s0Fire && s0Sel === L1Src.Cpu && !io.core.s1Kill
  io.ptw.req.ready := s0Fire && s0Sel === L1Src.Ptw
  miss.io.s0Grant := s0Fire && (s0Sel === L1Src.Refill || s0Sel === L1Src.WbRead || s0Sel === L1Src.Replay)
  probe.io.s0Grant := s0Fire && s0Sel === L1Src.Probe

  io.tlb.req.valid := anyCand && needsTlb && s0CanIssue
  io.tlb.req.bits.vaddr := s0Req.core.vaddr
  io.tlb.req.bits.cmd := Mux(isStoreLike(s0Req.core.op), MmuCmd.Store, MmuCmd.Load)
  io.tlb.kill := io.core.s1Kill || io.core.s2Kill

  // Array reads (data address {idx, word}). Install/PS writes are below.
  val readEn = s0Fire && s0Sel =/= L1Src.Init
  val tagRead = tagArr.read(s0Req.idx, readEn)
  val dataRead = VecInit(dataArr.map(_.read(s0Req.idx ## s0Req.word, readEn).asUInt))

  // ===========================================================================
  // S1 (§3): capture TLB result; hold-safe capture of array outputs.
  // ===========================================================================
  val s1Fresh = RegNext(s0Fire && s0Sel =/= L1Src.Init, false.B)
  val s1TagHeld = Reg(Vec(p.ways, new L1TagEntry(p)))
  val s1DataHeld = Reg(Vec(p.ways, UInt(64.W)))
  val s1Tag = Mux(s1Fresh, tagRead, s1TagHeld)
  val s1Data = Mux(s1Fresh, dataRead, s1DataHeld)
  s1TagHeld := s1Tag
  s1DataHeld := s1Data

  val s1Paddr = Mux(s1.req.hasPaddr, s1.req.paddr, io.tlb.resp.bits.paddr(p.paddrBits - 1, 0))
  // TODO: TLB miss in S1 → xlatWait (X, and Y = this cycle's S0 fire) (§7.1).
  // TODO: snapshot invalidation of S1 on same-set install/probe/victim (§5.3).

  when(s1Advance) {
    s1.valid := s0Fire && !(io.core.s1Kill && s0Sel === L1Src.Cpu)
    s1.req := s0Req
  }
  when(io.core.s1Kill || io.core.s2Kill) {
    // s1Kill: CPU request in S1 is dropped; internal sources are unaffected (PTW reads never die).
    when(s1.req.src === L1Src.Cpu || s1.req.src === L1Src.Recheck) { s1.valid := false.B }
  }

  // ===========================================================================
  // S2 (§5): tag compare, PMA/PMP, decision
  // ===========================================================================
  when(s2Advance) {
    s2.valid := s1.valid
    s2.req := s1.req
    s2.paddr := s1Paddr
    s2.pageFault := !s1.req.hasPaddr && io.tlb.resp.bits.pageFault
    s2.accessFault := !s1.req.hasPaddr && io.tlb.resp.bits.accessFault
    s2.tagVec := s1Tag
    s2.dataVec := s1Data
    s2.snapInvalid := false.B
  }

  val s2Tag = tagOfPa(s2.paddr)
  val hitVec = VecInit(s2.tagVec.map(e => e.state =/= L1State.I && e.tag === s2Tag)) // TODO: exclude locked way
  val hit = hitVec.asUInt.orR
  val hitWay = OHToUInt(hitVec)
  val hitState = s2.tagVec(hitWay).state
  val wOk = hitState === L1State.E || hitState === L1State.M
  val hitWord = s2.dataVec(hitWay)

  pma.io.query.addr := s2.paddr
  pma.io.query.sizeLog2 := s2.req.core.size
  pma.io.query.accessType := Mux(isStoreLike(s2.req.core.op), flow.platform.PMAAccessType.Store,
    flow.platform.PMAAccessType.Load)
  pmp.io.addr := s2.paddr
  pmp.io.sizeLog2 := s2.req.core.size
  pmp.io.access := DontCare   // TODO codex: BreezeMmuAccess mapping
  pmp.io.privilege := DontCare // TODO codex: effective privilege from csr (MPRV)
  pmp.io.context := io.core.csr

  val sameLineMshr = miss.io.status.mshrValid && miss.io.status.mshrLineAddr === lineOf(s2.paddr)
  val sameLineWb = miss.io.status.wbValid && miss.io.status.wbLineAddr === lineOf(s2.paddr)
  val isCpuLike = s2.req.src === L1Src.Cpu || s2.req.src === L1Src.Recheck || s2.req.src === L1Src.Replay

  val outcome = WireDefault(L1S2Outcome.None)
  when(s2.valid) {
    when(!isCpuLike && s2.req.src =/= L1Src.Ptw) {
      outcome := L1S2Outcome.Internal
    }.elsewhen(s2.snapInvalid) {
      outcome := L1S2Outcome.Recheck
    }.elsewhen(s2.pageFault || s2.accessFault || !pma.io.result.allowed || !pmp.io.allowed) {
      outcome := L1S2Outcome.Exc // TODO: PTW → ptw.resp.accessFault instead
    }.elsewhen(pma.io.result.device) {
      outcome := L1S2Outcome.ToMmio // TODO: LR/SC/AMO on device → Exc (§5.1 item 5)
    }.elsewhen(s2.req.core.op === L1DOp.AMO && s2.req.src =/= L1Src.Replay) {
      outcome := L1S2Outcome.ToAmo
    }.elsewhen(sameLineMshr || sameLineWb) {
      outcome := L1S2Outcome.Hold
    }.elsewhen(s2.req.src === L1Src.Replay) {
      outcome := L1S2Outcome.Done // replay always hits (assert below)
    }.elsewhen(s2.req.src === L1Src.Ptw) {
      outcome := Mux(hit, L1S2Outcome.PtwResp, Mux(miss.io.status.canAllocate, L1S2Outcome.Mshr, L1S2Outcome.Hold))
    }.otherwise {
      val op = s2.req.core.op
      when(op === L1DOp.Load) {
        outcome := Mux(hit, L1S2Outcome.Done, Mux(miss.io.status.canAllocate, L1S2Outcome.Mshr, L1S2Outcome.Hold))
      }.elsewhen(op === L1DOp.Store) {
        // Store hit E/M → Done into PS; PS busy-and-blocked → Hold. TODO: PS-blocked rule.
        outcome := Mux(hit && wOk, L1S2Outcome.Done,
          Mux(miss.io.status.canAllocate, L1S2Outcome.Mshr, L1S2Outcome.Hold))
      }.elsewhen(op === L1DOp.Fence) {
        outcome := Mux(miss.io.status.mshrValid || ps.valid, L1S2Outcome.Hold, L1S2Outcome.Done)
      }.otherwise {
        outcome := L1S2Outcome.Hold // TODO: LR/SC rows of §5.2
      }
    }
  }
  when(s2.valid && s2.req.src === L1Src.Replay) {
    assert(hit, "MSHR replay must hit (sec 6.2)")
  }

  s2Stall := s2.valid && (outcome === L1S2Outcome.Hold || outcome === L1S2Outcome.ToMmio && true.B)
  // TODO: ToMmio holds until mmio.done; ToAmo holds during the AMO path.

  // ---- Load formatting (§5.4) ----
  private def formatLoad(word: UInt, pa: UInt, size: UInt, signed: Bool, isFlw: Bool): UInt = {
    val shifted = word >> (pa(2, 0) ## 0.U(3.W))
    val b = shifted(7, 0); val h = shifted(15, 0); val w = shifted(31, 0)
    val ext = MuxLookup(size, shifted)(Seq(
      0.U -> Mux(signed, b.asSInt.pad(64).asUInt, b.pad(64)),
      1.U -> Mux(signed, h.asSInt.pad(64).asUInt, h.pad(64)),
      2.U -> Mux(signed, w.asSInt.pad(64).asUInt, w.pad(64))))
    Mux(isFlw, "hffffffff".U(32.W) ## w, ext)
  }
  val loadData = formatLoad(hitWord, s2.paddr, s2.req.core.size, s2.req.core.signed, s2.req.core.isFlw)

  // ---- Backend response: exactly one per CPU request (§1.1) ----
  val cpuDecided = s2.valid && (s2.req.src === L1Src.Cpu || s2.req.src === L1Src.Recheck) && !io.core.s2Kill
  io.core.resp.valid := cpuDecided &&
    (outcome === L1S2Outcome.Done || outcome === L1S2Outcome.Mshr || outcome === L1S2Outcome.Exc)
  io.core.resp.bits.kind := MuxCase(L1DRespKind.Done, Seq(
    (outcome === L1S2Outcome.Mshr) -> L1DRespKind.Mshr,
    (outcome === L1S2Outcome.Exc) -> L1DRespKind.Exc))
  io.core.resp.bits.data := loadData
  io.core.resp.bits.excCause := 0.U // TODO: 5/7/13/15 per op (§5.1)
  io.core.resp.bits.tval := s2.req.core.vaddr
  io.core.s2Hold := s2.valid && s2Stall && isCpuLike

  // ---- MSHR allocation ----
  miss.io.alloc.valid := s2.valid && outcome === L1S2Outcome.Mshr && !io.core.s2Kill
  miss.io.alloc.bits := 0.U.asTypeOf(miss.io.alloc.bits)
  miss.io.alloc.bits.lineAddr := lineOf(s2.paddr)
  miss.io.alloc.bits.isGetM := isStoreLike(s2.req.core.op)
  miss.io.alloc.bits.req := s2.req.core
  miss.io.alloc.bits.word := s2.req.word
  miss.io.alloc.bits.src := Mux(s2.req.src === L1Src.Ptw, MshrSrc.Ptw, MshrSrc.Cpu)
  // TODO: victim choice (PLRU skipping locked way, prefer invalid), upgrade, victim tag → I and lock.

  // ---- Replay result → late (B01: S2 never waits for late.ready) ----
  miss.io.replayDone := s2.valid && s2.req.src === L1Src.Replay
  miss.io.replayLoad.valid := s2.valid && s2.req.src === L1Src.Replay && s2.req.core.op === L1DOp.Load
  miss.io.replayLoad.bits.rd := s2.req.core.rd
  miss.io.replayLoad.bits.data := loadData
  miss.io.replayLoad.bits.error := false.B // TODO: refill error path
  io.core.late <> miss.io.late

  // ---- PTW response ----
  io.ptw.resp.valid := s2.valid && outcome === L1S2Outcome.PtwResp
  io.ptw.resp.bits.data := hitWord
  io.ptw.resp.bits.accessFault := false.B // TODO

  // ---- Writeback-read and probe beats from S2 ----
  miss.io.wbReadBeat.valid := s2.valid && s2.req.src === L1Src.WbRead
  miss.io.wbReadBeat.bits := s2.dataVec(miss.io.status.mshrWay) // TODO: writeback slot way
  probe.io.s2Beat.valid := s2.valid && s2.req.src === L1Src.Probe
  probe.io.s2Beat.bits.beat := s2.req.beat
  probe.io.s2Beat.bits.data := hitWord
  probe.io.s2Beat.bits.hitWay := hitWay
  probe.io.s2Beat.bits.localState := Mux(hit, hitState, L1State.I)
  probe.io.hold := false.B    // TODO: §10.2 table
  probe.io.startOk := true.B  // TODO: §10.1 start condition
  probe.io.initDone := initDone

  // ===========================================================================
  // PS (§3): store that passed S2 writes the data array next cycle.
  // ===========================================================================
  val storeDone = s2.valid && isCpuLike && s2.req.core.op === L1DOp.Store && outcome === L1S2Outcome.Done &&
    !io.core.s2Kill
  val psWriteBlocked = WireDefault(false.B) // TODO: whole-line op owns the data write port
  when(ps.valid && !psWriteBlocked) { ps.valid := false.B }
  when(storeDone) {
    ps.valid := true.B
    ps.idx := idxOfAddr(s2.paddr)
    ps.word := wordOfAddr(s2.paddr)
    ps.way := hitWay
    ps.paddr := s2.paddr
    ps.mask := 0.U // TODO: size/offset byte mask
    ps.data := s2.req.core.wdata << (s2.paddr(2, 0) ## 0.U(3.W))
    ps.setDirty := hitState === L1State.E
  }

  // ===========================================================================
  // Array writes: init, install, PS, probe tag update, victim invalidate.
  // ===========================================================================
  val tagWrEn = WireDefault(false.B)
  val tagWrIdx = WireDefault(0.U(p.idxW.W))
  val tagWrVal = Wire(Vec(p.ways, new L1TagEntry(p)))
  val tagWrMask = WireDefault(VecInit(Seq.fill(p.ways)(false.B)))
  tagWrVal := 0.U.asTypeOf(tagWrVal)

  when(!initDone) {
    tagWrEn := true.B
    tagWrIdx := initIdx
    tagWrMask := VecInit(Seq.fill(p.ways)(true.B))
    initIdx := initIdx + 1.U
  }
  // TODO: install last beat writes tag {E/S, tag}; probe writes I/S; PS E→M; victim → I.
  when(tagWrEn) { tagArr.write(tagWrIdx, tagWrVal, tagWrMask) }

  for (w <- 0 until p.ways) {
    val installHere = s0Fire && s0Sel === L1Src.Refill && miss.io.s0Req.bits.way === w.U &&
      !miss.io.s0Req.bits.installIsAckE
    val psHere = ps.valid && !psWriteBlocked && ps.way === w.U
    when(installHere) {
      dataArr(w).write(miss.io.s0Req.bits.idx ## miss.io.s0Req.bits.beat,
        miss.io.s0Req.bits.installData.asTypeOf(Vec(8, UInt(8.W))))
    }.elsewhen(psHere) {
      dataArr(w).write(ps.idx ## ps.word, ps.data.asTypeOf(Vec(8, UInt(8.W))), ps.mask.asBools)
    }
  }

  // PLRU touch on hit decision (§5.2). TODO: touch on install, skip locked way on victim.
  when(s2.valid && hit && outcome === L1S2Outcome.Done && isCpuLike) {
    plru(idxOfAddr(s2.paddr)) := TreePlru.touch(plru(idxOfAddr(s2.paddr)), hitWay, p.ways)
  }

  // ===========================================================================
  // Coherence links
  // ===========================================================================
  io.coh.req <> miss.io.req
  miss.io.rspDown.valid := io.coh.rspDown.valid
  miss.io.rspDown.bits := io.coh.rspDown.bits
  io.coh.rspDown.ready := true.B
  probe.io.snp <> io.coh.snp
  // RSP↑: probe answer > Put (§6.4).
  val upArb = Module(new Arbiter(new CoherenceRspUp(p.coh), 2))
  upArb.io.in(0) <> probe.io.ack
  upArb.io.in(1) <> miss.io.put
  io.coh.rspUp <> upArb.io.out

  // ===========================================================================
  // MMIO, AMO, misc
  // ===========================================================================
  mmio.io.start.valid := s2.valid && outcome === L1S2Outcome.ToMmio && !mmio.io.active
  mmio.io.start.bits.paddr := s2.paddr
  mmio.io.start.bits.isWrite := s2.req.core.op === L1DOp.Store
  mmio.io.start.bits.size := s2.req.core.size
  mmio.io.start.bits.signed := s2.req.core.signed
  mmio.io.start.bits.wdata := s2.req.core.wdata
  mmio.io.start.bits.rd := s2.req.core.rd
  mmio.io.start.bits.isFlw := s2.req.core.isFlw
  mmio.io.drained := io.core.drained
  mmio.io.s2Kill := io.core.s2Kill
  io.mmio <> mmio.io.axi
  io.core.mmioBusy := mmio.io.busy

  amoAlu.io.func := s2.req.core.amoFunc
  amoAlu.io.isWord := s2.req.core.size === 2.U
  amoAlu.io.oldOperand := hitWord // TODO: AMO.W half select (§8.3 item 5)
  amoAlu.io.rs2 := s2.req.core.wdata

  io.core.drained := !miss.io.status.mshrValid && !ps.valid

  when(io.core.trapClearRsv) { rsvValid := false.B }
  when(rsvTimer =/= 0.U) { rsvTimer := rsvTimer - 1.U }
  // TODO: LR sets reservation, SC checks it, probe/victim clears it (§8.1).

  // ===========================================================================
  // Events and assertions
  // ===========================================================================
  io.events := 0.U.asTypeOf(io.events)
  io.events.hit := io.core.resp.valid && io.core.resp.bits.kind === L1DRespKind.Done
  io.events.miss := miss.io.alloc.valid
  io.events.s2Hold := io.core.s2Hold
  io.events.s0Conflict := io.core.req.valid && cpuConflict

  // B01 ruling: S1/S2 hold only when S2 cannot decide.
  when(s1.valid && !s1Advance) { assert(s2Stall, "S1 held without an S2 stall") }
  assert(PopCount(hitVec) <= 1.U, "two ways hit the same tag")
}
