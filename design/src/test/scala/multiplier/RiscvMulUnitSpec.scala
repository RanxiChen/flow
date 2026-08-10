package flow.multiplier

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.core.MUL_OP
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class RiscvMulUnitSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val Mask64 = (BigInt(1) << 64) - 1

  private def reset(dut: RiscvMulUnit): Unit = {
    dut.io.flush.poke(false.B)
    dut.io.in_valid.poke(false.B)
    dut.io.a.poke(0.S(65.W))
    dut.io.b.poke(0.S(65.W))
    dut.io.op.poke(MUL_OP.MUL.U)
    dut.reset.poke(true.B)
    dut.clock.step(1)
    dut.reset.poke(false.B)
  }

  private def run(dut: RiscvMulUnit, a: BigInt, b: BigInt, op: Int): BigInt = {
    dut.io.a.poke(a.S(65.W))
    dut.io.b.poke(b.S(65.W))
    dut.io.op.poke(op.U)
    dut.io.in_valid.poke(true.B)
    dut.clock.step(1)
    dut.io.in_valid.poke(false.B)
    dut.clock.step(2)
    dut.io.out_valid.expect(true.B)
    val result = dut.io.result.peekValue().asBigInt
    dut.clock.step(1)
    dut.io.out_valid.expect(false.B)
    result
  }

  "wrapper selects all multiplication result forms" in {
    simulate(new RiscvMulUnit) { dut =>
      reset(dut)

      run(dut, -2, 3, MUL_OP.MUL) mustBe ((BigInt(-6)) & Mask64)
      run(dut, -2, 3, MUL_OP.MULH) mustBe Mask64
      run(dut, -2, Mask64, MUL_OP.MULHSU) mustBe (Mask64 - 1)
      run(dut, Mask64, Mask64, MUL_OP.MULHU) mustBe (Mask64 - 1)
      run(dut, -2, 3, MUL_OP.MULW) mustBe ((BigInt(-6)) & Mask64)
    }
  }

  "flush cancels a stale completion token" in {
    simulate(new RiscvMulUnit) { dut =>
      reset(dut)
      dut.io.a.poke(7.S(65.W))
      dut.io.b.poke(9.S(65.W))
      dut.io.op.poke(MUL_OP.MUL.U)
      dut.io.in_valid.poke(true.B)
      dut.clock.step(1)
      dut.io.in_valid.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.clock.step(1)
      dut.io.flush.poke(false.B)
      dut.clock.step(3)
      dut.io.out_valid.expect(false.B)
    }
  }
}
