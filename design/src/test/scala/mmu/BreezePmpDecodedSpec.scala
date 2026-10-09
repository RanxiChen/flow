package flow.mmu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.BreezePmpConfig
import flow.interface._
import org.scalatest.freespec.AnyFreeSpec
import scala.util.Random

/** Exercise both fallback decode and the registered-range/precomputed-end path. */
class PmpDecodedHarness extends Module {
  val io = IO(new Bundle {
    val context = Input(new BreezeMmuContext(64))
    val capture = Input(Bool())
    val addr = Input(UInt(64.W)); val size = Input(UInt(3.W))
    val access = Input(BreezeMmuAccess()); val privilege = Input(UInt(2.W))
    val allowed = Output(Vec(3, Bool()))
  })
  val saved = Reg(new BreezeMmuContext(64))
  when(io.capture) {
    saved := io.context
    saved.pmpRangesValid := true.B
    for (n <- 0 until BreezePmpConfig.ActiveEntries) {
      saved.pmpRanges(n) := BreezePmpDecode.range(io.context.pmpcfg(n), io.context.pmpaddr(n),
        if (n == 0) 0.U(54.W) else io.context.pmpaddr(n - 1))
    }
  }
  for (n <- 0 until 3) {
    val c = Module(new BreezePmpChecker(decoded = n != 0, precomputedEnd = n == 2))
    c.io.context := (if (n == 2) saved else io.context)
    c.io.addr := io.addr; c.io.sizeLog2 := io.size
    c.io.access := io.access; c.io.privilege := io.privilege
    c.io.accessEnd.foreach(_ := BreezePmpDecode.accessEnd(io.addr(6, 0), io.size))
    io.allowed(n) := c.io.allowed
  }
}

class BreezePmpDecodedSpec extends AnyFreeSpec with ChiselSim {
  // Integer interval model: no block splitting, hardware masks or DUT decode.
  private def region(cfg: Int, addr: BigInt, previous: BigInt): Option[(BigInt, BigInt)] =
    ((cfg >> 3) & 3) match {
      case 0 => None
      case 1 => Some((previous << 2, addr << 2))
      case 2 => Some((addr << 2, (addr << 2) + 4))
      case 3 =>
        var ones = 0
        while (ones < 54 && addr.testBit(ones)) ones += 1
        // The frozen checker has a 54-bit PMP encoding / 56-bit aperture.
        // A full-ones pmpaddr covers that aperture, not a 57-bit PA region.
        val bytes = (BigInt(1) << (ones + 3)).min(BigInt(1) << 56)
        val base = (addr << 2) / bytes * bytes
        Some((base, base + bytes))
    }
  private def reference(cfg: Seq[Int], addrs: Seq[BigInt], a: BigInt, size: Int,
      access: Int, privilege: Int): Boolean = {
    val end = a + (BigInt(1) << size)
    cfg.indices.iterator.flatMap { n =>
      region(cfg(n), addrs(n), if (n == 0) BigInt(0) else addrs(n - 1))
        .filter { case (lo, hi) => lo < hi && a < hi && end > lo }
        .map { case (lo, hi) => a >= lo && end <= hi &&
          ((privilege == 3 && (cfg(n) & 128) == 0) || (cfg(n) & (1 << access)) != 0) }
    }.take(1).toSeq.headOption.getOrElse(privilege == 3)
  }
  "SOC3d decoded PMP agrees with integer intervals at boundaries and seeded random overlaps" in {
    simulate(new PmpDecodedHarness) { d =>
      d.io.context.poke(0.U.asTypeOf(new BreezeMmuContext(64)))
      d.io.capture.poke(false.B); d.io.addr.poke(0.U); d.io.size.poke(0.U)
      d.io.access.poke(BreezeMmuAccess.Load); d.io.privilege.poke(1.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val rng = new Random(0x3d504d50L)
      val mask = (BigInt(1) << 54) - 1
      def check(cfg: Seq[Int], addr: Seq[BigInt], accesses: Seq[(BigInt, Int)], sweep: Boolean = true): Unit = {
        for (n <- cfg.indices) {
          d.io.context.pmpcfg(n).poke(cfg(n).U); d.io.context.pmpaddr(n).poke(addr(n).U)
        }
        d.io.capture.poke(true.B); d.clock.step(); d.io.capture.poke(false.B)
        val modes = if (sweep) Seq((1, 0), (1, 1), (1, 2), (0, 0), (3, 0))
          else Seq((Seq(0, 1, 3)(rng.nextInt(3)), rng.nextInt(3)))
        for ((a, size) <- accesses; (priv, access) <- modes) {
          d.io.addr.poke(a.U); d.io.size.poke(size.U); d.io.privilege.poke(priv.U)
          // PMP R/W/X bits are load/store/fetch, unlike the access enum order.
          d.io.access.poke(Seq(BreezeMmuAccess.Load, BreezeMmuAccess.Store, BreezeMmuAccess.Fetch)(access))
          val expected = reference(cfg, addr, a, size, access, priv)
          for (n <- 0 until 3) {
            val actual = d.io.allowed(n).peek().litToBoolean
            assert(actual == expected,
              s"path=$n addr=0x${a.toString(16)} size=$size priv=$priv access=$access cfg=$cfg pmpaddr=$addr actual=$actual expected=$expected")
          }
        }
      }
      val directed = Seq(
        (Seq(0, 0x0f, 0x1b, 0x95, 0, 0, 0, 0), Seq[BigInt](32, 64, 95, 65, 0, 0, 0, 0)),
        (Seq(0x10, 0x1f, 0, 0, 0, 0, 0, 0), Seq[BigInt](33, 63, 0, 0, 0, 0, 0, 0)),
        (Seq(0x1f, 0, 0, 0, 0, 0, 0, 0), Seq(mask, BigInt(0), BigInt(0), BigInt(0), BigInt(0), BigInt(0), BigInt(0), BigInt(0))))
      for ((cfg, addr) <- directed) {
        val points = Seq(0, 127, 128, 131, 132, 255, 256, 383, 384).map(BigInt(_)) ++ Seq((BigInt(1) << 56) - 1,
          BigInt(1) << 56, (BigInt(1) << 57) - 1, (BigInt(1) << 64) - 1)
        check(cfg, addr, points.flatMap(a => Seq(0, 2, 3, 7).map(a -> _)))
      }
      for (_ <- 0 until 40) {
        val cfg = Seq.fill(8)(rng.nextInt(256))
        val addr = Seq.fill(8)(if (rng.nextBoolean()) BigInt(rng.nextInt(1024)) else BigInt(54, rng))
        val edges = addr.flatMap(a => Seq((a << 2) - 1, a << 2, (a << 2) + 1)).filter(_ >= 0)
        check(cfg, addr, (rng.shuffle(edges).take(4) :+ BigInt(64, rng)).map(a => (a, rng.nextInt(8))), sweep = false)
      }
    }
  }
}
