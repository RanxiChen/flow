package flow.backend
import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.core.BreezePerformanceCounters
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class HpmSpec extends AnyFreeSpec with Matchers with ChiselSim {
  "S16_HPM13_bank_count_write_priority_and_selector_bound" in {
    simulate(new BreezePerformanceCounters) { d =>
      d.io.write.poke(false.B); d.io.address.poke(0.U); d.io.data.poke(0.U); d.io.retire.poke(false.B)
      val e=d.io.events
      Seq(e.controlRetired,e.controlTaken,e.predictionMiss,e.icacheAccess,e.icacheMiss,
        e.dcacheAccess,e.dcacheMiss,e.dcacheUncached,e.memStallCycle,e.loadUseStall,
        e.mulSourceStall,e.divSourceStall).foreach(_.poke(false.B))
      e.wbPortConflict.poke(0.U)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.write.poke(true.B); d.io.address.poke(0x323.U); d.io.data.poke(13.U); d.clock.step()
      d.io.selector(0).expect(13.U); d.io.write.poke(false.B); e.wbPortConflict.poke(2.U)
      d.clock.step(); d.io.counter(0).expect(2.U)
      d.clock.step(); d.io.counter(0).expect(4.U)
      e.wbPortConflict.poke(0.U); d.clock.step(); d.io.counter(0).expect(4.U)
      d.io.write.poke(true.B); d.io.address.poke(0xb03.U); d.io.data.poke(11.U)
      e.wbPortConflict.poke(2.U); d.clock.step(); d.io.counter(0).expect(11.U)
      d.io.write.poke(false.B); d.clock.step(); d.io.counter(0).expect(13.U)
      d.io.write.poke(true.B); d.io.address.poke(0x323.U); d.io.data.poke(14.U); d.clock.step()
      d.io.selector(0).expect(0.U); d.io.counter(0).expect(15.U)
      d.io.write.poke(false.B); d.clock.step(); d.io.counter(0).expect(15.U)
    }
  }
}
