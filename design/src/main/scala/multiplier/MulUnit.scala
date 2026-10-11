package flow.multiplier

import chisel3._
import chisel3.util._
import flow.backend.{IntMduIO, IntMulRequest, IntMduResult}
import flow.core.MUL_OP

/** Three always-advancing DSP stages followed by registered CPA results.
  * Eight credits cover ALL accepted items, including the DSP stages: every
  * outstanding item can therefore end in the eight-slot result store even
  * if writeback stops indefinitely. DSP slots are not additional credits.
  */
class MulUnit(val registeredCommit: Boolean = false) extends Module {
  val io = IO(new IntMduIO(new IntMulRequest))
  val capacity = 8
  val valid = RegInit(VecInit(Seq.fill(3)(false.B)))
  val committed = RegInit(VecInit(Seq.fill(3)(false.B)))
  val product = Reg(SInt(130.W))
  val op = Reg(Vec(3, UInt(3.W)))
  val rd = Reg(Vec(3, UInt(5.W)))
  val results = Reg(Vec(capacity, new IntMduResult))
  val head = RegInit(0.U(log2Ceil(capacity).W))
  val tail = RegInit(0.U(log2Ceil(capacity).W))
  val count = RegInit(0.U(log2Ceil(capacity + 1).W))
  // In-order authorization makes committed results a prefix of the ring.
  val committedCount = RegInit(0.U(log2Ceil(capacity + 1).W))
  val outstanding = count +& PopCount(valid)
  io.req.ready := outstanding < capacity.U
  val authorizeResult = io.commit && count > committedCount
  // R4 is established before R2: the caller still supplies an unregistered
  // WB commit. Keep its legacy one-edge authorization boundary here. R2 may
  // add same-cycle commitD authorization only after that token is registered.
  io.result.valid := count =/= 0.U && (committedCount =/= 0.U || (registeredCommit.B && authorizeResult))
  io.result.bits := results(head)

  // Twelve independent DSPs, no cascade or inferred register retiming.
  val shiftsA = Seq(0, 24, 48)
  val shiftsB = Seq(0, 17, 34, 51)
  val tiles = for (aShift <- shiftsA; bShift <- shiftsB) yield {
    val tile = Module(new MulDspTile)
    tile.io.clk := clock
    tile.io.ce := true.B
    tile.io.a := (io.req.bits.a.asUInt(63, 0) >> aShift).pad(64)(23, 0)
    tile.io.b := (io.req.bits.b.asUInt(63, 0) >> bShift).pad(64)(16, 0)
    (tile, aShift + bShift)
  }
  // Keep the full signed 65x65 semantics, including the reference probe.
  // a = low64(a) - a[64]*2^64, likewise b. Corrections are only bit rows.
  val operandA = Reg(Vec(3, UInt(65.W)))
  val operandB = Reg(Vec(3, UInt(65.W)))
  val a2 = operandA(2)
  val b2 = operandB(2)
  val correctionA = Mux(a2(64), ~(b2(63, 0).pad(130) << 64)(129, 0), 0.U(130.W))
  val correctionB = Mux(b2(64), ~(a2(63, 0).pad(130) << 64)(129, 0), 0.U(130.W))
  val correctionCarry = a2(64).asUInt +& b2(64).asUInt
  val correctionTop = Cat(0.U(1.W), a2(64) && b2(64), 0.U(128.W))
  val rows = tiles.map { case (tile, shift) => (tile.io.p.pad(130) << shift)(129, 0) } ++
    Seq(correctionA, correctionB, correctionCarry.pad(130), correctionTop(129, 0))
  // Carry-save compression: no serial chain of wide carry-propagating adds.
  def compress(input: Seq[UInt]): Seq[UInt] = {
    if (input.size <= 2) input
    else compress(input.grouped(3).toSeq.flatMap {
      case Seq(x, y, z) => Seq(x ^ y ^ z, (((x & y) | (x & z) | (y & z)) << 1)(129, 0))
      case rest => rest
    })
  }
  val compressed = compress(rows)
  val finalBits = (compressed(0) + compressed(1))(129, 0)
  val selectedResult = MuxLookup(op(2), finalBits(63, 0))(Seq(
    MUL_OP.MUL.U -> finalBits(63, 0),
    MUL_OP.MULH.U -> finalBits(127, 64),
    MUL_OP.MULHSU.U -> finalBits(127, 64),
    MUL_OP.MULHU.U -> finalBits(127, 64),
    MUL_OP.MULW.U -> Cat(Fill(32, finalBits(31)), finalBits(31, 0))))

  val uncommitted = VecInit((0 until 3).map(i => valid(i) && !committed(i)))
  // Buffered work is older than every DSP slot. Resolve commit before kill.
  val authorize = VecInit((0 until 3).map(i =>
    io.commit && !authorizeResult && uncommitted(i) &&
      !(i + 1 until 3).map(uncommitted(_)).foldLeft(false.B)(_ || _)))
  val resolvedCommitted = VecInit((0 until 3).map(i => committed(i) || authorize(i)))
  val resolvedValid = VecInit((0 until 3).map(i =>
    valid(i) && (!io.killUncommitted || resolvedCommitted(i))))
  val enqueue = resolvedValid(2)
  val enqueueCommitted = enqueue && resolvedCommitted(2)
  val authorizedCount = committedCount + authorizeResult.asUInt
  val retainedCount = Mux(io.killUncommitted, authorizedCount, count)
  val insert = Mux(io.killUncommitted,
    (head + authorizedCount)(log2Ceil(capacity) - 1, 0), tail)
  when(enqueue) {
    results(insert).rd := rd(2)
    results(insert).data := selectedResult
  }
  tail := insert + enqueue.asUInt
  when(io.result.fire) { head := head + 1.U }
  count := retainedCount + enqueue.asUInt - io.result.fire.asUInt
  committedCount := authorizedCount + enqueueCommitted.asUInt - io.result.fire.asUInt

  // No grant/occupancy controls arithmetic or DSP metadata advancement.
  for (i <- 1 until 3) {
    valid(i) := resolvedValid(i - 1)
    committed(i) := resolvedCommitted(i - 1)
    op(i) := op(i - 1)
    rd(i) := rd(i - 1)
    operandA(i) := operandA(i - 1)
    operandB(i) := operandB(i - 1)
  }
  operandA(0) := io.req.bits.a.asUInt
  operandB(0) := io.req.bits.b.asUInt
  product := finalBits.asSInt
  valid(0) := io.req.fire && !io.killUncommitted
  committed(0) := false.B
  when(io.req.fire && !io.killUncommitted) {
    op(0) := io.req.bits.op
    rd(0) := io.req.bits.rd
  }

  when(!reset.asBool) {
    assert(!io.commit || authorizeResult || uncommitted.asUInt.orR,
      "[MDU S04] MUL commit without an uncommitted item")
    assert(!(io.killUncommitted && io.req.fire), "[MDU] WB kill must suppress younger EX request")
    assert(!io.req.fire || io.req.bits.rd =/= 0.U, "[MDU S01] MUL x0 must not issue")
    assert(PopCount(authorize) +& authorizeResult.asUInt <= 1.U,
      "[MDU S04] MUL commit must authorize exactly one oldest item")
    assert(outstanding <= capacity.U && committedCount <= count, "[BE2 R4] MUL ownership overflow")
    assert(!enqueue || retainedCount < capacity.U, "[BE2 N03] MUL result store overflow")
  }
  val pastActive = RegNext(!reset.asBool, false.B)
  val wasBlocked = RegNext(io.result.valid && !io.result.ready, false.B)
  val heldResult = RegNext(io.result.bits.asUInt)
  when(pastActive && !reset.asBool) {
    when(wasBlocked) {
      assert(io.result.valid && io.result.bits.asUInt === heldResult, "[MDU S07] stalled MUL result changed")
    }
  }
}
