package flow.rvv.zve

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class ZveFpArithmeticSpec extends AnyFreeSpec with ChiselSim {
  private def boxed(f: Float): BigInt = (BigInt("ffffffff",16)<<32) | BigInt(java.lang.Float.floatToRawIntBits(f)&0xffffffffL)
  "real FPnew FMA preserves fused precision and suppresses inactive invalid operations" in {
    simulate(new ZveFpBatchHarness(8,4,0)) { d =>
      d.io.in.valid.poke(false.B); d.io.out.ready.poke(false.B)
      d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
      val a=java.lang.Float.intBitsToFloat(0x3f800001)
      val b=java.lang.Float.intBitsToFloat(0x3f7ffffe)
      val expected=java.lang.Math.fma(a,b,-1.0f)
      val q=d.io.in.bits
      q.metadata.poke(42.U); q.active.poke(0x7f.U)
      q.operation.poke(0.U); q.modifier.poke(false.B); q.rounding.poke(0.U)
      q.sourceFormat.poke(0.U); q.destinationFormat.poke(0.U); q.integerFormat.poke(2.U)
      for(l<-0 until 8) {
        q.a(l).poke(boxed(if(l==7) Float.PositiveInfinity else a).U)
        q.b(l).poke(boxed(if(l==7) 0.0f else b).U); q.c(l).poke(boxed(-1.0f).U)
      }
      d.io.in.valid.poke(true.B); d.io.in.ready.expect(true.B); d.clock.step(); d.io.in.valid.poke(false.B)
      var waited=0
      while(!d.io.out.valid.peek().litToBoolean && waited<150) { d.clock.step(); waited+=1 }
      assert(waited<150,"real FMA batch did not return")
      for(l<-0 until 7) {
        assert((d.io.out.bits.data(l).peek().litValue & BigInt("ffffffff",16)) ==
          BigInt(java.lang.Float.floatToRawIntBits(expected)&0xffffffffL))
      }
      d.io.out.bits.data(7).expect(0.U); d.io.out.bits.flags.expect(0.U)
      d.clock.step(4); d.io.out.bits.metadata.expect(42.U)
      d.io.out.ready.poke(true.B); d.clock.step(3); d.io.drained.expect(true.B)
    }
  }
}
