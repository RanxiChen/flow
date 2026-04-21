package flow.sim

import chisel3._
import chisel3.simulator.EphemeralSimulator
import chisel3.simulator.PeekPokeAPI
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import flow.config.BreezeCoreConfig
import flow.core.BreezeCore

import java.io.File
import scala.collection.mutable

final case class BreezeCoreSimResult(cycleCount: Int, timedOut: Boolean)

object BreezeCoreSimSupport {
    val Mask32: BigInt = (BigInt(1) << 32) - 1
    val Mask64: BigInt = (BigInt(1) << 64) - 1
    val EstopInst: BigInt = BigInt("7ff00073", 16)

    def encodeAddi(rd: Int, rs1: Int, imm: Int): BigInt = {
        val imm12 = imm & 0xfff
        (BigInt(imm12) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(0) << 12) |
        (BigInt(rd) << 7) |
        BigInt(0x13)
    }

    def encodeLoad(rd: Int, rs1: Int, imm: Int, funct3: Int): BigInt = {
        val imm12 = imm & 0xfff
        (BigInt(imm12) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(funct3) << 12) |
        (BigInt(rd) << 7) |
        BigInt(0x03)
    }

    def encodeStore(rs1: Int, rs2: Int, imm: Int, funct3: Int): BigInt = {
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

    def alignDown(addr: BigInt, alignBytes: Int): BigInt = {
        require(alignBytes > 0 && (alignBytes & (alignBytes - 1)) == 0, s"alignBytes must be power of two: $alignBytes")
        addr & ~BigInt(alignBytes - 1)
    }

    def read64(memory: mutable.Map[BigInt, BigInt], addr: BigInt): BigInt = {
        memory.getOrElse(alignDown(addr, 8), BigInt(0)) & Mask64
    }

    def write64(
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

        val maskedWord = nextWord & Mask64
        memory.update(alignedAddr, maskedWord)
        maskedWord
    }

    def buildCacheLine(
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

    def buildInstructionMemory(
        instructions: Seq[BigInt],
        baseAddr: BigInt = 0
    ): mutable.LinkedHashMap[BigInt, BigInt] = {
        val memory = mutable.LinkedHashMap.empty[BigInt, BigInt]
        instructions.zipWithIndex.foreach { case (inst, idx) =>
            val byteAddr = baseAddr + idx * 4
            val alignedAddr = alignDown(byteAddr, 8)
            val shift = ((byteAddr - alignedAddr) * 8).toInt
            val prev = memory.getOrElse(alignedAddr, BigInt(0))
            val next = (prev & ~(Mask32 << shift)) | ((inst & Mask32) << shift)
            memory.update(alignedAddr, next & Mask64)
        }
        memory
    }
}

object BreezeCoreSimMemoryLoader {
    private val mapper = new ObjectMapper()

    def loadMemoryMap(path: String): mutable.LinkedHashMap[BigInt, BigInt] = {
        val root = mapper.readTree(new File(path))
        val memoryMapNode = Option(root.get("memoryMap")).getOrElse {
            throw new IllegalArgumentException(s"missing top-level memoryMap in $path")
        }
        if (!memoryMapNode.isObject) {
            throw new IllegalArgumentException(s"memoryMap must be a JSON object in $path")
        }

        val memory = mutable.LinkedHashMap.empty[BigInt, BigInt]
        val fields = memoryMapNode.fields()
        while (fields.hasNext) {
            val entry = fields.next()
            val addr = parseNumericString(entry.getKey)
            require((addr & 0x7) == 0, s"memoryMap address must be 8-byte aligned: ${entry.getKey}")
            val data = parseNodeValue(entry.getValue) & BreezeCoreSimSupport.Mask64
            memory.update(addr, data)
        }
        memory
    }

    def loadBootAddr(path: String): BigInt = {
        val root = mapper.readTree(new File(path))
        val simulationNode = Option(root.get("simulation")).getOrElse {
            throw new IllegalArgumentException(s"missing top-level simulation in $path")
        }
        val bootAddrNode = Option(simulationNode.get("bootaddr")).getOrElse {
            throw new IllegalArgumentException(s"missing simulation.bootaddr in $path")
        }
        parseNodeValue(bootAddrNode)
    }

    private def parseNodeValue(node: JsonNode): BigInt = {
        if (node.isTextual) {
            parseNumericString(node.asText())
        } else if (node.isIntegralNumber) {
            BigInt(node.bigIntegerValue())
        } else {
            throw new IllegalArgumentException(s"memoryMap value must be textual or integral: $node")
        }
    }

    private def parseNumericString(raw: String): BigInt = {
        val text = raw.trim
        if (text.startsWith("0x") || text.startsWith("0X")) {
            BigInt(text.drop(2), 16)
        } else {
            BigInt(text, 10)
        }
    }
}

object BreezeCoreSimRunner extends PeekPokeAPI {
    def run(
        memory: mutable.Map[BigInt, BigInt],
        coreCfg: BreezeCoreConfig,
        maxCycles: Int,
        imemLatency: Int,
        dmemLatency: Int
    ): BreezeCoreSimResult = {
        run(
          memory = memory,
          coreCfg = coreCfg,
          maxCycles = maxCycles,
          imemLatency = imemLatency,
          dmemLatency = dmemLatency,
          bootAddr = 0
        )
    }

    def run(
        memory: mutable.Map[BigInt, BigInt],
        coreCfg: BreezeCoreConfig = BreezeCoreConfig(useFASE = false),
        maxCycles: Int = 100000,
        imemLatency: Int = 6,
        dmemLatency: Int = 7,
        bootAddr: BigInt = 0
    ): BreezeCoreSimResult = {
        var result = BreezeCoreSimResult(cycleCount = 0, timedOut = false)

        EphemeralSimulator.simulate(new BreezeCore(coreCfg, enabledebug = true)) { dut =>
            val cacheLineBytes = coreCfg.frontendCfg.cacheCfg.ICACHE_LINE_BYTES

            var cycleCount = 0
            var processImemReq = false
            var processDmemReq = false
            var imemTape = 0
            var dmemTape = 0
            var imemWaitAddr: BigInt = 0
            var dmemWaitAddr: BigInt = 0
            var imemRespData: BigInt = 0
            var dmemRespData: BigInt = 0
            var dmemWaitIsWrite = false

            dut.io.resetAddr.poke(bootAddr.U)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0.U)
            dut.io.dmem.rsp.valid.poke(false.B)
            dut.io.dmem.rsp.data.poke(0.U)
            dut.io.dmem.rsp.isWriteAck.poke(false.B)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            while (!dut.io.estop.peek().litToBoolean && cycleCount < maxCycles) {
                dut.io.nextLevelRsp.vld.poke(false.B)
                dut.io.nextLevelRsp.data.poke(0.U)
                dut.io.dmem.rsp.valid.poke(false.B)
                dut.io.dmem.rsp.data.poke(0.U)
                dut.io.dmem.rsp.isWriteAck.poke(false.B)

                if (processImemReq) {
                    if (imemTape == imemLatency) {
                        dut.io.nextLevelRsp.vld.poke(true.B)
                        dut.io.nextLevelRsp.data.poke(imemRespData.U)
                        println(s"[FE] refill ${imemRespData.toString(16)} to ${imemWaitAddr.toString(16)}")
                        processImemReq = false
                        imemTape = 0
                    } else {
                        imemTape += 1
                    }
                } else if (dut.io.nextLevelReq.req.peek().litToBoolean) {
                    imemWaitAddr = dut.io.nextLevelReq.paddr.peek().litValue
                    imemRespData = BreezeCoreSimSupport.buildCacheLine(memory, imemWaitAddr, cacheLineBytes)
                    processImemReq = true
                    imemTape = 0
                    println(s"[FE] receive imem req for ${imemWaitAddr.toString(16)}")
                }

                if (processDmemReq) {
                    if (dmemTape == dmemLatency) {
                        dut.io.dmem.rsp.valid.poke(true.B)
                        dut.io.dmem.rsp.data.poke(dmemRespData.U)
                        dut.io.dmem.rsp.isWriteAck.poke(dmemWaitIsWrite.B)
                        println(
                          s"[BE] respond ${if (dmemWaitIsWrite) "write" else "read"} " +
                            s"addr=${dmemWaitAddr.toString(16)} data=${dmemRespData.toString(16)}"
                        )
                        processDmemReq = false
                        dmemTape = 0
                    } else {
                        dmemTape += 1
                    }
                } else if (dut.io.dmem.req.valid.peek().litToBoolean) {
                    dmemWaitAddr = dut.io.dmem.req.addr.peek().litValue
                    dmemWaitIsWrite = dut.io.dmem.req.isWrite.peek().litToBoolean

                    if (dmemWaitIsWrite) {
                        val wdata = dut.io.dmem.req.wdata.peek().litValue
                        val wmask = dut.io.dmem.req.wmask.peek().litValue
                        dmemRespData = BreezeCoreSimSupport.write64(memory, dmemWaitAddr, wdata, wmask)
                        println(
                          s"[BE] receive dmem write addr=${dmemWaitAddr.toString(16)} " +
                            s"wdata=${wdata.toString(16)} wmask=${wmask.toString(16)}"
                        )
                    } else {
                        dmemRespData = BreezeCoreSimSupport.read64(memory, dmemWaitAddr)
                        println(s"[BE] receive dmem read addr=${dmemWaitAddr.toString(16)}")
                    }

                    processDmemReq = true
                    dmemTape = 0
                }

                dut.clock.step(1)
                cycleCount += 1
            }

            result = BreezeCoreSimResult(cycleCount = cycleCount, timedOut = cycleCount >= maxCycles)
        }

        result
    }
}

object BreezeCoreSimApp {
    def main(args: Array[String]): Unit = {
        require(args.length == 1, "usage: BreezeCoreSimApp <memory-json-path>")
        val memory = BreezeCoreSimMemoryLoader.loadMemoryMap(args(0))
        val result = BreezeCoreSimRunner.run(memory)
        println(s"[BreezeCoreSimApp] cycleCount=${result.cycleCount} timedOut=${result.timedOut}")
        require(!result.timedOut, "simulation timed out before estop")
    }
}
