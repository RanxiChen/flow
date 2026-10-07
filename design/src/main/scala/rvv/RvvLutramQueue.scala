package flow.rvv

import chisel3._
import chisel3.util._

/** Explicit 1W1R asynchronous distributed-memory VIQ. Payload has no reset. */
class RvvLutramQueue(p: RvvParams) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new RvvDescriptor(p)))
    val deq = Decoupled(new RvvDescriptor(p))
    val count = Output(UInt(log2Ceil(p.viqDepth+1).W))
  })
  private val addressBits = math.max(1,log2Ceil(p.viqDepth))
  val head = RegInit(0.U(addressBits.W)); val tail = RegInit(0.U(addressBits.W))
  val count = RegInit(0.U(log2Ceil(p.viqDepth+1).W))
  io.count := count; io.enq.ready := count < p.viqDepth.U; io.deq.valid := count =/= 0.U
  val ram = Module(new RvvDistributedRam(p.viqDepth,(new RvvDescriptor(p)).getWidth))
  ram.io.clock := clock; ram.io.writeValid := io.enq.fire
  ram.io.writeAddress := tail; ram.io.writeData := io.enq.bits.asUInt
  ram.io.readAddress := head; io.deq.bits := ram.io.readData.asTypeOf(new RvvDescriptor(p))
  when(io.enq.fire) { tail := Mux(tail === (p.viqDepth-1).U,0.U,tail+1.U) }
  when(io.deq.fire) { head := Mux(head === (p.viqDepth-1).U,0.U,head+1.U) }
  when(io.enq.fire =/= io.deq.fire) { count := count+io.enq.fire.asUInt-io.deq.fire.asUInt }
}
class RvvDistributedRam(rows: Int,dataBits: Int) extends BlackBox(Map(
  "ROWS" -> rows,"DATA_BITS" -> dataBits,"ADDR_BITS" -> math.max(1,log2Ceil(rows)))) with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock()); val writeValid = Input(Bool())
    val writeAddress = Input(UInt(math.max(1,log2Ceil(rows)).W)); val writeData = Input(UInt(dataBits.W))
    val readAddress = Input(UInt(math.max(1,log2Ceil(rows)).W)); val readData = Output(UInt(dataBits.W))
  })
  setInline("RvvDistributedRam.sv","""module RvvDistributedRam #(
    |parameter int ROWS=8, DATA_BITS=1, ADDR_BITS=3
    |)(input logic clock, input logic writeValid,
    |input logic [ADDR_BITS-1:0] writeAddress, input logic [DATA_BITS-1:0] writeData,
    |input logic [ADDR_BITS-1:0] readAddress, output logic [DATA_BITS-1:0] readData);
    |(* ram_style="distributed" *) logic [DATA_BITS-1:0] memory [0:ROWS-1];
    |always @(posedge clock) if(writeValid) memory[writeAddress] <= writeData;
    |assign readData=memory[readAddress];
    |endmodule
    |""".stripMargin)
}
