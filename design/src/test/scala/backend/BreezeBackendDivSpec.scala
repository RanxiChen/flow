package flow.backend

import chisel3._
import flow.config.BackendConfig
import flow.fpu.BreezeFpChiselSim
import flow.interface.FrontendPredType
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeBackendDivSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  private val Mask64 = (BigInt(1) << 64) - 1

  private def encodeAddi(rd: Int, rs1: Int, imm: Int): BigInt =
    (BigInt(imm & 0xfff) << 20) | (BigInt(rs1) << 15) |
      (BigInt(rd) << 7) | BigInt(0x13)

  private def encodeSlli(rd: Int, rs1: Int, shamt: Int): BigInt =
    (BigInt(shamt & 0x3f) << 20) | (BigInt(rs1) << 15) |
      (BigInt(1) << 12) | (BigInt(rd) << 7) | BigInt(0x13)

  private def encodeR(opcode: Int, funct7: Int, funct3: Int, rd: Int, rs1: Int, rs2: Int): BigInt =
    (BigInt(funct7) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(opcode)

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
    var waitCycles = 0
    while (!dut.io.fetchBuffer.ready.peek().litToBoolean && waitCycles < 80) {
      dut.clock.step(1)
      waitCycles += 1
    }
    dut.io.fetchBuffer.ready.expect(true.B)
    dut.io.fetchBuffer.valid.poke(true.B)
    dut.io.fetchBuffer.bits.pc.poke(pc.U)
    dut.io.fetchBuffer.bits.inst.poke(inst.U)
    dut.io.fetchBuffer.bits.pred.predPc.poke((pc + 4).U)
    dut.clock.step(1)
    dut.io.fetchBuffer.valid.poke(false.B)
  }

  private def waitForWb(dut: BreezeBackend, inst: BigInt): BigInt = {
    var cycles = 0
    while (cycles < 80) {
      if (dut.io.debug.get.memWbValid.peek().litToBoolean &&
          dut.io.debug.get.memWbInst.peekValue().asBigInt == inst) {
        return dut.io.debug.get.wbData.peekValue().asBigInt
      }
      dut.clock.step(1)
      cycles += 1
    }
    fail(s"instruction 0x${inst.toString(16)} did not reach WB")
  }

  "RV64 divider executes all eight operations, fast paths, and completion bypass" in {
    simulate(new BreezeBackend(BackendConfig(), enabledebug = true)) { dut =>
      driveIdle(dut)
      dut.reset.poke(true.B)
      dut.clock.step(1)
      dut.reset.poke(false.B)

      var pc = BigInt(0x200)
      def issueNext(inst: BigInt): Unit = {
        issue(dut, pc, inst)
        pc += 4
      }
      def issueAndCheck(inst: BigInt, expected: BigInt): Unit = {
        issueNext(inst)
        waitForWb(dut, inst) mustBe (expected & Mask64)
      }

      issueAndCheck(encodeAddi(1, 0, -20), -20)
      issueAndCheck(encodeAddi(2, 0, 3), 3)

      val div = encodeR(0x33, 1, 4, 3, 1, 2)
      val dependentAdd = encodeR(0x33, 0, 0, 4, 3, 0)
      issueNext(div)
      issueNext(dependentAdd)
      waitForWb(dut, div) mustBe (BigInt(-6) & Mask64)
      waitForWb(dut, dependentAdd) mustBe (BigInt(-6) & Mask64)

      issueAndCheck(encodeR(0x33, 1, 6, 5, 1, 2), -2) // REM
      issueAndCheck(encodeAddi(6, 0, 20), 20)
      issueAndCheck(encodeAddi(7, 0, 3), 3)
      issueAndCheck(encodeR(0x33, 1, 5, 8, 6, 7), 6) // DIVU
      issueAndCheck(encodeR(0x33, 1, 7, 9, 6, 7), 2) // REMU

      issueAndCheck(encodeR(0x3b, 1, 4, 10, 1, 2), -6) // DIVW
      issueAndCheck(encodeR(0x3b, 1, 6, 11, 1, 2), -2) // REMW
      issueAndCheck(encodeR(0x3b, 1, 5, 12, 6, 7), 6) // DIVUW
      issueAndCheck(encodeR(0x3b, 1, 7, 13, 6, 7), 2) // REMUW

      issueAndCheck(encodeR(0x33, 1, 4, 14, 1, 0), -1) // DIV by zero
      issueAndCheck(encodeR(0x33, 1, 6, 15, 1, 0), -20) // REM by zero

      issueAndCheck(encodeAddi(16, 0, 1), 1)
      issueAndCheck(encodeSlli(16, 16, 63), BigInt(1) << 63)
      issueAndCheck(encodeAddi(17, 0, -1), -1)
      issueAndCheck(encodeR(0x33, 1, 4, 18, 16, 17), BigInt(1) << 63)
      issueAndCheck(encodeR(0x33, 1, 6, 19, 16, 17), 0)
    }
  }
}
