package flow.l2

import chisel3._
import chisel3.util._
import flow.bus.{Axi4MasterIO, Axi4Params}
import flow.coherence._
import flow.config.BreezeMemGeometry
import flow.mmu.sv39.TreePlru

/** Breeze v1 shared L2 / Home (coherence-l2-rtl-spec).
  *
  * SKELETON (feat/v1-mem-skeleton). Three-stage main pipeline is the only
  * array entry; same-set entries are serialised by the in-flight check;
  * slow transactions live in slots. `TODO` marks the simulate-and-fix work.
  */
class L2Home(g: BreezeMemGeometry) extends Module {
  val p = CoherenceParams(g)
  val io = IO(new Bundle {
    val l1d = Vec(p.nCores, Flipped(new L1DCoherenceIO(p)))
    val l1i = Vec(p.nCores, Flipped(new ReadClientIO(p)))
    val dma = Flipped(new ReadClientIO(p))
    val mem = new Axi4MasterIO(Axi4Params(p.paddrBits, p.memDataBits, p.slotBits))
  })

  // ===========================================================================
  // Port views: REQ port order L1D(0..n-1), L1I(0..n-1), DMA (§4.1)
  // ===========================================================================
  val reqPorts: Seq[DecoupledIO[CoherenceReq]] = io.l1d.map(_.req) ++ io.l1i.map(_.req) :+ io.dma.req
  val rspPorts: Seq[DecoupledIO[CoherenceRspDown]] = io.l1d.map(_.rspDown) ++ io.l1i.map(_.rspDown) :+ io.dma.rspDown

  // ===========================================================================
  // Arrays (§2.1)
  // ===========================================================================
  val meta = SyncReadMem(p.l2Sets, Vec(p.l2Ways, new L2MetaEntry(p)))
  val plruArr = SyncReadMem(p.l2Sets, UInt(p.plruBits.W))
  val data = SyncReadMem(p.l2Sets * p.l2Ways, Vec(p.lineBytes, UInt(8.W)))
  val initSet = RegInit(0.U((p.setBits + 1).W))
  val initDone = initSet === p.l2Sets.U

  // ===========================================================================
  // Sub-blocks and buffers
  // ===========================================================================
  val slots = Module(new L2Slots(p))
  val probeEng = Module(new L2ProbeEngine(p))
  val memEng = Module(new L2MemEngine(p))
  io.mem <> memEng.io.mem

  val putBuf = RegInit(VecInit(Seq.fill(p.nCores)(0.U.asTypeOf(new L2PutBuf(p)))))
  val ansBuf = RegInit(VecInit(Seq.fill(p.nCores)(0.U.asTypeOf(new L2ProbeAnswer(p)))))
  val inPipe = RegInit(VecInit(Seq.fill(p.nReqPorts)(false.B)))
  val slotWait = RegInit(VecInit(Seq.fill(p.nReqPorts)(false.B)))

  // RSP↓ output FIFOs; S2 writes, links drain (§8).
  val outQ = (0 until p.nReqPorts).map(i => Module(new Queue(new CoherenceRspDown(p), p.rspDownDepth(i))))
  for (i <- 0 until p.nReqPorts) {
    rspPorts(i) <> outQ(i).io.deq
    outQ(i).io.enq.valid := false.B
    outQ(i).io.enq.bits := DontCare
  }

  // ===========================================================================
  // RSP↑: always accepted (§3.2)
  // ===========================================================================
  for (c <- 0 until p.nCores) {
    val up = io.l1d(c).rspUp
    up.ready := true.B
    probeEng.io.answer(c).valid := false.B
    probeEng.io.answer(c).bits := DontCare
    when(up.fire) {
      when(up.bits.op === RspUpOp.Put) {
        assert(!putBuf(c).valid, "Put buffer overwritten")
        putBuf(c).valid := true.B
        putBuf(c).hasData := up.bits.hasData
        putBuf(c).addr := up.bits.addr
        putBuf(c).data := up.bits.data
      }.otherwise {
        assert(!ansBuf(c).valid, "probe answer buffer overwritten")
        ansBuf(c).valid := true.B
        ansBuf(c).op := up.bits.op
        ansBuf(c).hasData := up.bits.hasData
        ansBuf(c).addr := up.bits.addr
        ansBuf(c).data := up.bits.data
        probeEng.io.answer(c).valid := true.B
        probeEng.io.answer(c).bits.valid := true.B
        probeEng.io.answer(c).bits.op := up.bits.op
        probeEng.io.answer(c).bits.hasData := up.bits.hasData
        probeEng.io.answer(c).bits.addr := up.bits.addr
        probeEng.io.answer(c).bits.data := up.bits.data
      }
    }
    io.l1d(c).snp <> probeEng.io.snp(c)
  }
  probeEng.io.job <> slots.io.probeJob
  slots.io.probeCollected := probeEng.io.collected

  slots.io.memRead <> memEng.io.readReq
  slots.io.memReadDone := memEng.io.readDone

  // ===========================================================================
  // Pipeline registers
  // ===========================================================================
  val s1 = RegInit(0.U.asTypeOf(new L2S1(p)))
  val s2 = RegInit(0.U.asTypeOf(new L2S2(p)))

  // ===========================================================================
  // S0: arbitration (§4.1), in-flight check (§4.3), protection (§5.3)
  // ===========================================================================
  private def inFlight(set: UInt): Bool = s1.valid && s1.e.set === set || s2.valid && s2.e.set === set
  private def isProtected(set: UInt): Bool =
    slots.io.protectedSet.map(ps => ps.valid && ps.bits === set).reduce(_ || _) ||
      s1.valid && s1.e.kind === L2Kind.NewReq && s1.e.set === set ||
      s2.valid && s2.e.kind === L2Kind.NewReq && s2.e.set === set

  // Candidates. TODO: round-robin within each class (spec); priority order for now.
  val putCand = VecInit(putBuf.map(b => b.valid && !inFlight(p.setOf(b.addr))))
  val taskCand = VecInit(slots.io.taskReq.map(t => t.valid && !inFlight(t.bits.set)))
  val reqCand = VecInit(reqPorts.zipWithIndex.map { case (r, i) =>
    r.valid && !inPipe(i) && !slotWait(i) && !isProtected(p.setOf(r.bits.addr)) && !inFlight(p.setOf(r.bits.addr))
  })

  val s0Entry = Wire(new L2PipeEntry(p))
  s0Entry := 0.U.asTypeOf(s0Entry)
  val s0Valid = WireDefault(false.B)
  val putIdx = PriorityEncoder(putCand)
  val taskIdx = PriorityEncoder(taskCand)
  val reqIdx = PriorityEncoder(reqCand)
  val reqSel = VecInit(reqPorts.map(_.bits))(reqIdx)
  for (i <- 0 until p.l2Slots) slots.io.taskGrant(i) := false.B

  when(initDone) {
    when(putCand.asUInt.orR) {
      s0Valid := true.B
      s0Entry.kind := L2Kind.PutTask
      s0Entry.port := putIdx
      s0Entry.req.addr := putBuf(putIdx).addr
      s0Entry.req.data := putBuf(putIdx).data
    }.elsewhen(taskCand.asUInt.orR) {
      val t = slots.io.taskReq(taskIdx).bits
      s0Valid := true.B
      s0Entry.kind := L2Kind.SlotTask
      s0Entry.slot := taskIdx
      s0Entry.task := t.task
      s0Entry.port := t.port
      s0Entry.req := t.req
      slots.io.taskGrant(taskIdx) := true.B
    }.elsewhen(reqCand.asUInt.orR) {
      s0Valid := true.B
      s0Entry.kind := L2Kind.NewReq
      s0Entry.port := reqIdx
      s0Entry.req := reqSel
      inPipe(reqIdx) := true.B
    }
  }
  // Slot tasks address their own set (EVICT uses the victim tag but the same set).
  s0Entry.set := Mux(s0Entry.kind === L2Kind.SlotTask, slots.io.taskReq(taskIdx).bits.set, p.setOf(s0Entry.req.addr))
  s0Entry.tag := Mux(s0Entry.kind === L2Kind.SlotTask, slots.io.taskReq(taskIdx).bits.tag, p.tagOf(s0Entry.req.addr))

  val metaRead = meta.read(s0Entry.set, s0Valid)
  val plruRead = plruArr.read(s0Entry.set, s0Valid)

  // The pipeline never stalls: every stage completes in one cycle (§4).
  s1.valid := s0Valid
  s1.e := s0Entry

  // ===========================================================================
  // S1: tag compare, classification (§4.2, §4.4), data read
  // ===========================================================================
  val hitVec = VecInit(metaRead.map(m => m.valid && m.tag === s1.e.tag))
  val hit = hitVec.asUInt.orR
  val hitWay = OHToUInt(hitVec)
  val hm = metaRead(hitWay)
  val reqCore = s1.e.port // valid only for L1D ports (< nCores)
  val reqBit = UIntToOH(reqCore(p.coreBits - 1, 0), p.nCores)
  val fromL1D = s1.e.port < p.nCores.U

  val action = WireDefault(L2Action.None)
  when(s1.valid) {
    switch(s1.e.kind) {
      is(L2Kind.PutTask) { action := L2Action.Put }
      is(L2Kind.SlotTask) { action := L2Action.Task }
      is(L2Kind.NewReq) {
        when(!hit) {
          action := L2Action.NeedMiss
        }.otherwise {
          switch(s1.e.req.op) {
            is(ReqOp.GetS) {
              action := MuxCase(L2Action.NeedProbe, Seq(
                (hm.state === DirState.NONE) -> L2Action.FastGetM, // DataE (read copies granted E)
                (hm.state === DirState.SHARED) -> L2Action.FastGetS))
              // TODO: protocol errors (§4.4) — GetS hitting UNIQUE{c} or SHARED with c
            }
            is(ReqOp.GetM) {
              action := MuxCase(L2Action.NeedProbe, Seq(
                (hm.state === DirState.NONE) -> L2Action.FastGetM,
                (hm.state === DirState.SHARED && hm.sharers === reqBit) -> L2Action.FastAckE))
            }
            is(ReqOp.Read) {
              action := Mux(hm.state === DirState.UNIQUE, L2Action.NeedProbe, L2Action.FastRead)
            }
            is(ReqOp.MaskWrite) {
              action := Mux(hm.state === DirState.NONE, L2Action.FastMaskWrite, L2Action.NeedProbe)
            }
          }
        }
      }
    }
  }

  // Data read way: hit way for requests and Put; slot-recorded way for tasks.
  val s1Way = Mux(s1.e.kind === L2Kind.SlotTask, slots.io.taskReq(s1.e.slot).bits.way, hitWay)
  val dataRead = data.read(s1.e.set ## s1Way, s1.valid)

  s2.valid := s1.valid
  s2.e := s1.e
  s2.meta := metaRead
  s2.plru := plruRead
  s2.hit := hit
  s2.hitWay := hitWay
  s2.action := action
  s2.way := s1Way

  // ===========================================================================
  // S2: execute (§4.4–4.6, §5.2). At most one meta write, one data write,
  // one plru write, one response, one slot allocation.
  // ===========================================================================
  val metaWrEn = WireDefault(false.B)
  val metaWrWay = WireDefault(0.U(p.wayBits.W))
  val metaWrVal = Wire(new L2MetaEntry(p))
  metaWrVal := s2.meta(s2.hitWay)
  val dataWrEn = WireDefault(false.B)
  val dataWrVal = Wire(Vec(p.lineBytes, UInt(8.W)))
  dataWrVal := DontCare
  val dataWrMask = WireDefault(VecInit(Seq.fill(p.lineBytes)(false.B)))
  val plruWrEn = WireDefault(false.B)
  val rsp = Wire(new CoherenceRspDown(p))
  rsp := 0.U.asTypeOf(rsp)
  val rspEn = WireDefault(false.B)
  val reqReady = WireDefault(false.B)    // REQ handshake for s2.e.port
  val reqRetire = WireDefault(false.B)   // clear inPipe without handshake (slots full)

  val lineData = dataRead.asUInt         // SyncReadMem read issued in S1, valid in S2
  val s2Core = s2.e.port(p.coreBits - 1, 0)
  val s2Bit = UIntToOH(s2Core, p.nCores)
  val hmS2 = s2.meta(s2.hitWay)

  slots.io.alloc.valid := false.B
  slots.io.alloc.bits := 0.U.asTypeOf(slots.io.alloc.bits)
  slots.io.taskDone.valid := false.B
  slots.io.taskDone.bits := 0.U.asTypeOf(slots.io.taskDone.bits)
  probeEng.io.release := false.B
  memEng.io.wbPush.valid := false.B
  memEng.io.wbPush.bits := DontCare

  when(s2.valid) {
    switch(s2.action) {
      is(L2Action.FastGetS) {
        metaWrEn := true.B; metaWrWay := s2.hitWay
        metaWrVal.sharers := hmS2.sharers | s2Bit
        rspEn := true.B; rsp.op := RspDownOp.DataS; rsp.data := lineData
        plruWrEn := true.B; reqReady := true.B
      }
      is(L2Action.FastGetM) {
        metaWrEn := true.B; metaWrWay := s2.hitWay
        metaWrVal.state := DirState.UNIQUE; metaWrVal.sharers := s2Bit
        rspEn := true.B; rsp.op := RspDownOp.DataE; rsp.data := lineData
        plruWrEn := true.B; reqReady := true.B
      }
      is(L2Action.FastAckE) {
        metaWrEn := true.B; metaWrWay := s2.hitWay
        metaWrVal.state := DirState.UNIQUE; metaWrVal.sharers := s2Bit
        rspEn := true.B; rsp.op := RspDownOp.AckE
        plruWrEn := true.B; reqReady := true.B
      }
      is(L2Action.FastRead) {
        rspEn := true.B; rsp.op := RspDownOp.ReadData; rsp.id := s2.e.req.id; rsp.data := lineData
        plruWrEn := true.B; reqReady := true.B
      }
      is(L2Action.FastMaskWrite) {
        dataWrEn := true.B
        dataWrVal := s2.e.req.data.asTypeOf(dataWrVal)
        dataWrMask := s2.e.req.mask.asBools
        metaWrEn := true.B; metaWrWay := s2.hitWay; metaWrVal.dirty := true.B
        rspEn := true.B; rsp.op := RspDownOp.WriteAck
        reqReady := true.B
      }
      is(L2Action.NeedProbe, L2Action.NeedMiss) {
        when(slots.io.hasFree) {
          slots.io.alloc.valid := true.B
          slots.io.alloc.bits.port := s2.e.port
          slots.io.alloc.bits.req := s2.e.req
          slots.io.alloc.bits.set := s2.e.set
          slots.io.alloc.bits.tag := s2.e.tag
          slots.io.alloc.bits.typ := Mux(s2.action === L2Action.NeedMiss, L2SlotType.Miss, L2SlotType.Probe)
          // TODO: victim selection (invalid way first, else PLRU), victimTag,
          //       probe op/targets/owner per §4.4 rows.
          reqReady := true.B
        }.otherwise {
          reqRetire := true.B
        }
      }
      is(L2Action.Put) {
        // §4.5: remove sharer, write data if hasData, PutAck. TODO: full rules + asserts.
        val c = s2.e.port(p.coreBits - 1, 0)
        metaWrEn := true.B; metaWrWay := s2.hitWay
        val left = hmS2.sharers & ~UIntToOH(c, p.nCores)
        metaWrVal.sharers := left
        metaWrVal.state := Mux(left === 0.U || hmS2.state === DirState.UNIQUE, DirState.NONE, hmS2.state)
        when(putBuf(c).hasData) {
          dataWrEn := true.B
          dataWrVal := putBuf(c).data.asTypeOf(dataWrVal)
          dataWrMask := VecInit(Seq.fill(p.lineBytes)(true.B))
          metaWrVal.dirty := true.B
        }
        rspEn := true.B; rsp.op := RspDownOp.PutAck
        putBuf(c).valid := false.B
      }
      is(L2Action.Task) {
        // TODO: EVICT / INSTALL / REPLAY per §5.2, merging probe answers from
        //       ansBuf, wbPush for dirty victims, response for the original
        //       request, taskDone.result, probeEng.release.
        slots.io.taskDone.valid := true.B
        slots.io.taskDone.bits.slot := s2.e.slot
        slots.io.taskDone.bits.result := L2TaskResult.Finished
      }
    }
  }
  // Put and slot-task responses go to the recorded port; Put's port is the core.
  val rspPort = s2.e.port
  for (i <- 0 until p.nReqPorts) {
    when(rspEn && rspPort === i.U) {
      assert(outQ(i).io.enq.ready, "RSPdown output FIFO full (depth sized for max in-flight)")
      outQ(i).io.enq.valid := true.B
      outQ(i).io.enq.bits := rsp
    }
  }

  // REQ handshake at S2 (§3.1).
  for (i <- 0 until p.nReqPorts) {
    val mine = s2.valid && s2.e.kind === L2Kind.NewReq && s2.e.port === i.U
    reqPorts(i).ready := mine && reqReady
    when(mine && (reqReady || reqRetire)) { inPipe(i) := false.B }
    when(mine && reqRetire) { slotWait(i) := true.B }
  }
  when(slots.io.released) { slotWait.foreach(_ := false.B) }

  // Array writes
  when(!initDone) {
    meta.write(initSet, 0.U.asTypeOf(Vec(p.l2Ways, new L2MetaEntry(p))))
    plruArr.write(initSet, 0.U)
    initSet := initSet + 1.U
  }.otherwise {
    when(metaWrEn) {
      val v = Wire(Vec(p.l2Ways, new L2MetaEntry(p)))
      v := s2.meta
      v(metaWrWay) := metaWrVal
      meta.write(s2.e.set, v, UIntToOH(metaWrWay, p.l2Ways).asBools)
    }
    when(plruWrEn) { plruArr.write(s2.e.set, TreePlru.touch(s2.plru, s2.hitWay, p.l2Ways)) }
  }
  when(dataWrEn) { data.write(s2.e.set ## s2.way, dataWrVal, dataWrMask) }

  // ===========================================================================
  // Assertions (§10)
  // ===========================================================================
  when(s2.valid && s2.action === L2Action.Put) { assert(s2.hit, "Put must hit (inclusive L2)") }
  when(metaWrEn) {
    assert((metaWrVal.state === DirState.NONE) === (metaWrVal.sharers === 0.U), "NONE <=> no sharers")
    assert(metaWrVal.state =/= DirState.UNIQUE || PopCount(metaWrVal.sharers) === 1.U, "UNIQUE has one sharer")
  }
  // TODO: REQ stays stable until ready (§3.1): compare against a registered copy.
}
