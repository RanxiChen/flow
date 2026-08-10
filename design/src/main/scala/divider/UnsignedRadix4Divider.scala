package flow.divider

import chisel3._
import chisel3.util._

/** Single-request unsigned iterative divider.
  *
  * Each cycle performs two restoring radix-2 quotient steps, which is a
  * radix-4 outer iteration.  Operands are aligned by their leading-one
  * positions, so latency is data dependent and the worst RV64 case takes
  * 32 iterations (RV32 magnitudes take at most 16).
  */
class UnsignedRadix4Divider extends Module {
  val io = IO(new Bundle {
    val flush = Input(Bool())
    val in_valid = Input(Bool())
    val dividend = Input(UInt(64.W))
    val divisor = Input(UInt(64.W))
    val busy = Output(Bool())
    val out_valid = Output(Bool())
    val quotient = Output(UInt(64.W))
    val remainder = Output(UInt(64.W))
  })

  val busyReg = RegInit(false.B)
  val outValidReg = RegInit(false.B)
  val quotientReg = RegInit(0.U(64.W))
  val remainderReg = RegInit(0.U(64.W))
  val divisorReg = RegInit(0.U(64.W))
  val shiftReg = RegInit(0.U(6.W))

  val dividendLz = PriorityEncoder(Reverse(io.dividend))
  val divisorLz = PriorityEncoder(Reverse(io.divisor))
  val dividendMsb = 63.U(6.W) - dividendLz
  val divisorMsb = 63.U(6.W) - divisorLz
  val initialShift = dividendMsb - divisorMsb

  // First restoring quotient bit in this outer iteration.
  val alignedDivisor1 = (divisorReg << shiftReg)(63, 0)
  val canSubtract1 = remainderReg >= alignedDivisor1
  val remainder1 = Mux(canSubtract1, remainderReg - alignedDivisor1, remainderReg)
  val quotientBit1 = (1.U(64.W) << shiftReg)(63, 0)
  val quotient1 = Mux(canSubtract1, quotientReg | quotientBit1, quotientReg)

  // Second restoring quotient bit.  It is disabled when the first bit was q0.
  val secondActive = shiftReg =/= 0.U
  val secondShift = Mux(secondActive, shiftReg - 1.U, 0.U)
  val alignedDivisor2 = (divisorReg << secondShift)(63, 0)
  val canSubtract2 = secondActive && (remainder1 >= alignedDivisor2)
  val remainder2 = Mux(canSubtract2, remainder1 - alignedDivisor2, remainder1)
  val quotientBit2 = (1.U(64.W) << secondShift)(63, 0)
  val quotient2 = Mux(canSubtract2, quotient1 | quotientBit2, quotient1)

  io.busy := busyReg
  io.out_valid := outValidReg
  io.quotient := quotientReg
  io.remainder := remainderReg

  assert(!(io.in_valid && busyReg),
    "[UnsignedRadix4Divider] request arrived while divider was busy")

  when(io.flush) {
    busyReg := false.B
    outValidReg := false.B
  }.otherwise {
    outValidReg := false.B

    when(io.in_valid && !busyReg) {
      quotientReg := 0.U
      divisorReg := io.divisor

      when(io.divisor === 0.U) {
        // Defensive unsigned semantics; architectural divide-by-zero normally
        // takes the EXE fast path and does not enter this unit.
        quotientReg := Fill(64, 1.U(1.W))
        remainderReg := io.dividend
        outValidReg := true.B
      }.elsewhen(io.dividend === 0.U || io.dividend < io.divisor) {
        remainderReg := io.dividend
        outValidReg := true.B
      }.elsewhen(io.dividend === io.divisor) {
        quotientReg := 1.U
        remainderReg := 0.U
        outValidReg := true.B
      }.otherwise {
        remainderReg := io.dividend
        shiftReg := initialShift
        busyReg := true.B
      }
    }.elsewhen(busyReg) {
      quotientReg := quotient2
      remainderReg := remainder2
      when(shiftReg <= 1.U) {
        busyReg := false.B
        outValidReg := true.B
      }.otherwise {
        shiftReg := shiftReg - 2.U
      }
    }
  }
}
