package flow.memsys

import chisel3.simulator.scalatest.ChiselSim
import flow.config.BreezeMemGeometry
import flow.l1d.L1DCache
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** L1D against a backend lockstep model and a behavioural Home (v1-mem-plan step 4/6).
  *
  * Exact cycle checks come from the frozen backend contract, which is where L1D
  * timing is already agreed: resp at E+2 (T02/T10), late at R+7 (T11),
  * store→same-word load at E+4 (T08). Everything else is a protocol or value check.
  */
class L1DSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val ram = BigInt(0x80000000L)

  private def bench(g: BreezeMemGeometry, seed: Long = 1)(body: L1DBench => Unit): Unit =
    simulate(new L1DCache(g)) { dut =>
      val b = new L1DBench(dut, seed)
      b.reset(); b.waitInit(); body(b)
    }
  private def fireOf(b: L1DBench, o: MemOp): Long = b.events.find(e => e.kind == "fire" && e.op.exists(_.tag == o.tag)).get.cycle
  private def evOf(b: L1DBench, kind: String, o: MemOp): Long = b.events.find(e => e.kind == kind && e.op.exists(_.tag == o.tag)).get.cycle

  "directed" - {
    val g = BreezeMemGeometry.default

    "load miss: Mshr at E+2, GetS, late at R+7 (T10/T11)" in bench(g) { b =>
      val ld = b.load(ram)
      b.runUntil("late")(b.idle)
      val e = fireOf(b, ld)
      evOf(b, "mshr", ld) mustBe e + 2
      b.home.received.map(_._2).head mustBe s"req${Op.GetS}"
      val r = b.home.delivered.find(d => d._2 == Op.DataE || d._2 == Op.DataS).get._1
      evOf(b, "late", ld) mustBe r + 7
    }

    "load hit: Done at E+2 with data, back-to-back hits every cycle (T02/P01)" in bench(g) { b =>
      b.load(ram); b.runUntil("fill")(b.idle)
      val hits = (0 until 4).map(i => b.load(ram + 8 * i))
      b.runUntil("hits")(b.idle)
      val fires = hits.map(fireOf(b, _))
      fires.sliding(2).foreach(p => p(1) mustBe p(0) + 1)
      hits.foreach(h => evOf(b, "done", h) mustBe fireOf(b, h) + 2)
    }

    "store hit then same-word load: load fires at E+4 and sees the store (T08)" in bench(g) { b =>
      b.store(ram, 0); b.runUntil("own line")(b.idle) // miss → GetM → E
      val st = b.store(ram, BigInt("1122334455667788", 16))
      val ld = b.load(ram)
      b.runUntil("done")(b.idle)
      evOf(b, "done", st) mustBe fireOf(b, st) + 2
      fireOf(b, ld) mustBe fireOf(b, st) + 4
    }

    "store hit then different-word load: no stall (T09)" in bench(g) { b =>
      b.store(ram, 0); b.runUntil("own line")(b.idle)
      val st = b.store(ram, 5); val ld = b.load(ram + 8)
      b.runUntil("done")(b.idle)
      fireOf(b, ld) mustBe fireOf(b, st) + 1
    }

    "store miss: GetM, Mshr at E+2, later load sees it" in bench(g) { b =>
      val st = b.store(ram + 16, 0xabcd, size = 1)
      b.runUntil("store")(b.idle)
      evOf(b, "mshr", st) mustBe fireOf(b, st) + 2
      b.home.received.map(_._2).head mustBe s"req${Op.GetM}"
      b.load(ram + 16, size = 1); b.runUntil("load")(b.idle) // value checked by the bench
    }

    "shared line upgrade: DataS, store → GetM → AckE" in bench(g) { b =>
      // Force a shared grant by stealing with Down after an exclusive fill.
      b.load(ram + 64); b.runUntil("fill")(b.idle)
      b.home.steal(ram + 64 >> 5, inv = false); b.runUntil("down")(b.home.probeIdle)
      b.store(ram + 64, 9); b.runUntil("upgrade")(b.idle)
      b.home.received.map(_._2) must contain(s"req${Op.GetM}")
      b.home.delivered.map(_._2) must contain(Op.AckE)
    }

    "probe Inv on a dirty line: InvAck with data, then refetch sees the other core's write" in bench(g) { b =>
      b.store(ram + 128, 0x42); b.runUntil("own")(b.idle)
      b.home.steal(ram + 128 >> 5, inv = true); b.runUntil("inv")(b.home.probeIdle)
      b.home.received.map(_._2) must contain(s"up${Op.InvAck}")
      for (i <- 0 until 4) b.load(ram + 128 + 8 * i)
      b.runUntil("refetch")(b.idle)
    }

    "dirty victim: Put with data, PutAck, refetch (1-way stress geometry)" in bench(BreezeMemGeometry.stress) { b =>
      val sets = b.p.sets
      b.store(ram, 0x11); b.runUntil("a")(b.idle)
      b.load(ram + sets * 32); b.runUntil("b evicts a")(b.idle)
      b.home.received.map(_._2) must contain(s"up${Op.Put}")
      b.load(ram); b.runUntil("refetch a")(b.idle)
    }

    "MMIO load and store over AXI-Lite" in bench(g) { b =>
      val dev = BigInt(0x12001000L)
      val ld = b.load(dev, size = 2)
      b.store(dev + 4, 0x5a, size = 0)
      b.runUntil("mmio")(b.idle)
      b.events.find(e => e.kind == "done" && e.op.exists(_.tag == ld.tag)).get.data mustBe (b.mmioData(dev) & 0xffffffffL)
      b.mmioWrites.map(_._1) mustBe Seq(dev + 4)
    }

    "fence waits for the MSHR" in bench(g) { b =>
      val st = b.store(ram + 256, 1); val f = b.fence()
      b.runUntil("fence")(b.idle)
      evOf(b, "done", f) must be > evOf(b, "mshr", st)
    }
  }

  "random" - {
    val cases = Seq(
      ("default", BreezeMemGeometry.default),
      ("stress", BreezeMemGeometry.stress),
      ("l1dTwoWay", BreezeMemGeometry.l1dTwoWay))
    for ((name, g) <- cases; seed <- 1 to 4) {
      s"$name seed=$seed: loads/stores/fences, probes, late backpressure" in bench(g, seed) { b =>
        b.home.stealP = 0.02
        b.lateReadyP = 0.7
        // Six lines over two sets plus a same-word pair: hits, misses, conflicts and victims.
        val lines = Seq(0, 1, 2, b.p.sets, b.p.sets + 1, 2 * b.p.sets).map(i => ram + 32 * i)
        val addrs = lines.flatMap(l => Seq(l, l + 8, l + 24))
        b.randomTraffic(400, addrs)
        info(s"committed ${b.committed} ops in ${b.cycle} cycles")
      }
    }
  }
}
