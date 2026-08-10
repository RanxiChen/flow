package flow.divider

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class RiscvDivUnitSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val Mask64 = (BigInt(1) << 64) - 1

  private def reset(dut: RiscvDivUnit): Unit = {
    dut.io.flush.poke(false.B)
    dut.io.in_valid.poke(false.B)
    dut.io.dividend_mag.poke(0.U)
    dut.io.divisor_mag.poke(1.U)
    dut.io.quotient_neg.poke(false.B)
    dut.io.remainder_neg.poke(false.B)
    dut.io.is_remainder.poke(false.B)
    dut.io.is_word.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step(1)
    dut.reset.poke(false.B)
  }

  private def run(
      dut: RiscvDivUnit,
      dividendMag: BigInt,
      divisorMag: BigInt,
      quotientNeg: Boolean,
      remainderNeg: Boolean,
      isRemainder: Boolean,
      isWord: Boolean
  ): BigInt = {
    dut.io.dividend_mag.poke(dividendMag.U)
    dut.io.divisor_mag.poke(divisorMag.U)
    dut.io.quotient_neg.poke(quotientNeg.B)
    dut.io.remainder_neg.poke(remainderNeg.B)
    dut.io.is_remainder.poke(isRemainder.B)
    dut.io.is_word.poke(isWord.B)
    dut.io.in_valid.poke(true.B)
    dut.clock.step(1)
    dut.io.in_valid.poke(false.B)
    var cycles = 0
    while (!dut.io.out_valid.peek().litToBoolean && cycles < 33) {
      dut.clock.step(1)
      cycles += 1
    }
    dut.io.out_valid.expect(true.B)
    val result = dut.io.result.peekValue().asBigInt
    dut.clock.step(1)
    result
  }

  "wrapper restores quotient/remainder signs and W sign extension" in {
    simulate(new RiscvDivUnit) { dut =>
      reset(dut)
      run(dut, 20, 3, quotientNeg = true, remainderNeg = true,
        isRemainder = false, isWord = false) mustBe (BigInt(-6) & Mask64)
      run(dut, 20, 3, quotientNeg = true, remainderNeg = true,
        isRemainder = true, isWord = false) mustBe (BigInt(-2) & Mask64)
      run(dut, BigInt("fffffffe", 16), 1, quotientNeg = false, remainderNeg = false,
        isRemainder = false, isWord = true) mustBe BigInt("fffffffffffffffe", 16)
      run(dut, BigInt("fffffffe", 16), 3, quotientNeg = false, remainderNeg = false,
        isRemainder = true, isWord = true) mustBe 2
    }
  }
}
