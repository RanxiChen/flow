package flow.backend

import chisel3._
import flow.config.{BackendConfig, FrontendBranchPredictorKind}
import flow.fpu.BreezeFpChiselSim
import flow.interface.FrontendPredType
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeBackendGShareSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
    private val GhrLength = 4
    private val cfg = BackendConfig(
      branchPredKind = FrontendBranchPredictorKind.GShare,
      ghrLength = GhrLength
    )

    private def encodeBranch(rs1: Int, rs2: Int, imm: Int, funct3: Int): BigInt = {
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

    private def encodeAddi(rd: Int, rs1: Int, imm: Int): BigInt = {
        (BigInt(imm & 0xfff) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(rd) << 7) |
        BigInt(0x13)
    }

    private def encodeJalr(rd: Int, rs1: Int, imm: Int): BigInt = {
        (BigInt(imm & 0xfff) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(rd) << 7) |
        BigInt(0x67)
    }

    private def driveIdleInputs(dut: BreezeBackend): Unit = {
        dut.io.resetAddr.poke(0.U)
        dut.io.machineTimerInterrupt.poke(false.B)
        dut.io.machineSoftwareInterrupt.poke(false.B)
        dut.io.supervisorExternalInterrupt.poke(false.B)
        dut.io.time.poke(0.U)
        dut.io.externalInterrupts.poke(0.U)
        dut.io.fetchBuffer.valid.poke(false.B)
        dut.io.fetchBuffer.bits.pc.poke(0.U)
        dut.io.fetchBuffer.bits.inst.poke(0.U)
        dut.io.fetchBuffer.bits.rawInst.poke(0.U)
        dut.io.fetchBuffer.bits.instLen.poke(4.U)
        dut.io.fetchBuffer.bits.isCompressed.poke(false.B)
        dut.io.fetchBuffer.bits.illegalCompressed.poke(false.B)
        dut.io.fetchBuffer.bits.instructionPageFault.poke(false.B)
        dut.io.fetchBuffer.bits.instructionAccessFault.poke(false.B)
        dut.io.fetchBuffer.bits.pred.predType.poke(FrontendPredType.NONE)
        dut.io.fetchBuffer.bits.pred.predTaken.poke(false.B)
        dut.io.fetchBuffer.bits.pred.predPc.poke(0.U)
        dut.io.fetchBuffer.bits.pred.phtIdx.poke(0.U)
        dut.io.dmem.rsp.valid.poke(false.B)
        dut.io.dmem.rsp.data.poke(0.U)
        dut.io.dmem.rsp.isWriteAck.poke(false.B)
        dut.io.dmem.rsp.error.poke(false.B)
        dut.io.dcacheFlushDone.poke(false.B)
        dut.io.hpmEvents.controlRetired.poke(false.B)
        dut.io.hpmEvents.controlTaken.poke(false.B)
        dut.io.hpmEvents.predictionMiss.poke(false.B)
        dut.io.hpmEvents.icacheAccess.poke(false.B)
        dut.io.hpmEvents.icacheMiss.poke(false.B)
        dut.io.hpmEvents.dcacheAccess.poke(false.B)
        dut.io.hpmEvents.dcacheMiss.poke(false.B)
        dut.io.hpmEvents.dcacheUncached.poke(false.B)
        dut.io.hpmEvents.memStallCycle.poke(false.B)
        dut.io.hpmEvents.loadUseStall.poke(false.B)
    }

    private def reset(dut: BreezeBackend): Unit = {
        driveIdleInputs(dut)
        dut.reset.poke(true.B)
        dut.clock.step(1)
        dut.reset.poke(false.B)
    }

    private def issueBranch(
        dut: BreezeBackend,
        pc: BigInt,
        inst: BigInt,
        predTaken: Boolean,
        predPc: BigInt,
        phtIdx: Int
    ): Unit = {
        dut.io.fetchBuffer.valid.poke(true.B)
        dut.io.fetchBuffer.bits.pc.poke(pc.U)
        dut.io.fetchBuffer.bits.inst.poke(inst.U)
        dut.io.fetchBuffer.bits.instructionAccessFault.poke(false.B)
        dut.io.fetchBuffer.bits.pred.predType.poke(FrontendPredType.BR)
        dut.io.fetchBuffer.bits.pred.predTaken.poke(predTaken.B)
        dut.io.fetchBuffer.bits.pred.predPc.poke(predPc.U)
        dut.io.fetchBuffer.bits.pred.phtIdx.poke(phtIdx.U)
        dut.io.fetchBuffer.ready.expect(true.B)
        dut.clock.step(1)
        dut.io.fetchBuffer.valid.poke(false.B)
    }

    private def issueInstruction(dut: BreezeBackend, pc: BigInt, inst: BigInt): Unit = {
        dut.io.fetchBuffer.valid.poke(true.B)
        dut.io.fetchBuffer.bits.pc.poke(pc.U)
        dut.io.fetchBuffer.bits.inst.poke(inst.U)
        dut.io.fetchBuffer.bits.instructionAccessFault.poke(false.B)
        dut.io.fetchBuffer.bits.pred.predType.poke(FrontendPredType.NONE)
        dut.io.fetchBuffer.bits.pred.predTaken.poke(false.B)
        dut.io.fetchBuffer.bits.pred.predPc.poke((pc + 4).U)
        dut.io.fetchBuffer.bits.pred.phtIdx.poke(0.U)
        dut.io.fetchBuffer.ready.expect(true.B)
        dut.clock.step(1)
        dut.io.fetchBuffer.valid.poke(false.B)
    }

    private def issuePredictedControl(
        dut: BreezeBackend,
        pc: BigInt,
        inst: BigInt,
        predType: FrontendPredType.Type,
        predPc: BigInt
    ): Unit = {
        dut.io.fetchBuffer.valid.poke(true.B)
        dut.io.fetchBuffer.bits.pc.poke(pc.U)
        dut.io.fetchBuffer.bits.inst.poke(inst.U)
        dut.io.fetchBuffer.bits.instructionAccessFault.poke(false.B)
        dut.io.fetchBuffer.bits.pred.predType.poke(predType)
        dut.io.fetchBuffer.bits.pred.predTaken.poke(true.B)
        dut.io.fetchBuffer.bits.pred.predPc.poke(predPc.U)
        dut.io.fetchBuffer.bits.pred.phtIdx.poke(0.U)
        dut.io.fetchBuffer.ready.expect(true.B)
        dut.clock.step(1)
        dut.io.fetchBuffer.valid.poke(false.B)
    }

    "GShare backend should redirect a predicted-taken branch to fall-through when actually not-taken" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { dut =>
            val pc = BigInt(0x100)
            val predictedTarget = BigInt(0x80)
            val phtIdx = 7
            val bneX0X0 = encodeBranch(rs1 = 0, rs2 = 0, imm = -0x20, funct3 = 1)

            reset(dut)
            issueBranch(
              dut,
              pc,
              bneX0X0,
              predTaken = true,
              predPc = predictedTarget,
              phtIdx = phtIdx
            )

            dut.io.debug.get.idExeValid.expect(true.B)
            dut.io.debug.get.exeBruTaken.expect(false.B)
            dut.io.frontendRedirect.valid.expect(true.B)
            dut.io.frontendRedirect.target.expect((pc + 4).U)

            dut.io.frontendBtbUpdate.valid.expect(false.B)
            dut.io.frontendPhtUpdate.valid.expect(true.B)
            dut.io.frontendPhtUpdate.idx.expect(phtIdx.U)
            dut.io.frontendPhtUpdate.taken.expect(false.B)
            dut.io.frontendGhrUpdate.valid.expect(true.B)
            dut.io.frontendGhrUpdate.taken.expect(false.B)

            dut.clock.step(1)
            dut.io.frontendBtbUpdate.valid.expect(true.B)
            dut.io.frontendBtbUpdate.pc.expect(pc.U)
            dut.io.frontendBtbUpdate.target.expect((pc - 0x20).U)
            dut.io.frontendBtbUpdate.predType.expect(FrontendPredType.BR)
            dut.io.frontendBtbUpdate.taken.expect(false.B)
            dut.clock.step(1)
            dut.io.frontendBtbUpdate.valid.expect(false.B)
            dut.io.frontendPhtUpdate.valid.expect(false.B)
            dut.io.frontendGhrUpdate.valid.expect(false.B)
        }
    }

    "GShare backend should redirect a predicted-not-taken branch to its taken target" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { dut =>
            val pc = BigInt(0x100)
            val target = BigInt(0xe0)
            val phtIdx = 3
            val beqX0X0 = encodeBranch(rs1 = 0, rs2 = 0, imm = -0x20, funct3 = 0)

            reset(dut)
            issueBranch(
              dut,
              pc,
              beqX0X0,
              predTaken = false,
              predPc = pc + 4,
              phtIdx = phtIdx
            )

            dut.io.debug.get.exeBruTaken.expect(true.B)
            dut.io.frontendRedirect.valid.expect(true.B)
            dut.io.frontendRedirect.target.expect(target.U)
            dut.io.frontendBtbUpdate.valid.expect(false.B)
            dut.io.frontendPhtUpdate.valid.expect(true.B)
            dut.io.frontendPhtUpdate.taken.expect(true.B)
            dut.io.frontendGhrUpdate.valid.expect(true.B)
            dut.io.frontendGhrUpdate.taken.expect(true.B)
            dut.clock.step(1)
            dut.io.frontendBtbUpdate.valid.expect(true.B)
            dut.io.frontendBtbUpdate.target.expect(target.U)
            dut.io.frontendBtbUpdate.taken.expect(true.B)
            dut.clock.step(1)
            dut.io.frontendBtbUpdate.valid.expect(false.B)
        }
    }

    "GShare backend should train a correctly predicted branch without redirecting" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { dut =>
            val pc = BigInt(0x100)
            val phtIdx = 5
            val bneX0X0 = encodeBranch(rs1 = 0, rs2 = 0, imm = -0x20, funct3 = 1)

            reset(dut)
            issueBranch(
              dut,
              pc,
              bneX0X0,
              predTaken = false,
              predPc = pc + 4,
              phtIdx = phtIdx
            )

            dut.io.frontendRedirect.valid.expect(false.B)
            dut.io.frontendBtbUpdate.valid.expect(false.B)
            dut.io.frontendPhtUpdate.valid.expect(true.B)
            dut.io.frontendPhtUpdate.idx.expect(phtIdx.U)
            dut.io.frontendPhtUpdate.taken.expect(false.B)
            dut.io.frontendGhrUpdate.valid.expect(true.B)
            dut.io.frontendGhrUpdate.taken.expect(false.B)
            dut.clock.step(1)
            dut.io.frontendBtbUpdate.valid.expect(true.B)
            dut.clock.step(1)
            dut.io.frontendBtbUpdate.valid.expect(false.B)
        }
    }

    "GShare backend should not retrain a branch while an older load holds the pipeline" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { dut =>
            val loadPc = BigInt(0x100)
            val branchPc = BigInt(0x104)
            val loadX1FromZero = BigInt("00003083", 16) // ld x1, 0(x0)
            val bneX0X0 = encodeBranch(rs1 = 0, rs2 = 0, imm = -4, funct3 = 1)
            val phtIdx = 9

            reset(dut)
            issueInstruction(dut, loadPc, loadX1FromZero)
            issueBranch(
              dut,
              branchPc,
              bneX0X0,
              predTaken = false,
              predPc = branchPc + 4,
              phtIdx = phtIdx
            )

            dut.io.debug.get.idExeValid.expect(true.B)
            dut.io.debug.get.idExePc.expect(branchPc.U)
            dut.io.dmem.req.valid.expect(true.B)
            dut.io.frontendBtbUpdate.valid.expect(false.B)
            dut.io.frontendPhtUpdate.valid.expect(false.B)
            dut.io.frontendPhtUpdate.idx.expect(phtIdx.U)
            dut.io.frontendGhrUpdate.valid.expect(false.B)

            // The request cycle and every response-wait cycle must suppress
            // branch resolution and predictor training.
            dut.clock.step(1)
            for (_ <- 0 until 3) {
                dut.io.debug.get.idExePc.expect(branchPc.U)
                dut.io.frontendBtbUpdate.valid.expect(false.B)
                dut.io.frontendPhtUpdate.valid.expect(false.B)
                dut.io.frontendPhtUpdate.idx.expect(phtIdx.U)
                dut.io.frontendGhrUpdate.valid.expect(false.B)
                dut.clock.step(1)
            }

            // The held branch can resolve exactly when the older load responds.
            dut.io.dmem.rsp.valid.poke(true.B)
            dut.io.dmem.rsp.data.poke(0.U)
            dut.io.dmem.rsp.isWriteAck.poke(false.B)
            dut.io.dmem.rsp.error.poke(false.B)
            dut.io.frontendBtbUpdate.valid.expect(false.B)
            dut.io.frontendPhtUpdate.valid.expect(true.B)
            dut.io.frontendPhtUpdate.idx.expect(phtIdx.U)
            dut.io.frontendGhrUpdate.valid.expect(true.B)
            dut.clock.step(1)
            dut.io.frontendBtbUpdate.valid.expect(true.B)

            dut.io.dmem.rsp.valid.poke(false.B)
            dut.clock.step(1)
            dut.io.frontendBtbUpdate.valid.expect(false.B)
            dut.io.frontendPhtUpdate.valid.expect(false.B)
            dut.io.frontendGhrUpdate.valid.expect(false.B)
        }
    }

    for ((name, operation, expectedIntegerResult) <- Seq(
        ("multiply", BigInt("022082b3", 16), Some(BigInt(567))), // mul x5,x1,x2
        ("divide", BigInt("0220d2b3", 16), Some(BigInt(7))),     // divu x5,x1,x2
        ("floating add", BigInt("020001d3", 16), None))) {     // fadd.d f3,f0,f0
        s"GShare backend should drain $name and train its younger branch before taking a timer interrupt" in {
            simulate(new BreezeBackend(cfg, enabledebug = true)) { dut =>
                reset(dut)
                def setup(pc: Int, inst: BigInt): Unit = {
                    issueInstruction(dut, BigInt(pc), inst)
                    // Keep CSR/register setup separate from the measured drain.
                    dut.clock.step(12)
                }
                def csrw(address: Int, rs1: Int): BigInt =
                    (BigInt(address) << 20) | (BigInt(rs1) << 15) | BigInt(0x1073)
                // Enable MTIE and global MIE; also enable floating-point state.
                setup(0x00, encodeAddi(1, 0, 128))
                setup(0x04, csrw(0x304, 1))
                setup(0x08, BigInt("000060b7", 16)) // lui x1,6
                setup(0x0c, encodeAddi(1, 1, 8))
                setup(0x10, csrw(0x300, 1))
                setup(0x14, BigInt("f2000053", 16)) // fmv.d.x f0,x0
                setup(0x18, encodeAddi(1, 0, 63))
                setup(0x1c, encodeAddi(2, 0, 9))

                val operationPc = BigInt(0x100)
                val branchPc = BigInt(0x104)
                val index = 11
                issueInstruction(dut, operationPc, operation)
                issueBranch(dut, branchPc,
                    encodeBranch(0, 0, 8, 1), // bne x0,x0,+8, actually not taken
                    predTaken = false, predPc = branchPc + 4, phtIdx = index)
                // The operation is about to issue from MEM, and the branch is
                // held in EXE. A pending interrupt must not cancel either one.
                dut.io.debug.get.exeMemValid.expect(true.B)
                dut.io.debug.get.exeMemPc.expect(operationPc.U)
                dut.io.frontendPhtUpdate.valid.expect(false.B)
                dut.io.frontendPhtUpdate.idx.expect(index.U)
                dut.io.machineTimerInterrupt.poke(true.B)
                // Offer a younger instruction throughout drain. It must never
                // enter the backend before the interrupt redirect.
                dut.io.fetchBuffer.valid.poke(true.B)
                dut.io.fetchBuffer.bits.pc.poke((branchPc + 4).U)
                dut.io.fetchBuffer.bits.inst.poke(encodeAddi(31, 0, 1).U)
                dut.io.fetchBuffer.bits.pred.predType.poke(FrontendPredType.NONE)
                dut.io.fetchBuffer.bits.pred.predTaken.poke(false.B)
                dut.io.fetchBuffer.bits.pred.predPc.poke((branchPc + 8).U)
                dut.io.fetchBuffer.bits.pred.phtIdx.poke(0.U)

                var trainingCount = 0
                var operationRetireCount = 0
                var branchRetireCount = 0
                var interruptSeen = false
                var cycles = 0
                while (!interruptSeen && cycles < 160) {
                    dut.io.fetchBuffer.ready.expect(false.B)
                    if (dut.io.frontendPhtUpdate.valid.peek().litToBoolean) {
                        dut.io.frontendPhtUpdate.idx.expect(index.U)
                        dut.io.frontendPhtUpdate.taken.expect(false.B)
                        trainingCount += 1
                    }
                    if (dut.io.debug.get.memWbValid.peek().litToBoolean) {
                        dut.io.debug.get.memWbTrapValid.expect(false.B)
                        val pc = dut.io.debug.get.memWbPc.peekValue().asBigInt
                        if (pc == operationPc) {
                            operationRetireCount += 1
                            expectedIntegerResult.foreach(value =>
                                dut.io.debug.get.wbData.expect(value.U))
                        } else {
                            assert(pc == branchPc, s"unexpected instruction retired at $pc")
                            branchRetireCount += 1
                        }
                    }
                    if (dut.io.debug.get.redirectValid.peek().litToBoolean) {
                        assert(operationRetireCount == 1 && branchRetireCount == 1,
                            "interrupt must wait for both older instructions to retire")
                        dut.io.debug.get.idExeValid.expect(false.B)
                        dut.io.debug.get.exeMemValid.expect(false.B)
                        dut.io.debug.get.memWbValid.expect(false.B)
                        dut.io.debug.get.memWbTrapValid.expect(true.B)
                        dut.io.frontendPhtUpdate.valid.expect(false.B)
                        interruptSeen = true
                        dut.io.fetchBuffer.valid.poke(false.B)
                    }
                    dut.clock.step(1)
                    cycles += 1
                }
                assert(interruptSeen, s"$name did not drain to a timer interrupt")
                assert(trainingCount == 1, s"branch trained $trainingCount times")
                dut.io.debug.get.csrMcause.expect(((BigInt(1) << 63) | 7).U)
                dut.io.debug.get.csrMepc.expect((branchPc + 4).U)
            }
        }
    }

    "GShare backend should repair a stale JALR target exactly once" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { dut =>
            val producerPc = BigInt(0xfc)
            val jalrPc = BigInt(0x100)
            val staleTarget = BigInt(0x40)
            val actualTarget = BigInt(0x80)

            reset(dut)
            issueInstruction(dut, producerPc, encodeAddi(rd = 1, rs1 = 0, imm = actualTarget.toInt))
            issuePredictedControl(
              dut,
              jalrPc,
              encodeJalr(rd = 0, rs1 = 1, imm = 0),
              FrontendPredType.JALR,
              staleTarget
            )

            dut.io.debug.get.idExeValid.expect(true.B)
            dut.io.debug.get.idExePc.expect(jalrPc.U)
            dut.io.frontendRedirect.valid.expect(true.B)
            dut.io.frontendRedirect.target.expect(actualTarget.U)
            dut.io.frontendBtbUpdate.valid.expect(false.B)
            dut.io.frontendPhtUpdate.valid.expect(false.B)
            dut.io.frontendGhrUpdate.valid.expect(false.B)

            dut.clock.step(1)
            dut.io.frontendBtbUpdate.valid.expect(true.B)
            dut.io.frontendBtbUpdate.pc.expect(jalrPc.U)
            dut.io.frontendBtbUpdate.target.expect(actualTarget.U)
            dut.io.frontendBtbUpdate.predType.expect(FrontendPredType.JALR)
            dut.io.frontendBtbUpdate.taken.expect(true.B)
            dut.io.frontendRedirect.valid.expect(false.B)
            dut.clock.step(1)
            dut.io.frontendBtbUpdate.valid.expect(false.B)
        }
    }

    "GShare backend should preserve a high Sv39 JALR target from a load" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { dut =>
            val loadPc = BigInt(0xfc)
            val jalrPc = BigInt(0x100)
            val staleTarget = BigInt(0x40)
            val actualTarget = BigInt("0000003f92bffbfe", 16)
            val truncatedTarget = BigInt("ffffffff92bffbfe", 16)
            val loadX1FromZero = BigInt("00003083", 16) // ld x1, 0(x0)

            reset(dut)
            issueInstruction(dut, loadPc, loadX1FromZero)
            issuePredictedControl(
              dut,
              jalrPc,
              encodeJalr(rd = 0, rs1 = 1, imm = 0),
              FrontendPredType.JALR,
              staleTarget
            )

            dut.io.debug.get.idExePc.expect(jalrPc.U)
            dut.io.dmem.req.valid.expect(true.B)
            dut.clock.step(1)
            for (_ <- 0 until 2) {
                dut.io.frontendRedirect.valid.expect(false.B)
                dut.clock.step(1)
            }

            dut.io.dmem.rsp.valid.poke(true.B)
            dut.io.dmem.rsp.data.poke(actualTarget.U)
            dut.io.dmem.rsp.isWriteAck.poke(false.B)
            dut.io.dmem.rsp.error.poke(false.B)
            dut.io.frontendRedirect.valid.expect(true.B)
            dut.io.frontendRedirect.target.expect(actualTarget.U)
            dut.io.frontendRedirect.target.peek().litValue must not be truncatedTarget
            dut.io.frontendBtbUpdate.valid.expect(false.B)
            dut.clock.step(1)
            dut.io.frontendBtbUpdate.valid.expect(true.B)
            dut.io.frontendBtbUpdate.target.expect(actualTarget.U)

            dut.io.dmem.rsp.valid.poke(false.B)
            dut.io.frontendRedirect.valid.expect(false.B)
            dut.clock.step(1)
            dut.io.frontendBtbUpdate.valid.expect(false.B)
        }
    }

    "GShare backend should keep a correct JALR target without retraining" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { dut =>
            val producerPc = BigInt(0xfc)
            val jalrPc = BigInt(0x100)
            val actualTarget = BigInt(0x80)

            reset(dut)
            issueInstruction(dut, producerPc, encodeAddi(rd = 1, rs1 = 0, imm = actualTarget.toInt))
            issuePredictedControl(
              dut,
              jalrPc,
              encodeJalr(rd = 0, rs1 = 1, imm = 0),
              FrontendPredType.JALR,
              actualTarget
            )

            dut.io.frontendRedirect.valid.expect(false.B)
            dut.io.frontendBtbUpdate.valid.expect(false.B)
            dut.io.frontendPhtUpdate.valid.expect(false.B)
            dut.io.frontendGhrUpdate.valid.expect(false.B)
        }
    }
}
