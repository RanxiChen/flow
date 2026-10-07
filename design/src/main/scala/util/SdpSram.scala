package flow.util

import chisel3._
import chisel3.util._

/** One synchronous read and one write. Same-address read/write is forbidden.
  * maskGranule == width selects an unmasked word; otherwise only bytes are supported.
  * No reset is applied to the memory or its output register.
  */
class SdpSram(val depth: Int, val width: Int, val maskGranule: Int) extends Module {
  require(depth > 0 && width > 0)
  require(maskGranule == width || (maskGranule == 8 && width % 8 == 0))
  private val addrBits = math.max(1, log2Ceil(depth))
  val io = IO(new Bundle {
    val ren = Input(Bool())
    val raddr = Input(UInt(addrBits.W))
    val rdata = Output(UInt(width.W))
    val wen = Input(Bool())
    val waddr = Input(UInt(addrBits.W))
    val wdata = Input(UInt(width.W))
    val wmask = Input(UInt((width / maskGranule).W))
  })
  private val ram = Module(new SdpSramBlackBox(depth, width, maskGranule, addrBits))
  ram.io.clk := clock
  ram.io.ren := io.ren
  ram.io.raddr := io.raddr
  ram.io.wen := io.wen
  ram.io.waddr := io.waddr
  ram.io.wdata := io.wdata
  ram.io.we := io.wmask
  io.rdata := ram.io.rdata
  assert(!(io.ren && io.wen && io.raddr === io.waddr),
    "SdpSram same-address read/write collision")
}

private class SdpSramBlackBox(depth: Int, width: Int, granule: Int, addrBits: Int)
    extends BlackBox(Map("DEPTH" -> depth, "WIDTH" -> width,
      "GRANULE" -> granule, "ADDR_BITS" -> addrBits)) with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val ren = Input(Bool())
    val raddr = Input(UInt(addrBits.W))
    val rdata = Output(UInt(width.W))
    val wen = Input(Bool())
    val waddr = Input(UInt(addrBits.W))
    val wdata = Input(UInt(width.W))
    val we = Input(UInt((width / granule).W))
  })
  setInline("SdpSramBlackBox.sv", """module SdpSramBlackBox #(
    |  parameter DEPTH = 1024, WIDTH = 64, GRANULE = 8, ADDR_BITS = 10
    |) (
    |  input clk, ren, wen,
    |  input [ADDR_BITS-1:0] raddr, waddr,
    |  input [WIDTH-1:0] wdata,
    |  input [WIDTH/GRANULE-1:0] we,
    |  output reg [WIDTH-1:0] rdata
    |);
    |  (* ram_style = "block" *) reg [WIDTH-1:0] mem [0:DEPTH-1];
    |  integer i;
    |  always @(posedge clk) begin
    |    if (wen) begin
    |      if (GRANULE == WIDTH)
    |        mem[waddr] <= wdata;
    |      else
    |        for (i = 0; i < WIDTH/GRANULE; i = i + 1)
    |          if (we[i]) mem[waddr][i*GRANULE +: GRANULE] <= wdata[i*GRANULE +: GRANULE];
    |    end
    |    if (ren) rdata <= mem[raddr];
    |  end
    |endmodule
    |""".stripMargin)
}
