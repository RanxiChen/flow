package flow.divider

import chisel3._

/** Sign restoration and RV64 W-result wrapper for the unsigned divider core. */
class RiscvDivUnit extends Module {
  val io = IO(new Bundle {
    val flush = Input(Bool())
    val in_valid = Input(Bool())
    val dividend_mag = Input(UInt(64.W))
    val divisor_mag = Input(UInt(64.W))
    val quotient_neg = Input(Bool())
    val remainder_neg = Input(Bool())
    val is_remainder = Input(Bool())
    val is_word = Input(Bool())
    val busy = Output(Bool())
    val out_valid = Output(Bool())
    val result = Output(UInt(64.W))
  })

  val divider = Module(new UnsignedRadix4Divider)
  divider.io.flush := io.flush
  divider.io.in_valid := io.in_valid
  divider.io.dividend := io.dividend_mag
  divider.io.divisor := io.divisor_mag

  val quotientNegReg = RegEnable(io.quotient_neg, false.B, io.in_valid)
  val remainderNegReg = RegEnable(io.remainder_neg, false.B, io.in_valid)
  val isRemainderReg = RegEnable(io.is_remainder, false.B, io.in_valid)
  val isWordReg = RegEnable(io.is_word, false.B, io.in_valid)

  val signedQuotient = Mux(
    quotientNegReg,
    0.U(64.W) - divider.io.quotient,
    divider.io.quotient
  )
  val signedRemainder = Mux(
    remainderNegReg,
    0.U(64.W) - divider.io.remainder,
    divider.io.remainder
  )
  val selectedResult = Mux(isRemainderReg, signedRemainder, signedQuotient)
  val wordResult = Cat(Fill(32, selectedResult(31)), selectedResult(31, 0))

  io.busy := divider.io.busy
  io.out_valid := divider.io.out_valid
  io.result := Mux(isWordReg, wordResult, selectedResult)
}
