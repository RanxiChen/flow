package flow.observe

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.BreezeMemGeometry
import org.scalatest.freespec.AnyFreeSpec

class BreezeIcacheObservationSpec extends AnyFreeSpec with ChiselSim {
  private val geometry = BreezeMemGeometry(nCores=1,l1Sets=2,l1iWays=2)
  "capture preserves occurrence metadata and counts refill intervals before delayed processing" in {
    simulate(new BreezeIcacheObservation(geometry)) { d =>
      def clear(): Unit = d.io.sample.poke(0.U.asTypeOf(d.io.sample))
      clear(); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.sample.refillStart.poke(true.B); d.clock.step()
      for(_ <- 0 until 4) { clear(); d.io.sample.refillWaiting.poke(true.B); d.clock.step() }
      clear(); d.io.sample.refillWaiting.poke(true.B); d.io.sample.refillReturned.poke(true.B)
      d.io.sample.refillCancelled.poke(true.B); d.io.sample.refillError.poke(true.B)
      d.io.sample.demand.valid.poke(true.B); d.io.sample.demand.bits.line.poke(0x40.U)
      d.io.sample.demand.bits.miss.poke(true.B)
      d.io.sample.trace.cycle.poke(701.U); d.io.sample.trace.pc.poke(0x400002.U)
      d.io.sample.trace.vaddr.poke(0x400002.U); d.io.sample.trace.paddr.poke(0x800002.U)
      d.io.sample.trace.satp.poke("h8000300000000100".U); d.io.sample.trace.flags.poke(13.U)
      d.clock.step(); clear()
      d.io.events("ic_refill_completed").expect(1.U); d.io.events("ic_refill_latency").expect(5.U)
      d.io.events("ic_refill_latency_bin_0").expect(1.U)
      d.io.events("ic_cancelled_refill").expect(1.U); d.io.events("ic_refill_error").expect(1.U)
      d.io.events("ic_demand_access").expect(1.U); d.io.events("ic_demand_miss").expect(1.U)
      d.io.trace.valid.expect(true.B); d.io.trace.bits.cycle.expect(701.U)
      d.io.trace.bits.pc.expect(0x400002.U); d.io.trace.bits.paddr.expect(0x800002.U)
      d.io.trace.bits.satp.expect("h8000300000000100".U); d.io.trace.bits.flags.expect(13.U)
      d.clock.step(); d.io.events("ic_refill_completed").expect(0.U); d.io.trace.valid.expect(false.B)
      // A second owner restarts its age, independently of the preceding one.
      d.io.sample.refillStart.poke(true.B); d.clock.step()
      clear(); d.io.sample.refillWaiting.poke(true.B); d.clock.step()
      clear(); d.io.sample.refillWaiting.poke(true.B); d.io.sample.refillReturned.poke(true.B); d.clock.step()
      d.io.events("ic_refill_latency").expect(2.U)
      d.io.events("ic_cancelled_refill").expect(0.U); d.io.events("ic_refill_error").expect(0.U)
    }
  }
  "demand install and fence retain their relative cycles through capture" in {
    simulate(new BreezeIcacheObservation(geometry)) { d =>
      def clear(): Unit = d.io.sample.poke(0.U.asTypeOf(d.io.sample))
      clear(); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.sample.demand.valid.poke(true.B); d.io.sample.demand.bits.line.poke(0x40.U)
      d.io.sample.install.valid.poke(true.B); d.io.sample.install.bits.line.poke(0x41.U)
      d.io.sample.install.bits.way.poke(0.U); d.clock.step()
      d.io.events("new_line").expect(1.U)
      clear(); d.clock.step(); d.io.events("next_line_unknown").expect(1.U)
      d.io.sample.fence.poke(true.B); d.clock.step(); d.io.events("fence_i").expect(1.U)
      clear(); d.io.sample.demand.valid.poke(true.B); d.io.sample.demand.bits.line.poke(0x40.U)
      d.clock.step(); d.io.trace.valid.expect(true.B); d.io.events("new_line").expect(1.U)
      clear(); d.clock.step(); d.io.events("next_line_nonresident").expect(1.U)
    }
  }
}

class BreezeFrontendRecoveryObservationSpec extends AnyFreeSpec with ChiselSim {
  "captured recovery controls retain latency target ownership and cancellation priority" in {
    simulate(new BreezeFrontendRecoveryObservation) { d =>
      def clear():Unit = d.io.sample.poke(0.U.asTypeOf(d.io.sample))
      clear(); d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.sample.resolvedRedirect.poke(true.B); d.io.sample.f2Redirect.poke(true.B)
      d.io.sample.actualNext.poke(0x1004.U); d.clock.step()
      d.io.events("recovery_cycles").expect(0.U); d.io.events("f2_recovery_cycles").expect(0.U)
      clear(); d.io.sample.f1Valid.poke(true.B); d.io.sample.f1Pc.poke(0x1000.U); d.clock.step()
      d.io.events("recovery_cycles").expect(1.U); d.io.events("f2_recovery_cycles").expect(1.U)
      d.io.events("f2_recovery_completed").expect(0.U)
      clear(); d.io.sample.instructionValid.poke(true.B)
      d.io.sample.f1Valid.poke(true.B); d.io.sample.f1Pc.poke(0x1004.U); d.clock.step()
      d.io.events("recovery_completed").expect(1.U); d.io.events("recovery_latency").expect(2.U)
      d.io.events("f2_recovery_completed").expect(1.U); d.io.events("f2_recovery_latency").expect(2.U)
      d.io.events("recovery_latency_bin_0").expect(1.U); d.io.events("f2_recovery_latency_bin_0").expect(1.U)
      clear(); d.clock.step(); d.io.events("fe_wait_recovery").expect(0.U)
      // A same-packet cancellation blocks a new interval and stale completion.
      d.io.sample.resolvedRedirect.poke(true.B); d.io.sample.globalCancel.poke(true.B)
      d.io.sample.f2Redirect.poke(true.B); d.io.sample.olderCancel.poke(true.B)
      d.io.sample.actualNext.poke(0x2000.U); d.clock.step()
      clear(); d.io.sample.instructionValid.poke(true.B)
      d.io.sample.f1Valid.poke(true.B); d.io.sample.f1Pc.poke(0x2000.U); d.clock.step()
      d.io.events("recovery_completed").expect(0.U); d.io.events("f2_recovery_completed").expect(0.U)
      d.io.events("fe_wait_recovery").expect(0.U)
    }
  }
}
