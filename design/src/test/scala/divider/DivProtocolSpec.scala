package flow.divider

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class DivProtocolSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val mask = (BigInt(1) << 64) - 1
  private def init(d: DivUnit): Unit = {
    d.io.req.valid.poke(false.B); d.io.commit.poke(false.B); d.io.killUncommitted.poke(false.B)
    d.io.result.ready.poke(false.B); d.io.req.bits.rd.poke(5.U)
    d.io.req.bits.dividendMag.poke(0.U); d.io.req.bits.divisorMag.poke(1.U)
    d.io.req.bits.quotientNeg.poke(false.B); d.io.req.bits.remainderNeg.poke(false.B)
    d.io.req.bits.isRemainder.poke(false.B); d.io.req.bits.isWord.poke(false.B)
    d.io.req.bits.fastValid.poke(false.B); d.io.req.bits.fastData.poke(0.U)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
  }
  "T05/T06/T09/T17: early done waits, commit before kill, stalled result survives kill and release has a gap" in {
    simulate(new DivUnit) { d =>
      init(d); d.io.req.bits.fastValid.poke(true.B); d.io.req.bits.fastData.poke(63.U)
      d.io.req.valid.poke(true.B); d.clock.step(); d.io.req.valid.poke(false.B)
      d.clock.step(9); d.io.req.ready.expect(false.B); d.io.result.valid.expect(false.B)
      d.io.commit.poke(true.B); d.io.killUncommitted.poke(true.B); d.clock.step()
      d.io.commit.poke(false.B); d.io.killUncommitted.poke(false.B)
      d.io.result.valid.expect(true.B); d.io.result.bits.data.expect(63.U)
      d.io.killUncommitted.poke(true.B); d.clock.step(7); d.io.result.valid.expect(true.B)
      d.io.killUncommitted.poke(false.B); d.io.result.ready.poke(true.B)
      d.io.req.ready.expect(false.B); d.clock.step(); d.io.req.ready.expect(true.B)
      d.io.result.ready.poke(false.B); d.io.req.bits.fastValid.poke(false.B)
      d.io.req.bits.dividendMag.poke(mask.U); d.io.req.bits.divisorMag.poke(3.U)
      d.io.req.valid.poke(true.B); d.clock.step(); d.io.req.valid.poke(false.B); d.clock.step(5)
      d.io.killUncommitted.poke(true.B); d.clock.step(); d.io.killUncommitted.poke(false.B)
      d.clock.step(40); d.io.result.valid.expect(false.B); d.io.req.ready.expect(true.B)
    }
  }
  "B/T16/T17/U02: eight operations and special values, random commit delay/backpressure (seed 0x702, 512 requests)" in {
    simulate(new DivUnit) { d =>
      init(d); val rng = new scala.util.Random(0x702)
      def signed(x: BigInt,w: Int): BigInt = if (x.testBit(w-1)) x-(BigInt(1)<<w) else x
      for (i <- 0 until 512) {
        val w = if ((i & 4) != 0) 32 else 64
        val signedOp = (i & 1) == 0; val remOp = (i & 2) != 0
        val wm = (BigInt(1)<<w)-1
        val au = if (i % 32 < 16) BigInt(1)<<(w-1) else BigInt(w,rng)
        val bu = if (i % 16 < 8) BigInt(0) else if (i % 32 < 16) wm else BigInt(w,rng)
        val a = if (signedOp) signed(au,w) else au; val b = if (signedOp) signed(bu,w) else bu
        val raw = if (b == 0) { if (remOp) a else wm }
          else if (signedOp && a == -(BigInt(1)<<(w-1)) && b == -1) { if (remOp) BigInt(0) else a }
          else if (remOp) a % b else a / b
        val expected = (if (w == 32) signed(raw & wm,32) else raw) & mask
        val fast = b == 0 || (signedOp && a == -(BigInt(1)<<(w-1)) && b == -1)
        d.io.req.ready.expect(true.B); d.io.req.bits.rd.poke((1+i%31).U)
        d.io.req.bits.dividendMag.poke(a.abs.U); d.io.req.bits.divisorMag.poke(b.abs.U)
        d.io.req.bits.quotientNeg.poke((signedOp && ((a<0)!=(b<0))).B)
        d.io.req.bits.remainderNeg.poke((signedOp && a<0).B)
        d.io.req.bits.isRemainder.poke(remOp.B); d.io.req.bits.isWord.poke((w==32).B)
        d.io.req.bits.fastValid.poke(fast.B); d.io.req.bits.fastData.poke(expected.U)
        d.io.req.valid.poke(true.B); d.clock.step(); d.io.req.valid.poke(false.B)
        d.io.result.valid.expect(false.B); d.clock.step(rng.nextInt(40))
        d.io.result.valid.expect(false.B); d.io.commit.poke(true.B); d.clock.step(); d.io.commit.poke(false.B)
        var waited = 0
        while (!d.io.result.valid.peek().litToBoolean && waited < 35) { d.clock.step(); waited += 1 }
        d.io.result.valid.expect(true.B); d.io.result.bits.data.peekValue().asBigInt mustBe expected
        d.io.result.bits.rd.expect((1+i%31).U); d.clock.step(rng.nextInt(10))
        d.io.result.bits.data.peekValue().asBigInt mustBe expected
        d.io.result.ready.poke(true.B); d.io.req.ready.expect(false.B); d.clock.step()
        d.io.result.ready.poke(false.B); d.io.req.ready.expect(true.B)
      }
    }
  }
}
