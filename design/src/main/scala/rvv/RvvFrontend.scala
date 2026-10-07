package flow.rvv

import chisel3._
import chisel3.util._

/** Reservation covers the pending FIFO plus the committed VIQ. Translation tags
  * survive kills until their replies arrive. Interval lifetime is independent of
  * both instruction queues and VRF writeback. */
class RvvFrontend(p: RvvParams) extends Module {
  private val intervalDepth = p.viqDepth + p.memoryInflight + p.unitDepths.head
  val io = IO(new Bundle {
    val issue = Flipped(Decoupled(new RvvIssue(p)))
    val verdict = Decoupled(new RvvVerdict(p))
    val commit = Input(Bool()); val kill = Input(Bool()); val serialGo = Input(Bool())
    val translation = Decoupled(new RvvTranslationRequest(p))
    val translated = Flipped(Decoupled(new RvvTranslationResponse(p)))
    val dispatch = Decoupled(new RvvDescriptor(p))
    val scalarQuery = Input(new RvvConflict); val scalarConflict = Output(Bool())
    val vectorQuery = Input(new RvvDescriptor(p)); val vectorQueryValid = Input(Bool())
    val vectorConflict = Output(Bool())
    val released = Flipped(Valid(UInt(64.W)))
    val empty = Output(Bool()); val serialCount = Output(UInt(64.W))
  })
  val pending = Reg(Vec(p.viqDepth,new RvvDescriptor(p)))
  val prepared = RegInit(VecInit(Seq.fill(p.viqDepth)(false.B)))
  val sent = RegInit(VecInit(Seq.fill(p.viqDepth)(false.B)))
  val serial = RegInit(VecInit(Seq.fill(p.viqDepth)(false.B)))
  val stage = RegInit(VecInit(Seq.fill(p.viqDepth)(0.U(2.W))))
  val waiting = RegInit(VecInit(Seq.fill(p.viqDepth)(false.B)))
  val count = RegInit(0.U(log2Ceil(p.viqDepth+1).W))
  val nextAge = RegInit(0.U(64.W))
  val viq = Module(new Queue(new RvvDescriptor(p),p.viqDepth))
  io.dispatch <> viq.io.deq
  viq.io.enq.valid := io.commit
  viq.io.enq.bits := pending(0)
  val decoded = RvvDecode(io.issue.bits,p)
  val occupied = count +& viq.io.count

  class Interval extends Bundle {
    val age = UInt(64.W); val committed = Bool(); val store = Bool()
    val pa = Vec(2,UInt(64.W)); val length = Vec(2,UInt(p.lengthBits.W))
  }
  val intervals = Reg(Vec(intervalDepth,new Interval))
  val intervalValid = RegInit(VecInit(Seq.fill(intervalDepth)(false.B)))
  val intervalFree = PriorityEncoder(~intervalValid.asUInt)
  io.issue.ready := occupied < p.viqDepth.U && !io.kill &&
    (!decoded.memory || decoded.bytes === 0.U || !intervalValid.asUInt.andR)
  io.empty := count === 0.U && viq.io.count === 0.U
  val serialCount = RegInit(0.U(64.W)); io.serialCount := serialCount

  // The first not-yet-delivered verdict is authoritative; later translation
  // preparation may proceed while the core delays committing an older item.
  val judgementMask = VecInit((0 until p.viqDepth).map(j => j.U < count && !sent(j)))
  val judgementIndex = PriorityEncoder(judgementMask.asUInt)
  io.verdict.valid := judgementMask.asUInt.orR && prepared(judgementIndex) && !io.kill
  io.verdict.bits := 0.U.asTypeOf(new RvvVerdict(p))
  io.verdict.bits.kind := Mux(serial(judgementIndex),3.U,0.U)
  io.verdict.bits.newVl := pending(judgementIndex).issue.vl

  when(io.commit) {
    assert(count =/= 0.U && viq.io.enq.ready,"commit lacks its reserved VIQ slot")
    assert(prepared(0) && !serial(0) && (sent(0) || (io.verdict.fire && judgementIndex === 0.U)),
      "commit requires the oldest item to have received Ok")
    when(pending(0).decoded.memory && pending(0).decoded.bytes =/= 0.U) {
      printf(p"R02_INTERVAL_COMMIT age=${pending(0).age} pa=${pending(0).pa(0)} length=${pending(0).length(0)}\n")
    }
  }
  assert(occupied <= p.viqDepth.U,"reservation overflow")
  assert(!io.serialGo,"R02 serial execution is not implemented")

  for(j <- 0 until p.viqDepth-1) {
    when(io.commit) {
      pending(j) := pending(j+1); prepared(j) := prepared(j+1); sent(j) := sent(j+1)
      serial(j) := serial(j+1); stage(j) := stage(j+1); waiting(j) := waiting(j+1)
    }
  }
  when(io.commit) {
    prepared(p.viqDepth-1) := false.B; sent(p.viqDepth-1) := false.B
    waiting(p.viqDepth-1) := false.B
  }
  val finalCount = count - io.commit.asUInt
  val insertionIndex = finalCount(log2Ceil(p.viqDepth)-1,0)
  when(io.commit || io.issue.fire) { count := finalCount + io.issue.fire.asUInt }
  when(io.verdict.fire) {
    val dst = judgementIndex - io.commit.asUInt
    when(!(io.commit && judgementIndex === 0.U)) { sent(dst) := true.B }
    when(serial(judgementIndex)) { serialCount := serialCount + 1.U }
  }

  val tagLive = RegInit(VecInit(Seq.fill(p.translationIds)(false.B)))
  val tagAge = Reg(Vec(p.translationIds,UInt(64.W)))
  val tagPage = Reg(Vec(p.translationIds,Bool()))
  val freeTag = PriorityEncoder(~tagLive.asUInt)
  val prepMask = VecInit((0 until p.viqDepth).map(j => j.U < count && !prepared(j) && !waiting(j)))
  val prepIndex = PriorityEncoder(prepMask.asUInt)
  val candidate = pending(prepIndex)
  val dstPrep = prepIndex - io.commit.asUInt
  val noAccess = candidate.issue.vl === 0.U || candidate.issue.vstart >= candidate.issue.vl
  val needsTranslate = candidate.decoded.memory && !noAccess && candidate.decoded.fast
  io.translation.valid := prepMask.asUInt.orR && needsTranslate && !tagLive.asUInt.andR && !io.kill
  io.translation.bits.id := freeTag
  io.translation.bits.write := candidate.decoded.store
  io.translation.bits.va := Mux(stage(prepIndex) === 0.U,candidate.issue.rs1,
    (candidate.issue.rs1 & "hfffffffffffff000".U) + 4096.U)
  when(prepMask.asUInt.orR && !needsTranslate && !io.kill) {
    prepared(dstPrep) := true.B
    serial(dstPrep) := candidate.decoded.memory && !noAccess
  }
  when(io.translation.fire) {
    tagLive(freeTag) := true.B; tagAge(freeTag) := candidate.age
    tagPage(freeTag) := stage(prepIndex) === 1.U; waiting(dstPrep) := true.B
  }
  io.translated.ready := true.B
  when(io.translated.fire) {
    val t = io.translated.bits.id
    assert(t < p.translationIds.U && tagLive(t),"translation response without a live request")
    tagLive(t) := false.B
    val responseMatches = VecInit((0 until p.viqDepth).map(j => j.U < count && pending(j).age === tagAge(t)))
    val responseIndex = PriorityEncoder(responseMatches.asUInt)
    val responding = pending(responseIndex)
    val off = responding.issue.rs1(11,0)
    val first = Mux(responding.decoded.bytes < (4096.U-off),responding.decoded.bytes,4096.U-off)
    val second = responding.decoded.bytes-first
    val pa = Mux(tagPage(t),io.translated.bits.pa & "hfffffffffffff000".U,
      (io.translated.bits.pa & "hfffffffffffff000".U) | off)
    def update(dst: Int): Unit = {
      waiting(dst) := false.B
      pending(dst).pa(tagPage(t)) := pa
      pending(dst).length(0) := first; pending(dst).length(1) := second
      when(io.translated.bits.exception || io.translated.bits.device) {
        serial(dst) := true.B; prepared(dst) := true.B
      }.elsewhen(tagPage(t) || second === 0.U) { prepared(dst) := true.B }
        .otherwise { stage(dst) := 1.U }
    }
    for(j <- 0 until p.viqDepth) {
      when(j.U < count && pending(j).age === tagAge(t) && !io.kill) {
        assert(!(io.commit && j.U === 0.U))
        if(j==0) { update(0) }
        else { when(io.commit) { update(j-1) }.otherwise { update(j) } }
      }
    }
    for(k <- 0 until intervalDepth) {
      when(responseMatches.asUInt.orR && !io.kill && intervalValid(k) && intervals(k).age === tagAge(t)) {
        intervals(k).pa(tagPage(t)) := pa
        intervals(k).length(0) := first; intervals(k).length(1) := second
      }
    }
  }
  // Kill invalidates descriptors, not translation tags. An orphan response finds
  // no matching live age and is drained without affecting the new stream.
  when(io.kill) { count := 0.U; prepared.foreach(_ := false.B); sent.foreach(_ := false.B); waiting.foreach(_ := false.B) }
  when(io.issue.fire) {
    pending(insertionIndex) := 0.U.asTypeOf(new RvvDescriptor(p))
    pending(insertionIndex).issue := io.issue.bits; pending(insertionIndex).decoded := decoded
    pending(insertionIndex).age := nextAge
    prepared(insertionIndex) := false.B; sent(insertionIndex) := false.B; serial(insertionIndex) := false.B
    waiting(insertionIndex) := false.B; stage(insertionIndex) := 0.U; nextAge := nextAge + 1.U
    assert(decoded.supported,"R02 received an unimplemented instruction")
    when(decoded.memory && decoded.bytes =/= 0.U) {
      printf(p"R02_INTERVAL_ALLOC age=${nextAge} index=${intervalFree} bytes=${decoded.bytes}\n")
      intervalValid(intervalFree) := true.B
      intervals(intervalFree) := 0.U.asTypeOf(new Interval)
      intervals(intervalFree).age := nextAge; intervals(intervalFree).store := decoded.store
    }
  }
  for(k <- 0 until intervalDepth) {
    val commitNow = io.commit && count =/= 0.U && intervals(k).age === pending(0).age
    when(commitNow) { intervals(k).committed := true.B }
    when(io.kill && !intervals(k).committed && !commitNow) {
      when(intervalValid(k)) { printf(p"R02_INTERVAL_KILL age=${intervals(k).age} index=${k.U}\n") }
      intervalValid(k) := false.B
    }
    when(io.released.valid && intervals(k).age === io.released.bits) {
      when(intervalValid(k)) { printf(p"R02_INTERVAL_RELEASE age=${intervals(k).age} index=${k.U}\n") }
      intervalValid(k) := false.B
    }
  }
  def overlap(a: UInt, al: UInt, b: UInt, bl: UInt): Bool =
    al =/= 0.U && bl =/= 0.U && a < b +& bl && b < a +& al
  val scalarHits = (0 until intervalDepth).map { k =>
    val x = intervals(k)
    val commitNow = io.commit && count =/= 0.U && x.age === pending(0).age
    intervalValid(k) && (x.committed || commitNow) && (x.store || io.scalarQuery.write) &&
      (0 until 2).map(s => overlap(x.pa(s),x.length(s),io.scalarQuery.pa,io.scalarQuery.bytes)).reduce(_ || _)
  }
  io.scalarConflict := io.scalarQuery.valid && scalarHits.reduce(_ || _)
  val vectorHits = (0 until intervalDepth).map { k =>
    val x = intervals(k); val q = io.vectorQuery
    intervalValid(k) && x.committed && x.age < q.age && (x.store || q.decoded.store) &&
      (for(a <- 0 until 2;b <- 0 until 2) yield overlap(x.pa(a),x.length(a),q.pa(b),q.length(b))).reduce(_ || _)
  }
  io.vectorConflict := io.vectorQueryValid && vectorHits.reduce(_ || _)
}
