package flow.core

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.PrivilegeProfile
import flow.observe.BreezeObserveEvent
import org.scalatest.freespec.AnyFreeSpec

class BreezeSscofpmfSpec extends AnyFreeSpec with ChiselSim {
  private val max = (BigInt(1) << 64) - 1
  private val of = BigInt(1) << 63
  private val minh = BigInt(1) << 62
  private class Driver(d: BreezePerformanceCounters) {
    var requests = 0
    d.io.write.poke(false.B); d.io.address.poke(0.U); d.io.data.poke(0.U)
    d.io.retire.poke(false.B); d.io.privilege.poke(3.U)
    d.io.events.poke(0.U.asTypeOf(d.io.events))
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
    def step(n: Int = 1): Unit = for(_ <- 0 until n) {
      if(d.io.overflowRequest.peek().litValue != 0) requests += 1
      d.clock.step()
    }
    def write(a: Int, v: BigInt): Unit = {
      d.io.write.poke(true.B); d.io.address.poke(a.U); d.io.data.poke(v.U); step()
      d.io.write.poke(false.B)
    }
    def conflict(): Unit = {
      d.io.events.wbPortConflict.poke(2.U); step()
      d.io.events.wbPortConflict.poke(0.U); step(2)
    }
  }

  "all HPM counters overflow together; software OF masks requests and rearming repeats them" in {
    simulate(new BreezePerformanceCounters) { d =>
      val t = new Driver(d)
      def arm(flags: BigInt): Unit = {
        t.write(CSRMAP.mcountinhibit, 0x7f8)
        for(i <- 0 until 8) {
          t.write(CSRMAP.mhpmevent3+i, flags|13)
          t.write(CSRMAP.mhpmcounter3+i, max-1)
        }
        t.write(CSRMAP.mcountinhibit, 0); t.step(2)
      }
      arm(0)
      assert(t.requests == 0, "preloading counters must not request an IRQ")
      t.conflict()
      for(i <- 0 until 8) { d.io.counter(i).expect(0.U); d.io.selector(i).expect((of|13).U) }
      d.io.overflow.expect(0x7f8.U); assert(t.requests == 1)
      arm(of); t.conflict()
      for(i <- 0 until 8) d.io.counter(i).expect(0.U)
      assert(t.requests == 1, "OF=1 must mask IRQs, not stop counting")
      arm(0); t.conflict(); assert(t.requests == 2)
    }
  }

  "software counter writes and their canceled delayed events do not report overflow" in {
    simulate(new BreezePerformanceCounters) { d =>
      val t = new Driver(d)
      t.write(CSRMAP.mhpmevent3,13); t.step(2)
      d.io.events.wbPortConflict.poke(2.U); t.step()
      t.write(CSRMAP.mhpmcounter3,max)
      d.io.events.wbPortConflict.poke(0.U); t.step(2)
      d.io.counter(0).expect(max.U); d.io.overflow.expect(0.U); assert(t.requests == 0)
      t.conflict(); d.io.counter(0).expect(1.U); d.io.overflow.expect(8.U); assert(t.requests == 1)
      // OF set wins over a coincident software clear at a real overflow edge.
      t.write(CSRMAP.mhpmcounter3,max)
      t.write(CSRMAP.mhpmevent3,13); t.step(2)
      d.io.events.wbPortConflict.poke(2.U); t.step()
      d.io.events.wbPortConflict.poke(0.U)
      t.write(CSRMAP.mhpmevent3,13)
      d.io.selector(0).expect((of|13).U); t.step(2); assert(t.requests == 2)
    }
  }

  "mode filtering follows each event group's occurrence tag across pipeline registers" in {
    simulate(new BreezePerformanceCounters) { d =>
      val t = new Driver(d)
      val raw = BreezeObserveEvent.raw("ic_refill_latency")
      for((id,i) <- Seq(1,4,raw).zipWithIndex) t.write(CSRMAP.mhpmevent3+i,minh|id)
      t.step(2)
      for((mode,cache,obs) <- Seq((3,1,0),(1,3,1),(0,0,3),(3,3,3))) {
        d.io.privilege.poke(mode.U)
        d.io.events.cachePrivilege.poke(cache.U); d.io.events.observationPrivilege.poke(obs.U)
        d.io.events.controlRetired.poke(true.B); d.io.events.icacheAccess.poke(true.B)
        d.io.events.observation("ic_refill_latency").poke(3.U); t.step()
      }
      d.io.events.poke(0.U.asTypeOf(d.io.events)); d.io.privilege.poke(3.U); t.step(2)
      d.io.counter(0).expect(2.U); d.io.counter(1).expect(2.U); d.io.counter(2).expect(6.U)
      // Filter all S and U events on one counter, allow M.
      t.write(CSRMAP.mhpmevent3,(BigInt(3)<<60)|1); t.step(2)
      for(mode <- Seq(0,1,3)) {
        d.io.privilege.poke(mode.U); d.io.events.controlRetired.poke(true.B); t.step()
      }
      d.io.events.controlRetired.poke(false.B); t.step(2)
      d.io.counter(0).expect(3.U)
    }
  }

  "programmable cycle and retirement events support filtering with stable raw encodings" in {
    simulate(new BreezePerformanceCounters) { d =>
      val t = new Driver(d)
      val cycle = BreezeObserveEvent.raw("cpu_cycles")
      val retire = BreezeObserveEvent.raw("retired_instructions")
      t.write(CSRMAP.mhpmevent3,minh|cycle); t.write(CSRMAP.mhpmevent3+1,minh|retire); t.step(3)
      d.io.counter(0).expect(0.U)
      d.io.privilege.poke(0.U)
      for(r <- Seq(true,false,true,true,false,false)) { d.io.retire.poke(r.B); t.step() }
      d.io.privilege.poke(3.U); d.io.retire.poke(false.B); t.step(2)
      d.io.counter(0).expect(6.U); d.io.counter(1).expect(3.U)
      t.write(CSRMAP.mhpmevent3,(BigInt(3)<<58)|of|minh|cycle)
      d.io.selector(0).expect((of|minh|cycle).U) // no H modes
      t.write(CSRMAP.mhpmevent3,minh|(BigInt(1)<<32)|cycle)
      d.io.selector(0).expect(minh.U) // full platform event field checked, not truncated
      for(n <- Seq(21,0x101,0xfff)) { t.write(CSRMAP.mhpmevent3,n); d.io.selector(0).expect(0.U) }
    }
  }

  "M-only profile implements no S or U inhibit bits" in {
    simulate(new BreezePerformanceCounters(supervisor=false)) { d =>
      val t = new Driver(d)
      t.write(CSRMAP.mhpmevent3,(BigInt(0x3f)<<58)|13)
      d.io.selector(0).expect((of|minh|13).U)
    }
  }
}

class BreezeSscofpmfCsrSpec extends AnyFreeSpec with ChiselSim {
  private val of = BigInt(1)<<63
  private val irq = BigInt(1)<<13
  private class Driver(d: CSRFile) {
    d.io.csr_addr.poke(0.U); d.io.csr_cmd.poke(CSR_CMD.RS.U); d.io.csr_reg_data.poke(0.U)
    d.io.rs1_id.poke(0.U); d.io.rd_id.poke(1.U)
    d.io.commit_valid.poke(false.B); d.io.commit_addr.poke(0.U)
    d.io.commit_wdata.poke(0.U); d.io.commit_write_en.poke(false.B)
    d.io.fp_commit_valid.poke(false.B); d.io.fp_flags.poke(0.U); d.io.retire_valid.poke(false.B)
    d.io.hpmEvents.poke(0.U.asTypeOf(d.io.hpmEvents))
    d.io.machineTimerInterrupt.poke(false.B); d.io.machineSoftwareInterrupt.poke(false.B)
    d.io.machineExternalInterrupt.poke(false.B); d.io.supervisorExternalInterrupt.poke(false.B)
    d.io.time.poke(0.U); d.io.trap.poke(0.U.asTypeOf(d.io.trap))
    d.io.mret_commit.poke(false.B); d.io.sret_commit.poke(false.B)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
    def write(a:Int,v:BigInt):Unit = {
      d.io.commit_valid.poke(true.B); d.io.commit_write_en.poke(true.B)
      d.io.commit_addr.poke(a.U); d.io.commit_wdata.poke(v.U); d.clock.step()
      d.io.commit_valid.poke(false.B); d.io.commit_write_en.poke(false.B)
    }
    def read(a:Int,v:BigInt):Unit = {
      d.io.csr_addr.poke(a.U); d.io.csr_cmd.poke(CSR_CMD.RS.U)
      d.io.csr_illegal.expect(false.B); d.io.csr_old_data.expect(v.U)
    }
    def enter(mode:Int):Unit = {
      write(CSRMAP.mstatus,(BigInt(mode)<<11)|2)
      d.io.mret_commit.poke(true.B); d.clock.step(); d.io.mret_commit.poke(false.B)
      d.io.current_privilege.expect(mode.U)
    }
    def arm():Unit = {
      write(CSRMAP.mcountinhibit,8); write(CSRMAP.mhpmevent3,1)
      write(CSRMAP.mhpmcounter3,(BigInt(1)<<64)-1)
      write(CSRMAP.mcountinhibit,0); d.clock.step(2)
    }
    def fire():Unit = {
      d.io.hpmEvents.controlRetired.poke(true.B); d.clock.step()
      d.io.hpmEvents.controlRetired.poke(false.B); d.clock.step()
    }
  }

  "OF and local pending are independent; clear wins only without a new overflow" in {
    simulate(new CSRFile(privilegeProfile=PrivilegeProfile.Linux)) { d =>
      val t = new Driver(d)
      t.write(CSRMAP.mhpmevent3,of|1); t.read(CSRMAP.scountovf,8); t.read(CSRMAP.mip,0)
      t.arm(); t.write(CSRMAP.mie,irq); t.fire()
      t.read(CSRMAP.scountovf,8); t.read(CSRMAP.mip,irq)
      d.io.wfiWakeup.expect(true.B); d.io.interruptPending.expect(false.B) // MIE=0
      t.write(CSRMAP.mstatus,8); d.io.interruptPending.expect(true.B); d.io.interruptCause.expect(13.U)
      t.write(CSRMAP.mip,0); t.read(CSRMAP.scountovf,8); t.read(CSRMAP.mip,0)
      d.clock.step(3); d.io.interruptPending.expect(false.B)
      t.arm()
      d.io.hpmEvents.controlRetired.poke(true.B); d.clock.step()
      d.io.hpmEvents.controlRetired.poke(false.B)
      t.write(CSRMAP.mip,0) // same edge as incoming overflow
      t.read(CSRMAP.mip,irq); d.io.interruptPending.expect(true.B)
      // Trap-flushed CSR writes cannot clear pending.
      d.io.trap.valid.poke(true.B); d.io.trap.is_interrupt.poke(true.B); d.io.trap.cause.poke(13.U)
      t.write(CSRMAP.mip,0); d.io.trap.valid.poke(false.B); t.read(CSRMAP.mip,irq)
    }
  }

  "delegated LCOFI reaches S vectored trap and scountovf masks only inaccessible counters" in {
    simulate(new CSRFile(privilegeProfile=PrivilegeProfile.Linux)) { d =>
      val t = new Driver(d)
      t.write(CSRMAP.mhpmevent3,of|1); t.write(CSRMAP.mhpmevent3+1,of|1)
      t.read(CSRMAP.scountovf,24)
      t.write(CSRMAP.mcounteren,8); t.write(CSRMAP.mideleg,irq)
      t.write(CSRMAP.mie,irq); t.write(CSRMAP.mip,irq); t.write(CSRMAP.stvec,0x1001)
      d.io.interruptPending.expect(false.B) // cannot take S-targeted IRQ in M
      t.enter(1); t.read(CSRMAP.scountovf,8)
      d.io.interruptPending.expect(true.B); d.io.interruptCause.expect(13.U)
      d.io.trap.valid.poke(true.B); d.io.trap.is_interrupt.poke(true.B)
      d.io.trap.cause.poke(13.U); d.io.trap.pc.poke(0x840.U)
      d.io.trap_target.expect((0x1000+4*13).U); d.clock.step(); d.io.trap.valid.poke(false.B)
      t.read(CSRMAP.scause,(BigInt(1)<<63)|13); t.read(CSRMAP.sepc,0x840)
      t.write(CSRMAP.sip,0); t.read(CSRMAP.sip,0); t.read(CSRMAP.scountovf,8)
      t.write(CSRMAP.sie,0); t.read(CSRMAP.sie,0)
      // scountovf is read-only, even when other counter reads are permitted.
      d.io.csr_addr.poke(CSRMAP.scountovf.U); d.io.csr_cmd.poke(CSR_CMD.RW.U)
      d.io.csr_illegal.expect(true.B)
      d.io.csr_cmd.poke(CSR_CMD.RS.U)
      t.write(CSRMAP.sstatus,0); t.write(CSRMAP.sepc,0x900)
      d.io.sret_commit.poke(true.B); d.clock.step(); d.io.sret_commit.poke(false.B)
      d.io.current_privilege.expect(0.U); d.io.csr_addr.poke(CSRMAP.scountovf.U)
      d.io.csr_illegal.expect(true.B)
    }
  }

  "LCOFI obeys destination priority and hidden sie/sip writes preserve nondelegated bits" in {
    simulate(new CSRFile(privilegeProfile=PrivilegeProfile.Linux)) { d =>
      val t = new Driver(d)
      t.write(CSRMAP.mie,irq|2); t.write(CSRMAP.mip,irq|2)
      t.write(CSRMAP.sie,0); t.write(CSRMAP.sip,0)
      t.read(CSRMAP.mie,irq|2); t.read(CSRMAP.mip,irq|2)
      t.write(CSRMAP.mstatus,8)
      d.io.interruptCause.expect(1.U) // existing software IRQ priority preserved
      t.write(CSRMAP.mideleg,2) // S software, M overflow
      d.io.interruptCause.expect(13.U)
      t.write(CSRMAP.mideleg,irq|2)
      t.enter(1); d.io.interruptCause.expect(1.U)
      t.write(CSRMAP.sip,irq)
      d.io.interruptCause.expect(13.U)
    }
  }
}
