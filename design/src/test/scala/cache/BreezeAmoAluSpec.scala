package flow.cache

import chisel3._
import chisel3.simulator.PeekPokeAPI
import chisel3.simulator.scalatest.ChiselSim
import flow.interface.BreezeAmoFunc
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** Directed vectors for the combinational AMO ALU: all 9 operations at both
  * widths, signed/unsigned min/max boundaries, wrap-around adds and the
  * W-width rule (only the low 32 bits participate; the upper result bits are
  * unspecified and masked by the caller).
  */
class BreezeAmoAluSpec extends AnyFreeSpec with Matchers with ChiselSim with PeekPokeAPI {
  private val mask64 = (BigInt(1) << 64) - 1
  private val mask32 = (BigInt(1) << 32) - 1

  private case class Vec64(func: BreezeAmoFunc.Type, old: BigInt, rs2: BigInt, expect: BigInt)

  private val dVectors = Seq(
    Vec64(BreezeAmoFunc.Swap, BigInt("0123456789abcdef", 16), BigInt("fedcba9876543210", 16),
      BigInt("fedcba9876543210", 16)),
    Vec64(BreezeAmoFunc.Add, BigInt(41), BigInt(1), BigInt(42)),
    // Add wraps modulo 2^64.
    Vec64(BreezeAmoFunc.Add, mask64, BigInt(1), BigInt(0)),
    Vec64(BreezeAmoFunc.Xor, BigInt("ff00ff00ff00ff00", 16), BigInt("0ff00ff00ff00ff0", 16),
      BigInt("f0f0f0f0f0f0f0f0", 16)),
    Vec64(BreezeAmoFunc.And, BigInt("ffff0000ffff0000", 16), BigInt("ff00ff00ff00ff00", 16),
      BigInt("ff000000ff000000", 16)),
    Vec64(BreezeAmoFunc.Or, BigInt("ffff0000ffff0000", 16), BigInt("00ff00ff00ff00ff", 16),
      BigInt("ffff00ffffff00ff", 16)),
    // Signed 64-bit boundaries: INT64_MIN vs INT64_MAX.
    Vec64(BreezeAmoFunc.Min, BigInt("8000000000000000", 16), BigInt("7fffffffffffffff", 16),
      BigInt("8000000000000000", 16)),
    Vec64(BreezeAmoFunc.Max, BigInt("8000000000000000", 16), BigInt("7fffffffffffffff", 16),
      BigInt("7fffffffffffffff", 16)),
    // Signed: -1 vs 1.
    Vec64(BreezeAmoFunc.Min, mask64, BigInt(1), mask64),
    Vec64(BreezeAmoFunc.Max, mask64, BigInt(1), BigInt(1)),
    // Unsigned boundaries: UINT64_MAX vs 1.
    Vec64(BreezeAmoFunc.MinU, mask64, BigInt(1), BigInt(1)),
    Vec64(BreezeAmoFunc.MaxU, mask64, BigInt(1), mask64),
    Vec64(BreezeAmoFunc.MinU, BigInt(0), mask64, BigInt(0)),
    Vec64(BreezeAmoFunc.MaxU, BigInt(0), mask64, mask64)
  )

  // (func, old32, rs232, expect32); the harness plants garbage in the upper
  // 32 bits of both operands to prove W ops ignore them.
  private val wVectors = Seq(
    (BreezeAmoFunc.Swap, BigInt("12345678", 16), BigInt("9abcdef0", 16), BigInt("9abcdef0", 16)),
    (BreezeAmoFunc.Add, mask32, BigInt(1), BigInt(0)), // wraps modulo 2^32
    (BreezeAmoFunc.Add, BigInt(40), BigInt(2), BigInt(42)),
    (BreezeAmoFunc.Xor, BigInt("ff00ff00", 16), BigInt("0ff00ff0", 16), BigInt("f0f0f0f0", 16)),
    (BreezeAmoFunc.And, BigInt("ffff0000", 16), BigInt("ff00ff00", 16), BigInt("ff000000", 16)),
    (BreezeAmoFunc.Or, BigInt("ffff0000", 16), BigInt("00ff00ff", 16), BigInt("ffff00ff", 16)),
    // Signed 32-bit boundaries: INT32_MIN vs INT32_MAX.
    (BreezeAmoFunc.Min, BigInt("80000000", 16), BigInt("7fffffff", 16), BigInt("80000000", 16)),
    (BreezeAmoFunc.Max, BigInt("80000000", 16), BigInt("7fffffff", 16), BigInt("7fffffff", 16)),
    // Signed: -1 (0xffffffff) vs 1.
    (BreezeAmoFunc.Min, mask32, BigInt(1), mask32),
    (BreezeAmoFunc.Max, mask32, BigInt(1), BigInt(1)),
    // Unsigned boundaries.
    (BreezeAmoFunc.MinU, mask32, BigInt(1), BigInt(1)),
    (BreezeAmoFunc.MaxU, mask32, BigInt(1), mask32)
  )

  "AMO ALU computes all 64-bit operations including signed/unsigned boundaries" in {
    simulate(new BreezeAmoAlu) { dut =>
      for (v <- dVectors) {
        dut.io.func.poke(v.func)
        dut.io.isWord.poke(false.B)
        dut.io.oldOperand.poke(v.old.U)
        dut.io.rs2.poke(v.rs2.U)
        dut.clock.step(1)
        withClue(s"${v.func} old=${v.old.toString(16)} rs2=${v.rs2.toString(16)}: ") {
          dut.io.newOperand.peek().litValue mustBe v.expect
        }
      }
    }
  }

  "AMO ALU computes all 32-bit operations on the low word only" in {
    simulate(new BreezeAmoAlu) { dut =>
      val garbage = BigInt("deadbeef", 16) << 32
      for ((func, old32, rs232, expect32) <- wVectors) {
        dut.io.func.poke(func)
        dut.io.isWord.poke(true.B)
        dut.io.oldOperand.poke((garbage | old32).U)
        dut.io.rs2.poke((garbage | rs232).U)
        dut.clock.step(1)
        withClue(s"$func old=${old32.toString(16)} rs2=${rs232.toString(16)}: ") {
          (dut.io.newOperand.peek().litValue & mask32) mustBe expect32
        }
      }
    }
  }
}
