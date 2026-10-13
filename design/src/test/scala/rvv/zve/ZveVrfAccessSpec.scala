package flow.rvv.zve

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class ZveVrfAccessSpec extends AnyFreeSpec with ChiselSim {
  "triplet ownership survives arbitration and backpressure; write ack follows physical BRAM" in {
    val p=ZveParams()
    simulate(new ZveVrfAccess(p,3)) { d =>
      for(port<-d.io.ports) {
        port.read.valid.poke(false.B); port.write.valid.poke(false.B)
        port.returned.ready.poke(false.B); port.written.ready.poke(false.B)
      }
      d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
      val values=(0 until 3).map(i => BigInt(f"${17+i}%02x"*64,16))
      for(c<-0 until 3) {
        val w=d.io.ports(c).write
        w.bits.tag.poke((10+c).U); w.bits.age.poke(c.U); w.bits.row.poke((4+c).U)
        w.bits.data.poke(values(c).U); w.bits.enables.poke(((BigInt(1)<<64)-1).U)
        w.valid.poke(true.B); w.ready.expect(true.B)
      }
      d.clock.step(); d.io.ports.foreach(_.write.valid.poke(false.B))
      d.io.ports.foreach(_.written.valid.expect(false.B))
      d.clock.step(3)
      for(c<-0 until 3) {
        d.io.ports(c).written.valid.expect(true.B)
        d.io.ports(c).written.bits.tag.expect((10+c).U)
        d.io.ports(c).written.ready.poke(true.B)
      }
      d.clock.step(); d.io.ports.foreach(_.written.ready.poke(false.B))
      for(c<-0 until 3) {
        val r=d.io.ports(c).read
        r.valid.poke(true.B); r.bits.tag.poke((100+c).U); r.bits.age.poke((3-c).U); r.bits.enables.poke(7.U)
        for(j<-0 until 3) { r.bits.rows(j).poke((4+(c+j)%3).U) }
      }
      val accepted=scala.collection.mutable.Set.empty[Int]
      var cycles=0
      while(accepted.size<3 && cycles<20) {
        val fires=(0 until 3).filter(c => !accepted(c) && d.io.ports(c).read.ready.peek().litToBoolean)
        d.clock.step(); fires.foreach { c => accepted+=c; d.io.ports(c).read.valid.poke(false.B) }
        cycles+=1
      }
      assert(accepted.size==3)
      d.clock.step(5)
      for(c<-0 until 3) {
        val r=d.io.ports(c).returned
        r.valid.expect(true.B); r.bits.tag.expect((100+c).U)
        for(j<-0 until 3) { r.bits.data(j).expect(values((c+j)%3).U) }
      }
      d.clock.step(4)
      for(c<-0 until 3) { d.io.ports(c).returned.bits.tag.expect((100+c).U) }
      d.io.drained.expect(false.B)
      d.io.ports.foreach(_.returned.ready.poke(true.B)); d.clock.step(3); d.io.drained.expect(true.B)
    }
  }
}
