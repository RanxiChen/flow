package flow.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.{BreezeFrontendConfig, GShareBranchPredictorConfig}
import flow.interface.FrontendPredType
import flow.platform.BreezeMcuPlatform
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeFrontendGShareSpec extends AnyFreeSpec with Matchers with ChiselSim {
    private val GhrLength = 4
    private val BootAddr = BreezeMcuPlatform.ResetVector
    private val cfg = BreezeFrontendConfig(
      branchPredCfg = GShareBranchPredictorConfig(
        ghrLength = GhrLength,
        btbEntryNum = 4
      )
    )

    private def driveIdleInputs(dut: BreezeFrontend): Unit = {
        dut.io.resetAddr.poke(BootAddr.U)
        dut.io.beRedirect.valid.poke(false.B)
        dut.io.beRedirect.flush.poke(false.B)
        dut.io.beRedirect.cacheFlush.poke(false.B)
        dut.io.beRedirect.target.poke(0.U)
        dut.io.btbUpdate.valid.poke(false.B)
        dut.io.btbUpdate.pc.poke(0.U)
        dut.io.btbUpdate.target.poke(0.U)
        dut.io.btbUpdate.predType.poke(FrontendPredType.NONE)
        dut.io.btbUpdate.taken.poke(false.B)
        dut.io.phtUpdate.valid.poke(false.B)
        dut.io.phtUpdate.idx.poke(0.U)
        dut.io.phtUpdate.taken.poke(false.B)
        dut.io.ghrUpdate.valid.poke(false.B)
        dut.io.ghrUpdate.taken.poke(false.B)
        dut.io.fetchBuffer.canAccept3.poke(false.B)
        dut.io.nextLevelRsp.vld.poke(false.B)
        dut.io.nextLevelRsp.data.poke(0.U)
        dut.io.nextLevelRsp.error.poke(false.B)
    }

    private def encodeAddi(rd: Int, imm: Int): BigInt = {
        (BigInt(imm & 0xfff) << 20) |
        (BigInt(rd) << 7) |
        BigInt(0x13)
    }

    private def buildRefillLine(words: Seq[BigInt]): BigInt = {
        words.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (word, idx)) =>
            acc | ((word & BigInt("ffffffff", 16)) << (idx * 32))
        }
    }

    private def stepUntil(dut: BreezeFrontend, maxCycles: Int = 32)(cond: => Boolean): Unit = {
        var cycles = 0
        while (!cond && cycles < maxCycles) {
            dut.clock.step(1)
            cycles += 1
        }
        assert(cond, s"condition not met within $maxCycles cycles")
    }

    "GShare frontend should shift actual branch outcomes into GHR exactly once per update" in {
        simulate(new BreezeFrontend(cfg, enabledebug = true)) { dut =>
            val debug = dut.io.debug.get

            driveIdleInputs(dut)
            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)
            debug.ghr.expect(0.U)

            dut.io.ghrUpdate.valid.poke(true.B)
            dut.io.ghrUpdate.taken.poke(true.B)
            dut.clock.step(1)
            debug.ghr.expect(1.U)

            dut.io.ghrUpdate.taken.poke(false.B)
            dut.clock.step(1)
            debug.ghr.expect(2.U)

            dut.io.ghrUpdate.taken.poke(true.B)
            dut.clock.step(1)
            debug.ghr.expect(5.U)

            dut.io.ghrUpdate.valid.poke(false.B)
            dut.io.ghrUpdate.taken.poke(false.B)
            dut.clock.step(3)
            debug.ghr.expect(5.U)
        }
    }

    "GShare frontend should reset GHR without changing the baseline-visible fetch state" in {
        simulate(new BreezeFrontend(cfg, enabledebug = true)) { dut =>
            val debug = dut.io.debug.get

            driveIdleInputs(dut)
            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            debug.ghr.expect(0.U)
            debug.s1PredTaken.expect(false.B)
            debug.s1PredPc.expect((BootAddr + 4).U)
            debug.s1PhtIdx.expect(0.U)
            debug.s3FastRedirectValid.expect(false.B)
        }
    }

    "GShare frontend should use trained BTB and PHT state for the S1 next PC" in {
        simulate(new BreezeFrontend(cfg, enabledebug = true)) { dut =>
            val debug = dut.io.debug.get
            val branchPc = BootAddr + 0x100
            val targetPc = BootAddr + 0x80
            val phtIdx = ((branchPc >> 2) & ((1 << GhrLength) - 1))

            driveIdleInputs(dut)
            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            // One taken update moves the selected PHT entry from 01 to 10.
            dut.io.btbUpdate.valid.poke(true.B)
            dut.io.btbUpdate.pc.poke(branchPc.U)
            dut.io.btbUpdate.target.poke(targetPc.U)
            dut.io.btbUpdate.predType.poke(FrontendPredType.BR)
            dut.io.btbUpdate.taken.poke(true.B)
            dut.io.phtUpdate.valid.poke(true.B)
            dut.io.phtUpdate.idx.poke(phtIdx.U)
            dut.io.phtUpdate.taken.poke(true.B)
            dut.clock.step(1)
            dut.io.btbUpdate.valid.poke(false.B)
            dut.io.phtUpdate.valid.poke(false.B)

            // Redirect while fetch is blocked so the next accepted request is
            // unambiguously the trained branch PC.
            dut.io.beRedirect.valid.poke(true.B)
            dut.io.beRedirect.flush.poke(true.B)
            dut.io.beRedirect.target.poke(branchPc.U)
            dut.clock.step(1)
            dut.io.beRedirect.valid.poke(false.B)
            dut.io.beRedirect.flush.poke(false.B)
            dut.io.fetchBuffer.canAccept3.poke(true.B)

            debug.s1_pcReg.expect(branchPc.U)
            debug.dreq_fire.expect(true.B)
            debug.s1PhtIdx.expect(phtIdx.U)
            debug.s1PredTaken.expect(true.B)
            debug.s1PredPc.expect(targetPc.U)

            dut.clock.step(1)
            debug.s1_pcReg.expect(targetPc.U)
        }
    }

    "GShare frontend should discard a wrong-path refill after redirect" in {
        simulate(new BreezeFrontend(cfg, enabledebug = true)) { dut =>
            val debug = dut.io.debug.get
            val wrongPathPc = BootAddr + 0x100
            val redirectPc = BootAddr + 0x200
            val wrongPathInst = encodeAddi(rd = 9, imm = 9)
            val redirectInst = encodeAddi(rd = 5, imm = 0x55)
            val wrongPathLine = buildRefillLine(wrongPathInst +: Seq.fill(7)(BigInt(0x13)))
            val redirectLine = buildRefillLine(redirectInst +: Seq.fill(7)(BigInt(0x13)))

            driveIdleInputs(dut)
            dut.io.resetAddr.poke(wrongPathPc.U)
            dut.io.fetchBuffer.canAccept3.poke(true.B)
            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            stepUntil(dut) { dut.io.nextLevelReq.req.peek().litToBoolean }
            dut.io.nextLevelReq.paddr.expect(wrongPathPc.U)

            // Redirect while the original cacheline request is outstanding.
            dut.io.beRedirect.valid.poke(true.B)
            dut.io.beRedirect.flush.poke(true.B)
            dut.io.beRedirect.target.poke(redirectPc.U)
            dut.clock.step(1)
            dut.io.beRedirect.valid.poke(false.B)
            dut.io.beRedirect.flush.poke(false.B)
            debug.s1_pcReg.expect(redirectPc.U)
            debug.s2_valid.expect(false.B)

            // The old response may still refill the ICache, but must never be
            // paired with the redirected frontend context or reach fetchBuffer.
            dut.io.nextLevelRsp.data.poke(wrongPathLine.U)
            dut.io.nextLevelRsp.vld.poke(true.B)
            dut.clock.step(1)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0.U)

            stepUntil(dut) {
                dut.io.fetchBuffer.valid.expect(false.B)
                dut.io.nextLevelReq.req.peek().litToBoolean
            }
            dut.io.nextLevelReq.paddr.expect(redirectPc.U)

            dut.clock.step(2)
            dut.io.fetchBuffer.valid.expect(false.B)
            dut.io.nextLevelRsp.data.poke(redirectLine.U)
            dut.io.nextLevelRsp.vld.poke(true.B)
            dut.clock.step(1)
            dut.io.nextLevelRsp.vld.poke(false.B)

            stepUntil(dut) { dut.io.fetchBuffer.valid.peek().litToBoolean }
            dut.io.fetchBuffer.bits.pc.expect(redirectPc.U)
            dut.io.fetchBuffer.bits.inst.expect(redirectInst.U)
            debug.s3_pcReg.expect(redirectPc.U)
        }
    }
}
