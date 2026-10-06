package flow.backend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.divider.CommittedDivUnit
import flow.multiplier.MulUnit
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class DivDoneProbe extends CommittedDivUnit {
  val arithmeticDone = IO(Output(Bool()))
  val iterating = IO(Output(Bool()))
  arithmeticDone := done
  iterating := divider.io.busy
}

class MduBoundarySpec extends AnyFreeSpec with Matchers with ChiselSim {
  private def initDiv(d: CommittedDivUnit): Unit = {
    d.io.req.valid.poke(false.B); d.io.commit.poke(false.B); d.io.killUncommitted.poke(false.B)
    d.io.result.ready.poke(false.B); d.io.req.bits.rd.poke(5.U)
    d.io.req.bits.dividendMag.poke(0.U); d.io.req.bits.divisorMag.poke(1.U)
    d.io.req.bits.quotientNeg.poke(false.B); d.io.req.bits.remainderNeg.poke(false.B)
    d.io.req.bits.isRemainder.poke(false.B); d.io.req.bits.isWord.poke(false.B)
    d.io.req.bits.fastValid.poke(false.B); d.io.req.bits.fastData.poke(0.U)
    d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
  }
  "T06/S04: empty MUL commit triggers its RTL assertion" in {
    intercept[Exception] {
      simulate(new MulUnit) { d =>
        d.io.req.valid.poke(false.B); d.io.req.bits.a.poke(0.S); d.io.req.bits.b.poke(0.S)
        d.io.req.bits.op.poke(0.U); d.io.req.bits.rd.poke(5.U)
        d.io.commit.poke(false.B); d.io.killUncommitted.poke(false.B); d.io.result.ready.poke(false.B)
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B); d.clock.step()
        d.io.commit.poke(true.B); d.clock.step()
      }
    }
  }
  "T06/S04: empty DIV commit triggers its RTL assertion" in {
    intercept[Exception] {
      simulate(new CommittedDivUnit) { d =>
        initDiv(d); d.clock.step(); d.io.commit.poke(true.B); d.clock.step()
      }
    }
  }
  "T16/T17: fast done is one edge; short and maximum-span arithmetic latency is distinct from req-to-write" in {
    simulate(new DivDoneProbe) { d =>
      val max = (BigInt(1)<<64)-1
      for ((a,b) <- Vector((BigInt(0),BigInt(1)),(BigInt(1),BigInt(2)),(BigInt(5),BigInt(5)),(max,BigInt(1)))) {
        initDiv(d); d.io.req.bits.dividendMag.poke(a.U); d.io.req.bits.divisorMag.poke(b.U)
        d.io.req.valid.poke(true.B); d.clock.step(); d.io.req.valid.poke(false.B)
        var reqToDone = 1
        var iterations = 0
        while (!d.arithmeticDone.peek().litToBoolean && reqToDone < 35) {
          if (d.iterating.peek().litToBoolean) iterations += 1
          d.clock.step(); reqToDone += 1
        }
        d.arithmeticDone.expect(true.B); d.io.result.valid.expect(false.B)
        d.io.commit.poke(true.B); d.clock.step(); d.io.commit.poke(false.B)
        d.io.result.valid.expect(true.B); d.io.result.bits.data.peekValue().asBigInt mustBe a/b
        d.clock.step(8); d.io.result.ready.poke(true.B); d.clock.step()
        println(s"T01_DIV_LATENCY a=$a b=$b arithmetic_iterations=$iterations req_to_done=$reqToDone req_to_write=${reqToDone+10}")
      }
      initDiv(d); d.io.req.bits.fastValid.poke(true.B); d.io.req.bits.fastData.poke(max.U)
      d.io.req.valid.poke(true.B); d.clock.step(); d.io.req.valid.poke(false.B)
      d.arithmeticDone.expect(true.B); d.io.result.valid.expect(false.B)
      d.io.killUncommitted.poke(true.B); d.clock.step(); d.io.killUncommitted.poke(false.B)
      d.clock.step(40); d.io.result.valid.expect(false.B); d.io.req.ready.expect(true.B)
      initDiv(d); d.io.req.bits.fastValid.poke(true.B); d.io.req.valid.poke(true.B); d.clock.step()
      d.io.req.valid.poke(false.B); initDiv(d); d.clock.step(40)
      d.io.result.valid.expect(false.B)
      println("T01_DIV_FAST req_to_done=1 kill_before_commit=PASS reset_in_flight=PASS")
    }
  }
}
