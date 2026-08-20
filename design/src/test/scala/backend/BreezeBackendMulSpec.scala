package flow.backend

import chisel3._
import flow.config.BackendConfig
import flow.fpu.BreezeFpChiselSim
import flow.interface.FrontendPredType
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeBackendMulSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  private val Mask64 = (BigInt(1) << 64) - 1

  private def encodeAddi(rd: Int, rs1: Int, imm: Int): BigInt =
    (BigInt(imm & 0xfff) << 20) | (BigInt(rs1) << 15) |
      (BigInt(rd) << 7) | BigInt(0x13)

  private def encodeR(funct7: Int, funct3: Int, rd: Int, rs1: Int, rs2: Int): BigInt =
    (BigInt(funct7) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(0x33)

  private def driveIdle(dut: BreezeBackend): Unit = {
    dut.io.resetAddr.poke(0.U)
    dut.io.machineTimerInterrupt.poke(false.B)
    dut.io.externalInterrupts.poke(0.U)
    dut.io.fetchBuffer.valid.poke(false.B)
    dut.io.fetchBuffer.bits.pc.poke(0.U)
    dut.io.fetchBuffer.bits.inst.poke(0.U)
    dut.io.fetchBuffer.bits.instructionAccessFault.poke(false.B)
    dut.io.fetchBuffer.bits.pred.predType.poke(FrontendPredType.NONE)
    dut.io.fetchBuffer.bits.pred.predTaken.poke(false.B)
    dut.io.fetchBuffer.bits.pred.predPc.poke(0.U)
    dut.io.fetchBuffer.bits.pred.phtIdx.poke(0.U)
    dut.io.dmem.rsp.valid.poke(false.B)
    dut.io.dmem.rsp.data.poke(0.U)
    dut.io.dmem.rsp.isWriteAck.poke(false.B)
    dut.io.dmem.rsp.error.poke(false.B)
    dut.io.dcacheFlushDone.poke(false.B)
    dut.io.hpmEvents.controlRetired.poke(false.B)
    dut.io.hpmEvents.controlTaken.poke(false.B)
    dut.io.hpmEvents.predictionMiss.poke(false.B)
    dut.io.hpmEvents.icacheAccess.poke(false.B)
    dut.io.hpmEvents.icacheMiss.poke(false.B)
    dut.io.hpmEvents.dcacheAccess.poke(false.B)
    dut.io.hpmEvents.dcacheMiss.poke(false.B)
    dut.io.hpmEvents.dcacheUncached.poke(false.B)
    dut.io.hpmEvents.memStallCycle.poke(false.B)
    dut.io.hpmEvents.loadUseStall.poke(false.B)
  }

  private def issue(dut: BreezeBackend, pc: BigInt, inst: BigInt): Unit = {
    dut.io.fetchBuffer.ready.expect(true.B)
    dut.io.fetchBuffer.valid.poke(true.B)
    dut.io.fetchBuffer.bits.pc.poke(pc.U)
    dut.io.fetchBuffer.bits.inst.poke(inst.U)
    dut.io.fetchBuffer.bits.pred.predPc.poke((pc + 4).U)
    dut.clock.step(1)
    dut.io.fetchBuffer.valid.poke(false.B)
  }

  "multiply completion bypass feeds the immediately dependent EXE instruction" in {
    simulate(new BreezeBackend(BackendConfig(), enabledebug = true)) { dut =>
      driveIdle(dut)
      dut.reset.poke(true.B)
      dut.clock.step(1)
      dut.reset.poke(false.B)

      val addiX1 = encodeAddi(rd = 1, rs1 = 0, imm = -2)
      val addiX2 = encodeAddi(rd = 2, rs1 = 0, imm = 3)
      val mulX3 = encodeR(funct7 = 1, funct3 = 0, rd = 3, rs1 = 1, rs2 = 2)
      val addX4 = encodeR(funct7 = 0, funct3 = 0, rd = 4, rs1 = 3, rs2 = 3)

      issue(dut, 0x100, addiX1)
      issue(dut, 0x104, addiX2)
      issue(dut, 0x108, mulX3)
      issue(dut, 0x10c, addX4)

      var mulResult: Option[BigInt] = None
      var addResult: Option[BigInt] = None
      for (_ <- 0 until 16) {
        if (dut.io.debug.get.memWbValid.peek().litToBoolean) {
          val inst = dut.io.debug.get.memWbInst.peekValue().asBigInt
          val data = dut.io.debug.get.wbData.peekValue().asBigInt
          if (inst == mulX3) mulResult = Some(data)
          if (inst == addX4) addResult = Some(data)
        }
        dut.clock.step(1)
      }

      mulResult mustBe Some(BigInt(-6) & Mask64)
      addResult mustBe Some(BigInt(-12) & Mask64)
    }
  }
}
