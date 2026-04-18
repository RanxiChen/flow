package flow.core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.BreezeCoreConfig
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.collection.mutable

class BreezeCoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
    private val nopInst = BigInt("00000013", 16)

    private def encodeAddi(rd: Int, rs1: Int, imm: Int): BigInt = {
        val imm12 = imm & 0xfff
        BigInt((imm12 << 20) | (rs1 << 15) | (0 << 12) | (rd << 7) | 0x13)
    }

    private case class AddiTrace(rd: Int, imm: Int, inst: BigInt)
    private case class LoadCase(name: String, funct3: Int, offset: Int, rspData: BigInt, expectedWb: BigInt)
    private case class StoreCase(name: String, funct3: Int, offset: Int, rs2Value: Int, expectedWdata: BigInt, expectedWmask: BigInt)
    private case class ObservedDmemReq(addr: BigInt, isWrite: Boolean, wdata: BigInt, wmask: BigInt)
    private case class PendingDmemResp(addr: BigInt, data: BigInt, isWriteAck: Boolean, cyclesLeft: Int)

    private def encodeLoad(rd: Int, rs1: Int, imm: Int, funct3: Int): BigInt = {
        val imm12 = imm & 0xfff
        BigInt((imm12 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x03)
    }

    private def encodeStore(rs1: Int, rs2: Int, imm: Int, funct3: Int): BigInt = {
        val imm12 = imm & 0xfff
        val immHi = (imm12 >> 5) & 0x7f
        val immLo = imm12 & 0x1f
        BigInt((immHi << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (immLo << 7) | 0x23)
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
