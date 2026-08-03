package flow.backend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.{BackendConfig, FrontendBranchPredictorKind}
import flow.interface.FrontendPredType
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeBackendGShareSpec extends AnyFreeSpec with Matchers with ChiselSim {
    private val GhrLength = 4
    private val cfg = BackendConfig(
      branchPredKind = FrontendBranchPredictorKind.GShare,
      ghrLength = GhrLength
    )

    private def encodeBranch(rs1: Int, rs2: Int, imm: Int, funct3: Int): BigInt = {
        val value = imm & 0x1fff
        (BigInt((value >> 12) & 1) << 31) |
        (BigInt((value >> 5) & 0x3f) << 25) |
        (BigInt(rs2) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(funct3) << 12) |
        (BigInt((value >> 1) & 0xf) << 8) |
        (BigInt((value >> 11) & 1) << 7) |
        BigInt(0x63)
    }

    private def encodeAddi(rd: Int, rs1: Int, imm: Int): BigInt = {
        (BigInt(imm & 0xfff) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(rd) << 7) |
        BigInt(0x13)
    }

    private def encodeJalr(rd: Int, rs1: Int, imm: Int): BigInt = {
        (BigInt(imm & 0xfff) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(rd) << 7) |
        BigInt(0x67)
    }

    private def driveIdleInputs(dut: BreezeBackend): Unit = {
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

    private def reset(dut: BreezeBackend): Unit = {
        driveIdleInputs(dut)
        dut.reset.poke(true.B)
        dut.clock.step(1)
        dut.reset.poke(false.B)
    }

    private def issueBranch(
        dut: BreezeBackend,
        pc: BigInt,
        inst: BigInt,
        predTaken: Boolean,
        predPc: BigInt,
        phtIdx: Int
    ): Unit = {
        dut.io.fetchBuffer.valid.poke(true.B)
        dut.io.fetchBuffer.bits.pc.poke(pc.U)
        dut.io.fetchBuffer.bits.inst.poke(inst.U)
        dut.io.fetchBuffer.bits.instructionAccessFault.poke(false.B)
        dut.io.fetchBuffer.bits.pred.predType.poke(FrontendPredType.BR)
        dut.io.fetchBuffer.bits.pred.predTaken.poke(predTaken.B)
        dut.io.fetchBuffer.bits.pred.predPc.poke(predPc.U)
        dut.io.fetchBuffer.bits.pred.phtIdx.poke(phtIdx.U)
        dut.io.fetchBuffer.ready.expect(true.B)
        dut.clock.step(1)
        dut.io.fetchBuffer.valid.poke(false.B)
    }

    private def issueInstruction(dut: BreezeBackend, pc: BigInt, inst: BigInt): Unit = {
        dut.io.fetchBuffer.valid.poke(true.B)
        dut.io.fetchBuffer.bits.pc.poke(pc.U)
        dut.io.fetchBuffer.bits.inst.poke(inst.U)
        dut.io.fetchBuffer.bits.instructionAccessFault.poke(false.B)
        dut.io.fetchBuffer.bits.pred.predType.poke(FrontendPredType.NONE)
        dut.io.fetchBuffer.bits.pred.predTaken.poke(false.B)
        dut.io.fetchBuffer.bits.pred.predPc.poke((pc + 4).U)
        dut.io.fetchBuffer.bits.pred.phtIdx.poke(0.U)
        dut.io.fetchBuffer.ready.expect(true.B)
        dut.clock.step(1)
        dut.io.fetchBuffer.valid.poke(false.B)
    }

    private def issuePredictedControl(
        dut: BreezeBackend,
        pc: BigInt,
        inst: BigInt,
        predType: FrontendPredType.Type,
        predPc: BigInt
    ): Unit = {
        dut.io.fetchBuffer.valid.poke(true.B)
        dut.io.fetchBuffer.bits.pc.poke(pc.U)
        dut.io.fetchBuffer.bits.inst.poke(inst.U)
        dut.io.fetchBuffer.bits.instructionAccessFault.poke(false.B)
        dut.io.fetchBuffer.bits.pred.predType.poke(predType)
        dut.io.fetchBuffer.bits.pred.predTaken.poke(true.B)
        dut.io.fetchBuffer.bits.pred.predPc.poke(predPc.U)
        dut.io.fetchBuffer.bits.pred.phtIdx.poke(0.U)
        dut.io.fetchBuffer.ready.expect(true.B)
        dut.clock.step(1)
        dut.io.fetchBuffer.valid.poke(false.B)
    }

    "GShare backend should redirect a predicted-taken branch to fall-through when actually not-taken" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { dut =>
            val pc = BigInt(0x100)
            val predictedTarget = BigInt(0x80)
            val phtIdx = 7
            val bneX0X0 = encodeBranch(rs1 = 0, rs2 = 0, imm = -0x20, funct3 = 1)

            reset(dut)
            issueBranch(
              dut,
              pc,
              bneX0X0,
              predTaken = true,
              predPc = predictedTarget,
              phtIdx = phtIdx
            )

            dut.io.debug.get.idExeValid.expect(true.B)
            dut.io.debug.get.exeBruTaken.expect(false.B)
            dut.io.frontendRedirect.valid.expect(true.B)
            dut.io.frontendRedirect.target.expect((pc + 4).U)

            dut.io.frontendBtbUpdate.valid.expect(true.B)
            dut.io.frontendBtbUpdate.pc.expect(pc.U)
            dut.io.frontendBtbUpdate.target.expect((pc - 0x20).U)
            dut.io.frontendBtbUpdate.predType.expect(FrontendPredType.BR)
            dut.io.frontendBtbUpdate.taken.expect(false.B)
            dut.io.frontendPhtUpdate.valid.expect(true.B)
            dut.io.frontendPhtUpdate.idx.expect(phtIdx.U)
            dut.io.frontendPhtUpdate.taken.expect(false.B)
            dut.io.frontendGhrUpdate.valid.expect(true.B)
            dut.io.frontendGhrUpdate.taken.expect(false.B)

            dut.clock.step(1)
            dut.io.frontendBtbUpdate.valid.expect(false.B)
            dut.io.frontendPhtUpdate.valid.expect(false.B)
            dut.io.frontendGhrUpdate.valid.expect(false.B)
        }
    }

    "GShare backend should redirect a predicted-not-taken branch to its taken target" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { dut =>
            val pc = BigInt(0x100)
            val target = BigInt(0xe0)
            val phtIdx = 3
            val beqX0X0 = encodeBranch(rs1 = 0, rs2 = 0, imm = -0x20, funct3 = 0)

            reset(dut)
            issueBranch(
              dut,
              pc,
              beqX0X0,
              predTaken = false,
              predPc = pc + 4,
              phtIdx = phtIdx
            )

            dut.io.debug.get.exeBruTaken.expect(true.B)
            dut.io.frontendRedirect.valid.expect(true.B)
            dut.io.frontendRedirect.target.expect(target.U)
            dut.io.frontendBtbUpdate.valid.expect(true.B)
            dut.io.frontendBtbUpdate.target.expect(target.U)
            dut.io.frontendBtbUpdate.taken.expect(true.B)
            dut.io.frontendPhtUpdate.valid.expect(true.B)
            dut.io.frontendPhtUpdate.taken.expect(true.B)
            dut.io.frontendGhrUpdate.valid.expect(true.B)
            dut.io.frontendGhrUpdate.taken.expect(true.B)
        }
    }

    "GShare backend should train a correctly predicted branch without redirecting" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { dut =>
            val pc = BigInt(0x100)
            val phtIdx = 5
            val bneX0X0 = encodeBranch(rs1 = 0, rs2 = 0, imm = -0x20, funct3 = 1)

            reset(dut)
            issueBranch(
              dut,
              pc,
              bneX0X0,
              predTaken = false,
              predPc = pc + 4,
              phtIdx = phtIdx
            )

            dut.io.frontendRedirect.valid.expect(false.B)
            dut.io.frontendBtbUpdate.valid.expect(true.B)
            dut.io.frontendPhtUpdate.valid.expect(true.B)
            dut.io.frontendPhtUpdate.idx.expect(phtIdx.U)
            dut.io.frontendPhtUpdate.taken.expect(false.B)
            dut.io.frontendGhrUpdate.valid.expect(true.B)
            dut.io.frontendGhrUpdate.taken.expect(false.B)
        }
    }

    "GShare backend should not retrain a branch while an older load holds the pipeline" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { dut =>
            val loadPc = BigInt(0x100)
            val branchPc = BigInt(0x104)
            val loadX1FromZero = BigInt("00003083", 16) // ld x1, 0(x0)
            val bneX0X0 = encodeBranch(rs1 = 0, rs2 = 0, imm = -4, funct3 = 1)
            val phtIdx = 9

            reset(dut)
            issueInstruction(dut, loadPc, loadX1FromZero)
            issueBranch(
              dut,
              branchPc,
              bneX0X0,
              predTaken = false,
              predPc = branchPc + 4,
              phtIdx = phtIdx
            )

            dut.io.debug.get.idExeValid.expect(true.B)
            dut.io.debug.get.idExePc.expect(branchPc.U)
            dut.io.dmem.req.valid.expect(true.B)
            dut.io.frontendBtbUpdate.valid.expect(false.B)
            dut.io.frontendPhtUpdate.valid.expect(false.B)
            dut.io.frontendGhrUpdate.valid.expect(false.B)

            // The request cycle and every response-wait cycle must suppress
            // branch resolution and predictor training.
            dut.clock.step(1)
            for (_ <- 0 until 3) {
                dut.io.debug.get.idExePc.expect(branchPc.U)
                dut.io.frontendBtbUpdate.valid.expect(false.B)
                dut.io.frontendPhtUpdate.valid.expect(false.B)
                dut.io.frontendGhrUpdate.valid.expect(false.B)
                dut.clock.step(1)
            }

            // The held branch can resolve exactly when the older load responds.
            dut.io.dmem.rsp.valid.poke(true.B)
            dut.io.dmem.rsp.data.poke(0.U)
            dut.io.dmem.rsp.isWriteAck.poke(false.B)
            dut.io.dmem.rsp.error.poke(false.B)
            dut.io.frontendBtbUpdate.valid.expect(true.B)
            dut.io.frontendPhtUpdate.valid.expect(true.B)
            dut.io.frontendPhtUpdate.idx.expect(phtIdx.U)
            dut.io.frontendGhrUpdate.valid.expect(true.B)
            dut.clock.step(1)

            dut.io.dmem.rsp.valid.poke(false.B)
            dut.io.frontendBtbUpdate.valid.expect(false.B)
            dut.io.frontendPhtUpdate.valid.expect(false.B)
            dut.io.frontendGhrUpdate.valid.expect(false.B)
        }
    }

    "GShare backend should repair a stale JALR target exactly once" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { dut =>
            val producerPc = BigInt(0xfc)
            val jalrPc = BigInt(0x100)
            val staleTarget = BigInt(0x40)
            val actualTarget = BigInt(0x80)

            reset(dut)
            issueInstruction(dut, producerPc, encodeAddi(rd = 1, rs1 = 0, imm = actualTarget.toInt))
            issuePredictedControl(
              dut,
              jalrPc,
              encodeJalr(rd = 0, rs1 = 1, imm = 0),
              FrontendPredType.JALR,
              staleTarget
            )

            dut.io.debug.get.idExeValid.expect(true.B)
            dut.io.debug.get.idExePc.expect(jalrPc.U)
            dut.io.frontendRedirect.valid.expect(true.B)
            dut.io.frontendRedirect.target.expect(actualTarget.U)
            dut.io.frontendBtbUpdate.valid.expect(true.B)
            dut.io.frontendBtbUpdate.pc.expect(jalrPc.U)
            dut.io.frontendBtbUpdate.target.expect(actualTarget.U)
            dut.io.frontendBtbUpdate.predType.expect(FrontendPredType.JALR)
            dut.io.frontendBtbUpdate.taken.expect(true.B)
            dut.io.frontendPhtUpdate.valid.expect(false.B)
            dut.io.frontendGhrUpdate.valid.expect(false.B)

            dut.clock.step(1)
            dut.io.frontendRedirect.valid.expect(false.B)
            dut.io.frontendBtbUpdate.valid.expect(false.B)
        }
    }

    "GShare backend should keep a correct JALR target without retraining" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { dut =>
            val producerPc = BigInt(0xfc)
            val jalrPc = BigInt(0x100)
            val actualTarget = BigInt(0x80)

            reset(dut)
            issueInstruction(dut, producerPc, encodeAddi(rd = 1, rs1 = 0, imm = actualTarget.toInt))
            issuePredictedControl(
              dut,
              jalrPc,
              encodeJalr(rd = 0, rs1 = 1, imm = 0),
              FrontendPredType.JALR,
              actualTarget
            )

            dut.io.frontendRedirect.valid.expect(false.B)
            dut.io.frontendBtbUpdate.valid.expect(false.B)
            dut.io.frontendPhtUpdate.valid.expect(false.B)
            dut.io.frontendGhrUpdate.valid.expect(false.B)
        }
    }
}
