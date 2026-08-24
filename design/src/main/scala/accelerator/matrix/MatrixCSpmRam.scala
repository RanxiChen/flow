package flow.accelerator.matrix

import chisel3._
import chisel3.util.{HasBlackBoxInline, isPow2, log2Ceil}

/** One physical INT32 C bank shared in time by CPU and engine. */
class MatrixCSpmRam(depth: Int = 256, width: Int = 32) extends Module {
  require(depth > 0 && isPow2(depth))
  require(width == 32)
  private val addrWidth = log2Ceil(depth)

  val io = IO(new Bundle {
    val cpuAddr = Input(UInt(addrWidth.W))
    val cpuReadEnable = Input(Bool())
    val cpuWriteEnable = Input(Bool())
    val cpuWriteData = Input(UInt(width.W))
    val cpuReadData = Output(UInt(width.W))

    val engineAddr = Input(UInt(addrWidth.W))
    val engineReadEnable = Input(Bool())
    val engineWriteEnable = Input(Bool())
    val engineWriteData = Input(UInt(width.W))
    val engineReadData = Output(UInt(width.W))
  })

  private val engineActive = io.engineReadEnable || io.engineWriteEnable
  private val ram = Module(new MatrixCSpmRamBlackBox(depth, width, addrWidth))
  ram.io.clock := clock
  ram.io.addr := Mux(engineActive, io.engineAddr, io.cpuAddr)
  ram.io.re := Mux(engineActive, io.engineReadEnable, io.cpuReadEnable)
  ram.io.we := Mux(engineActive, io.engineWriteEnable, io.cpuWriteEnable)
  ram.io.wdata := Mux(engineActive, io.engineWriteData, io.cpuWriteData)
  io.cpuReadData := ram.io.rdata
  io.engineReadData := ram.io.rdata
}

private class MatrixCSpmRamBlackBox(depth: Int, width: Int, addrWidth: Int)
    extends BlackBox(Map(
      "DEPTH" -> depth,
      "DATA_WIDTH" -> width,
      "ADDR_WIDTH" -> addrWidth))
    with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val addr = Input(UInt(addrWidth.W))
    val re = Input(Bool())
    val we = Input(Bool())
    val wdata = Input(UInt(width.W))
    val rdata = Output(UInt(width.W))
  })

  setInline(
    "MatrixCSpmRamBlackBox.sv",
    """module MatrixCSpmRamBlackBox #(
      |  parameter integer DEPTH = 256,
      |  parameter integer DATA_WIDTH = 32,
      |  parameter integer ADDR_WIDTH = 8
      |) (
      |  input  wire                  clock,
      |  input  wire [ADDR_WIDTH-1:0] addr,
      |  input  wire                  re,
      |  input  wire                  we,
      |  input  wire [DATA_WIDTH-1:0] wdata,
      |  output wire [DATA_WIDTH-1:0] rdata
      |);
      |`ifdef MATRIX_QUARTUS
      |  altsyncram altsyncram_component (
      |    .address_a(addr),
      |    .clock0(clock),
      |    .data_a(wdata),
      |    .rden_a(re),
      |    .wren_a(we),
      |    .q_a(rdata),
      |    .aclr0(1'b0),
      |    .addressstall_a(1'b0),
      |    .byteena_a(1'b1),
      |    .clocken0(1'b1)
      |  );
      |  defparam
      |    altsyncram_component.clock_enable_input_a = "BYPASS",
      |    altsyncram_component.clock_enable_output_a = "BYPASS",
      |    altsyncram_component.intended_device_family = "Cyclone IV E",
      |    altsyncram_component.numwords_a = DEPTH,
      |    altsyncram_component.operation_mode = "SINGLE_PORT",
      |    altsyncram_component.outdata_aclr_a = "NONE",
      |    altsyncram_component.outdata_reg_a = "UNREGISTERED",
      |    altsyncram_component.power_up_uninitialized = "TRUE",
      |    altsyncram_component.widthad_a = ADDR_WIDTH,
      |    altsyncram_component.width_a = DATA_WIDTH,
      |    altsyncram_component.width_byteena_a = 1;
      |`else
      |  (* ramstyle = "M9K, no_rw_check" *) reg [DATA_WIDTH-1:0] mem [0:DEPTH-1];
      |  reg [DATA_WIDTH-1:0] rdata_reg;
      |  assign rdata = rdata_reg;
      |  always @(posedge clock) begin
      |    if (re)
      |      rdata_reg <= mem[addr];
      |    if (we)
      |      mem[addr] <= wdata;
      |  end
      |`endif
      |endmodule
      |""".stripMargin)
}
