package flow.frontend.experiment

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import flow.frontend._

class HistoryQuery extends Bundle {
  val pc = UInt(64.W)
  val hit = Vec(2, Bool())
  val kind = Vec(2, UInt(2.W))
  val pageFault = Vec(2, Bool())
  val accessFault = Vec(2, Bool())
  val mask1 = Bool()
}

/** Prediction-only extraction: original PHT + original F1 preview/history mux.
  * BTB hits/kinds and fault/mask metadata are supplied at the query boundary,
  * captured beside the synchronous PHT read. Actual BTB combinational delay,
  * PC redirect, cache admission, F2 repair and training-consumer timing are out
  * of scope. Training remains writable to preserve the real table circuitry.
  * Crucially, earlyHistory feeds the NEXT query index without an extra register.
  */
class BreezeGshareHistoryCore extends Module {
  val p = BreezeFrontendPipelineParams()
  val io = IO(new Bundle {
    val query = Flipped(Valid(new HistoryQuery))
    val train = Flipped(Decoupled(new PipelinePhtUpdate(p.historyBits)))
    val valid = Output(Bool())
    val indices = Output(Vec(3, UInt(p.historyBits.W)))
    val history = Output(UInt(p.historyBits.W))
    val earlyHistory = Output(UInt(p.historyBits.W))
    val available = Output(Vec(3, Bool()))
    val taken = Output(Vec(3, Bool()))
    val chosenTaken = Output(Vec(2, Bool()))
  })
  def append(h: UInt, bit: Bool): UInt = Cat(h(p.historyBits - 2, 0), bit)
  val pht = Module(new BreezeBankedPht(p))
  pht.io.train <> io.train
  val forecastHistory = RegInit(0.U(p.historyBits.W))
  val f1Pending = RegNext(io.query.valid, false.B)
  val f1Metadata = RegEnable(io.query.bits, io.query.valid)
  val queryHistory = Wire(UInt(p.historyBits.W))
  val f1History = RegEnable(queryHistory, io.query.valid)
  val ok0 = !f1Metadata.pageFault(0) && !f1Metadata.accessFault(0)
  val cond0 = ok0 && f1Metadata.hit(0) && f1Metadata.kind(0) === 1.U
  val t0 = ok0 && f1Metadata.hit(0) &&
    (f1Metadata.kind(0) >= 2.U || (cond0 && pht.io.taken(0)))
  val choice1 = Mux(cond0 && !t0, 2.U, 1.U)
  val ok1 = f1Metadata.mask1 && !f1Metadata.pageFault(1) &&
    !f1Metadata.accessFault(1) && !t0
  val cond1 = ok1 && f1Metadata.hit(1) && f1Metadata.kind(1) === 1.U
  val t1 = ok1 && f1Metadata.hit(1) &&
    (f1Metadata.kind(1) >= 2.U || (cond1 && pht.io.taken(choice1)))
  val h0 = Mux(cond0, append(f1History, t0), f1History)
  val earlyHistory = Mux(cond1, append(h0, t1), h0)
  when(f1Pending) { forecastHistory := earlyHistory }
  queryHistory := Mux(f1Pending, earlyHistory, forecastHistory)
  val shifted = append(queryHistory, false.B)
  val pc0 = io.query.bits.pc(p.historyBits, 1)
  val pc1 = (io.query.bits.pc + 2.U)(p.historyBits, 1)
  val indices = VecInit(Seq(pc0 ^ queryHistory, pc1 ^ queryHistory, pc1 ^ shifted))
  pht.io.query.valid := io.query.valid
  pht.io.query.bits := indices
  io.valid := f1Pending
  io.indices := RegEnable(indices, io.query.valid)
  io.history := f1History
  io.earlyHistory := earlyHistory
  io.available := pht.io.available
  io.taken := pht.io.taken
  io.chosenTaken := VecInit(Seq(t0, t1))
}

private class HistoryExperimentClock extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val in = Input(Clock()); val out = Output(Clock()) })
  setInline("HistoryExperimentClock.sv", """module HistoryExperimentClock(input in, output out);
    |`ifdef SYNTHESIS
    |  BUFGCE clock_buffer (.I(in), .CE(1'b1), .O(out));
    |`else
    |  assign out = in;
    |`endif
    |endmodule
    |""".stripMargin)
}

class BreezeGshareHistoryTimingHarness extends Module {
  val io = IO(new Bundle {
    val query = Flipped(Valid(new HistoryQuery))
    val train = Flipped(Valid(new PipelinePhtUpdate(10)))
    val resultValid = Output(Bool())
    val resultHistory = Output(UInt(10.W))
    val resultEarlyHistory = Output(UInt(10.W))
    val resultIndices = Output(Vec(3, UInt(10.W)))
    val resultAvailable = Output(Vec(3, Bool()))
    val resultTaken = Output(Vec(3, Bool()))
    val resultChosenTaken = Output(Vec(2, Bool()))
    val trainAccepted = Output(Bool())
  })
  private val globalClock = Module(new HistoryExperimentClock)
  globalClock.io.in := clock
  withClock(globalClock.io.out) {
    val core = Module(new BreezeGshareHistoryCore)
    core.io.query.valid := RegNext(io.query.valid, false.B)
    core.io.query.bits := RegNext(io.query.bits)
    core.io.train.valid := RegNext(io.train.valid, false.B)
    core.io.train.bits := RegNext(io.train.bits)
    io.resultValid := RegNext(core.io.valid, false.B)
    io.resultHistory := RegNext(core.io.history)
    io.resultEarlyHistory := RegNext(core.io.earlyHistory)
    io.resultIndices := RegNext(core.io.indices)
    io.resultAvailable := RegNext(core.io.available)
    io.resultTaken := RegNext(core.io.taken)
    io.resultChosenTaken := RegNext(core.io.chosenTaken)
    io.trainAccepted := RegNext(core.io.train.fire, false.B)
  }
}

object GenerateBreezeGshareHistoryTimingHarness extends App {
  ChiselStage.emitSystemVerilogFile(new BreezeGshareHistoryTimingHarness, args,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info",
      "-default-layer-specialization=enable"))
}
