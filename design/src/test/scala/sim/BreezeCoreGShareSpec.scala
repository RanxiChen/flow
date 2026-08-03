package flow.sim

import flow.config.BreezeCoreConfigs
import flow.core.CSRMAP
import flow.platform.BreezeMcuPlatform
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import scala.collection.mutable

class BreezeCoreGShareSpec extends AnyFreeSpec with Matchers {
    private val BootAddr = BreezeMcuPlatform.ResetVector
    private val NopInst = BigInt("00000013", 16)
    private val EcallInst = BigInt("00000073", 16)
    private val MretInst = BigInt("30200073", 16)
    private val IllegalInst = BigInt("ffffffff", 16)

    private def encodeOpImm(rd: Int, rs1: Int, imm: Int, funct3: Int): BigInt = {
        val imm12 = imm & 0xfff
        (BigInt(imm12) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(funct3) << 12) |
        (BigInt(rd) << 7) |
        BigInt(0x13)
    }

    private def encodeJal(rd: Int, imm: Int): BigInt = {
        val value = imm & 0x1fffff
        (BigInt((value >> 20) & 1) << 31) |
        (BigInt((value >> 1) & 0x3ff) << 21) |
        (BigInt((value >> 11) & 1) << 20) |
        (BigInt((value >> 12) & 0xff) << 12) |
        (BigInt(rd) << 7) |
        BigInt(0x6f)
    }

    private def encodeJalr(rd: Int, rs1: Int, imm: Int): BigInt = {
        (BigInt(imm & 0xfff) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(rd) << 7) |
        BigInt(0x67)
    }

    private def encodeLui(rd: Int, imm20: Int): BigInt = {
        (BigInt(imm20 & 0xfffff) << 12) |
        (BigInt(rd) << 7) |
        BigInt(0x37)
    }

    private def encodeCsr(rd: Int, rs1: Int, csr: Int, funct3: Int): BigInt = {
        (BigInt(csr & 0xfff) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(funct3) << 12) |
        (BigInt(rd) << 7) |
        BigInt(0x73)
    }

    private def runMemory(
        memory: mutable.Map[BigInt, BigInt],
        gshare: Boolean
    ): BreezeCoreSimTandemResult = {
        val cfg = if (gshare) {
            BreezeCoreConfigs.gshare(enableTandem = true)
        } else {
            BreezeCoreConfigs.baseline(enableTandem = true)
        }
        BreezeCoreSimRunner.runWithTandemTrace(
          memory = memory,
          coreCfg = cfg,
          maxCycles = 5000,
          imemLatency = 6,
          dmemLatency = 7,
          bootAddr = BootAddr
        )
    }

    private def run(program: Seq[BigInt], gshare: Boolean): BreezeCoreSimTandemResult = {
        val memory = BreezeCoreSimSupport.buildInstructionMemory(program, BootAddr)
        runMemory(memory, gshare)
    }

    private def architecturalTrace(result: BreezeCoreSimTandemResult) = {
        result.commitEvents.map { event =>
            (event.pc, event.inst, event.nextPc, event.rdWriteEn, event.rdAddr, event.rdData, event.estop)
        }
    }

    private def expectArchitecturalMatch(
        baseline: BreezeCoreSimTandemResult,
        gshare: BreezeCoreSimTandemResult
    ): Unit = {
        baseline.result.timedOut mustBe false
        gshare.result.timedOut mustBe false
        architecturalTrace(gshare) mustBe architecturalTrace(baseline)
        baseline.commitEvents.last.estop mustBe true
        gshare.commitEvents.last.estop mustBe true
    }

    "Baseline and GShare should retire the same architectural loop sequence" in {
        val decrement = BreezeCoreSimSupport.encodeAddi(rd = 1, rs1 = 1, imm = -1)
        val loopBranch = BreezeCoreSimSupport.encodeBranch(
          rs1 = 1,
          rs2 = 0,
          imm = -4,
          funct3 = 1
        )
        val program = Seq(
            BreezeCoreSimSupport.encodeAddi(rd = 1, rs1 = 0, imm = 20),
            decrement,
            loopBranch,
            BreezeCoreSimSupport.encodeAddi(rd = 2, rs1 = 0, imm = 0x55),
            BreezeCoreSimSupport.EstopInst
        )

        val baseline = run(program, gshare = false)
        val gshare = run(program, gshare = true)

        expectArchitecturalMatch(baseline, gshare)
        baseline.commitEvents.count(_.inst == decrement) mustBe 20
        baseline.commitEvents.count(_.inst == loopBranch) mustBe 20
    }

    "Baseline and GShare should match on alternating branches and direct jumps" in {
        val decrement = BreezeCoreSimSupport.encodeAddi(rd = 1, rs1 = 1, imm = -1)
        val loopBranch = BreezeCoreSimSupport.encodeBranch(
          rs1 = 1,
          rs2 = 0,
          imm = -24,
          funct3 = 1
        )
        val alternatingBranch = BreezeCoreSimSupport.encodeBranch(
          rs1 = 2,
          rs2 = 0,
          imm = 12,
          funct3 = 0
        )
        val program = Seq(
            BreezeCoreSimSupport.encodeAddi(rd = 1, rs1 = 0, imm = 20),
            BreezeCoreSimSupport.encodeAddi(rd = 2, rs1 = 0, imm = 0),
            encodeOpImm(rd = 2, rs1 = 2, imm = 1, funct3 = 4), // xori x2, x2, 1
            alternatingBranch,
            BreezeCoreSimSupport.encodeAddi(rd = 3, rs1 = 3, imm = 3),
            encodeJal(rd = 0, imm = 8),
            BreezeCoreSimSupport.encodeAddi(rd = 3, rs1 = 3, imm = 7),
            decrement,
            loopBranch,
            BreezeCoreSimSupport.EstopInst
        )

        val baseline = run(program, gshare = false)
        val gshare = run(program, gshare = true)

        expectArchitecturalMatch(baseline, gshare)
        val finalX3 = baseline.commitEvents.filter { event =>
            event.rdWriteEn && event.rdAddr == 3
        }.last.rdData
        finalX3 mustBe BigInt(100)
        baseline.commitEvents.count(_.inst == alternatingBranch) mustBe 20
        baseline.commitEvents.count(_.inst == loopBranch) mustBe 20
    }

    "Baseline and GShare should match on an adjacent load-to-branch dependency" in {
        val dependentBranch = BreezeCoreSimSupport.encodeBranch(
          rs1 = 2,
          rs2 = 0,
          imm = 12,
          funct3 = 1
        )
        val program = Seq(
            BreezeCoreSimSupport.encodeAddi(rd = 2, rs1 = 0, imm = 1),
            BreezeCoreSimSupport.encodeAddi(rd = 1, rs1 = 0, imm = 0x100),
            BreezeCoreSimSupport.encodeLoad(rd = 2, rs1 = 1, imm = 0, funct3 = 3),
            dependentBranch,
            BreezeCoreSimSupport.encodeAddi(rd = 3, rs1 = 0, imm = 0x55),
            BreezeCoreSimSupport.EstopInst,
            BreezeCoreSimSupport.encodeAddi(rd = 3, rs1 = 0, imm = 0x66),
            BreezeCoreSimSupport.EstopInst
        )

        val baseline = run(program, gshare = false)
        val gshare = run(program, gshare = true)

        expectArchitecturalMatch(baseline, gshare)
        val finalX3 = baseline.commitEvents.filter { event =>
            event.rdWriteEn && event.rdAddr == 3
        }.last.rdData
        finalX3 mustBe BigInt(0x55)
    }

    "Baseline and GShare should match when one JALR PC changes targets" in {
        val dispatcherOffset = 0x0c
        val targetAOffset = 0x40
        val targetBOffset = 0x60
        val targetCOffset = 0x80
        val program = Array.fill[BigInt](36)(NopInst)
        val jalrInst = encodeJalr(rd = 0, rs1 = 2, imm = 0)

        program(0) = encodeLui(rd = 1, imm20 = (BootAddr >> 12).toInt)
        program(1) = BreezeCoreSimSupport.encodeAddi(rd = 2, rs1 = 1, imm = targetAOffset)
        program(2) = BreezeCoreSimSupport.encodeAddi(rd = 3, rs1 = 0, imm = 0)
        program(dispatcherOffset / 4) = jalrInst

        program(targetAOffset / 4) = BreezeCoreSimSupport.encodeAddi(rd = 3, rs1 = 3, imm = 1)
        program(targetAOffset / 4 + 1) = BreezeCoreSimSupport.encodeAddi(rd = 2, rs1 = 1, imm = targetBOffset)
        program(targetAOffset / 4 + 2) = encodeJal(
          rd = 0,
          imm = dispatcherOffset - (targetAOffset + 8)
        )

        program(targetBOffset / 4) = BreezeCoreSimSupport.encodeAddi(rd = 3, rs1 = 3, imm = 1)
        program(targetBOffset / 4 + 1) = BreezeCoreSimSupport.encodeAddi(rd = 2, rs1 = 1, imm = targetCOffset)
        program(targetBOffset / 4 + 2) = encodeJal(
          rd = 0,
          imm = dispatcherOffset - (targetBOffset + 8)
        )

        program(targetCOffset / 4) = BreezeCoreSimSupport.encodeAddi(rd = 3, rs1 = 3, imm = 1)
        program(targetCOffset / 4 + 1) = BreezeCoreSimSupport.EstopInst

        val baseline = run(program.toSeq, gshare = false)
        val gshare = run(program.toSeq, gshare = true)

        expectArchitecturalMatch(baseline, gshare)
        baseline.commitEvents.count(_.inst == jalrInst) mustBe 3
        val finalX3 = baseline.commitEvents.filter { event =>
            event.rdWriteEn && event.rdAddr == 3
        }.last.rdData
        finalX3 mustBe BigInt(3)
    }

    "Baseline and GShare should match across branch, ecall, illegal trap, and mret" in {
        val handlerOffset = 0x100
        val handlerIndex = handlerOffset / 4
        val program = Array.fill[BigInt](handlerIndex + 8)(NopInst)
        val takenBranch = BreezeCoreSimSupport.encodeBranch(
          rs1 = 0,
          rs2 = 0,
          imm = 8,
          funct3 = 0
        )

        program(0) = encodeLui(rd = 1, imm20 = (BootAddr >> 12).toInt)
        program(1) = BreezeCoreSimSupport.encodeAddi(rd = 1, rs1 = 1, imm = handlerOffset)
        program(2) = encodeCsr(rd = 0, rs1 = 1, csr = CSRMAP.mtvec, funct3 = 1)
        program(3) = takenBranch
        program(4) = BreezeCoreSimSupport.encodeAddi(rd = 7, rs1 = 0, imm = 0x7ff)
        program(5) = EcallInst
        program(6) = IllegalInst
        program(7) = BreezeCoreSimSupport.encodeAddi(rd = 2, rs1 = 0, imm = 0x55)
        program(8) = BreezeCoreSimSupport.EstopInst

        program(handlerIndex) = encodeCsr(rd = 3, rs1 = 0, csr = CSRMAP.mepc, funct3 = 2)
        program(handlerIndex + 1) = BreezeCoreSimSupport.encodeAddi(rd = 3, rs1 = 3, imm = 4)
        program(handlerIndex + 2) = encodeCsr(rd = 0, rs1 = 3, csr = CSRMAP.mepc, funct3 = 1)
        program(handlerIndex + 3) = MretInst

        val baseline = run(program.toSeq, gshare = false)
        val gshare = run(program.toSeq, gshare = true)

        expectArchitecturalMatch(baseline, gshare)
        baseline.commitEvents.count(_.inst == takenBranch) mustBe 1
        baseline.commitEvents.count(_.inst == EcallInst) mustBe 1
        baseline.commitEvents.count(_.inst == IllegalInst) mustBe 1
        baseline.commitEvents.count(_.inst == MretInst) mustBe 2
        val finalX2 = baseline.commitEvents.filter { event =>
            event.rdWriteEn && event.rdAddr == 2
        }.last.rdData
        finalX2 mustBe BigInt(0x55)
    }
}
