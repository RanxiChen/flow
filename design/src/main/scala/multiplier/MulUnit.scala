package flow.multiplier

import chisel3._
import chisel3.util._
import flow.backend.{IntMduIO, IntMulRequest}
import flow.core.MUL_OP

/** T01 four-register multiplier. The old wrapper remains until integration. */
class MulUnit extends Module {
  val io = IO(new IntMduIO(new IntMulRequest))
  val valid = RegInit(VecInit(Seq.fill(4)(false.B)))
  val committed = RegInit(VecInit(Seq.fill(4)(false.B)))
  val product = Reg(Vec(4, SInt(130.W)))
  val op = Reg(Vec(4, UInt(3.W)))
  val rd = Reg(Vec(4, UInt(5.W)))

  val mulEnable = !valid(3) || (committed(3) && io.result.ready)
  io.req.ready := mulEnable
  io.result.valid := valid(3) && committed(3)
  io.result.bits.rd := rd(3)
  val bits = product(3).asUInt
  io.result.bits.data := MuxLookup(op(3), bits(63, 0))(Seq(
    MUL_OP.MUL.U -> bits(63, 0),
    MUL_OP.MULH.U -> bits(127, 64),
    MUL_OP.MULHSU.U -> bits(127, 64),
    MUL_OP.MULHU.U -> bits(127, 64),
    MUL_OP.MULW.U -> Cat(Fill(32, bits(31)), bits(31, 0))))

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
      product(i) := product(i - 1)
      op(i) := op(i - 1)
      rd(i) := rd(i - 1)
    }
    valid(0) := io.req.fire && !io.killUncommitted
    committed(0) := false.B
    when(io.req.fire && !io.killUncommitted) {
      product(0) := io.req.bits.a * io.req.bits.b
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
  val heldProduct = RegNext(product.asUInt)
  val heldOp = RegNext(op.asUInt)
  val heldRd = RegNext(rd.asUInt)
  when(pastActive && !reset.asBool) {
    when(wasBlocked) {
      assert(io.result.valid && io.result.bits.asUInt === heldResult, "[MDU S07] stalled MUL result changed")
    }
    when(wasStopped) {
      assert(product.asUInt === heldProduct && op.asUInt === heldOp && rd.asUInt === heldRd,
        "[MDU S11] stopped MUL payload advanced")
    }
  }
}
