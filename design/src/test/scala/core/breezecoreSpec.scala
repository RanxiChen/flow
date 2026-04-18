package flow.core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.BreezeCoreConfig
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.collection.mutable

class BreezeCoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
    private val nopInst = BigInt("00000013", 16)
    private val mask64 = (BigInt(1) << 64) - 1

    private def encodeAddi(rd: Int, rs1: Int, imm: Int): BigInt = {
        val imm12 = imm & 0xfff
        (BigInt(imm12) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(0) << 12) |
        (BigInt(rd) << 7) |
        BigInt(0x13)
    }

    private case class AddiTrace(rd: Int, imm: Int, inst: BigInt)
    private case class LoadCase(name: String, funct3: Int, offset: Int, rspData: BigInt, expectedWb: BigInt)
    private case class StoreCase(name: String, funct3: Int, offset: Int, rs2Value: Int, expectedWdata: BigInt, expectedWmask: BigInt)
    private case class RTypeCase(
        name: String,
        rs1Value: Int,
        rs2Value: Int,
        funct3: Int,
        funct7: Int,
        expectedAluOut: BigInt
    )
    private case class DependencyChainCase(name: String, instructions: Seq[BigInt], expectedWb: Seq[BigInt])
    private case class ObservedDmemReq(addr: BigInt, isWrite: Boolean, wdata: BigInt, wmask: BigInt)
    private case class PendingDmemResp(addr: BigInt, data: BigInt, isWriteAck: Boolean, cyclesLeft: Int)

    private def u64(value: BigInt): BigInt = value & mask64

    private def encodeLoad(rd: Int, rs1: Int, imm: Int, funct3: Int): BigInt = {
        val imm12 = imm & 0xfff
        (BigInt(imm12) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(funct3) << 12) |
        (BigInt(rd) << 7) |
        BigInt(0x03)
    }

    private def encodeRType(rd: Int, rs1: Int, rs2: Int, funct3: Int, funct7: Int): BigInt = {
        (BigInt(funct7) << 25) |
        (BigInt(rs2) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(funct3) << 12) |
        (BigInt(rd) << 7) |
        BigInt(0x33)
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

    private def initCore(dut: BreezeCore): Unit = {
        val fase = dut.io.fase.get
        dut.io.resetAddr.poke(0.U)
        dut.io.nextLevelRsp.vld.poke(false.B)
        dut.io.nextLevelRsp.data.poke(0.U)
        dut.io.dmem.rsp.valid.poke(false.B)
        dut.io.dmem.rsp.data.poke(0.U)
        dut.io.dmem.rsp.isWriteAck.poke(false.B)
        fase.inst_valid.poke(false.B)
        fase.instruction.poke(0.U)

        dut.reset.poke(true.B)
        dut.clock.step(1)
        dut.reset.poke(false.B)
    }

    private def stepUntil(
        dut: BreezeCore,
        maxCycles: Int = 32
    )(cond: => Boolean): Unit = {
        var cycles = 0
        while (!cond && cycles < maxCycles) {
            dut.clock.step(1)
            cycles += 1
        }
        assert(cond, s"condition not met within $maxCycles cycles")
    }

    private def enqueueFaseInstruction(
        dut: BreezeCore,
        inst: BigInt
    ): Unit = {
        val fase = dut.io.fase.get
        stepUntil(dut) { fase.inst_ready.peek().litToBoolean }
        fase.inst_valid.poke(true.B)
        fase.instruction.poke(inst.U)
        dut.clock.step(1)
        fase.inst_valid.poke(false.B)
    }

    private def waitForWbData(
        dut: BreezeCore,
        expected: BigInt,
        maxCycles: Int = 32
    ): Unit = {
        val debug = dut.io.debug.get
        stepUntil(dut, maxCycles) {
            debug.memWbValid.peek().litToBoolean && debug.wbData.peek().litValue == expected
        }
    }

    private def enqueueNopsUntilWbObserved(
        dut: BreezeCore,
        nopCount: Int,
        expectedWb: BigInt
    ): Unit = {
        val debug = dut.io.debug.get
        var seenExpectedWb = false

        for (_ <- 0 until nopCount) {
            enqueueFaseInstruction(dut, nopInst)
            seenExpectedWb ||= debug.memWbValid.peek().litToBoolean && debug.wbData.peek().litValue == expectedWb
        }

        if (!seenExpectedWb) {
            waitForWbData(dut, expectedWb)
        }
    }

    private def stepWithFakeDrivers(
        dut: BreezeCore,
        instQueue: mutable.Queue[BigInt],
        pendingDmemResps: mutable.Queue[PendingDmemResp],
        observedReqs: mutable.ArrayBuffer[ObservedDmemReq]
    )(
        mkResponse: ObservedDmemReq => PendingDmemResp
    ): Unit = {
        val fase = dut.io.fase.get
        val dmemReq = dut.io.dmem.req
        val dmemRsp = dut.io.dmem.rsp
        val driveRespThisCycle = pendingDmemResps.headOption.exists(_.cyclesLeft == 0)

        if (instQueue.nonEmpty && fase.inst_ready.peek().litToBoolean) {
            fase.inst_valid.poke(true.B)
            fase.instruction.poke(instQueue.dequeue().U)
        } else {
            fase.inst_valid.poke(false.B)
            fase.instruction.poke(0.U)
        }

        pendingDmemResps.headOption match {
            case Some(resp) if driveRespThisCycle =>
                dmemRsp.valid.poke(true.B)
                dmemRsp.data.poke(resp.data.U)
                dmemRsp.isWriteAck.poke(resp.isWriteAck.B)
            case _ =>
                dmemRsp.valid.poke(false.B)
                dmemRsp.data.poke(0.U)
                dmemRsp.isWriteAck.poke(false.B)
        }

        if (dmemReq.valid.peek().litToBoolean) {
            val observedReq = ObservedDmemReq(
                addr = dmemReq.addr.peek().litValue,
                isWrite = dmemReq.isWrite.peek().litToBoolean,
                wdata = dmemReq.wdata.peek().litValue,
                wmask = dmemReq.wmask.peek().litValue
            )
            observedReqs += observedReq
            pendingDmemResps.enqueue(mkResponse(observedReq))
        }

        dut.clock.step(1)

        fase.inst_valid.poke(false.B)
        fase.instruction.poke(0.U)
        dmemRsp.valid.poke(false.B)
        dmemRsp.data.poke(0.U)
        dmemRsp.isWriteAck.poke(false.B)

        if (driveRespThisCycle) {
            pendingDmemResps.dequeue()
        }
        val decremented = pendingDmemResps.map { resp =>
            resp.copy(cyclesLeft = math.max(resp.cyclesLeft - 1, 0))
        }
        pendingDmemResps.clear()
        pendingDmemResps ++= decremented
    }

    "BreezeCore should retire a single addi instruction from FASE input" in {
        simulate(new BreezeCore(BreezeCoreConfig(useFASE = true), enabledebug = true)) { dut =>
            val debug = dut.io.debug.get
            val fase = dut.io.fase.get
            val addiX1 = encodeAddi(rd = 1, rs1 = 0, imm = 1)

            initCore(dut)

            enqueueFaseInstruction(dut, addiX1)

            stepUntil(dut) { debug.decodeValid.peek().litToBoolean }
            debug.decodeInst.expect(addiX1.U)
            debug.decodePc.expect(0.U)

            dut.clock.step(1)
            debug.idExeValid.expect(true.B)
            debug.idExeRs1Addr.expect(0.U)
            debug.idExeRs2Addr.expect(1.U)
            debug.idExeSrc1.expect(0.U)
            debug.idExeSrc2.expect(1.U)
            debug.exeSrc1.expect(0.U)
            debug.exeSrc2.expect(1.U)
            debug.exeAluOut.expect(1.U)

            dut.clock.step(1)
            debug.exeMemValid.expect(true.B)
            debug.exeMemRdAddr.expect(1.U)
            debug.exeMemData.expect(1.U)

            dut.clock.step(1)
            debug.memWbValid.expect(true.B)
            debug.wbData.expect(1.U)
        }
    }

    "BreezeCore should pipeline consecutive addi instructions from FASE input" in {
        simulate(new BreezeCore(BreezeCoreConfig(useFASE = true), enabledebug = true)) { dut =>
            val debug = dut.io.debug.get
            val fase = dut.io.fase.get
            val traces = (1 to 4).map(i => AddiTrace(rd = i, imm = i, inst = encodeAddi(rd = i, rs1 = 0, imm = i)))

            initCore(dut)

            enqueueFaseInstruction(dut, traces.head.inst)

            stepUntil(dut, maxCycles = 32) {
                debug.decodeValid.peek().litToBoolean && debug.decodeInst.peek().litValue == traces.head.inst
            }

            val totalCycles = traces.length + 3
            for (cycle <- 0 until totalCycles) {
                val decodeIdx = cycle
                val exeIdx = cycle - 1
                val exeMemIdx = cycle - 2
                val wbIdx = cycle - 3

                if (decodeIdx >= 0 && decodeIdx < traces.length) {
                    val trace = traces(decodeIdx)
                    debug.decodeValid.expect(true.B)
                    debug.decodeInst.expect(trace.inst.U)
                    debug.decodePc.expect(0.U)
                } else {
                    debug.decodeValid.expect(false.B)
                }

                if (exeIdx >= 0 && exeIdx < traces.length) {
                    val trace = traces(exeIdx)
                    debug.idExeValid.expect(true.B)
                    debug.exeSrc1.expect(0.U)
                    debug.exeSrc2.expect(trace.imm.U)
                    debug.exeAluOut.expect(trace.imm.U)
                } else {
                    debug.idExeValid.expect(false.B)
                }

                if (exeMemIdx >= 0 && exeMemIdx < traces.length) {
                    val trace = traces(exeMemIdx)
                    debug.exeMemValid.expect(true.B)
                    debug.exeMemRdAddr.expect(trace.rd.U)
                    debug.exeMemData.expect(trace.imm.U)
                } else {
                    debug.exeMemValid.expect(false.B)
                }

                if (wbIdx >= 0 && wbIdx < traces.length) {
                    val trace = traces(wbIdx)
                    debug.memWbValid.expect(true.B)
                    debug.wbData.expect(trace.imm.U)
                } else {
                    debug.memWbValid.expect(false.B)
                }

                val nextTraceIdx = cycle + 1
                if (cycle != totalCycles - 1) {
                    if (nextTraceIdx < traces.length) {
                        fase.inst_valid.poke(true.B)
                        fase.instruction.poke(traces(nextTraceIdx).inst.U)
                    } else {
                        fase.inst_valid.poke(false.B)
                    }
                    dut.clock.step(1)
                }
            }
        }
    }

    "BreezeCore should execute RV64I R-type ALU instructions through the pipeline" in {
        val rTypeCases = Seq(
            RTypeCase("add-basic", 3, 5, funct3 = 0, funct7 = 0x00, expectedAluOut = 8),
            RTypeCase("add-cancel", -1, 1, funct3 = 0, funct7 = 0x00, expectedAluOut = 0),
            RTypeCase("sub-basic", 9, 4, funct3 = 0, funct7 = 0x20, expectedAluOut = 5),
            RTypeCase("sub-negative", 1, 2, funct3 = 0, funct7 = 0x20, expectedAluOut = -1),
            RTypeCase("and-mask", 0x55, 0x0f, funct3 = 7, funct7 = 0x00, expectedAluOut = 0x05),
            RTypeCase("and-preserve", -1, 0x12, funct3 = 7, funct7 = 0x00, expectedAluOut = 0x12),
            RTypeCase("or-mask", 0x50, 0x0f, funct3 = 6, funct7 = 0x00, expectedAluOut = 0x5f),
            RTypeCase("or-all-ones", 0, -1, funct3 = 6, funct7 = 0x00, expectedAluOut = -1),
            RTypeCase("xor-mask", 0x5a, 0x0f, funct3 = 4, funct7 = 0x00, expectedAluOut = 0x55),
            RTypeCase("xor-clear", -1, -1, funct3 = 4, funct7 = 0x00, expectedAluOut = 0),
            RTypeCase("sll-small", 1, 3, funct3 = 1, funct7 = 0x00, expectedAluOut = 8),
            RTypeCase("sll-large", 1, 8, funct3 = 1, funct7 = 0x00, expectedAluOut = 0x100),
            RTypeCase("srl-basic", 0x80, 3, funct3 = 5, funct7 = 0x00, expectedAluOut = 0x10),
            RTypeCase("srl-unsigned", -1, 4, funct3 = 5, funct7 = 0x00, expectedAluOut = BigInt("0fffffffffffffff", 16)),
            RTypeCase("sra-negative", -16, 2, funct3 = 5, funct7 = 0x20, expectedAluOut = -4),
            RTypeCase("sra-all-ones", -1, 8, funct3 = 5, funct7 = 0x20, expectedAluOut = -1),
            RTypeCase("slt-signed-true", -1, 1, funct3 = 2, funct7 = 0x00, expectedAluOut = 1),
            RTypeCase("slt-signed-false", 5, 5, funct3 = 2, funct7 = 0x00, expectedAluOut = 0),
            RTypeCase("sltu-false", -1, 1, funct3 = 3, funct7 = 0x00, expectedAluOut = 0),
            RTypeCase("sltu-true", 1, -1, funct3 = 3, funct7 = 0x00, expectedAluOut = 1)
        )

        rTypeCases.foreach { testCase =>
            withClue(s"R-type case ${testCase.name}: ") {
                simulate(new BreezeCore(BreezeCoreConfig(useFASE = true), enabledebug = true)) { dut =>
                    val debug = dut.io.debug.get
                    val rd = 3
                    val targetInst = encodeRType(rd, rs1 = 1, rs2 = 2, testCase.funct3, testCase.funct7)
                    val instQueue = mutable.Queue[BigInt](
                        encodeAddi(rd = 1, rs1 = 0, imm = testCase.rs1Value),
                        encodeAddi(rd = 2, rs1 = 0, imm = testCase.rs2Value),
                        nopInst,
                        nopInst,
                        nopInst,
                        nopInst,
                        targetInst
                    )
                    val pendingDmemResps = mutable.Queue.empty[PendingDmemResp]
                    val observedReqs = mutable.ArrayBuffer.empty[ObservedDmemReq]
                    val expectedRs1 = u64(BigInt(testCase.rs1Value))
                    val expectedRs2 = u64(BigInt(testCase.rs2Value))
                    val expectedAluOut = u64(testCase.expectedAluOut)
                    var seenTargetDecode = false

                    initCore(dut)

                    for (_ <- 0 until 64 if !seenTargetDecode) {
                        seenTargetDecode ||= debug.decodeValid.peek().litToBoolean &&
                            debug.decodeInst.peek().litValue == targetInst
                        if (!seenTargetDecode) {
                            stepWithFakeDrivers(dut, instQueue, pendingDmemResps, observedReqs) { req =>
                                throw new AssertionError(
                                    s"unexpected dmem req in R-type ALU test: addr=0x${req.addr.toString(16)}"
                                )
                            }
                        }
                    }
                    seenTargetDecode mustBe true
                    debug.decodeInst.expect(targetInst.U)
                    debug.decodePc.expect(0.U)

                    dut.clock.step(1)
                    debug.idExeValid.expect(true.B)
                    debug.idExeRs1Addr.expect(1.U)
                    debug.idExeRs2Addr.expect(2.U)
                    debug.exeSrc1.expect(expectedRs1.U)
                    debug.exeSrc2.expect(expectedRs2.U)
                    debug.exeAluOut.expect(expectedAluOut.U)

                    dut.clock.step(1)
                    debug.exeMemValid.expect(true.B)
                    debug.exeMemRdAddr.expect(rd.U)
                    debug.exeMemData.expect(expectedAluOut.U)

                    dut.clock.step(1)
                    debug.memWbValid.expect(true.B)
                    debug.wbData.expect(expectedAluOut.U)

                    observedReqs mustBe empty
                }
            }
        }
    }

    "BreezeCore should retire dependent add/sub chains without pipeline stalls after first writeback" in {
        val dependencyCases = Seq(
            DependencyChainCase(
                name = "add-chain",
                instructions = Seq(
                    encodeAddi(rd = 1, rs1 = 0, imm = 1),
                    encodeRType(rd = 2, rs1 = 1, rs2 = 1, funct3 = 0, funct7 = 0x00),
                    encodeRType(rd = 3, rs1 = 2, rs2 = 1, funct3 = 0, funct7 = 0x00),
                    encodeRType(rd = 4, rs1 = 3, rs2 = 2, funct3 = 0, funct7 = 0x00)
                ),
                expectedWb = Seq(1, 2, 3, 5).map(BigInt(_))
            ),
            DependencyChainCase(
                name = "add-sub-chain",
                instructions = Seq(
                    encodeAddi(rd = 1, rs1 = 0, imm = 10),
                    encodeAddi(rd = 2, rs1 = 0, imm = 3),
                    encodeRType(rd = 3, rs1 = 1, rs2 = 2, funct3 = 0, funct7 = 0x20),
                    encodeRType(rd = 4, rs1 = 3, rs2 = 2, funct3 = 0, funct7 = 0x00),
                    encodeRType(rd = 5, rs1 = 4, rs2 = 1, funct3 = 0, funct7 = 0x20)
                ),
                expectedWb = Seq(10, 3, 7, 10, 0).map(BigInt(_))
            )
        )

        dependencyCases.foreach { testCase =>
            withClue(s"dependency chain ${testCase.name}: ") {
                simulate(new BreezeCore(BreezeCoreConfig(useFASE = true), enabledebug = true)) { dut =>
                    val debug = dut.io.debug.get
                    val instQueue = mutable.Queue.from(testCase.instructions)
                    val pendingDmemResps = mutable.Queue.empty[PendingDmemResp]
                    val observedReqs = mutable.ArrayBuffer.empty[ObservedDmemReq]
                    val observedHazards = mutable.ArrayBuffer.empty[Boolean]
                    val observedMemWaits = mutable.ArrayBuffer.empty[Boolean]
                    val observedRetire = mutable.ArrayBuffer.empty[(Int, BigInt)]
                    val expectedWb = testCase.expectedWb.map(u64)
                    var cycle = 0

                    initCore(dut)

                    while (cycle < 96 && observedRetire.length < expectedWb.length) {
                        observedHazards += debug.loadUseHazard.peek().litToBoolean
                        observedMemWaits += debug.memWaitingResp.peek().litToBoolean
                        if (debug.memWbValid.peek().litToBoolean) {
                            observedRetire += ((cycle, debug.wbData.peek().litValue))
                        }
                        if (observedRetire.length < expectedWb.length) {
                            stepWithFakeDrivers(dut, instQueue, pendingDmemResps, observedReqs) { req =>
                                throw new AssertionError(
                                    s"unexpected dmem req in dependency chain test: addr=0x${req.addr.toString(16)}"
                                )
                            }
                            cycle += 1
                        }
                    }

                    observedReqs mustBe empty
                    observedRetire.length mustBe expectedWb.length
                    observedRetire.map(_._2) mustBe expectedWb

                    val retireCycles = observedRetire.map(_._1)
                    retireCycles.sliding(2).foreach { pair =>
                        pair(1) - pair(0) mustBe 1
                    }

                    observedHazards.exists(identity) mustBe false
                    observedMemWaits.exists(identity) mustBe false
                }
            }
        }
    }

    "BreezeCore should execute supported RV64I load instructions through dmem" in {
        val loadCases = Seq(
            LoadCase("lb", 0, 1, BigInt("00000000000080ff", 16), BigInt("ffffffffffffff80", 16)),
            LoadCase("lbu", 4, 2, BigInt("0000000000aa0000", 16), BigInt("aa", 16)),
            LoadCase("lh", 1, 2, BigInt("0000000080010000", 16), BigInt("ffffffffffff8001", 16)),
            LoadCase("lhu", 5, 4, BigInt("0000123400000000", 16), BigInt("1234", 16)),
            LoadCase("lw", 2, 4, BigInt("8000000100000000", 16), BigInt("ffffffff80000001", 16))
        )

        loadCases.foreach { testCase =>
            withClue(s"load case ${testCase.name}: ") {
                simulate(new BreezeCore(BreezeCoreConfig(useFASE = true), enabledebug = true)) { dut =>
                    val debug = dut.io.debug.get
                    val instQueue = mutable.Queue[BigInt](
                        encodeAddi(rd = 1, rs1 = 0, imm = 0x20),
                        nopInst,
                        nopInst,
                        nopInst,
                        nopInst,
                        encodeLoad(rd = 3, rs1 = 1, imm = testCase.offset, funct3 = testCase.funct3)
                    )
                    val pendingDmemResps = mutable.Queue.empty[PendingDmemResp]
                    val observedReqs = mutable.ArrayBuffer.empty[ObservedDmemReq]
                    var seenExpectedWb = false

                    initCore(dut)

                    for (_ <- 0 until 64 if !seenExpectedWb) {
                        if (dut.io.dmem.req.valid.peek().litToBoolean) {
                            dut.io.dmem.req.isWrite.expect(false.B)
                            dut.io.dmem.req.addr.expect(0x20.U)
                        }
                        seenExpectedWb ||= debug.memWbValid.peek().litToBoolean &&
                            debug.wbData.peek().litValue == testCase.expectedWb
                        if (!seenExpectedWb) {
                            stepWithFakeDrivers(dut, instQueue, pendingDmemResps, observedReqs) { req =>
                                if (req.addr != BigInt(0x20) || req.isWrite) {
                                    throw new AssertionError(
                                        s"unexpected load req: addr=0x${req.addr.toString(16)} isWrite=${req.isWrite}"
                                    )
                                }
                                PendingDmemResp(req.addr, testCase.rspData, isWriteAck = false, cyclesLeft = 6)
                            }
                        }
                    }

                    withClue(s"observed reqs in ${testCase.name}: ") {
                        observedReqs.count(_.addr == BigInt(0x20)) mustBe 1
                    }
                    withClue(s"waiting for memWbValid in ${testCase.name}: ") {
                        seenExpectedWb mustBe true
                    }
                }
            }
        }
    }

    "BreezeCore should execute supported RV64I store instructions through dmem" in {
        val storeCases = Seq(
            StoreCase("sb", 0, 1, 0x0ab, BigInt("abababababababab", 16), BigInt("02", 16)),
            StoreCase("sh", 1, 2, 0x123, BigInt("0123012301230123", 16), BigInt("0c", 16)),
            StoreCase("sw", 2, 4, 0x456, BigInt("0000045600000456", 16), BigInt("f0", 16))
        )

        storeCases.foreach { testCase =>
            simulate(new BreezeCore(BreezeCoreConfig(useFASE = true), enabledebug = true)) { dut =>
                val debug = dut.io.debug.get
                val instQueue = mutable.Queue[BigInt](
                    encodeAddi(rd = 1, rs1 = 0, imm = 0x20),
                    encodeAddi(rd = 2, rs1 = 0, imm = testCase.rs2Value),
                    nopInst,
                    nopInst,
                    nopInst,
                    nopInst,
                    encodeStore(rs1 = 1, rs2 = 2, imm = testCase.offset, funct3 = testCase.funct3)
                )
                val pendingDmemResps = mutable.Queue.empty[PendingDmemResp]
                val observedReqs = mutable.ArrayBuffer.empty[ObservedDmemReq]
                var seenStoreAck = false

                initCore(dut)

                for (_ <- 0 until 64 if !seenStoreAck) {
                    seenStoreAck ||= observedReqs.nonEmpty && !debug.memWaitingResp.peek().litToBoolean
                    if (!seenStoreAck) {
                        stepWithFakeDrivers(dut, instQueue, pendingDmemResps, observedReqs) { req =>
                            if (!req.isWrite || req.addr != BigInt(0x20)) {
                                throw new AssertionError(
                                    s"unexpected store req: addr=0x${req.addr.toString(16)} isWrite=${req.isWrite}"
                                )
                            }
                            PendingDmemResp(req.addr, data = 0, isWriteAck = true, cyclesLeft = 6)
                        }
                    }
                }

                observedReqs.length mustBe 1
                observedReqs.head.isWrite mustBe true
                observedReqs.head.addr mustBe BigInt(0x20)
                observedReqs.head.wdata mustBe testCase.expectedWdata
                observedReqs.head.wmask mustBe testCase.expectedWmask
                seenStoreAck mustBe true
            }
        }
    }
}

class BreezeCoreNoFASESpec extends AnyFreeSpec with Matchers with ChiselSim {
    private val mask64 = (BigInt(1) << 64) - 1
    private val nopInst = BigInt("00000013", 16)
    private case class PendingIcacheResp(paddr: BigInt, data: BigInt, cyclesLeft: Int)

    private def encodeAddi(rd: Int, rs1: Int, imm: Int): BigInt = {
        val imm12 = imm & 0xfff
        (BigInt(imm12) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(0) << 12) |
        (BigInt(rd) << 7) |
        BigInt(0x13)
    }

    private def encodeBranch(rs1: Int, rs2: Int, imm: Int, funct3: Int): BigInt = {
        val imm13 = imm & 0x1fff
        val bit12 = (imm13 >> 12) & 0x1
        val bits10To5 = (imm13 >> 5) & 0x3f
        val bits4To1 = (imm13 >> 1) & 0xf
        val bit11 = (imm13 >> 11) & 0x1

        (BigInt(bit12) << 31) |
        (BigInt(bits10To5) << 25) |
        (BigInt(rs2) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(funct3) << 12) |
        (BigInt(bits4To1) << 8) |
        (BigInt(bit11) << 7) |
        BigInt(0x63)
    }

    private def buildRefillLine(words: Seq[BigInt]): BigInt = {
        words.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (word, idx)) =>
            acc | ((word & BigInt("ffffffff", 16)) << (idx * 32))
        }
    }

    private def stepUntil(
        dut: BreezeCore,
        maxCycles: Int = 64
    )(cond: => Boolean): Unit = {
        var cycles = 0
        while (!cond && cycles < maxCycles) {
            dut.clock.step(1)
            cycles += 1
        }
        assert(cond, s"condition not met within $maxCycles cycles")
    }

    private def logCoreTimeline(dut: BreezeCore, cycle: Int, tag: String): Unit = {
        val frontendDebug = dut.io.frontendDebug.get
        val backendDebug = dut.io.debug.get
        println(
          f"[NoFASE][$tag] cycle=$cycle%02d " +
            f"req=${dut.io.nextLevelReq.req.peek().litToBoolean} " +
            f"paddr=0x${dut.io.nextLevelReq.paddr.peek().litValue}%x " +
            f"rsp=${dut.io.nextLevelRsp.vld.peek().litToBoolean} " +
            f"| s1(pc=0x${frontendDebug.s1_pcReg.peek().litValue}%x,v=${frontendDebug.s1_valid.peek().litToBoolean},fire=${frontendDebug.dreq_fire.peek().litToBoolean}) " +
            f"s2(pc=0x${frontendDebug.s2_pcReg.peek().litValue}%x,v=${frontendDebug.s2_valid.peek().litToBoolean},resp=${frontendDebug.s2_respValid.peek().litToBoolean}) " +
            f"s3(pc=0x${frontendDebug.s3_pcReg.peek().litValue}%x,v=${frontendDebug.s3_valid.peek().litToBoolean}) " +
            f"| decode(v=${backendDebug.decodeValid.peek().litToBoolean},pc=0x${backendDebug.decodePc.peek().litValue}%x) " +
            f"idExe(v=${backendDebug.idExeValid.peek().litToBoolean}) " +
            f"exeMem(v=${backendDebug.exeMemValid.peek().litToBoolean}) " +
            f"memWb(v=${backendDebug.memWbValid.peek().litToBoolean},wb=0x${backendDebug.wbData.peek().litValue}%x)"
        )
    }

    "BreezeCore should stall in frontend s2 on a single icache miss and then flow without extra bubbles" in {
        simulate(new BreezeCore(BreezeCoreConfig(useFASE = false), enabledebug = true)) { dut =>
            val frontendDebug = dut.io.frontendDebug.get
            val backendDebug = dut.io.debug.get
            val refillDelayCycles = 6
            val refillInstWords = Seq.tabulate(8)(i => encodeAddi(rd = i + 1, rs1 = 0, imm = i + 1))
            val refillLine = buildRefillLine(refillInstWords)
            var cycle = 0

            dut.io.resetAddr.poke(0.U)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0.U)
            dut.io.dmem.rsp.valid.poke(false.B)
            dut.io.dmem.rsp.data.poke(0.U)
            dut.io.dmem.rsp.isWriteAck.poke(false.B)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            logCoreTimeline(dut, cycle, "after-reset")
            cycle += 1

            frontendDebug.s1_pcReg.expect(0.U)
            frontendDebug.dreq_fire.expect(true.B)
            frontendDebug.s2_valid.expect(false.B)
            frontendDebug.s3_valid.expect(false.B)
            backendDebug.decodeValid.expect(false.B)

            dut.reset.poke(false.B)
            dut.clock.step(1)
            logCoreTimeline(dut, cycle, "first-miss-detect")
            cycle += 1

            frontendDebug.cache_s1_valid.expect(true.B)
            frontendDebug.cache_s1_hit.expect(false.B)

            var observedMissReq = false
            var missReqWaitCycles = 0
            while (!observedMissReq && missReqWaitCycles < 16) {
                logCoreTimeline(dut, cycle, s"wait-miss-req-$missReqWaitCycles")
                observedMissReq = dut.io.nextLevelReq.req.peek().litToBoolean
                if (!observedMissReq) {
                    dut.clock.step(1)
                    cycle += 1
                    missReqWaitCycles += 1
                }
            }
            assert(observedMissReq, "miss request was not observed within 16 cycles")

            frontendDebug.s2_valid.expect(true.B)
            frontendDebug.s2_pcReg.expect(0.U)
            frontendDebug.s3_valid.expect(false.B)
            backendDebug.decodeValid.expect(false.B)

            val missAddr = dut.io.nextLevelReq.paddr.peek().litValue
            missAddr mustBe BigInt(0)

            for (waitIdx <- 0 until refillDelayCycles) {
                logCoreTimeline(dut, cycle, s"stall-in-s2-$waitIdx")
                dut.io.nextLevelRsp.vld.expect(false.B)
                frontendDebug.s2_valid.expect(true.B)
                frontendDebug.s2_pcReg.expect(0.U)
                frontendDebug.s3_valid.expect(false.B)
                backendDebug.decodeValid.expect(false.B)
                dut.clock.step(1)
                cycle += 1
            }

            dut.io.nextLevelRsp.vld.poke(true.B)
            dut.io.nextLevelRsp.data.poke(refillLine.U)
            logCoreTimeline(dut, cycle, "drive-refill")
            dut.clock.step(1)
            cycle += 1

            frontendDebug.s3_valid.expect(false.B)
            backendDebug.decodeValid.expect(false.B)

            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0.U)
            logCoreTimeline(dut, cycle, "refill-accepted")
            dut.clock.step(1)
            cycle += 1

            logCoreTimeline(dut, cycle, "s2-resp-visible")
            frontendDebug.s2_respValid.expect(true.B)
            frontendDebug.s2_valid.expect(true.B)
            frontendDebug.s2_pcReg.expect(4.U)

            dut.clock.step(1)
            cycle += 1

            logCoreTimeline(dut, cycle, "s3-visible")
            frontendDebug.s3_valid.expect(true.B)
            frontendDebug.s3_pcReg.expect(4.U)
            frontendDebug.s2_valid.expect(true.B)
            frontendDebug.s2_pcReg.expect(8.U)

            stepUntil(dut) {
                backendDebug.decodeValid.peek().litToBoolean && backendDebug.decodePc.peek().litValue == 0
            }
            logCoreTimeline(dut, cycle, "decode-pc0")
            backendDebug.decodeInst.expect(refillInstWords.head.U)

            dut.clock.step(1)
            cycle += 1
            logCoreTimeline(dut, cycle, "decode-pc4-idexe-pc0")
            backendDebug.idExeValid.expect(true.B)
            backendDebug.decodeValid.expect(true.B)
            backendDebug.decodePc.expect(4.U)
            backendDebug.decodeInst.expect(refillInstWords(1).U)

            dut.clock.step(1)
            cycle += 1
            logCoreTimeline(dut, cycle, "decode-pc8-exemem-pc0")
            backendDebug.idExeValid.expect(true.B)
            backendDebug.exeMemValid.expect(true.B)
            backendDebug.decodeValid.expect(true.B)
            backendDebug.decodePc.expect(8.U)
            backendDebug.decodeInst.expect(refillInstWords(2).U)

            dut.clock.step(1)
            cycle += 1
            logCoreTimeline(dut, cycle, "decode-pc12-memwb-pc0")
            backendDebug.idExeValid.expect(true.B)
            backendDebug.exeMemValid.expect(true.B)
            backendDebug.memWbValid.expect(true.B)
            backendDebug.wbData.expect(1.U)
            backendDebug.decodeValid.expect(true.B)
            backendDebug.decodePc.expect(12.U)
            backendDebug.decodeInst.expect(refillInstWords(3).U)

            dut.clock.step(1)
            cycle += 1
            logCoreTimeline(dut, cycle, "steady-state-1")
            backendDebug.idExeValid.expect(true.B)
            backendDebug.exeMemValid.expect(true.B)
            backendDebug.memWbValid.expect(true.B)
            backendDebug.wbData.expect(2.U)
            dut.clock.step(1)
            cycle += 1
            logCoreTimeline(dut, cycle, "steady-state-2")
            backendDebug.exeMemValid.expect(true.B)
            backendDebug.memWbValid.expect(true.B)
            backendDebug.wbData.expect(3.U)
        }
    }

    "BreezeCore should redirect to the taken branch target after a frontend mispredict" in {
        simulate(new BreezeCore(BreezeCoreConfig(useFASE = false), enabledebug = true)) { dut =>
            val frontendDebug = dut.io.frontendDebug.get
            val backendDebug = dut.io.debug.get
            val branchPc = BigInt(0x8)
            val targetPc = BigInt(0x40)
            val branchInst = encodeBranch(rs1 = 1, rs2 = 2, imm = (targetPc - branchPc).toInt, funct3 = 0)
            val targetFirstInst = encodeAddi(rd = 5, rs1 = 0, imm = 42)
            val fallbackLine = buildRefillLine(Seq.fill(8)(BigInt("deadbeef", 16)))
            val lineMap = Map(
                BigInt(0x0) -> buildRefillLine(Seq(
                    encodeAddi(rd = 1, rs1 = 0, imm = 1),
                    encodeAddi(rd = 2, rs1 = 0, imm = 1),
                    branchInst,
                    nopInst,
                    nopInst,
                    nopInst,
                    nopInst,
                    nopInst
                )),
                BigInt(0x20) -> buildRefillLine(Seq.fill(8)(nopInst)),
                BigInt(0x40) -> buildRefillLine(Seq(
                    encodeAddi(rd = 5, rs1 = 0, imm = 42),
                    encodeAddi(rd = 6, rs1 = 0, imm = 7),
                    nopInst,
                    nopInst,
                    nopInst,
                    nopInst,
                    nopInst,
                    nopInst
                ))
            )
            val pendingIcacheResps = mutable.Queue.empty[PendingIcacheResp]
            val observedReqs = mutable.ArrayBuffer.empty[BigInt]
            var seenBranchDecode = false
            var checkedRedirectFlush = false
            var seenRedirectedS2 = false
            var seenRedirectedS3 = false
            var seenRedirectedDecode = false
            var cycle = 0

            dut.io.resetAddr.poke(0.U)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0.U)
            dut.io.dmem.rsp.valid.poke(false.B)
            dut.io.dmem.rsp.data.poke(0.U)
            dut.io.dmem.rsp.isWriteAck.poke(false.B)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            while (cycle < 200 && !seenRedirectedDecode) {
                pendingIcacheResps.headOption match {
                    case Some(resp) if resp.cyclesLeft == 0 =>
                        dut.io.nextLevelRsp.vld.poke(true.B)
                        dut.io.nextLevelRsp.data.poke(resp.data.U)
                    case _ =>
                        dut.io.nextLevelRsp.vld.poke(false.B)
                        dut.io.nextLevelRsp.data.poke(0.U)
                }

                if (dut.io.nextLevelReq.req.peek().litToBoolean) {
                    val reqAddr = dut.io.nextLevelReq.paddr.peek().litValue
                    observedReqs += reqAddr
                    pendingIcacheResps.enqueue(
                        PendingIcacheResp(reqAddr, lineMap.getOrElse(reqAddr, fallbackLine), cyclesLeft = 6)
                    )
                }

                if (!seenBranchDecode &&
                    backendDebug.decodeValid.peek().litToBoolean &&
                    backendDebug.decodeInst.peek().litValue == branchInst) {
                    seenBranchDecode = true
                    backendDebug.decodePc.expect(branchPc.U)
                } else if (seenBranchDecode && !checkedRedirectFlush) {
                    backendDebug.idExeValid.expect(true.B)
                    backendDebug.idExeInst.expect(branchInst.U)
                    backendDebug.idExePc.expect(branchPc.U)
                    backendDebug.exeBruTaken.expect(true.B)
                    backendDebug.exeJumpAddr.expect(targetPc.U)
                    backendDebug.redirectValid.expect(true.B)

                    dut.clock.step(1)
                    cycle += 1

                    dut.io.nextLevelRsp.vld.poke(false.B)
                    dut.io.nextLevelRsp.data.poke(0.U)

                    if (pendingIcacheResps.headOption.exists(_.cyclesLeft == 0)) {
                        pendingIcacheResps.dequeue()
                    }
                    val updatedAfterFlush = pendingIcacheResps.map { resp =>
                        resp.copy(cyclesLeft = math.max(resp.cyclesLeft - 1, 0))
                    }
                    pendingIcacheResps.clear()
                    pendingIcacheResps ++= updatedAfterFlush

                    frontendDebug.s1_valid.expect(true.B)
                    frontendDebug.s1_pcReg.expect(targetPc.U)
                    frontendDebug.s2_valid.expect(false.B)
                    frontendDebug.s3_valid.expect(false.B)
                    backendDebug.decodeValid.expect(false.B)
                    backendDebug.idExeValid.expect(false.B)
                    backendDebug.exeMemValid.expect(true.B)
                    backendDebug.exeMemPc.expect(branchPc.U)
                    checkedRedirectFlush = true
                } else if (checkedRedirectFlush && !seenRedirectedS2 && frontendDebug.s2_valid.peek().litToBoolean) {
                    frontendDebug.s2_pcReg.expect(targetPc.U)
                    frontendDebug.s3_valid.expect(false.B)
                    backendDebug.decodeValid.expect(false.B)
                    backendDebug.idExeValid.expect(false.B)
                    backendDebug.exeMemValid.expect(false.B)
                    seenRedirectedS2 = true
                } else if (seenRedirectedS2 && !seenRedirectedS3) {
                    backendDebug.decodeValid.expect(false.B)
                    backendDebug.idExeValid.expect(false.B)
                    if (frontendDebug.s3_valid.peek().litToBoolean) {
                        frontendDebug.s3_pcReg.expect(targetPc.U)
                        frontendDebug.cache_drsp_valid.expect(true.B)
                        seenRedirectedS3 = true
                    }
                } else if (seenRedirectedS3 && !seenRedirectedDecode) {
                    if (backendDebug.decodeValid.peek().litToBoolean) {
                        backendDebug.decodePc.expect(targetPc.U)
                        backendDebug.decodeInst.expect(targetFirstInst.U)
                        seenRedirectedDecode = true
                    }
                }

                if (!seenRedirectedDecode) {
                    dut.clock.step(1)
                    cycle += 1

                    dut.io.nextLevelRsp.vld.poke(false.B)
                    dut.io.nextLevelRsp.data.poke(0.U)

                    if (pendingIcacheResps.headOption.exists(_.cyclesLeft == 0)) {
                        pendingIcacheResps.dequeue()
                    }
                    val updatedPending = pendingIcacheResps.map { resp =>
                        resp.copy(cyclesLeft = math.max(resp.cyclesLeft - 1, 0))
                    }
                    pendingIcacheResps.clear()
                    pendingIcacheResps ++= updatedPending
                }
            }

            seenBranchDecode mustBe true
            checkedRedirectFlush mustBe true
            seenRedirectedS2 mustBe true
            seenRedirectedS3 mustBe true
            seenRedirectedDecode mustBe true
            observedReqs.contains(BigInt(0x0)) mustBe true
        }
    }
}
