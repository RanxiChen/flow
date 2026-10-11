package flow.frontend

import chisel3._
import chisel3.util._
import flow.interface._
import flow.mmu.sv39._
import _root_.circt.stage.ChiselStage

/** Explicit flop storage at the timing boundary. Separate payload registers
  * prevent a two-entry queue becoming LUTRAM with external data on its write
  * pins. Neither ready nor valid depends on the opposite-side handshake.
  */
class FrontendBoundaryQueue[T <: Data](gen: T) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen)); val deq = Decoupled(gen)
  })
  val first = Reg(gen); val second = Reg(gen)
  val head = RegInit(false.B); val tail = RegInit(false.B)
  val maybeFull = RegInit(false.B)
  val equal = head === tail
  io.enq.ready := !(equal && maybeFull)
  io.deq.valid := !(equal && !maybeFull)
  io.deq.bits := Mux(head, second, first)
  when(io.enq.fire) {
    when(tail) { second := io.enq.bits }.otherwise { first := io.enq.bits }
    tail := !tail
  }
  when(io.deq.fire) { head := !head }
  when(io.enq.fire =/= io.deq.fire) { maybeFull := io.enq.fire }
}

/** FPGA timing harness only; the core itself keeps an ordinary clock port. */
private class FrontendGlobalClock extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle { val in = Input(Clock()); val out = Output(Clock()) })
  setInline("FrontendGlobalClock.sv", """module FrontendGlobalClock(input in, output out);
    |`ifdef SYNTHESIS
    |  BUFGCE clock_buffer (.I(in), .CE(1'b1), .O(out));
    |`else
    |  assign out = in;
    |`endif
    |endmodule
    |""".stripMargin)
}

/** Physical timing boundary for the standalone frontend, without a backend.
  * Scalar controls/Valid payloads register once; Decoupled interfaces cross
  * real two-entry queues (pipe=false), so ready cannot run through the wrapper.
  */
class BreezeFrontendTimingHarness extends Module {
  val p = BreezeFrontendPipelineParams()
  val io = IO(new Bundle {
    val resetPc = Input(UInt(64.W)); val context = Input(new BreezeMmuContext(64))
    val block = Input(Bool()); val redirect = Flipped(Valid(UInt(64.W)))
    val fenceI = Input(Bool()); val sfence = Input(new SfenceIO)
    val resolve = Flipped(Decoupled(new PipelineResolution(p)))
    val committedBranch = Flipped(Valid(Bool()))
    val instruction = Decoupled(new PipelineInstruction(p))
    val refillReq = new L1CacheMissReqIO(64)
    val refillRsp = new L1CacheMissRespIO(256)
    val ptwMem = new PtwMemIO
    val idle = Output(Bool())
  })
  private val globalClock = Module(new FrontendGlobalClock)
  globalClock.io.in := clock
  withClock(globalClock.io.out) {
  val frontend = Module(new BreezeFrontendPipeline())
  frontend.io.resetPc := RegNext(io.resetPc)
  val context = RegNext(io.context)
  frontend.io.context := context
  frontend.io.block := RegNext(io.block, true.B) || !context.pmpRangesValid
  frontend.io.redirect := RegNext(io.redirect, 0.U.asTypeOf(io.redirect))
  frontend.io.fenceI := RegNext(io.fenceI, false.B)
  frontend.io.sfence := RegNext(io.sfence, 0.U.asTypeOf(io.sfence))
  frontend.io.committedBranch := RegNext(io.committedBranch, 0.U.asTypeOf(io.committedBranch))
  frontend.io.refillRsp.vld := RegNext(io.refillRsp.vld, false.B)
  frontend.io.refillRsp.data := RegNext(io.refillRsp.data)
  frontend.io.refillRsp.error := RegNext(io.refillRsp.error)
  io.refillReq.req := RegNext(frontend.io.refillReq.req, false.B)
  io.refillReq.paddr := RegNext(frontend.io.refillReq.paddr)
  val cancel = Wire(Bool())
  cancel := frontend.io.redirect.valid || frontend.io.fenceI ||
    (frontend.io.resolve.fire && frontend.io.resolve.bits.incorrect)
  val resolutions = withReset(reset.asBool || cancel) { Module(new FrontendBoundaryQueue(new PipelineResolution(p))) }
  resolutions.io.enq <> io.resolve; frontend.io.resolve <> resolutions.io.deq
  val instructions = withReset(reset.asBool || cancel) { Module(new FrontendBoundaryQueue(new PipelineInstruction(p))) }
  instructions.io.enq <> frontend.io.instruction
  io.instruction.valid := instructions.io.deq.valid && !cancel
  io.instruction.bits := instructions.io.deq.bits
  instructions.io.deq.ready := io.instruction.ready && !cancel
  val walks = Module(new FrontendBoundaryQueue(new PtwMemReq))
  walks.io.enq <> frontend.io.ptwMem.req; io.ptwMem.req <> walks.io.deq
  frontend.io.ptwMem.resp := RegNext(io.ptwMem.resp, 0.U.asTypeOf(io.ptwMem.resp))
  io.idle := RegNext(frontend.io.idle, false.B)
  }
}

object GenerateBreezeFrontendTimingHarness extends App {
  ChiselStage.emitSystemVerilogFile(new BreezeFrontendTimingHarness, args,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable"))
}
