package flow.backend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.multiplier.MulUnit
import flow.divider.DivUnit
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class DivProbe extends DivUnit {
  val iterating = IO(Output(Bool()))
  iterating := divider.io.busy
}
class MduTimingSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private def divIdle(d: DivUnit): Unit = {
    d.io.req.valid.poke(false.B); d.io.commit.poke(false.B); d.io.killUncommitted.poke(false.B)
    d.io.result.ready.poke(true.B); d.io.req.bits.rd.poke(5.U)
    d.io.req.bits.dividendMag.poke(0.U); d.io.req.bits.divisorMag.poke(1.U)
    d.io.req.bits.quotientNeg.poke(false.B); d.io.req.bits.remainderNeg.poke(false.B)
    d.io.req.bits.isRemainder.poke(false.B); d.io.req.bits.isWord.poke(false.B)
    d.io.req.bits.fastValid.poke(false.B); d.io.req.bits.fastData.poke(0.U)
  }
  "T04_component_P02_component_S11: eight MUL requests and writes, each exactly EX+4" in {
    simulate(new MulUnit) { d =>
      d.io.req.valid.poke(false.B); d.io.commit.poke(false.B); d.io.killUncommitted.poke(false.B)
      d.io.result.ready.poke(true.B); d.io.req.bits.a.poke(7.S); d.io.req.bits.b.poke(9.S)
      d.io.req.bits.op.poke(0.U); d.io.req.bits.rd.poke(1.U)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      for (cycle <- 0 until 13) {
        d.io.req.valid.poke((cycle < 8).B); d.io.req.bits.rd.poke((if (cycle < 8) cycle + 1 else 1).U)
        d.io.commit.poke((cycle >= 2 && cycle < 10).B)
        if (cycle < 8) d.io.req.ready.expect(true.B)
        val write = cycle >= 4 && cycle < 12
        d.io.result.valid.expect(write.B)
        if (write) { d.io.result.bits.rd.expect((cycle - 3).U); d.io.result.bits.data.expect(63.U) }
        d.clock.step()
      }
    }
  }
  "T05_component_T07_component_S12: DIV fast write EX+3 and next receive write+1" in {
    simulate(new DivUnit) { d =>
      divIdle(d); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val max = (BigInt(1) << 64) - 1
      d.io.req.bits.fastValid.poke(true.B); d.io.req.bits.fastData.poke(max.U)
      d.io.req.valid.poke(true.B); d.io.req.ready.expect(true.B); d.clock.step()
      // A second independent request stays offered while the first is occupied.
      for (cycle <- 1 to 3) {
        d.io.commit.poke((cycle == 2).B); d.io.req.ready.expect(false.B)
        d.io.result.valid.expect((cycle == 3).B)
        if (cycle == 3) d.io.result.bits.data.expect(max.U)
        d.clock.step()
      }
      d.io.commit.poke(false.B); d.io.req.ready.expect(true.B)
      d.io.result.valid.expect(false.B); d.clock.step() // receive at E+4
      d.io.req.valid.poke(false.B); d.clock.step(); d.io.commit.poke(true.B)
      d.clock.step(); d.io.commit.poke(false.B); d.io.result.valid.expect(true.B)
    }
  }
  "T06_component: regular DIV writes EX+2+actual arithmetic iteration count" in {
    simulate(new DivProbe) { d =>
      divIdle(d); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      val max = (BigInt(1) << 64) - 1
      d.io.req.bits.dividendMag.poke(max.U); d.io.req.bits.divisorMag.poke(1.U)
      var iterations = 0
      var writes = Vector.empty[Int]
      for (cycle <- 0 until 40) {
        d.io.req.valid.poke((cycle == 0).B); d.io.commit.poke((cycle == 2).B)
        if (d.iterating.peek().litToBoolean) iterations += 1
        if (d.io.result.valid.peek().litToBoolean) {
          writes :+= cycle; d.io.result.bits.data.expect(max.U)
        }
        d.clock.step()
      }
      iterations mustBe 32
      writes mustBe Vector(2 + iterations)
    }
  }
}
