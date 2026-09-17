package flow.backend

import chisel3._
import flow.config.{BackendConfig, FrontendBranchPredictorKind, PrivilegeProfile}
import flow.fpu.BreezeFpChiselSim
import flow.interface.FrontendPredType
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeRedirectPrioritySpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
    private val GhrLength = 4
    private val cfg = BackendConfig(
      branchPredKind = FrontendBranchPredictorKind.GShare,
      ghrLength = GhrLength, privilegeProfile = PrivilegeProfile.Linux, enableCompressed = true
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
        dut.io.fetchBuffer.bits.instructionFaultSecondParcel.poke(false.B)
        dut.io.fetchBuffer.bits.pred.predType.poke(FrontendPredType.NONE)
        dut.io.fetchBuffer.bits.pred.predTaken.poke(false.B)
        dut.io.fetchBuffer.bits.pred.predPc.poke(0.U)
        dut.io.fetchBuffer.bits.pred.phtIdx.poke(0.U)
        dut.io.dmem.rsp.valid.poke(false.B)
        dut.io.dmem.rsp.data.poke(0.U)
        dut.io.dmem.rsp.isWriteAck.poke(false.B)
        dut.io.dmem.rsp.error.poke(false.B)
        dut.io.dmem.rsp.pageFault.poke(false.B)
        dut.io.dmem.rsp.faultAddr.poke(0.U)
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
        dut.io.fetchBuffer.bits.instructionFaultSecondParcel.poke(false.B)
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
        dut.io.fetchBuffer.bits.instructionFaultSecondParcel.poke(false.B)
        dut.io.fetchBuffer.bits.pred.predType.poke(FrontendPredType.NONE)
        dut.io.fetchBuffer.bits.pred.predTaken.poke(false.B)
        dut.io.fetchBuffer.bits.pred.predPc.poke((pc + 4).U)
        dut.io.fetchBuffer.bits.pred.phtIdx.poke(0.U)
        dut.io.fetchBuffer.ready.expect(true.B)
        dut.clock.step(1)
        dut.io.fetchBuffer.valid.poke(false.B)
    }

    private def writeCsr(d: BreezeBackend, address: Int, value: Int): Unit = {
        issueInstruction(d, 0x40, encodeAddi(1, 0, value)); d.clock.step(4)
        issueInstruction(d, 0x44, (BigInt(address) << 20) | (1 << 15) | 0x1073)
        d.clock.step(4)
    }

    "WB store fault beats younger branch, memory, sfence and fence side effects" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { d =>
            for (younger <- Seq("branch", "store", "sfence", "fence")) {
                reset(d); writeCsr(d, 0x305, 0x400)
                val pc = BigInt("3fbed9498e", 16)
                issueInstruction(d, pc, BigInt("00003023", 16)) // sd x0,0(x0)
                val middle = younger match {
                    case "store" => BigInt("00003423", 16) // sd x0,8(x0)
                    case "fence" => BigInt("0000100f", 16)
                    case _ => encodeAddi(0, 0, 0)
                }
                issueInstruction(d, pc+4, middle)
                d.io.dmem.req.valid.expect(true.B)
                d.clock.step(2) // original store outstanding, middle held in EX
                d.io.dmem.rsp.valid.poke(true.B)
                d.io.dmem.rsp.error.poke(true.B)
                d.io.dmem.rsp.pageFault.poke(true.B)
                d.io.dmem.rsp.isWriteAck.poke(true.B)
                if (younger == "sfence") issueInstruction(d, pc+8, BigInt("12000073", 16))
                else issueBranch(d, pc+8, encodeBranch(0,0,-0x18,0), false, pc+12, 3)
                d.io.dmem.rsp.valid.poke(false.B)
                d.io.debug.get.memWbPc.expect(pc.U)
                d.io.debug.get.memWbTrapValid.expect(true.B)
                d.io.debug.get.idExeValid.expect(true.B)
                d.io.frontendRedirect.valid.expect(true.B)
                d.io.frontendRedirect.target.expect(0x400.U) // old Mux1H fails here
                d.io.frontendPhtUpdate.valid.expect(false.B)
                d.io.frontendGhrUpdate.valid.expect(false.B)
                d.io.frontendBtbUpdate.valid.expect(false.B)
                d.io.dmem.req.valid.expect(false.B)
                d.io.sfence.valid.expect(false.B)
                d.io.dcacheFlushReq.expect(false.B)
                d.io.frontendRedirect.cacheFlush.expect(false.B)
                d.clock.step()
                d.io.debug.get.csrMcause.expect(15.U)
                d.io.debug.get.csrMepc.expect(pc.U)
                d.io.debug.get.idExeValid.expect(false.B)
                d.io.debug.get.exeMemValid.expect(false.B)
                d.io.dmem.req.valid.expect(false.B)
            }
        }
    }

    "WB mret beats a simultaneous EX branch and satp blocks younger issue" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { d =>
            for (ret <- Seq(true, false)) {
                reset(d); writeCsr(d, 0x341, 0x600)
                // Preserve M mode on mret so this test isolates target arbitration.
                issueInstruction(d, 0x50, BigInt("000020b7",16)) // lui x1,2
                d.clock.step(4)
                issueInstruction(d, 0x54, encodeAddi(1,1,-2048)); d.clock.step(4)
                issueInstruction(d, 0x58, BigInt("30009073",16)); d.clock.step(4)
                val instruction = if (ret) BigInt("30200073",16) else BigInt("18001073",16)
                issueInstruction(d, 0x100, instruction)
                if (ret) {
                    issueInstruction(d, 0x104, encodeAddi(0,0,0))
                    issueBranch(d, 0x108, encodeBranch(0,0,0x20,0), false, 0x10c, 2)
                } else {
                    // A satp CSR now drains before any younger decode.
                    d.io.fetchBuffer.valid.poke(true.B)
                    d.io.fetchBuffer.bits.pc.poke(0x104.U)
                    d.io.fetchBuffer.bits.inst.poke(encodeBranch(0,0,0x20,0).U)
                    for (_ <- 0 until 2) {
                        d.io.fetchBuffer.ready.expect(false.B)
                        d.clock.step()
                    }
                    d.io.fetchBuffer.ready.expect(false.B)
                    d.io.fetchBuffer.valid.poke(false.B)
                }
                d.io.debug.get.memWbPc.expect(0x100.U)
                d.io.debug.get.idExeValid.expect(ret.B)
                d.io.frontendRedirect.valid.expect(true.B)
                d.io.frontendRedirect.target.expect((if (ret) 0x600 else 0x104).U)
                d.io.frontendPhtUpdate.valid.expect(false.B)
                d.io.frontendGhrUpdate.valid.expect(false.B)
                d.clock.step()
                d.io.frontendBtbUpdate.valid.expect(false.B)
            }
        }
    }

    "overlapping fetch fault flags select one cause rather than OR causes" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { d =>
            reset(d)
            d.io.fetchBuffer.bits.pc.poke(0x180.U)
            d.io.fetchBuffer.bits.inst.poke(0.U)
            d.io.fetchBuffer.bits.rawInst.poke(0.U)
            d.io.fetchBuffer.bits.instructionAccessFault.poke(true.B)
            d.io.fetchBuffer.bits.instructionPageFault.poke(true.B)
            d.io.fetchBuffer.valid.poke(true.B); d.clock.step()
            d.io.fetchBuffer.valid.poke(false.B); d.clock.step(3)
            d.io.debug.get.csrMcause.expect(1.U)
            d.io.debug.get.csrMepc.expect(0x180.U)
        }
    }

    "keep EPC at the instruction start but report a fault on its second parcel" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { d =>
            reset(d)
            d.io.fetchBuffer.bits.pc.poke(0x1ffe.U)
            d.io.fetchBuffer.bits.instructionPageFault.poke(true.B)
            d.io.fetchBuffer.bits.instructionFaultSecondParcel.poke(true.B)
            d.io.fetchBuffer.valid.poke(true.B); d.clock.step()
            d.io.fetchBuffer.valid.poke(false.B); d.clock.step(3)
            d.io.debug.get.csrMepc.expect(0x1ffe.U)
            d.io.debug.get.csrMcause.expect(12.U)
            d.io.fetchBuffer.bits.instructionPageFault.poke(false.B)
            issueInstruction(d, 0x200, BigInt("343022f3", 16)) // csrr x5,mtval
            d.clock.step(2)
            d.io.debug.get.memWbValid.expect(true.B)
            d.io.debug.get.wbData.expect(0x2000.U)
        }
    }

    "forward a CSR result to the following SC store operand" in {
        simulate(new BreezeBackend(cfg, enabledebug = true)) { d =>
            reset(d)
            writeCsr(d, 0x340, 0x55)
            issueInstruction(d, 0x100, BigInt("34002173", 16)) // csrr x2,mscratch
            d.io.fetchBuffer.bits.pc.poke(0x104.U)
            d.io.fetchBuffer.bits.inst.poke(BigInt("182031af", 16).U) // sc.d x3,x2,(x0)
            d.io.fetchBuffer.valid.poke(true.B)
            var cycles = 0
            while (!d.io.fetchBuffer.ready.peek().litToBoolean && cycles < 20) {
                d.clock.step(); cycles += 1
            }
            assert(cycles < 20)
            d.clock.step(); d.io.fetchBuffer.valid.poke(false.B)
            cycles = 0
            while (!d.io.dmem.req.valid.peek().litToBoolean && cycles < 20) {
                d.clock.step(); cycles += 1
            }
            assert(cycles < 20)
            d.io.dmem.req.wdata.expect(0x55.U)
        }
    }
}
