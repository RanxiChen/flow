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
  * LR/SC, AMO and aq/rl are not implemented yet and are not exercised.
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
}
