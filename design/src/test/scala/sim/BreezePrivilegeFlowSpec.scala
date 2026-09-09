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

  "preserve an OpenSBI-style trap frame across CSR-read and store pairs" in {
    val memory = mutable.LinkedHashMap.empty[BigInt, BigInt]
    val boot = BreezeMcuPlatform.ResetVector
    val handler = boot + 0x200
    val trapFrame = boot + 0x2000
    val csrrw = 1
    val csrrs = 2
    val illegal = BigInt("ffffffff", 16)

    install(memory, boot, Seq(
      encodeLui(2, (trapFrame >> 12).toInt),
      encodeLui(1, (handler >> 12).toInt),
      BreezeCoreSimSupport.encodeAddi(1, 1, (handler & 0xfff).toInt),
      encodeCsr(0, CSRMAP.mtvec, 1, csrrw),
      BreezeCoreSimSupport.encodeAddi(5, 0, 55),
      illegal,
      BreezeCoreSimSupport.EstopInst
    ))

    // This mirrors OpenSBI's trap entry pattern: read one trap CSR into t0,
    // immediately store it, then reuse t0 for the next CSR. Distinct loads at
    // the end make CSR/read-after-write and DCache/store failures observable
    // independently in the tandem commit stream.
    install(memory, handler, Seq(
      encodeCsr(5, CSRMAP.mepc, 0, csrrs),
      BreezeCoreSimSupport.encodeStore(2, 5, 0, funct3 = 3),
      encodeCsr(5, CSRMAP.mstatus, 0, csrrs),
      BreezeCoreSimSupport.encodeStore(2, 5, 8, funct3 = 3),
      encodeCsr(5, CSRMAP.mcause, 0, csrrs),
      BreezeCoreSimSupport.encodeStore(2, 5, 16, funct3 = 3),
      encodeCsr(5, CSRMAP.mtval, 0, csrrs),
      BreezeCoreSimSupport.encodeStore(2, 5, 24, funct3 = 3),
      BreezeCoreSimSupport.encodeLoad(20, 2, 0, funct3 = 3),
      BreezeCoreSimSupport.encodeLoad(21, 2, 8, funct3 = 3),
      BreezeCoreSimSupport.encodeLoad(22, 2, 16, funct3 = 3),
      BreezeCoreSimSupport.encodeLoad(23, 2, 24, funct3 = 3),
      BreezeCoreSimSupport.EstopInst
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

    val faultPc = boot + 5 * 4
    result.result.timedOut mustBe false
    result.commitEvents.last.estop mustBe true
    result.commitEvents.exists(event =>
      event.rdWriteEn && event.rdAddr == 20 && event.rdData == faultPc) mustBe true
    result.commitEvents.exists(event =>
      event.rdWriteEn && event.rdAddr == 21 && event.rdData == BigInt("a00001800", 16)) mustBe true
    result.commitEvents.exists(event =>
      event.rdWriteEn && event.rdAddr == 22 && event.rdData == 2) mustBe true
    result.commitEvents.exists(event =>
      event.rdWriteEn && event.rdAddr == 23 && event.rdData == illegal) mustBe true
  }
}
