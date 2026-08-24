package flow.accelerator.matrix

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class SystolicArraySpec extends AnyFreeSpec with ChiselSim {
  private def signed(value: BigInt, width: Int): BigInt = {
    if (value < 0) value
    else {
      val signBit = BigInt(1) << (width - 1)
      if ((value & signBit) != 0) value - (BigInt(1) << width) else value
    }
  }

  "PE forwards operands by one cycle and accumulates only paired valid inputs" in {
    simulate(new SystolicPE()) { dut =>
      dut.io.clear.poke(true.B)
      dut.io.loadAccumulator.poke(false.B)
      dut.io.accumulatorIn.poke(0.S)
      dut.io.aIn.poke(0.S); dut.io.bIn.poke(0.S)
      dut.io.aValidIn.poke(false.B); dut.io.bValidIn.poke(false.B)
      dut.clock.step()

      dut.io.clear.poke(false.B)
      dut.io.loadAccumulator.poke(true.B)
      dut.io.accumulatorIn.poke(7.S)
      dut.clock.step()
      assert(signed(dut.io.accumulator.peekValue().asBigInt, 32) == 7)
      dut.io.loadAccumulator.poke(false.B)
      dut.io.aIn.poke(3.S); dut.io.bIn.poke((-2).S)
      dut.io.aValidIn.poke(true.B); dut.io.bValidIn.poke(true.B)
      dut.clock.step()
      assert(signed(dut.io.accumulator.peekValue().asBigInt, 32) == 1)
      assert(signed(dut.io.aOut.peekValue().asBigInt, 8) == 3)
      assert(signed(dut.io.bOut.peekValue().asBigInt, 8) == -2)
      assert(dut.io.aValidOut.peek().litToBoolean)
      assert(dut.io.bValidOut.peek().litToBoolean)

      dut.io.aIn.poke((-4).S); dut.io.bIn.poke((-5).S)
      dut.clock.step()
      assert(signed(dut.io.accumulator.peekValue().asBigInt, 32) == 21)

      dut.io.aIn.poke(100.S); dut.io.bIn.poke(100.S)
      dut.io.aValidIn.poke(true.B); dut.io.bValidIn.poke(false.B)
      dut.clock.step()
      assert(signed(dut.io.accumulator.peekValue().asBigInt, 32) == 21)

      dut.io.clear.poke(true.B)
      dut.clock.step()
      assert(signed(dut.io.accumulator.peekValue().asBigInt, 32) == 0)
      assert(!dut.io.aValidOut.peek().litToBoolean)
      assert(!dut.io.bValidOut.peek().litToBoolean)
    }
  }

  "4x4 array computes a signed integer matrix product from skewed streams" in {
    val a = Seq(
      Seq(1, -2, 3, 4, -1),
      Seq(-3, 1, 2, -2, 5),
      Seq(4, 0, -1, 3, 2),
      Seq(-2, -3, 1, 0, 4))
    val b = Seq(
      Seq(2, -1, 3, 0),
      Seq(-2, 4, 1, -3),
      Seq(1, 2, -2, 5),
      Seq(3, -1, 0, 2),
      Seq(-1, 3, 4, -2))
    val expected = Seq.tabulate(4, 4) { case (row, col) =>
      (0 until a.head.size).map(k => a(row)(k) * b(k)(col)).sum
    }

    simulate(new SystolicArray()) { dut =>
      dut.io.clear.poke(true.B)
      for (row <- 0 until 4; col <- 0 until 4) {
        dut.io.loadAccumulator(row)(col).poke(false.B)
        dut.io.accumulatorIn(row)(col).poke(0.S)
      }
      for (lane <- 0 until 4) {
        dut.io.aIn(lane).poke(0.S); dut.io.aValid(lane).poke(false.B)
        dut.io.bIn(lane).poke(0.S); dut.io.bValid(lane).poke(false.B)
      }
      dut.clock.step()
      dut.io.clear.poke(false.B)

      val kLength = a.head.size
      for (cycle <- 0 until (kLength + 4 + 4 - 2)) {
        for (row <- 0 until 4) {
          val k = cycle - row
          val valid = k >= 0 && k < kLength
          dut.io.aValid(row).poke(valid.B)
          dut.io.aIn(row).poke((if (valid) a(row)(k) else 0).S)
        }
        for (col <- 0 until 4) {
          val k = cycle - col
          val valid = k >= 0 && k < kLength
          dut.io.bValid(col).poke(valid.B)
          dut.io.bIn(col).poke((if (valid) b(k)(col) else 0).S)
        }
        dut.clock.step()
      }

      for (row <- 0 until 4; col <- 0 until 4) {
        val actual = signed(dut.io.accumulators(row)(col).peekValue().asBigInt, 32)
        assert(actual == expected(row)(col),
          s"C($row,$col) expected ${expected(row)(col)}, got $actual")
      }
    }
  }
}
