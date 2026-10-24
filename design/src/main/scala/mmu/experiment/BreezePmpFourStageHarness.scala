package flow.mmu.experiment

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

private class PmpExperimentClock extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val in = Input(Clock()); val out = Output(Clock()) })
  setInline("PmpExperimentClock.sv", """module PmpExperimentClock(input in, output out);
    |`ifdef SYNTHESIS
    |  BUFGCE clock_buffer (.I(in), .CE(1'b1), .O(out));
    |`else
    |  assign out = in;
    |`endif
    |endmodule
    |""".stripMargin)
}

/** Registered-input OOC fixture. Includes the CSR predecode timing path.
  * External request valid is a stimulus; accepted reports the internal fire.
  * No translation, data cache, permission cache, PLL or board interface.
  */
class BreezePmpFourStageHarness extends Module {
  val io = IO(new Bundle {
    val commit = Flipped(Valid(new PmpCsrCommit))
    val request = Flipped(Valid(new PmpPipelineRequest))
    val cancel = Input(Bool())
    val accepted = Output(Bool())
    val response = Valid(new PmpPipelineResponse(8))
  })
  val globalClock = Module(new PmpExperimentClock)
  globalClock.io.in := clock
  withClock(globalClock.io.out) {
    val experiment = Module(new BreezePmpFourStageExperiment)
    experiment.io.commit.valid := RegNext(io.commit.valid, false.B)
    experiment.io.commit.bits := RegNext(io.commit.bits)
    experiment.io.request.valid := RegNext(io.request.valid, false.B)
    experiment.io.request.bits := RegNext(io.request.bits)
    experiment.io.cancel := RegNext(io.cancel, false.B)
    io.accepted := RegNext(experiment.io.request.fire, false.B)
    io.response := experiment.io.response
  }
}

object GenerateBreezePmpFourStageHarness extends App {
  ChiselStage.emitSystemVerilogFile(new BreezePmpFourStageHarness, args,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info",
      "-default-layer-specialization=enable"))
}
