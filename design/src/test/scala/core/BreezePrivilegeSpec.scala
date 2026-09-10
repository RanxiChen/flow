package flow.core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.PrivilegeProfile
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezePrivilegeSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private def idle(dut: CSRFile): Unit = {
    dut.io.csr_addr.poke(0.U)
    dut.io.csr_cmd.poke(CSR_CMD.NOP.U)
    dut.io.csr_reg_data.poke(0.U)
    dut.io.rs1_id.poke(0.U)
    dut.io.rd_id.poke(0.U)
    dut.io.commit_valid.poke(false.B)
    dut.io.commit_addr.poke(0.U)
    dut.io.commit_wdata.poke(0.U)
    dut.io.commit_write_en.poke(false.B)
    dut.io.fp_commit_valid.poke(false.B)
    dut.io.fp_flags.poke(0.U)
    dut.io.retire_valid.poke(false.B)
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
    dut.io.machineTimerInterrupt.poke(false.B)
    dut.io.machineSoftwareInterrupt.poke(false.B)
    dut.io.machineExternalInterrupt.poke(false.B)
    dut.io.supervisorExternalInterrupt.poke(false.B)
    dut.io.time.poke(0.U)
    dut.io.trap.valid.poke(false.B)
    dut.io.trap.is_interrupt.poke(false.B)
    dut.io.trap.cause.poke(0.U)
    dut.io.trap.pc.poke(0.U)
    dut.io.trap.tval.poke(0.U)
    dut.io.mret_commit.poke(false.B)
    dut.io.sret_commit.poke(false.B)
  }

  private def reset(dut: CSRFile): Unit = {
    idle(dut)
    dut.reset.poke(true.B)
    dut.clock.step(1)
    dut.reset.poke(false.B)
  }

  private def commit(dut: CSRFile, address: Int, value: BigInt): Unit = {
    dut.io.commit_valid.poke(true.B)
    dut.io.commit_write_en.poke(true.B)
    dut.io.commit_addr.poke(address.U)
    dut.io.commit_wdata.poke(value.U)
    dut.clock.step(1)
    dut.io.commit_valid.poke(false.B)
    dut.io.commit_write_en.poke(false.B)
  }

  private def selectRead(dut: CSRFile, address: Int): Unit = {
    dut.io.csr_addr.poke(address.U)
    dut.io.csr_cmd.poke(CSR_CMD.RS.U)
    dut.io.csr_reg_data.poke(0.U)
    dut.io.rs1_id.poke(0.U)
    dut.io.rd_id.poke(1.U)
  }

  private def mret(dut: CSRFile): Unit = {
    dut.io.mret_commit.poke(true.B)
    dut.clock.step(1)
    dut.io.mret_commit.poke(false.B)
  }

  private def sret(dut: CSRFile): Unit = {
    dut.io.sret_commit.poke(true.B)
    dut.clock.step(1)
    dut.io.sret_commit.poke(false.B)
  }

  "keep the MCU profile machine-only and reject supervisor CSRs" in {
    simulate(new CSRFile(64, privilegeProfile = PrivilegeProfile.Mcu)) { dut =>
      reset(dut)
      dut.io.current_privilege.expect(PRIV_MODE.M.U)
      selectRead(dut, CSRMAP.misa)
      val misa = dut.io.csr_old_data.peek().litValue
      ((misa >> 18) & 1) mustBe 0
      ((misa >> 20) & 1) mustBe 0

      selectRead(dut, CSRMAP.sstatus)
      dut.io.csr_illegal.expect(true.B)
      dut.io.sret_illegal.expect(true.B)
    }
  }

  "reject every unimplemented MCU CSR instead of silently reading zero" in {
    simulate(new CSRFile(64, privilegeProfile = PrivilegeProfile.Mcu)) { dut =>
      reset(dut)
      commit(dut, CSRMAP.mstatus, BigInt(1) << 13)

      val implemented = Set(
        CSRMAP.fflags, CSRMAP.frm, CSRMAP.fcsr, CSRMAP.printer, CSRMAP.coreinst,
        CSRMAP.misa, CSRMAP.mvendorid, CSRMAP.marchid, CSRMAP.mimpid, CSRMAP.mhartid,
        CSRMAP.mstatus, CSRMAP.mie, CSRMAP.mtvec, CSRMAP.mcounteren,
        CSRMAP.mscratch, CSRMAP.mepc, CSRMAP.mcause, CSRMAP.mtval, CSRMAP.mip,
        CSRMAP.mcycle, CSRMAP.minstret, CSRMAP.cycle, CSRMAP.time, CSRMAP.instret,
        CSRMAP.mcountinhibit, CSRMAP.menvcfg, CSRMAP.pmpcfg0, CSRMAP.pmpcfg2
      ) ++ (0 until 16).map(CSRMAP.pmpaddr0 + _) ++
        (0 until 8).flatMap(index => Seq(
          CSRMAP.mhpmcounter3 + index,
          CSRMAP.hpmcounter3 + index,
          CSRMAP.mhpmevent3 + index))

      for (address <- 0 until 4096) {
        selectRead(dut, address)
        val expectedIllegal = !implemented.contains(address)
        withClue(f"MCU CSR 0x$address%03x: ") {
          (dut.io.csr_illegal.peek().litValue != 0) mustBe expectedIllegal
        }
      }
    }
  }

  "reject OpenSBI extension probes and every unimplemented Linux CSR" in {
    simulate(new CSRFile(64, privilegeProfile = PrivilegeProfile.Linux)) { dut =>
      reset(dut)

      // Make dynamically gated implemented CSRs readable before checking the
      // static address whitelist: FS=Initial enables fflags/frm/fcsr and STCE
      // enables stimecmp.
      commit(dut, CSRMAP.mstatus, BigInt(1) << 13)
      commit(dut, CSRMAP.menvcfg, BigInt(1) << 63)

      val implemented = Set(
        CSRMAP.fflags, CSRMAP.frm, CSRMAP.fcsr, CSRMAP.printer, CSRMAP.coreinst,
        CSRMAP.misa, CSRMAP.mvendorid, CSRMAP.marchid, CSRMAP.mimpid, CSRMAP.mhartid,
        CSRMAP.mstatus, CSRMAP.medeleg, CSRMAP.mideleg, CSRMAP.mie, CSRMAP.mtvec,
        CSRMAP.mcounteren, CSRMAP.mscratch, CSRMAP.mepc, CSRMAP.mcause, CSRMAP.mtval,
        CSRMAP.mip, CSRMAP.mcycle, CSRMAP.minstret, CSRMAP.cycle, CSRMAP.time,
        CSRMAP.instret, CSRMAP.mcountinhibit, CSRMAP.menvcfg, CSRMAP.pmpcfg0,
        CSRMAP.pmpcfg2,
        CSRMAP.sstatus, CSRMAP.sie, CSRMAP.stvec, CSRMAP.scounteren,
        CSRMAP.sscratch, CSRMAP.sepc, CSRMAP.scause, CSRMAP.stval, CSRMAP.sip,
        CSRMAP.stimecmp, CSRMAP.satp
      ) ++ (0 until 16).map(CSRMAP.pmpaddr0 + _) ++
        (0 until 8).flatMap(index => Seq(
          CSRMAP.mhpmcounter3 + index,
          CSRMAP.hpmcounter3 + index,
          CSRMAP.mhpmevent3 + index))

      val openSbiUnsupportedProbes = Seq(
        0xfb0 -> "mtopi/Smaia",
        0xda0 -> "scountovf/Sscofpmf",
        0x30c -> "mstateen0/Smstateen",
        0x10c -> "sstateen0/Ssstateen",
        0x321 -> "mcyclecfg/Smcntrpmf",
        0x7a0 -> "tselect/Sdtrig")
      openSbiUnsupportedProbes.foreach { case (address, name) =>
        selectRead(dut, address)
        withClue(s"OpenSBI probe $name at 0x${address.toHexString}: ") {
          dut.io.csr_illegal.peek().litValue mustBe 1
        }
      }

      for (address <- 0 until 4096) {
        selectRead(dut, address)
        val expectedIllegal = !implemented.contains(address)
        withClue(f"CSR 0x$address%03x: ") {
          (dut.io.csr_illegal.peek().litValue != 0) mustBe expectedIllegal
        }
      }
    }
  }

  "enter S and U with MRET/SRET and route delegated exceptions to S" in {
    simulate(new CSRFile(64, privilegeProfile = PrivilegeProfile.Linux)) { dut =>
      reset(dut)
      selectRead(dut, CSRMAP.misa)
      val misa = dut.io.csr_old_data.peek().litValue
      ((misa >> 18) & 1) mustBe 1
      ((misa >> 20) & 1) mustBe 1

      commit(dut, CSRMAP.stvec, 0x400)
      commit(dut, CSRMAP.medeleg, BigInt(1) << 8)
      commit(dut, CSRMAP.mepc, 0x1000)
      commit(dut, CSRMAP.mstatus, BigInt(PRIV_MODE.S) << 11)
      dut.io.xret_target.expect(0x1000.U)
      mret(dut)
      dut.io.current_privilege.expect(PRIV_MODE.S.U)

      commit(dut, CSRMAP.sepc, 0x2000)
      commit(dut, CSRMAP.sstatus, 0)
      sret(dut)
      dut.io.current_privilege.expect(PRIV_MODE.U.U)

      selectRead(dut, CSRMAP.sstatus)
      dut.io.csr_illegal.expect(true.B)
      selectRead(dut, CSRMAP.mstatus)
      dut.io.csr_illegal.expect(true.B)

      dut.io.csr_cmd.poke(CSR_CMD.NOP.U)
      dut.io.trap.valid.poke(true.B)
      dut.io.trap.is_interrupt.poke(false.B)
      dut.io.trap.cause.poke(8.U)
      dut.io.trap.pc.poke(0x2344.U)
      dut.io.trap.tval.poke(0.U)
      dut.io.trap_target.expect(0x400.U)
      dut.clock.step(1)
      dut.io.trap.valid.poke(false.B)
      dut.io.current_privilege.expect(PRIV_MODE.S.U)

      selectRead(dut, CSRMAP.sepc)
      dut.io.csr_illegal.expect(false.B)
      dut.io.csr_old_data.expect(0x2344.U)
      selectRead(dut, CSRMAP.scause)
      dut.io.csr_old_data.expect(8.U)

      commit(dut, CSRMAP.satp, BigInt("8000000000001234", 16))
      selectRead(dut, CSRMAP.satp)
      dut.io.csr_old_data.expect(BigInt("8000000000001234", 16).U)
    }
  }

  "preserve a high Sv39 user address across sepc and SRET" in {
    simulate(new CSRFile(64, enableCompressed = true,
      privilegeProfile = PrivilegeProfile.Linux)) { dut =>
      reset(dut)
      val target = BigInt("0000003f92bffbfe", 16)
      val truncated = BigInt("ffffffff92bffbfe", 16)

      commit(dut, CSRMAP.mepc, 0x1000)
      commit(dut, CSRMAP.mstatus, BigInt(PRIV_MODE.S) << 11)
      mret(dut)
      dut.io.current_privilege.expect(PRIV_MODE.S.U)

      commit(dut, CSRMAP.sepc, target)
      selectRead(dut, CSRMAP.sepc)
      dut.io.csr_old_data.expect(target.U)
      dut.io.csr_old_data.peek().litValue must not be truncated

      dut.io.csr_cmd.poke(CSR_CMD.NOP.U)
      dut.io.sret_commit.poke(true.B)
      dut.io.xret_target.expect(target.U)
      dut.io.xret_target.peek().litValue must not be truncated
      dut.clock.step(1)
      dut.io.sret_commit.poke(false.B)
      dut.io.current_privilege.expect(PRIV_MODE.U.U)
    }
  }

  "take a delegated supervisor software interrupt with vectored stvec" in {
    simulate(new CSRFile(64, privilegeProfile = PrivilegeProfile.Linux)) { dut =>
      reset(dut)
      val ssip = BigInt(1) << SUPERVISOR_INTERRUPT_CAUSE.SOFTWARE
      commit(dut, CSRMAP.stvec, 0x401)
      commit(dut, CSRMAP.mideleg, ssip)
      commit(dut, CSRMAP.mie, ssip)
      commit(dut, CSRMAP.mip, ssip)
      commit(dut, CSRMAP.mepc, 0x1000)
      commit(dut, CSRMAP.mstatus, 0)
      mret(dut)
      dut.io.current_privilege.expect(PRIV_MODE.U.U)
      dut.io.interruptPending.expect(true.B)
      dut.io.interruptCause.expect(SUPERVISOR_INTERRUPT_CAUSE.SOFTWARE.U)

      dut.io.trap.valid.poke(true.B)
      dut.io.trap.is_interrupt.poke(true.B)
      dut.io.trap.cause.poke(SUPERVISOR_INTERRUPT_CAUSE.SOFTWARE.U)
      dut.io.trap.pc.poke(0x1234.U)
      dut.io.trap_target.expect(0x404.U)
      dut.clock.step(1)
      dut.io.trap.valid.poke(false.B)
      dut.io.current_privilege.expect(PRIV_MODE.S.U)
    }
  }

  "wake WFI from a locally enabled interrupt independently of global MIE" in {
    simulate(new CSRFile(64, privilegeProfile = PrivilegeProfile.Linux)) { dut =>
      reset(dut)
      val msip = BigInt(1) << MACHINE_INTERRUPT_CAUSE.SOFTWARE

      commit(dut, CSRMAP.mie, msip)
      commit(dut, CSRMAP.mstatus, 0)
      dut.io.machineSoftwareInterrupt.poke(true.B)
      dut.io.wfiWakeup.expect(true.B)
      dut.io.interruptPending.expect(false.B)

      commit(dut, CSRMAP.mstatus, BigInt(1) << 3)
      dut.io.wfiWakeup.expect(true.B)
      dut.io.interruptPending.expect(true.B)

      dut.io.machineSoftwareInterrupt.poke(false.B)
      dut.io.wfiWakeup.expect(false.B)
      dut.io.interruptPending.expect(false.B)
    }
  }

  "enforce TSR TW TVM and keep simulation CSRs machine-only" in {
    simulate(new CSRFile(64, privilegeProfile = PrivilegeProfile.Linux)) { dut =>
      reset(dut)
      val trapControls = (BigInt(1) << 22) | (BigInt(1) << 21) |
        (BigInt(1) << 20)
      commit(dut, CSRMAP.mepc, 0x1000)
      commit(dut, CSRMAP.mstatus, trapControls | (BigInt(PRIV_MODE.S) << 11))

      selectRead(dut, CSRMAP.mstatus)
      val mstatus = dut.io.csr_old_data.peek().litValue
      ((mstatus >> 34) & 3) mustBe 2 // SXL=64
      ((mstatus >> 32) & 3) mustBe 2 // UXL=64

      mret(dut)
      dut.io.current_privilege.expect(PRIV_MODE.S.U)
      dut.io.sret_illegal.expect(true.B)
      dut.io.wfi_illegal.expect(true.B)
      dut.io.sfence_vma_illegal.expect(true.B)

      selectRead(dut, CSRMAP.sstatus)
      ((dut.io.csr_old_data.peek().litValue >> 32) & 3) mustBe 2
      selectRead(dut, CSRMAP.satp)
      dut.io.csr_illegal.expect(true.B)
      selectRead(dut, CSRMAP.printer)
      dut.io.csr_illegal.expect(true.B)
      selectRead(dut, CSRMAP.coreinst)
      dut.io.csr_illegal.expect(true.B)
    }
  }

  "decode WFI and SFENCE.VMA as privilege-checked system operations" in {
    simulate(new RV64IZicsrDecoder) { dut =>
      dut.io.inst.poke("h10500073".U) // WFI
      dut.io.illegal_inst.expect(false.B)
      dut.io.I_ctrl.is_wfi.expect(true.B)
      dut.io.I_ctrl.is_sfence_vma.expect(false.B)

      dut.io.inst.poke("h12000073".U) // SFENCE.VMA x0, x0
      dut.io.illegal_inst.expect(false.B)
      dut.io.I_ctrl.is_wfi.expect(false.B)
      dut.io.I_ctrl.is_sfence_vma.expect(true.B)
    }
  }

  "decode EBREAK as a breakpoint exception instead of an illegal instruction" in {
    simulate(new RV64IZicsrDecoder) { dut =>
      dut.io.inst.poke("h00100073".U)
      dut.io.illegal_inst.expect(false.B)
      dut.io.I_ctrl.is_ebreak.expect(true.B)
      dut.io.I_ctrl.is_ecall.expect(false.B)

      dut.io.inst.poke("h00108073".U) // Reserved encoding: rs1 must be x0.
      dut.io.illegal_inst.expect(true.B)
      dut.io.I_ctrl.is_ebreak.expect(false.B)
    }
  }

  "expose eight writable PMP entries and eight read-zero slots to firmware probes" in {
    simulate(new CSRFile(64, privilegeProfile = PrivilegeProfile.Linux)) { dut =>
      reset(dut)
      val addrMask = (BigInt(1) << 54) - 1
      var detected = 0
      for (index <- 0 until 16) {
        commit(dut, CSRMAP.pmpaddr0 + index, addrMask)
        selectRead(dut, CSRMAP.pmpaddr0 + index)
        dut.io.csr_illegal.expect(false.B)
        val expected = if (index < 8) addrMask else BigInt(0)
        dut.io.csr_old_data.expect(expected.U)
        dut.io.mmu_context.pmpaddr(index).expect(expected.U)
        if (dut.io.csr_old_data.peek().litValue == addrMask) detected += 1
      }
      detected mustBe 8

      // Attempts to configure/lock entry 8 cannot change or lock entry 7.
      commit(dut, CSRMAP.pmpcfg2, BigInt("ffffffffffffffff", 16))
      selectRead(dut, CSRMAP.pmpcfg2)
      dut.io.csr_illegal.expect(false.B)
      dut.io.csr_old_data.expect(0.U)
      for (index <- 8 until 16) dut.io.mmu_context.pmpcfg(index).expect(0.U)
      commit(dut, CSRMAP.pmpaddr0 + 7, 0x1234)
      selectRead(dut, CSRMAP.pmpaddr0 + 7)
      dut.io.csr_old_data.expect(0x1234.U)

      // A locked TOR entry 7 still locks its own address and entry 6's bound.
      commit(dut, CSRMAP.pmpcfg0, BigInt(0x89) << 56)
      commit(dut, CSRMAP.pmpaddr0 + 6, 0)
      commit(dut, CSRMAP.pmpaddr0 + 7, 0)
      selectRead(dut, CSRMAP.pmpaddr0 + 6)
      dut.io.csr_old_data.expect(addrMask.U)
      selectRead(dut, CSRMAP.pmpaddr0 + 7)
      dut.io.csr_old_data.expect(0x1234.U)
      commit(dut, CSRMAP.pmpcfg0, 0)
      selectRead(dut, CSRMAP.pmpcfg0)
      dut.io.csr_old_data.expect((BigInt(0x89) << 56).U)
    }
  }

  "implement PMP CSRs and Sstc time/stimecmp pending state" in {
    simulate(new CSRFile(64, privilegeProfile = PrivilegeProfile.Linux)) { dut =>
      reset(dut)
      commit(dut, CSRMAP.pmpaddr0, (BigInt(1) << 54) - 1)
      commit(dut, CSRMAP.pmpcfg0, 0x0f)
      selectRead(dut, CSRMAP.pmpaddr0)
      dut.io.csr_old_data.expect(((BigInt(1) << 54) - 1).U)
      selectRead(dut, CSRMAP.pmpcfg0)
      dut.io.csr_old_data.expect(0x0f.U)

      commit(dut, CSRMAP.menvcfg, (BigInt(1) << 63) | (BigInt(1) << 61))
      commit(dut, CSRMAP.mideleg, BigInt(1) << SUPERVISOR_INTERRUPT_CAUSE.TIMER)
      commit(dut, CSRMAP.stimecmp, 100)
      dut.io.time.poke(99.U)
      selectRead(dut, CSRMAP.sip)
      dut.io.csr_old_data.expect(0.U)
      dut.io.time.poke(100.U)
      selectRead(dut, CSRMAP.sip)
      assert((dut.io.csr_old_data.peekValue().asBigInt & (BigInt(1) << 5)) != 0)
    }
  }
}
