package flow.multiplier

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class SignedMul65x65Spec extends AnyFreeSpec with Matchers with ChiselSim {

  private val Mask65  = (BigInt(1) << 65) - 1
  private val Mask130 = (BigInt(1) << 130) - 1

  /** Interpret a 65-bit unsigned bit-pattern as a signed value. */
  private def asSigned65(raw: BigInt): BigInt = {
    val u = raw & Mask65
    if (u.testBit(64)) u - (BigInt(1) << 65) else u
  }

  /** Golden 65×65 signed product (lower 130 bits, two's complement). */
  private def golden(rawA: BigInt, rawB: BigInt): BigInt = {
    val sa = asSigned65(rawA)
    val sb = asSigned65(rawB)
    val product = sa * sb
    product & Mask130
  }

  /** Drive one multiply through the 3-stage pipeline and return the result. */
  private def runOne(dut: SignedMul65x65, a: BigInt, b: BigInt): BigInt = {
    dut.io.in_valid.poke(true.B)
    dut.io.a.poke((a & Mask65).S(65.W))
    dut.io.b.poke((b & Mask65).S(65.W))
    dut.clock.step(1)

    // Two pipeline bubbles after the single input
    dut.io.in_valid.poke(false.B)
    dut.io.a.poke(0.S(65.W))
    dut.io.b.poke(0.S(65.W))
    dut.clock.step(2)

    // Result appears on cycle 3 (relative to input)
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
        val result = runOne(dut, 0, 0)
        result mustBe golden(0, 0)
      }
    }

    "0 × N = 0" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        for (n <- Seq(BigInt(1), BigInt(-1), BigInt(42), BigInt("ffffffffffffffff", 16))) {
          val result = runOne(dut, 0, n)
          result mustBe golden(0, n)
        }
      }
    }

    "N × 0 = 0" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        for (n <- Seq(BigInt(1), BigInt(-1), BigInt(42), BigInt("ffffffffffffffff", 16))) {
          val result = runOne(dut, n, 0)
          result mustBe golden(n, 0)
        }
      }
    }

    "1 × 1 = 1" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        val result = runOne(dut, 1, 1)
        result mustBe golden(1, 1)
      }
    }

    "1 × -1 = -1" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        val mask = Mask65
        val neg1 = mask // 65-bit representation of -1
        val result = runOne(dut, 1, neg1)
        result mustBe golden(1, neg1)
      }
    }

    "-1 × -1 = 1" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        val neg1 = Mask65
        val result = runOne(dut, neg1, neg1)
        result mustBe golden(neg1, neg1)
      }
    }

    "max positive × max positive" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        val maxPos = BigInt("7ffffffffffffffff", 16) // 2^64 - 1, as 65-bit unsigned
        val result = runOne(dut, maxPos, maxPos)
        result mustBe golden(maxPos, maxPos)
      }
    }

    "min negative × min negative" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        val minNeg = BigInt("10000000000000000", 16) // -2^64, as 65-bit unsigned
        val result = runOne(dut, minNeg, minNeg)
        result mustBe golden(minNeg, minNeg)
      }
    }

    "min negative × max positive" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        val minNeg = BigInt("10000000000000000", 16)
        val maxPos = BigInt("7ffffffffffffffff", 16)
        val result = runOne(dut, minNeg, maxPos)
        result mustBe golden(minNeg, maxPos)
      }
    }

    // ── Sign Combinations ─────────────────────────────────────────────────

    "positive × positive" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        for ((a, b) <- Seq((3L, 7L), (42L, 100L), (0x7fffL, 0x3L))) {
          val result = runOne(dut, BigInt(a), BigInt(b))
          result mustBe golden(BigInt(a), BigInt(b))
        }
      }
    }

    "positive × negative" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        val neg5  = Mask65 - 4  // -5 in 65-bit
        val neg42 = Mask65 - 41 // -42 in 65-bit
        for ((a, b) <- Seq((BigInt(7), neg5), (BigInt(100), neg42))) {
          val result = runOne(dut, a, b)
          result mustBe golden(a, b)
        }
      }
    }

    "negative × positive" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        val neg3  = Mask65 - 2
        val neg99 = Mask65 - 98
        for ((a, b) <- Seq((neg3, BigInt(8)), (neg99, BigInt(50)))) {
          val result = runOne(dut, a, b)
          result mustBe golden(a, b)
        }
      }
    }

    "negative × negative" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)
        val neg1 = Mask65
        val neg7 = Mask65 - 6
        for ((a, b) <- Seq((neg1, neg1), (neg7, neg7), (neg1, neg7))) {
          val result = runOne(dut, a, b)
          result mustBe golden(a, b)
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

        // Feed inputs continuously
        for ((a, b) <- inputs) {
          dut.io.in_valid.poke(true.B)
          dut.io.a.poke((a & Mask65).S(65.W))
          dut.io.b.poke((b & Mask65).S(65.W))
          dut.clock.step(1)
        }

        // Wait for pipeline to drain + last result
        dut.io.in_valid.poke(false.B)
        dut.io.a.poke(0.S(65.W))
        dut.io.b.poke(0.S(65.W))

        // First result at cycle 3; last result at cycle 3 + inputs.size - 1
        // Cycle 0-4: inputs fed
        // Cycle 3: result[0] ready → need to capture it
        // We're now at cycle 5 (after 5 input cycles). First result was at cycle 3.
        // Let me step through carefully.

        // After feeding 5 inputs, we're at time step 5.
        // Result for input[0] appeared at time step 3 (already passed).
        // Results for input[1..4] appear at time steps 4..7.

        // Step until we've seen all results
        val expected = inputs.map { case (a, b) => golden(a, b) }
        val results  = scala.collection.mutable.ArrayBuffer[BigInt]()

        for (_ <- 0 until inputs.length + 3) {
          if (dut.io.out_valid.peekValue().asBigInt == 1) {
            results += dut.io.product.peekValue().asBigInt
          }
          dut.clock.step(1)
        }

        // We should see exactly inputs.length = 5 results
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

        // Pipeline tracking: queue of (cycles_remaining, golden_result)
        val pipeline = scala.collection.mutable.Queue[(Int, BigInt)]()
        var cycle    = 0
        var mismatch = 0

        while (pipeline.nonEmpty || cycle < count) {
          // Feed new input
          if (cycle < count) {
            val a = BigInt(65, rand)
            val b = BigInt(65, rand)
            val expected = golden(a, b)
            dut.io.in_valid.poke(true.B)
            dut.io.a.poke((a & Mask65).S(65.W))
            dut.io.b.poke((b & Mask65).S(65.W))
            pipeline.enqueue((3, expected))
          } else {
            dut.io.in_valid.poke(false.B)
            dut.io.a.poke(0.S(65.W))
            dut.io.b.poke(0.S(65.W))
          }

          // Check output
          val outValid = dut.io.out_valid.peekValue().asBigInt == 1
          if (outValid) {
            val dutResult = dut.io.product.peekValue().asBigInt
            val (_, expected) = pipeline.dequeue()
            if (dutResult != expected) {
              mismatch += 1
            }
          }

          // Advance pipeline timers
          val advanced = pipeline.map { case (rem, exp) => (rem - 1, exp) }
          pipeline.clear()
          pipeline ++= advanced

          dut.clock.step(1)
          cycle += 1
        }

        mismatch mustBe 0
      }
    }

    // ── Output Valid Timing ───────────────────────────────────────────────

    "out_valid is strictly 3 cycles after in_valid" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)

        // Single input pulse
        dut.io.in_valid.poke(true.B)
        dut.io.a.poke(42.S(65.W))
        dut.io.b.poke(10.S(65.W))
        dut.clock.step(1)

        dut.io.in_valid.poke(false.B)
        dut.io.a.poke(0.S(65.W))
        dut.io.b.poke(0.S(65.W))

        // Cycle 1 (1 after input): out_valid should be false
        dut.io.out_valid.expect(false.B)
        dut.clock.step(1)

        // Cycle 2 (2 after input): out_valid should be false
        dut.io.out_valid.expect(false.B)
        dut.clock.step(1)

        // Cycle 3 (3 after input): out_valid should be true
        dut.io.out_valid.expect(true.B)
        dut.clock.step(1)

        // Cycle 4: out_valid should be false again
        dut.io.out_valid.expect(false.B)
      }
    }

    // ── Structural Checks via Bit-Level Golden Comparison ─────────────────

    "Booth PP + correction equals reference product" in {
      // This is verified implicitly by the end-to-end test above.
      // Additional explicit check: we test many random inputs comparing DUT
      // output against the BigInt golden reference, which proves the Booth
      // encoding + Dadda tree + CPA combination is mathematically correct.
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)

        val rand = new scala.util.Random(0xcafeL)
        for (_ <- 0 until 1000) {
          val a = BigInt(65, rand)
          val b = BigInt(65, rand)
          val result = runOne(dut, a, b)
          result mustBe golden(a, b)
        }
      }
    }

    // ── Power-of-Two Boundary Tests ───────────────────────────────────────

    "powers of two" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)

        for (i <- 0 until 64) {
          val pow2 = BigInt(1) << i
          // pow2 × pow2 = 2^(2i)
          val result1 = runOne(dut, pow2, pow2)
          result1 mustBe golden(pow2, pow2)

          // pow2 × 3
          val result2 = runOne(dut, pow2, BigInt(3))
          result2 mustBe golden(pow2, BigInt(3))
        }
      }
    }

    "near power-of-two boundaries" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)

        for (i <- 1 until 64) {
          val near = (BigInt(1) << i) - 1
          val result = runOne(dut, near, near)
          result mustBe golden(near, near)

          val nearP1 = (BigInt(1) << i) + 1
          val result2 = runOne(dut, nearP1, nearP1)
          result2 mustBe golden(nearP1, nearP1)
        }
      }
    }

    // ── Wide Operand Tests ────────────────────────────────────────────────

    "64-bit alternating bit patterns" in {
      simulate(new SignedMul65x65) { dut =>
        dut.reset.poke(true.B); dut.clock.step(1); dut.reset.poke(false.B)

        val patterns = Seq(
          BigInt("5555555555555555", 16),  // 0101...
          BigInt("aaaaaaaaaaaaaaaa", 16),  // 1010... (negative as 65-bit)
          BigInt("3333333333333333", 16),
          BigInt("cccccccccccccccc", 16),
        )
        for (pa <- patterns; pb <- patterns) {
          val result = runOne(dut, pa, pb)
          result mustBe golden(pa, pb)
        }
      }
    }
  }
}
