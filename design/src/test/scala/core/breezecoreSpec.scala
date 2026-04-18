package flow.core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.BreezeCoreConfig
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeCoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
    private def encodeAddi(rd: Int, rs1: Int, imm: Int): BigInt = {
        val imm12 = imm & 0xfff
        BigInt((imm12 << 20) | (rs1 << 15) | (0 << 12) | (rd << 7) | 0x13)
    }

    "BreezeCore should retire a single addi instruction from FASE input" in {
        simulate(new BreezeCore(BreezeCoreConfig(useFASE = true), enabledebug = true)) { dut =>
            val debug = dut.io.debug.get
            val fase = dut.io.fase.get
            val addiX1 = encodeAddi(rd = 1, rs1 = 0, imm = 1)

            dut.io.resetAddr.poke(0.U)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0.U)
            dut.io.dmem.rsp.valid.poke(false.B)
            dut.io.dmem.rsp.data.poke(0.U)
            dut.io.dmem.rsp.isWriteAck.poke(false.B)
            fase.inst_valid.poke(false.B)
            fase.instruction.poke(0.U)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            while (!fase.inst_ready.peek().litToBoolean) {
                dut.clock.step(1)
            }

            fase.inst_valid.poke(true.B)
            fase.instruction.poke(addiX1.U)
            dut.clock.step(1)
            fase.inst_valid.poke(false.B)

            while (!debug.decodeValid.peek().litToBoolean) {
                dut.clock.step(1)
            }
            debug.decodeInst.expect(addiX1.U)
            debug.decodePc.expect(0.U)

            dut.clock.step(1)
            debug.idExeValid.expect(true.B)
            debug.idExeRs1Addr.expect(0.U)
            debug.idExeRs2Addr.expect(1.U)
            debug.idExeSrc1.expect(0.U)
            debug.idExeSrc2.expect(1.U)
            debug.exeSrc1.expect(0.U)
            debug.exeSrc2.expect(1.U)
            debug.exeAluOut.expect(1.U)

            dut.clock.step(1)
            debug.exeMemValid.expect(true.B)
            debug.exeMemRdAddr.expect(1.U)
            debug.exeMemData.expect(1.U)

            dut.clock.step(1)
            debug.memWbValid.expect(true.B)
            debug.wbData.expect(1.U)
        }
    }
}
