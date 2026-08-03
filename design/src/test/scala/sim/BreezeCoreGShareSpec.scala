package flow.sim

import flow.config.BreezeCoreConfigs
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeCoreGShareSpec extends AnyFreeSpec with Matchers {
    private val BootAddr = BigInt("10000000", 16)

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

    private def run(program: Seq[BigInt], gshare: Boolean): BreezeCoreSimTandemResult = {
        val memory = BreezeCoreSimSupport.buildInstructionMemory(program, BootAddr)
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
}
