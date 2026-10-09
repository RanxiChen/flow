package flow.memsys

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.coherence._
import flow.config.BreezeMemGeometry
import flow.interface._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.util.Random

import MemTestKit._

/** L1D + behavioral L2 + identity dTLB (l1d-rtl-spec 13.2). Every load is
  * checked against a golden memory at its response or late data. `finish`
  * invalidates every L1D line and compares the written-back memory with it.
  * Atomic results and final dirty writebacks use the same independent golden.
  */
class L1DCacheSpec extends AnyFreeSpec with Matchers with ChiselSim {
  class Env(val d: L1DTestHarness, val g: BreezeMemGeometry, seed: Int) {
    val rng = new Random(seed)
    val arch = new GoldenMem(seed)
    val l2 = new BehavioralL2(d.io.coh, new GoldenMem(seed), rng)
    val device = new AxiLiteDevice(d.io.mmio, rng, seed + 7)
    val core = new CoreDriver(d.io.core, arch, Some(device), rng)
    val ptw = new PtwDriver(d.io.ptw, arch)
    val tlb = new TlbKnobs(d.io.tlb, rng)
    val bench = new Bench(d.clock, Seq(tlb, core, ptw, l2, device))
    /** Next tag in the same L1D set. */
    val stride: BigInt = BigInt(g.l1Sets) * LineBytes
    def ram(off: BigInt): BigInt = MainRam + off
    def line(a: BigInt): BigInt = lineOf(a)
    def preload(a: BigInt, n: Int, v: BigInt): Unit = { arch.write(a, n, v); l2.backing.write(a, n, v) }
    def runOps(ops: CoreOp*): Unit = { core.enqueue(ops: _*); bench.quiesce() }
    def txns(from: Int): Seq[CoreTxn] = core.history.drop(from).toSeq
    def finish(): Unit = { bench.quiesce(); l2.flushAll(bench); l2.compareWith(arch) }
  }

  private def init(d: L1DTestHarness): Unit = {
    val c = d.io.core
    c.req.valid.poke(false.B); c.req.bits.op.poke(L1DOp.Load); c.req.bits.vaddr.poke(0.U)
    c.req.bits.size.poke(3.U); c.req.bits.signed.poke(false.B); c.req.bits.amoFunc.poke(BreezeAmoFunc.Swap)
    c.req.bits.aq.poke(false.B); c.req.bits.rl.poke(false.B); c.req.bits.wdata.poke(0.U)
    c.req.bits.rd.isFp.poke(false.B); c.req.bits.rd.idx.poke(1.U); c.req.bits.isFlw.poke(false.B)
    c.s1Kill.poke(false.B); c.s2Kill.poke(false.B); c.trapClearRsv.poke(false.B); c.late.ready.poke(true.B)
    c.csr.satp.poke(0.U); c.csr.privilege.poke(3.U); c.csr.mprv.poke(false.B); c.csr.mpp.poke(0.U)
    c.csr.sum.poke(false.B); c.csr.mxr.poke(false.B); c.csr.adue.poke(false.B)
    c.csr.permissionEvent.poke(false.B)
    for (i <- c.csr.pmpcfg.indices) { c.csr.pmpcfg(i).poke(0.U); c.csr.pmpaddr(i).poke(0.U) }
    c.csr.pmpcfg(0).poke(0x1f.U); c.csr.pmpaddr(0).poke(0x1fffffff.U)
    d.io.ptw.req.valid.poke(false.B); d.io.ptw.req.bits.paddr.poke(0.U)
    val h = d.io.coh
    h.req.ready.poke(false.B); h.rspUp.ready.poke(true.B)
    h.snp.valid.poke(false.B); h.snp.bits.op.poke(SnpOp.Inv); h.snp.bits.owner.poke(false.B); h.snp.bits.addr.poke(0.U)
    h.rspDown.valid.poke(false.B); h.rspDown.bits.op.poke(RspDownOp.DataE); h.rspDown.bits.id.poke(0.U)
    h.rspDown.bits.error.poke(false.B); h.rspDown.bits.data.poke(0.U)
    val m = d.io.mmio
    m.ar.ready.poke(false.B); m.aw.ready.poke(false.B); m.w.ready.poke(false.B)
    m.r.valid.poke(false.B); m.r.bits.data.poke(0.U); m.r.bits.resp.poke(0.U)
    m.b.valid.poke(false.B); m.b.bits.poke(0.U)
    d.io.tlb.tlbReady.poke(true.B); d.io.tlb.tlbMiss.poke(false.B); d.io.tlb.tlbPageFault.poke(false.B)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }

  private def withL1D(g: BreezeMemGeometry = BreezeMemGeometry.singleCore, seed: Int = 1)(body: Env => Unit): Unit =
    simulate(new L1DTestHarness(g)) { d =>
      init(d)
      val env = new Env(d, g, seed)
      body(env)
      env.finish()
    }

  "load miss refills with GetS, then hits return every size, offset, sign and NaN-boxed FLW" in withL1D() { e =>
    import e._
    val a = ram(0x100)
    preload(a, 8, BigInt("80ff7f0181fe0280", 16)); preload(a + 8, 8, BigInt("fedcba9876543210", 16))
    runOps(CoreOp.load(a))
    core.last.kind mustBe "Mshr"
    l2.gets.map(x => (x._2, x._3)) mustBe Seq(("GetS", line(a)))
    val n = core.history.size
    val loads = for (size <- 0 to 3; off <- 0 until LineBytes by (1 << size); signed <- Seq(false, true))
      yield CoreOp.load(a + off, size, signed, rd = 1 + off % 31)
    runOps(loads: _*)
    runOps(CoreOp(L1DOp.Load, a + 4, size = 2, isFlw = true))
    txns(n).foreach(_.kind mustBe "Done")
    l2.gets.size mustBe 1
  }

  "store hits on E merge bytes in place and an Inv probe returns the dirty line" in withL1D() { e =>
    import e._
    val a = ram(0x200)
    runOps(CoreOp.load(a))
    val n = core.history.size
    runOps(CoreOp.store(a + 1, 0xab, 0), CoreOp.store(a + 6, 0xbeef, 1),
      CoreOp.store(a + 12, BigInt("deadbeef", 16), 2), CoreOp.store(a + 16, BigInt("0123456789abcdef", 16)),
      CoreOp.load(a), CoreOp.load(a + 8), CoreOp.load(a + 16), CoreOp.load(a + 6, 1, signed = true))
    txns(n).foreach(_.kind mustBe "Done")
    l2.gets.size mustBe 1
    finish()
    l2.acks.map(x => (x._2, x._3, x._4)) mustBe Seq(("InvAck", line(a), true))
  }

  "store miss sends GetM, replays through the pending store, and later loads see it" in withL1D() { e =>
    import e._
    val a = ram(0x300)
    runOps(CoreOp.store(a + 8, BigInt("1122334455667788", 16)))
    core.last.kind mustBe "Mshr"
    runOps(CoreOp.load(a + 8), CoreOp.load(a + 12, 2), CoreOp.load(a))
    core.history.takeRight(3).foreach(_.kind mustBe "Done")
    l2.gets.map(_._2) mustBe Seq("GetM")
    finish()
    l2.acks.map(_._4) mustBe Seq(true)
  }

  "store to a shared line upgrades with GetM and AckE and keeps the rest of the line" in withL1D() { e =>
    import e._
    val a = ram(0x400)
    l2.sharedLines += line(a)
    runOps(CoreOp.load(a + 24))
    runOps(CoreOp.store(a, 0x55aa, 1))
    core.last.kind mustBe "Mshr"
    runOps(CoreOp.load(a), CoreOp.load(a + 24))
    l2.getsOf(line(a)) mustBe Seq("GetS", "GetM")
    l2.grantsOf(line(a)) mustBe Seq("DataS", "AckE")
    finish()
  }

  "a store racing an earlier sharer Inv reacquires the line before later dirty eviction" in withL1D() { e =>
    import e._
    val a = ram(0x440)
    l2.sharedLines += line(a)
    runOps(CoreOp.load(a))
    l2.probe(SnpOp.Inv, line(a))
    runOps(CoreOp.store(a + 8, BigInt("0123456789abcdef", 16)), CoreOp.load(a + 8))
    l2.grantsOf(line(a)) mustBe Seq("DataS", "DataE")
    l2.gets.filter(_._3 == line(a)).last._1 must be > l2.acks.head._1
    // Force this single-set L1D line out through Put, then read it back.
    runOps((1 to 2 * g.l1dWays).map(k => CoreOp.load(a + k * stride)): _*)
    l2.puts.exists(x => x._2 == line(a) && x._3) mustBe true
    runOps(CoreOp.load(a + 8))
    finish()
  }

  "victims fill invalid ways first, only dirty victims carry data, and evicted data returns" in withL1D() { e =>
    import e._
    val ways = g.l1dWays
    val set = (0 until 2 * ways + 2).map(k => ram(0x40 + k * stride))
    runOps(set.take(ways).map(CoreOp.load(_)): _*)
    l2.puts mustBe empty
    runOps(CoreOp.store(set(1), 0x1111), CoreOp.store(set(ways - 1) + 8, 0x2222))
    val dirty = scala.collection.mutable.Set(line(set(1)), line(set(ways - 1)))
    for (a <- set.drop(ways)) {
      val before = l2.puts.size
      runOps(CoreOp.load(a))
      l2.puts.size mustBe before + 1
      val (_, victim, hasData) = l2.puts.last
      withClue(s"victim ${hex(victim << 5)}: ") { hasData mustBe dirty.remove(victim) }
    }
    runOps(set.map(CoreOp.load(_)): _*)
    finish()
  }

  "a hit completes under a miss, and back-to-back store/load pairs see program order" in withL1D() { e =>
    import e._
    val h = ram(0x500); val m = ram(0x540)
    runOps(CoreOp.load(h))
    l2.minLatency = 25; l2.maxLatency = 25
    val n = core.history.size
    runOps(CoreOp.load(m, rd = 3), CoreOp.load(h + 8, rd = 4),
      CoreOp.store(h + 16, BigInt("cafef00d", 16), 2), CoreOp.load(h + 16, 2, rd = 5),
      CoreOp.store(h + 24, 0x7f, 0), CoreOp.load(h + 24, rd = 6),
      CoreOp.store(h, 0x1234, 1), CoreOp.load(h, 2, rd = 7))
    val t = txns(n)
    t.head.kind mustBe "Mshr"
    t(1).kind mustBe "Done"
    t(1).respCycle must be < t.head.lateCycle
    t.drop(2).foreach(_.kind mustBe "Done")
    finish()
  }

  "SOC3d-control failed SC completes on the occupied MSHR line without a new miss" in withL1D(seed = 61) { e =>
    import e._
    val h = ram(0x1540); val m = ram(0x1580)
    runOps(CoreOp.load(h))
    val n = core.history.size
    val getsBefore = l2.gets.size
    l2.reqReadyProb = 0
    core.enqueue(CoreOp.load(m, rd = 2), CoreOp.sc(m, 0x1234, success = false), CoreOp.load(h, rd = 3))
    bench.runUntil(core.history.size >= n + 3 && core.history(n + 2).respCycle >= 0, limit = 1000)
    core.history(n).kind mustBe "Mshr"
    core.history(n).lateCycle mustBe -1L
    core.history(n + 1).kind mustBe "Done"
    core.history(n + 1).value mustBe BigInt(1)
    core.history(n + 2).kind mustBe "Done"
    l2.gets.size mustBe getsBefore
    core.drained mustBe false
    bench.steps(5) // Driver rejects duplicated/unowned responses.
    l2.reqReadyProb = 1
    bench.quiesce()
    l2.getsOf(line(m)) mustBe Seq("GetS")
    runOps(CoreOp.load(m)) // Failed SC must leave the independent golden intact.
  }

  "SOC3d-control PTW and CPU recheck each finish once after a stalled store refill" in withL1D(seed = 62) { e =>
    import e._
    val h = ram(0x1640); val m = ram(0x1680)
    runOps(CoreOp.load(h))
    val n = core.history.size
    val holdsBefore = core.holdCycles
    l2.reqReadyProb = 0
    core.enqueue(CoreOp.store(m, 0x3456), CoreOp.load(m, rd = 2),
      CoreOp.store(h + 8, 0x7788), CoreOp.load(h + 8, rd = 3), CoreOp.fence)
    bench.runUntil(core.history.size >= n + 2 && core.holdCycles > holdsBefore + 3, limit = 1000)
    core.history(n).kind mustBe "Mshr"
    core.history(n + 1).respCycle mustBe -1L
    ptw.read(h + 24)
    bench.steps(5)
    ptw.results mustBe empty // PTW admission waits for its reserved miss capacity.
    l2.probe(SnpOp.Inv, line(h))
    bench.runUntil(l2.acks.exists(_._3 == line(h)), limit = 1000)
    core.history(n + 1).respCycle mustBe -1L
    l2.getsOf(line(m)) mustBe empty
    l2.reqReadyProb = 1
    bench.quiesce()
    ptw.results.size mustBe 1
    ptw.results.head._1 mustBe h + 24
    ptw.results.head._3 mustBe false
    core.history.size mustBe n + 5
    core.history(n + 1).kind mustBe "Done"
    core.history.last.kind mustBe "Done" // FENCE retires after refill/PS/PTW drain.
    core.history.drop(n + 1).foreach(_.done mustBe true)
    l2.getsOf(line(m)) mustBe Seq("GetM")
    runOps(CoreOp.load(m), CoreOp.load(h + 8))
  }

  "a second miss waits for the single MSHR and a same-line access waits for the refill" in withL1D() { e =>
    import e._
    l2.minLatency = 15; l2.maxLatency = 15
    val a = ram(0xe00); val b = ram(0xe40)
    runOps(CoreOp.load(a, rd = 2), CoreOp.load(b, rd = 3), CoreOp.load(b + 8, rd = 4))
    val t = core.history.toSeq
    t(0).kind mustBe "Mshr"; t(1).kind mustBe "Mshr"; t(2).kind mustBe "Done"
    t(1).respCycle must be >= t(0).lateCycle
    t(2).respCycle must be >= t(1).lateCycle
    l2.gets.map(_._3) mustBe Seq(line(a), line(b))
    finish()
  }

  "a shared store waiting for the MSHR lets a probe pass a younger same-set store" in withL1D() { e =>
    import e._
    val a = ram(0x1200); val b = a + stride; val m = a + 2 * stride; val y = a + 3 * stride
    l2.sharedLines += line(a)
    runOps(CoreOp.load(a), CoreOp.load(b))
    val n = core.history.size
    l2.reqReadyProb = 0
    core.enqueue(CoreOp.load(m, rd = 2), CoreOp.store(a, 0x1234), CoreOp.store(y, 0x5678))
    bench.runUntil(core.history.size > n && core.history(n).kind == "Mshr")
    bench.steps(3)
    l2.probe(SnpOp.Inv, line(b))
    // The older Get stays backpressured. Probe progress must be local and
    // must not depend on freeing its MSHR or advancing either younger Store.
    bench.runUntil(l2.acks.nonEmpty)
    (l2.acks.head._2, l2.acks.head._3) mustBe (("InvAck", line(b)))
    l2.getsOf(line(m)) mustBe empty
    core.history(n + 1).respCycle mustBe -1L
    l2.reqReadyProb = 1
    bench.quiesce()
    core.history(n + 1).respCycle must be > core.history(n).lateCycle
    runOps(CoreOp.load(a), CoreOp.load(y))
    finish()
  }

  for (op <- Seq(SnpOp.Inv, SnpOp.Down))
    s"a FENCE waiting for an older miss lets $op pass its held younger store" in withL1D() { e =>
      import e._
      val b = ram(0x1240); val m = ram(0x3280)
      runOps(CoreOp.store(b, 0x1234))
      val n = core.history.size
      l2.reqReadyProb = 0
      core.enqueue(CoreOp.store(m, 0x5678), CoreOp.fence, CoreOp.store(b, 0x9abc))
      bench.runUntil(core.history.size >= n + 3 && core.history(n).kind == "Mshr")
      bench.steps(3)
      l2.probe(op, line(b))
      // The younger Store is held behind FENCE, so it cannot mutate this
      // line. The probe must complete before the older miss is released.
      bench.runUntil(l2.acks.nonEmpty)
      l2.acks.head._3 mustBe line(b)
      l2.acks.head._4 mustBe true
      l2.backing.read(b, 8) mustBe BigInt(0x1234)
      l2.getsOf(line(m)) mustBe empty
      core.history(n + 1).respCycle mustBe -1L
      core.history(n + 2).respCycle mustBe -1L
      l2.reqReadyProb = 1
      bench.quiesce()
      core.history(n + 2).respCycle must be > core.history(n + 1).respCycle
      runOps(CoreOp.load(b), CoreOp.load(m))
      finish()
    }

  "killed stores never write, and a load miss killed at WB sends no GetS" in withL1D() { e =>
    import e._
    val k = ram(0x600); val n = ram(0x640)
    runOps(CoreOp.load(k))
    runOps(CoreOp.store(k, 0x1111).copy(s1Kill = true))
    core.last.killed mustBe true
    runOps(CoreOp.store(k, 0x2222).copy(s2KillAtResp = true))
    core.last.killed mustBe true
    runOps(CoreOp.load(k))
    runOps(CoreOp.load(n).copy(s2KillAtResp = true))
    core.last.killed mustBe true
    l2.getsOf(line(n)) mustBe empty
    runOps(CoreOp.load(n))
    l2.getsOf(line(n)) mustBe Seq("GetS")
    // A younger store in S1 dies with the older request killed at WB.
    runOps(CoreOp.load(k).copy(s2KillAtResp = true), CoreOp.store(k + 8, 0x3333))
    core.history.takeRight(2).foreach(_.killed mustBe true)
    runOps(CoreOp.load(k + 8))
    finish()
  }

  "Inv and Down answer with data only from M and leave the right local state" in withL1D() { e =>
    import e._
    val p1 = ram(0x700); val p2 = ram(0x740); val q = ram(0x780)
    runOps(CoreOp.load(p1))
    l2.probe(SnpOp.Inv, line(p1)); bench.quiesce()
    runOps(CoreOp.store(p2, 0x77))
    l2.probe(SnpOp.Down, line(p2)); bench.quiesce()
    l2.probe(SnpOp.Inv, line(q)); bench.quiesce()
    l2.acks.map(x => (x._2, x._3, x._4)) mustBe
      Seq(("InvAck", line(p1), false), ("DownAck", line(p2), true), ("InvAck", line(q), false))
    l2.backing.read(p2, 8) mustBe arch.read(p2, 8)
    val gets = l2.gets.size
    runOps(CoreOp.load(p2))
    l2.gets.size mustBe gets
    runOps(CoreOp.load(p1))
    l2.getsOf(line(p1)) mustBe Seq("GetS", "GetS")
    runOps(CoreOp.store(p2 + 8, 0x88))
    l2.grantsOf(line(p2)) mustBe Seq("DataE", "AckE")
    finish()
  }

  "a probe that arrives with the grant waits for the replay and answers with its result" in withL1D() { e =>
    import e._
    val r1 = ram(0x800); val r2 = ram(0x840); val r3 = ram(0x880)
    l2.probeAfterGrant(line(r1)) = SnpOp.Inv
    runOps(CoreOp.load(r1, rd = 9))
    val t1 = core.last
    val a1 = l2.acks.last
    (a1._2, a1._3, a1._4) mustBe (("InvAck", line(r1), false))
    a1._1 must be > t1.lateCycle
    l2.probeAfterGrant(line(r2)) = SnpOp.Inv
    runOps(CoreOp.store(r2 + 8, BigInt("abcdef", 16)))
    l2.acks.last._4 mustBe true
    l2.backing.read(r2 + 8, 8) mustBe BigInt("abcdef", 16)
    l2.probeAfterGrant(line(r3)) = SnpOp.Down
    runOps(CoreOp.store(r3, 0x99))
    (l2.acks.last._2, l2.acks.last._3, l2.acks.last._4) mustBe (("DownAck", line(r3), true))
    for (a <- Seq(r1, r2, r3)) {
      val l = line(a)
      l2.probes.find(_._3 == l).get._1 mustBe l2.grants.find(_._3 == l).get._1
    }
    val gets = l2.gets.size
    runOps(CoreOp.load(r3), CoreOp.load(r1))
    l2.gets.size mustBe gets + 1
    finish()
  }

  "a sharer Inv during an upgrade drops S at once and the later DataE carries another core's write" in withL1D() { e =>
    import e._
    val u = ram(0x900)
    l2.sharedLines += line(u)
    runOps(CoreOp.load(u + 24))
    l2.upgradeRace += line(u)
    val other = BigInt("5a5a5a5a5a5a5a5a", 16)
    l2.beforeRacedGrant = (l, cycle) => {
      arch.write((l << 5) + 16, 8, other, cycle); l2.backing.write((l << 5) + 16, 8, other, cycle)
    }
    runOps(CoreOp.store(u, 0x4242))
    l2.acks.map(x => (x._2, x._4)) mustBe Seq(("InvAck", false))
    l2.grantsOf(line(u)) mustBe Seq("DataS", "DataE")
    runOps(CoreOp.load(u), CoreOp.load(u + 16), CoreOp.load(u + 24))
    finish()
  }

  "a refill error is reported on late data or drops the store, and leaves the line invalid" in withL1D() { e =>
    import e._
    val a = ram(0xa00); val b = ram(0xa40)
    l2.errorLines += line(a)
    runOps(CoreOp.load(a).copy(refillError = true))
    runOps(CoreOp.load(a))
    l2.getsOf(line(a)) mustBe Seq("GetS", "GetS")
    l2.errorLines += line(b)
    runOps(CoreOp.store(b, 0x1234).copy(refillError = true))
    runOps(CoreOp.load(b))
    l2.getsOf(line(b)) mustBe Seq("GetM", "GetS")
    finish()
  }

  "device accesses use blocking AXI-Lite with formatted data, byte strobes and error responses" in withL1D() { e =>
    import e._
    val dev = Device
    device.regs.write(dev, 8, BigInt("8877665544332211", 16))
    device.regs.write(dev + 8, 8, BigInt("fffefdfc80818283", 16))
    def ld(a: BigInt, size: Int, signed: Boolean = false) = CoreOp.load(a, size, signed).copy(device = true)
    def st(a: BigInt, v: BigInt, size: Int) = CoreOp.store(a, v, size).copy(device = true)
    runOps(ld(dev, 3), ld(dev + 4, 2), ld(dev + 8, 2, signed = true), ld(dev + 11, 0, signed = true), ld(dev + 14, 1))
    core.history.foreach(_.kind mustBe "Done")
    runOps(st(dev + 12, BigInt("cafebabe", 16), 2), st(dev + 1, 0x5a, 0), ld(dev + 8, 3), ld(dev, 3))
    device.log.filter(_._1 == "W").map(x => (x._2, x._3, x._4)) mustBe
      Seq((dev + 12, BigInt("cafebabe", 16) << 32, 0xf0), (dev + 1, BigInt(0x5a) << 8, 0x02))
    device.log.filter(_._1 == "R").map(_._2) mustBe Seq(dev, dev + 4, dev + 8, dev + 11, dev + 14, dev + 8, dev)
    device.errorWords += dev + 16
    runOps(ld(dev + 16, 3).copy(expectExc = Some(5)), st(dev + 16, 1, 3).copy(expectExc = Some(7)))
    l2.gets mustBe empty
    core.mmioBusyCycles must be > 0L
  }

  "misaligned, unmapped, read-only and page-faulting accesses raise the right cause without traffic" in withL1D() { e =>
    import e._
    def exc(o: CoreOp, c: Int) = o.copy(expectExc = Some(c))
    runOps(exc(CoreOp.load(ram(4)), 4), exc(CoreOp.load(ram(2), 2), 4), exc(CoreOp.store(ram(1), 0, 1), 6),
      exc(CoreOp.load(Hole), 5), exc(CoreOp.store(Hole + 8, 0), 7), exc(CoreOp.store(Rom, 0, 2), 7))
    tlb.pageFault = true
    runOps(exc(CoreOp.load(ram(0x40)), 13), exc(CoreOp.store(ram(0x48), 0), 15))
    tlb.pageFault = false
    l2.gets mustBe empty
    device.log mustBe empty
    runOps(CoreOp.load(ram(0x40)))
    core.last.kind mustBe "Mshr"
  }

  "TLB misses and a busy dTLB delay requests, which still complete in program order" in withL1D() { e =>
    import e._
    val a = ram(0xb00); val b = ram(0xb40)
    runOps(CoreOp.load(a))
    val before = tlb.requests
    tlb.missUntil = bench.cycle + 40
    runOps(CoreOp.store(a + 8, 0x6161), CoreOp.load(a + 8), CoreOp.load(b), CoreOp.store(b + 16, 0x99, 0))
    tlb.requests must be > before + 4
    tlb.busyUntil = bench.cycle + 30
    runOps(CoreOp.load(a), CoreOp.store(a, 0x1), CoreOp.load(b + 16, 0))
    finish()
  }

  "PTW reads hit, miss through GetS, see committed stores, and leave the line usable by the CPU" in withL1D() { e =>
    import e._
    val w = ram(0xc00); val x = ram(0xc40)
    runOps(CoreOp.load(w))
    ptw.read(w + 8); bench.quiesce()
    l2.gets.size mustBe 1
    ptw.read(x + 16); bench.quiesce()
    l2.getsOf(line(x)) mustBe Seq("GetS")
    runOps(CoreOp.load(x + 16))
    core.last.kind mustBe "Done"
    runOps(CoreOp.store(w + 8, 0x4545))
    ptw.read(w + 8); bench.quiesce()
    ptw.results.size mustBe 3
    finish()
  }

  "FENCE completes only after an outstanding store miss has drained" in withL1D() { e =>
    import e._
    val f = ram(0xd00)
    l2.minLatency = 20; l2.maxLatency = 20
    runOps(CoreOp.store(f, 0x5150), CoreOp.fence)
    val fence = core.last
    fence.kind mustBe "Done"
    fence.respCycle must be > l2.grants.last._1
    finish()
  }

  "LR gets exclusive permission, SC succeeds once and a failed SC never misses" in withL1D() { e =>
    import e._
    val a = ram(0x1800); val b = ram(0x1840)
    preload(a, 8, BigInt("8000000180000002", 16))
    runOps(CoreOp.lr(a, 2), CoreOp.sc(a + 4, 0x12345678, success = true, size = 2),
      CoreOp.sc(a, 0x9999, success = false), CoreOp.sc(b, 0x9999, success = false), CoreOp.load(a))
    core.history.take(4).foreach(_.kind mustBe "Done")
    l2.gets.map(_._2) mustBe Seq("GetM")
    core.history.head.value mustBe BigInt("ffffffff80000002", 16)
    l2.getsOf(line(b)) mustBe empty
    // Other memory traffic does not clear reservation; the set is not full.
    runOps(CoreOp.lr(a), CoreOp.load(b), CoreOp.store(b + 8, 0x77), CoreOp.sc(a, 0x33, success = true))
  }

  "LR upgrades S with GetM and AckE before creating its reservation" in withL1D() { e =>
    import e._
    val a = ram(0x1900)
    l2.sharedLines += line(a)
    runOps(CoreOp.load(a), CoreOp.lr(a), CoreOp.sc(a, 0x1234, success = true))
    l2.getsOf(line(a)) mustBe Seq("GetS", "GetM")
    l2.grantsOf(line(a)) mustBe Seq("DataS", "AckE")
  }

  "a pending probe permits SC within the LR window and sees the successful write" in withL1D() { e =>
    import e._
    val a = ram(0x1a00)
    runOps(CoreOp.lr(a))
    l2.probe(SnpOp.Inv, line(a))
    bench.steps(5)
    l2.acks mustBe empty
    runOps(CoreOp.sc(a, 0x5151, success = true))
    l2.acks.last._4 mustBe true
    l2.backing.read(a, 8) mustBe BigInt(0x5151)
    runOps(CoreOp.sc(a, 0x6262, success = false))
  }

  "probe timeout, trap and eviction clear reservations, but timer expiry alone does not" in withL1D() { e =>
    import e._
    val a = ram(0x1b00)
    runOps(CoreOp.lr(a)); bench.steps(90)
    runOps(CoreOp.sc(a, 0x11, success = true))
    runOps(CoreOp.lr(a))
    l2.probe(SnpOp.Down, line(a)); bench.quiesce()
    runOps(CoreOp.sc(a, 0x22, success = false))
    runOps(CoreOp.lr(a))
    core.trapClear = true; bench.steps(1); core.trapClear = false
    runOps(CoreOp.sc(a, 0x33, success = false))
    runOps(CoreOp.lr(a))
    runOps((1 to 2 * g.l1dWays).map(k => CoreOp.load(a + k * stride)): _*)
    runOps(CoreOp.sc(a, 0x44, success = false))
  }

  "all nine AMOs return the old operand and preserve the other word half" in withL1D(seed = 71) { e =>
    import e._
    val a = ram(0x1c00)
    val funcs = Seq(BreezeAmoFunc.Swap, BreezeAmoFunc.Add, BreezeAmoFunc.Xor, BreezeAmoFunc.Or,
      BreezeAmoFunc.And, BreezeAmoFunc.Min, BreezeAmoFunc.Max, BreezeAmoFunc.MinU, BreezeAmoFunc.MaxU)
    for (size <- Seq(2, 3); off <- (if (size == 2) Seq(0, 4) else Seq(0)); f <- funcs;
         (old, rhs) <- Seq((BigInt("8000000180000001", 16), BigInt(7)),
           (BigInt(7), BigInt("ffffffffffffffff", 16)), (mask(64), BigInt(1)))) {
      runOps(CoreOp.store(a, old), CoreOp.amo(a + off, rhs, f, size), CoreOp.load(a))
    }
    core.history.filter(t => enumIs(t.op.op, L1DOp.AMO)).foreach(_.kind mustBe "Done")
  }

  "AMO cold misses, shared upgrades and grant probes use GetM and protected RMW" in withL1D() { e =>
    import e._
    val a = ram(0x1d00); val b = ram(0x1d40); val c = ram(0x1d80)
    l2.probeAfterGrant(line(a)) = SnpOp.Inv
    runOps(CoreOp.amo(a + 4, 1, BreezeAmoFunc.Add, 2), CoreOp.load(a + 4, 2, signed = true))
    l2.acks.head._4 mustBe true
    l2.acks.head._1 must be > core.history.head.respCycle
    l2.sharedLines += line(b)
    runOps(CoreOp.load(b), CoreOp.amo(b, 0x77, BreezeAmoFunc.Swap))
    l2.grantsOf(line(b)) mustBe Seq("DataS", "AckE")
    l2.sharedLines += line(c)
    runOps(CoreOp.load(c)); l2.upgradeRace += line(c)
    runOps(CoreOp.amo(c, 3, BreezeAmoFunc.Add), CoreOp.load(c))
    l2.grantsOf(line(c)) mustBe Seq("DataS", "DataE")
  }

  "atomic permissions and refill errors trap before writes or failed-SC status" in withL1D() { e =>
    import e._
    for (a <- Seq(Device, Rom, Hole)) {
      runOps(CoreOp.lr(a).copy(expectExc = Some(5)),
        CoreOp.sc(a, 1, success = false).copy(expectExc = Some(7)),
        CoreOp.amo(a, 1, BreezeAmoFunc.Add).copy(expectExc = Some(7)))
    }
    val a = ram(0x1e00); val b = ram(0x1e40)
    runOps(CoreOp.lr(a + 1).copy(expectExc = Some(4)),
      CoreOp.sc(a + 1, 1, success = false).copy(expectExc = Some(6)),
      CoreOp.amo(a + 1, 1, BreezeAmoFunc.Add).copy(expectExc = Some(6)))
    tlb.pageFault = true
    runOps(CoreOp.lr(a).copy(expectExc = Some(13)),
      CoreOp.sc(a, 1, success = false).copy(expectExc = Some(15)),
      CoreOp.amo(a, 1, BreezeAmoFunc.Add).copy(expectExc = Some(15)))
    tlb.pageFault = false
    l2.gets mustBe empty; device.log mustBe empty
    l2.errorLines += line(a); l2.errorLines += line(b)
    runOps(CoreOp.lr(a).copy(expectExc = Some(5)), CoreOp.amo(b, 1, BreezeAmoFunc.Add).copy(expectExc = Some(7)))
    runOps(CoreOp.sc(a, 1, success = false), CoreOp.load(a), CoreOp.load(b))
  }

  "an AMO upgrade waiting for GetM lets the required sharer Inv finish first" in withL1D() { e =>
    import e._
    val a = ram(0x2200)
    l2.sharedLines += line(a)
    runOps(CoreOp.load(a))
    l2.upgradeRace += line(a)
    runOps(CoreOp.amo(a, 1, BreezeAmoFunc.Add))
    l2.gets.last._2 mustBe "GetM"
    l2.gets.last._1 must be < l2.acks.last._1
    l2.acks.last._1 must be < l2.grants.last._1
    core.last.respCycle must be > l2.grants.last._1
    l2.acks.last._4 mustBe false
    runOps(CoreOp.load(a))
  }

  "atomic kill suppresses reservation and AMO or SC writes, including the RMW write edge" in withL1D() { e =>
    import e._
    val a = ram(0x1f00)
    runOps(CoreOp.lr(a).copy(s1Kill = true), CoreOp.sc(a, 1, success = false))
    runOps(CoreOp.lr(a).copy(s2KillAtResp = true), CoreOp.sc(a, 2, success = false))
    runOps(CoreOp.lr(a), CoreOp.sc(a, 3, success = true).copy(s2KillAtResp = true), CoreOp.load(a))
    runOps(CoreOp.amo(a, 4, BreezeAmoFunc.Add).copy(s1Kill = true), CoreOp.load(a))
    runOps(CoreOp.amo(a, 5, BreezeAmoFunc.Add).copy(s2KillAtResp = true), CoreOp.load(a))
    runOps(CoreOp.amo(a + stride, 6, BreezeAmoFunc.Swap).copy(s2KillAtResp = true), CoreOp.load(a + stride))
    l2.minLatency = 30; l2.maxLatency = 30
    val b = a + 2 * stride; val c = a + 3 * stride
    runOps(CoreOp.lr(b).copy(aq = true, s2KillAfter = Some(8)), CoreOp.sc(b, 7, success = false), CoreOp.load(b))
    runOps(CoreOp.amo(c, 8, BreezeAmoFunc.Add).copy(s2KillAfter = Some(8)), CoreOp.load(c))
  }

  "aq blocks younger requests and rl or AMO admission waits for older misses to drain" in withL1D() { e =>
    import e._
    val a = ram(0x2000); val b = ram(0x2040); val c = ram(0x2080)
    l2.minLatency = 25; l2.maxLatency = 25
    runOps(CoreOp.load(a, rd = 2), CoreOp.lr(b).copy(aq = true, rl = true), CoreOp.load(c, rd = 3))
    val t = core.history.toSeq
    t(1).fired must be > t(0).lateCycle
    t(2).fired must be > t(1).respCycle
    val n = core.history.size
    runOps(CoreOp.store(a + stride, 1), CoreOp.amo(b, 1, BreezeAmoFunc.Add), CoreOp.load(b))
    val u = txns(n)
    u(1).fired must be > l2.grants.find(_._3 == line(a + stride)).get._1
    u(2).fired must be > u(1).respCycle
    // aq alone can enter behind an older hit; that older response must not
    // release the acquire gate while the LR still owns it in S1/S2.
    val k = core.history.size
    runOps(CoreOp.load(b), CoreOp.lr(b).copy(aq = true), CoreOp.load(b + 8))
    val v = txns(k)
    v(1).fired mustBe v(0).fired + 1
    v(2).fired must be > v(1).respCycle
    val m = core.history.size
    runOps(CoreOp.lr(b), CoreOp.lr(b).copy(aq = true), CoreOp.load(b + 8))
    val w = txns(m)
    w(1).fired mustBe w(0).fired + 1
    w(2).fired must be > w(1).respCycle
  }

  private def randomAtomics(e: Env): Unit = {
    import e._
    val funcs = Seq(BreezeAmoFunc.Swap, BreezeAmoFunc.Add, BreezeAmoFunc.Xor, BreezeAmoFunc.Or,
      BreezeAmoFunc.And, BreezeAmoFunc.Min, BreezeAmoFunc.Max, BreezeAmoFunc.MinU, BreezeAmoFunc.MaxU)
    val lines = (0 until g.l1dWays + 3).map(k => ram(0x2400 + k * stride))
    l2.sharedLines ++= lines.take(2).map(line)
    l2.reqReadyProb = 0.6; l2.rspUpReadyProb = 0.6; l2.minLatency = 1; l2.maxLatency = 12
    l2.randomProbeProb = 0.04
    core.issueProb = 0.8; core.lateReadyProb = 0.5
    tlb.missProb = 0.03; tlb.busyProb = 0.05
    for (_ <- 0 until 300) {
      val size = 2 + rng.nextInt(2)
      val a = lines(rng.nextInt(lines.size)) + (rng.nextInt(LineBytes >> size) << size)
      core.enqueue(rng.nextInt(4) match {
        case 0 => CoreOp.load(a, size, signed = true)
        case 1 => CoreOp.store(a, BigInt(64, rng), size)
        case _ => CoreOp.amo(a, BigInt(64, rng), funcs(rng.nextInt(funcs.size)), size)
          .copy(aq = rng.nextBoolean(), rl = rng.nextBoolean(), rd = rng.nextInt(32))
      })
    }
    bench.runUntil(core.idle)
    tlb.missProb = 0; tlb.busyProb = 0; l2.randomProbeProb = 0
    finish()
  }

  for ((g, seed) <- Seq((BreezeMemGeometry.singleCore, 73), (BreezeMemGeometry.l1dTwoWay, 74),
    (BreezeMemGeometry.stress, 75)))
    s"atomic random traffic with probes and backpressure matches golden memory with seed $seed" in
      withL1D(g, seed) { e => randomAtomics(e) }

  /** Random loads/stores over three sets, each with more tags than ways, with
    * random backpressure on every link, random probes, shared grants (so
    * stores upgrade) and TLB misses.
    */
  private def randomTraffic(e: Env, ops: Int): Unit = {
    import e._
    val lines = for (s <- 0 until 3; t <- 0 until g.l1dWays + 2) yield ram(0x1000 + s * LineBytes + t * stride)
    l2.sharedLines ++= lines.indices.filter(_ % 3 == 0).map(i => line(lines(i)))
    l2.reqReadyProb = 0.6; l2.rspUpReadyProb = 0.7; l2.minLatency = 1; l2.maxLatency = 8
    l2.randomProbeProb = 0.02
    core.issueProb = 0.7; core.lateReadyProb = 0.5
    tlb.missProb = 0.03; tlb.busyProb = 0.05
    for (_ <- 0 until ops) {
      val size = rng.nextInt(4)
      val a = lines(rng.nextInt(lines.size)) + (rng.nextInt(LineBytes >> size) << size)
      core.enqueue(
        if (rng.nextInt(10) < 6) CoreOp.load(a, size, rng.nextBoolean(), rd = 1 + rng.nextInt(31))
        else CoreOp.store(a, BigInt(64, rng), size))
    }
    bench.runUntil(core.idle)
    tlb.missProb = 0; tlb.busyProb = 0
    finish()
    core.history.count(_.kind == "Mshr") must be > 0
    l2.acks must not be empty
  }

  for (seed <- Seq(11, 12, 13))
    s"random traffic with backpressure, probes and TLB misses matches the golden memory with seed $seed" in
      withL1D(seed = seed) { e => randomTraffic(e, 1500) }

  "non-default geometry smoke: random traffic on a two-way L1D" in
    withL1D(BreezeMemGeometry.l1dTwoWay, seed = 21) { e => randomTraffic(e, 800) }

  "non-default geometry smoke: random traffic on the two-set direct-mapped stress L1D" in
    withL1D(BreezeMemGeometry.stress, seed = 22) { e => randomTraffic(e, 800) }
  "SOC3d-combined tag banks preserve all sets ways and dirty victims across consecutive bank switches" in
    withL1D(seed = 0x3d04) { e =>
      import e._
      // Each set gets ways+1 tags, exercising all ways plus replacement.
      // Interleave distant banks; byte stores must preserve neighboring data.
      val sets = (0 until g.l1Sets).sortBy(s => (s % 16, s / 16))
      for (generation <- 0 to g.l1dWays; set <- sets) {
        val a = ram(set * LineBytes + generation * stride)
        val v = BigInt(generation * g.l1Sets + set + 1)
        runOps(CoreOp.load(a), CoreOp.store(a + 1,v & 255,0), CoreOp.load(a))
      }
      for (set <- sets.reverse) runOps(CoreOp.load(ram(set * LineBytes)))
      l2.gets.size must be > g.l1Sets * g.l1dWays
    }

  "SOC3d local PLRU preserves the victim when a hit is killed in each tag bank" in
    withL1D(seed = 0x3d05) { e =>
      import e._
      for (set <- 0 until g.l1Sets by 16) {
        val a = ram(set * LineBytes)
        for (way <- 0 until 4) runOps(CoreOp.load(a + way * stride))
        // Filling/touching ways 0,1,2,3 makes way 0 the tree victim.
        // A killed hit on way 0 must not change that replacement decision.
        runOps(CoreOp.load(a).copy(s2KillAtResp = true))
        runOps(CoreOp.load(a + 4 * stride))
        val before = l2.gets.size
        for (way <- 1 until 4) runOps(CoreOp.load(a + way * stride))
        l2.gets.size mustBe before
        runOps(CoreOp.load(a))
        l2.gets.size mustBe before + 1
      }
    }

}
