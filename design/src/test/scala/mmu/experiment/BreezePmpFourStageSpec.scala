package flow.mmu.experiment

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.interface.BreezeMmuAccess
import org.scalatest.freespec.AnyFreeSpec
import scala.util.Random

/** Integer interval oracle. No DUT masks, block-carry logic or merge tree. */
private class PmpOracle(val entries: Int = 8) {
  val cfg = Array.fill(entries)(0)
  val address = Array.fill(entries)(BigInt(0))
  var epoch = 0
  val addressMask = (BigInt(1) << 54) - 1
  def range(i: Int): (BigInt, BigInt, Boolean) = {
    val mode = (cfg(i) >> 3) & 3
    val a = address(i)
    val (lo, hi) = mode match {
      case 0 | 1 => (if (i == 0) BigInt(0) else address(i - 1) * 4, a * 4)
      case 2 => (a * 4, a * 4 + 4)
      case 3 =>
        var ones = 0
        while (ones < 54 && a.testBit(ones)) ones += 1
        val bytes = (BigInt(1) << (ones + 3)).min(BigInt(1) << 56)
        val base = (a * 4 / bytes) * bytes
        (base, base + bytes)
    }
    (lo, hi, mode != 0 && lo < hi)
  }
  def recognized(a: Int): Boolean = a == 0x3a0 || a == 0x3a2 ||
    (a >= 0x3b0 && a < 0x3b0 + entries)
  def write(a: Int, data: BigInt): Unit = if (recognized(a)) {
    if (a == 0x3a0 || a == 0x3a2) {
      val base = if (a == 0x3a0) 0 else 8
      for (i <- base until math.min(base + 8, entries) if (cfg(i) & 128) == 0) {
        val raw = ((data >> ((i - base) * 8)) & 255).toInt
        cfg(i) = (raw & 0x9d) | (if ((raw & 1) != 0) raw & 2 else 0)
      }
    } else {
      val i = a - 0x3b0
      val nextLockedTor = i + 1 < entries && (cfg(i + 1) & 128) != 0 &&
        ((cfg(i + 1) >> 3) & 3) == 1
      if ((cfg(i) & 128) == 0 && !nextLockedTor) address(i) = data & addressMask
    }
    epoch = (epoch + 1) & 65535
  }
  def check(a: BigInt, size: Int, accessBit: Int, privilege: Int): (Boolean, BigInt) = {
    val end = a + (BigInt(1) << size)
    val first = cfg.indices.find { i =>
      val (lo, hi, active) = range(i)
      active && a < hi && end > lo
    }
    first match {
      case None => (privilege == 3, BigInt(0))
      case Some(i) =>
        val (lo, hi, _) = range(i)
        (a >= lo && end <= hi && ((privilege == 3 && (cfg(i) & 128) == 0) ||
          (cfg(i) & (1 << accessBit)) != 0), BigInt(1) << i)
    }
  }
}

class BreezePmpFourStageSpec extends AnyFreeSpec with ChiselSim {
  private def pack(cfg: Seq[Int]): BigInt =
    cfg.zipWithIndex.map { case (c, i) => BigInt(c) << (8 * i) }.foldLeft(BigInt(0))(_ | _)

  "CSR predecode publishes legal ranges and predecessors, including TOR neighbour locks" in {
    simulate(new BreezePmpCsrPredecode) { d =>
      val ref = new PmpOracle
      d.io.commit.valid.poke(false.B)
      d.io.commit.bits.address.poke(0.U); d.io.commit.bits.data.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def verify(): Unit = {
        val mask = (BigInt(1) << 58) - 1
        for (i <- 0 until 8) {
          val (lo, hi, active) = ref.range(i)
          d.io.cfg(i).expect(ref.cfg(i).U)
          d.io.address(i).expect(ref.address(i).U)
          d.io.ranges(i).lower.expect(lo.U); d.io.ranges(i).upper.expect(hi.U)
          d.io.ranges(i).nonempty.expect(active.B)
          d.io.ranges(i).lowerBlockPrevious.expect((((lo / 128) - 1) & mask).U)
          d.io.ranges(i).upperBlockPrevious.expect((((hi / 128) - 1) & mask).U)
        }
        d.io.epoch.expect(ref.epoch.U)
      }
      def write(a: Int, data: BigInt): Unit = {
        d.io.commit.valid.poke(true.B)
        d.io.commit.bits.address.poke(a.U); d.io.commit.bits.data.poke(data.U)
        d.io.changed.expect(ref.recognized(a).B)
        d.clock.step(); ref.write(a, data)
        d.io.commit.valid.poke(false.B); verify()
      }
      verify()
      write(0x3b0, 0x400); write(0x3b1, 0x800)
      write(0x3a0, pack(Seq(0, 0x0f, 0x12, 0x1f, 0, 0, 0, 0)))
      write(0x3b0, 0x440) // changes the following TOR lower bound
      write(0x3b3, ref.addressMask)
      write(0x3a0, pack(Seq(0, 0x8d, 0x1f, 0x1f, 0, 0, 0, 0)))
      write(0x3b0, 0x700) // locked by next TOR entry
      write(0x3b1, 0x900) // own lock
      write(0x3a0, 0) // locked cfg survives
      write(0x3a2, BigInt("ffffffffffffffff", 16)) // absent bank: ignored, cancels conservatively
      write(0x305, 42) // unrelated CSR is not an update
    }
  }

  private case class Query(a: BigInt, size: Int, bit: Int, priv: Int, id: Int)
  private case class Expected(id: Int, epoch: Int, allowed: Boolean, winner: BigInt)
  private def runPipeline(body: ((Option[Query], Option[(Int, BigInt)], Boolean) => Unit,
      PmpOracle) => Unit): Unit = {
    simulate(new BreezePmpFourStageExperiment) { d =>
      val ref = new PmpOracle
      var pending = Vector.fill[Option[Expected]](4)(None)
      var responses = 0
      d.io.commit.valid.poke(false.B)
      d.io.commit.bits.address.poke(0.U); d.io.commit.bits.data.poke(0.U)
      d.io.cancel.poke(false.B); d.io.request.valid.poke(false.B)
      d.io.request.bits.id.poke(0.U); d.io.request.bits.address.poke(0.U)
      d.io.request.bits.sizeLog2.poke(0.U); d.io.request.bits.privilege.poke(3.U)
      d.io.request.bits.access.poke(BreezeMmuAccess.Fetch)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def tick(q: Option[Query], write: Option[(Int, BigInt)], cancel: Boolean): Unit = {
        d.io.request.valid.poke(q.nonEmpty.B)
        q.foreach { x =>
          d.io.request.bits.id.poke(x.id.U); d.io.request.bits.address.poke(x.a.U)
          d.io.request.bits.sizeLog2.poke(x.size.U); d.io.request.bits.privilege.poke(x.priv.U)
          d.io.request.bits.access.poke(Seq(BreezeMmuAccess.Load,
            BreezeMmuAccess.Store, BreezeMmuAccess.Fetch)(x.bit))
        }
        d.io.commit.valid.poke(write.nonEmpty.B)
        write.foreach { case (a, data) =>
          d.io.commit.bits.address.poke(a.U); d.io.commit.bits.data.poke(data.U)
        }
        d.io.cancel.poke(cancel.B)
        val clear = cancel || write.exists(w => ref.recognized(w._1))
        d.io.request.ready.expect((!clear).B)
        val expected = if (clear) None else q.map { x =>
          val (allow, winner) = ref.check(x.a, x.size, x.bit, x.priv)
          Expected(x.id, ref.epoch, allow, winner)
        }
        pending = if (clear) Vector.fill(4)(None) else expected +: pending.take(3)
        d.clock.step()
        write.foreach { case (a, data) => ref.write(a, data) }
        // clear masks the combinational response as well as the valid registers.
        pending.last match {
          case None => d.io.response.valid.expect(false.B)
          case Some(e) =>
            d.io.response.valid.expect(true.B)
            d.io.response.bits.id.expect(e.id.U)
            d.io.response.bits.epoch.expect(e.epoch.U)
            d.io.response.bits.allowed.expect(e.allowed.B)
            d.io.response.bits.winnerOH.expect(e.winner.U)
            d.io.response.bits.matched.expect((e.winner != 0).B)
            responses += 1
        }
      }
      body(tick, ref)
      for (_ <- 0 until 5) tick(None, None, false)
      println(s"PMP_FOUR_STAGE_CHECKED_RESPONSES=$responses")
    }
  }

  "four stages sustain II=1 and agree with integer intervals for boundaries and random accesses" in {
    runPipeline { (tick, ref) =>
      val rng = new Random(0x504d5034L)
      var id = 0
      def send(a: BigInt, size: Int, bit: Int, priv: Int): Unit = {
        tick(Some(Query(a, size, bit, priv, id)), None, false); id += 1
      }
      def write(a: Int, data: BigInt): Unit = tick(None, Some(a -> data), false)
      val points = Seq[BigInt](0, 1, 126, 127, 128, 129, 0x17fc, 0x1800, 0x18ff,
        0x1900, 0x1fff, 0x2000, (BigInt(1) << 56) - 1, BigInt(1) << 56,
        (BigInt(1) << 64) - 1)
      // OFF, nested NAPOT hole, NA4, TOR using an OFF predecessor, max NAPOT.
      val configurations = Seq(
        (Seq.fill(8)(0), Seq.fill(8)(BigInt(0))),
        (Seq(0x18, 0x1f, 0, 0, 0, 0, 0, 0), Seq[BigInt](0x61f, 0x5ff, 0, 0, 0, 0, 0, 0)),
        (Seq(0x15, 0x1f, 0, 0x0f, 0, 0, 0, 0), Seq[BigInt](32, 63, 0x400, 0x800, 0, 0, 0, 0)),
        (Seq(0x1f) ++ Seq.fill(7)(0), Seq(ref.addressMask) ++ Seq.fill(7)(BigInt(0))))
      for ((cfg, addr) <- configurations) {
        write(0x3a0, 0)
        addr.zipWithIndex.foreach { case (a, i) => write(0x3b0 + i, a) }
        write(0x3a0, pack(cfg))
        for (a <- points; s <- Seq(0, 1, 2, 3, 7); bit <- 0 until 3; priv <- Seq(0, 1, 3))
          send(a, s, bit, priv)
        for (_ <- 0 until 4) tick(None, None, false)
      }
      // Force each high-compare chunk and the low 128-byte carry boundary.
      for (bit <- Seq(7, 15, 23, 31, 39, 47, 55)) {
        write(0x3a0, 0); write(0x3b0, (BigInt(1) << bit) >> 2)
        write(0x3a0, 0x0f)
        for (offset <- Seq(-129, -128, -127, -8, -1, 0, 1); s <- Seq(0, 3, 7)) {
          val a = (BigInt(1) << bit) + offset
          if (a >= 0) send(a, s, 2, 1)
        }
        for (_ <- 0 until 4) tick(None, None, false)
      }
      for (_ <- 0 until 20) {
        write(0x3a0, 0)
        for (i <- 0 until 8) write(0x3b0 + i,
          if (rng.nextBoolean()) BigInt(rng.nextInt(4096)) else BigInt(54, rng))
        write(0x3a0, pack(Seq.fill(8)(rng.nextInt(128))))
        for (_ <- 0 until 100) {
          val a = if (rng.nextBoolean()) BigInt(rng.nextInt(16384)) else BigInt(64, rng)
          send(a, rng.nextInt(8), rng.nextInt(3), Seq(0, 1, 3)(rng.nextInt(3)))
        }
        for (_ <- 0 until 4) tick(None, None, false)
      }
      // Locked R/W/X must constrain M-mode too.
      write(0x3a0, 0); write(0x3b0, 0x61f); write(0x3a0, 0x99)
      for (a <- Seq[BigInt](0x17ff, 0x1800, 0x18ff, 0x1900);
           s <- Seq(0, 2, 7); bit <- 0 until 3; priv <- Seq(0, 1, 3)) send(a, s, bit, priv)
    }
  }

  "cancel and CSR commits discard every in-flight age without stale response or mixed rules" in {
    runPipeline { (tick, _) =>
      def q(id: Int) = Some(Query(0x1200, 2, 2, 1, id))
      tick(None, Some(0x3b0 -> BigInt(0x5ff)), false)
      tick(None, Some(0x3a0 -> BigInt(0x1f)), false)
      for (age <- 0 until 4) {
        tick(q(age * 10), None, false)
        for (_ <- 0 until age) tick(None, None, false)
        tick(q(age * 10 + 1), None, true)
        for (_ <- 0 until 5) tick(None, None, false)
        tick(q(age * 10 + 2), None, false)
        for (_ <- 0 until 4) tick(None, None, false)
      }
      for (age <- 0 until 4) {
        tick(q(100 + age), None, false)
        for (_ <- 0 until age) tick(None, None, false)
        tick(q(200 + age), Some(0x3a0 -> BigInt(if (age % 2 == 0) 0x18 else 0x1f)), false)
        for (i <- 0 until 8) tick(q(300 + age * 10 + i), None, false)
        for (_ <- 0 until 4) tick(None, None, false)
      }
      // Unrelated CSR must not cancel a request.
      tick(q(999), Some(0x305 -> BigInt(0)), false)
    }
  }
}
