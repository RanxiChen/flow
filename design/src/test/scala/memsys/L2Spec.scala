package flow.memsys

import chisel3.simulator.scalatest.ChiselSim
import flow.config.BreezeMemGeometry
import flow.l2.L2Home
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** L2/Home against behavioural L1D agents (v1-mem-plan step 4/6).
  *
  * Directed cases pin the transaction shapes of coherence-l2-rtl-spec §4.4/§5.2;
  * random cases run every agent with SWMR, data-value and watchdog checks. The
  * bench checks protocol correctness only; cycle counts are printed for the
  * timing agreement and bounded loosely here.
  */
class L2Spec extends AnyFreeSpec with Matchers with ChiselSim {
  private val ramLine = BigInt(0x80000000L) >> 5

  private def bench(g: BreezeMemGeometry, seed: Long = 1, lines: Seq[BigInt] = Nil, random: Boolean = false)
                   (body: L2Bench => Unit): Unit =
    simulate(new L2Home(g)) { dut =>
      val b = new L2Bench(dut, seed, if (lines.nonEmpty) lines else Seq(ramLine),
        useReaders = random, pGet = if (random) 0.15 else 0, pWrite = if (random) 0.2 else 0, pEvict = if (random) 0.05 else 0)
      b.reset(); b.waitInit(); body(b)
    }

  "directed" - {
    val g = BreezeMemGeometry.default
    val x = ramLine

    "GetS miss: memory read, DataE, owner E" in bench(g) { b =>
      val a0 = b.agents(0)
      a0.issueGet(x, getM = false, b.cycle)
      val n = b.runUntil("DataE")(a0.get.isEmpty)
      a0.state(x) mustBe LState.E
      b.mem.reads_ mustBe 1
      info(s"GetS miss latency: $n cycles")
    }

    "GetS hit owned by another core: Down, both S" in bench(g) { b =>
      val Seq(a0, a1) = b.agents.take(2)
      a0.issueGet(x, getM = true, b.cycle); b.runUntil("a0 DataE")(a0.get.isEmpty)
      a0.write(x, BigInt(0x1234))
      a1.issueGet(x, getM = false, b.cycle)
      val n = b.runUntil("a1 DataS")(a1.get.isEmpty)
      a0.state(x) mustBe LState.S; a1.state(x) mustBe LState.S
      a1.local(x).data mustBe BigInt(0x1234)
      info(s"GetS with Down probe: $n cycles")
    }

    "GetM upgrade with another sharer: Inv sharer, AckE" in bench(g) { b =>
      val Seq(a0, a1) = b.agents.take(2)
      a0.issueGet(x, getM = false, b.cycle); b.runUntil("a0")(a0.get.isEmpty)
      a1.issueGet(x, getM = false, b.cycle); b.runUntil("a1")(a1.get.isEmpty)
      a0.state(x) mustBe LState.S; a1.state(x) mustBe LState.S
      a1.issueGet(x, getM = true, b.cycle); b.runUntil("a1 upgrade")(a1.get.isEmpty)
      a0.state(x) mustBe LState.I; a1.state(x) mustBe LState.E
    }

    "GetM on a line owned M elsewhere: Inv owner, data forwarded" in bench(g) { b =>
      val Seq(a0, a1) = b.agents.take(2)
      a0.issueGet(x, getM = true, b.cycle); b.runUntil("a0")(a0.get.isEmpty)
      a0.write(x, BigInt(0xbeef))
      a1.issueGet(x, getM = true, b.cycle); b.runUntil("a1")(a1.get.isEmpty)
      a0.state(x) mustBe LState.I; a1.state(x) mustBe LState.E; a1.local(x).data mustBe BigInt(0xbeef)
    }

    "dirty Put, PutAck, data survives" in bench(g) { b =>
      val Seq(a0, a1) = b.agents.take(2)
      a0.issueGet(x, getM = true, b.cycle); b.runUntil("a0")(a0.get.isEmpty)
      a0.write(x, BigInt(0x77))
      a0.evict(x, b.cycle); b.runUntil("PutAck")(a0.put.isEmpty)
      a1.issueGet(x, getM = false, b.cycle); b.runUntil("a1")(a1.get.isEmpty)
      a1.local(x).data mustBe BigInt(0x77)
      a1.state(x) mustBe LState.E // no other holder: read copies are granted E
    }

    "L1I Read of a line owned M: Down, latest data" in bench(g) { b =>
      val a0 = b.agents(0); val i0 = b.l1i(0)
      a0.issueGet(x, getM = true, b.cycle); b.runUntil("a0")(a0.get.isEmpty)
      a0.write(x, BigInt(0x99))
      i0.issueRead(x, 0, b.cycle); b.runUntil("ReadData")(i0.idle)
      i0.lastData mustBe Some(BigInt(0x99))
      a0.state(x) mustBe LState.S
    }

    "L2 victim with an L1 copy: back-invalidate, write back, refetch" in {
      val s = BreezeMemGeometry.stress
      val sets = s.nCores * s.l2BytesPerCore / (s.l2Ways * s.lineBytes)
      // l2Ways + 1 lines in set 0 force an L2 eviction.
      val ls = (0 to s.l2Ways).map(i => ramLine + i * sets)
      bench(s, lines = ls) { b =>
        val a0 = b.agents(0); val a1 = b.agents(1)
        a0.issueGet(ls(0), getM = true, b.cycle); b.runUntil("first")(a0.get.isEmpty)
        a0.write(ls(0), BigInt(0x5a5a))
        for (l <- ls.tail) { a1.issueGet(l, getM = false, b.cycle); b.runUntil(f"fill 0x$l%x")(a1.get.isEmpty); a1.evict(l, b.cycle); b.runUntil("put")(a1.put.isEmpty) }
        a0.state(ls(0)) mustBe LState.I // inclusive L2 evicted it
        b.mem.writes_ must be >= 1
        a1.issueGet(ls(0), getM = false, b.cycle); b.runUntil("refetch")(a1.get.isEmpty)
        a1.local(ls(0)).data mustBe BigInt(0x5a5a)
      }
    }
  }

  "random" - {
    val cases = Seq(
      ("stress", BreezeMemGeometry.stress, 4000),
      ("singleCore", BreezeMemGeometry.singleCore.copy(l2BytesPerCore = 256, l2Ways = 2), 3000),
      ("default", BreezeMemGeometry.default, 3000))
    for ((name, g, cycles) <- cases; seed <- 1 to 4) {
      s"$name seed=$seed" in {
        val sets = g.nCores * g.l2BytesPerCore / (g.l2Ways * g.lineBytes)
        // Three lines per L2 set plus same-set pressure: forces evictions, probes and races.
        val ls = (0 until (3 * sets min 12)).map(i => ramLine + i)
        bench(g, seed, ls, random = true) { b =>
          b.run(cycles); b.drain()
          info(s"completed Gets: ${b.agents.map(_.completed).sum}, mem reads ${b.mem.reads_}, writes ${b.mem.writes_}")
          b.agents.map(_.completed).sum must be > 0
        }
      }
    }
  }
}
