package flow.divider

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class DivUnitSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val Mask64 = (BigInt(1) << 64) - 1

  private def reset(dut: DivUnit): Unit = {
    dut.io.killUncommitted.poke(false.B)
    dut.io.commit.poke(false.B)
    dut.io.result.ready.poke(true.B)
    dut.io.req.bits.rd.poke(5.U)
    dut.io.req.bits.fastValid.poke(false.B)
    dut.io.req.bits.fastData.poke(0.U)
    dut.io.req.valid.poke(false.B)
    dut.io.req.bits.dividendMag.poke(0.U)
    dut.io.req.bits.divisorMag.poke(1.U)
    dut.io.req.bits.quotientNeg.poke(false.B)
    dut.io.req.bits.remainderNeg.poke(false.B)
    dut.io.req.bits.isRemainder.poke(false.B)
    dut.io.req.bits.isWord.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step(1)
    dut.reset.poke(false.B)
  }

  private def run(
      dut: DivUnit,
      dividendMag: BigInt,
      divisorMag: BigInt,
      quotientNeg: Boolean,
      remainderNeg: Boolean,
      isRemainder: Boolean,
      isWord: Boolean
  ): BigInt = {
    dut.io.req.bits.dividendMag.poke(dividendMag.U)
    dut.io.req.bits.divisorMag.poke(divisorMag.U)
    dut.io.req.bits.quotientNeg.poke(quotientNeg.B)
    dut.io.req.bits.remainderNeg.poke(remainderNeg.B)
    dut.io.req.bits.isRemainder.poke(isRemainder.B)
    dut.io.req.bits.isWord.poke(isWord.B)
    dut.io.req.valid.poke(true.B)
    dut.clock.step(1)
    dut.io.req.valid.poke(false.B)
    dut.io.commit.poke(true.B)
    dut.clock.step(1)
    dut.io.commit.poke(false.B)
    var cycles = 0
    while (!dut.io.result.valid.peek().litToBoolean && cycles < 33) {
      dut.clock.step(1)
      cycles += 1
    }
    dut.io.result.valid.expect(true.B)
    val result = dut.io.result.bits.data.peekValue().asBigInt
    dut.clock.step(1)
    result
  }

  "wrapper restores quotient/remainder signs and W sign extension" in {
    simulate(new DivUnit) { dut =>
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
