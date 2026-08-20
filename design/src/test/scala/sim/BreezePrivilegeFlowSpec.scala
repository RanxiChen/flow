package flow.sim

import flow.config.{BreezeCoreConfig, PrivilegeProfile}
import flow.core.CSRMAP
import flow.platform.BreezeMcuPlatform
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import scala.collection.mutable

class BreezePrivilegeFlowSpec extends AnyFreeSpec with Matchers {
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

  "run M boot, S setup, U ECALL, delegated S handler, and SRET in Bare mode" in {
    val memory = mutable.LinkedHashMap.empty[BigInt, BigInt]
    val csrrw = 1
    val csrrs = 2
    val mret = BigInt("30200073", 16)
    val sret = BigInt("10200073", 16)
    val ecall = BigInt("00000073", 16)
    val boot = BreezeMcuPlatform.ResetVector

    install(memory, boot, Seq(
      encodeLui(1, 0x10000),
      BreezeCoreSimSupport.encodeAddi(1, 1, 0x300),
      encodeCsr(0, CSRMAP.stvec, 1, csrrw),
      BreezeCoreSimSupport.encodeAddi(1, 0, 1 << 8),
      encodeCsr(0, CSRMAP.medeleg, 1, csrrw),
      encodeLui(1, 0x10000),
      BreezeCoreSimSupport.encodeAddi(1, 1, 0x100),
      encodeCsr(0, CSRMAP.mepc, 1, csrrw),
      encodeLui(1, 1),
      BreezeCoreSimSupport.encodeAddi(1, 1, -2048),
      encodeCsr(0, CSRMAP.mstatus, 1, csrrw),
      mret
    ))
    install(memory, boot + 0x100, Seq(
      encodeLui(1, 0x10000),
      BreezeCoreSimSupport.encodeAddi(1, 1, 0x200),
      encodeCsr(0, CSRMAP.sepc, 1, csrrw),
      sret
    ))
    install(memory, boot + 0x200, Seq(
      BreezeCoreSimSupport.encodeAddi(5, 0, 42),
      ecall,
      BreezeCoreSimSupport.encodeAddi(7, 0, 77),
      BreezeCoreSimSupport.EstopInst
    ))
    install(memory, boot + 0x300, Seq(
      encodeCsr(1, CSRMAP.sepc, 0, csrrs),
      BreezeCoreSimSupport.encodeAddi(1, 1, 4),
      encodeCsr(0, CSRMAP.sepc, 1, csrrw),
      BreezeCoreSimSupport.encodeAddi(6, 0, 99),
      sret
    ))

    val result = BreezeCoreSimRunner.runWithTandemTrace(
      memory = memory,
      coreCfg = BreezeCoreConfig(
        useFASE = false,
        enableTandem = true,
        useGShare = true,
        privilegeProfile = PrivilegeProfile.Linux),
      maxCycles = 5000,
      imemLatency = 2,
      dmemLatency = 2,
      bootAddr = boot)

    result.result.timedOut mustBe false
    result.commitEvents.last.estop mustBe true
    result.commitEvents.exists(event =>
      event.rdWriteEn && event.rdAddr == 5 && event.rdData == 42) mustBe true
    result.commitEvents.exists(event =>
      event.rdWriteEn && event.rdAddr == 6 && event.rdData == 99) mustBe true
    result.commitEvents.exists(event =>
      event.rdWriteEn && event.rdAddr == 7 && event.rdData == 77) mustBe true
  }
}
