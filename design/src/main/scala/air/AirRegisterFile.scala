package flow.air

import chisel3._
import chisel3.util.{Cat, HasBlackBoxInline}

/** Air's only integer register storage: one physical 256x8 M9K.
  *
  * There is one synchronous read port and one independent write port.  Air
  * schedules rs1 and rs2 on consecutive clocks, so a second read copy is not
  * required.  Reads of x0 are suppressed and writes to x0 are ignored.
  */
class AirRegisterFile extends Module {
  val io = IO(new Bundle {
    val readEnable = Input(Bool())
    val readReg = Input(UInt(5.W))
    val readByte = Input(UInt(3.W))
    val readData = Output(UInt(8.W))
    val writeEnable = Input(Bool())
    val writeReg = Input(UInt(5.W))
    val writeByte = Input(UInt(3.W))
    val writeData = Input(UInt(8.W))
  })

  private val ram = Module(new AirRegisterFileRam)
  ram.io.clock := clock
  ram.io.readEnable := io.readEnable && io.readReg =/= 0.U
  ram.io.readAddress := Cat(io.readReg, io.readByte)
  ram.io.writeEnable := io.writeEnable && io.writeReg =/= 0.U
  ram.io.writeAddress := Cat(io.writeReg, io.writeByte)
  ram.io.writeData := io.writeData

  private val readWasX0 = RegNext(io.readEnable && io.readReg === 0.U, false.B)
  io.readData := Mux(readWasX0, 0.U, ram.io.readData)
}

private class AirRegisterFileRam extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val readEnable = Input(Bool())
    val readAddress = Input(UInt(8.W))
    val readData = Output(UInt(8.W))
    val writeEnable = Input(Bool())
    val writeAddress = Input(UInt(8.W))
    val writeData = Input(UInt(8.W))
  })

  setInline("AirRegisterFileRam.sv",
    """module AirRegisterFileRam (
      |  input  wire       clock,
      |  input  wire       readEnable,
      |  input  wire [7:0] readAddress,
      |  output wire [7:0] readData,
      |  input  wire       writeEnable,
      |  input  wire [7:0] writeAddress,
      |  input  wire [7:0] writeData
      |);
      |  (* ramstyle = "M9K, no_rw_check" *) reg [7:0] mem [0:255];
      |  reg [7:0] readDataReg;
      |  assign readData = readDataReg;
      |  always @(posedge clock) begin
      |    if (readEnable)
      |      readDataReg <= mem[readAddress];
      |    if (writeEnable)
      |      mem[writeAddress] <= writeData;
      |  end
      |endmodule
      |""".stripMargin)
}
