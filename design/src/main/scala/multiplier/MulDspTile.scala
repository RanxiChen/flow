package flow.multiplier

import chisel3._
import chisel3.util.HasBlackBoxInline

/** Explicit 24x17 unsigned DSP tile: input, multiplier, then output registers.
  * The simulation model has the same three CE-controlled edges as DSP48E2.
  */
class MulDspTile extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val ce = Input(Bool())
    val a = Input(UInt(24.W))
    val b = Input(UInt(17.W))
    val p = Output(UInt(41.W))
  })
  setInline("MulDspTile.sv", """module MulDspTile(
    |  input clk, ce, input [23:0] a, input [16:0] b, output [40:0] p
    |);
    |`ifdef SYNTHESIS
    |  wire [47:0] result;
    |  DSP48E2 #(
    |    .AREG(1), .BREG(1), .ACASCREG(1), .BCASCREG(1), .MREG(1), .PREG(1),
    |    .ADREG(0), .DREG(0), .CREG(0), .INMODEREG(0), .OPMODEREG(0),
    |    .ALUMODEREG(0), .CARRYINREG(0), .CARRYINSELREG(0),
    |    .A_INPUT("DIRECT"), .B_INPUT("DIRECT"), .USE_MULT("MULTIPLY"),
    |    .USE_SIMD("ONE48"), .AMULTSEL("A"), .BMULTSEL("B"), .PREADDINSEL("A")
    |  ) dsp (
    |    .CLK(clk), .A({6'b0,a}), .B({1'b0,b}), .C(48'b0), .D(27'b0),
    |    .ACIN(30'b0), .BCIN(18'b0), .PCIN(48'b0),
    |    .ALUMODE(4'b0), .INMODE(5'b0), .OPMODE(9'b000000101),
    |    .CARRYIN(1'b0), .CARRYINSEL(3'b0), .CARRYCASCIN(1'b0), .MULTSIGNIN(1'b0),
    |    .CEA1(ce), .CEA2(ce), .CEB1(ce), .CEB2(ce), .CEM(ce), .CEP(ce),
    |    .CEAD(1'b0), .CEC(1'b0), .CED(1'b0), .CEINMODE(1'b0),
    |    .CEALUMODE(1'b0), .CECTRL(1'b0), .CECARRYIN(1'b0),
    |    .RSTA(1'b0), .RSTB(1'b0), .RSTM(1'b0), .RSTP(1'b0),
    |    .RSTC(1'b0), .RSTD(1'b0), .RSTINMODE(1'b0), .RSTALUMODE(1'b0),
    |    .RSTCTRL(1'b0), .RSTALLCARRYIN(1'b0), .P(result)
    |  );
    |  assign p = result[40:0];
    |`else
    |  reg [23:0] ar;
    |  reg [16:0] br;
    |  reg [40:0] mr, pr;
    |  always @(posedge clk) if (ce) begin
    |    ar <= a; br <= b; mr <= ar * br; pr <= mr;
    |  end
    |  assign p = pr;
    |  // S11 also checks the hidden operand/MREG registers, not just PREG.
    |  reg stopped = 0;
    |  reg [122:0] held;
    |  always @(posedge clk) begin
    |    if (stopped) assert ({ar,br,mr,pr} == held)
    |      else $fatal(1, "[MDU S11] stopped DSP tile advanced");
    |    stopped <= !ce; held <= {ar,br,mr,pr};
    |  end
    |`endif
    |endmodule
    |""".stripMargin)
}
