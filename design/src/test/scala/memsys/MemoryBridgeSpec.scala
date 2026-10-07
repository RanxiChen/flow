package flow.memsys

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.bus._
import flow.coherence._
import flow.config.BreezeMemGeometry
import org.scalatest.freespec.AnyFreeSpec

class MemoryBridgeSpec extends AnyFreeSpec with ChiselSim {
  "MMIO arbitration retains owner across independent W/AW and a stalled response" in {
    simulate(new Axi4LiteArbiter(2)) { d =>
      for (c <- d.io.clients) {
        c.ar.valid.poke(false.B); c.ar.bits.addr.poke(0.U); c.ar.bits.prot.poke(0.U)
        c.aw.valid.poke(false.B); c.aw.bits.addr.poke(0.U); c.aw.bits.prot.poke(0.U)
        c.w.valid.poke(false.B); c.w.bits.data.poke(0.U); c.w.bits.strb.poke(0.U)
        c.r.ready.poke(false.B); c.b.ready.poke(false.B)
      }
      d.io.out.ar.ready.poke(true.B); d.io.out.aw.ready.poke(false.B); d.io.out.w.ready.poke(true.B)
      d.io.out.r.valid.poke(false.B); d.io.out.r.bits.data.poke(0.U); d.io.out.r.bits.resp.poke(0.U)
      d.io.out.b.valid.poke(false.B); d.io.out.b.bits.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val c = d.io.clients(0); val other = d.io.clients(1)
      c.w.valid.poke(true.B); c.w.bits.data.poke(77.U); c.w.bits.strb.poke(0xff.U)
      d.clock.step(); d.io.out.w.valid.expect(true.B); d.clock.step(); c.w.valid.poke(false.B)
      other.ar.valid.poke(true.B); other.ar.bits.addr.poke(0x200.U)
      c.aw.valid.poke(true.B); c.aw.bits.addr.poke(0x100.U)
      for (_ <- 0 until 4) { d.io.out.aw.bits.addr.expect(0x100.U); other.ar.ready.expect(false.B); d.clock.step() }
      d.io.out.aw.ready.poke(true.B); d.clock.step(); c.aw.valid.poke(false.B)
      d.io.out.b.valid.poke(true.B); d.io.out.b.bits.poke(2.U)
      for (_ <- 0 until 4) { c.b.valid.expect(true.B); other.b.valid.expect(false.B); other.ar.ready.expect(false.B); d.clock.step() }
      c.b.ready.poke(true.B); d.clock.step(); d.io.out.b.valid.poke(false.B)
      d.clock.step(); other.ar.ready.expect(true.B); d.io.out.ar.bits.addr.expect(0x200.U)
    }
  }
}
