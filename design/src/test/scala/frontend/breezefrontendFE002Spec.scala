package flow.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeFrontendFE002Spec extends AnyFreeSpec with Matchers with ChiselSim {
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
        simulate(new BreezeFrontend(enabledebug = true)){dut =>
            val debug = dut.io.debug.get
            val refillDelayCycles = 6
            val refillInstWords = Seq.tabulate(8)(i => encodeAddi(rd = i + 1, rs1 = 0, imm = i + 1))
            val refillLineData = buildRefillLine(refillInstWords)
            val maxObserveCycles = 40

            dut.io.fetchBuffer.canAccept3.poke(true.B)
            dut.io.resetAddr.poke(0x0.U)
            dut.io.beRedirect.valid.poke(false.B)
            dut.io.beRedirect.flush.poke(false.B)
            dut.io.beRedirect.cacheFlush.poke(false.B)
            dut.io.beRedirect.target.poke(0x0.U)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0x0.U)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            var cycle = 1
            var observedReq = false
            var refillSent = false
            var reqAddr = BigInt(0)
            var delayCount = 0
            var responseBeat = 0
            var fetchBeat = 0
            var observedFirstResponse = false
            var postResponseCheckIndex = -1

            while (cycle <= maxObserveCycles) {
                val reqValid = dut.io.nextLevelReq.req.peek().litToBoolean
                val reqPaddr = dut.io.nextLevelReq.paddr.peek().litValue
                val cacheRspValid = debug.cache_drsp_valid.peek().litToBoolean
                val cacheRspVaddr = debug.cache_drsp_vaddr.peek().litValue
                val s2RespValid = debug.s2_respValid.peek().litToBoolean
                val s2Pc = debug.s2_pcReg.peek().litValue
                val fetchValid = dut.io.fetchBuffer.valid.peek().litToBoolean
                val fetchPc = dut.io.fetchBuffer.bits.pc.peek().litValue
                val fetchInst = dut.io.fetchBuffer.bits.inst.peek().litValue

                if(false)println(
                  f"[INFO] FE002 cycle $cycle: " +
                    f"nextLevelReq.req=$reqValid " +
                    f"nextLevelReq.paddr=0x${reqPaddr}%x " +
                    f"cache_drsp_valid=$cacheRspValid " +
                    f"cache_drsp_vaddr=0x${cacheRspVaddr}%x " +
                    f"s2_respValid=$s2RespValid " +
                    f"s2_pcReg=0x${s2Pc}%x " +
                    f"s1_pcReg=0x${debug.s1_pcReg.peek().litValue}%x " +
                    f"s3_pcReg=0x${debug.s3_pcReg.peek().litValue}%x"
                )

                if (!observedReq && reqValid) {
                    observedReq = true
                    reqAddr = reqPaddr
                    dut.io.nextLevelReq.paddr.expect(0x0.U)
                    if(false)println(f"[INFO] FE002 observed miss request at cycle $cycle, paddr=0x${reqAddr}%x")
                } else if (observedReq && !refillSent) {
                    delayCount += 1
                    if (delayCount == refillDelayCycles) {
                        dut.io.nextLevelRsp.data.poke(refillLineData.U)
                        dut.io.nextLevelRsp.vld.poke(true.B)
                        refillSent = true
                        responseBeat = 0
                        fetchBeat = 0
                        println(
                          f"[INFO] FE002 send refill at cycle $cycle: " +
                            f"data=0x${refillLineData}%x"
                        )
                    }
                }

                if (!observedFirstResponse && (cacheRspValid || s2RespValid)) {
                    val expectedPc = BigInt(responseBeat * 4)
                    println(
                      f"[INFO] FE002 response observe at cycle $cycle: " +
                        f"cache_drsp_valid=$cacheRspValid " +
                        f"cache_drsp_vaddr=0x${cacheRspVaddr}%x " +
                        f"s2_respValid=$s2RespValid " +
                        f"s2_pcReg=0x${s2Pc}%x " +
                        f"s1_valid=${debug.s1_valid.peek().litToBoolean} " +
                        f"s1_pcReg=0x${debug.s1_pcReg.peek().litValue}%x " +
                        f"expected_pc=0x${expectedPc}%x"
                    )
                    cacheRspValid mustBe true
                    s2RespValid mustBe true
                    responseBeat must be < refillInstWords.length
                    debug.cache_drsp_vaddr.expect(expectedPc.U)
                    debug.s2_pcReg.expect(expectedPc.U)
                    debug.s1_valid.expect(true.B)
                    debug.s1_pcReg.expect(0x4.U)
                    responseBeat += 1
                    observedFirstResponse = true
                    postResponseCheckIndex = 0
                } else if (postResponseCheckIndex >= 0 && postResponseCheckIndex < refillInstWords.length) {
                    val expectedS3Pc = BigInt(postResponseCheckIndex * 4)
                    val expectedS2Pc = BigInt((postResponseCheckIndex + 1) * 4)
                    val expectedS1Pc = BigInt((postResponseCheckIndex + 2) * 4)
                    val expectedInst = refillInstWords(postResponseCheckIndex)
                    println(
                      f"[INFO] FE002 post-response stream cycle $cycle: " +
                        f"idx=$postResponseCheckIndex " +
                        f"s3_pcReg=0x${debug.s3_pcReg.peek().litValue}%x " +
                        f"s3_inst=0x${fetchInst}%x " +
                        s"s3_disasm=${disasmInst(fetchInst)}"
                    )
                    debug.s3_valid.expect(true.B)
                    debug.s3_pcReg.expect(expectedS3Pc.U)
                    debug.s2_valid.expect(true.B)
                    debug.s2_pcReg.expect(expectedS2Pc.U)
                    debug.s1_valid.expect(true.B)
                    debug.s1_pcReg.expect(expectedS1Pc.U)
                    dut.io.fetchBuffer.valid.expect(true.B)
                    dut.io.fetchBuffer.bits.pc.expect(expectedS3Pc.U)
                    dut.io.fetchBuffer.bits.inst.expect(expectedInst.U)
                    dut.io.nextLevelReq.req.expect(false.B)
                    postResponseCheckIndex += 1
                }

                if (!observedFirstResponse && fetchValid) {
                    val expectedPc = BigInt(fetchBeat * 4)
                    val expectedInst = refillInstWords(fetchBeat)
                    println(
                      f"[INFO] FE002 fetch observe at cycle $cycle: " +
                        f"fetch_pc=0x${fetchPc}%x " +
                        f"fetch_inst=0x${fetchInst}%x " +
                        s"fetch_disasm=${disasmInst(fetchInst)} " +
                        f"expected_pc=0x${expectedPc}%x " +
                        f"expected_inst=0x${expectedInst}%x " +
                        s"expected_disasm=${disasmInst(expectedInst)}"
                    )
                    fetchBeat must be < refillInstWords.length
                    dut.io.fetchBuffer.bits.pc.expect(expectedPc.U)
                    dut.io.fetchBuffer.bits.inst.expect(expectedInst.U)
                    fetchBeat += 1
                }

                dut.clock.step(1)
                cycle += 1

                if (refillSent) {
                    dut.io.nextLevelRsp.vld.poke(false.B)
                }
            }
        }
    }
}
