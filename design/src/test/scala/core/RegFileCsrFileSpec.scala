package flow.core

// RegFile and CSRFile unit specs, kept from the deleted breezecoreSpec.scala
// (its BreezeCore pipeline classes went with the old core).

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

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

    "CSRFile should report no triggers through tselect, tdata1 and tdata2" in {
        simulate(new CSRFile(64)) { dut =>
            driveIdle(dut)
            dut.reset.poke(true.B)
            dut.clock.step(1)
            dut.reset.poke(false.B)
            for (address <- Seq(CSRMAP.tselect, CSRMAP.tdata1, CSRMAP.tdata2)) {
                dut.io.csr_addr.poke(address.U)
                dut.io.csr_cmd.poke(CSR_CMD.RW.U)
                dut.io.rs1_id.poke(1.U)
                dut.io.csr_reg_data.poke("hffffffffffffffff".U)
                dut.io.csr_illegal.expect(false.B)
                dut.io.csr_old_data.expect(0.U)
                dut.io.commit_valid.poke(true.B)
                dut.io.commit_write_en.poke(true.B)
                dut.io.commit_addr.poke(address.U)
                dut.io.commit_wdata.poke("hffffffffffffffff".U)
                dut.clock.step(1)
                dut.io.commit_valid.poke(false.B)
                dut.io.commit_write_en.poke(false.B)
                dut.io.csr_old_data.expect(0.U)
            }
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
