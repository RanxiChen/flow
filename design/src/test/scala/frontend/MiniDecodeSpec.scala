package flow.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.interface.FrontendPredType
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class MiniDecodeSpec extends AnyFreeSpec with Matchers with ChiselSim {
    private def encodeBranch(imm: Int): BigInt = {
        val value = imm & 0x1fff
        (BigInt((value >> 12) & 1) << 31) |
        (BigInt((value >> 5) & 0x3f) << 25) |
        (BigInt(2) << 20) |
        (BigInt(1) << 15) |
        (BigInt(1) << 12) |
        (BigInt((value >> 1) & 0xf) << 8) |
        (BigInt((value >> 11) & 1) << 7) |
        BigInt(0x63)
    }

    private def encodeJal(imm: Int): BigInt = {
        val value = imm & 0x1fffff
        (BigInt((value >> 20) & 1) << 31) |
        (BigInt((value >> 1) & 0x3ff) << 21) |
        (BigInt((value >> 11) & 1) << 20) |
        (BigInt((value >> 12) & 0xff) << 12) |
        (BigInt(1) << 7) |
        BigInt(0x6f)
    }

    "MiniDecode should identify direct control-flow instructions and targets" in {
        simulate(new MiniDecode(vlen = 64)) { dut =>
            dut.io.pc.poke(0x100.U)
            dut.io.inst.poke(encodeJal(0x40).U)
            dut.io.predType.expect(FrontendPredType.JAL)
            dut.io.predTaken.expect(true.B)
            dut.io.predPc.expect(0x140.U)

            dut.io.inst.poke(encodeBranch(-0x20).U)
            dut.io.predType.expect(FrontendPredType.BR)
            dut.io.predTaken.expect(false.B)
            dut.io.predPc.expect(0xe0.U)

            // jalr x1, 0(x2): MiniDecode identifies the type but cannot know rs1.
            dut.io.inst.poke(BigInt("000100e7", 16).U)
            dut.io.predType.expect(FrontendPredType.JALR)
            dut.io.predTaken.expect(false.B)
            dut.io.predPc.expect(0.U)

            dut.io.inst.poke(BigInt("00100093", 16).U) // addi x1, x0, 1
            dut.io.predType.expect(FrontendPredType.NONE)
            dut.io.predTaken.expect(false.B)
            dut.io.predPc.expect(0.U)
        }
    }
}
