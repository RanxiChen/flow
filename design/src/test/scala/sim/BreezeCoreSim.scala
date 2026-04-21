package flow.sim

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.BreezeCoreConfig
import flow.core.BreezeCore
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import scala.collection.mutable

class BreezeCoreSim extends AnyFreeSpec with Matchers with ChiselSim {
    private val mask32 = (BigInt(1) << 32) - 1
    private val mask64 = (BigInt(1) << 64) - 1
    private val estopInst = BigInt("7ff00073", 16)

    private def encodeAddi(rd: Int, rs1: Int, imm: Int): BigInt = {
        val imm12 = imm & 0xfff
        (BigInt(imm12) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(0) << 12) |
        (BigInt(rd) << 7) |
        BigInt(0x13)
    }

    private def encodeLoad(rd: Int, rs1: Int, imm: Int, funct3: Int): BigInt = {
        val imm12 = imm & 0xfff
        (BigInt(imm12) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(funct3) << 12) |
        (BigInt(rd) << 7) |
        BigInt(0x03)
    }

    private def encodeStore(rs1: Int, rs2: Int, imm: Int, funct3: Int): BigInt = {
        val imm12 = imm & 0xfff
        val immHi = (imm12 >> 5) & 0x7f
        val immLo = imm12 & 0x1f
        (BigInt(immHi) << 25) |
        (BigInt(rs2) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(funct3) << 12) |
        (BigInt(immLo) << 7) |
        BigInt(0x23)
    }

    private def alignDown(addr: BigInt, alignBytes: Int): BigInt = {
        require(alignBytes > 0 && (alignBytes & (alignBytes - 1)) == 0, s"alignBytes must be power of two: $alignBytes")
        addr & ~BigInt(alignBytes - 1)
    }

    private def read64(memory: mutable.Map[BigInt, BigInt], addr: BigInt): BigInt = {
        memory.getOrElse(alignDown(addr, 8), BigInt(0)) & mask64
    }

    private def write64(
        memory: mutable.Map[BigInt, BigInt],
        addr: BigInt,
        wdata: BigInt,
        wmask: BigInt
    ): BigInt = {
        val alignedAddr = alignDown(addr, 8)
        var nextWord = read64(memory, alignedAddr)

        for (lane <- 0 until 8) {
            if (((wmask >> lane) & 1) == 1) {
                val laneShift = lane * 8
                val laneMask = BigInt(0xff) << laneShift
                nextWord = (nextWord & ~laneMask) | (((wdata >> laneShift) & 0xff) << laneShift)
            }
        }

        val maskedWord = nextWord & mask64
        memory.update(alignedAddr, maskedWord)
        maskedWord
    }

    private def buildCacheLine(
        memory: mutable.Map[BigInt, BigInt],
        reqAddr: BigInt,
        cacheLineBytes: Int
    ): BigInt = {
        val lineBase = alignDown(reqAddr, cacheLineBytes)
        val beatCount = cacheLineBytes / 8
        (0 until beatCount).foldLeft(BigInt(0)) { (acc, beatIdx) =>
            acc | (read64(memory, lineBase + beatIdx * 8) << (beatIdx * 64))
        }
    }

    private def buildInstructionMemory(
        instructions: Seq[BigInt],
        baseAddr: BigInt = 0
    ): mutable.LinkedHashMap[BigInt, BigInt] = {
        val memory = mutable.LinkedHashMap.empty[BigInt, BigInt]
        instructions.zipWithIndex.foreach { case (inst, idx) =>
            val byteAddr = baseAddr + idx * 4
            val alignedAddr = alignDown(byteAddr, 8)
            val shift = ((byteAddr - alignedAddr) * 8).toInt
            val prev = memory.getOrElse(alignedAddr, BigInt(0))
            val next = (prev & ~(mask32 << shift)) | ((inst & mask32) << shift)
            memory.update(alignedAddr, next & mask64)
        }
        memory
    }

    "BreezeCore Run simulate" in {
        simulate(new BreezeCore(BreezeCoreConfig(useFASE = false), enabledebug = true)) { dut =>
            val max_cycles = 100000
            val imem_lantency = 6
            val dmem_lantency = 7
            val coreCfg = BreezeCoreConfig(useFASE = false)
            val cacheLineBytes = coreCfg.frontendCfg.cacheCfg.ICACHE_LINE_BYTES
            val program = Seq(
                encodeAddi(rd = 1, rs1 = 0, imm = 42),
                encodeAddi(rd = 3, rs1 = 0, imm = 256),
                encodeStore(rs1 = 3, rs2 = 1, imm = 0, funct3 = 3),
                encodeLoad(rd = 2, rs1 = 3, imm = 0, funct3 = 3),
                estopInst
            )
            // 这里只假定有一份地址到 64-bit word 的 map 可用，构造方式后续再替换。
            val memory = buildInstructionMemory(program)

            var cycleCount = 0
            var process_imem_req = false
            var process_dmem_req = false
            var imem_tape = 0
            var dmem_tape = 0
            var imem_wait_addr: BigInt = 0
            var dmem_wait_addr: BigInt = 0
            var imem_resp_data: BigInt = 0
            var dmem_resp_data: BigInt = 0
            var dmem_wait_is_write = false

            dut.io.resetAddr.poke(0.U)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0.U)
            dut.io.dmem.rsp.valid.poke(false.B)
            dut.io.dmem.rsp.data.poke(0.U)
            dut.io.dmem.rsp.isWriteAck.poke(false.B)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            while (dut.io.estop.peek().litToBoolean == false && cycleCount < max_cycles) {
                dut.io.nextLevelRsp.vld.poke(false.B)
                dut.io.nextLevelRsp.data.poke(0.U)
                dut.io.dmem.rsp.valid.poke(false.B)
                dut.io.dmem.rsp.data.poke(0.U)
                dut.io.dmem.rsp.isWriteAck.poke(false.B)

                if (process_imem_req) {
                    if (imem_tape == imem_lantency) {
                        dut.io.nextLevelRsp.vld.poke(true.B)
                        dut.io.nextLevelRsp.data.poke(imem_resp_data.U)
                        println(s"[FE] refill ${imem_resp_data.toString(16)} to ${imem_wait_addr.toString(16)}")
                        process_imem_req = false
                        imem_tape = 0
                    } else {
                        imem_tape += 1
                    }
                } else {
                    if (dut.io.nextLevelReq.req.peek().litToBoolean) {
                        imem_wait_addr = dut.io.nextLevelReq.paddr.peek().litValue
                        imem_resp_data = buildCacheLine(memory, imem_wait_addr, cacheLineBytes)
                        process_imem_req = true
                        imem_tape = 0
                        println(s"[FE] receive imem req for ${imem_wait_addr.toString(16)}")
                    }
                }

                if (process_dmem_req) {
                    if (dmem_tape == dmem_lantency) {
                        dut.io.dmem.rsp.valid.poke(true.B)
                        dut.io.dmem.rsp.data.poke(dmem_resp_data.U)
                        dut.io.dmem.rsp.isWriteAck.poke(dmem_wait_is_write.B)
                        println(
                          s"[BE] respond ${if (dmem_wait_is_write) "write" else "read"} " +
                            s"addr=${dmem_wait_addr.toString(16)} data=${dmem_resp_data.toString(16)}"
                        )
                        process_dmem_req = false
                        dmem_tape = 0
                    } else {
                        dmem_tape += 1
                    }
                } else {
                    if (dut.io.dmem.req.valid.peek().litToBoolean) {
                        dmem_wait_addr = dut.io.dmem.req.addr.peek().litValue
                        dmem_wait_is_write = dut.io.dmem.req.isWrite.peek().litToBoolean

                        if (dmem_wait_is_write) {
                            val wdata = dut.io.dmem.req.wdata.peek().litValue
                            val wmask = dut.io.dmem.req.wmask.peek().litValue
                            dmem_resp_data = write64(memory, dmem_wait_addr, wdata, wmask)
                            println(
                              s"[BE] receive dmem write addr=${dmem_wait_addr.toString(16)} " +
                                s"wdata=${wdata.toString(16)} wmask=${wmask.toString(16)}"
                            )
                        } else {
                            dmem_resp_data = read64(memory, dmem_wait_addr)
                            println(s"[BE] receive dmem read addr=${dmem_wait_addr.toString(16)}")
                        }

                        process_dmem_req = true
                        dmem_tape = 0
                    }
                }

                dut.clock.step(1)
                cycleCount += 1
            }

            cycleCount must be < max_cycles
        }
    }
}
