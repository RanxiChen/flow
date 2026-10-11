package flow.rvv

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.bus._
import org.scalatest.freespec.AnyFreeSpec

class RvvAxiArbiterSpec extends AnyFreeSpec with ChiselSim {
  "registered addresses retain FIFO/source ownership under backpressure and bound scalar priority" in {
    simulate(new RvvAxiArbiter(32,512)) { d =>
      val clients=Seq(d.io.scalar,d.io.vector)
      def addr(a: Axi4Ar, value: Int): Unit = {
        a.id.poke(0.U); a.addr.poke(value.U); a.len.poke(0.U)
        a.size.poke(6.U); a.burst.poke(1.U); a.prot.poke(0.U)
      }
      for(c <- clients) {
        c.ar.valid.poke(false.B); c.aw.valid.poke(false.B); c.w.valid.poke(false.B)
        addr(c.ar.bits,0); addr(c.aw.bits,0)
        c.w.bits.data.poke(0.U); c.w.bits.strb.poke(0.U); c.w.bits.last.poke(true.B)
        c.r.ready.poke(true.B); c.b.ready.poke(true.B)
      }
      d.io.mem.ar.ready.poke(false.B); d.io.mem.aw.ready.poke(false.B); d.io.mem.w.ready.poke(false.B)
      d.io.mem.r.valid.poke(false.B); d.io.mem.b.valid.poke(false.B)
      d.io.mem.r.bits.id.poke(0.U); d.io.mem.r.bits.data.poke(0.U)
      d.io.mem.r.bits.resp.poke(0.U); d.io.mem.r.bits.last.poke(true.B)
      d.io.mem.b.bits.id.poke(0.U); d.io.mem.b.bits.resp.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      // Local acceptance reserves the source response slot even while the
      // downstream address channel is stopped. No combinational bypass.
      addr(d.io.scalar.ar.bits,0x1000); d.io.scalar.ar.valid.poke(true.B)
      d.io.mem.ar.valid.expect(false.B); d.io.scalar.ar.ready.expect(true.B); d.clock.step()
      d.io.scalar.ar.valid.poke(false.B)
      addr(d.io.vector.ar.bits,0x2000); d.io.vector.ar.valid.poke(true.B)
      d.io.vector.ar.ready.expect(true.B); d.clock.step()
      addr(d.io.vector.ar.bits,0x3000)
      d.io.vector.ar.ready.expect(false.B)
      for(_ <- 0 until 3) {
        d.io.mem.ar.valid.expect(true.B); d.io.mem.ar.bits.addr.expect(0x1000.U)
        d.io.mem.ar.bits.id.expect(0.U); d.clock.step()
      }
      d.io.vector.ar.valid.poke(false.B); d.io.mem.ar.ready.poke(true.B)
      d.clock.step(); d.io.mem.ar.bits.addr.expect(0x2000.U); d.io.mem.ar.bits.id.expect(2.U)
      d.clock.step(); d.io.mem.ar.valid.expect(false.B)
      // Concurrent demand: no more than eight scalar addresses precede the
      // waiting vector address, with the ID attached to the queued payload.
      addr(d.io.scalar.ar.bits,0x4000); addr(d.io.vector.ar.bits,0x5000)
      clients.foreach(_.ar.valid.poke(true.B))
      var scalarRun=0; var vectorSeen=false
      for(_ <- 0 until 24) {
        if(d.io.mem.ar.valid.peek().litToBoolean) {
          if(d.io.mem.ar.bits.id.peek().litValue == 2) { vectorSeen=true; scalarRun=0 }
          else { scalarRun+=1; assert(scalarRun<=8,"scalar arbitration starved vector") }
        }
        d.clock.step()
      }
      assert(vectorSeen); clients.foreach(_.ar.valid.poke(false.B)); d.clock.step(3)
      // AW may be accepted into the queue first, but W stays with that owner
      // until its last beat. The other source cannot replace it under stall.
      addr(d.io.vector.aw.bits,0x6000); d.io.vector.aw.valid.poke(true.B)
      d.io.vector.aw.ready.expect(true.B); d.clock.step(); d.io.vector.aw.valid.poke(false.B)
      d.io.vector.w.valid.poke(true.B); d.io.vector.w.bits.data.poke(0x66.U)
      d.io.vector.w.bits.strb.poke(((BigInt(1)<<64)-1).U)
      addr(d.io.scalar.aw.bits,0x7000); d.io.scalar.aw.valid.poke(true.B)
      d.io.scalar.w.valid.poke(true.B); d.io.scalar.w.bits.data.poke(0x77.U)
      for(_ <- 0 until 3) {
        d.io.scalar.aw.ready.expect(false.B); d.io.mem.aw.bits.addr.expect(0x6000.U)
        d.io.mem.aw.bits.id.expect(2.U); d.io.mem.w.bits.data.expect(0x66.U); d.clock.step()
      }
      d.io.mem.aw.ready.poke(true.B); d.clock.step()
      d.io.mem.w.ready.poke(true.B); d.io.vector.w.ready.expect(true.B)
      d.io.scalar.w.ready.expect(false.B); d.clock.step(); d.io.vector.w.valid.poke(false.B)
      d.io.scalar.aw.ready.expect(true.B); d.clock.step(); d.io.scalar.aw.valid.poke(false.B)
      d.io.mem.aw.bits.addr.expect(0x7000.U); d.io.mem.aw.bits.id.expect(0.U)
      d.io.mem.w.bits.data.expect(0x77.U); d.clock.step(); d.io.scalar.w.valid.poke(false.B)
      // The extra address buffering never changes response routing or masks
      // an error. Both IDs and R/B payloads are returned to the original source.
      for(id <- Seq(0,2)) {
        val source=id/2
        d.io.mem.r.valid.poke(true.B); d.io.mem.r.bits.id.poke(id.U)
        d.io.mem.r.bits.data.poke(0x99.U); d.io.mem.r.bits.resp.poke(2.U)
        d.io.mem.b.valid.poke(true.B); d.io.mem.b.bits.id.poke(id.U); d.io.mem.b.bits.resp.poke(2.U)
        clients(source).r.valid.expect(true.B); clients(1-source).r.valid.expect(false.B)
        clients(source).r.bits.data.expect(0x99.U); clients(source).r.bits.resp.expect(2.U)
        clients(source).b.valid.expect(true.B); clients(1-source).b.valid.expect(false.B)
        clients(source).b.bits.resp.expect(2.U); d.clock.step()
      }
    }
  }
}
