package flow.observe

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.BreezeMemGeometry
import org.scalatest.freespec.AnyFreeSpec

class BreezeObserveSpec extends AnyFreeSpec with ChiselSim {
  "bank preserves full-width deltas, freezes coherently and clears every counter" in {
    simulate(new BreezeObserveBank) { d =>
      d.io.events.poke(0.U.asTypeOf(d.io.events)); d.io.trace.poke(0.U.asTypeOf(d.io.trace))
      d.io.write.poke(false.B); d.io.address.poke(0.U); d.io.data.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def write(a:Int,v:BigInt):Unit = {
        d.io.write.poke(true.B); d.io.address.poke(a.U); d.io.data.poke(v.U); d.clock.step()
        d.io.write.poke(false.B)
      }
      val id = BreezeObserveEvent.index("ic_refill_latency")
      write(0x7d1,id)
      d.io.events("ic_refill_latency").poke(3.U); d.clock.step(2); d.io.read.expect(6.U)
      write(0x7d0,1)
      d.io.events("ic_refill_latency").poke(17.U); d.clock.step(4); d.io.read.expect(6.U)
      write(0x7d0,3); d.io.read.expect(0.U)
      write(0x7d0,0)
      d.io.events("ic_refill_latency").poke("hffffffff".U); d.clock.step(2)
      d.io.read.expect(BigInt("1fffffffe",16).U)
      d.io.events.poke(0.U.asTypeOf(d.io.events)); write(0x7d1,65535); d.io.read.expect(0.U)
      write(0x7d0,3)
      for(i <- 0 until BreezeObserveEvent.count) { write(0x7d1,i); d.io.read.expect(0.U) }
    }
  }
  "trace preserves owner metadata, reports overflow and supports decimation without backpressure" in {
    simulate(new BreezeObserveBank) { d =>
      d.io.events.poke(0.U.asTypeOf(d.io.events)); d.io.trace.poke(0.U.asTypeOf(d.io.trace))
      d.io.write.poke(false.B); d.io.address.poke(0.U); d.io.data.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def write(a:Int,v:BigInt):Unit = {
        d.io.write.poke(true.B); d.io.address.poke(a.U); d.io.data.poke(v.U); d.clock.step(); d.io.write.poke(false.B)
      }
      write(0x7d0,6) // clear and enable, period=0: every sample
      for(i <- 0 until 20) {
        d.io.trace.valid.poke(true.B); d.io.trace.bits.cycle.poke(i.U)
        d.io.trace.bits.pc.poke((0x40000000L+4*i).U); d.io.trace.bits.vaddr.poke((0x40000000L+4*i).U)
        d.io.trace.bits.paddr.poke((0x80000000L+4*i).U); d.io.trace.bits.satp.poke("h8000300000000100".U)
        d.io.trace.bits.flags.poke(5.U); d.clock.step()
      }
      d.io.trace.valid.poke(false.B)
      d.io.traceStatus.expect(((BigInt(4)<<32)|16).U)
      write(0x7d3,3*8+3); d.io.traceRead.expect((0x80000000L+12).U)
      write(0x7d3,3*8+4); d.io.traceRead.expect("h8000300000000100".U)
      write(0x7d3,3*8+6); d.io.traceRead.expect(0.U)
      write(0x7d0,(BigInt(2)<<16)|6) // every third eligible sample
      for(i <- 0 until 7) { d.io.trace.valid.poke(true.B); d.io.trace.bits.cycle.poke(i.U); d.clock.step() }
      d.io.trace.valid.poke(false.B); d.io.traceStatus.expect(3.U)
      write(0x7d3,2*8); d.io.traceRead.expect(6.U)
    }
  }
  "locality distinguishes resident, collision, recent eviction windows and fence invalidation" in {
    simulate(new BreezeInstructionLocality(BreezeMemGeometry(nCores=1,l1Sets=2,l1iWays=2))) { d =>
      d.io.demand.poke(0.U.asTypeOf(d.io.demand)); d.io.install.poke(0.U.asTypeOf(d.io.install)); d.io.fence.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def install(line:Int,victim:Int=0,evict:Boolean=false):Unit = {
        d.io.install.valid.poke(true.B); d.io.install.bits.line.poke(line.U); d.io.install.bits.way.poke(0.U)
        d.io.install.bits.evicted.poke(evict.B); d.io.install.bits.victim.poke(victim.U)
        d.clock.step(); d.io.install.valid.poke(false.B)
      }
      def demand(line:Int,miss:Boolean=true):Unit = {
        d.io.demand.valid.poke(true.B); d.io.demand.bits.line.poke(line.U); d.io.demand.bits.miss.poke(miss.B)
      }
      install(0x40); install(0x41)
      demand(0x40,false); d.clock.step(); d.io.demand.valid.poke(false.B)
      d.io.observation("next_line_resident").expect(1.U); d.clock.step()
      demand(0x42,false)
      d.io.install.valid.poke(true.B); d.io.install.bits.line.poke(0x43.U)
      d.clock.step(); d.io.install.valid.poke(false.B); d.io.demand.valid.poke(false.B)
      d.io.observation("next_line_unknown").expect(1.U); d.clock.step()
      for(i <- 0 until 20) install(0x200+i,0x100+i,true)
      demand(0x113)
      for(n <- Seq(4,8,16)) d.io.observation(s"recent_eviction_hit_$n").expect(1.U)
      d.clock.step(); demand(0x109)
      d.io.observation("recent_eviction_hit_4").expect(0.U)
      d.io.observation("recent_eviction_hit_8").expect(0.U)
      d.io.observation("recent_eviction_hit_16").expect(1.U)
      d.io.demand.valid.poke(false.B); install(0x109)
      demand(0x109); d.io.observation("recent_eviction_hit_16").expect(0.U)
      d.io.fence.poke(true.B); d.clock.step(); d.io.fence.poke(false.B)
      demand(0x113); d.io.observation("recent_eviction_hit_16").expect(0.U)
    }
  }
}

class BreezeObserveHpmSpec extends AnyFreeSpec with ChiselSim {
  "new raw selectors preserve multi-bit increments while legacy gaps stay illegal" in {
    simulate(new flow.core.BreezePerformanceCounters()) { d =>
      d.io.privilege.poke(3.U)
      d.io.events.poke(0.U.asTypeOf(d.io.events)); d.io.retire.poke(false.B)
      d.io.write.poke(false.B); d.io.address.poke(0.U); d.io.data.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def write(a:Int,v:BigInt):Unit = {
        d.io.write.poke(true.B); d.io.address.poke(a.U); d.io.data.poke(v.U); d.clock.step(); d.io.write.poke(false.B)
      }
      val name="ic_refill_latency"
      write(flow.core.CSRMAP.mhpmevent3,BreezeObserveEvent.raw(name))
      d.io.events.observation(name).poke(65537.U); d.clock.step(5)
      d.io.events.poke(0.U.asTypeOf(d.io.events)); d.clock.step(3)
      d.io.counter(0).expect((5*65537).U)
      write(0x7d1,BreezeObserveEvent.index(name)); d.io.observeData.expect((5*65537).U)
      for(invalid <- Seq(21,0x101,0xfff,BreezeObserveEvent.rawBase+BreezeObserveEvent.count)) {
        write(flow.core.CSRMAP.mhpmevent3,invalid); d.io.selector(0).expect(0.U)
      }
    }
  }
}
