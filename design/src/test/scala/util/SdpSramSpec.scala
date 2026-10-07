package flow.util

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class SdpSramSpec extends AnyFreeSpec with Matchers with ChiselSim {
  for ((depth, width, granule) <- Seq((32, 64, 8), (19, 23, 23), (1, 7, 7))) {
    s"SdpSram $depth x $width / $granule: synchronous reads, enables and random writes" in {
      simulate(new SdpSram(depth, width, granule)) { d =>
        val rng = new scala.util.Random(0x502bL + width)
        val model = Array.fill(depth)(BigInt(0))
        val lanes = width / granule
        val fullMask = (BigInt(1) << lanes) - 1
        var expectedOutput: Option[BigInt] = None
        d.io.ren.poke(false.B); d.io.wen.poke(false.B)
        d.io.raddr.poke(0.U); d.io.waddr.poke(0.U)
        d.io.wdata.poke(0.U); d.io.wmask.poke(fullMask.U)
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)

        def cycle(ren: Boolean, ra: Int, wen: Boolean, wa: Int, value: BigInt, mask: BigInt): Unit = {
          d.io.ren.poke(ren.B); d.io.raddr.poke(ra.U)
          d.io.wen.poke(wen.B); d.io.waddr.poke(wa.U)
          d.io.wdata.poke(value.U); d.io.wmask.poke(mask.U)
          // A new read address must not change the output before the edge.
          expectedOutput.foreach(v => d.io.rdata.expect(v.U))
          val next = if (ren) Some(model(ra)) else expectedOutput
          if (wen) {
            for (lane <- 0 until lanes if granule == width || mask.testBit(lane)) {
              val bits = ((BigInt(1) << granule) - 1) << (lane * granule)
              model(wa) = (model(wa) & ~bits) | (value & bits)
            }
          }
          d.clock.step()
          // Disabled read output is deliberately not checked: D1 allows arbitrary data.
          if (ren) d.io.rdata.expect(next.get.U)
          expectedOutput = if (ren) next else None
        }
        for (a <- 0 until depth) cycle(false, 0, true, a, BigInt(width, rng), fullMask)
        for (a <- 0 until depth) cycle(true, a, false, 0, 0, 0)
        // Consecutive writes to the same word, including partial and zero-byte masks.
        for (mask <- Seq(fullMask, BigInt(0), BigInt(1), fullMask ^ 1))
          cycle(false, 0, true, depth - 1, BigInt(width, rng), mask)
        cycle(true, depth - 1, false, 0, 0, 0)
        for (_ <- 0 until 1000) {
          val ra = rng.nextInt(depth); val wa = rng.nextInt(depth)
          val ren = rng.nextBoolean()
          val wen = rng.nextBoolean() && (!ren || ra != wa)
          cycle(ren, ra, wen, wa, BigInt(width, rng), BigInt(lanes, rng))
        }
        for (a <- 0 until depth) cycle(true, a, false, 0, 0, 0)
      }
    }
  }

  "SdpSram same-address read/write trips the RTL assertion" in {
    var reachedCollision = false
    intercept[Exception] {
      simulate(new SdpSram(16, 32, 8)) { d =>
        d.io.ren.poke(false.B); d.io.wen.poke(false.B)
        d.io.raddr.poke(3.U); d.io.waddr.poke(3.U)
        d.io.wdata.poke(0.U); d.io.wmask.poke(15.U)
        d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
        d.clock.step()
        reachedCollision = true
        d.io.ren.poke(true.B); d.io.wen.poke(true.B); d.clock.step()
      }
    }
    reachedCollision mustBe true
  }
}
