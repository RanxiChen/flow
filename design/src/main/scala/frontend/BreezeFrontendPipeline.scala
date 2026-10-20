package flow.frontend

import chisel3._
import chisel3.util._
import flow.config.BreezeMemGeometry
import flow.interface._
import flow.mmu.sv39._
import _root_.circt.stage.ChiselStage

class PipelineInstruction(p: BreezeFrontendPipelineParams) extends Bundle {
  val pc = UInt(64.W); val eventPc = UInt(64.W)
  val raw = UInt(32.W); val instruction = UInt(32.W); val length = UInt(3.W)
  val compressed = Bool(); val illegal = Bool()
  val pageFault = Bool(); val accessFault = Bool(); val faultSecondParcel = Bool()
  val kind = UInt(2.W); val predictedTaken = Bool(); val predictedTarget = UInt(64.W)
  val historyBefore = UInt(p.historyBits.W); val phtIndex = UInt(p.historyBits.W)
  val rasPush = Bool(); val rasPop = Bool()
}
class PipelineResolution(p: BreezeFrontendPipelineParams) extends Bundle {
  val eventPc = UInt(64.W); val target = UInt(64.W); val nextPc = UInt(64.W)
  val kind = UInt(2.W); val taken = Bool(); val incorrect = Bool()
  val historyBefore = UInt(p.historyBits.W); val phtIndex = UInt(p.historyBits.W)
  val rasPush = Bool(); val rasPop = Bool(); val returnAddress = UInt(64.W)
}

/** Atomic zero/one/two-instruction enqueue, single-instruction dequeue. */
class PipelineInstructionQueue(p: BreezeFrontendPipelineParams) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new Bundle {
      val count = UInt(2.W); val instructions = Vec(2, new PipelineInstruction(p))
    }))
    val deq = Decoupled(new PipelineInstruction(p))
    val clear = Input(Bool())
    val empty = Output(Bool())
  })
  val ptrBits = log2Ceil(p.instructionSlots)
  // Consecutive lane addresses have opposite parity. Select a lane once
  // per bank, then each bank writes just one row. This preserves the atomic
  // 0/1/2 enqueue without a separate wide two-writer mux at every slot.
  val storage = Seq.fill(2)(Reg(Vec(p.instructionSlots / 2, new PipelineInstruction(p))))
  val head = RegInit(0.U(ptrBits.W)); val tail = RegInit(0.U(ptrBits.W))
  val count = RegInit(0.U((ptrBits + 1).W))
  io.deq.valid := count =/= 0.U && !io.clear
  io.deq.bits := Mux(head(0), storage(1)(head(ptrBits - 1, 1)), storage(0)(head(ptrBits - 1, 1)))
  io.enq.ready := !io.clear && (p.instructionSlots.U - count + io.deq.fire.asUInt) >= io.enq.bits.count
  when(io.deq.fire) { head := head + 1.U }
  when(io.enq.fire) {
    assert(io.enq.bits.count <= 2.U)
    tail := tail + io.enq.bits.count
  }
  for (bank <- 0 until 2) {
    val firstLane = tail(0) === bank.U
    val row = Mux(firstLane, tail(ptrBits - 1, 1), (tail + 1.U)(ptrBits - 1, 1))
    val payload = Mux(firstLane, io.enq.bits.instructions(0), io.enq.bits.instructions(1))
    val writes = io.enq.fire && Mux(firstLane, io.enq.bits.count >= 1.U, io.enq.bits.count === 2.U)
    when(writes) { storage(bank)(row) := payload }
  }
  count := count + Mux(io.enq.fire, io.enq.bits.count, 0.U) - io.deq.fire.asUInt
  when(io.clear) { head := 0.U; tail := 0.U; count := 0.U }
  io.empty := count === 0.U
  assert(count <= p.instructionSlots.U)
}

/** Production/standalone frontend boundary: genuine VIPT F0/F1, streaming RVC F2.
  * Translation is an external per-core service. Connected mode reserves
  * training capacity before backend EX.
  */
class BreezeFrontendPipeline(g: BreezeMemGeometry = BreezeMemGeometry(nCores = 1),
    p: BreezeFrontendPipelineParams = BreezeFrontendPipelineParams(),
    itlbParams: Sv39TlbParams = Sv39MmuParams().itlb, backendConnected: Boolean = false,
    predictorEnabled: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val resetPc = Input(UInt(64.W))
    val context = Input(new BreezeMmuContext(64))
    val block = Input(Bool())
    val redirect = Flipped(Valid(UInt(64.W))) // global exception/context restart
    val fenceI = Input(Bool())
    val resolve = Flipped(Decoupled(new PipelineResolution(p)))
    val resolutionReserve = if(backendConnected) Some(Input(Bool())) else None
    val resolutionAvailable = Output(Bool())
    val committedBranch = Flipped(Valid(Bool()))
    val instruction = Decoupled(new PipelineInstruction(p))
    val refillReq = new L1CacheMissReqIO(64)
    val refillRsp = new L1CacheMissRespIO(g.lineBytes * 8)
    val itlb = Flipped(new TlbPortIO(withPmpCandidate = true,
      fetchCandidates = itlbParams.ways + itlbParams.superpages + 1))
    val hpm = Output(new BreezeHpmEvents)
    val idle = Output(Bool())
    val debug = Output(new Bundle {
      val f0Fire = Bool(); val f0Pc = UInt(64.W); val f1Fire = Bool(); val f2Fire = Bool()
      val f1Redirect = Bool(); val f2Redirect = Bool(); val specHistory = UInt(p.historyBits.W)
      val queryHistory = UInt(p.historyBits.W); val rasCount = UInt(log2Ceil(p.rasDepth + 1).W)
      val resolutionUsed = UInt(3.W); val resolutionReserved = UInt(3.W); val trainingFire = Bool()
    })
  })
  def append(h: UInt, bit: Bool): UInt = Cat(h(p.historyBits - 2, 0), bit)
  val pc = Reg(UInt(64.W))
  val specHistory = RegInit(0.U(p.historyBits.W))
  // Last F1 preview belongs to the stream tail, unlike the stalled F2 head.
  val forecastHistory = RegInit(0.U(p.historyBits.W))
  val committedHistory = RegInit(0.U(p.historyBits.W))
  val faultStop = RegInit(false.B)
  val cache = Module(new BreezeViptICache(g, p, itlbParams,
    quiesceReplayWhenBlocked = backendConnected))
  val predictor = Module(new BreezePipelinePredictor(p))
  val queue = Module(new PipelineInstructionQueue(p))
  cache.io.tlb <> io.itlb
  cache.io.context := io.context; cache.io.fenceI := io.fenceI
  io.refillReq <> cache.io.refillReq; cache.io.refillRsp <> io.refillRsp
  io.instruction <> queue.io.deq

  // Resolve accepts only when all required training records can be retained.
  // ID reserves a record before EX. Accepted EX resolution immediately owns
  // redirect/history/RAS effects; its training record stays until both banks
  // accept it. Training backpressure affects ID admission, never EX advance.
  val training = if(backendConnected) Some(Module(new Queue(new PipelineResolution(p), 4))) else None
  val feedback = training.map(_.io.deq).getOrElse(io.resolve)
  val trainingConditional = feedback.bits.kind === 1.U
  val updatesBtb = feedback.bits.kind =/= 0.U && (!trainingConditional || feedback.bits.taken)
  feedback.ready := ((!trainingConditional || predictor.io.phtUpdate.ready) &&
    (!updatesBtb || predictor.io.btbUpdate.ready) || !predictorEnabled.B) && !io.redirect.valid
  io.resolutionAvailable := true.B
  io.debug.resolutionUsed := 0.U
  io.debug.resolutionReserved := 0.U
  io.debug.trainingFire := feedback.fire
  if(backendConnected) {
    val used = RegInit(0.U(3.W))
    val reserved = RegInit(0.U(3.W))
    val reserve = io.resolutionReserve.get
    val cancel = io.redirect.valid || io.fenceI
    val release = Mux(cancel, reserved, io.resolve.fire.asUInt)
    io.resolutionAvailable := used < 4.U && !cancel
    io.debug.resolutionUsed := used
    io.debug.resolutionReserved := reserved
    training.get.io.enq <> io.resolve
    // Suppress an unconsumed EX record at a global cancellation. Previously
    // accepted training is retained, matching legal speculative training.
    training.get.io.enq.valid := io.resolve.valid && !cancel
    io.resolve.ready := training.get.io.enq.ready && !cancel
    used := used + reserve.asUInt - training.get.io.deq.fire.asUInt - Mux(cancel, reserved, 0.U)
    reserved := reserved + reserve.asUInt - release
    when(reserve) { assert(io.resolutionAvailable, "unreserved branch feedback capacity") }
    when(io.resolve.valid && !cancel) {
      assert(reserved =/= 0.U && io.resolve.ready, "EX resolution lacks its ID reservation")
    }
    assert(used <= 4.U && reserved <= used)
  }
  predictor.io.phtUpdate.valid := predictorEnabled.B && feedback.valid && trainingConditional &&
    (!updatesBtb || predictor.io.btbUpdate.ready) && !io.redirect.valid
  predictor.io.phtUpdate.bits.index := feedback.bits.phtIndex
  predictor.io.phtUpdate.bits.taken := feedback.bits.taken
  predictor.io.btbUpdate.valid := predictorEnabled.B && feedback.valid && updatesBtb &&
    (!trainingConditional || predictor.io.phtUpdate.ready) && !io.redirect.valid
  predictor.io.btbUpdate.bits.pc := feedback.bits.eventPc
  predictor.io.btbUpdate.bits.target := feedback.bits.target
  predictor.io.btbUpdate.bits.kind := feedback.bits.kind
  predictor.io.btbUpdate.bits.invalidate := false.B
  val resolvedRedirect = io.resolve.fire && io.resolve.bits.incorrect
  // EX recovery belongs to this resolving instruction; the training head
  // may be an older control instruction of a different kind.
  val resolvedConditional = io.resolve.bits.kind === 1.U
  val globalCancel = io.redirect.valid || io.fenceI
  val olderCancel = globalCancel || resolvedRedirect
  queue.io.clear := olderCancel
  when(io.committedBranch.valid) { committedHistory := append(committedHistory, io.committedBranch.bits) }

  // RAS uses two full, small stacks; no pointer-only rollback through an
  // overwritten entry. Ordered resolution is part of the interface contract.
  val rasPtrBits = log2Ceil(p.rasDepth)
  val rasCountBits = log2Ceil(p.rasDepth + 1)
  val specStack = Reg(Vec(p.rasDepth, UInt(64.W)))
  val resolvedStack = Reg(Vec(p.rasDepth, UInt(64.W)))
  val specPtr = RegInit(0.U(rasPtrBits.W)); val resolvedPtr = RegInit(0.U(rasPtrBits.W))
  val specCount = RegInit(0.U(rasCountBits.W)); val resolvedCount = RegInit(0.U(rasCountBits.W))
  val popResolved = io.resolve.bits.rasPop && resolvedCount =/= 0.U
  val resolvedAfterPop = (resolvedPtr - popResolved.asUInt)(rasPtrBits - 1, 0)
  val resolvedNextPtr = (resolvedAfterPop + io.resolve.bits.rasPush.asUInt)(rasPtrBits - 1, 0)
  val resolvedCountAfterPop = resolvedCount - popResolved.asUInt
  val resolvedNextCount = Mux(io.resolve.bits.rasPush && resolvedCountAfterPop < p.rasDepth.U,
    resolvedCountAfterPop + 1.U, resolvedCountAfterPop)
  val resolvedNextStack = Wire(Vec(p.rasDepth, UInt(64.W)))
  resolvedNextStack := resolvedStack
  when(io.resolve.bits.rasPush) { resolvedNextStack(resolvedAfterPop) := io.resolve.bits.returnAddress }
  when(io.resolve.fire) {
    resolvedStack := resolvedNextStack; resolvedPtr := resolvedNextPtr; resolvedCount := resolvedNextCount
  }

  // The F1 preview is never appended again to the persistent F2 history.
  def preview(w: PipelineFetchWindow): (Bool, UInt, UInt) = {
    val b0 = w.prediction.btb(0); val b1 = w.prediction.btb(1)
    val ok0 = !w.pageFault(0) && !w.accessFault(0)
    val cond0 = ok0 && b0.hit && b0.kind === 1.U
    val t0 = ok0 && b0.hit && (b0.kind >= 2.U || (cond0 && w.prediction.taken(0)))
    val choice1 = Mux(cond0 && !t0, 2.U, 1.U)
    val ok1 = w.mask(1) && !w.pageFault(1) && !w.accessFault(1) && !t0
    val cond1 = ok1 && b1.hit && b1.kind === 1.U
    val t1 = ok1 && b1.hit && (b1.kind >= 2.U || (cond1 && w.prediction.taken(choice1)))
    val h0 = Mux(cond0, append(w.prediction.history, t0), w.prediction.history)
    val h1 = Mux(cond1, append(h0, t1), h0)
    val sequential = w.pc + Mux(w.mask(1), 4.U, 2.U)
    (t0 || t1, Mux(t0, b0.target, Mux(t1, b1.target, sequential)), h1)
  }
  val (earlyTaken, earlyTarget, earlyHistory) = preview(cache.io.f1Prediction.bits)
  when(cache.io.f1Prediction.valid) { forecastHistory := earlyHistory }
  cache.io.historyAfter := earlyHistory
  val f1Redirect = cache.io.f1Prediction.valid && earlyTaken && earlyTarget =/= pc
  cache.io.stopF0 := f1Redirect
  cache.io.discardDeferred := cache.io.f1.valid && earlyTaken
  cache.io.request.valid := !io.block && !faultStop && !olderCancel
  cache.io.request.bits.pc := pc
  predictor.io.query.valid := predictorEnabled.B && cache.io.predictorQuery.valid
  predictor.io.query.bits.pc := cache.io.predictorQuery.bits.pc
  predictor.io.query.bits.history := cache.io.predictorQuery.bits.history
  cache.io.prediction := predictor.io.result

  // F2 reconstructs at most three available parcels (one retained + two new).
  val carryValid = RegInit(false.B)
  val carryParcel = Reg(UInt(16.W)); val carryPc = Reg(UInt(64.W))
  val carryPageFault = Reg(Bool()); val carryAccessFault = Reg(Bool())
  val w = cache.io.response.bits
  val parcels = Wire(Vec(3, UInt(16.W)))
  val pfs = Wire(Vec(3, Bool())); val afs = Wire(Vec(3, Bool()))
  parcels(0) := Mux(carryValid, carryParcel, w.data(15, 0))
  parcels(1) := Mux(carryValid, w.data(15, 0), w.data(31, 16))
  parcels(2) := w.data(31, 16)
  pfs(0) := Mux(carryValid, carryPageFault, w.pageFault(0))
  afs(0) := Mux(carryValid, carryAccessFault, w.accessFault(0))
  pfs(1) := Mux(carryValid, w.pageFault(0), w.pageFault(1))
  afs(1) := Mux(carryValid, w.accessFault(0), w.accessFault(1))
  pfs(2) := w.pageFault(1); afs(2) := w.accessFault(1)
  val availableParcels = Mux(w.mask(1), 2.U, 1.U) + carryValid.asUInt
  val startPc = Mux(carryValid, carryPc, w.pc)
  // Late parcel-length/fault selection chooses among already computed PCs.
  // Do not put consecutive 64-bit adders after those late selectors.
  val parcelPc = VecInit((0 until 5).map(n => if (n == 0) startPc else startPc + (2 * n).U))
  val len0 = Mux(parcels(0)(1, 0) === 3.U && !pfs(0) && !afs(0), 2.U(2.W), 1.U(2.W))
  val have0 = availableParcels >= len0
  val remaining = availableParcels - len0
  val nextParcel = parcels(len0)
  val nextPf = pfs(len0); val nextAf = afs(len0)
  val len1 = Mux(nextParcel(1, 0) === 3.U && !nextPf && !nextAf, 2.U(2.W), 1.U(2.W))
  val have1 = have0 && remaining >= len1
  val outputs = Wire(Vec(2, new PipelineInstruction(p)))
  val history = Wire(Vec(3, UInt(p.historyBits.W)))
  history(0) := specHistory
  val validOutput = Wire(Vec(2, Bool()))
  val firstTaken = Wire(Bool())
  val groupStacks = Seq.fill(3)(Wire(Vec(p.rasDepth, UInt(64.W))))
  val groupPtrs = Wire(Vec(3, UInt(rasPtrBits.W)))
  val groupCounts = Wire(Vec(3, UInt(rasCountBits.W)))
  groupStacks(0) := specStack; groupPtrs(0) := specPtr; groupCounts(0) := specCount
  validOutput(0) := have0
  validOutput(1) := have1 && !firstTaken && !outputs(0).pageFault && !outputs(0).accessFault
  for (lane <- 0 until 2) {
    val offset = if (lane == 0) 0.U(2.W) else len0
    val length = if (lane == 0) len0 else len1
    val low = parcels(offset)
    val high = parcels((offset + 1.U)(1, 0))
    val raw = Mux(length === 1.U, low.pad(32), Cat(high, low))
    val decoder = Module(new BreezeCompressedDecoder)
    decoder.io.in := low
    val inst = Mux(length === 1.U, decoder.io.out, raw)
    val out = outputs(lane)
    out := 0.U.asTypeOf(out)
    out.pc := parcelPc(offset)
    out.eventPc := parcelPc(offset + (length === 2.U).asUInt)
    out.raw := raw; out.instruction := inst; out.length := length << 1
    out.compressed := length === 1.U; out.illegal := out.compressed && decoder.io.illegal
    out.pageFault := pfs(offset) || (length === 2.U && pfs((offset + 1.U)(1, 0)))
    out.accessFault := afs(offset) || (length === 2.U && afs((offset + 1.U)(1, 0)))
    out.faultSecondParcel := length === 2.U && !pfs(offset) && !afs(offset) &&
      (pfs((offset + 1.U)(1, 0)) || afs((offset + 1.U)(1, 0)))
    // CFI predecode runs alongside expansion. These compressed CFI forms
    // are legal by construction (JR/JALR excludes rs1=x0), so recovery must
    // not wait for the general decompressor/illegal mux.
    val safe = validOutput(lane) && !out.pageFault && !out.accessFault
    val cq1 = low(1, 0) === 1.U
    val cq2 = low(1, 0) === 2.U
    val branch = Mux(out.compressed, cq1 && low(15, 14) === 3.U, raw(6, 0) === 0x63.U)
    val jal = Mux(out.compressed, cq1 && low(15, 13) === 5.U, raw(6, 0) === 0x6f.U)
    val jalr = Mux(out.compressed, cq2 && low(15, 13) === 4.U &&
      !low(6, 2).orR && low(11, 7).orR, raw(6, 0) === 0x67.U && raw(14, 12) === 0.U)
    out.kind := Mux(safe, Mux(branch, 1.U, Mux(jal, 2.U, Mux(jalr, 3.U, 0.U))), 0.U)
    val rdLink = Mux(out.compressed, jalr && low(12), raw(11, 7) === 1.U || raw(11, 7) === 5.U)
    val rsLink = Mux(out.compressed, low(11, 7) === 1.U || low(11, 7) === 5.U,
      raw(19, 15) === 1.U || raw(19, 15) === 5.U)
    val distinctLinks = Mux(out.compressed, low(11, 7) =/= 1.U, raw(11, 7) =/= raw(19, 15))
    out.rasPush := safe && (jal || jalr) && rdLink
    out.rasPop := safe && jalr && rsLink && (!rdLink || distinctLinks)
    out.historyBefore := history(lane)
    out.phtIndex := out.eventPc(p.historyBits, 1) ^ history(lane)
    val candidates = VecInit((0 until 3).map(q => w.prediction.available(q) && w.prediction.index(q) === out.phtIndex))
    val direction = (candidates.asUInt & w.prediction.taken.asUInt).orR
    val slot = out.eventPc =/= w.pc
    val btb = w.prediction.btb(slot)
    val branchImm = Mux(out.compressed,
      Cat(Fill(56, low(12)), low(6, 5), low(2), low(11, 10), low(4, 3), 0.U(1.W)),
      Cat(Fill(51, raw(31)), raw(7), raw(30, 25), raw(11, 8), 0.U(1.W)))
    val jalImm = Mux(out.compressed,
      Cat(Fill(53, low(12)), low(8), low(10, 9), low(6), low(7), low(2), low(11), low(5, 3), 0.U(1.W)),
      Cat(Fill(43, raw(31)), raw(19, 12), raw(20), raw(30, 21), 0.U(1.W)))
    val jalrImm = Mux(out.compressed, 0.U(64.W), Cat(Fill(52, raw(31)), raw(31, 20)))
    val returnTarget = (groupStacks(lane)((groupPtrs(lane) - 1.U)(rasPtrBits - 1, 0)) + jalrImm) & ~1.U(64.W)
    val rasPredict = predictorEnabled.B && out.rasPop && groupCounts(lane) =/= 0.U
    out.predictedTaken := safe && ((branch && direction) || jal ||
      (jalr && (rasPredict || (btb.hit && btb.kind === 3.U))))
    out.predictedTarget := Mux(branch, out.pc + branchImm,
      Mux(jal, out.pc + jalImm, Mux(rasPredict, returnTarget, btb.target)))
    history(lane + 1) := Mux(safe && branch, append(history(lane), out.predictedTaken), history(lane))
    // An unknown indirect target may leave both slots on the sequential
    // speculative path. Apply both hints in order, including return bypass.
    val pop = out.rasPop && groupCounts(lane) =/= 0.U
    val afterPop = (groupPtrs(lane) - pop.asUInt)(rasPtrBits - 1, 0)
    val afterCount = groupCounts(lane) - pop.asUInt
    groupStacks(lane + 1) := groupStacks(lane)
    when(out.rasPush) { groupStacks(lane + 1)(afterPop) := parcelPc(offset +& length) }
    groupPtrs(lane + 1) := afterPop + out.rasPush.asUInt
    groupCounts(lane + 1) := Mux(out.rasPush && afterCount < p.rasDepth.U, afterCount + 1.U, afterCount)
  }
  firstTaken := validOutput(0) && outputs(0).predictedTaken
  val actualHistory = Mux(validOutput(1), history(2), history(1))
  val secondTaken = validOutput(1) && outputs(1).predictedTaken
  val sequentialNext = w.pc + Mux(w.mask(1), 4.U, 2.U)
  val actualNext = Mux(firstTaken, outputs(0).predictedTarget,
    Mux(secondTaken, outputs(1).predictedTarget, sequentialNext))
  val (_, predictedNext, predictedHistory) = preview(w)
  val countOut = validOutput(0).asUInt +& validOutput(1).asUInt
  queue.io.enq.bits.count := countOut
  queue.io.enq.bits.instructions := outputs
  queue.io.enq.valid := cache.io.response.valid && !olderCancel
  cache.io.response.ready := queue.io.enq.ready && !olderCancel
  val f2Fire = cache.io.response.fire
  val hasFault = (validOutput(0) && (outputs(0).pageFault || outputs(0).accessFault)) ||
    (validOutput(1) && (outputs(1).pageFault || outputs(1).accessFault))
  val f2Redirect = f2Fire && (w.prediction.history =/= specHistory ||
    actualNext =/= predictedNext || actualHistory =/= predictedHistory || hasFault)
  when(f2Redirect) { forecastHistory := actualHistory }
  cache.io.cancel := olderCancel
  cache.io.cancelYounger := f2Redirect
  cache.io.block := io.block || faultStop

  val consumed = Mux(have0, len0, 0.U) + Mux(validOutput(1), len1, 0.U)
  val pending = availableParcels > consumed && !firstTaken && !secondTaken && !hasFault
  when(f2Fire) {
    specHistory := actualHistory
    carryValid := pending
    when(pending) {
      assert(availableParcels - consumed === 1.U, "more than one incomplete parcel")
      carryParcel := parcels(consumed); carryPc := startPc + (consumed << 1)
      carryPageFault := pfs(consumed); carryAccessFault := afs(consumed)
    }
    when(hasFault) { faultStop := true.B }
    specStack := groupStacks(2); specPtr := groupPtrs(2); specCount := groupCounts(2)
  }
  // At most one opportunistic table repair per F2 group. Decode/PC correction
  // is independent of whether the update queue accepts this repair.
  val eventValid = Wire(Vec(2, Bool())); val eventKind = Wire(Vec(2, UInt(2.W)))
  for (slot <- 0 until 2) {
    val events = VecInit((0 until 2).map(i => validOutput(i) && outputs(i).eventPc === w.pc + (slot * 2).U))
    eventValid(slot) := events.asUInt.orR
    eventKind(slot) := Mux1H(events, outputs.map(_.kind))
  }
  val bad = VecInit((0 until 2).map { s =>
    val observedPrefix = if (s == 0) true.B else !firstTaken || outputs(0).eventPc =/= w.pc
    w.mask(s) && !hasFault && observedPrefix && w.prediction.btb(s).hit &&
      (!eventValid(s) || eventKind(s) === 0.U || eventKind(s) =/= w.prediction.btb(s).kind)
  })
  val direct = VecInit((0 until 2).map(i => validOutput(i) && outputs(i).kind === 2.U &&
    (!w.prediction.btb(outputs(i).eventPc =/= w.pc).hit ||
      w.prediction.btb(outputs(i).eventPc =/= w.pc).target =/= outputs(i).predictedTarget)))
  val directInstr = outputs(PriorityEncoder(direct))
  predictor.io.btbRepair.valid := predictorEnabled.B && f2Fire && (bad.asUInt.orR || direct.asUInt.orR)
  predictor.io.btbRepair.bits.pc := Mux(bad.asUInt.orR, w.pc + (PriorityEncoder(bad) << 1), directInstr.eventPc)
  predictor.io.btbRepair.bits.target := directInstr.predictedTarget
  predictor.io.btbRepair.bits.kind := directInstr.kind
  predictor.io.btbRepair.bits.invalidate := bad.asUInt.orR

  when(cache.io.request.fire) {
    pc := pc + Mux(pc(log2Ceil(g.lineBytes) - 1, 0) === (g.lineBytes - 2).U, 2.U, 4.U)
  }
  when(f1Redirect) { pc := earlyTarget }
  when(f2Redirect) { pc := actualNext }
  when(resolvedRedirect) {
    pc := io.resolve.bits.nextPc
    specHistory := Mux(resolvedConditional, append(io.resolve.bits.historyBefore, io.resolve.bits.taken), io.resolve.bits.historyBefore)
    forecastHistory := Mux(resolvedConditional, append(io.resolve.bits.historyBefore, io.resolve.bits.taken), io.resolve.bits.historyBefore)
    specStack := resolvedNextStack; specPtr := resolvedNextPtr; specCount := resolvedNextCount
    carryValid := false.B; faultStop := false.B
  }
  when(globalCancel) {
    pc := io.redirect.bits
    specHistory := Mux(io.committedBranch.valid, append(committedHistory, io.committedBranch.bits), committedHistory)
    forecastHistory := Mux(io.committedBranch.valid, append(committedHistory, io.committedBranch.bits), committedHistory)
    specCount := 0.U; resolvedCount := 0.U; specPtr := 0.U; resolvedPtr := 0.U
    carryValid := false.B; faultStop := false.B
  }
  when(reset.asBool) { pc := io.resetPc }
  when(io.fenceI) { assert(io.redirect.valid, "fence.i requires an explicit restart PC") }
  when(io.context.permissionEvent) {
    assert(io.redirect.valid || (io.block && cache.io.idle && queue.io.empty && !carryValid),
      "CSR permissions changed with old frontend queries in flight")
  }
  // Keep the latest stream-tail preview through stalls; a queued F2 head
  // precedes other windows and cannot supply the next query's history.
  // F2/EX/global repair overrides this register and kills this edge's query.
  // A canceled edge cannot accept a predictor query. Select its private
  // payload from registered ownership, keeping WB/EX cancellation out of
  // the history -> PHT address mux. Preview state/redirect side effects
  // still use f1Prediction.valid; admission and cancellation are unchanged.
  cache.io.request.bits.history := Mux(cache.io.f1PredictionPending, earlyHistory, forecastHistory)
  when(cache.io.predictorQuery.valid) {
    assert(cache.io.request.bits.history ===
      Mux(cache.io.f1Prediction.valid, earlyHistory, forecastHistory),
      "accepted history differs from cancellation-qualified preview")
  }
  when(f2Fire && carryValid) { assert(w.pc === carryPc + 2.U, "noncontiguous RVC continuation") }
  // Local frontend drain only; the integration layer observes MMU idle separately.
  io.idle := cache.io.idle && queue.io.empty && !carryValid
  io.hpm := 0.U.asTypeOf(io.hpm)
  io.hpm.icacheAccess := cache.io.request.fire
  io.hpm.icacheMiss := cache.io.refillReq.req
  io.debug.f0Fire := cache.io.request.fire; io.debug.f0Pc := pc
  io.debug.f1Fire := cache.io.f1Prediction.valid; io.debug.f2Fire := f2Fire
  io.debug.f1Redirect := f1Redirect; io.debug.f2Redirect := f2Redirect
  io.debug.specHistory := specHistory; io.debug.queryHistory := cache.io.request.bits.history
  io.debug.rasCount := specCount
}

object GenerateBreezeFrontendPipeline extends App {
  ChiselStage.emitSystemVerilogFile(new BreezeFrontendPipeline(), args,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable"))
}
