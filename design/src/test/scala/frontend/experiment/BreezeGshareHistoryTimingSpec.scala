package flow.frontend.experiment

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import scala.util.Random

class BreezeGshareHistoryTimingSpec extends AnyFreeSpec with ChiselSim {
  "three banked queries and continuous speculative history match an integer oracle" in {
    simulate(new BreezeGshareHistoryCore) { d =>
      val rng = new Random(0x6200L)
      val table = Array.fill(1024)(rng.nextBoolean())
      d.io.query.valid.poke(false.B); d.io.query.bits.pc.poke(0.U)
      d.io.query.bits.mask1.poke(true.B)
      for (s <- 0 until 2) {
        d.io.query.bits.hit(s).poke(false.B); d.io.query.bits.kind(s).poke(0.U)
        d.io.query.bits.pageFault(s).poke(false.B); d.io.query.bits.accessFault(s).poke(false.B)
      }
      d.io.train.valid.poke(false.B); d.io.train.bits.index.poke(0.U)
      d.io.train.bits.taken.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      // One settled update per cell: weak NT (1) becomes 2 or 0.
      for (i <- 0 until 1024) {
        d.io.train.bits.index.poke(i.U); d.io.train.bits.taken.poke(table(i).B)
        d.io.train.valid.poke(true.B); d.io.train.ready.expect(true.B)
        d.clock.step(); d.io.train.valid.poke(false.B); d.clock.step(7)
      }
      var h = 0; var checked = 0; var denied = 0; var q2Used = 0
      for (n <- 0 until 3000) {
        val valid = n % 13 != 0
        val pc = if (n % 31 == 0) BigInt("fffffffffffffffe",16) else BigInt(rng.nextInt(65536) * 2)
        val hit = Array.fill(2)(rng.nextInt(5) != 0)
        val kind = Array.fill(2)(rng.nextInt(4))
        val pf = Array.fill(2)(rng.nextInt(23) == 0)
        val af = Array.fill(2)(rng.nextInt(29) == 0)
        val mask = rng.nextInt(7) != 0
        d.io.query.valid.poke(valid.B); d.io.query.bits.pc.poke(pc.U)
        d.io.query.bits.mask1.poke(mask.B)
        for (s <- 0 until 2) {
          d.io.query.bits.hit(s).poke(hit(s).B); d.io.query.bits.kind(s).poke(kind(s).U)
          d.io.query.bits.pageFault(s).poke(pf(s).B); d.io.query.bits.accessFault(s).poke(af(s).B)
        }
        val pc0 = ((pc / 2) % 1024).toInt
        val pc1 = ((pc / 2 + 1) % 1024).toInt
        val indices = Array(pc0 ^ h, pc1 ^ h, pc1 ^ ((h * 2) % 1024))
        val available = indices.indices.map { q =>
          val winner = indices.indices.find(k => indices(k) % 4 == indices(q) % 4).get
          indices(q) == indices(winner)
        }
        val taken = indices.indices.map(q => available(q) && table(indices(q)))
        var nextH = h
        val chosen = Array(false, false)
        var stopped = false
        var sawFirstConditionalNT = false
        for (s <- 0 until 2 if !stopped && (s == 0 || mask)) {
          if (hit(s) && !pf(s) && !af(s)) {
            if (kind(s) == 1) {
              val q = if (s == 0) 0 else if (sawFirstConditionalNT) 2 else 1
              chosen(s) = taken(q)
              nextH = (nextH * 2 + (if (chosen(s)) 1 else 0)) % 1024
              if (s == 0 && !chosen(s)) sawFirstConditionalNT = true
              if (s == 1 && q == 2 && valid) q2Used += 1
            } else if (kind(s) >= 2) chosen(s) = true
          }
          stopped = chosen(s)
        }
        d.clock.step(); d.io.valid.expect(valid.B)
        if (valid) {
          d.io.history.expect(h.U); d.io.earlyHistory.expect(nextH.U)
          for (q <- 0 until 3) {
            d.io.indices(q).expect(indices(q).U)
            d.io.available(q).expect(available(q).B); d.io.taken(q).expect(taken(q).B)
            if (!available(q)) denied += 1
          }
          for (s <- 0 until 2) d.io.chosenTaken(s).expect(chosen(s).B)
          h = nextH; checked += 1
        }
      }
      assert(checked > 2500 && denied > 0 && q2Used > 0)
      println(s"GSHARE_HISTORY checked=$checked denied=$denied q2Used=$q2Used")
    }
  }
}
