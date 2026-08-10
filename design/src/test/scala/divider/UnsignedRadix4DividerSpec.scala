package flow.divider

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import scala.util.Random

class UnsignedRadix4DividerSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val Mask64 = (BigInt(1) << 64) - 1

  private def reset(dut: UnsignedRadix4Divider): Unit = {
    dut.io.flush.poke(false.B)
    dut.io.in_valid.poke(false.B)
    dut.io.dividend.poke(0.U)
    dut.io.divisor.poke(1.U)
    dut.reset.poke(true.B)
    dut.clock.step(1)
    dut.reset.poke(false.B)
  }

  private def run(dut: UnsignedRadix4Divider, dividend: BigInt, divisor: BigInt): (BigInt, BigInt, Int) = {
    dut.io.dividend.poke((dividend & Mask64).U)
    dut.io.divisor.poke((divisor & Mask64).U)
    dut.io.in_valid.poke(true.B)
    dut.clock.step(1)
    dut.io.in_valid.poke(false.B)

    var iterations = 0
    while (!dut.io.out_valid.peek().litToBoolean && iterations < 33) {
      dut.clock.step(1)
      iterations += 1
    }
    dut.io.out_valid.expect(true.B)
    val quotient = dut.io.quotient.peekValue().asBigInt
    val remainder = dut.io.remainder.peekValue().asBigInt
    dut.clock.step(1)
    dut.io.out_valid.expect(false.B)
    (quotient, remainder, iterations)
  }

  "radix-4 core handles directed and randomized unsigned divisions" in {
    simulate(new UnsignedRadix4Divider) { dut =>
      reset(dut)
      val directed = Seq(
        (BigInt(0), BigInt(7)),
        (BigInt(7), BigInt(9)),
        (BigInt(9), BigInt(9)),
        (BigInt(20), BigInt(3)),
        (Mask64, BigInt(1)),
        (Mask64, BigInt("ffffffff", 16)),
        (BigInt(1) << 63, BigInt(3))
      )
      val random = new Random(0x52445634L)
      val vectors = directed ++ Seq.fill(2000) {
        val dividend = BigInt(64, random)
        val divisor = BigInt(64, random) | 1
        (dividend, divisor)
      }

      for ((dividend, divisor) <- vectors) {
        val (quotient, remainder, iterations) = run(dut, dividend, divisor)
        quotient mustBe dividend / divisor
        remainder mustBe dividend % divisor
        iterations must be <= 32
      }
    }
  }

  "flush cancels an iterative request" in {
    simulate(new UnsignedRadix4Divider) { dut =>
      reset(dut)
      dut.io.dividend.poke(Mask64.U)
      dut.io.divisor.poke(3.U)
      dut.io.in_valid.poke(true.B)
      dut.clock.step(1)
      dut.io.in_valid.poke(false.B)
      dut.io.busy.expect(true.B)
      dut.io.flush.poke(true.B)
      dut.clock.step(1)
      dut.io.flush.poke(false.B)
      dut.io.busy.expect(false.B)
      dut.io.out_valid.expect(false.B)
      dut.clock.step(35)
      dut.io.out_valid.expect(false.B)
    }
  }
}
