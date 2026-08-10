package flow.core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class MulDecodeSpec extends AnyFreeSpec with ChiselSim {
  private def encodeR(opcode: Int, funct3: Int, rd: Int = 3, rs1: Int = 1, rs2: Int = 2): BigInt =
    (BigInt(1) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(opcode)

  "decoder recognizes the RV64 multiplication subset" in {
    simulate(new Decoder) { dut =>
      val cases = Seq(
        (encodeR(0x33, 0), MUL_OP.MUL),
        (encodeR(0x33, 1), MUL_OP.MULH),
        (encodeR(0x33, 2), MUL_OP.MULHSU),
        (encodeR(0x33, 3), MUL_OP.MULHU),
        (encodeR(0x3b, 0), MUL_OP.MULW)
      )

      for ((inst, op) <- cases) {
        dut.io.inst.poke(inst.U)
        dut.io.illegal_inst.expect(false.B)
        dut.io.exe_ctrl.wb_en.expect(true.B)
        dut.io.exe_ctrl.sel_wb.expect(SEL_WB.MUL.U)
        dut.io.exe_ctrl.mul_valid.expect(true.B)
        dut.io.exe_ctrl.div_valid.expect(false.B)
        dut.io.exe_ctrl.mul_op.expect(op.U)
        dut.io.exe_ctrl.mem_cmd.expect(MEM_TYPE.NOT_MEM.U)
      }

      val divCases = Seq(
        (encodeR(0x33, 4), DIV_OP.DIV),
        (encodeR(0x33, 5), DIV_OP.DIVU),
        (encodeR(0x33, 6), DIV_OP.REM),
        (encodeR(0x33, 7), DIV_OP.REMU),
        (encodeR(0x3b, 4), DIV_OP.DIVW),
        (encodeR(0x3b, 5), DIV_OP.DIVUW),
        (encodeR(0x3b, 6), DIV_OP.REMW),
        (encodeR(0x3b, 7), DIV_OP.REMUW)
      )
      for ((inst, op) <- divCases) {
        dut.io.inst.poke(inst.U)
        dut.io.illegal_inst.expect(false.B)
        dut.io.exe_ctrl.wb_en.expect(true.B)
        dut.io.exe_ctrl.sel_wb.expect(SEL_WB.MUL.U)
        dut.io.exe_ctrl.mul_valid.expect(false.B)
        dut.io.exe_ctrl.div_valid.expect(true.B)
        dut.io.exe_ctrl.div_op.expect(op.U)
        dut.io.exe_ctrl.mem_cmd.expect(MEM_TYPE.NOT_MEM.U)
      }
    }
  }
}
