package flow.backend

import chisel3._
import chisel3.simulator.PeekPokeAPI
import flow.interface.FrontendPredType
import org.scalatest.Assertions.fail

private[backend] object BreezeBackendFpTestUtils extends PeekPokeAPI {
  val Mask64: BigInt = (BigInt(1) << 64) - 1

  def encodeAddi(rd: Int, rs1: Int, imm: Int): BigInt =
    (BigInt(imm & 0xfff) << 20) | (BigInt(rs1) << 15) |
      (BigInt(rd) << 7) | BigInt(0x13)

  def encodeLui(rd: Int, imm20: Int): BigInt =
    (BigInt(imm20 & 0xfffff) << 12) | (BigInt(rd) << 7) | BigInt(0x37)

  def encodeSlli(rd: Int, rs1: Int, shamt: Int): BigInt =
    (BigInt(shamt & 0x3f) << 20) | (BigInt(rs1) << 15) |
      (BigInt(1) << 12) | (BigInt(rd) << 7) | BigInt(0x13)

  def encodeCsr(funct3: Int, rd: Int, csr: Int, rs1: Int): BigInt =
    (BigInt(csr) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(0x73)

  def encodeOpFp(funct5: Int, fmt: Int, rs2: Int, rm: Int,
      rd: Int, rs1: Int): BigInt =
    (BigInt(funct5) << 27) | (BigInt(fmt) << 25) | (BigInt(rs2) << 20) |
      (BigInt(rs1) << 15) | (BigInt(rm) << 12) |
      (BigInt(rd) << 7) | BigInt(0x53)

  def encodeFma(opcode: Int, fmt: Int, rs3: Int, rs2: Int, rs1: Int,
      rm: Int, rd: Int): BigInt =
    (BigInt(rs3) << 27) | (BigInt(fmt) << 25) | (BigInt(rs2) << 20) |
      (BigInt(rs1) << 15) | (BigInt(rm) << 12) |
      (BigInt(rd) << 7) | BigInt(opcode)

  def encodeFpLoad(rd: Int, rs1: Int, imm: Int, isDouble: Boolean): BigInt =
    (BigInt(imm & 0xfff) << 20) | (BigInt(rs1) << 15) |
      (BigInt(if (isDouble) 3 else 2) << 12) | (BigInt(rd) << 7) | BigInt(0x07)

  def encodeFpStore(rs2: Int, rs1: Int, imm: Int, isDouble: Boolean): BigInt =
    (BigInt((imm >> 5) & 0x7f) << 25) | (BigInt(rs2) << 20) |
      (BigInt(rs1) << 15) | (BigInt(if (isDouble) 3 else 2) << 12) |
      (BigInt(imm & 0x1f) << 7) | BigInt(0x27)

  def driveIdle(dut: BreezeBackend): Unit = {
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

  def issue(dut: BreezeBackend, pc: BigInt, inst: BigInt): Unit = {
    var cycles = 0
    while (!dut.io.fetchBuffer.ready.peek().litToBoolean && cycles < 2000) {
      dut.clock.step(1)
      cycles += 1
    }
    if (cycles == 2000) fail(s"backend did not accept instruction 0x${inst.toString(16)}")
    dut.io.fetchBuffer.valid.poke(true.B)
    dut.io.fetchBuffer.bits.pc.poke(pc.U)
    dut.io.fetchBuffer.bits.inst.poke(inst.U)
    dut.io.fetchBuffer.bits.pred.predPc.poke((pc + 4).U)
    dut.clock.step(1)
    dut.io.fetchBuffer.valid.poke(false.B)
  }

  def waitForWb(dut: BreezeBackend, inst: BigInt, limit: Int = 2000): BigInt = {
    var cycles = 0
    while (cycles < limit) {
      if (dut.io.debug.get.memWbValid.peek().litToBoolean &&
          dut.io.debug.get.memWbInst.peekValue().asBigInt == inst) {
        val result = dut.io.debug.get.wbData.peekValue().asBigInt
        dut.clock.step(1) // commit the observed MEM/WB entry
        return result
      }
      dut.clock.step(1)
      cycles += 1
    }
    fail(s"instruction 0x${inst.toString(16)} did not reach WB")
  }

  def enableFp(dut: BreezeBackend, nextPc: () => BigInt): Unit = {
    val one = encodeAddi(30, 0, 1)
    val fsInitial = encodeSlli(30, 30, 13)
    val setMstatus = encodeCsr(2, 0, 0x300, 30) // CSRRS x0,mstatus,x30
    Seq(one, fsInitial, setMstatus).foreach { inst =>
      issue(dut, nextPc(), inst)
      waitForWb(dut, inst)
    }
  }
}
