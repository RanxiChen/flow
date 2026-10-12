package flow.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.util.Random

class BreezePipelinePredictorSpec extends AnyFreeSpec with ChiselSim {
  for (banks <- Seq(1, 4, 8)) {
    s"PHT bank-local initialized reads preserve independent row counters ($banks banks)" in {
      simulate(new BreezeBankedPht(BreezeFrontendPipelineParams(phtBanks = banks))) { d =>
        d.io.query.valid.poke(false.B); d.io.query.bits.foreach(_.poke(0.U))
        d.io.train.valid.poke(false.B); d.io.train.bits.index.poke(0.U)
        d.io.train.bits.taken.poke(false.B)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        val counters = Array.fill(1024)(1)
        val rng = new Random(0xb314L + banks)
        val directed = Seq(0, 1, 2, 3, 4, 255, 256, 257, 511, 512, 768, 1020, 1021, 1022, 1023)
        for (n <- 0 until 128) {
          val index = if (n < directed.size) directed(n) else rng.nextInt(1024)
          val taken = rng.nextBoolean()
          d.io.train.bits.index.poke(index.U); d.io.train.bits.taken.poke(taken.B)
          d.io.train.valid.poke(true.B); d.io.train.ready.expect(true.B)
          d.clock.step(); d.io.train.valid.poke(false.B); d.clock.step(9)
          counters(index) = (counters(index) + (if (taken) 1 else -1)).max(0).min(3)
          val other = if (n % 2 == 0) (index + banks) % 1024 else rng.nextInt(1024)
          val indices = Seq(index, other, if (n % 3 == 0) index else rng.nextInt(1024))
          d.io.query.valid.poke(true.B)
          indices.indices.foreach(q => d.io.query.bits(q).poke(indices(q).U))
          d.clock.step(); d.io.query.valid.poke(false.B)
          for (q <- indices.indices) {
            val selected = indices.find(i => i % banks == indices(q) % banks).get
            val available = indices(q) == selected
            d.io.available(q).expect(available.B)
            d.io.taken(q).expect((available && counters(indices(q)) >= 2).B)
          }
          d.clock.step()
        }
      }
    }
  }

  "PHT broadcasts identical indices, reports bank conflicts and trains current counters" in {
    val p = BreezeFrontendPipelineParams()
    simulate(new BreezeBankedPht(p)) { d =>
      d.io.query.valid.poke(false.B); d.io.query.bits.foreach(_.poke(0.U))
      d.io.train.valid.poke(false.B); d.io.train.bits.index.poke(0.U); d.io.train.bits.taken.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def query(indices: Seq[Int], available: Seq[Boolean], taken: Seq[Boolean]): Unit = {
        d.io.query.valid.poke(true.B)
        indices.indices.foreach(i => d.io.query.bits(i).poke(indices(i).U))
        d.clock.step(); d.io.query.valid.poke(false.B)
        indices.indices.foreach { i =>
          d.io.available(i).expect(available(i).B); d.io.taken(i).expect(taken(i).B)
        }
        d.clock.step()
      }
      query(Seq(0, 1, 4), Seq(true, true, false), Seq(false, false, false))
      query(Seq(0, 1, 0), Seq(true, true, true), Seq(false, false, false))
      def train(taken: Boolean, n: Int): Unit = {
        d.io.train.bits.index.poke(0.U); d.io.train.bits.taken.poke(taken.B)
        d.io.train.valid.poke(true.B)
        var accepted = 0; var limit = 100
        while (accepted < n && limit > 0) {
          if (d.io.train.ready.peek().litToBoolean) accepted += 1
          d.clock.step(); limit -= 1
        }
        assert(accepted == n)
        d.io.train.valid.poke(false.B); d.clock.step(12)
      }
      train(true, 7)
      query(Seq(0, 1, 0), Seq(true, true, true), Seq(true, false, true))
      train(false, 7)
      query(Seq(0, 1, 0), Seq(true, true, true), Seq(false, false, false))
      // A training read owns its bank. Its prediction candidate becomes
      // unavailable, without denying predictions in the other three banks.
      d.io.train.valid.poke(true.B); d.io.train.bits.taken.poke(true.B)
      d.clock.step(); d.io.train.valid.poke(false.B)
      d.io.query.valid.poke(true.B)
      Seq(0, 1, 2).indices.foreach(i => d.io.query.bits(i).poke(i.U))
      d.clock.step()
      d.io.available(0).expect(false.B); d.io.taken(0).expect(false.B)
      d.io.available(1).expect(true.B); d.io.available(2).expect(true.B)
      // Following read overlaps the RMW write of index 0 and must bypass it.
      d.clock.step(); d.io.available(0).expect(true.B)
      d.io.taken(0).expect(false.B) // 0 -> 1 is still NT
    }
  }

  "BTB PC+2 crosses bank and set, updates are associative and invalidation preserves its neighbor" in {
    val p = BreezeFrontendPipelineParams()
    simulate(new BreezeBankedBtb(p)) { d =>
      d.io.query.valid.poke(false.B); d.io.query.bits.poke(0.U)
      d.io.update.valid.poke(false.B); d.io.update.bits.poke(0.U.asTypeOf(d.io.update.bits))
      d.io.repair.valid.poke(false.B); d.io.repair.bits.poke(0.U.asTypeOf(d.io.repair.bits))
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def update(pc: Int, target: Int, invalidate: Boolean = false): Unit = {
        d.io.update.bits.pc.poke(pc.U); d.io.update.bits.target.poke(target.U)
        d.io.update.bits.kind.poke(2.U); d.io.update.bits.invalidate.poke(invalidate.B)
        d.io.update.valid.poke(true.B); d.io.update.ready.expect(true.B)
        d.clock.step(); d.io.update.valid.poke(false.B); d.clock.step(5)
      }
      def query(): Unit = {
        d.io.query.valid.poke(true.B); d.io.query.bits.poke(0x102.U)
        d.clock.step(); d.io.query.valid.poke(false.B)
      }
      update(0x102, 0x200); update(0x104, 0x300)
      query(); d.io.result(0).hit.expect(true.B); d.io.result(0).target.expect(0x200.U)
      d.io.result(1).hit.expect(true.B); d.io.result(1).target.expect(0x300.U); d.clock.step()
      update(0x102, 0x400); update(0x102, 0x500)
      query(); d.io.result(0).hit.expect(true.B); d.io.result(0).target.expect(0x500.U); d.clock.step()
      update(0x102, 0, invalidate = true)
      query(); d.io.result(0).hit.expect(false.B)
      d.io.result(1).hit.expect(true.B); d.io.result(1).target.expect(0x300.U)
    }
  }
}
