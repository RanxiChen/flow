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

            while (cycle <= maxObserveCycles) {
                val reqValid = dut.io.nextLevelReq.req.peek().litToBoolean
                val reqPaddr = dut.io.nextLevelReq.paddr.peek().litValue
                val cacheRspValid = debug.cache_drsp_valid.peek().litToBoolean
                val cacheRspVaddr = debug.cache_drsp_vaddr.peek().litValue
                val s2RespValid = debug.s2_respValid.peek().litToBoolean
                val s2Pc = debug.s2_pcReg.peek().litValue

                println(
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
                    println(f"[INFO] FE002 observed miss request at cycle $cycle, paddr=0x${reqAddr}%x")
                } else if (observedReq && !refillSent) {
                    delayCount += 1
                    if (delayCount == refillDelayCycles) {
                        dut.io.nextLevelRsp.data.poke(refillLineData.U)
                        dut.io.nextLevelRsp.vld.poke(true.B)
                        refillSent = true
                        println(
                          f"[INFO] FE002 send refill at cycle $cycle: " +
                            f"data=0x${refillLineData}%x"
                        )
                    }
                }

                if (cacheRspValid || s2RespValid) {
                    println(
                      f"[INFO] FE002 response observe at cycle $cycle: " +
                        f"cache_drsp_valid=$cacheRspValid " +
                        f"cache_drsp_vaddr=0x${cacheRspVaddr}%x " +
                        f"s2_respValid=$s2RespValid " +
                        f"s2_pcReg=0x${s2Pc}%x"
                    )
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
