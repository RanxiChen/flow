package flow.l2

import chisel3._
import chisel3.util._
import flow.bus.{Axi4MasterIO, Axi4Params}
import flow.coherence._
import flow.config.BreezeMemGeometry
import flow.mmu.sv39.TreePlru

/** Shared L2 / Home. All array updates pass through the main pipeline;
  * slow transactions retain set ownership in slots until their response.
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

  // Round-robin within each priority class, after excluding busy sets.
  val putCand = VecInit(putBuf.map(b => b.valid && !inFlight(p.setOf(b.addr))))
  val taskCand = VecInit(slots.io.taskReq.map(t => t.valid && !inFlight(t.bits.set)))
  val reqCand = VecInit(reqPorts.zipWithIndex.map { case (r, i) =>
    r.valid && !inPipe(i) && !slotWait(i) && !isProtected(p.setOf(r.bits.addr)) && !inFlight(p.setOf(r.bits.addr))
  })

  val s0Entry = Wire(new L2PipeEntry(p))
  s0Entry := 0.U.asTypeOf(s0Entry)
  val s0Valid = WireDefault(false.B)
  def roundRobin(cand: Vec[Bool]): (UInt, UInt) = {
    val next = RegInit(0.U((log2Ceil(cand.length) max 1).W))
    val rotated = VecInit((0 until cand.length).map { offset =>
      val sum = next +& offset.U
      val idx = Mux(sum >= cand.length.U, sum - cand.length.U, sum)
      cand(idx)
    })
    val sum = next +& PriorityEncoder(rotated)
    (Mux(sum >= cand.length.U, sum - cand.length.U, sum), next)
  }
  val (putIdx, putNext) = roundRobin(putCand)
  val (taskIdx, taskNext) = roundRobin(taskCand)
  val (reqIdx, reqNext) = roundRobin(reqCand)
  val reqSel = VecInit(reqPorts.map(_.bits))(reqIdx)
  for (i <- 0 until p.l2Slots) slots.io.taskGrant(i) := false.B

  when(initDone) {
    when(putCand.asUInt.orR) {
      s0Valid := true.B
      s0Entry.kind := L2Kind.PutTask
      s0Entry.port := putIdx
      s0Entry.req.addr := putBuf(putIdx).addr
      s0Entry.req.data := putBuf(putIdx).data
      putNext := Mux(putIdx === (p.nCores - 1).U, 0.U, putIdx + 1.U)
    }.elsewhen(taskCand.asUInt.orR) {
      val t = slots.io.taskReq(taskIdx).bits
      s0Valid := true.B
      s0Entry.kind := L2Kind.SlotTask
      s0Entry.slot := taskIdx
      s0Entry.task := t.task
      s0Entry.port := t.port
      s0Entry.req := t.req
      s0Entry.taskData := t
      taskNext := Mux(taskIdx === (p.l2Slots - 1).U, 0.U, taskIdx + 1.U)
      slots.io.taskGrant(taskIdx) := true.B
    }.elsewhen(reqCand.asUInt.orR) {
      s0Valid := true.B
      s0Entry.kind := L2Kind.NewReq
      s0Entry.port := reqIdx
      s0Entry.req := reqSel
      inPipe(reqIdx) := true.B
      reqNext := Mux(reqIdx === (p.nReqPorts - 1).U, 0.U, reqIdx + 1.U)
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
              when((hm.sharers & reqBit).orR) { action := L2Action.ProtocolError }
            }
            is(ReqOp.GetM) {
              action := MuxCase(L2Action.NeedProbe, Seq(
                (hm.state === DirState.NONE) -> L2Action.FastGetM,
                (hm.state === DirState.SHARED && hm.sharers === reqBit) -> L2Action.FastAckE))
              when(hm.state === DirState.UNIQUE && hm.sharers === reqBit) {
                action := L2Action.ProtocolError
              }
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
  val s1Way = Mux(s1.e.kind === L2Kind.SlotTask, s1.e.taskData.way, hitWay)
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
  val plruWrWay = WireDefault(s2.hitWay)
  val rsp = Wire(new CoherenceRspDown(p))
  rsp := 0.U.asTypeOf(rsp)
  val rspEn = WireDefault(false.B)
  val reqReady = WireDefault(false.B)    // REQ handshake for s2.e.port
  val reqRetire = WireDefault(false.B)   // clear inPipe without handshake (slots full)

  val lineData = dataRead.asUInt         // SyncReadMem read issued in S1, valid in S2
  val s2Core = s2.e.port(p.coreBits - 1, 0)
  val s2Bit = UIntToOH(s2Core, p.nCores)
  val hmS2 = s2.meta(s2.hitWay)
  val task = s2.e.taskData
  val taskMeta = s2.meta(s2.way)
  val invalidWays = VecInit(s2.meta.map(m => !m.valid))
  val victimWay = Mux(invalidWays.asUInt.orR, PriorityEncoder(invalidWays),
    TreePlru.victim(s2.plru, p.l2Ways))
  val victim = s2.meta(victimWay)

  // Only the current slot's targets own the probe buffers. A Put may have
  // updated the directory while the slot waited, so always use fresh meta.
  val answerTargets = VecInit((0 until p.nCores).map(c =>
    task.probeCollected && task.probeTargets(c) && ansBuf(c).valid))
  val dataAnswers = VecInit((0 until p.nCores).map(c => answerTargets(c) && ansBuf(c).hasData))
  val ownerData = Mux1H(dataAnswers, ansBuf.map(_.data))
  val mergedData = Mux(dataAnswers.asUInt.orR, ownerData, lineData)
  val afterProbe = Wire(new L2MetaEntry(p))
  afterProbe := taskMeta
  when(task.probeCollected) {
    when(task.probeOp === SnpOp.Inv) {
      val left = taskMeta.sharers & ~task.probeTargets
      afterProbe.sharers := left
      afterProbe.state := Mux(left === 0.U, DirState.NONE, taskMeta.state)
    }.otherwise {
      // The owner may have sent Put before acknowledging Down.
      afterProbe.state := Mux(taskMeta.sharers === 0.U, DirState.NONE, DirState.SHARED)
    }
    when(dataAnswers.asUInt.orR) { afterProbe.dirty := true.B }
  }

  def consumeAnswers(): Unit = {
    when(task.probeCollected) {
      assert(PopCount(dataAnswers) <= 1.U, "multiple dirty probe answers")
      for (c <- 0 until p.nCores) {
        when(task.probeTargets(c)) {
          assert(ansBuf(c).valid, "slot consumes a missing probe answer")
          ansBuf(c).valid := false.B
        }
      }
      probeEng.io.release := true.B
    }
  }

  // Execute an original request after install/probe. No new slot may be
  // needed: install starts at NONE, and a probe removed the conflict.
  def finishRequest(base: L2MetaEntry, contents: UInt): Unit = {
    metaWrEn := true.B
    metaWrWay := s2.way
    metaWrVal := base
    rspEn := true.B
    rsp.id := s2.e.req.id
    rsp.data := contents
    plruWrEn := true.B
    plruWrWay := s2.way
    switch(s2.e.req.op) {
      is(ReqOp.GetS) {
        assert(base.state =/= DirState.UNIQUE, "GetS replay still requires a probe")
        metaWrVal.sharers := base.sharers | s2Bit
        metaWrVal.state := Mux(base.state === DirState.NONE, DirState.UNIQUE, DirState.SHARED)
        rsp.op := Mux(base.state === DirState.NONE, RspDownOp.DataE, RspDownOp.DataS)
      }
      is(ReqOp.GetM) {
        assert(base.state === DirState.NONE || (base.state === DirState.SHARED && base.sharers === s2Bit),
          "GetM replay still requires a probe")
        metaWrVal.state := DirState.UNIQUE
        metaWrVal.sharers := s2Bit
        rsp.op := Mux(base.state === DirState.SHARED && base.sharers === s2Bit, RspDownOp.AckE, RspDownOp.DataE)
      }
      is(ReqOp.Read) {
        assert(base.state =/= DirState.UNIQUE, "Read replay still requires a probe")
        rsp.op := RspDownOp.ReadData
      }
      is(ReqOp.MaskWrite) {
        assert(base.state === DirState.NONE, "MaskWrite replay still has sharers")
        val oldBytes = contents.asTypeOf(Vec(p.lineBytes, UInt(8.W)))
        val newBytes = s2.e.req.data.asTypeOf(Vec(p.lineBytes, UInt(8.W)))
        dataWrEn := true.B
        for (b <- 0 until p.lineBytes) {
          dataWrVal(b) := Mux(s2.e.req.mask(b), newBytes(b), oldBytes(b))
          // Installing/reforwarding owner data writes the entire line.
          dataWrMask(b) := s2.e.task === L2SlotTask.Install || dataAnswers.asUInt.orR || s2.e.req.mask(b)
        }
        metaWrVal.dirty := true.B
        rsp.op := RspDownOp.WriteAck
      }
    }
  }

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
        plruWrEn := true.B
      }
      is(L2Action.NeedProbe, L2Action.NeedMiss) {
        when(slots.io.hasFree) {
          slots.io.alloc.valid := true.B
          slots.io.alloc.bits.port := s2.e.port
          slots.io.alloc.bits.req := s2.e.req
          slots.io.alloc.bits.set := s2.e.set
          slots.io.alloc.bits.tag := s2.e.tag
          slots.io.alloc.bits.typ := Mux(s2.action === L2Action.NeedMiss, L2SlotType.Miss, L2SlotType.Probe)
          val isMiss = s2.action === L2Action.NeedMiss
          slots.io.alloc.bits.way := Mux(isMiss, victimWay, s2.hitWay)
          slots.io.alloc.bits.victimValid := isMiss && victim.valid
          slots.io.alloc.bits.victimTag := victim.tag
          val isDown = s2.e.req.op === ReqOp.GetS || s2.e.req.op === ReqOp.Read
          slots.io.alloc.bits.probeOp := Mux(isDown, SnpOp.Down, SnpOp.Inv)
          slots.io.alloc.bits.probeOwner := hmS2.state === DirState.UNIQUE
          slots.io.alloc.bits.probeTargets := Mux(s2.e.req.op === ReqOp.GetM,
            hmS2.sharers & ~s2Bit, hmS2.sharers)
          reqReady := true.B
        }.otherwise {
          reqRetire := true.B
        }
      }
      is(L2Action.Put) {
        // Put is legal even while a slow slot protects this set.
        val c = s2.e.port(p.coreBits - 1, 0)
        metaWrEn := true.B; metaWrWay := s2.hitWay
        val left = hmS2.sharers & ~UIntToOH(c, p.nCores)
        assert((hmS2.sharers & UIntToOH(c, p.nCores)).orR, "Put from a non-sharer")
        metaWrVal.sharers := left
        metaWrVal.state := Mux(left === 0.U || hmS2.state === DirState.UNIQUE, DirState.NONE, hmS2.state)
        when(putBuf(c).hasData) {
          assert(hmS2.state === DirState.UNIQUE && hmS2.sharers === UIntToOH(c, p.nCores),
            "dirty Put from a non-owner")
          dataWrEn := true.B
          dataWrVal := putBuf(c).data.asTypeOf(dataWrVal)
          dataWrMask := VecInit(Seq.fill(p.lineBytes)(true.B))
          metaWrVal.dirty := true.B
        }
        rspEn := true.B; rsp.op := RspDownOp.PutAck
        putBuf(c).valid := false.B
      }
      is(L2Action.Task) {
        slots.io.taskDone.valid := true.B
        slots.io.taskDone.bits.slot := s2.e.slot
        slots.io.taskDone.bits.result := L2TaskResult.Finished
        switch(s2.e.task) {
          is(L2SlotTask.Evict) {
            when(afterProbe.sharers.orR) {
              assert(!task.probeCollected, "eviction probe left sharers")
              slots.io.taskDone.bits.result := L2TaskResult.EvictNeedProbe
              slots.io.taskDone.bits.probeTargets := afterProbe.sharers
              slots.io.taskDone.bits.probeOwner := afterProbe.state === DirState.UNIQUE
            }.elsewhen(afterProbe.dirty && !memEng.io.wbFree) {
              // Keep answer buffers and probe engine ownership across retry.
              slots.io.taskDone.bits.result := L2TaskResult.EvictRetry
            }.otherwise {
              when(afterProbe.dirty) {
                memEng.io.wbPush.valid := true.B
                memEng.io.wbPush.bits.addr := task.victimTag ## task.set
                memEng.io.wbPush.bits.data := mergedData
              }
              metaWrEn := true.B
              metaWrWay := s2.way
              metaWrVal := 0.U.asTypeOf(metaWrVal)
              slots.io.taskDone.bits.result := L2TaskResult.EvictDone
              consumeAnswers()
            }
          }
          is(L2SlotTask.Install) {
            when(task.refillErr) {
              rspEn := true.B
              rsp.id := s2.e.req.id
              rsp.op := MuxCase(RspDownOp.DataE, Seq(
                (s2.e.req.op === ReqOp.Read) -> RspDownOp.ReadData,
                (s2.e.req.op === ReqOp.MaskWrite) -> RspDownOp.WriteAck))
              rsp.error := s2.e.req.op =/= ReqOp.MaskWrite
              when(s2.e.req.op === ReqOp.MaskWrite) { printf(p"DMA refill error at ${s2.e.req.addr}\n") }
            }.otherwise {
              val installed = Wire(new L2MetaEntry(p))
              installed := 0.U.asTypeOf(installed)
              installed.valid := true.B
              installed.tag := task.tag
              dataWrEn := true.B
              dataWrVal := task.refill.asTypeOf(dataWrVal)
              dataWrMask := VecInit(Seq.fill(p.lineBytes)(true.B))
              finishRequest(installed, task.refill)
            }
          }
          is(L2SlotTask.Replay) {
            assert(s2.hit && s2.hitWay === s2.way, "probe replay lost its protected line")
            when(dataAnswers.asUInt.orR) {
              dataWrEn := true.B
              dataWrVal := mergedData.asTypeOf(dataWrVal)
              dataWrMask := VecInit(Seq.fill(p.lineBytes)(true.B))
            }
            finishRequest(afterProbe, mergedData)
            consumeAnswers()
          }
        }
      }
      is(L2Action.ProtocolError) { assert(false.B, "illegal coherence request for the current directory") }
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
    when(plruWrEn) { plruArr.write(s2.e.set, TreePlru.touch(s2.plru, plruWrWay, p.l2Ways)) }
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
  for ((r, i) <- reqPorts.zipWithIndex) {
    val held = RegNext(r.valid && !r.ready, false.B)
    val previous = RegEnable(r.bits, r.valid)
    when(held) { assert(r.valid && r.bits.asUInt === previous.asUInt, "REQ changed before acceptance") }
    when(r.valid) {
      if (i < p.nCores) assert(r.bits.op === ReqOp.GetS || r.bits.op === ReqOp.GetM)
      else if (i < 2 * p.nCores) assert(r.bits.op === ReqOp.Read)
      else assert(r.bits.op === ReqOp.Read || r.bits.op === ReqOp.MaskWrite)
    }
  }
}
