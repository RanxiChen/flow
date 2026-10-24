package flow.frontend.experiment

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import flow.frontend.{BreezeBankedBtb, BreezeFrontendPipelineParams, PipelineBtbHit, PipelineBtbUpdate}

private class BtbExperimentClock extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val in = Input(Clock()); val out = Output(Clock()) })
  setInline("BtbExperimentClock.sv", """module BtbExperimentClock(input in, output out);
    |`ifdef SYNTHESIS
    |  BUFGCE clock_buffer (.I(in), .CE(1'b1), .O(out));
    |`else
    |  assign out = in;
    |`endif
    |endmodule
    |""".stripMargin)
}

/** OOC fixture around the unmodified production BTB.
  * Inputs and all BTB outputs have fixture registers. Valid update stimulus is
  * sampled unconditionally; updateAccepted acknowledges the registered offer
  * on its actual BTB enqueue edge. This boundary is not a Decoupled producer.
  * A query sampled on edge N has resultValid/output after edge N+2 (II=1).
  * No predictor table, PC recurrence, PHT, fetch array, translation or PMP is added.
  */
class BreezeBtbTimingHarness extends Module {
  val io = IO(new Bundle {
    val query = Flipped(Valid(UInt(64.W)))
    val update = Flipped(Valid(new PipelineBtbUpdate))
    val repair = Flipped(Valid(new PipelineBtbUpdate))
    val resultValid = Output(Bool())
    val result = Output(Vec(2, new PipelineBtbHit))
    val updateReady = Output(Bool())
    val updateAccepted = Output(Bool())
    val allocated = Output(Bool())
    val evicted = Output(Bool())
  })
  private val globalClock = Module(new BtbExperimentClock)
  globalClock.io.in := clock
  withClock(globalClock.io.out) {
    val btb = Module(new BreezeBankedBtb(BreezeFrontendPipelineParams()))
    val queryValid = RegNext(io.query.valid, false.B)
    btb.io.query.valid := queryValid
    btb.io.query.bits := RegNext(io.query.bits)
    btb.io.update.valid := RegNext(io.update.valid, false.B)
    btb.io.update.bits := RegNext(io.update.bits)
    btb.io.repair.valid := RegNext(io.repair.valid, false.B)
    btb.io.repair.bits := RegNext(io.repair.bits)
    val coreResultValid = RegNext(queryValid, false.B)
    io.resultValid := RegNext(coreResultValid, false.B)
    io.result := RegNext(btb.io.result)
    io.updateReady := RegNext(btb.io.update.ready, false.B)
    io.updateAccepted := RegNext(btb.io.update.fire, false.B)
    io.allocated := RegNext(btb.io.allocated, false.B)
    io.evicted := RegNext(btb.io.evicted, false.B)
  }
}

object GenerateBreezeBtbTimingHarness extends App {
  ChiselStage.emitSystemVerilogFile(new BreezeBtbTimingHarness, args,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info",
      "-default-layer-specialization=enable"))
}
