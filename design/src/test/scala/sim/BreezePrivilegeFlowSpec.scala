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

  private def installParcels(
      memory: mutable.LinkedHashMap[BigInt, BigInt],
      base: BigInt,
      parcels: Seq[(BigInt, Int)]
  ): Unit = {
    var address = base
    parcels.foreach { case (value, bytes) =>
      require(bytes == 2 || bytes == 4)
      for (byte <- 0 until bytes) {
        val byteAddress = address + byte
        val alignedAddress = BreezeCoreSimSupport.alignDown(byteAddress, 8)
        val shift = ((byteAddress - alignedAddress) * 8).toInt
        val previous = memory.getOrElse(alignedAddress, BigInt(0))
        val next = (previous & ~(BigInt(0xff) << shift)) |
          (((value >> (byte * 8)) & 0xff) << shift)
        memory.update(alignedAddress, next & BreezeCoreSimSupport.Mask64)
      }
      address += bytes
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

  "preserve a loaded high user PC through the Linux return-to-user sequence" in {
    val memory = mutable.LinkedHashMap.empty[BigInt, BigInt]
    val boot = BreezeMcuPlatform.ResetVector
    val supervisorEntry = boot + 0x100
    val machineHandler = boot + 0x300
    val targetSlot = boot + 0x1000
    val target = BigInt("0000003f92bff000", 16)
    val truncated = BigInt("ffffffff92bff000", 16)
    val csrrw = 1
    val csrrs = 2
    val mret = BigInt("30200073", 16)
    val sret = BigInt("10200073", 16)
    val scdZeroA2Sp = BigInt("18c1302f", 16)

    install(memory, boot, Seq(
      encodeLui(1, (machineHandler >> 12).toInt),
      BreezeCoreSimSupport.encodeAddi(1, 1, (machineHandler & 0xfff).toInt),
      encodeCsr(0, CSRMAP.mtvec, 1, csrrw),
      encodeLui(1, (supervisorEntry >> 12).toInt),
      BreezeCoreSimSupport.encodeAddi(1, 1, (supervisorEntry & 0xfff).toInt),
      encodeCsr(0, CSRMAP.mepc, 1, csrrw),
      encodeLui(1, 1),
      BreezeCoreSimSupport.encodeAddi(1, 1, -2048),
      encodeCsr(0, CSRMAP.mstatus, 1, csrrw),
      mret
    ))
    install(memory, supervisorEntry, Seq(
      encodeLui(2, (targetSlot >> 12).toInt),
      BreezeCoreSimSupport.encodeLoad(12, 2, 0, funct3 = 3),
      scdZeroA2Sp,
      BreezeCoreSimSupport.encodeAddi(10, 0, 0),
      encodeCsr(0, CSRMAP.sstatus, 10, csrrw),
      encodeCsr(0, CSRMAP.sepc, 12, csrrw),
      BreezeCoreSimSupport.encodeAddi(0, 0, 0),
      BreezeCoreSimSupport.encodeAddi(0, 0, 0),
      sret
    ))
    memory.update(targetSlot, target)
    install(memory, machineHandler, Seq(
      encodeCsr(20, CSRMAP.mepc, 0, csrrs),
      encodeCsr(21, CSRMAP.mcause, 0, csrrs),
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

    result.result.timedOut mustBe false
    result.commitEvents.last.estop mustBe true
    result.commitEvents.exists(_.pc == target) mustBe true
    result.commitEvents.exists(_.pc == truncated) mustBe false
    result.commitEvents.exists(event =>
      event.rdWriteEn && event.rdAddr == 20 && event.rdData == target) mustBe true
    result.commitEvents.exists(event =>
      event.rdWriteEn && event.rdAddr == 21 && event.rdData == 1) mustBe true
  }

  "execute the crashing musl RELA loop at its high Sv39 user address" in {
    val memory = mutable.LinkedHashMap.empty[BigInt, BigInt]
    val boot = BreezeMcuPlatform.ResetVector
    val supervisorEntry = boot + 0x100
    val constants = boot + 0x1000
    val rootPageTable = BigInt("80100000", 16)
    val level1PageTable = BigInt("80101000", 16)
    val level0PageTable = BigInt("80102000", 16)
    val codePhysicalPage = BigInt("80200000", 16)
    val relaPhysicalPage = BigInt("80201000", 16)
    val destinationPhysicalPage = BigInt("80202000", 16)

    val loadBase = BigInt("0000003f928a1000", 16)
    val code = loadBase + BigInt("5e97c", 16)
    val rela = loadBase + BigInt("133d8", 16)
    val destination = loadBase + BigInt("9db28", 16)
    val addend = BigInt("6d770", 16)
    val relaEnd = rela + 24
    val truncatedCode = BigInt("ffffffff928ff97c", 16)
    val satp = (BigInt(8) << 60) | (rootPageTable >> 12)

    val csrrw = 1
    val mret = BigInt("30200073", 16)
    val sret = BigInt("10200073", 16)
    val sfenceVma = BigInt("12000073", 16)

    // M-mode establishes an all-address PMP entry, then enters the identity-
    // mapped supervisor setup page.
    install(memory, boot, Seq(
      encodeLui(2, (constants >> 12).toInt),
      BreezeCoreSimSupport.encodeLoad(1, 2, 0, funct3 = 3),
      encodeCsr(0, CSRMAP.pmpaddr0, 1, csrrw),
      BreezeCoreSimSupport.encodeAddi(1, 0, 0x0f),
      encodeCsr(0, CSRMAP.pmpcfg0, 1, csrrw),
      encodeLui(1, (supervisorEntry >> 12).toInt),
      BreezeCoreSimSupport.encodeAddi(1, 1, (supervisorEntry & 0xfff).toInt),
      encodeCsr(0, CSRMAP.mepc, 1, csrrw),
      encodeLui(1, 1),
      BreezeCoreSimSupport.encodeAddi(1, 1, -2048),
      encodeCsr(0, CSRMAP.mstatus, 1, csrrw),
      mret
    ))

    // S-mode loads the exact register state printed by the Linux Oops before
    // enabling Sv39 and returning to the dynamic loader in U-mode.
    install(memory, supervisorEntry, Seq(
      BreezeCoreSimSupport.encodeLoad(13, 2, 8, funct3 = 3),
      BreezeCoreSimSupport.encodeLoad(14, 2, 16, funct3 = 3),
      BreezeCoreSimSupport.encodeLoad(15, 2, 24, funct3 = 3),
      BreezeCoreSimSupport.encodeLoad(11, 2, 32, funct3 = 3),
      BreezeCoreSimSupport.encodeAddi(16, 0, 3),
      BreezeCoreSimSupport.encodeLoad(6, 2, 40, funct3 = 3),
      BreezeCoreSimSupport.encodeLoad(12, 2, 48, funct3 = 3),
      BreezeCoreSimSupport.encodeLoad(5, 2, 56, funct3 = 3),
      encodeCsr(0, CSRMAP.satp, 5, csrrw),
      sfenceVma,
      encodeCsr(0, CSRMAP.sepc, 12, csrrw),
      encodeCsr(0, CSRMAP.sstatus, 0, csrrw),
      sret
    ))

    memory.update(constants, (BigInt(1) << 54) - 1)
    memory.update(constants + 8, loadBase)
    memory.update(constants + 16, BigInt("a08", 16))
    memory.update(constants + 24, rela)
    memory.update(constants + 32, BigInt("7fffffff", 16))
    memory.update(constants + 40, relaEnd)
    memory.update(constants + 48, code)
    memory.update(constants + 56, satp)

    def pointerPte(nextTable: BigInt): BigInt = ((nextTable >> 12) << 10) | 1
    def leafPte(physicalPage: BigInt, flags: BigInt): BigInt =
      ((physicalPage >> 12) << 10) | flags

    // Keep the supervisor setup page identity mapped. The three U-mode pages
    // cover the real musl code, relocation record, and relocation destination.
    memory.update(rootPageTable, BigInt("cf", 16))
    memory.update(rootPageTable + BigInt("fe", 16) * 8, pointerPte(level1PageTable))
    memory.update(level1PageTable + BigInt("94", 16) * 8, pointerPte(level0PageTable))
    memory.update(level0PageTable + BigInt("ff", 16) * 8,
      leafPte(codePhysicalPage, BigInt("5b", 16)))
    memory.update(level0PageTable + BigInt("b4", 16) * 8,
      leafPte(relaPhysicalPage, BigInt("d7", 16)))
    memory.update(level0PageTable + BigInt("13e", 16) * 8,
      leafPte(destinationPhysicalPage, BigInt("d7", 16)))

    val codePhysical = codePhysicalPage + (code & 0xfff)
    installParcels(memory, codePhysical, Seq(
      BigInt("cf09", 16) -> 2,       // beqz a4, +26
      BigInt("6798", 16) -> 2,       // ld a4, 8(a5)
      BigInt("8f6d", 16) -> 2,       // and a4, a4, a1
      BigInt("01071763", 16) -> 4,   // bne a4, a6, +14
      BigInt("6398", 16) -> 2,       // ld a4, 0(a5)
      BigInt("6b90", 16) -> 2,       // ld a2, 16(a5)
      BigInt("9736", 16) -> 2,       // add a4, a4, a3
      BigInt("9636", 16) -> 2,       // add a2, a2, a3
      BigInt("e310", 16) -> 2,       // sd a2, 0(a4)
      BigInt("07e1", 16) -> 2,       // addi a5, a5, 24
      BigInt("fe6796e3", 16) -> 4,   // bne a5, t1, -20
      BreezeCoreSimSupport.EstopInst -> 4
    ))

    val relaPhysical = relaPhysicalPage + (rela & 0xfff)
    memory.update(relaPhysical, BigInt("9db28", 16))
    memory.update(relaPhysical + 8, 3)
    memory.update(relaPhysical + 16, addend)

    val result = BreezeCoreSimRunner.runWithTandemTrace(
      memory = memory,
      coreCfg = BreezeCoreConfig(
        useFASE = false,
        enableTandem = true,
        useGShare = true,
        privilegeProfile = PrivilegeProfile.Linux,
        enableCompressed = true,
        enableMmu = true),
      maxCycles = 20000,
      imemLatency = 6,
      dmemLatency = 7,
      bootAddr = boot)

    val expectedPcs = Seq(0, 2, 4, 6, 10, 12, 14, 16, 18, 20, 22, 26)
      .map(code + _)
    val retiredPcs = result.commitEvents.map(_.pc)
    result.result.timedOut mustBe false
    result.commitEvents.last.estop mustBe true
    expectedPcs.foreach(pc => retiredPcs must contain(pc))
    retiredPcs must not contain truncatedCode
    BreezeCoreSimSupport.read64(
      memory, destinationPhysicalPage + (destination & 0xfff)) mustBe loadBase + addend
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

  "trap an unsupported OpenSBI mtopi probe in the matching pipeline slot" in {
    val memory = mutable.LinkedHashMap.empty[BigInt, BigInt]
    val boot = BreezeMcuPlatform.ResetVector
    val handler = boot + 0x200
    val csrrw = 1
    val csrrs = 2
    val mret = BigInt("30200073", 16)
    val mtopi = 0xfb0
    val probe = encodeCsr(rd = 9, csr = mtopi, rs1 = 0, funct3 = csrrs)

    install(memory, boot, Seq(
      encodeLui(1, (handler >> 12).toInt),
      BreezeCoreSimSupport.encodeAddi(1, 1, (handler & 0xfff).toInt),
      encodeCsr(0, CSRMAP.mtvec, 1, csrrw),
      BreezeCoreSimSupport.encodeAddi(9, 0, 55),
      probe,
      BreezeCoreSimSupport.encodeAddi(10, 9, 0),
      BreezeCoreSimSupport.EstopInst
    ))
    install(memory, handler, Seq(
      encodeCsr(20, CSRMAP.mepc, 0, csrrs),
      encodeCsr(21, CSRMAP.mcause, 0, csrrs),
      encodeCsr(22, CSRMAP.mtval, 0, csrrs),
      BreezeCoreSimSupport.encodeAddi(20, 20, 4),
      encodeCsr(0, CSRMAP.mepc, 20, csrrw),
      mret
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

    val faultPc = boot + 4 * 4
    result.result.timedOut mustBe false
    result.commitEvents.last.estop mustBe true
    result.commitEvents.exists(event =>
      event.rdWriteEn && event.rdAddr == 20 && event.rdData == faultPc) mustBe true
    result.commitEvents.exists(event =>
      event.rdWriteEn && event.rdAddr == 21 && event.rdData == 2) mustBe true
    result.commitEvents.exists(event =>
      event.rdWriteEn && event.rdAddr == 22 && event.rdData == 0) mustBe true
    result.commitEvents.exists(event =>
      event.rdWriteEn && event.rdAddr == 10 && event.rdData == 55) mustBe true
  }
}
