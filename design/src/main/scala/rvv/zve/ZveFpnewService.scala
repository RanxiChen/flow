package flow.rvv.zve

import chisel3._
import chisel3.util._
import flow.fpu.{BreezeFpOp,BreezeFpSources}

class ZveFpnewBlackBox(kind: Int) extends BlackBox(Map("TAG_WIDTH" -> 16,"KIND" -> kind)) with HasBlackBoxPath {
  override def desiredName = "ZveFpnewWrapper"
  addPath(BreezeFpSources.aggregate.toString)
  addPath(BreezeFpSources.designRoot.resolve("src/main/resources/vsrc/fpnew/ZveFpnewWrapper.sv").toString)
  val io = IO(new Bundle {
    val clk_i=Input(Clock()); val reset_i=Input(Bool()); val flush_i=Input(Bool())
    val tag_i=Input(UInt(16.W)); val tag_o=Output(UInt(16.W))
    val in_valid_i=Input(Bool()); val in_ready_o=Output(Bool())
    val operand_a_i=Input(UInt(64.W)); val operand_b_i=Input(UInt(64.W)); val operand_c_i=Input(UInt(64.W))
    val rnd_mode_i=Input(UInt(3.W)); val op_i=Input(UInt(4.W)); val op_mod_i=Input(Bool())
    val src_fmt_i=Input(UInt(3.W)); val dst_fmt_i=Input(UInt(3.W)); val int_fmt_i=Input(UInt(2.W))
    val out_valid_o=Output(Bool()); val out_ready_i=Input(Bool())
    val result_o=Output(UInt(64.W)); val status_o=Output(UInt(5.W)); val busy_o=Output(Bool())
  })
}

/** Arithmetic service, independent of scalar rd/commit tables. Production
  * conversion/FMA instances disable unused operation groups. The slow service
  * is FP32-only (kind 3); kind 2 (RV64D) is kept only for reference. */
class ZveFpnewService(kind: Int = 3) extends Module {
  val io = IO(new Bundle {
    val service = Flipped(new ZveFpService)
    val drained = Output(Bool())
  })
  val fp = Module(new ZveFpnewBlackBox(kind))
  val input = Module(new Queue(new ZveFpRequest,2,pipe=false,flow=false))
  val output = Module(new Queue(new ZveFpResponse,2,pipe=false,flow=false))
  input.io.enq <> io.service.request
  io.service.response <> output.io.deq
  fp.io.clk_i := clock; fp.io.reset_i := reset.asBool; fp.io.flush_i := false.B
  val q=input.io.deq.bits
  if(kind==3) {
    when(input.io.deq.fire) {
      assert(q.sourceFormat===0.U && q.destinationFormat===0.U &&
        Seq(BreezeFpOp.ADD,BreezeFpOp.MUL,BreezeFpOp.DIV,BreezeFpOp.SQRT,
          BreezeFpOp.MINMAX,BreezeFpOp.CMP).map(op=>q.operation===op.U).reduce(_ || _),
        "R07c-1: slow FP request must be an FP32 arithmetic or compare operation")
    }
  }
  fp.io.in_valid_i := input.io.deq.valid; input.io.deq.ready := fp.io.in_ready_o
  fp.io.tag_i := q.tag; fp.io.operand_a_i := q.a; fp.io.operand_b_i := q.b; fp.io.operand_c_i := q.c
  fp.io.rnd_mode_i := q.rounding; fp.io.op_i := q.operation; fp.io.op_mod_i := q.modifier
  fp.io.src_fmt_i := q.sourceFormat; fp.io.dst_fmt_i := q.destinationFormat; fp.io.int_fmt_i := q.integerFormat
  output.io.enq.valid := fp.io.out_valid_o; fp.io.out_ready_i := output.io.enq.ready
  output.io.enq.bits.tag := fp.io.tag_o; output.io.enq.bits.data := fp.io.result_o; output.io.enq.bits.flags := fp.io.status_o
  io.drained := !input.io.deq.valid && !fp.io.busy_o && !output.io.deq.valid
}

/** Standalone verification shell: exactly the same batch protocol as production,
  * with real FPnew arithmetic in place of a future SoC-owned service. */
class ZveFpBatchHarness(lanes: Int, depth: Int = 8, kind: Int = 0) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ZveFpBatch(lanes,32)))
    val out = Decoupled(new ZveFpBatchResult(lanes,32))
    val drained = Output(Bool())
  })
  val batch=Module(new ZveFpBatchPipeline(lanes,depth,32))
  batch.io.in <> io.in; io.out <> batch.io.out
  val services=Seq.fill(lanes)(Module(new ZveFpnewService(kind)))
  services.zipWithIndex.foreach { case(s,l) => s.io.service <> batch.io.fp(l) }
  io.drained := batch.io.drained && services.map(_.io.drained).reduce(_ && _)
}
