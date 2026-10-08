package flow.multiplier

import chisel3._
import chisel3.util._
import flow.backend.{IntMduIO, IntMulRequest}
import flow.core.MUL_OP

/** Four-edge multiplier with explicit DSP input/M/P registers and a fabric CPA. */
class MulUnit extends Module {
  val io = IO(new IntMduIO(new IntMulRequest))
  val valid = RegInit(VecInit(Seq.fill(4)(false.B)))
  val committed = RegInit(VecInit(Seq.fill(4)(false.B)))
  val product = Reg(SInt(130.W))
  val resultData = Reg(UInt(64.W))
  val op = Reg(Vec(4, UInt(3.W)))
  val rd = Reg(Vec(4, UInt(5.W)))

  val mulEnable = !valid(3) || (committed(3) && io.result.ready)
  io.req.ready := mulEnable
  io.result.valid := valid(3) && committed(3)
  io.result.bits.rd := rd(3)
  io.result.bits.data := resultData

  // Twelve independent DSPs, no cascade or inferred register retiming.
  val shiftsA = Seq(0, 24, 48)
  val shiftsB = Seq(0, 17, 34, 51)
  val tiles = for (aShift <- shiftsA; bShift <- shiftsB) yield {
    val tile = Module(new MulDspTile)
    tile.io.clk := clock
    tile.io.ce := mulEnable
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

  val uncommitted = VecInit((0 until 4).map(i => valid(i) && !committed(i)))
  // P4 is oldest. Resolve the old slots before shifting them or accepting P1.
  val authorize = VecInit((0 until 4).map(i =>
    io.commit && uncommitted(i) && !(i + 1 until 4).map(uncommitted(_)).foldLeft(false.B)(_ || _)))
  val resolvedCommitted = VecInit((0 until 4).map(i => committed(i) || authorize(i)))
  val resolvedValid = VecInit((0 until 4).map(i =>
    valid(i) && (!io.killUncommitted || resolvedCommitted(i))))

  when(mulEnable) {
    for (i <- 1 until 4) {
      valid(i) := resolvedValid(i - 1)
      committed(i) := resolvedCommitted(i - 1)
      op(i) := op(i - 1)
      rd(i) := rd(i - 1)
    }
    operandA(0) := io.req.bits.a.asUInt
    operandB(0) := io.req.bits.b.asUInt
    for (i <- 1 until 3) {
      operandA(i) := operandA(i - 1)
      operandB(i) := operandB(i - 1)
    }
    product := finalBits.asSInt
    resultData := selectedResult
    valid(0) := io.req.fire && !io.killUncommitted
    committed(0) := false.B
    when(io.req.fire && !io.killUncommitted) {
      op(0) := io.req.bits.op
      rd(0) := io.req.bits.rd
    }
  }.otherwise {
    valid := resolvedValid
    committed := resolvedCommitted
  }

  when(!reset.asBool) {
    assert(!io.commit || uncommitted.asUInt.orR, "[MDU S04] MUL commit without an uncommitted item")
    assert(!(io.killUncommitted && io.req.fire), "[MDU] WB kill must suppress younger EX request")
    assert(!io.req.fire || io.req.bits.rd =/= 0.U, "[MDU S01] MUL x0 must not issue")
    assert(PopCount(authorize) <= 1.U, "[MDU S04] MUL commit must authorize exactly one oldest item")
  }
  val pastActive = RegNext(!reset.asBool, false.B)
  val wasBlocked = RegNext(io.result.valid && !io.result.ready, false.B)
  val heldResult = RegNext(io.result.bits.asUInt)
  val wasStopped = RegNext(!mulEnable, false.B)
  val payload = Cat(product.asUInt, resultData, operandA.asUInt, operandB.asUInt,
    VecInit(tiles.map(_._1.io.p)).asUInt)
  val heldProduct = RegNext(payload)
  val heldOp = RegNext(op.asUInt)
  val heldRd = RegNext(rd.asUInt)
  when(pastActive && !reset.asBool) {
    when(wasBlocked) {
      assert(io.result.valid && io.result.bits.asUInt === heldResult, "[MDU S07] stalled MUL result changed")
    }
    when(wasStopped) {
      assert(payload === heldProduct && op.asUInt === heldOp && rd.asUInt === heldRd,
        "[MDU S11] stopped MUL payload advanced")
    }
  }
}
