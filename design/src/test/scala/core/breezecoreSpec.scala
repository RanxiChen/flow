package flow.core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.BreezeCoreConfig
import flow.fpu.BreezeFpChiselSim
import flow.platform.BreezeMcuPlatform
import flow.interface.L1DRespKind
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.collection.mutable

class RegFileSpec extends AnyFreeSpec with Matchers with ChiselSim {
    "RegFile should forward a same-cycle write to both read ports" in {
        simulate(new RegFile(64)) { dut =>
            val value = BigInt("0123456789abcdef", 16)

            dut.io.rs1_addr.poke(0.U)
            dut.io.rs2_addr.poke(0.U)
            dut.io.rd_addr.poke(0.U)
            dut.io.rd_data.poke(0.U)
            dut.io.rd_en.poke(false.B)
            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            dut.io.rs1_addr.poke(5.U)
            dut.io.rs2_addr.poke(5.U)
            dut.io.rd_addr.poke(5.U)
            dut.io.rd_data.poke(value.U)
            dut.io.rd_en.poke(true.B)

            dut.io.rs1_data.expect(value.U)
            dut.io.rs2_data.expect(value.U)

            dut.clock.step(1)
            dut.io.rd_en.poke(false.B)
            dut.io.rs1_data.expect(value.U)
            dut.io.rs2_data.expect(value.U)

            dut.io.rs1_addr.poke(0.U)
            dut.io.rs2_addr.poke(0.U)
            dut.io.rd_addr.poke(0.U)
            dut.io.rd_data.poke("hffffffffffffffff".U)
            dut.io.rd_en.poke(true.B)
            dut.io.rs1_data.expect(0.U)
            dut.io.rs2_data.expect(0.U)
        }
    }
}

class CSRFileSpec extends AnyFreeSpec with Matchers with ChiselSim {
    private def clearHpmEvents(dut: CSRFile): Unit = {
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
        dut.io.fp_commit_valid.poke(false.B)
        dut.io.fp_flags.poke(0.U)
    }

    private def driveIdle(dut: CSRFile): Unit = {
        clearHpmEvents(dut)
        dut.io.csr_addr.poke(CSRMAP.mhartid.U)
        dut.io.csr_cmd.poke(CSR_CMD.RS.U)
        dut.io.csr_reg_data.poke(0.U)
        dut.io.rs1_id.poke(0.U)
        dut.io.rd_id.poke(1.U)
        dut.io.commit_valid.poke(false.B)
        dut.io.commit_write_en.poke(false.B)
        dut.io.commit_addr.poke(0.U)
        dut.io.commit_wdata.poke(0.U)
        dut.io.retire_valid.poke(false.B)
        dut.io.machineTimerInterrupt.poke(false.B)
        dut.io.machineExternalInterrupt.poke(false.B)
        dut.io.supervisorExternalInterrupt.poke(false.B)
        dut.io.time.poke(0.U)
        dut.io.trap.valid.poke(false.B)
        dut.io.trap.is_interrupt.poke(false.B)
        dut.io.trap.cause.poke(0.U)
        dut.io.trap.pc.poke(0.U)
        dut.io.trap.tval.poke(0.U)
        dut.io.mret_commit.poke(false.B)
    }

    "CSRFile should gate FP with mstatus.FS and keep fcsr flags sticky" in {
        simulate(new CSRFile(64)) { dut =>
            driveIdle(dut)
            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)
            dut.io.fp_enabled.expect(false.B)

            // Enable FP with FS=Initial.
            dut.io.commit_valid.poke(true.B)
            dut.io.commit_write_en.poke(true.B)
            dut.io.commit_addr.poke(CSRMAP.mstatus.U)
            dut.io.commit_wdata.poke((BigInt(1) << 13).U)
            dut.clock.step(1)
            dut.io.commit_valid.poke(false.B)
            dut.io.commit_write_en.poke(false.B)
            dut.io.fp_enabled.expect(true.B)

            // fcsr write: frm=RUP(3), fflags=NX(1).
            dut.io.commit_valid.poke(true.B)
            dut.io.commit_write_en.poke(true.B)
            dut.io.commit_addr.poke(CSRMAP.fcsr.U)
            dut.io.commit_wdata.poke(((BigInt(3) << 5) | 1).U)
            dut.clock.step(1)
            dut.io.commit_valid.poke(false.B)
            dut.io.commit_write_en.poke(false.B)
            dut.io.frm.expect(3.U)

            // A committed divide-by-zero ORs DZ into NX and marks FS Dirty.
            dut.io.fp_commit_valid.poke(true.B)
            dut.io.fp_flags.poke(8.U)
            dut.clock.step(1)
            dut.io.fp_commit_valid.poke(false.B)
            dut.io.fp_flags.poke(0.U)
            dut.io.csr_addr.poke(CSRMAP.fcsr.U)
            dut.io.csr_cmd.poke(CSR_CMD.RS.U)
            dut.io.csr_reg_data.poke(0.U)
            dut.io.rs1_id.poke(0.U)
            dut.io.rd_id.poke(1.U)
            dut.io.csr_old_data.expect(((BigInt(3) << 5) | 9).U)
        }
    }

    "CSRFile should expose the elaborated read-only hart identity" in {
        simulate(new CSRFile(64, hartId = 1)) { dut =>
            driveIdle(dut)
            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)
            dut.io.csr_old_data.expect(1.U)

            // A committed CSR write must not alter the read-only mhartid.
            dut.io.commit_valid.poke(true.B)
            dut.io.commit_write_en.poke(true.B)
            dut.io.commit_addr.poke(CSRMAP.mhartid.U)
            dut.io.commit_wdata.poke(7.U)
            dut.clock.step(1)
            dut.io.commit_valid.poke(false.B)
            dut.io.commit_write_en.poke(false.B)
            dut.io.csr_old_data.expect(1.U)
        }
    }

    "CSRFile should write mtvec via CSRRW (RW command) and read back" in {
        simulate(new CSRFile(64)) { dut =>
            clearHpmEvents(dut)
            // Drive trap/mret defaults
            dut.io.trap.valid.poke(false.B)
            dut.io.trap.is_interrupt.poke(false.B)
            dut.io.trap.cause.poke(0.U)
            dut.io.trap.pc.poke(0.U)
            dut.io.trap.tval.poke(0.U)
            dut.io.mret_commit.poke(false.B)
            dut.io.machineTimerInterrupt.poke(false.B)
            dut.io.machineExternalInterrupt.poke(false.B)
            dut.io.commit_valid.poke(false.B)
            dut.io.commit_write_en.poke(false.B)
            dut.io.commit_addr.poke(0.U)
            dut.io.commit_wdata.poke(0.U)
            dut.io.retire_valid.poke(false.B)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            // Step 1: Read mtvec (default 0x200) via pure read (RS with rs1=0)
            dut.io.csr_addr.poke(CSRMAP.mtvec.U)
            dut.io.csr_cmd.poke(CSR_CMD.RS.U)
            dut.io.csr_reg_data.poke(0.U)
            dut.io.rs1_id.poke(0.U)
            dut.io.rd_id.poke(1.U)
            dut.clock.step(1)
            dut.io.csr_old_data.expect(BigInt("200", 16).U)
            // RS with rs1=0 is pure read: write_csr=false

            // Step 2: CSRRW write 0x345 to mtvec (rd=x0, rs1=x5)
            dut.io.csr_addr.poke(CSRMAP.mtvec.U)
            dut.io.csr_cmd.poke(CSR_CMD.RW.U)
            dut.io.csr_reg_data.poke(BigInt("345", 16).U)
            dut.io.rs1_id.poke(5.U)
            dut.io.rd_id.poke(0.U)  // rd=0 → read suppressed, write still happens
            dut.clock.step(1)
            dut.io.csr_write_en.expect(true.B)
            dut.io.csr_new_data.expect(BigInt("345", 16).U)

            // Step 3: Commit the write
            dut.io.commit_valid.poke(true.B)
            dut.io.commit_write_en.poke(true.B)
            dut.io.commit_addr.poke(CSRMAP.mtvec.U)
            dut.io.commit_wdata.poke(BigInt("345", 16).U)
            dut.clock.step(1)
            dut.io.commit_valid.poke(false.B)
            dut.io.commit_write_en.poke(false.B)

            // Step 4: MODE=1 is supported and preserved.
            dut.io.csr_addr.poke(CSRMAP.mtvec.U)
            dut.io.csr_cmd.poke(CSR_CMD.RS.U)
            dut.io.csr_reg_data.poke(0.U)
            dut.io.rs1_id.poke(0.U)
            dut.io.rd_id.poke(1.U)
            dut.clock.step(1)
            dut.io.csr_old_data.expect(BigInt("345", 16).U)

            // Reserved MODE=2 is WARL-coerced to Direct mode.
            dut.io.csr_cmd.poke(CSR_CMD.RW.U)
            dut.io.csr_reg_data.poke(BigInt("346", 16).U)
            dut.io.rs1_id.poke(5.U)
            dut.io.rd_id.poke(0.U)
            dut.clock.step(1)
            dut.io.commit_valid.poke(true.B)
            dut.io.commit_write_en.poke(true.B)
            dut.io.commit_addr.poke(CSRMAP.mtvec.U)
            dut.io.commit_wdata.poke(BigInt("346", 16).U)
            dut.clock.step(1)
            dut.io.commit_valid.poke(false.B)
            dut.io.commit_write_en.poke(false.B)
            dut.io.csr_cmd.poke(CSR_CMD.RS.U)
            dut.io.rs1_id.poke(0.U)
            dut.io.rd_id.poke(1.U)
            dut.clock.step(1)
            dut.io.csr_old_data.expect(BigInt("344", 16).U)
        }
    }

    "CSRFile should count retired instructions in coreinst CSR" in {
        simulate(new CSRFile(64)) { dut =>
            clearHpmEvents(dut)
            dut.io.csr_addr.poke(CSRMAP.coreinst.U)
            dut.io.csr_cmd.poke(CSR_CMD.RS.U)
            dut.io.csr_reg_data.poke(0.U)
            dut.io.rs1_id.poke(0.U)
            dut.io.rd_id.poke(1.U)
            dut.io.commit_valid.poke(false.B)
            dut.io.commit_addr.poke(0.U)
            dut.io.commit_wdata.poke(0.U)
            dut.io.commit_write_en.poke(false.B)
            dut.io.retire_valid.poke(false.B)
            dut.io.machineTimerInterrupt.poke(false.B)
            dut.io.machineExternalInterrupt.poke(false.B)
            dut.io.trap.valid.poke(false.B)
            dut.io.trap.is_interrupt.poke(false.B)
            dut.io.trap.cause.poke(0.U)
            dut.io.trap.pc.poke(0.U)
            dut.io.trap.tval.poke(0.U)
            dut.io.mret_commit.poke(false.B)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)
            dut.io.csr_old_data.expect(0.U)

            dut.io.retire_valid.poke(true.B)
            dut.clock.step(3)
            dut.io.retire_valid.poke(false.B)

            dut.io.csr_old_data.expect(3.U)
        }
    }

    "CSRFile should expose writable mcycle and minstret counters" in {
        simulate(new CSRFile(64)) { dut =>
            clearHpmEvents(dut)
            dut.io.csr_addr.poke(CSRMAP.mcycle.U)
            dut.io.csr_cmd.poke(CSR_CMD.RS.U)
            dut.io.csr_reg_data.poke(0.U)
            dut.io.rs1_id.poke(0.U)
            dut.io.rd_id.poke(1.U)
            dut.io.commit_valid.poke(false.B)
            dut.io.commit_addr.poke(0.U)
            dut.io.commit_wdata.poke(0.U)
            dut.io.commit_write_en.poke(false.B)
            dut.io.retire_valid.poke(false.B)
            dut.io.machineTimerInterrupt.poke(false.B)
            dut.io.machineExternalInterrupt.poke(false.B)
            dut.io.trap.valid.poke(false.B)
            dut.io.trap.is_interrupt.poke(false.B)
            dut.io.trap.cause.poke(0.U)
            dut.io.trap.pc.poke(0.U)
            dut.io.trap.tval.poke(0.U)
            dut.io.mret_commit.poke(false.B)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            val firstCycle = dut.io.csr_old_data.peek().litValue
            dut.clock.step(3)
            dut.io.csr_old_data.expect((firstCycle + 3).U)

            dut.io.csr_addr.poke(CSRMAP.minstret.U)
            dut.io.retire_valid.poke(true.B)
            dut.clock.step(4)
            dut.io.retire_valid.poke(false.B)
            dut.io.csr_old_data.expect(4.U)

            dut.io.commit_valid.poke(true.B)
            dut.io.commit_write_en.poke(true.B)
            dut.io.commit_addr.poke(CSRMAP.minstret.U)
            dut.io.commit_wdata.poke(100.U)
            dut.clock.step(1)
            dut.io.commit_valid.poke(false.B)
            dut.io.commit_write_en.poke(false.B)
            dut.io.csr_old_data.expect(100.U)

            dut.io.csr_addr.poke(CSRMAP.cycle.U)
            dut.clock.step(1)
            assert(dut.io.csr_old_data.peek().litValue > firstCycle)
            dut.io.csr_addr.poke(CSRMAP.instret.U)
            dut.io.csr_old_data.expect(100.U)
        }
    }

    "CSRFile should program, count, inhibit, and overwrite HPM counters" in {
        simulate(new CSRFile(64)) { dut =>
            clearHpmEvents(dut)
            dut.io.csr_addr.poke(CSRMAP.mhpmcounter3.U)
            dut.io.csr_cmd.poke(CSR_CMD.RS.U)
            dut.io.csr_reg_data.poke(0.U)
            dut.io.rs1_id.poke(0.U)
            dut.io.rd_id.poke(1.U)
            dut.io.commit_valid.poke(false.B)
            dut.io.commit_addr.poke(0.U)
            dut.io.commit_wdata.poke(0.U)
            dut.io.commit_write_en.poke(false.B)
            dut.io.retire_valid.poke(false.B)
            dut.io.machineTimerInterrupt.poke(false.B)
            dut.io.machineExternalInterrupt.poke(false.B)
            dut.io.trap.valid.poke(false.B)
            dut.io.trap.is_interrupt.poke(false.B)
            dut.io.trap.cause.poke(0.U)
            dut.io.trap.pc.poke(0.U)
            dut.io.trap.tval.poke(0.U)
            dut.io.mret_commit.poke(false.B)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)

            dut.io.commit_valid.poke(true.B)
            dut.io.commit_write_en.poke(true.B)
            dut.io.commit_addr.poke(CSRMAP.mhpmevent3.U)
            dut.io.commit_wdata.poke(BREEZE_HPM_EVENT.CONTROL_RETIRED.U)
            dut.clock.step(1)
            dut.io.commit_valid.poke(false.B)
            dut.io.commit_write_en.poke(false.B)

            dut.io.hpmEvents.controlRetired.poke(true.B)
            dut.clock.step(3)
            dut.io.hpmEvents.controlRetired.poke(false.B)
            dut.io.csr_old_data.expect(3.U)

            dut.io.commit_valid.poke(true.B)
            dut.io.commit_write_en.poke(true.B)
            dut.io.commit_addr.poke(CSRMAP.mcountinhibit.U)
            dut.io.commit_wdata.poke((BigInt(1) << 3).U)
            dut.clock.step(1)
            dut.io.commit_valid.poke(false.B)
            dut.io.commit_write_en.poke(false.B)
            dut.io.hpmEvents.controlRetired.poke(true.B)
            dut.clock.step(2)
            dut.io.hpmEvents.controlRetired.poke(false.B)
            dut.io.csr_old_data.expect(3.U)

            dut.io.commit_valid.poke(true.B)
            dut.io.commit_write_en.poke(true.B)
            dut.io.commit_addr.poke(CSRMAP.mhpmcounter3.U)
            dut.io.commit_wdata.poke(9.U)
            dut.clock.step(1)
            dut.io.commit_valid.poke(false.B)
            dut.io.commit_write_en.poke(false.B)
            dut.io.csr_addr.poke(CSRMAP.hpmcounter3.U)
            dut.io.csr_old_data.expect(9.U)

            dut.io.commit_valid.poke(true.B)
            dut.io.commit_write_en.poke(true.B)
            dut.io.commit_addr.poke(CSRMAP.mhpmevent3.U)
            dut.io.commit_wdata.poke(99.U)
            dut.clock.step(1)
            dut.io.commit_valid.poke(false.B)
            dut.io.commit_write_en.poke(false.B)
            dut.io.csr_addr.poke(CSRMAP.mhpmevent3.U)
            dut.io.csr_old_data.expect(BREEZE_HPM_EVENT.NONE.U)
        }
    }
}

class BreezeCoreNoFASECustomInstrSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
    private val RomBase = BreezeMcuPlatform.ResetVector
    private val nopInst = BigInt("00000013", 16)
    private val estopInst = BigInt("7ff00073", 16)
    private case class PendingIcacheResp(paddr: BigInt, data: BigInt, cyclesLeft: Int)

    private def encodeAddi(rd: Int, rs1: Int, imm: Int): BigInt = {
        val imm12 = imm & 0xfff
        (BigInt(imm12) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(0) << 12) |
        (BigInt(rd) << 7) |
        BigInt(0x13)
    }

    private def encodeRType(rd: Int, rs1: Int, rs2: Int, funct3: Int, funct7: Int): BigInt = {
        (BigInt(funct7) << 25) |
        (BigInt(rs2) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(funct3) << 12) |
        (BigInt(rd) << 7) |
        BigInt(0x33)
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

    "ESTOP through the full frontend holds halted state and prevents younger retirement" in {
        simulate(new BreezeCore(BreezeCoreConfig(useFASE = false, enableTandem = true), enabledebug = true)) { dut =>
            val words = Seq(encodeAddi(1,0,1), estopInst, encodeAddi(2,0,99)) ++ Seq.fill(5)(nopInst)
            dut.io.l1d.req.ready.poke(true.B); dut.io.l1d.s2Hold.poke(false.B)
            dut.io.l1d.resp.valid.poke(false.B); dut.io.l1d.resp.bits.kind.poke(L1DRespKind.Done)
            dut.io.l1d.resp.bits.data.poke(0.U); dut.io.l1d.resp.bits.excCause.poke(0.U); dut.io.l1d.resp.bits.tval.poke(0.U)
            dut.io.l1d.late.valid.poke(false.B); dut.io.l1d.late.bits.rd.idx.poke(0.U)
            dut.io.l1d.late.bits.rd.isFp.poke(false.B); dut.io.l1d.late.bits.data.poke(0.U); dut.io.l1d.late.bits.error.poke(false.B)
            dut.io.l1d.drained.poke(true.B); dut.io.l1d.mmioBusy.poke(false.B); dut.io.mmuIdle.poke(true.B)
            dut.io.machineSoftwareInterrupt.poke(false.B); dut.io.supervisorExternalInterrupt.poke(false.B); dut.io.time.poke(0.U)
            dut.io.dcacheHpm.mulSourceStall.poke(false.B); dut.io.dcacheHpm.divSourceStall.poke(false.B); dut.io.dcacheHpm.wbPortConflict.poke(0.U)
            dut.io.resetAddr.poke(RomBase.U)
            dut.io.machineTimerInterrupt.poke(false.B); dut.io.externalInterrupts.poke(0.U)
            dut.io.nextLevelRsp.vld.poke(false.B); dut.io.nextLevelRsp.data.poke(0.U); dut.io.nextLevelRsp.error.poke(false.B)
            dut.io.dmem.rsp.valid.poke(false.B); dut.io.dmem.rsp.data.poke(0.U)
            dut.io.dmem.rsp.isWriteAck.poke(false.B); dut.io.dmem.rsp.error.poke(false.B)
            dut.io.dcacheFlushDone.poke(true.B)
            dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
            var pending = -1; var halted = false; var stoppedCommits = 0
            for (_ <- 0 until 150) {
                dut.io.nextLevelRsp.vld.poke((pending == 0).B)
                dut.io.nextLevelRsp.data.poke(buildRefillLine(words).U)
                if (dut.io.nextLevelReq.req.peek().litToBoolean && pending < 0) pending = 3
                val trace = dut.io.tandem.get
                if (trace.valid.peek().litToBoolean) {
                    assert(!halted, "younger retirement after ESTOP")
                    assert(trace.pc.peek().litValue <= RomBase+4)
                    if (trace.estop.peek().litToBoolean) stoppedCommits += 1
                }
                if (halted) dut.io.estop.expect(true.B)
                dut.clock.step()
                if (stoppedCommits != 0) halted = true
                if (pending >= 0) pending -= 1
            }
            halted mustBe true; stoppedCommits mustBe 1
        }
    }
}

class BreezeCoreNoFASESpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
    private val RomBase = BreezeMcuPlatform.ResetVector
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

    private def encodeCsr(rd: Int, rs1: Int, csr: Int, funct3: Int): BigInt = {
        (BigInt(csr & 0xfff) << 20) |
        (BigInt(rs1) << 15) |
        (BigInt(funct3) << 12) |
        (BigInt(rd) << 7) |
        BigInt(0x73)
    }

    private def encodeLui(rd: Int, imm20: Int): BigInt = {
        (BigInt(imm20 & 0xfffff) << 12) |
        (BigInt(rd) << 7) |
        BigInt(0x37)
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

            dut.io.l1d.req.ready.poke(true.B); dut.io.l1d.s2Hold.poke(false.B)
            dut.io.l1d.resp.valid.poke(false.B); dut.io.l1d.resp.bits.kind.poke(L1DRespKind.Done)
            dut.io.l1d.resp.bits.data.poke(0.U); dut.io.l1d.resp.bits.excCause.poke(0.U); dut.io.l1d.resp.bits.tval.poke(0.U)
            dut.io.l1d.late.valid.poke(false.B); dut.io.l1d.late.bits.rd.idx.poke(0.U)
            dut.io.l1d.late.bits.rd.isFp.poke(false.B); dut.io.l1d.late.bits.data.poke(0.U); dut.io.l1d.late.bits.error.poke(false.B)
            dut.io.l1d.drained.poke(true.B); dut.io.l1d.mmioBusy.poke(false.B); dut.io.mmuIdle.poke(true.B)
            dut.io.machineSoftwareInterrupt.poke(false.B); dut.io.supervisorExternalInterrupt.poke(false.B); dut.io.time.poke(0.U)
            dut.io.dcacheHpm.mulSourceStall.poke(false.B); dut.io.dcacheHpm.divSourceStall.poke(false.B); dut.io.dcacheHpm.wbPortConflict.poke(0.U)
            dut.io.resetAddr.poke(RomBase.U)
            dut.io.machineTimerInterrupt.poke(false.B)
            dut.io.externalInterrupts.poke(0.U)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0.U)
            dut.io.dmem.rsp.valid.poke(false.B)
            dut.io.dmem.rsp.data.poke(0.U)
            dut.io.dmem.rsp.isWriteAck.poke(false.B)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            logCoreTimeline(dut, cycle, "after-reset")
            cycle += 1

            frontendDebug.s1_pcReg.expect(RomBase.U)
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
            frontendDebug.s2_pcReg.expect(RomBase.U)
            frontendDebug.s3_valid.expect(false.B)
            backendDebug.decodeValid.expect(false.B)

            val missAddr = dut.io.nextLevelReq.paddr.peek().litValue
            missAddr mustBe RomBase

            for (waitIdx <- 0 until refillDelayCycles) {
                logCoreTimeline(dut, cycle, s"stall-in-s2-$waitIdx")
                dut.io.nextLevelRsp.vld.expect(false.B)
                frontendDebug.s2_valid.expect(true.B)
                frontendDebug.s2_pcReg.expect(RomBase.U)
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
            frontendDebug.s2_pcReg.expect((RomBase + 4).U)

            dut.clock.step(1)
            cycle += 1

            logCoreTimeline(dut, cycle, "s3-visible")
            frontendDebug.s3_valid.expect(true.B)
            frontendDebug.s3_pcReg.expect((RomBase + 4).U)
            frontendDebug.s2_valid.expect(true.B)
            frontendDebug.s2_pcReg.expect((RomBase + 8).U)

            stepUntil(dut) {
                backendDebug.decodeValid.peek().litToBoolean && backendDebug.decodePc.peek().litValue == RomBase
            }
            logCoreTimeline(dut, cycle, "decode-pc0")
            backendDebug.decodeInst.expect(refillInstWords.head.U)

            dut.clock.step(1)
            cycle += 1
            logCoreTimeline(dut, cycle, "decode-pc4-idexe-pc0")
            backendDebug.idExeValid.expect(true.B)
            backendDebug.decodeValid.expect(true.B)
            backendDebug.decodePc.expect((RomBase + 4).U)
            backendDebug.decodeInst.expect(refillInstWords(1).U)

            dut.clock.step(1)
            cycle += 1
            logCoreTimeline(dut, cycle, "decode-pc8-exemem-pc0")
            backendDebug.idExeValid.expect(true.B)
            backendDebug.exeMemValid.expect(true.B)
            backendDebug.decodeValid.expect(true.B)
            backendDebug.decodePc.expect((RomBase + 8).U)
            backendDebug.decodeInst.expect(refillInstWords(2).U)

            dut.clock.step(1)
            cycle += 1
            logCoreTimeline(dut, cycle, "decode-pc12-memwb-pc0")
            backendDebug.idExeValid.expect(true.B)
            backendDebug.exeMemValid.expect(true.B)
            backendDebug.memWbValid.expect(true.B)
            backendDebug.wbData.expect(1.U)
            backendDebug.decodeValid.expect(true.B)
            backendDebug.decodePc.expect((RomBase + 12).U)
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
            val branchPc = RomBase + 0x8
            val targetPc = RomBase + 0x40
            val branchInst = encodeBranch(rs1 = 1, rs2 = 2, imm = (targetPc - branchPc).toInt, funct3 = 0)
            val targetFirstInst = encodeAddi(rd = 5, rs1 = 0, imm = 42)
            val fallbackLine = buildRefillLine(Seq.fill(8)(BigInt("deadbeef", 16)))
            val lineMap = Map(
                RomBase -> buildRefillLine(Seq(
                    encodeAddi(rd = 1, rs1 = 0, imm = 1),
                    encodeAddi(rd = 2, rs1 = 0, imm = 1),
                    branchInst,
                    nopInst,
                    nopInst,
                    nopInst,
                    nopInst,
                    nopInst
                )),
                (RomBase + 0x20) -> buildRefillLine(Seq.fill(8)(nopInst)),
                (RomBase + 0x40) -> buildRefillLine(Seq(
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

            dut.io.l1d.req.ready.poke(true.B); dut.io.l1d.s2Hold.poke(false.B)
            dut.io.l1d.resp.valid.poke(false.B); dut.io.l1d.resp.bits.kind.poke(L1DRespKind.Done)
            dut.io.l1d.resp.bits.data.poke(0.U); dut.io.l1d.resp.bits.excCause.poke(0.U); dut.io.l1d.resp.bits.tval.poke(0.U)
            dut.io.l1d.late.valid.poke(false.B); dut.io.l1d.late.bits.rd.idx.poke(0.U)
            dut.io.l1d.late.bits.rd.isFp.poke(false.B); dut.io.l1d.late.bits.data.poke(0.U); dut.io.l1d.late.bits.error.poke(false.B)
            dut.io.l1d.drained.poke(true.B); dut.io.l1d.mmioBusy.poke(false.B); dut.io.mmuIdle.poke(true.B)
            dut.io.machineSoftwareInterrupt.poke(false.B); dut.io.supervisorExternalInterrupt.poke(false.B); dut.io.time.poke(0.U)
            dut.io.dcacheHpm.mulSourceStall.poke(false.B); dut.io.dcacheHpm.divSourceStall.poke(false.B); dut.io.dcacheHpm.wbPortConflict.poke(0.U)
            dut.io.resetAddr.poke(RomBase.U)
            dut.io.machineTimerInterrupt.poke(false.B)
            dut.io.externalInterrupts.poke(0.U)
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
            observedReqs.contains(RomBase) mustBe true
        }
    }

    "BreezeCore should trap on an illegal ROM instruction and jump to mtvec" in {
        simulate(new BreezeCore(BreezeCoreConfig(useFASE = false), enabledebug = true)) { dut =>
            val backendDebug = dut.io.debug.get
            val refillDelayCycles = 6

            val line0x800 = buildRefillLine(Seq(
                encodeLui(1, (RomBase >> 12).toInt),   encodeCsr(0, 1, CSRMAP.mtvec, 1),
                encodeAddi(1, 0, 1),                   encodeAddi(2, 0, 2),
                encodeAddi(3, 0, 3),                   encodeAddi(4, 0, 4),
                encodeAddi(5, 0, 5),                   BigInt("FFFFFFFF", 16)
            ))
            val line0x820 = buildRefillLine(Seq(
                encodeAddi(6, 0, 6),   encodeAddi(7, 0, 7),
                encodeAddi(8, 0, 8),   encodeAddi(9, 0, 9),
                encodeAddi(10, 0, 10), encodeAddi(11, 0, 11),
                encodeAddi(12, 0, 12), encodeAddi(13, 0, 13)
            ))
            val line0x200 = buildRefillLine(Seq(
                encodeAddi(1, 0, 0),
                encodeAddi(2, 0, 1),
                BigInt("7FF00073", 16),
                nopInst, nopInst, nopInst, nopInst, nopInst
            ))
            val memoryMap = Map(
                (RomBase + 0x800) -> line0x800,
                (RomBase + 0x820) -> line0x820,
                RomBase -> line0x200
            )
            val defaultLine = buildRefillLine(Seq.fill(8)(nopInst))
            val pendingIcacheResps = mutable.Queue.empty[PendingIcacheResp]
            var prevIcacheReq = false

            dut.io.l1d.req.ready.poke(true.B); dut.io.l1d.s2Hold.poke(false.B)
            dut.io.l1d.resp.valid.poke(false.B); dut.io.l1d.resp.bits.kind.poke(L1DRespKind.Done)
            dut.io.l1d.resp.bits.data.poke(0.U); dut.io.l1d.resp.bits.excCause.poke(0.U); dut.io.l1d.resp.bits.tval.poke(0.U)
            dut.io.l1d.late.valid.poke(false.B); dut.io.l1d.late.bits.rd.idx.poke(0.U)
            dut.io.l1d.late.bits.rd.isFp.poke(false.B); dut.io.l1d.late.bits.data.poke(0.U); dut.io.l1d.late.bits.error.poke(false.B)
            dut.io.l1d.drained.poke(true.B); dut.io.l1d.mmioBusy.poke(false.B); dut.io.mmuIdle.poke(true.B)
            dut.io.machineSoftwareInterrupt.poke(false.B); dut.io.supervisorExternalInterrupt.poke(false.B); dut.io.time.poke(0.U)
            dut.io.dcacheHpm.mulSourceStall.poke(false.B); dut.io.dcacheHpm.divSourceStall.poke(false.B); dut.io.dcacheHpm.wbPortConflict.poke(0.U)
            dut.io.resetAddr.poke((RomBase + 0x800).U)
            dut.io.machineTimerInterrupt.poke(false.B)
            dut.io.externalInterrupts.poke(0.U)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0.U)
            dut.io.dmem.rsp.valid.poke(false.B)
            dut.io.dmem.rsp.data.poke(0.U)
            dut.io.dmem.rsp.isWriteAck.poke(false.B)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)
            dut.clock.step(1)

            val retiredPcs = mutable.ArrayBuffer.empty[BigInt]
            var illegalTrapSeen = false
            var estopSeen = false
            var cycle = 0

            while (!estopSeen && cycle < 500) {
                val reqValid = dut.io.nextLevelReq.req.peek().litToBoolean
                if (reqValid && !prevIcacheReq) {
                    val reqAddr = dut.io.nextLevelReq.paddr.peek().litValue
                    pendingIcacheResps.enqueue(
                        PendingIcacheResp(reqAddr, memoryMap.getOrElse(reqAddr, defaultLine), refillDelayCycles)
                    )
                }
                prevIcacheReq = reqValid

                if (pendingIcacheResps.headOption.exists(_.cyclesLeft == 0)) {
                    val resp = pendingIcacheResps.dequeue()
                    dut.io.nextLevelRsp.vld.poke(true.B)
                    dut.io.nextLevelRsp.data.poke(resp.data.U)
                } else {
                    dut.io.nextLevelRsp.vld.poke(false.B)
                    dut.io.nextLevelRsp.data.poke(0.U)
                }

                if (backendDebug.memWbTrapValid.peek().litToBoolean) {
                    illegalTrapSeen = true
                    backendDebug.memWbPc.expect((RomBase + 0x81c).U)
                }
                if (backendDebug.memWbValid.peek().litToBoolean) {
                    retiredPcs += backendDebug.memWbPc.peek().litValue
                }

                if (dut.io.estop.peek().litToBoolean) {
                    estopSeen = true
                }

                dut.clock.step(1)
                cycle += 1

                val updated = pendingIcacheResps.map(r => r.copy(cyclesLeft = math.max(r.cyclesLeft - 1, 0)))
                pendingIcacheResps.clear()
                pendingIcacheResps ++= updated
            }

            estopSeen mustBe true
            illegalTrapSeen mustBe true
            backendDebug.csrMcause.expect(2.U)
            backendDebug.csrMepc.expect((RomBase + 0x81c).U)
            retiredPcs mustBe Seq(
                RomBase + 0x800, RomBase + 0x804, RomBase + 0x808,
                RomBase + 0x80c, RomBase + 0x810, RomBase + 0x814,
                RomBase + 0x818, // faulting 0x81c traps without commit
                RomBase, RomBase + 0x4, RomBase + 0x8
            )
        }
    }

    "BreezeCore should set correct mcause/mepc/mtvec on illegal instruction exception" in {
        simulate(new BreezeCore(BreezeCoreConfig(useFASE = false), enabledebug = true)) { dut =>
            val backendDebug = dut.io.debug.get
            val refillDelayCycles = 6

            val line0x800 = buildRefillLine(Seq(
                encodeLui(1, (RomBase >> 12).toInt),   encodeCsr(0, 1, CSRMAP.mtvec, 1),
                encodeAddi(1, 0, 1),                   encodeAddi(2, 0, 2),
                encodeAddi(3, 0, 3),                   encodeAddi(4, 0, 4),
                encodeAddi(5, 0, 5),                   BigInt("FFFFFFFF", 16)
            ))
            val line0x820 = buildRefillLine(Seq(
                encodeAddi(6, 0, 6),   encodeAddi(7, 0, 7),
                encodeAddi(8, 0, 8),   encodeAddi(9, 0, 9),
                encodeAddi(10, 0, 10), encodeAddi(11, 0, 11),
                encodeAddi(12, 0, 12), encodeAddi(13, 0, 13)
            ))
            val line0x200 = buildRefillLine(Seq(
                encodeAddi(1, 0, 0),
                encodeAddi(2, 0, 1),
                BigInt("7FF00073", 16),
                nopInst, nopInst, nopInst, nopInst, nopInst
            ))
            val memoryMap = Map(
                (RomBase + 0x800) -> line0x800,
                (RomBase + 0x820) -> line0x820,
                RomBase -> line0x200
            )
            val defaultLine = buildRefillLine(Seq.fill(8)(nopInst))
            val pendingIcacheResps = mutable.Queue.empty[PendingIcacheResp]
            var prevIcacheReq = false

            dut.io.l1d.req.ready.poke(true.B); dut.io.l1d.s2Hold.poke(false.B)
            dut.io.l1d.resp.valid.poke(false.B); dut.io.l1d.resp.bits.kind.poke(L1DRespKind.Done)
            dut.io.l1d.resp.bits.data.poke(0.U); dut.io.l1d.resp.bits.excCause.poke(0.U); dut.io.l1d.resp.bits.tval.poke(0.U)
            dut.io.l1d.late.valid.poke(false.B); dut.io.l1d.late.bits.rd.idx.poke(0.U)
            dut.io.l1d.late.bits.rd.isFp.poke(false.B); dut.io.l1d.late.bits.data.poke(0.U); dut.io.l1d.late.bits.error.poke(false.B)
            dut.io.l1d.drained.poke(true.B); dut.io.l1d.mmioBusy.poke(false.B); dut.io.mmuIdle.poke(true.B)
            dut.io.machineSoftwareInterrupt.poke(false.B); dut.io.supervisorExternalInterrupt.poke(false.B); dut.io.time.poke(0.U)
            dut.io.dcacheHpm.mulSourceStall.poke(false.B); dut.io.dcacheHpm.divSourceStall.poke(false.B); dut.io.dcacheHpm.wbPortConflict.poke(0.U)
            dut.io.resetAddr.poke((RomBase + 0x800).U)
            dut.io.machineTimerInterrupt.poke(false.B)
            dut.io.externalInterrupts.poke(0.U)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0.U)
            dut.io.dmem.rsp.valid.poke(false.B)
            dut.io.dmem.rsp.data.poke(0.U)
            dut.io.dmem.rsp.isWriteAck.poke(false.B)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)
            dut.clock.step(1)

            var exceptionSeen = false
            var exceptionChecked = false
            var estopSeen = false
            var cycle = 0

            while (!estopSeen && cycle < 500) {
                val reqValid = dut.io.nextLevelReq.req.peek().litToBoolean
                if (reqValid && !prevIcacheReq) {
                    val reqAddr = dut.io.nextLevelReq.paddr.peek().litValue
                    pendingIcacheResps.enqueue(
                        PendingIcacheResp(reqAddr, memoryMap.getOrElse(reqAddr, defaultLine), refillDelayCycles)
                    )
                }
                prevIcacheReq = reqValid

                if (pendingIcacheResps.headOption.exists(_.cyclesLeft == 0)) {
                    val resp = pendingIcacheResps.dequeue()
                    dut.io.nextLevelRsp.vld.poke(true.B)
                    dut.io.nextLevelRsp.data.poke(resp.data.U)
                } else {
                    dut.io.nextLevelRsp.vld.poke(false.B)
                    dut.io.nextLevelRsp.data.poke(0.U)
                }

                if (!exceptionSeen && backendDebug.memWbException.peek().litToBoolean) {
                    backendDebug.csrMtvec.expect(RomBase.U)
                    exceptionSeen = true
                } else if (exceptionSeen && !exceptionChecked) {
                    backendDebug.csrMcause.expect(2.U)
                    backendDebug.csrMepc.expect((RomBase + 0x81c).U)
                    exceptionChecked = true
                }

                if (dut.io.estop.peek().litToBoolean) {
                    estopSeen = true
                }

                dut.clock.step(1)
                cycle += 1

                val updated = pendingIcacheResps.map(r => r.copy(cyclesLeft = math.max(r.cyclesLeft - 1, 0)))
                pendingIcacheResps.clear()
                pendingIcacheResps ++= updated
            }

            estopSeen mustBe true
            exceptionSeen mustBe true
            exceptionChecked mustBe true
        }
    }

    "BreezeCore should boot from a non-zero executable resetAddr" in {
        simulate(new BreezeCore(BreezeCoreConfig(useFASE = false), enabledebug = true)) { dut =>
            val backendDebug = dut.io.debug.get
            val refillDelayCycles = 6

            // Program at ROM base + 0x800: 4 addi + ESTOP
            val line0x800 = buildRefillLine(Seq(
                encodeAddi(1, 0, 1), encodeAddi(2, 0, 2),
                encodeAddi(3, 0, 3), encodeAddi(4, 0, 4),
                BigInt("7FF00073", 16), nopInst, nopInst, nopInst
            ))
            // Keep a second legal ROM line to catch an incorrect base-only fetch.
            val line0x00 = buildRefillLine(Seq(
                encodeAddi(1, 0, 1), encodeAddi(2, 0, 2),
                encodeAddi(3, 0, 3), encodeAddi(4, 0, 4),
                BigInt("7FF00073", 16), nopInst, nopInst, nopInst
            ))
            val memoryMap = Map(
                RomBase -> line0x00,
                (RomBase + 0x800) -> line0x800
            )
            val defaultLine = buildRefillLine(Seq.fill(8)(nopInst))
            val pendingIcacheResps = mutable.Queue.empty[PendingIcacheResp]
            var prevIcacheReq = false

            dut.io.l1d.req.ready.poke(true.B); dut.io.l1d.s2Hold.poke(false.B)
            dut.io.l1d.resp.valid.poke(false.B); dut.io.l1d.resp.bits.kind.poke(L1DRespKind.Done)
            dut.io.l1d.resp.bits.data.poke(0.U); dut.io.l1d.resp.bits.excCause.poke(0.U); dut.io.l1d.resp.bits.tval.poke(0.U)
            dut.io.l1d.late.valid.poke(false.B); dut.io.l1d.late.bits.rd.idx.poke(0.U)
            dut.io.l1d.late.bits.rd.isFp.poke(false.B); dut.io.l1d.late.bits.data.poke(0.U); dut.io.l1d.late.bits.error.poke(false.B)
            dut.io.l1d.drained.poke(true.B); dut.io.l1d.mmioBusy.poke(false.B); dut.io.mmuIdle.poke(true.B)
            dut.io.machineSoftwareInterrupt.poke(false.B); dut.io.supervisorExternalInterrupt.poke(false.B); dut.io.time.poke(0.U)
            dut.io.dcacheHpm.mulSourceStall.poke(false.B); dut.io.dcacheHpm.divSourceStall.poke(false.B); dut.io.dcacheHpm.wbPortConflict.poke(0.U)
            dut.io.resetAddr.poke((RomBase + 0x800).U)
            dut.io.machineTimerInterrupt.poke(false.B)
            dut.io.externalInterrupts.poke(0.U)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0.U)
            dut.io.dmem.rsp.valid.poke(false.B)
            dut.io.dmem.rsp.data.poke(0.U)
            dut.io.dmem.rsp.isWriteAck.poke(false.B)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)
            dut.clock.step(1)

            val retiredPcs = mutable.ArrayBuffer.empty[BigInt]
            var estopSeen = false
            var cycle = 0

            while (!estopSeen && cycle < 500) {
                val reqValid = dut.io.nextLevelReq.req.peek().litToBoolean
                if (reqValid && !prevIcacheReq) {
                    val reqAddr = dut.io.nextLevelReq.paddr.peek().litValue
                    pendingIcacheResps.enqueue(
                        PendingIcacheResp(reqAddr, memoryMap.getOrElse(reqAddr, defaultLine), refillDelayCycles)
                    )
                }
                prevIcacheReq = reqValid

                if (pendingIcacheResps.headOption.exists(_.cyclesLeft == 0)) {
                    val resp = pendingIcacheResps.dequeue()
                    dut.io.nextLevelRsp.vld.poke(true.B)
                    dut.io.nextLevelRsp.data.poke(resp.data.U)
                } else {
                    dut.io.nextLevelRsp.vld.poke(false.B)
                    dut.io.nextLevelRsp.data.poke(0.U)
                }

                if (backendDebug.memWbValid.peek().litToBoolean) {
                    retiredPcs += backendDebug.memWbPc.peek().litValue
                }

                if (dut.io.estop.peek().litToBoolean) {
                    estopSeen = true
                }

                dut.clock.step(1)
                cycle += 1

                val updated = pendingIcacheResps.map(r => r.copy(cyclesLeft = math.max(r.cyclesLeft - 1, 0)))
                pendingIcacheResps.clear()
                pendingIcacheResps ++= updated
            }

            estopSeen mustBe true
            // The first retired PC must be the exact non-zero reset address.
            retiredPcs.headOption mustBe Some(RomBase + 0x800)
        }
    }

    "BreezeCore should trap on ecall with correct mcause/mepc and enter handler" in {
        simulate(new BreezeCore(BreezeCoreConfig(useFASE = false), enabledebug = true)) { dut =>
            val backendDebug = dut.io.debug.get
            val refillDelayCycles = 6
            val ecallInst = BigInt("00000073", 16)

            // Main program @ ROM base + 0x800: set mtvec to ROM base, then ecall.
            val line0x800 = buildRefillLine(Seq(
                encodeLui(1, (RomBase >> 12).toInt), encodeCsr(0, 1, CSRMAP.mtvec, 1),
                ecallInst,                    encodeAddi(2, 0, 1),
                encodeAddi(3, 0, 2),         BigInt("7FF00073", 16),
                nopInst,                      nopInst
            ))
            // Handler @ ROM base: just ESTOP (verifies trap entered handler)
            val line0x200 = buildRefillLine(Seq(
                encodeAddi(1, 0, 0),  // nop-like
                encodeAddi(2, 0, 0),
                BigInt("7FF00073", 16), // ESTOP
                nopInst, nopInst, nopInst, nopInst, nopInst
            ))
            val memoryMap = Map(
                RomBase -> line0x200,
                (RomBase + 0x800) -> line0x800
            )
            val defaultLine = buildRefillLine(Seq.fill(8)(nopInst))
            val pendingIcacheResps = mutable.Queue.empty[PendingIcacheResp]
            var prevIcacheReq = false

            dut.io.l1d.req.ready.poke(true.B); dut.io.l1d.s2Hold.poke(false.B)
            dut.io.l1d.resp.valid.poke(false.B); dut.io.l1d.resp.bits.kind.poke(L1DRespKind.Done)
            dut.io.l1d.resp.bits.data.poke(0.U); dut.io.l1d.resp.bits.excCause.poke(0.U); dut.io.l1d.resp.bits.tval.poke(0.U)
            dut.io.l1d.late.valid.poke(false.B); dut.io.l1d.late.bits.rd.idx.poke(0.U)
            dut.io.l1d.late.bits.rd.isFp.poke(false.B); dut.io.l1d.late.bits.data.poke(0.U); dut.io.l1d.late.bits.error.poke(false.B)
            dut.io.l1d.drained.poke(true.B); dut.io.l1d.mmioBusy.poke(false.B); dut.io.mmuIdle.poke(true.B)
            dut.io.machineSoftwareInterrupt.poke(false.B); dut.io.supervisorExternalInterrupt.poke(false.B); dut.io.time.poke(0.U)
            dut.io.dcacheHpm.mulSourceStall.poke(false.B); dut.io.dcacheHpm.divSourceStall.poke(false.B); dut.io.dcacheHpm.wbPortConflict.poke(0.U)
            dut.io.resetAddr.poke((RomBase + 0x800).U)
            dut.io.machineTimerInterrupt.poke(false.B)
            dut.io.externalInterrupts.poke(0.U)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0.U)
            dut.io.dmem.rsp.valid.poke(false.B)
            dut.io.dmem.rsp.data.poke(0.U)
            dut.io.dmem.rsp.isWriteAck.poke(false.B)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)
            dut.clock.step(1)

            val retiredPcs = mutable.ArrayBuffer.empty[BigInt]
            var trapSeen = false
            var csrChecked = false
            var estopSeen = false
            var cycle = 0

            while (!estopSeen && cycle < 500) {
                val reqValid = dut.io.nextLevelReq.req.peek().litToBoolean
                if (reqValid && !prevIcacheReq) {
                    val reqAddr = dut.io.nextLevelReq.paddr.peek().litValue
                    pendingIcacheResps.enqueue(
                        PendingIcacheResp(reqAddr, memoryMap.getOrElse(reqAddr, defaultLine), refillDelayCycles)
                    )
                }
                prevIcacheReq = reqValid

                if (pendingIcacheResps.headOption.exists(_.cyclesLeft == 0)) {
                    val resp = pendingIcacheResps.dequeue()
                    dut.io.nextLevelRsp.vld.poke(true.B)
                    dut.io.nextLevelRsp.data.poke(resp.data.U)
                } else {
                    dut.io.nextLevelRsp.vld.poke(false.B)
                    dut.io.nextLevelRsp.data.poke(0.U)
                }

                if (!trapSeen && backendDebug.memWbTrapValid.peek().litToBoolean &&
                    backendDebug.memWbIsEcall.peek().litToBoolean) {
                    trapSeen = true
                } else if (trapSeen && !csrChecked) {
                    // After ecall trap: verify CSRs set correctly
                    backendDebug.csrMcause.expect(11.U)          // Environment call from M-mode
                    backendDebug.csrMepc.expect((RomBase + 0x808).U) // PC of ecall
                    backendDebug.csrMtvec.expect(RomBase.U)          // handler address
                    csrChecked = true
                }

                if (backendDebug.memWbValid.peek().litToBoolean) {
                    retiredPcs += backendDebug.memWbPc.peek().litValue
                }

                if (dut.io.estop.peek().litToBoolean) {
                    estopSeen = true
                }

                dut.clock.step(1)
                cycle += 1

                val updated = pendingIcacheResps.map(r => r.copy(cyclesLeft = math.max(r.cyclesLeft - 1, 0)))
                pendingIcacheResps.clear()
                pendingIcacheResps ++= updated
            }

            estopSeen mustBe true
            trapSeen mustBe true
            csrChecked mustBe true
            // Key PCs: main → ecall → handler.
            retiredPcs must not contain (RomBase + 0x808)
            retiredPcs must contain(RomBase)
        }
    }

    "BreezeCore should handle ecall → handler(advance mepc) → mret → return to next instruction" in {
        simulate(new BreezeCore(BreezeCoreConfig(useFASE = false), enabledebug = true)) { dut =>
            val backendDebug = dut.io.debug.get
            val refillDelayCycles = 6
            val ecallInst = BigInt("00000073", 16)
            val mretInst  = BigInt("30200073", 16)

            // Main @ ROM base + 0x800: set mtvec, ecall, then return to addi + ESTOP.
            val line0x800 = buildRefillLine(Seq(
                encodeLui(1, (RomBase >> 12).toInt),
                encodeCsr(0, 1, CSRMAP.mtvec, 1),
                ecallInst,
                encodeAddi(2, 0, 1),           // mret returns here (ROM base + 0x80c)
                BigInt("7FF00073", 16),         // ESTOP
                nopInst, nopInst, nopInst
            ))
            // Handler @ ROM base: read mepc, +4, write back via csrrw, mret
            // csrrw x5, mepc, x0  (CSRRW: read old mepc→x5, write x0→mepc temporarily)
            val csrrw_x5_mepc = encodeCsr(rd = 5, rs1 = 0, csr = CSRMAP.mepc, funct3 = 1)
            val addi_x5_4    = encodeAddi(rd = 5, rs1 = 5, imm = 4)
            // csrrw x0, mepc, x5: write x5→mepc (rd=x0 discards old value)
            val csrrw_mepc   = encodeCsr(rd = 0, rs1 = 5, csr = CSRMAP.mepc, funct3 = 1)
            val line0x200 = buildRefillLine(Seq(
                csrrw_x5_mepc, addi_x5_4, csrrw_mepc, mretInst,
                nopInst, nopInst, nopInst, nopInst
            ))
            val memoryMap = Map(
                RomBase -> line0x200,
                (RomBase + 0x800) -> line0x800
            )
            val defaultLine = buildRefillLine(Seq.fill(8)(nopInst))
            val pendingIcacheResps = mutable.Queue.empty[PendingIcacheResp]
            var prevIcacheReq = false

            dut.io.l1d.req.ready.poke(true.B); dut.io.l1d.s2Hold.poke(false.B)
            dut.io.l1d.resp.valid.poke(false.B); dut.io.l1d.resp.bits.kind.poke(L1DRespKind.Done)
            dut.io.l1d.resp.bits.data.poke(0.U); dut.io.l1d.resp.bits.excCause.poke(0.U); dut.io.l1d.resp.bits.tval.poke(0.U)
            dut.io.l1d.late.valid.poke(false.B); dut.io.l1d.late.bits.rd.idx.poke(0.U)
            dut.io.l1d.late.bits.rd.isFp.poke(false.B); dut.io.l1d.late.bits.data.poke(0.U); dut.io.l1d.late.bits.error.poke(false.B)
            dut.io.l1d.drained.poke(true.B); dut.io.l1d.mmioBusy.poke(false.B); dut.io.mmuIdle.poke(true.B)
            dut.io.machineSoftwareInterrupt.poke(false.B); dut.io.supervisorExternalInterrupt.poke(false.B); dut.io.time.poke(0.U)
            dut.io.dcacheHpm.mulSourceStall.poke(false.B); dut.io.dcacheHpm.divSourceStall.poke(false.B); dut.io.dcacheHpm.wbPortConflict.poke(0.U)
            dut.io.resetAddr.poke((RomBase + 0x800).U)
            dut.io.machineTimerInterrupt.poke(false.B)
            dut.io.externalInterrupts.poke(0.U)
            dut.io.nextLevelRsp.vld.poke(false.B)
            dut.io.nextLevelRsp.data.poke(0.U)
            dut.io.dmem.rsp.valid.poke(false.B)
            dut.io.dmem.rsp.data.poke(0.U)
            dut.io.dmem.rsp.isWriteAck.poke(false.B)

            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)
            dut.clock.step(1)

            val retiredPcs = mutable.ArrayBuffer.empty[BigInt]
            var phase = 0 // 0=wait ecall, 1=check CSR, 2=wait mret, 3=wait return, 4=wait ESTOP
            var trapCount = 0
            var trapSeen = false
            var csrChecked = false
            var mretSeen = false
            var returned = false
            var estopSeen = false
            var cycle = 0

            while (!estopSeen && cycle < 500) {
                val reqValid = dut.io.nextLevelReq.req.peek().litToBoolean
                if (reqValid && !prevIcacheReq) {
                    val reqAddr = dut.io.nextLevelReq.paddr.peek().litValue
                    pendingIcacheResps.enqueue(
                        PendingIcacheResp(reqAddr, memoryMap.getOrElse(reqAddr, defaultLine), refillDelayCycles)
                    )
                }
                prevIcacheReq = reqValid

                if (pendingIcacheResps.headOption.exists(_.cyclesLeft == 0)) {
                    val resp = pendingIcacheResps.dequeue()
                    dut.io.nextLevelRsp.vld.poke(true.B)
                    dut.io.nextLevelRsp.data.poke(resp.data.U)
                } else {
                    dut.io.nextLevelRsp.vld.poke(false.B)
                    dut.io.nextLevelRsp.data.poke(0.U)
                }

                // Phase 0: wait for ecall trap
                val isEcallTrap = backendDebug.memWbTrapValid.peek().litToBoolean &&
                    backendDebug.memWbIsEcall.peek().litToBoolean
                if (isEcallTrap) trapCount += 1
                if (phase == 0 && isEcallTrap) {
                    trapSeen = true
                    phase = 1
                }
                // Phase 1: CSR check (next cycle after trap)
                else if (phase == 1) {
                    backendDebug.csrMcause.expect(11.U)
                    backendDebug.csrMepc.expect((RomBase + 0x808).U)
                    csrChecked = true
                    phase = 2
                }
                // Phase 2: wait for mret in handler
                if (phase == 2 && backendDebug.memWbValid.peek().litToBoolean &&
                    backendDebug.memWbIsMret.peek().litToBoolean) {
                    mretSeen = true
                    phase = 3
                }
                // Phase 3: wait for return to ROM base + 0x80c
                if (phase == 3 && backendDebug.memWbValid.peek().litToBoolean &&
                    backendDebug.memWbPc.peek().litValue == RomBase + 0x80c) {
                    returned = true
                    phase = 4
                }

                if (backendDebug.memWbValid.peek().litToBoolean) {
                    retiredPcs += backendDebug.memWbPc.peek().litValue
                }

                if (dut.io.estop.peek().litToBoolean) {
                    estopSeen = true
                }

                dut.clock.step(1)
                cycle += 1

                val updated = pendingIcacheResps.map(r => r.copy(cyclesLeft = math.max(r.cyclesLeft - 1, 0)))
                pendingIcacheResps.clear()
                pendingIcacheResps ++= updated
            }

            estopSeen mustBe true
            trapSeen mustBe true
            csrChecked mustBe true
            mretSeen mustBe true
            returned mustBe true
            // Verify PC sequence: main → ecall → handler → back to main
            retiredPcs must not contain (RomBase + 0x808) // ecall traps without commit
            retiredPcs must contain(RomBase)         // handler entry
            retiredPcs must contain(RomBase + 0x80c) // returned after mret
            // ecall must execute exactly once.
            trapCount mustBe 1
            retiredPcs.count(_ == RomBase + 0x808) mustBe 0
        }
    }

    // ── CORE-003 diagnostic variants ──────────────────────────────
    // All use csrrw to write mepc in handler context.
    // The base case (read+write both csrrw) is the failing test above.

    "BreezeCore CORE-003 variant: csrrs-read + csrrw-write to mepc should pass" in {
        simulate(new BreezeCore(BreezeCoreConfig(useFASE = false), enabledebug = true)) { dut =>
            val backendDebug = dut.io.debug.get
            val ecallInst = BigInt("00000073", 16)
            val mretInst  = BigInt("30200073", 16)

            val line0x800 = buildRefillLine(Seq(
                encodeLui(1, (RomBase >> 12).toInt), encodeCsr(0, 1, CSRMAP.mtvec, 1),
                ecallInst, encodeAddi(2, 0, 1), BigInt("7FF00073", 16),
                nopInst, nopInst, nopInst
            ))
            // Handler: csrrs read mepc (no side-effect) → addi → csrrw write mepc → mret
            val csrrs_rd   = encodeCsr(rd = 5, rs1 = 0, csr = CSRMAP.mepc, funct3 = 2)  // read with RS
            val addi_x5    = encodeAddi(rd = 5, rs1 = 5, imm = 4)
            val csrrw_wr   = encodeCsr(rd = 0, rs1 = 5, csr = CSRMAP.mepc, funct3 = 1)  // write with RW
            val line0x200 = buildRefillLine(Seq(csrrs_rd, addi_x5, csrrw_wr, mretInst, nopInst, nopInst, nopInst, nopInst))

            val memoryMap = Map(RomBase -> line0x200, (RomBase + 0x800) -> line0x800)
            val defaultLine = buildRefillLine(Seq.fill(8)(nopInst))
            val pendingIcacheResps = mutable.Queue.empty[PendingIcacheResp]
            var prevIcacheReq = false

            dut.io.l1d.req.ready.poke(true.B); dut.io.l1d.s2Hold.poke(false.B)
            dut.io.l1d.resp.valid.poke(false.B); dut.io.l1d.resp.bits.kind.poke(L1DRespKind.Done)
            dut.io.l1d.resp.bits.data.poke(0.U); dut.io.l1d.resp.bits.excCause.poke(0.U); dut.io.l1d.resp.bits.tval.poke(0.U)
            dut.io.l1d.late.valid.poke(false.B); dut.io.l1d.late.bits.rd.idx.poke(0.U)
            dut.io.l1d.late.bits.rd.isFp.poke(false.B); dut.io.l1d.late.bits.data.poke(0.U); dut.io.l1d.late.bits.error.poke(false.B)
            dut.io.l1d.drained.poke(true.B); dut.io.l1d.mmioBusy.poke(false.B); dut.io.mmuIdle.poke(true.B)
            dut.io.machineSoftwareInterrupt.poke(false.B); dut.io.supervisorExternalInterrupt.poke(false.B); dut.io.time.poke(0.U)
            dut.io.dcacheHpm.mulSourceStall.poke(false.B); dut.io.dcacheHpm.divSourceStall.poke(false.B); dut.io.dcacheHpm.wbPortConflict.poke(0.U)
            dut.io.resetAddr.poke((RomBase + 0x800).U)
            dut.io.machineTimerInterrupt.poke(false.B)
            dut.io.externalInterrupts.poke(0.U)
            dut.io.nextLevelRsp.vld.poke(false.B); dut.io.nextLevelRsp.data.poke(0.U)
            dut.io.dmem.rsp.valid.poke(false.B); dut.io.dmem.rsp.data.poke(0.U); dut.io.dmem.rsp.isWriteAck.poke(false.B)
            dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B); dut.clock.step(1)

            var phase = 0; var trapSeen = false; var mretSeen = false; var returned = false; var estopSeen = false; var cycle = 0
            val refillDelay = 6

            while (!estopSeen && cycle < 500) {
                val reqValid = dut.io.nextLevelReq.req.peek().litToBoolean
                if (reqValid && !prevIcacheReq) {
                    val reqAddr = dut.io.nextLevelReq.paddr.peek().litValue
                    pendingIcacheResps.enqueue(PendingIcacheResp(reqAddr, memoryMap.getOrElse(reqAddr, defaultLine), refillDelay))
                }
                prevIcacheReq = reqValid
                if (pendingIcacheResps.headOption.exists(_.cyclesLeft == 0)) {
                    val resp = pendingIcacheResps.dequeue()
                    dut.io.nextLevelRsp.vld.poke(true.B); dut.io.nextLevelRsp.data.poke(resp.data.U)
                } else { dut.io.nextLevelRsp.vld.poke(false.B); dut.io.nextLevelRsp.data.poke(0.U) }

                if (phase == 0 && backendDebug.memWbTrapValid.peek().litToBoolean && backendDebug.memWbIsEcall.peek().litToBoolean) { trapSeen = true; phase = 1 }
                else if (phase == 1) { phase = 2 }
                if (phase == 2 && backendDebug.memWbValid.peek().litToBoolean && backendDebug.memWbIsMret.peek().litToBoolean) { mretSeen = true; phase = 3 }
                if (phase == 3 && backendDebug.memWbValid.peek().litToBoolean && backendDebug.memWbPc.peek().litValue == RomBase + 0x80c) { returned = true; phase = 4 }
                if (dut.io.estop.peek().litToBoolean) estopSeen = true
                dut.clock.step(1); cycle += 1
                val updated = pendingIcacheResps.map(r => r.copy(cyclesLeft = math.max(r.cyclesLeft - 1, 0)))
                pendingIcacheResps.clear(); pendingIcacheResps ++= updated
            }

            estopSeen mustBe true; trapSeen mustBe true; mretSeen mustBe true; returned mustBe true
        }
    }

    "BreezeCore CORE-003 variant: different reg for addi result, csrrw write via x6 should pass" in {
        simulate(new BreezeCore(BreezeCoreConfig(useFASE = false), enabledebug = true)) { dut =>
            val backendDebug = dut.io.debug.get
            val ecallInst = BigInt("00000073", 16)
            val mretInst  = BigInt("30200073", 16)

            val line0x800 = buildRefillLine(Seq(
                encodeLui(1, (RomBase >> 12).toInt), encodeCsr(0, 1, CSRMAP.mtvec, 1),
                ecallInst, encodeAddi(2, 0, 1), BigInt("7FF00073", 16),
                nopInst, nopInst, nopInst
            ))
            // Handler: csrrw read mepc→x5, addi→x6 (different reg!), csrrw write x6→mepc, mret
            val csrrw_rd   = encodeCsr(rd = 5, rs1 = 0, csr = CSRMAP.mepc, funct3 = 1)
            val addi_x6    = encodeAddi(rd = 6, rs1 = 5, imm = 4)   // x6 = x5 + 4 (DIFFERENT rd!)
            val csrrw_wr   = encodeCsr(rd = 0, rs1 = 6, csr = CSRMAP.mepc, funct3 = 1)  // write x6→mepc
            val line0x200 = buildRefillLine(Seq(csrrw_rd, addi_x6, csrrw_wr, mretInst, nopInst, nopInst, nopInst, nopInst))

            val memoryMap = Map(RomBase -> line0x200, (RomBase + 0x800) -> line0x800)
            val defaultLine = buildRefillLine(Seq.fill(8)(nopInst))
            val pendingIcacheResps = mutable.Queue.empty[PendingIcacheResp]
            var prevIcacheReq = false

            dut.io.l1d.req.ready.poke(true.B); dut.io.l1d.s2Hold.poke(false.B)
            dut.io.l1d.resp.valid.poke(false.B); dut.io.l1d.resp.bits.kind.poke(L1DRespKind.Done)
            dut.io.l1d.resp.bits.data.poke(0.U); dut.io.l1d.resp.bits.excCause.poke(0.U); dut.io.l1d.resp.bits.tval.poke(0.U)
            dut.io.l1d.late.valid.poke(false.B); dut.io.l1d.late.bits.rd.idx.poke(0.U)
            dut.io.l1d.late.bits.rd.isFp.poke(false.B); dut.io.l1d.late.bits.data.poke(0.U); dut.io.l1d.late.bits.error.poke(false.B)
            dut.io.l1d.drained.poke(true.B); dut.io.l1d.mmioBusy.poke(false.B); dut.io.mmuIdle.poke(true.B)
            dut.io.machineSoftwareInterrupt.poke(false.B); dut.io.supervisorExternalInterrupt.poke(false.B); dut.io.time.poke(0.U)
            dut.io.dcacheHpm.mulSourceStall.poke(false.B); dut.io.dcacheHpm.divSourceStall.poke(false.B); dut.io.dcacheHpm.wbPortConflict.poke(0.U)
            dut.io.resetAddr.poke((RomBase + 0x800).U)
            dut.io.machineTimerInterrupt.poke(false.B)
            dut.io.externalInterrupts.poke(0.U)
            dut.io.nextLevelRsp.vld.poke(false.B); dut.io.nextLevelRsp.data.poke(0.U)
            dut.io.dmem.rsp.valid.poke(false.B); dut.io.dmem.rsp.data.poke(0.U); dut.io.dmem.rsp.isWriteAck.poke(false.B)
            dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B); dut.clock.step(1)

            var phase = 0; var trapSeen = false; var mretSeen = false; var returned = false; var estopSeen = false; var cycle = 0
            val refillDelay = 6

            while (!estopSeen && cycle < 500) {
                val reqValid = dut.io.nextLevelReq.req.peek().litToBoolean
                if (reqValid && !prevIcacheReq) {
                    val reqAddr = dut.io.nextLevelReq.paddr.peek().litValue
                    pendingIcacheResps.enqueue(PendingIcacheResp(reqAddr, memoryMap.getOrElse(reqAddr, defaultLine), refillDelay))
                }
                prevIcacheReq = reqValid
                if (pendingIcacheResps.headOption.exists(_.cyclesLeft == 0)) {
                    val resp = pendingIcacheResps.dequeue()
                    dut.io.nextLevelRsp.vld.poke(true.B); dut.io.nextLevelRsp.data.poke(resp.data.U)
                } else { dut.io.nextLevelRsp.vld.poke(false.B); dut.io.nextLevelRsp.data.poke(0.U) }

                if (phase == 0 && backendDebug.memWbTrapValid.peek().litToBoolean && backendDebug.memWbIsEcall.peek().litToBoolean) { trapSeen = true; phase = 1 }
                else if (phase == 1) { phase = 2 }
                if (phase == 2 && backendDebug.memWbValid.peek().litToBoolean && backendDebug.memWbIsMret.peek().litToBoolean) { mretSeen = true; phase = 3 }
                if (phase == 3 && backendDebug.memWbValid.peek().litToBoolean && backendDebug.memWbPc.peek().litValue == RomBase + 0x80c) { returned = true; phase = 4 }
                if (dut.io.estop.peek().litToBoolean) estopSeen = true
                dut.clock.step(1); cycle += 1
                val updated = pendingIcacheResps.map(r => r.copy(cyclesLeft = math.max(r.cyclesLeft - 1, 0)))
                pendingIcacheResps.clear(); pendingIcacheResps ++= updated
            }

            estopSeen mustBe true; trapSeen mustBe true; mretSeen mustBe true; returned mustBe true
        }
    }

    "BreezeCore CORE-003 variant: NOP between addi and csrrw write should pass" in {
        simulate(new BreezeCore(BreezeCoreConfig(useFASE = false), enabledebug = true)) { dut =>
            val backendDebug = dut.io.debug.get
            val ecallInst = BigInt("00000073", 16)
            val mretInst  = BigInt("30200073", 16)

            val line0x800 = buildRefillLine(Seq(
                encodeLui(1, (RomBase >> 12).toInt), encodeCsr(0, 1, CSRMAP.mtvec, 1),
                ecallInst, encodeAddi(2, 0, 1), BigInt("7FF00073", 16),
                nopInst, nopInst, nopInst
            ))
            // Handler: csrrw read mepc → addi → NOP (bubble) → csrrw write mepc → mret
            val csrrw_rd   = encodeCsr(rd = 5, rs1 = 0, csr = CSRMAP.mepc, funct3 = 1)
            val addi_x5    = encodeAddi(rd = 5, rs1 = 5, imm = 4)
            val csrrw_wr   = encodeCsr(rd = 0, rs1 = 5, csr = CSRMAP.mepc, funct3 = 1)
            val line0x200 = buildRefillLine(Seq(csrrw_rd, addi_x5, nopInst, csrrw_wr, mretInst, nopInst, nopInst, nopInst))

            val memoryMap = Map(RomBase -> line0x200, (RomBase + 0x800) -> line0x800)
            val defaultLine = buildRefillLine(Seq.fill(8)(nopInst))
            val pendingIcacheResps = mutable.Queue.empty[PendingIcacheResp]
            var prevIcacheReq = false

            dut.io.l1d.req.ready.poke(true.B); dut.io.l1d.s2Hold.poke(false.B)
            dut.io.l1d.resp.valid.poke(false.B); dut.io.l1d.resp.bits.kind.poke(L1DRespKind.Done)
            dut.io.l1d.resp.bits.data.poke(0.U); dut.io.l1d.resp.bits.excCause.poke(0.U); dut.io.l1d.resp.bits.tval.poke(0.U)
            dut.io.l1d.late.valid.poke(false.B); dut.io.l1d.late.bits.rd.idx.poke(0.U)
            dut.io.l1d.late.bits.rd.isFp.poke(false.B); dut.io.l1d.late.bits.data.poke(0.U); dut.io.l1d.late.bits.error.poke(false.B)
            dut.io.l1d.drained.poke(true.B); dut.io.l1d.mmioBusy.poke(false.B); dut.io.mmuIdle.poke(true.B)
            dut.io.machineSoftwareInterrupt.poke(false.B); dut.io.supervisorExternalInterrupt.poke(false.B); dut.io.time.poke(0.U)
            dut.io.dcacheHpm.mulSourceStall.poke(false.B); dut.io.dcacheHpm.divSourceStall.poke(false.B); dut.io.dcacheHpm.wbPortConflict.poke(0.U)
            dut.io.resetAddr.poke((RomBase + 0x800).U)
            dut.io.machineTimerInterrupt.poke(false.B)
            dut.io.externalInterrupts.poke(0.U)
            dut.io.nextLevelRsp.vld.poke(false.B); dut.io.nextLevelRsp.data.poke(0.U)
            dut.io.dmem.rsp.valid.poke(false.B); dut.io.dmem.rsp.data.poke(0.U); dut.io.dmem.rsp.isWriteAck.poke(false.B)
            dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B); dut.clock.step(1)

            var phase = 0; var trapSeen = false; var mretSeen = false; var returned = false; var estopSeen = false; var cycle = 0
            val refillDelay = 6

            while (!estopSeen && cycle < 500) {
                val reqValid = dut.io.nextLevelReq.req.peek().litToBoolean
                if (reqValid && !prevIcacheReq) {
                    val reqAddr = dut.io.nextLevelReq.paddr.peek().litValue
                    pendingIcacheResps.enqueue(PendingIcacheResp(reqAddr, memoryMap.getOrElse(reqAddr, defaultLine), refillDelay))
                }
                prevIcacheReq = reqValid
                if (pendingIcacheResps.headOption.exists(_.cyclesLeft == 0)) {
                    val resp = pendingIcacheResps.dequeue()
                    dut.io.nextLevelRsp.vld.poke(true.B); dut.io.nextLevelRsp.data.poke(resp.data.U)
                } else { dut.io.nextLevelRsp.vld.poke(false.B); dut.io.nextLevelRsp.data.poke(0.U) }

                if (phase == 0 && backendDebug.memWbTrapValid.peek().litToBoolean && backendDebug.memWbIsEcall.peek().litToBoolean) { trapSeen = true; phase = 1 }
                else if (phase == 1) { phase = 2 }
                if (phase == 2 && backendDebug.memWbValid.peek().litToBoolean && backendDebug.memWbIsMret.peek().litToBoolean) { mretSeen = true; phase = 3 }
                if (phase == 3 && backendDebug.memWbValid.peek().litToBoolean && backendDebug.memWbPc.peek().litValue == RomBase + 0x80c) { returned = true; phase = 4 }
                if (dut.io.estop.peek().litToBoolean) estopSeen = true
                dut.clock.step(1); cycle += 1
                val updated = pendingIcacheResps.map(r => r.copy(cyclesLeft = math.max(r.cyclesLeft - 1, 0)))
                pendingIcacheResps.clear(); pendingIcacheResps ++= updated
            }

            estopSeen mustBe true; trapSeen mustBe true; mretSeen mustBe true; returned mustBe true
        }
    }

    "BreezeCore CORE-003 variant: addi rd≠rs1 (x5=x3+4), csrrw reads x5 should pass" in {
        simulate(new BreezeCore(BreezeCoreConfig(useFASE = false), enabledebug = true)) { dut =>
            val backendDebug = dut.io.debug.get
            val ecallInst = BigInt("00000073", 16)
            val mretInst  = BigInt("30200073", 16)

            val line0x800 = buildRefillLine(Seq(
                encodeLui(1, (RomBase >> 12).toInt), encodeCsr(0, 1, CSRMAP.mtvec, 1),
                ecallInst, encodeAddi(2, 0, 1), BigInt("7FF00073", 16),
                nopInst, nopInst, nopInst
            ))
            // Handler: csrrw read mepc→x3, addi x5=x3+4 (rs1≠rd!), csrrw write x5→mepc, mret
            val csrrw_rd   = encodeCsr(rd = 3, rs1 = 0, csr = CSRMAP.mepc, funct3 = 1)
            val addi_x5    = encodeAddi(rd = 5, rs1 = 3, imm = 4)   // rd=5, rs1=3 — DIFFERENT!
            val csrrw_wr   = encodeCsr(rd = 0, rs1 = 5, csr = CSRMAP.mepc, funct3 = 1)
            val line0x200 = buildRefillLine(Seq(csrrw_rd, addi_x5, csrrw_wr, mretInst, nopInst, nopInst, nopInst, nopInst))

            val memoryMap = Map(RomBase -> line0x200, (RomBase + 0x800) -> line0x800)
            val defaultLine = buildRefillLine(Seq.fill(8)(nopInst))
            val pendingIcacheResps = mutable.Queue.empty[PendingIcacheResp]
            var prevIcacheReq = false

            dut.io.l1d.req.ready.poke(true.B); dut.io.l1d.s2Hold.poke(false.B)
            dut.io.l1d.resp.valid.poke(false.B); dut.io.l1d.resp.bits.kind.poke(L1DRespKind.Done)
            dut.io.l1d.resp.bits.data.poke(0.U); dut.io.l1d.resp.bits.excCause.poke(0.U); dut.io.l1d.resp.bits.tval.poke(0.U)
            dut.io.l1d.late.valid.poke(false.B); dut.io.l1d.late.bits.rd.idx.poke(0.U)
            dut.io.l1d.late.bits.rd.isFp.poke(false.B); dut.io.l1d.late.bits.data.poke(0.U); dut.io.l1d.late.bits.error.poke(false.B)
            dut.io.l1d.drained.poke(true.B); dut.io.l1d.mmioBusy.poke(false.B); dut.io.mmuIdle.poke(true.B)
            dut.io.machineSoftwareInterrupt.poke(false.B); dut.io.supervisorExternalInterrupt.poke(false.B); dut.io.time.poke(0.U)
            dut.io.dcacheHpm.mulSourceStall.poke(false.B); dut.io.dcacheHpm.divSourceStall.poke(false.B); dut.io.dcacheHpm.wbPortConflict.poke(0.U)
            dut.io.resetAddr.poke((RomBase + 0x800).U)
            dut.io.machineTimerInterrupt.poke(false.B)
            dut.io.externalInterrupts.poke(0.U)
            dut.io.nextLevelRsp.vld.poke(false.B); dut.io.nextLevelRsp.data.poke(0.U)
            dut.io.dmem.rsp.valid.poke(false.B); dut.io.dmem.rsp.data.poke(0.U); dut.io.dmem.rsp.isWriteAck.poke(false.B)
            dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B); dut.clock.step(1)

            var phase = 0; var trapSeen = false; var mretSeen = false; var returned = false; var estopSeen = false; var cycle = 0
            val refillDelay = 6

            while (!estopSeen && cycle < 500) {
                val reqValid = dut.io.nextLevelReq.req.peek().litToBoolean
                if (reqValid && !prevIcacheReq) {
                    val reqAddr = dut.io.nextLevelReq.paddr.peek().litValue
                    pendingIcacheResps.enqueue(PendingIcacheResp(reqAddr, memoryMap.getOrElse(reqAddr, defaultLine), refillDelay))
                }
                prevIcacheReq = reqValid
                if (pendingIcacheResps.headOption.exists(_.cyclesLeft == 0)) {
                    val resp = pendingIcacheResps.dequeue()
                    dut.io.nextLevelRsp.vld.poke(true.B); dut.io.nextLevelRsp.data.poke(resp.data.U)
                } else { dut.io.nextLevelRsp.vld.poke(false.B); dut.io.nextLevelRsp.data.poke(0.U) }

                if (phase == 0 && backendDebug.memWbTrapValid.peek().litToBoolean && backendDebug.memWbIsEcall.peek().litToBoolean) { trapSeen = true; phase = 1 }
                else if (phase == 1) { phase = 2 }
                if (phase == 2 && backendDebug.memWbValid.peek().litToBoolean && backendDebug.memWbIsMret.peek().litToBoolean) { mretSeen = true; phase = 3 }
                if (phase == 3 && backendDebug.memWbValid.peek().litToBoolean && backendDebug.memWbPc.peek().litValue == RomBase + 0x80c) { returned = true; phase = 4 }
                if (dut.io.estop.peek().litToBoolean) estopSeen = true
                dut.clock.step(1); cycle += 1
                val updated = pendingIcacheResps.map(r => r.copy(cyclesLeft = math.max(r.cyclesLeft - 1, 0)))
                pendingIcacheResps.clear(); pendingIcacheResps ++= updated
            }

            estopSeen mustBe true; trapSeen mustBe true; mretSeen mustBe true; returned mustBe true
        }
    }
}
