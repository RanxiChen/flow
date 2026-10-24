package flow.frontend.experiment

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.frontend._
import org.scalatest.freespec.AnyFreeSpec
import scala.util.Random

/** Associative table oracle keyed by halfword address; no DUT bank/slot mux reuse. */
private class BtbTableOracle {
  val mask = (BigInt(1) << 64) - 1
  val rows = Array.fill(64)(Array.fill[Option[(BigInt, BigInt, Int)]](2)(None))
  val nextVictim = Array.fill(64)(0)
  def key(pc: BigInt): Int = ((pc / 2) % 64).toInt
  def write(pc: BigInt, target: BigInt, kind: Int, invalidate: Boolean): Unit = {
    val k = key(pc); val row = rows(k)
    val hit = row.indexWhere(_.exists(_._1 == pc))
    if (invalidate) { if (hit >= 0) row(hit) = None }
    else {
      val empty = row.indexWhere(_.isEmpty)
      val victim = if (hit >= 0) hit else if (empty >= 0) empty else nextVictim(k)
      row(victim) = Some((pc, target, kind))
      if (hit < 0) nextVictim(k) = (victim + 1) % 2
    }
  }
  def lookup(pc: BigInt): Option[(BigInt, Int)] = {
    val a = pc & mask
    rows(key(a)).flatten.find(_._1 == a).map(e => (e._2, e._3))
  }
}

class BreezeBtbTimingSpec extends AnyFreeSpec with ChiselSim {
  private def pokeUpdate(port: PipelineBtbUpdate, pc: BigInt, target: BigInt,
      kind: Int = 2, invalidate: Boolean = false): Unit = {
    port.pc.poke(pc.U); port.target.poke(target.U)
    port.kind.poke(kind.U); port.invalidate.poke(invalidate.B)
  }

  "current BTB preserves slot order, tag boundaries, both ways and replacement under II=1 queries" in {
    simulate(new BreezeBankedBtb(BreezeFrontendPipelineParams())) { d =>
      d.io.query.valid.poke(false.B); d.io.query.bits.poke(0.U)
      d.io.update.valid.poke(false.B); pokeUpdate(d.io.update.bits, 0, 0)
      d.io.repair.valid.poke(false.B); pokeUpdate(d.io.repair.bits, 0, 0)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val model = new BtbTableOracle
      var checked = 0
      def query(pc: BigInt): Unit = {
        d.io.query.valid.poke(true.B); d.io.query.bits.poke(pc.U); d.clock.step()
        for (slot <- 0 until 2) {
          val expected = model.lookup(pc + slot * 2)
          d.io.result(slot).hit.expect(expected.isDefined.B)
          d.io.result(slot).kind.expect(expected.map(_._2).getOrElse(0).U)
          expected.foreach(e => d.io.result(slot).target.expect(e._1.U))
          checked += 1
        }
      }
      def update(pc: BigInt, target: BigInt, kind: Int = 2,
          invalidate: Boolean = false, repair: Boolean = false): Unit = {
        d.io.query.valid.poke(false.B)
        if (repair) {
          pokeUpdate(d.io.repair.bits, pc, target, kind, invalidate)
          d.io.repair.valid.poke(true.B)
        } else {
          pokeUpdate(d.io.update.bits, pc, target, kind, invalidate)
          d.io.update.valid.poke(true.B); d.io.update.ready.expect(true.B)
        }
        d.clock.step()
        d.io.repair.valid.poke(false.B); d.io.update.valid.poke(false.B)
        d.clock.step(5)
        model.write(pc, target, kind, invalidate)
      }
      query(0x1000); query(0x1002)
      val directed = Seq(BigInt(0x1000), BigInt(0x1002), BigInt(0x107e), BigInt(0x1080),
        BigInt(0x10fe), BigInt(0x1100), BigInt("8000000000000002", 16),
        BigInt("fffffffffffffffe", 16), BigInt(0))
      directed.zipWithIndex.foreach { case (pc, i) => update(pc, (BigInt(1) << 63) + i * 16, i % 3 + 1) }
      directed.foreach(query)
      // Same row, three tags: exercise empty-way fill and RR eviction.
      for (pc <- Seq(0x2000, 0x2080, 0x2100)) update(pc, pc + 0x8000)
      for (pc <- Seq(0x2000, 0x2080, 0x2100)) query(pc)
      update(0x2100, 0x1234, kind = 3); query(0x2100)
      update(0x2100, 0, invalidate = true); query(0x2100)
      update(0x2102, 0x5678, repair = true); query(0x2100)
      val rng = new Random(0xb7b200L)
      for (n <- 0 until 180) {
        val pc = BigInt(0x4000 + rng.nextInt(512) * 2)
        val target = BigInt(64, rng) & model.mask
        update(pc, target, rng.nextInt(3) + 1, invalidate = n % 11 == 0, repair = n % 7 == 0)
        for (_ <- 0 until 4) query(BigInt(0x4000 + rng.nextInt(512) * 2))
        query(pc)
      }
      // Consecutive queries with arbitrary PC[1], crossing rows/tags.
      for (_ <- 0 until 512) query(BigInt(0x4000 + rng.nextInt(512) * 2))
      d.io.query.valid.poke(false.B); d.clock.step()
      d.io.result.foreach(_.hit.expect(false.B))
      println(s"BTB_BASELINE_CORE checked_slot_results=$checked")
    }
  }

  "BTB update read/write conflicts deny only their own bank and queued updates are not lost" in {
    simulate(new BreezeBankedBtb(BreezeFrontendPipelineParams())) { d =>
      d.io.query.valid.poke(false.B); d.io.query.bits.poke(0x1000.U)
      d.io.update.valid.poke(false.B); pokeUpdate(d.io.update.bits, 0, 0)
      d.io.repair.valid.poke(false.B); pokeUpdate(d.io.repair.bits, 0, 0)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def put(pc: Int, target: Int): Unit = {
        d.io.query.valid.poke(false.B)
        pokeUpdate(d.io.update.bits, pc, target); d.io.update.valid.poke(true.B)
        d.io.update.ready.expect(true.B); d.clock.step()
        d.io.update.valid.poke(false.B); d.clock.step(6)
      }
      put(0x1000, 0x8000); put(0x1002, 0x9000)
      var acceptedTotal = 0; var deniedTotal = 0
      for (bank <- 0 until 2) {
        val updatePc = 0x1010 + bank * 2 // different set from the queried pair
        var sent = 0; var cycles = 0; var denied = 0; var backpressure = 0
        d.io.query.valid.poke(true.B); d.clock.step()
        while ((sent < 24 || cycles < 70) && cycles < 100) {
          d.io.update.valid.poke((sent < 24).B)
          pokeUpdate(d.io.update.bits, updatePc, 0xa000 + sent * 4)
          if (sent < 24 && d.io.update.ready.peek().litToBoolean) { sent += 1; acceptedTotal += 1 }
          else if (sent < 24) backpressure += 1
          d.clock.step(); cycles += 1
          val other = 1 - bank
          d.io.result(other).hit.expect(true.B)
          d.io.result(other).target.expect((if (other == 0) 0x8000 else 0x9000).U)
          if (!d.io.result(bank).hit.peek().litToBoolean) denied += 1
        }
        d.io.update.valid.poke(false.B); d.clock.step(10)
        assert(sent == 24 && denied >= 2 && backpressure > 0)
        deniedTotal += denied
        d.io.query.bits.poke(updatePc.U); d.clock.step()
        d.io.result(0).hit.expect(true.B); d.io.result(0).target.expect((0xa000 + 23 * 4).U)
        d.io.query.bits.poke(0x1000.U); d.clock.step()
      }
      // Normal training takes priority over an opportunistic repair.
      d.io.query.valid.poke(false.B)
      pokeUpdate(d.io.update.bits, 0x1030, 0xaaaa)
      pokeUpdate(d.io.repair.bits, 0x1030, 0xbbbb)
      d.io.update.valid.poke(true.B); d.io.repair.valid.poke(true.B); d.clock.step()
      d.io.update.valid.poke(false.B); d.io.repair.valid.poke(false.B); d.clock.step(8)
      d.io.query.valid.poke(true.B); d.io.query.bits.poke(0x1030.U); d.clock.step()
      d.io.result(0).hit.expect(true.B); d.io.result(0).target.expect(0xaaaa.U)
      println(s"BTB_BASELINE_CONFLICT accepted=$acceptedTotal denied_bank_queries=$deniedTotal")
    }
  }

  "registered timing fixture preserves two-slot results with exact latency and II=1" in {
    simulate(new BreezeBtbTimingHarness) { d =>
      d.io.query.valid.poke(false.B); d.io.query.bits.poke(0.U)
      d.io.update.valid.poke(false.B); pokeUpdate(d.io.update.bits, 0, 0)
      d.io.repair.valid.poke(false.B); pokeUpdate(d.io.repair.bits, 0, 0)
      d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
      val values = (0 until 20).map(i => BigInt(0x1070 + i * 2) -> (BigInt(1) << 63 | BigInt(i * 16 + 2))).toMap
      values.toSeq.sortBy(_._1).foreach { case (pc, target) =>
        pokeUpdate(d.io.update.bits, pc, target)
        d.io.update.valid.poke(true.B); d.clock.step(); d.io.update.valid.poke(false.B)
        d.clock.step(); d.io.updateAccepted.expect(true.B); d.clock.step(6)
      }
      val pending = scala.collection.mutable.Queue[Option[BigInt]](None, None)
      var checked = 0
      val rng = new Random(0x200b7bL)
      for (cycle <- 0 until 260) {
        val offered = if (cycle < 256 && cycle % 7 != 0) Some(BigInt(0x1060 + rng.nextInt(40) * 2)) else None
        d.io.query.valid.poke(offered.isDefined.B); d.io.query.bits.poke(offered.getOrElse(BigInt(0)).U)
        pending.enqueue(offered); val expected = pending.dequeue()
        d.clock.step(); d.io.resultValid.expect(expected.isDefined.B)
        expected.foreach { pc =>
          for (slot <- 0 until 2) {
            val target = values.get(pc + slot * 2)
            d.io.result(slot).hit.expect(target.isDefined.B)
            d.io.result(slot).kind.expect((if (target.isDefined) 2 else 0).U)
            target.foreach(t => d.io.result(slot).target.expect(t.U))
            checked += 1
          }
        }
      }
      assert(checked > 400)
      println(s"BTB_BASELINE_FIXTURE checked_slot_results=$checked")
    }
  }
}
