package flow.sim

import flow.config.{BreezeCoreConfig, PrivilegeProfile}
import flow.core.CSRMAP
import flow.platform.BreezeMcuPlatform
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import scala.collection.mutable

class BreezeWfiFlowSpec extends AnyFreeSpec with Matchers {
  private def encodeLui(rd: Int, imm20: Int): BigInt =
    (BigInt(imm20 & 0xfffff) << 12) | (BigInt(rd) << 7) | BigInt(0x37)

  private def encodeCsr(rd: Int, csr: Int, rs1: Int, funct3: Int): BigInt =
    (BigInt(csr) << 20) | (BigInt(rs1) << 15) | (BigInt(funct3) << 12) |
      (BigInt(rd) << 7) | BigInt(0x73)

  private def install(
      memory: mutable.LinkedHashMap[BigInt, BigInt],
      base: BigInt,
      instructions: Seq[BigInt]
  ): Unit = {
    BreezeCoreSimSupport.buildInstructionMemory(instructions, base).foreach {
      case (address, data) => memory.update(address, data)
    }
  }

  "retire WFI once, sleep, and take a later machine software interrupt at PC+4" in {
    val memory = mutable.LinkedHashMap.empty[BigInt, BigInt]
    val boot = BreezeMcuPlatform.ResetVector
    val handler = boot + 0x100
    val wfiPc = boot + 6 * 4
    val wfiNextPc = wfiPc + 4
    val csrrw = 1
    val csrrs = 2

    install(memory, boot, Seq(
      encodeLui(1, 0x10010),
      BreezeCoreSimSupport.encodeAddi(1, 1, 0x100),
      encodeCsr(0, CSRMAP.mtvec, 1, csrrw),
      BreezeCoreSimSupport.encodeAddi(1, 0, 1 << 3),
      encodeCsr(0, CSRMAP.mie, 1, csrrw),
      encodeCsr(0, CSRMAP.mstatus, 1, csrrs),
      BigInt("10500073", 16),
      BreezeCoreSimSupport.encodeAddi(5, 0, 42),
      BreezeCoreSimSupport.EstopInst
    ))
    install(memory, handler, Seq(
      encodeCsr(7, CSRMAP.mepc, 0, csrrs),
      BreezeCoreSimSupport.encodeAddi(6, 0, 99),
      BreezeCoreSimSupport.EstopInst
    ))

    val result = BreezeCoreSimRunner.runWithTandemTrace(
      memory = memory,
      coreCfg = BreezeCoreConfig(
        useFASE = false,
        enableTandem = true,
        useGShare = true,
        privilegeProfile = PrivilegeProfile.Linux),
      maxCycles = 2000,
      imemLatency = 2,
      dmemLatency = 2,
      bootAddr = boot,
      machineSoftwareInterruptAt = Some(500))

    result.result.timedOut mustBe false
    result.commitEvents.count(_.inst == BigInt("10500073", 16)) mustBe 1
    result.commitEvents.find(_.pc == wfiPc).map(_.nextPc) mustBe Some(wfiNextPc)
    result.commitEvents.exists(event =>
      event.rdWriteEn && event.rdAddr == 5 && event.rdData == 42) mustBe false
    result.commitEvents.exists(event =>
      event.rdWriteEn && event.rdAddr == 6 && event.rdData == 99) mustBe true
    result.commitEvents.exists(event =>
      event.rdWriteEn && event.rdAddr == 7 && event.rdData == wfiNextPc) mustBe true
    result.commitEvents.last.estop mustBe true
  }
}
