package flow.accelerator.matrix

import chisel3._

/** One output-stationary integer processing element.
  *
  * A travels from left to right and B travels from top to bottom.  The PE
  * accumulates only when both operands are valid on the same cycle.  The
  * registered forwarding path is deliberately independent of the accumulator
  * so bubbles can move through the array without changing the result.
  */
class SystolicPE(inputWidth: Int = 8, accWidth: Int = 32) extends Module {
  require(inputWidth > 0)
  require(accWidth >= 2 * inputWidth)

  val io = IO(new Bundle {
    val clear = Input(Bool())
    val loadAccumulator = Input(Bool())
    val accumulatorIn = Input(SInt(accWidth.W))

    val aIn = Input(SInt(inputWidth.W))
    val aValidIn = Input(Bool())
    val bIn = Input(SInt(inputWidth.W))
    val bValidIn = Input(Bool())

    val aOut = Output(SInt(inputWidth.W))
    val aValidOut = Output(Bool())
    val bOut = Output(SInt(inputWidth.W))
    val bValidOut = Output(Bool())
    val accumulator = Output(SInt(accWidth.W))
  })

  val aReg = RegInit(0.S(inputWidth.W))
  val aValidReg = RegInit(false.B)
  val bReg = RegInit(0.S(inputWidth.W))
  val bValidReg = RegInit(false.B)
  val accReg = RegInit(0.S(accWidth.W))

  when(io.clear) {
    aReg := 0.S
    aValidReg := false.B
    bReg := 0.S
    bValidReg := false.B
    accReg := 0.S
  }.otherwise {
    aReg := io.aIn
    aValidReg := io.aValidIn
    bReg := io.bIn
    bValidReg := io.bValidIn

    when(io.loadAccumulator) {
      accReg := io.accumulatorIn
    }.elsewhen(io.aValidIn && io.bValidIn) {
      val product = io.aIn * io.bIn
      accReg := accReg +% product.pad(accWidth)
    }
  }

  io.aOut := aReg
  io.aValidOut := aValidReg
  io.bOut := bReg
  io.bValidOut := bValidReg
  io.accumulator := accReg
}
