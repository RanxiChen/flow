package flow.mem

import chisel3._
import chisel3.util.HasBlackBoxInline
import chisel3.util._

/**
  * 一个单端口读，单端口写的sram,我觉得可以被FPGA综合称bram
  *
  */
class SRAM(num_entry: Int, dataWidth: Int) extends Module {
    require(num_entry > 0, "num_entry must be positive")
    require(dataWidth > 0, "dataWidth must be positive")

    private val addrWidth = math.max(1, log2Up(num_entry))

    val io = IO(new Bundle {
        val addr     = Input(UInt(addrWidth.W))
        val data_in  = Input(UInt(dataWidth.W))
        val data_out = Output(UInt(dataWidth.W))
        val we       = Input(Bool())
        val re       = Input(Bool())
    })

    private val mem = Module(new SRAMBlackBox(num_entry, dataWidth, addrWidth))

    mem.io.clock   := clock
    mem.io.addr    := io.addr
    mem.io.data_in := io.data_in
    mem.io.we      := io.we
    mem.io.re      := io.re

    io.data_out := mem.io.data_out
}

private class SRAMBlackBox(numEntry: Int, dataWidth: Int, addrWidth: Int)
    extends BlackBox(
      Map(
        "DEPTH"      -> numEntry,
        "DATA_WIDTH" -> dataWidth,
        "ADDR_WIDTH" -> addrWidth
      )
    )
    with HasBlackBoxInline {

    val io = IO(new Bundle {
        val clock    = Input(Clock())
        val addr     = Input(UInt(addrWidth.W))
        val data_in  = Input(UInt(dataWidth.W))
        val data_out = Output(UInt(dataWidth.W))
        val we       = Input(Bool())
        val re       = Input(Bool())
    })

    setInline(
      "SRAMBlackBox.sv",
      """module SRAMBlackBox #(
        |  parameter int DEPTH = 1024,
        |  parameter int DATA_WIDTH = 64,
        |  parameter int ADDR_WIDTH = 10
        |) (
        |  input  logic                  clock,
        |  input  logic [ADDR_WIDTH-1:0] addr,
        |  input  logic [DATA_WIDTH-1:0] data_in,
        |  output logic [DATA_WIDTH-1:0] data_out,
        |  input  logic                  we,
        |  input  logic                  re
        |);
        |  (* ram_style = "block" *) logic [DATA_WIDTH-1:0] mem [0:DEPTH-1];
        |  logic [DATA_WIDTH-1:0] data_out_reg;
        |
        |  assign data_out = data_out_reg;
        |
        |  always_ff @(posedge clock) begin
        |    if (we) begin
        |      mem[addr] <= data_in;
        |    end
        |    if (re) begin
        |      data_out_reg <= mem[addr];
        |    end
        |  end
        |endmodule
        |""".stripMargin
    )
}
