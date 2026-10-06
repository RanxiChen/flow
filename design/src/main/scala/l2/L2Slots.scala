package flow.l2

import chisel3._
import chisel3.util._
import flow.coherence._

/** S2 tells a slot how its task ended (coherence-l2-rtl-spec §5.2). */
object L2TaskResult extends ChiselEnum {
  /** EVICT: victim still has L1 copies → probe them, then EVICT again. */
  val EvictNeedProbe,
  /** EVICT: victim invalidated (and handed to the write buffer if dirty) → MEM_READ. */
  EvictDone,
  /** EVICT: write buffer busy, nothing changed → retry EVICT. */
  EvictRetry,
  /** INSTALL / REPLAY wrote the response → release. */
  Finished = Value
}

class L2TaskReq(p: CoherenceParams) extends Bundle {
  val slot = UInt(p.slotBits.W)
  val task = L2SlotTask()
  val set = UInt(p.setBits.W)
  val tag = UInt(p.tagBits.W)
  val way = UInt(p.wayBits.W)
  val port = UInt(p.portBits.W)
  val req = new CoherenceReq(p)
  val refill = UInt(p.lineBits.W)
  val refillErr = Bool()
  val victimTag = UInt(p.tagBits.W)
  val probeOp = SnpOp()
  val probeTargets = UInt(p.sharerBits.W)
  val probeOwner = Bool()
  val probeCollected = Bool()
}

/** Slow slots: MISS and PROBE transactions that leave the main pipeline (§5). */
class L2Slots(p: CoherenceParams) extends Module {
  val io = IO(new Bundle {
    val hasFree = Output(Bool())
    val alloc = Flipped(Valid(new L2SlotAlloc(p)))

    // Task requests to S0 (one per slot), grant, and completion from S2
    val taskReq = Vec(p.l2Slots, Valid(new L2TaskReq(p)))
    val taskGrant = Input(Vec(p.l2Slots, Bool()))
    val taskDone = Flipped(Valid(new Bundle {
      val slot = UInt(p.slotBits.W)
      val result = L2TaskResult()
      /** EVICT that needs a probe: targets/role read from the victim's directory. */
      val probeTargets = UInt(p.sharerBits.W)
      val probeOwner = Bool()
    }))

    val probeJob = Decoupled(new L2ProbeJob(p))
    val probeCollected = Flipped(Valid(UInt(p.slotBits.W)))

    val memRead = Decoupled(new Bundle {
      val slot = UInt(p.slotBits.W)
      val addr = UInt(p.lineAddrBits.W)
    })
    val memReadDone = Flipped(Valid(new Bundle {
      val slot = UInt(p.slotBits.W)
      val data = UInt(p.lineBits.W)
      val error = Bool()
    }))

    val protectedSet = Output(Vec(p.l2Slots, Valid(UInt(p.setBits.W))))
    /** Some slot was released this cycle: clear every slotWait bit (§3.1). */
    val released = Output(Bool())
  })

  val slots = RegInit(VecInit(Seq.fill(p.l2Slots)(0.U.asTypeOf(new L2SlotRegs(p)))))
  val probeServed = RegInit(VecInit(Seq.fill(p.l2Slots)(false.B)))
  val answersReady = RegInit(VecInit(Seq.fill(p.l2Slots)(false.B)))
  private def busy(s: L2SlotRegs): Bool = s.state =/= L2SlotState.Idle

  // ---------------- Allocation (S2) ----------------
  val freeVec = VecInit(slots.map(s => !busy(s)))
  val freeIdx = PriorityEncoder(freeVec)
  io.hasFree := freeVec.asUInt.orR
  when(io.alloc.valid) {
    assert(io.hasFree, "slot allocated while full")
    val s = slots(freeIdx)
    s.a := io.alloc.bits
    s.refillErr := false.B
    s.state := MuxCase(L2SlotState.MemRead, Seq(
      (io.alloc.bits.typ === L2SlotType.Probe) -> L2SlotState.ProbeWait,
      io.alloc.bits.victimValid -> L2SlotState.Evict))
    probeServed(freeIdx) := false.B
    answersReady(freeIdx) := false.B
  }

  // ---------------- Task requests ----------------
  for (i <- 0 until p.l2Slots) {
    val s = slots(i)
    val pending = s.state === L2SlotState.Evict || s.state === L2SlotState.Install || s.state === L2SlotState.Replay
    io.taskReq(i).valid := pending
    io.taskReq(i).bits.slot := i.U
    io.taskReq(i).bits.task := MuxCase(L2SlotTask.Evict, Seq(
      (s.state === L2SlotState.Install) -> L2SlotTask.Install,
      (s.state === L2SlotState.Replay) -> L2SlotTask.Replay))
    io.taskReq(i).bits.set := s.a.set
    io.taskReq(i).bits.tag := s.a.tag
    io.taskReq(i).bits.way := s.a.way
    io.taskReq(i).bits.port := s.a.port
    io.taskReq(i).bits.req := s.a.req
    io.taskReq(i).bits.refill := s.refill
    io.taskReq(i).bits.refillErr := s.refillErr
    io.taskReq(i).bits.victimTag := s.a.victimTag
    io.taskReq(i).bits.probeOp := s.a.probeOp
    io.taskReq(i).bits.probeTargets := s.a.probeTargets
    io.taskReq(i).bits.probeOwner := s.a.probeOwner
    io.taskReq(i).bits.probeCollected := answersReady(i)
    when(io.taskGrant(i)) {
      s.state := MuxLookup(s.state, s.state)(Seq(
        L2SlotState.Evict -> L2SlotState.EvictInPipe,
        L2SlotState.Install -> L2SlotState.InstallInPipe,
        L2SlotState.Replay -> L2SlotState.ReplayInPipe))
    }
    io.protectedSet(i).valid := busy(s)
    io.protectedSet(i).bits := s.a.set
  }

  // ---------------- Task completion (S2) ----------------
  io.released := false.B
  when(io.taskDone.valid) {
    val s = slots(io.taskDone.bits.slot)
    switch(io.taskDone.bits.result) {
      is(L2TaskResult.EvictNeedProbe) {
        // Probe the victim's holders; afterwards EVICT runs again (§5.2).
        s.a.probeOp := SnpOp.Inv
        s.a.probeTargets := io.taskDone.bits.probeTargets
        s.a.probeOwner := io.taskDone.bits.probeOwner
        s.state := L2SlotState.ProbeWait
        probeServed(io.taskDone.bits.slot) := false.B
      }
      is(L2TaskResult.EvictDone) { s.state := L2SlotState.MemRead }
      is(L2TaskResult.EvictRetry) { s.state := L2SlotState.Evict }
      is(L2TaskResult.Finished) {
        s.state := L2SlotState.Idle
        io.released := true.B
      }
    }
    when(io.taskDone.bits.result =/= L2TaskResult.EvictRetry) {
      answersReady(io.taskDone.bits.slot) := false.B
    }
  }

  // ---------------- Probe engine hand-off (§6) ----------------
  val wantProbe = VecInit(slots.zip(probeServed).map { case (s, srv) => s.state === L2SlotState.ProbeWait && !srv })
  val probeArb = Module(new RRArbiter(UInt(p.slotBits.W), p.l2Slots))
  for (i <- 0 until p.l2Slots) {
    probeArb.io.in(i).valid := wantProbe(i)
    probeArb.io.in(i).bits := i.U
  }
  val probeIdx = probeArb.io.out.bits
  val ps = slots(probeIdx)
  io.probeJob.valid := probeArb.io.out.valid
  probeArb.io.out.ready := io.probeJob.ready
  io.probeJob.bits.slot := probeIdx
  // EVICT probes target the victim line; PROBE slots target the request line.
  io.probeJob.bits.addr := p.lineOf(Mux(ps.a.typ === L2SlotType.Miss, ps.a.victimTag, ps.a.tag), ps.a.set)
  io.probeJob.bits.op := ps.a.probeOp
  io.probeJob.bits.owner := ps.a.probeOwner
  io.probeJob.bits.targets := ps.a.probeTargets
  when(io.probeJob.fire) { probeServed(probeIdx) := true.B }
  when(io.probeCollected.valid) {
    val s = slots(io.probeCollected.bits)
    s.state := Mux(s.a.typ === L2SlotType.Miss, L2SlotState.Evict, L2SlotState.Replay)
    answersReady(io.probeCollected.bits) := true.B
  }

  // ---------------- Memory read (§5.2 MEM_READ) ----------------
  val wantRead = VecInit(slots.map(_.state === L2SlotState.MemRead))
  val readIssued = RegInit(VecInit(Seq.fill(p.l2Slots)(false.B)))
  val readCand = VecInit(wantRead.zip(readIssued).map { case (w, i) => w && !i })
  // Lock the chosen read until AR accepts it; another slot becoming ready
  // must not change a backpressured AXI address/ID.
  val readSelected = RegInit(false.B)
  val readSlot = Reg(UInt(p.slotBits.W))
  when(!readSelected && readCand.asUInt.orR) {
    readSelected := true.B
    readSlot := PriorityEncoder(readCand)
  }
  val readIdx = readSlot
  io.memRead.valid := readSelected
  io.memRead.bits.slot := readIdx
  io.memRead.bits.addr := p.lineOf(slots(readIdx).a.tag, slots(readIdx).a.set)
  when(io.memRead.fire) {
    readIssued(readIdx) := true.B
    readSelected := false.B
  }
  when(io.memReadDone.valid) {
    val s = slots(io.memReadDone.bits.slot)
    s.refill := io.memReadDone.bits.data
    s.refillErr := io.memReadDone.bits.error
    s.state := L2SlotState.Install
    readIssued(io.memReadDone.bits.slot) := false.B
  }
}
