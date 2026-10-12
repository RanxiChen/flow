package flow.mmu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.interface.BreezePmpRange
import org.scalatest.freespec.AnyFreeSpec
import scala.util.Random

class PmpRangeHarness extends Module {
  val io = IO(new Bundle {
    val cfg = Input(UInt(8.W))
    val addr = Input(UInt(54.W))
    val previous = Input(UInt(54.W))
    val range = Output(new BreezePmpRange)
  })
  io.range := BreezePmpDecode.range(io.cfg, io.addr, io.previous)
}

class BreezePmpRangeSpec extends AnyFreeSpec with ChiselSim {
  "PMP range arithmetic preserves all modes, largest NAPOT and block underflow" in {
    simulate(new PmpRangeHarness) { d =>
      val rng = new Random(0x504d5032L)
      val mask = (BigInt(1) << 54) - 1
      val blockMask = (BigInt(1) << 58) - 1
      val edges = Seq(BigInt(0), BigInt(1), BigInt(31), BigInt(32), mask - 1, mask) ++
        (0 until 54).map(n => (BigInt(1) << n) - 1)
      val samples = edges.flatMap(a => Seq((a, BigInt(0)), (a, mask), (a, a))) ++
        (0 until 1000).map(_ => (BigInt(54, rng), BigInt(54, rng)))
      for ((a, previous) <- samples; mode <- 0 until 4) {
        d.io.cfg.poke((mode << 3).U)
        d.io.addr.poke(a.U); d.io.previous.poke(previous.U)
        // expect queues deferred checks. A long pure-combinational sweep
        // needs a synchronous read to drain svsim's bidirectional pipes;
        // there is no clock.step here to perform that synchronization.
        d.io.range.lower.peek()
        val (lower, upper) = mode match {
          case 0 | 1 => (previous << 2, a << 2)
          case 2 => (a << 2, (a << 2) + 4)
          case 3 =>
            var ones = 0
            while (ones < 54 && a.testBit(ones)) ones += 1
            val bytes = (BigInt(1) << (ones + 3)).min(BigInt(1) << 56)
            val base = (a << 2) / bytes * bytes
            (base, base + bytes)
        }
        d.io.range.lower.expect(lower.U); d.io.range.upper.expect(upper.U)
        d.io.range.nonempty.expect((mode != 0 && lower < upper).B)
        d.io.range.lowerBlockPrevious.expect((((lower >> 7) - 1) & blockMask).U)
        d.io.range.upperBlockPrevious.expect((((upper >> 7) - 1) & blockMask).U)
      }
    }
  }
}
