package flow.multiplier

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class SignedMul65x65Spec extends AnyFreeSpec with Matchers with ChiselSim {

  private val Mask65  = (BigInt(1) << 65) - 1
  private val Mask130 = (BigInt(1) << 130) - 1

  /** Interpret a 65-bit unsigned bit-pattern as a signed BigInt. */
  private def asSigned65(raw: BigInt): BigInt = {
    val u = raw & Mask65
    if (u.testBit(64)) u - (BigInt(1) << 65) else u
  }

  /** Golden 65×65 signed product (lower 130 bits, two's complement). */
  private def golden(rawA: BigInt, rawB: BigInt): BigInt = {
    val sa = asSigned65(rawA)
    val sb = asSigned65(rawB)
    val product = sa * sb
    // Convert 130-bit raw pattern to signed (matching SInt peekValue().asBigInt)
    val u = product & Mask130
    if (u.testBit(129)) u - (BigInt(1) << 130) else u
  }

  /** Poke a 65-bit raw value into an SInt port (handles sign). */
  private def pokeS65(dut: SignedMul65x65, portA: Boolean, raw: BigInt): Unit = {
    val signed = asSigned65(raw)
    if (portA) dut.io.a.poke(signed.S(65.W))
    else       dut.io.b.poke(signed.S(65.W))
  }

  /** Drive one multiply through the 3-stage pipeline and return the raw result. */
  private def runOne(dut: SignedMul65x65, a: BigInt, b: BigInt): BigInt = {
    dut.io.in_valid.poke(true.B)
    pokeS65(dut, portA = true, a)
    pokeS65(dut, portA = false, b)
    dut.clock.step(1)

    // Two pipeline bubbles
    dut.io.in_valid.poke(false.B)
    dut.io.a.poke(0.S(65.W))
    dut.io.b.poke(0.S(65.W))
    dut.clock.step(2)

    // Result at cycle 3
    dut.io.out_valid.expect(true.B)
    val result = dut.io.product.peekValue().asBigInt
    dut.clock.step(1)
    dut.io.out_valid.expect(false.B)
    result
  }

  // ── Edge Cases ──────────────────────────────────────────────────────────

  "SignedMul65x65" - {

    "0 × 0 = 0" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        runOne(dut, 0, 0) mustBe golden(0, 0)
      }
    }

    "0 × N = 0" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        for (n <- Seq(BigInt(1), Mask65 /* -1 */, BigInt(42), BigInt("ffffffffffffffff", 16))) {
          runOne(dut, 0, n) mustBe golden(0, n)
        }
      }
    }

    "N × 0 = 0" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        for (n <- Seq(BigInt(1), Mask65, BigInt(42), BigInt("ffffffffffffffff", 16))) {
          runOne(dut, n, 0) mustBe golden(n, 0)
        }
      }
    }

    "1 × 1 = 1" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        runOne(dut, 1, 1) mustBe golden(1, 1)
      }
    }

    "1 × -1 = -1" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        runOne(dut, 1, Mask65) mustBe golden(1, Mask65)
      }
    }

    "-1 × -1 = 1" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        runOne(dut, Mask65, Mask65) mustBe golden(Mask65, Mask65)
      }
    }

    "max positive × max positive" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        val maxPos = BigInt("7ffffffffffffffff", 16) // 2^64 - 1
        runOne(dut, maxPos, maxPos) mustBe golden(maxPos, maxPos)
      }
    }

    "min negative × min negative" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        val minNeg = BigInt("10000000000000000", 16) // -2^64
        runOne(dut, minNeg, minNeg) mustBe golden(minNeg, minNeg)
      }
    }

    "min negative × max positive" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        val minNeg = BigInt("10000000000000000", 16)
        val maxPos = BigInt("7ffffffffffffffff", 16)
        runOne(dut, minNeg, maxPos) mustBe golden(minNeg, maxPos)
      }
    }

    // ── Sign Combinations ─────────────────────────────────────────────────

    "positive × positive" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        for ((a, b) <- Seq((3L, 7L), (42L, 100L), (0x7fffL, 0x3L))) {
          runOne(dut, BigInt(a), BigInt(b)) mustBe golden(BigInt(a), BigInt(b))
        }
      }
    }

    "positive × negative" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        val neg5  = BigInt("1fffffffffffffffb", 16) // -5 as 65-bit raw
        val neg42 = BigInt("1fffffffffffffd6", 16)  // -42 as 65-bit raw
        for ((a, b) <- Seq((BigInt(7), neg5), (BigInt(100), neg42))) {
          runOne(dut, a, b) mustBe golden(a, b)
        }
      }
    }

    "negative × positive" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        val neg3  = BigInt("1ffffffffffffffd", 16)  // -3
        val neg99 = BigInt("1fffffffffffff9d", 16)  // -99
        for ((a, b) <- Seq((neg3, BigInt(8)), (neg99, BigInt(50)))) {
          runOne(dut, a, b) mustBe golden(a, b)
        }
      }
    }

    "negative × negative" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        val neg1 = Mask65
        val neg7 = BigInt("1fffffffffffffff9", 16) // -7
        for ((a, b) <- Seq((neg1, neg1), (neg7, neg7), (neg1, neg7))) {
          runOne(dut, a, b) mustBe golden(a, b)
        }
      }
    }

    // ── Continuous Input (II=1) ───────────────────────────────────────────

    "stream continuous inputs every cycle" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)

        val inputs = Seq(
          (BigInt(3), BigInt(5)),
          (BigInt(7), BigInt(11)),
          (BigInt(13), BigInt(17)),
          (BigInt(19), BigInt(23)),
          (BigInt(29), BigInt(31)),
        )
        val expected = inputs.map { case (a, b) => golden(a, b) }
        val results  = scala.collection.mutable.ArrayBuffer[BigInt]()

        // Feed inputs continuously, collect results as they appear
        for (cycle <- 0 until inputs.length + 3) {
          // Feed
          if (cycle < inputs.length) {
            val (a, b) = inputs(cycle)
            dut.io.in_valid.poke(true.B)
            pokeS65(dut, portA = true, a)
            pokeS65(dut, portA = false, b)
          } else {
            dut.io.in_valid.poke(false.B)
            dut.io.a.poke(0.S(65.W))
            dut.io.b.poke(0.S(65.W))
          }

          dut.clock.step(1)

          // Collect output
          if (dut.io.out_valid.peekValue().asBigInt == 1) {
            results += dut.io.product.peekValue().asBigInt
          }
        }

        results.size mustBe inputs.length
        for (i <- inputs.indices) {
          results(i) mustBe expected(i)
        }
      }
    }

    // ── Randomized Tests ──────────────────────────────────────────────────

    "randomized 50k test vectors" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)

        val rand  = new scala.util.Random(0xdeadbeefL)
        val count = 50000
        var mismatches = 0

        for (_ <- 0 until count) {
          val a = BigInt(65, rand)
          val b = BigInt(65, rand)
          val result = runOne(dut, a, b)
          if (result != golden(a, b)) {
            mismatches += 1
          }
        }

        mismatches mustBe 0
      }
    }

    // ── Output Valid Timing ───────────────────────────────────────────────

    "out_valid is strictly 3 cycles after in_valid" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)

        dut.io.in_valid.poke(true.B)
        dut.io.a.poke(42.S(65.W))
        dut.io.b.poke(10.S(65.W))
        dut.clock.step(1)

        dut.io.in_valid.poke(false.B)
        dut.io.a.poke(0.S(65.W))
        dut.io.b.poke(0.S(65.W))

        // Cycle 1 after input
        dut.io.out_valid.expect(false.B); dut.clock.step(1)
        // Cycle 2 after input
        dut.io.out_valid.expect(false.B); dut.clock.step(1)
        // Cycle 3 after input
        dut.io.out_valid.expect(true.B); dut.clock.step(1)
        // Cycle 4: back to 0
        dut.io.out_valid.expect(false.B)
      }
    }

    // ── Structural Checks via Bit-Level Golden Comparison ─────────────────

    "Booth PP + correction equals reference product" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)

        val rand = new scala.util.Random(0xcafeL)
        for (_ <- 0 until 1000) {
          val a = BigInt(65, rand)
          val b = BigInt(65, rand)
          runOne(dut, a, b) mustBe golden(a, b)
        }
      }
    }

    // ── Power-of-Two Boundary Tests ───────────────────────────────────────

    "powers of two" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)

        for (i <- 0 until 64) {
          val pow2 = BigInt(1) << i
          runOne(dut, pow2, pow2) mustBe golden(pow2, pow2)
          runOne(dut, pow2, BigInt(3)) mustBe golden(pow2, BigInt(3))
        }
      }
    }

    "near power-of-two boundaries" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)

        for (i <- 1 until 64) {
          val near = (BigInt(1) << i) - 1
          runOne(dut, near, near) mustBe golden(near, near)

          val nearP1 = (BigInt(1) << i) + 1
          runOne(dut, nearP1, nearP1) mustBe golden(nearP1, nearP1)
        }
      }
    }

    // ── Wide Operand Tests ────────────────────────────────────────────────

    "64-bit alternating bit patterns" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)

        val patterns = Seq(
          BigInt("5555555555555555", 16),  // 0101...
          BigInt("aaaaaaaaaaaaaaaa", 16),  // 1010...
          BigInt("3333333333333333", 16),
          BigInt("cccccccccccccccc", 16),
        )
        for (pa <- patterns; pb <- patterns) {
          runOne(dut, pa, pb) mustBe golden(pa, pb)
        }
      }
    }

    // ── Pipeline Bubbles ───────────────────────────────────────────────────

    "stream with in_valid bubbles" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)

        // Pattern: valid=1, valid=1, valid=0, valid=0, valid=1
        val inputs = Seq(
          (BigInt(3), BigInt(5), true),     // cycle 0
          (BigInt(7), BigInt(11), true),    // cycle 1
          (BigInt(0), BigInt(0), false),    // cycle 2 — bubble
          (BigInt(0), BigInt(0), false),    // cycle 3 — bubble
          (BigInt(13), BigInt(17), true),   // cycle 4
        )
        // Expected results (only for valid inputs)
        val expected = Seq(golden(3, 5), golden(7, 11), golden(13, 17))
        val results  = scala.collection.mutable.ArrayBuffer[BigInt]()

        for (cycle <- 0 until inputs.length + 4) {
          if (cycle < inputs.length) {
            val (a, b, valid) = inputs(cycle)
            dut.io.in_valid.poke(valid.B)
            pokeS65(dut, portA = true, a)
            pokeS65(dut, portA = false, b)
          } else {
            dut.io.in_valid.poke(false.B)
            dut.io.a.poke(0.S(65.W))
            dut.io.b.poke(0.S(65.W))
          }

          dut.clock.step(1)

          if (dut.io.out_valid.peekValue().asBigInt == 1) {
            results += dut.io.product.peekValue().asBigInt
          }
        }

        results.size mustBe 3
        results.zip(expected).foreach { case (got, exp) => got mustBe exp }
      }
    }

    "stream mixed positive and negative signs" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)

        // Deliberately alternate signs to stress Booth sign extension
        val neg1 = Mask65
        val inputs = Seq(
          (BigInt(42), BigInt(100)),             // pos × pos
          (BigInt(42), neg1),                    // pos × neg (-1)
          (neg1, BigInt(100)),                   // neg × pos
          (neg1, neg1),                          // neg × neg
          (BigInt("7ffffffffffffffff", 16), BigInt("10000000000000000", 16)), // max_pos × min_neg
        )
        val expected = inputs.map { case (a, b) => golden(a, b) }
        val results  = scala.collection.mutable.ArrayBuffer[BigInt]()

        for (cycle <- 0 until inputs.length + 3) {
          if (cycle < inputs.length) {
            val (a, b) = inputs(cycle)
            dut.io.in_valid.poke(true.B)
            pokeS65(dut, portA = true, a)
            pokeS65(dut, portA = false, b)
          } else {
            dut.io.in_valid.poke(false.B)
            dut.io.a.poke(0.S(65.W))
            dut.io.b.poke(0.S(65.W))
          }

          dut.clock.step(1)

          if (dut.io.out_valid.peekValue().asBigInt == 1) {
            results += dut.io.product.peekValue().asBigInt
          }
        }

        results.size mustBe inputs.length
        results.zip(expected).foreach { case (got, exp) => got mustBe exp }
      }
    }
  }
}
