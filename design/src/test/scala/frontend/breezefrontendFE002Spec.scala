package flow.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.{BreezeFrontendConfig, NoBranchPredictorConfig}
import flow.platform.BreezeMcuPlatform
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeFrontendFE002Spec extends AnyFreeSpec with Matchers with ChiselSim {
    private val BootAddr = BreezeMcuPlatform.ResetVector

    private def encodeAddi(rd: Int, rs1: Int, imm: Int): BigInt = {
        val imm12 = imm & 0xfff
        BigInt((imm12 << 20) | (rs1 << 15) | (0 << 12) | (rd << 7) | 0x13)
    }

    private def signExtend(value: BigInt, bits: Int): BigInt = {
        val signBit = BigInt(1) << (bits - 1)
        val mask = (BigInt(1) << bits) - 1
        val truncated = value & mask
        if ((truncated & signBit) != 0) truncated - (BigInt(1) << bits) else truncated
    }

    private def disasmInst(inst: BigInt): String = {
        val word = inst & BigInt("ffffffff", 16)
        val opcode = (word & 0x7f).toInt
        val rd = ((word >> 7) & 0x1f).toInt
        val funct3 = ((word >> 12) & 0x7).toInt
        val rs1 = ((word >> 15) & 0x1f).toInt
        val immI = signExtend(word >> 20, 12)

        opcode match {
            case 0x13 if funct3 == 0x0 => s"addi x$rd, x$rs1, $immI"
            case _ => f"unknown(0x$word%08x)"
        }
    }

    private def buildRefillLine(words: Seq[BigInt]): BigInt = {
        words.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (word, idx)) =>
            acc | ((word & BigInt("ffffffff", 16)) << (idx * 32))
        }
    }

    "BreezeFrontend FE-002 bug replication scenario" in {
        simulate(new BreezeFrontend(BreezeFrontendConfig(branchPredCfg = NoBranchPredictorConfig), enabledebug = true)){dut =>
            val debug = dut.io.debug.get
            val refillDelayCycles = 6
            val refillInstWords = Seq.tabulate(8)(i => encodeAddi(rd = i + 1, rs1 = 0, imm = i + 1))
            val refillLineData = buildRefillLine(refillInstWords)
            val maxObserveCycles = 40

            dut.io.fetchBuffer.canAccept3.poke(true.B)
            dut.io.resetAddr.poke(BootAddr.U)
            dut.io.beRedirect.valid.poke(false.B)
            dut.io.beRedirect.flush.poke(false.B)
            dut.io.beRedirect.cacheFlush.poke(false.B)
            dut.io.beRedirect.target.poke(0x0.U)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0x0.U)
            dut.io.nextLevelRsp.error.poke(false.B)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            var cycle = 1
            var observedReq = false
            var refillSent = false
            var delayCount = 0
            var refillCycle = -1
            var firstResponseCycle = -1
            var fetchBeat = 0
            // Reset clears all owners; only a fire after reset release counts.
            // Return accounting follows the PC sequence, independent of DUT data.
            val owners = scala.collection.mutable.Queue.empty[BigInt]
            var nextPc = BootAddr

            while (cycle <= maxObserveCycles) {
                val hadObservedReq = observedReq
                debug.s1_pcReg.expect(nextPc.U)
                val accepted = debug.dreq_fire.peek().litToBoolean
                val acceptedPc = nextPc
                if (debug.s2_valid.peek().litToBoolean) {
                    owners.nonEmpty mustBe true
                    debug.s2_pcReg.expect(owners.front.U)
                }
                if (dut.io.nextLevelReq.req.peek().litToBoolean) {
                    if (!observedReq) {
                        dut.io.nextLevelReq.paddr.expect(BootAddr.U)
                        observedReq = true
                    } else if (refillSent) {
                        // The first request beyond the eight returned words is
                        // a new line miss. It cannot return without new data.
                        dut.io.nextLevelReq.paddr.expect((BootAddr + 32).U)
                    }
                }
                if (hadObservedReq && !refillSent) {
                    delayCount += 1
                    if (delayCount == refillDelayCycles) {
                        dut.io.nextLevelRsp.data.poke(refillLineData.U)
                        dut.io.nextLevelRsp.vld.poke(true.B)
                        refillSent = true
                        refillCycle = cycle
                    }
                }
                val response = debug.cache_drsp_valid.peek().litToBoolean
                debug.s2_respValid.expect(response.B)
                if (response) {
                    owners.nonEmpty mustBe true
                    val expectedPc = owners.dequeue()
                    debug.cache_drsp_vaddr.expect(expectedPc.U)
                    debug.s2_pcReg.expect(expectedPc.U)
                    if (firstResponseCycle < 0) {
                        expectedPc mustBe BootAddr
                        cycle mustBe refillCycle + 2
                        firstResponseCycle = cycle
                    }
                }
                if (dut.io.fetchBuffer.valid.peek().litToBoolean) {
                    fetchBeat must be < refillInstWords.length
                    firstResponseCycle must be >= 0
                    // The added return boundary retains a gap-free eight-word
                    // stream after its first response; no tolerance or skip.
                    cycle mustBe firstResponseCycle + 1 + fetchBeat
                    val expectedPc = BootAddr + fetchBeat * 4
                    debug.s3_valid.expect(true.B)
                    debug.s3_pcReg.expect(expectedPc.U)
                    dut.io.fetchBuffer.bits.pc.expect(expectedPc.U)
                    dut.io.fetchBuffer.bits.inst.expect(refillInstWords(fetchBeat).U)
                    fetchBeat += 1
                }
                if (accepted) {
                    owners.enqueue(acceptedPc)
                    nextPc += 4
                }
                dut.clock.step()
                cycle += 1
                if (refillSent) dut.io.nextLevelRsp.vld.poke(false.B)
            }
            observedReq mustBe true
            refillSent mustBe true
            fetchBeat mustBe refillInstWords.length
        }
    }
}
