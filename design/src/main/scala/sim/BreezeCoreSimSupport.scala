package flow.sim

import chisel3._
import chisel3.simulator.ChiselSim
import chisel3.simulator.PeekPokeAPI
import chisel3.testing.HasTestingDirectory
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import flow.config.{BreezeCoreConfig, BreezeCoreConfigs, PrivilegeProfile}
import flow.core.BreezeCore
import flow.fpu.BreezeFpSources
import svsim.{CommonCompilationSettings, CommonSettingsModifications}

import java.io.File
import scala.collection.mutable

final case class BreezeCoreSimResult(
    cycleCount: Int,
    timedOut: Boolean,
    exitCode: Option[BigInt] = None
)
final case class BreezeCoreSimulationConfig(
    bootAddr: BigInt,
    tandemLog: Boolean,
    exitAddress: Option[BigInt] = None,
    maxCycles: Int = 100000
)

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

    def encodeBranch(rs1: Int, rs2: Int, imm: Int, funct3: Int): BigInt = {
        require((imm & 1) == 0, s"branch immediate must be 2-byte aligned: $imm")
        require(imm >= -4096 && imm <= 4094, s"branch immediate out of range: $imm")
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

    private def loadRoot(path: String): JsonNode = {
        mapper.readTree(new File(path))
    }

    private def loadSimulationNode(path: String): JsonNode = {
        val root = loadRoot(path)
        Option(root.get("simulation")).getOrElse {
            throw new IllegalArgumentException(s"missing top-level simulation in $path")
        }
    }

    def loadMemoryMap(path: String): mutable.LinkedHashMap[BigInt, BigInt] = {
        val root = loadRoot(path)
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
        val simNode = loadSimulationNode(path)
        val bootAddrNode = Option(simNode.get("bootaddr")).getOrElse {
            throw new IllegalArgumentException(s"missing simulation.bootaddr in $path")
        }
        parseNodeValue(bootAddrNode)
    }

    def loadMtvec(path: String): BigInt = {
        val simNode = loadSimulationNode(path)
        val mtvecNode = Option(simNode.get("mtvec")).getOrElse {
            throw new IllegalArgumentException(s"missing simulation.mtvec in $path")
        }
        parseNodeValue(mtvecNode)
    }

    def loadTandemLog(path: String): Boolean = {
        val node = loadSimulationNode(path)
        Option(node.get("tandemLog")).map(_.asBoolean(false)).getOrElse(false)
    }

    def loadExitAddress(path: String): Option[BigInt] = {
        val node = loadSimulationNode(path)
        Option(node.get("exitAddress")).map(parseNodeValue)
    }

    def loadMaxCycles(path: String): Int = {
        val node = loadSimulationNode(path)
        val value = Option(node.get("maxCycles")).map(parseNodeValue).getOrElse(BigInt(100000))
        require(value > 0 && value <= Int.MaxValue, s"simulation.maxCycles out of range: $value")
        value.toInt
    }

    def loadSimulationConfig(path: String): BreezeCoreSimulationConfig = {
        BreezeCoreSimulationConfig(
          bootAddr = loadBootAddr(path),
          tandemLog = loadTandemLog(path),
          exitAddress = loadExitAddress(path),
          maxCycles = loadMaxCycles(path)
        )
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
    private object CoreSimulator extends ChiselSim

    private implicit val fpnewCompilationSettings: CommonSettingsModifications =
        (settings: CommonCompilationSettings) => {
            val include = BreezeFpSources.includeDir.toString
            val includes = settings.includeDirs.getOrElse(Seq.empty)
            settings.copy(includeDirs = Some((includes :+ include).distinct))
        }

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

    def runWithTandemTrace(
        memory: mutable.Map[BigInt, BigInt],
        coreCfg: BreezeCoreConfig,
        maxCycles: Int,
        imemLatency: Int,
        dmemLatency: Int
    ): BreezeCoreSimTandemResult = {
        runWithTandemTrace(
          memory = memory,
          coreCfg = coreCfg,
          maxCycles = maxCycles,
          imemLatency = imemLatency,
          dmemLatency = dmemLatency,
          bootAddr = 0,
          logMode = TandemLogMode.Off
        )
    }

    def run(
        memory: mutable.Map[BigInt, BigInt],
        coreCfg: BreezeCoreConfig = BreezeCoreConfig(useFASE = false, useGShare = true),
        maxCycles: Int = 100000,
        imemLatency: Int = 6,
        dmemLatency: Int = 7,
        bootAddr: BigInt = 0,
        exitAddress: Option[BigInt] = None
    ): BreezeCoreSimResult = {
        runInternal(
          memory = memory,
          coreCfg = coreCfg,
          maxCycles = maxCycles,
          imemLatency = imemLatency,
          dmemLatency = dmemLatency,
          bootAddr = bootAddr,
          collectTandemTrace = false,
          exitAddress = exitAddress
        ).result
    }

    def runWithTandemTrace(
        memory: mutable.Map[BigInt, BigInt],
        coreCfg: BreezeCoreConfig = BreezeCoreConfig(useFASE = false, enableTandem = true, useGShare = true),
        maxCycles: Int = 100000,
        imemLatency: Int = 6,
        dmemLatency: Int = 7,
        bootAddr: BigInt = 0,
        logMode: TandemLogMode = TandemLogMode.Off,
        machineSoftwareInterruptAt: Option[Int] = None,
        exitAddress: Option[BigInt] = None
    ): BreezeCoreSimTandemResult = {
        runInternal(
          memory = memory,
          coreCfg = coreCfg,
          maxCycles = maxCycles,
          imemLatency = imemLatency,
          dmemLatency = dmemLatency,
          bootAddr = bootAddr,
          collectTandemTrace = true,
          logMode = logMode,
          machineSoftwareInterruptAt = machineSoftwareInterruptAt,
          exitAddress = exitAddress
        )
    }

    private def runInternal(
        memory: mutable.Map[BigInt, BigInt],
        coreCfg: BreezeCoreConfig,
        maxCycles: Int,
        imemLatency: Int,
        dmemLatency: Int,
        bootAddr: BigInt,
        collectTandemTrace: Boolean,
        logMode: TandemLogMode = TandemLogMode.Off,
        machineSoftwareInterruptAt: Option[Int] = None,
        exitAddress: Option[BigInt] = None
    ): BreezeCoreSimTandemResult = {
        var result = BreezeCoreSimResult(cycleCount = 0, timedOut = false)
        val commitEvents = mutable.ArrayBuffer.empty[RawCommitEvent]

        implicit val temporary: HasTestingDirectory =
            HasTestingDirectory.temporary(deleteOnExit = true)
        CoreSimulator.simulateRaw(new BreezeCore(coreCfg, enabledebug = true)) { dut =>
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
            var exitCode: Option[BigInt] = None

            dut.io.resetAddr.poke(bootAddr.U)
            dut.io.machineTimerInterrupt.poke(false.B)
            dut.io.machineSoftwareInterrupt.poke(false.B)
            dut.io.externalInterrupts.poke(0.U)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0.U)
            dut.io.nextLevelRsp.error.poke(false.B)
            dut.io.dmem.rsp.valid.poke(false.B)
            dut.io.dmem.rsp.data.poke(0.U)
            dut.io.dmem.rsp.isWriteAck.poke(false.B)
            dut.io.dmem.rsp.error.poke(false.B)
            dut.io.dcacheFlushDone.poke(false.B)
            dut.io.dcacheHpm.controlRetired.poke(false.B)
            dut.io.dcacheHpm.controlTaken.poke(false.B)
            dut.io.dcacheHpm.predictionMiss.poke(false.B)
            dut.io.dcacheHpm.icacheAccess.poke(false.B)
            dut.io.dcacheHpm.icacheMiss.poke(false.B)
            dut.io.dcacheHpm.dcacheAccess.poke(false.B)
            dut.io.dcacheHpm.dcacheMiss.poke(false.B)
            dut.io.dcacheHpm.dcacheUncached.poke(false.B)
            dut.io.dcacheHpm.memStallCycle.poke(false.B)
            dut.io.dcacheHpm.loadUseStall.poke(false.B)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            while (!dut.io.estop.peek().litToBoolean && exitCode.isEmpty && cycleCount < maxCycles) {
                dut.io.machineSoftwareInterrupt.poke(
                    machineSoftwareInterruptAt.exists(cycleCount >= _).B)
                dut.io.nextLevelRsp.vld.poke(false.B)
                dut.io.nextLevelRsp.data.poke(0.U)
                dut.io.nextLevelRsp.error.poke(false.B)
                dut.io.dmem.rsp.valid.poke(false.B)
                dut.io.dmem.rsp.data.poke(0.U)
                dut.io.dmem.rsp.isWriteAck.poke(false.B)
                dut.io.dmem.rsp.error.poke(false.B)

                if (processImemReq) {
                    if (imemTape == imemLatency) {
                        dut.io.nextLevelRsp.vld.poke(true.B)
                        dut.io.nextLevelRsp.data.poke(imemRespData.U)
                        //println(s"[FE] refill ${imemRespData.toString(16)} to ${imemWaitAddr.toString(16)}")
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
                    //println(s"[FE] receive imem req for ${imemWaitAddr.toString(16)}")
                }

                if (processDmemReq) {
                    if (dmemTape == dmemLatency) {
                        dut.io.dmem.rsp.valid.poke(true.B)
                        dut.io.dmem.rsp.data.poke(dmemRespData.U)
                        dut.io.dmem.rsp.isWriteAck.poke(dmemWaitIsWrite.B)
                        if(false)println(
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
                        if(false)println(
                          s"[BE] receive dmem write addr=${dmemWaitAddr.toString(16)} " +
                            s"wdata=${wdata.toString(16)} wmask=${wmask.toString(16)}"
                        )
                    } else {
                        dmemRespData = BreezeCoreSimSupport.read64(memory, dmemWaitAddr)
                        if(false)println(s"[BE] receive dmem read addr=${dmemWaitAddr.toString(16)}")
                    }

                    processDmemReq = true
                    dmemTape = 0
                }

                dut.clock.step(1)
                cycleCount += 1

                if (collectTandemTrace) {
                    dut.io.tandem.foreach { tandem =>
                        if (tandem.valid.peek().litToBoolean) {
                            val event = RawCommitEvent(
                              valid = true,
                              pc = tandem.pc.peek().litValue,
                              inst = tandem.inst.peek().litValue,
                              nextPc = tandem.nextPc.peek().litValue,
                              estop = tandem.estop.peek().litToBoolean,
                              rdWriteEn = tandem.rdWriteEn.peek().litToBoolean,
                              rdAddr = tandem.rdAddr.peek().litValue.toInt,
                              rdData = tandem.rdData.peek().litValue,
                              memEn = tandem.memEn.peek().litToBoolean,
                              memIsWrite = tandem.memIsWrite.peek().litToBoolean,
                              memAddr = tandem.memAddr.peek().litValue,
                              memAlignedAddr = tandem.memAlignedAddr.peek().litValue,
                              memRData = tandem.memRData.peek().litValue,
                              memWData = tandem.memWData.peek().litValue,
                              memWMask = tandem.memWMask.peek().litValue
                            )
                            exitAddress.foreach { address =>
                                if (event.memEn && event.memIsWrite &&
                                    event.memAlignedAddr == BreezeCoreSimSupport.alignDown(address, 8)) {
                                    val shift = ((address & 0x7) * 8).toInt
                                    exitCode = Some(
                                      (event.memWData >> shift) & BreezeCoreSimSupport.Mask32)
                                }
                            }
                            commitEvents += event
                            if (logMode == TandemLogMode.RawCommit) {
                                println(RawCommitEventLogFormatter.format(cycleCount, event))
                            }
                        }
                    }
                }
            }

            result = BreezeCoreSimResult(
              cycleCount = cycleCount,
              timedOut = cycleCount >= maxCycles && exitCode.isEmpty,
              exitCode = exitCode
            )
        }

        BreezeCoreSimTandemResult(result = result, commitEvents = commitEvents.toSeq)
    }
}

object BreezeCoreSimApp {
    def buildCoreConfig(
        corePreset: String,
        enableTandem: Boolean,
        privilegeProfileName: String = "mcu"
    ): BreezeCoreConfig = {
        val privilegeProfile = PrivilegeProfile.fromName(privilegeProfileName)
        corePreset match {
            case "baseline" => BreezeCoreConfigs.baseline(enableTandem, privilegeProfile)
            case "gshare"   => BreezeCoreConfigs.gshare(enableTandem, privilegeProfile)
            case other =>
                throw new IllegalArgumentException(
                  s"unsupported core preset: $other (expected baseline or gshare)"
                )
        }
    }

    def main(args: Array[String]): Unit = {
        require(
          args.length == 2 || args.length == 3,
          "usage: BreezeCoreSimApp <memory-json-path> <baseline|gshare> [mcu|linux]"
        )
        val memory = BreezeCoreSimMemoryLoader.loadMemoryMap(args(0))
        val simCfg = BreezeCoreSimMemoryLoader.loadSimulationConfig(args(0))
        val privilegeProfileName = if (args.length == 3) args(2) else "mcu"
        val useTandem = simCfg.tandemLog || simCfg.exitAddress.nonEmpty
        val coreCfg = buildCoreConfig(
          args(1),
          enableTandem = useTandem,
          privilegeProfileName = privilegeProfileName
        )
        if (useTandem) {
            val tandemResult = BreezeCoreSimRunner.runWithTandemTrace(
              memory = memory,
              coreCfg = coreCfg,
              bootAddr = simCfg.bootAddr,
              maxCycles = simCfg.maxCycles,
              exitAddress = simCfg.exitAddress,
              logMode = if (simCfg.tandemLog) TandemLogMode.RawCommit else TandemLogMode.Off
            )
            val commitCount = tandemResult.commitEvents.length
            val ipc =
                if (tandemResult.result.cycleCount == 0) 0.0
                else commitCount.toDouble / tandemResult.result.cycleCount.toDouble
            val exitText = tandemResult.result.exitCode
                .map(code => s"0x${code.toString(16)}").getOrElse("none")
            println(
              f"[BreezeCoreSimApp] cycleCount=${tandemResult.result.cycleCount}%d " +
                f"commitCount=$commitCount%d ipc=$ipc%.3f " +
                s"timedOut=${tandemResult.result.timedOut} exitCode=$exitText"
            )
            validateResult(tandemResult.result, simCfg.exitAddress)
        } else {
            val result = BreezeCoreSimRunner.run(
              memory,
              coreCfg = coreCfg,
              bootAddr = simCfg.bootAddr,
              maxCycles = simCfg.maxCycles,
              exitAddress = simCfg.exitAddress
            )
            val exitText = result.exitCode
                .map(code => s"0x${code.toString(16)}").getOrElse("none")
            println(
              s"[BreezeCoreSimApp] cycleCount=${result.cycleCount} " +
                s"timedOut=${result.timedOut} exitCode=$exitText"
            )
            validateResult(result, simCfg.exitAddress)
        }
    }

    private[sim] def validateResult(
        result: BreezeCoreSimResult,
        exitAddress: Option[BigInt]
    ): Unit = {
        require(!result.timedOut, "simulation timed out before completion")
        exitAddress.foreach { _ =>
            val exitText = result.exitCode
                .map(code => s"0x${code.toString(16)}").getOrElse("none")
            require(result.exitCode.contains(BigInt(1)),
              s"architectural test failed with exit code $exitText")
        }
    }
}
