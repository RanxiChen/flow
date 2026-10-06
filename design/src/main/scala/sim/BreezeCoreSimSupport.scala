package flow.sim

import chisel3._
import chisel3.simulator.ChiselSim
import chisel3.simulator.PeekPokeAPI
import chisel3.testing.HasTestingDirectory
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import flow.config.{BreezeCoreConfig, BreezeCoreConfigs, PrivilegeProfile}
import flow.core.BreezeCore
import flow.interface.L1DRespKind
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
        val lateEvents = mutable.ArrayBuffer.empty[LateRegisterEvent]
        val pendingTrace = new PendingTrace

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
            var stopped = false

            // Direct L1DCoreIO behavioral memory, independent of the legacy
            // instruction PTW below. No cache/coherence/PMP/PMA is modeled.
            case class CpuMemory(addr: BigInt, op: Int, size: Int, signed: Boolean,
                flw: Boolean, data: BigInt, satp: BigInt, priv: Int, sum: Boolean,
                mxr: Boolean, delay: Int)
            var cpuS1: Option[CpuMemory] = None
            var cpuS2: Option[CpuMemory] = None
            var reservation: Option[(BigInt,Int)] = None
            def translate(q: CpuMemory): Either[Int, BigInt] = {
                val store = q.op == 1 || q.op == 3 || q.op == 4
                val pageCause = if (store) 15 else 13
                if (q.priv == 3 || (q.satp >> 60) != 8) Right(q.addr)
                else if ((q.addr >> 39) != (if (q.addr.testBit(38)) (BigInt(1) << 25)-1 else BigInt(0))) Left(pageCause)
                else {
                    var table = (q.satp & ((BigInt(1) << 44)-1)) << 12
                    var result: Either[Int, BigInt] = Left(pageCause)
                    var searching = true
                    for (level <- 2 to 0 by -1 if searching) {
                        val vpn = (q.addr >> (12+9*level)) & 511
                        val pte = BreezeCoreSimSupport.read64(memory, table + vpn*8)
                        val r = pte.testBit(1); val w = pte.testBit(2); val x = pte.testBit(3)
                        val ppn = (pte >> 10) & ((BigInt(1) << 44)-1)
                        if (!pte.testBit(0) || (!r && w) || (pte >> 54) != 0) searching = false
                        else if (r || x) {
                            val lowerBits = 9*level
                            val aligned = (ppn & ((BigInt(1) << lowerBits)-1)) == 0
                            val user = pte.testBit(4)
                            val permitted = (if (store) w && pte.testBit(7) else r || (q.mxr && x)) &&
                                pte.testBit(6) && (if (q.priv == 0) user else !user || q.sum)
                            if (aligned && permitted) {
                                val pageMask = (BigInt(1) << (12+lowerBits))-1
                                result = Right((ppn << 12) | (q.addr & pageMask))
                            }
                            searching = false
                        } else if (level == 0) searching = false
                        else table = ppn << 12
                    }
                    result
                }
            }

            dut.io.l1d.req.ready.poke(true.B); dut.io.l1d.s2Hold.poke(false.B)
            dut.io.l1d.resp.valid.poke(false.B); dut.io.l1d.resp.bits.kind.poke(L1DRespKind.Done)
            dut.io.l1d.resp.bits.data.poke(0.U); dut.io.l1d.resp.bits.excCause.poke(0.U)
            dut.io.l1d.resp.bits.tval.poke(0.U)
            dut.io.l1d.late.valid.poke(false.B); dut.io.l1d.late.bits.rd.idx.poke(0.U)
            dut.io.l1d.late.bits.rd.isFp.poke(false.B); dut.io.l1d.late.bits.data.poke(0.U)
            dut.io.l1d.late.bits.error.poke(false.B)
            dut.io.l1d.drained.poke(true.B); dut.io.l1d.mmioBusy.poke(false.B)
            dut.io.mmuIdle.poke(true.B)
            dut.io.supervisorExternalInterrupt.poke(false.B); dut.io.time.poke(0.U)
            dut.io.dcacheHpm.mulSourceStall.poke(false.B); dut.io.dcacheHpm.divSourceStall.poke(false.B)
            dut.io.dcacheHpm.wbPortConflict.poke(0.U)
            dut.io.resetAddr.poke(bootAddr.U)
            dut.io.machineTimerInterrupt.poke(false.B)
            dut.io.machineSoftwareInterrupt.poke(false.B)
            dut.io.externalInterrupts.poke(0.U)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0.U)
            dut.io.nextLevelRsp.error.poke(false.B)
            dut.io.dmem.rsp.valid.poke(false.B)
            dut.io.dcacheArrayReq.foreach(_.ready.poke(false.B)) // scalar memory model has no L1 arrays
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

            while (!stopped && exitCode.isEmpty && cycleCount < maxCycles) {
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

                if (dut.io.l1d.trapClearRsv.peek().litToBoolean) reservation = None
                val cpuHold = cpuS2.exists(_.delay > 0)
                dut.io.l1d.s2Hold.poke(cpuHold.B)
                dut.io.l1d.req.ready.poke((!cpuHold).B)
                dut.io.l1d.resp.valid.poke((cpuS2.nonEmpty && !cpuHold).B)
                dut.io.l1d.resp.bits.kind.poke(L1DRespKind.Done)
                dut.io.l1d.resp.bits.data.poke(0.U)
                cpuS2.filter(_ => !cpuHold).foreach { q =>
                    val bytes = 1 << q.size
                    val load = q.op == 0 || q.op == 2
                    val translated = if ((q.addr & (bytes-1)) != 0)
                        Left(if (load) 4 else 6) else translate(q)
                    translated match {
                        case Left(cause) =>
                            dut.io.l1d.resp.bits.kind.poke(L1DRespKind.Exc)
                            dut.io.l1d.resp.bits.excCause.poke(cause.U)
                            dut.io.l1d.resp.bits.tval.poke(q.addr.U)
                        case Right(pa) =>
                            val offset = (pa & 7).toInt
                            val mask = (BigInt(1) << (bytes*8))-1
                            val raw = (BreezeCoreSimSupport.read64(memory, pa) >> (8*offset)) & mask
                            val value = if (q.flw) (BigInt("ffffffff",16) << 32) | raw
                                else if (q.signed && raw.testBit(bytes*8-1)) raw | (BreezeCoreSimSupport.Mask64 ^ mask)
                                else raw
                            val scSuccess = q.op == 3 && reservation.contains(pa -> bytes)
                            dut.io.l1d.resp.bits.data.poke((if (q.op == 3) (if (scSuccess) BigInt(0) else BigInt(1)) else value).U)
                            if (!dut.io.l1d.s2Kill.peek().litToBoolean) {
                                if (q.op == 2) reservation = Some(pa -> bytes)
                                else if (q.op == 3 || q.op == 1) reservation = None
                            }
                            if ((q.op == 1 || scSuccess) && !dut.io.l1d.s2Kill.peek().litToBoolean)
                                BreezeCoreSimSupport.write64(memory, pa, q.data << (8*offset),
                                    ((BigInt(1) << bytes)-1) << offset)
                            require(q.op != 4,
                                "scalar core runner does not model AMO; use native protocol tests")
                    }
                }
                val acceptedCpu = if (dut.io.l1d.req.valid.peek().litToBoolean && !cpuHold) {
                    val q = dut.io.l1d.req.bits; val c = dut.io.l1d.csr
                    val priv = (if (c.mprv.peek().litToBoolean) c.mpp else c.privilege).peek().litValue.toInt
                    Some(CpuMemory(q.vaddr.peek().litValue, q.op.peek().litValue.toInt,
                        q.size.peek().litValue.toInt, q.signed.peek().litToBoolean,
                        q.isFlw.peek().litToBoolean, q.wdata.peek().litValue,
                        c.satp.peek().litValue, priv, c.sum.peek().litToBoolean,
                        c.mxr.peek().litToBoolean, dmemLatency))
                } else None
                val killCpu = dut.io.l1d.s2Kill.peek().litToBoolean
                val killS1 = dut.io.l1d.s1Kill.peek().litToBoolean
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
                              memWMask = tandem.memWMask.peek().litValue,
                              rdPending = tandem.rdPending.peek().litToBoolean,
                              rdIsFp = tandem.rdIsFp.peek().litToBoolean
                            )
                            exitAddress.foreach { address =>
                                if (event.memEn && event.memIsWrite &&
                                    event.memAlignedAddr == BreezeCoreSimSupport.alignDown(address, 8)) {
                                    val shift = ((address & 0x7) * 8).toInt
                                    exitCode = Some(
                                      (event.memWData >> shift) & BreezeCoreSimSupport.Mask32)
                                }
                            }
                            pendingTrace.commit(event)
                            commitEvents += event
                            if (logMode == TandemLogMode.RawCommit) {
                                println(RawCommitEventLogFormatter.format(cycleCount, event))
                            }
                        }
                        if (tandem.lateWriteValid.peek().litToBoolean) {
                            val late = LateRegisterEvent(cycleCount, tandem.lateWriteIsFp.peek().litToBoolean,
                                tandem.lateWriteRd.peek().litValue.toInt, tandem.lateWriteData.peek().litValue,
                                tandem.lateWriteError.peek().litToBoolean)
                            pendingTrace.complete(late)
                            lateEvents += late
                            if (logMode == TandemLogMode.RawCommit) println(s"[LATE] $late")
                        }
                    }
                }
                val stopAtEdge = dut.io.estop.peek().litToBoolean
                dut.clock.step(1)
                stopped = stopAtEdge
                if (killCpu) { cpuS1 = None; cpuS2 = None }
                else if (cpuHold) cpuS2 = cpuS2.map(q => q.copy(delay = q.delay-1))
                else { cpuS2 = if (killS1) None else cpuS1; cpuS1 = acceptedCpu }
                cycleCount += 1

            }
            if (collectTandemTrace) pendingTrace.finish()

            result = BreezeCoreSimResult(
              cycleCount = cycleCount,
              timedOut = cycleCount >= maxCycles && exitCode.isEmpty,
              exitCode = exitCode
            )
        }

        BreezeCoreSimTandemResult(result = result, commitEvents = commitEvents.toSeq, lateEvents = lateEvents.toSeq)
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
