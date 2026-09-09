package flow.accelerator.matrix

import chisel3._

/** Rectangular output-stationary systolic array.
  *
  * The caller supplies already-skewed edge streams: row i's A stream is
  * delayed by i cycles and column j's B stream is delayed by j cycles.  This
  * keeps data packing/feeding policy outside the reusable compute array.
  */
class SystolicArray(
    rows: Int = 4,
    cols: Int = 4,
    inputWidth: Int = 8,
    accWidth: Int = 32) extends Module {
  require(rows > 0)
  require(cols > 0)

  val io = IO(new Bundle {
    val clear = Input(Bool())
    val loadAccumulator = Input(Vec(rows, Vec(cols, Bool())))
    val accumulatorIn = Input(Vec(rows, Vec(cols, SInt(accWidth.W))))
    val aIn = Input(Vec(rows, SInt(inputWidth.W)))
    val aValid = Input(Vec(rows, Bool()))
    val bIn = Input(Vec(cols, SInt(inputWidth.W)))
    val bValid = Input(Vec(cols, Bool()))
    val accumulators = Output(Vec(rows, Vec(cols, SInt(accWidth.W))))
  })

  private val pes = Seq.tabulate(rows, cols) { case (_, _) =>
    Module(new SystolicPE(inputWidth, accWidth))
  }

  for (row <- 0 until rows; col <- 0 until cols) {
    val pe = pes(row)(col)
    pe.io.clear := io.clear
    pe.io.loadAccumulator := io.loadAccumulator(row)(col)
    pe.io.accumulatorIn := io.accumulatorIn(row)(col)

    if (col == 0) {
      pe.io.aIn := io.aIn(row)
      pe.io.aValidIn := io.aValid(row)
    } else {
      pe.io.aIn := pes(row)(col - 1).io.aOut
      pe.io.aValidIn := pes(row)(col - 1).io.aValidOut
    }

    if (row == 0) {
      pe.io.bIn := io.bIn(col)
      pe.io.bValidIn := io.bValid(col)
    } else {
      pe.io.bIn := pes(row - 1)(col).io.bOut
      pe.io.bValidIn := pes(row - 1)(col).io.bValidOut
    }

    io.accumulators(row)(col) := pe.io.accumulator
  }
}

/** A synthesis-only driver that makes every PE observable to Quartus.
  *
  * This is not the future CSR/SPM controller.  It exists solely to measure the
  * resource cost of adding the compute array beside Wisp without allowing
  * constant propagation to erase it.
  */
class SystolicArrayAreaProbe extends Module {
  val io = IO(new Bundle {
    val probe = Output(Bool())
  })

  val counter = RegInit(0.U(8.W))
  counter := counter + 1.U

  val array = Module(new SystolicArray())
  array.io.clear := counter === 0.U
  for (row <- 0 until 4; col <- 0 until 4) {
    array.io.loadAccumulator(row)(col) := false.B
    array.io.accumulatorIn(row)(col) := 0.S
  }
  for (lane <- 0 until 4) {
    array.io.aIn(lane) := (counter + (lane + 1).U)(7, 0).asSInt
    array.io.bIn(lane) := (counter ^ (0x31 + lane * 0x17).U(8.W)).asSInt
    array.io.aValid(lane) := true.B
    array.io.bValid(lane) := true.B
  }

  io.probe := array.io.accumulators.asUInt.xorR
}
