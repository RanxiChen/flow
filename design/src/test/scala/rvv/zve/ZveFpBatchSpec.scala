package flow.rvv.zve

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class ZveFpBatchSpec extends AnyFreeSpec with ChiselSim {
  "out-of-order lane responses, inactive exceptions and full output backpressure retain ownership" in {
    simulate(new ZveFpBatchPipeline(4,4,16)) { d =>
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B)
      d.io.fp.foreach { f => f.request.ready.poke(false.B); f.response.valid.poke(false.B) }
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def batch(id: Int, active: Int): Unit = {
        val b=d.io.in.bits
        b.metadata.poke(id.U); b.active.poke(active.U)
        for(l<-0 until 4) { b.a(l).poke((id*10+l).U); b.b(l).poke(2.U); b.c(l).poke(3.U) }
        b.operation.poke(0.U); b.modifier.poke(false.B); b.rounding.poke(0.U)
        b.sourceFormat.poke(0.U); b.destinationFormat.poke(0.U); b.integerFormat.poke(2.U)
        d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      }
      batch(11,5) // lanes 1 and 3 are inactive: they must never execute.
      batch(22,15)
      d.io.fp(1).request.valid.expect(false.B); d.io.fp(3).request.valid.expect(false.B)
      val firstTag0=d.io.fp(0).request.bits.tag.peek().litValue
      val firstTag2=d.io.fp(2).request.bits.tag.peek().litValue
      d.io.fp(2).request.ready.poke(true.B); d.clock.step(); d.io.fp(2).request.ready.poke(false.B)
      d.clock.step(3); d.io.fp(0).request.bits.tag.expect(firstTag0.U)
      d.io.fp(0).request.bits.a.expect(110.U)
      d.io.fp(0).request.ready.poke(true.B); d.clock.step(); d.io.fp(0).request.ready.poke(false.B)
      d.io.fp.foreach(_.request.ready.poke(true.B)); d.clock.step()
      val secondTags=(0 until 4).map(l => BigInt(4+l))
      def respond(l: Int, tag: BigInt, value: Int, flags: Int): Unit = {
        val r=d.io.fp(l).response
        r.bits.tag.poke(tag.U); r.bits.data.poke(value.U); r.bits.flags.poke(flags.U)
        r.valid.poke(true.B); r.ready.expect(true.B); d.clock.step(); r.valid.poke(false.B)
      }
      // A later batch completes first; architectural output remains ordered.
      for(l<-(0 until 4).reverse) { respond(l,secondTags(l),220+l,1<<l) }
      d.io.out.valid.expect(false.B)
      respond(2,firstTag2,112,4); d.io.out.valid.expect(false.B)
      respond(0,firstTag0,110,1)
      d.io.out.valid.expect(true.B); d.io.out.bits.metadata.expect(11.U)
      d.io.out.bits.flags.expect(5.U); d.io.out.bits.data(0).expect(110.U); d.io.out.bits.data(2).expect(112.U)
      d.io.out.bits.data(1).expect(0.U); d.io.out.bits.data(3).expect(0.U)
      d.clock.step(5); d.io.out.bits.metadata.expect(11.U); d.io.drained.expect(false.B)
      d.io.out.ready.poke(true.B); d.clock.step()
      d.io.out.bits.metadata.expect(22.U); d.io.out.bits.flags.expect(15.U)
      d.clock.step(); d.io.drained.expect(true.B)
      d.io.out.ready.poke(false.B)
      batch(33,0); d.clock.step(2); d.io.out.valid.expect(true.B); d.io.out.bits.flags.expect(0.U)
      d.io.out.ready.poke(true.B); d.clock.step(); d.io.drained.expect(true.B)
    }
  }
}
