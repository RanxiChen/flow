package flow.multiplier

import chisel3._
import chisel3.util._
import flow.core.MUL_OP

/** RV64 multiplication completion wrapper around [[SignedMul65x65]].
  *
  * The operands are already sign/zero extended to the signed 65-bit encoding
  * required by the datapath.  The cancelable valid pipeline prevents a result
  * launched before a redirect from being mistaken for a later request.
  */
class RiscvMulUnit extends Module {
  val io = IO(new Bundle {
    val flush = Input(Bool())
    val in_valid = Input(Bool())
    val a = Input(SInt(65.W))
    val b = Input(SInt(65.W))
    val op = Input(UInt(MUL_OP.width.W))
    val out_valid = Output(Bool())
    val result = Output(UInt(64.W))
  })

  val multiplier = Module(new SignedMul65x65)
  multiplier.io.in_valid := io.in_valid
  multiplier.io.a := io.a
  multiplier.io.b := io.b

  val valid1 = RegInit(false.B)
  val valid2 = RegInit(false.B)
  val valid3 = RegInit(false.B)
  val op1 = RegEnable(io.op, MUL_OP.XXX.U, io.in_valid)
  val op2 = RegEnable(op1, MUL_OP.XXX.U, valid1)
  val op3 = RegEnable(op2, MUL_OP.XXX.U, valid2)

  when(io.flush) {
    valid1 := false.B
    valid2 := false.B
    valid3 := false.B
  }.otherwise {
    valid1 := io.in_valid
    valid2 := valid1
    valid3 := valid2
  }

  val product = multiplier.io.product.asUInt
  val highResult = product(127, 64)
  val wordResult = Cat(Fill(32, product(31)), product(31, 0))

  io.out_valid := multiplier.io.out_valid && valid3
  io.result := MuxLookup(op3, product(63, 0))(
    Seq(
      MUL_OP.MUL.U -> product(63, 0),
      MUL_OP.MULH.U -> highResult,
      MUL_OP.MULHSU.U -> highResult,
      MUL_OP.MULHU.U -> highResult,
      MUL_OP.MULW.U -> wordResult
    )
  )

  assert(!valid3 || multiplier.io.out_valid,
    "[RiscvMulUnit] completion token is not aligned with multiplier output")
}
