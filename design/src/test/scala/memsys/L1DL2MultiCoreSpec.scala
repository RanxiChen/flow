package flow.memsys

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.coherence._
import flow.config.BreezeMemGeometry
import flow.interface._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.collection.mutable
import scala.util.Random

import MemTestKit._

/** nCores real L1Ds on the real L2 (l1d-rtl-spec 13.3). Every case runs the
  * link-level coherence monitor (SWMR, grant/probe/Put legality) and ends by
  * reading every used line back through L2 with DMA: Owned/Free words must
  * be exact, Racy words must hold some core's last store, Counter words must
  * close their update chain (MultiCoreOracle).
  */
class L1DL2MultiCoreSpec extends AnyFreeSpec with Matchers with ChiselSim {
  import WordClass._

  private val two = BreezeMemGeometry(nCores = 2)
  private val four = BreezeMemGeometry(nCores = 4)

  class Env(val d: L1DL2MultiHarness, val g: BreezeMemGeometry, seed: Int) {
    val n: Int = g.nCores
    val rng = new Random(seed)
    val arch = new GoldenMem(seed)
    val dram = new GoldenMem(seed)
    val oracle = new MultiCoreOracle(arch)
    val devices = (0 until n).map(c => new AxiLiteDevice(d.io.mmio(c), rng, seed + 7 + c))
    val cores = (0 until n).map(c =>
      new CoreDriver(d.io.core(c), arch, Some(devices(c)), rng, s"core$c", c, Some(oracle)))
    val feeders = (0 until n).map(c => new ProgramFeeder(cores(c), s"program$c"))
    val ptws = (0 until n).map(c => new PtwDriver(d.io.ptw(c), arch))
    val tlbs = (0 until n).map(c => new TlbKnobs(d.io.tlb(c), rng))
    val mem = new AxiMemory(d.io.mem, dram, rng)
    val l1is = (0 until n).map(c => new ReadClientAgent(d.io.l1i(c), s"l1i$c", arch, rng, 2))
    val dma = new ReadClientAgent(d.io.dma, "dma", arch, rng, 1)
    val monitor = new CoherenceMonitor(d.io.obs)
    val bench = new Bench(d.clock, feeders ++ tlbs ++ cores ++ ptws ++ Seq(mem) ++ devices ++ l1is ++ Seq(dma, monitor))
    /** Next tag in the same L1D set and the same L2 set. */
    val stride: BigInt = (BigInt(g.l1Sets) max BigInt(CoherenceParams(g).l2Sets)) * LineBytes
    /** Next tag in the same L1D set only. */
    val l1Stride: BigInt = BigInt(g.l1Sets) * LineBytes
    def ram(off: BigInt): BigInt = MainRam + off

    def run(c: Int, ops: CoreOp*): Unit = { cores(c).enqueue(ops: _*); bench.quiesce() }
    def together(ops: (Int, Seq[CoreOp])*): Unit = { ops.foreach { case (c, o) => cores(c).enqueue(o: _*) }; bench.quiesce() }
    def ld(c: Int, a: BigInt, size: Int = 3): BigInt = { run(c, CoreOp.load(a, size)); cores(c).last.value }
    def loadsOf(c: Int, a: BigInt): Seq[CoreTxn] =
      cores(c).history.filter(t => t.op.addr == a && enumIs(t.op.op, L1DOp.Load)).toSeq

    def finish(): Unit = {
      bench.quiesce()
      val ls = (oracle.lines ++ arch.touched).toSeq.sorted
      val before = dma.results.size
      ls.foreach(l => dma.pending += ClientReq.read(l, check = false))
      bench.quiesce()
      val back = dma.results.drop(before).map(r => r._2.line -> r._3).toMap
      for (l <- ls; k <- 0 until 4) {
        val w = (l << 5) + 8 * k
        oracle.classOf(w) match {
          case Racy | Counter =>
          case _ => withClue(s"word ${hex(w)}: ") { (back(l) >> (64 * k)) & mask(64) mustBe arch.read(w, 8) }
        }
      }
      oracle.finish(back)
      monitor.idle mustBe true
    }
  }

  private def init(d: L1DL2MultiHarness): Unit = {
    for (c <- d.io.core) {
      c.req.valid.poke(false.B); c.req.bits.op.poke(L1DOp.Load); c.req.bits.vaddr.poke(0.U)
      c.req.bits.size.poke(3.U); c.req.bits.signed.poke(false.B); c.req.bits.amoFunc.poke(BreezeAmoFunc.Swap)
      c.req.bits.aq.poke(false.B); c.req.bits.rl.poke(false.B); c.req.bits.wdata.poke(0.U)
      c.req.bits.rd.isFp.poke(false.B); c.req.bits.rd.idx.poke(1.U); c.req.bits.isFlw.poke(false.B)
      c.s1Kill.poke(false.B); c.s2Kill.poke(false.B); c.trapClearRsv.poke(false.B); c.late.ready.poke(true.B)
      c.csr.satp.poke(0.U); c.csr.privilege.poke(3.U); c.csr.mprv.poke(false.B); c.csr.mpp.poke(0.U)
      c.csr.sum.poke(false.B); c.csr.mxr.poke(false.B); c.csr.adue.poke(false.B)
      for (i <- c.csr.pmpcfg.indices) { c.csr.pmpcfg(i).poke(0.U); c.csr.pmpaddr(i).poke(0.U) }
      c.csr.pmpcfg(0).poke(0x1f.U); c.csr.pmpaddr(0).poke(0x1fffffff.U)
    }
    for (p <- d.io.ptw) { p.req.valid.poke(false.B); p.req.bits.paddr.poke(0.U) }
    for (m <- d.io.mmio) {
      m.ar.ready.poke(false.B); m.aw.ready.poke(false.B); m.w.ready.poke(false.B)
      m.r.valid.poke(false.B); m.r.bits.data.poke(0.U); m.r.bits.resp.poke(0.U)
      m.b.valid.poke(false.B); m.b.bits.poke(0.U)
    }
    val x = d.io.mem
    x.ar.ready.poke(false.B); x.aw.ready.poke(false.B); x.w.ready.poke(false.B)
    x.r.valid.poke(false.B); x.r.bits.id.poke(0.U); x.r.bits.data.poke(0.U); x.r.bits.resp.poke(0.U)
    x.r.bits.last.poke(false.B); x.b.valid.poke(false.B); x.b.bits.id.poke(0.U); x.b.bits.resp.poke(0.U)
    for (r <- d.io.l1i :+ d.io.dma) {
      r.req.valid.poke(false.B); r.req.bits.op.poke(ReqOp.Read); r.req.bits.addr.poke(0.U)
      r.req.bits.id.poke(0.U); r.req.bits.mask.poke(0.U); r.req.bits.data.poke(0.U)
      r.rspDown.ready.poke(true.B)
    }
    for (t <- d.io.tlb) { t.tlbReady.poke(true.B); t.tlbMiss.poke(false.B); t.tlbPageFault.poke(false.B) }
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }

  private def withMc(g: BreezeMemGeometry = two, seed: Int = 1)(body: Env => Unit): Unit =
    simulate(new L1DL2MultiHarness(g)) { d =>
      init(d)
      val env = new Env(d, g, seed)
      body(env)
      env.finish()
    }

  // ===========================================================================
  // Directed
  // ===========================================================================

  "two cores reading one line both end shared after one Down, and memory is read once" in withMc() { e =>
    import e._
    val a = ram(0x100); val l = lineOf(a)
    ld(0, a) mustBe arch.read(a, 8)
    ld(1, a + 8) mustBe arch.read(a + 8, 8)
    monitor.grantsTo(0, l) mustBe Seq("DataE")
    monitor.probesTo(0, l) mustBe Seq("Down")
    monitor.grantsTo(1, l) mustBe Seq("DataS")
    monitor.state(0, l) mustBe 'S'; monitor.state(1, l) mustBe 'S'
    mem.readsOf(l) mustBe 1
  }

  "a store by one sharer invalidates the other, which then misses and reads the new value" in withMc() { e =>
    import e._
    val a = ram(0x200); val l = lineOf(a)
    ld(0, a); ld(1, a)
    run(0, CoreOp.store(a, BigInt("1122334455667788", 16)))
    monitor.probesTo(1, l) must contain("Inv")
    monitor.state(1, l) mustBe 'I'
    monitor.state(0, l) mustBe 'X'
    Set("AckE", "DataE") must contain(monitor.grantsTo(0, l).last)
    ld(1, a) mustBe BigInt("1122334455667788", 16)
    loadsOf(1, a).last.kind mustBe "Mshr"
  }

  "a load of a line another core holds M pulls the dirty data by Down and L2 keeps the merged line" in withMc() { e =>
    import e._
    val a = ram(0x300); val l = lineOf(a)
    run(0, CoreOp.store(a + 8, BigInt("cafef00d", 16), 2), CoreOp.store(a + 24, 0x5a, 0))
    monitor.state(0, l) mustBe 'X'
    ld(1, a + 8) mustBe arch.read(a + 8, 8)
    monitor.probesTo(0, l) mustBe Seq("Down")
    monitor.answers.filter(x => x._2 == 0 && x._4 == l).map(x => (x._3, x._5)) mustBe Seq(("DownAck", true))
    monitor.state(0, l) mustBe 'S'; monitor.state(1, l) mustBe 'S'
    // Core 0 still hits its now-shared copy; DMA in finish reads L2's merged data.
    val gets = monitor.grantsTo(0, l).size
    ld(0, a + 24, 0) mustBe arch.read(a + 24, 1)
    monitor.grantsTo(0, l).size mustBe gets
  }

  "two cores requesting ownership of one line in the same cycle are serialized, from I and from S" in withMc(seed = 4) { e =>
    import e._
    for (r <- 0 until 24) {
      val l = lineOf(ram(0x1000 + r * LineBytes))
      oracle.classifyLine(l, Seq(Owned(0), Owned(1), Free, Free))
      if (r % 2 == 1) together(0 -> Seq(CoreOp.load(l << 5)), 1 -> Seq(CoreOp.load((l << 5) + 8)))
      val pad = r % 4
      together(
        0 -> (Seq.fill(pad)(CoreOp.fence) :+ CoreOp.store(l << 5, BigInt(r) * 0x0101010101L)),
        1 -> (Seq.fill(3 - pad)(CoreOp.fence) :+ CoreOp.store((l << 5) + 8, BigInt(r) * 0x1010101010L)))
      for (c <- 0 until 2)
        monitor.grantsTo(c, l).exists(Set("DataE", "AckE")) mustBe true
      ld(1, l << 5) mustBe arch.read(l << 5, 8)
      ld(0, (l << 5) + 8) mustBe arch.read((l << 5) + 8, 8)
    }
  }

  "a dirty writeback Put racing another core's Get of the same line keeps the stored data" in withMc(seed = 5) { e =>
    import e._
    var putsSeen = 0
    for (r <- 0 until 16) {
      val x = ram(0x4000 + r * LineBytes); val l = lineOf(x)
      val v = BigInt(r + 1) * BigInt("0102030405060708", 16) & mask(64)
      run(0, CoreOp.store(x, v))
      // Same L1D set, different tags: the last store evicts x (tree-PLRU, all ways touched after x).
      val evict = (1 to g.l1dWays).map(k => CoreOp.store(x + k * l1Stride, BigInt(k)))
      together(0 -> evict, 1 -> (Seq.fill(r % 8)(CoreOp.fence) :+ CoreOp.load(x)))
      loadsOf(1, x).last.value mustBe v
      if (monitor.answers.exists(a => a._2 == 0 && a._3 == "Put" && a._4 == l)) putsSeen += 1
    }
    putsSeen must be > 0
  }

  "a DMA write invalidates both sharers and both then read the DMA bytes from L2" in withMc() { e =>
    import e._
    val a = ram(0x500); val l = lineOf(a)
    ld(0, a + 16); ld(1, a + 16)
    dma.pending += ClientReq.write(l, BigInt(0xff) << 16, BigInt("abcdef0123456789", 16) << 128)
    bench.quiesce()
    monitor.state(0, l) mustBe 'I'; monitor.state(1, l) mustBe 'I'
    monitor.probesTo(0, l).last mustBe "Inv"; monitor.probesTo(1, l).last mustBe "Inv"
    ld(0, a + 16) mustBe BigInt("abcdef0123456789", 16)
    ld(1, a + 16) mustBe BigInt("abcdef0123456789", 16)
    ld(1, a) mustBe arch.read(a, 8)
  }

  "AMOADD from both cores on one word loses no update" in withMc(seed = 7) { e =>
    import e._
    val w = ram(0x600)
    oracle.classify(w, Counter)
    val each = 200
    together((0 until 2).map(c => c -> Seq.tabulate(each)(i =>
      CoreOp.amo(w, 1, BreezeAmoFunc.Add).copy(aq = i % 3 == 0, rl = i % 5 == 0))): _*)
    oracle.counterTotal(w) mustBe 2 * each
  }

  // ===========================================================================
  // LR/SC forward progress
  // ===========================================================================

  private def lrscLoop(e: Env, word: BigInt, harts: Seq[Int], k: Int): Unit = {
    import e._
    for (c <- harts) {
      feeders(c).next = () => (CoreOp.lr(word), Some(BigInt(1)))
      feeders(c).remaining = Int.MaxValue
      feeders(c).stopAfterSuccesses = Some(k)
    }
    bench.runUntil(feeders.forall(_.idle), limit = 2000000)
    bench.quiesce()
    for (c <- harts) withClue(s"core $c: ") { feeders(c).successes mustBe k }
  }

  "LR/SC increment loops on one counter from both cores all succeed eventually and lose no update" in
    withMc(seed = 8) { e =>
      import e._
      val w = ram(0x700)
      oracle.classify(w, Counter)
      lrscLoop(e, w, Seq(0, 1), 60)
      oracle.counterTotal(w) mustBe 120
    }

  "LR/SC loops make progress on four cores" in withMc(four, seed = 9) { e =>
    import e._
    val w = ram(0x700)
    oracle.classify(w, Counter)
    lrscLoop(e, w, 0 until 4, 30)
    oracle.counterTotal(w) mustBe 120
  }

  "an LR/SC loop makes progress against a core hammering the same word with AMOADD" in withMc(seed = 10) { e =>
    import e._
    val w = ram(0x800)
    oracle.classify(w, Counter)
    cores(1).enqueue(Seq.fill(400)(CoreOp.amo(w, 3, BreezeAmoFunc.Add)): _*)
    lrscLoop(e, w, Seq(0), 40)
    bench.runUntil(cores(1).idle, limit = 2000000)
    oracle.counterTotal(w) mustBe 40 + 3 * 400
  }

  // ===========================================================================
  // Litmus
  // ===========================================================================

  /** Private padding: loads of the core's own private line delay the test body. */
  private def pad(e: Env, c: Int, k: Int): Seq[CoreOp] =
    Seq.fill(k)(CoreOp.load(e.ram(0x80000 + c * 0x1000)))

  /** Optionally make the observer share x/y first so the writer's stores need Inv. */
  private def warm(e: Env, x: BigInt, y: BigInt): Unit = {
    import e._
    val m = rng.nextInt(4)
    if (m != 0) together(1 -> Seq(CoreOp.load(if (m == 2) x else y), CoreOp.load(if (m == 1) y else x)),
      0 -> Seq(CoreOp.load(if (m == 3) x else y)))
  }

  private def litmus(e: Env, rounds: Int, body: (BigInt, BigInt, BigInt, BigInt, Int) => (BigInt, BigInt)): mutable.Map[String, Int] = {
    import e._
    val seen = mutable.Map.empty[String, Int].withDefaultValue(0)
    for (r <- 0 until rounds) {
      val x = ram(0x10000 + r * 2 * LineBytes)
      val y = x + LineBytes + (if (r % 3 == 0) stride else 0)
      oracle.classify(x, Racy); oracle.classify(y, Racy)
      val nx = (BigInt(1) << 60) | BigInt(r * 2 + 1)
      val ny = (BigInt(2) << 60) | BigInt(r * 2 + 2)
      warm(e, x, y)
      val (r1, r2) = body(x, y, nx, ny, r)
      seen(s"${if (r1 == ny || r1 == nx) 1 else 0}${if (r2 == nx || r2 == ny) 1 else 0}") += 1
    }
    seen
  }

  "MP with fences never observes the flag without the data" in withMc(seed = 11) { e =>
    import e._
    val seen = litmus(e, 64, (x, y, nx, ny, r) => {
      together(
        0 -> (pad(e, 0, rng.nextInt(6)) ++ Seq(CoreOp.store(x, nx), CoreOp.fence, CoreOp.store(y, ny))),
        1 -> (pad(e, 1, rng.nextInt(6)) ++ Seq(CoreOp.load(y), CoreOp.fence, CoreOp.load(x))))
      val r1 = loadsOf(1, y).last.value; val r2 = loadsOf(1, x).last.value
      withClue(s"round $r: ") { (r1 == ny && r2 == oracle.initial(x)) mustBe false }
      (r1, r2)
    })
    info(s"MP+fences outcomes (flag,data): $seen")
  }

  "MP without fences is run for coverage; RVWMO allows every outcome" in withMc(seed = 12) { e =>
    import e._
    val seen = litmus(e, 64, (x, y, nx, ny, _) => {
      together(
        0 -> (pad(e, 0, rng.nextInt(6)) ++ Seq(CoreOp.store(x, nx), CoreOp.store(y, ny))),
        1 -> (pad(e, 1, rng.nextInt(6)) ++ Seq(CoreOp.load(y), CoreOp.load(x))))
      (loadsOf(1, y).last.value, loadsOf(1, x).last.value)
    })
    info(s"MP outcomes (flag,data): $seen")
  }

  "SB with fences never observes both old values" in withMc(seed = 13) { e =>
    import e._
    val seen = litmus(e, 64, (x, y, nx, ny, r) => {
      together(
        0 -> (pad(e, 0, rng.nextInt(6)) ++ Seq(CoreOp.store(x, nx), CoreOp.fence, CoreOp.load(y))),
        1 -> (pad(e, 1, rng.nextInt(6)) ++ Seq(CoreOp.store(y, ny), CoreOp.fence, CoreOp.load(x))))
      val r0 = loadsOf(0, y).last.value; val r1 = loadsOf(1, x).last.value
      withClue(s"round $r: ") { (r0 == oracle.initial(y) && r1 == oracle.initial(x)) mustBe false }
      (r0, r1)
    })
    info(s"SB+fences outcomes: $seen")
  }

  // ===========================================================================
  // Random
  // ===========================================================================

  /** Contended random traffic. Shared lines (two L1D sets, conflicting tags)
    * each hold a Counter word, a Racy word and two words owned by different
    * cores (false sharing); each core also has private lines that overflow
    * one L1D set. L1I and DMA read shared lines unchecked to force Down.
    */
  private def randomTraffic(e: Env, opsPerCore: Int, sharedLines: Int = 4): Unit = {
    import e._
    val shared = (0 until sharedLines).map(i => lineOf(ram(0x2000 + (i % 2) * LineBytes + (i / 2) * stride)))
    for ((l, i) <- shared.zipWithIndex)
      oracle.classifyLine(l, Seq(Counter, Racy, Owned(i % n), Owned((i + 1) % n)))
    val priv = (0 until n).map(c => (0 to g.l1dWays).map(t =>
      lineOf(ram(BigInt(0x100000) * (c + 1) + (c % 2) * LineBytes + t * stride))))
    for (c <- 0 until n; l <- priv(c)) oracle.classifyLine(l, Seq.fill(4)(Owned(c)))
    val words = (shared ++ priv.flatten).flatMap(l => (0 until 4).map(k => (l << 5) + 8 * k))
    val own = (0 until n).map(c => words.filter(w => oracle.classOf(w) == Owned(c)))
    val counters = shared.map(_ << 5)
    val racy = shared.map(l => (l << 5) + 8)
    val funcs = Seq(BreezeAmoFunc.Swap, BreezeAmoFunc.Add, BreezeAmoFunc.Xor, BreezeAmoFunc.Or,
      BreezeAmoFunc.And, BreezeAmoFunc.Min, BreezeAmoFunc.Max, BreezeAmoFunc.MinU, BreezeAmoFunc.MaxU)
    var uniq = 0
    def pick[T](s: Seq[T]): T = s(rng.nextInt(s.size))
    def sub(w: BigInt): (BigInt, Int) = { val size = rng.nextInt(4); (w + (rng.nextInt(8 >> size) << size), size) }
    def flags(o: CoreOp): CoreOp = o.copy(aq = rng.nextInt(4) == 0, rl = rng.nextInt(4) == 0)
    def racyValue(c: Int, w: BigInt): BigInt = {
      var v = BigInt(0)
      do { uniq += 1; v = (BigInt(c + 1) << 56) | (BigInt(uniq) << 8) | 0x5a } while (v == oracle.initial(w))
      v
    }
    def gen(c: Int): (CoreOp, Option[BigInt]) = {
      val r = rng.nextInt(100)
      if (r < 35) {
        val w = rng.nextInt(20) match {
          case x if x < 10 => pick(shared.flatMap(l => (0 until 4).map(k => (l << 5) + 8 * k)))
          case x if x < 17 => pick(own(c))
          case _ => pick(words)
        }
        oracle.classOf(w) match {
          case Racy | Counter => (CoreOp.load(w, rd = 1 + rng.nextInt(31)), None)
          case _ => val (a, size) = sub(w); (CoreOp.load(a, size, rng.nextBoolean(), rd = 1 + rng.nextInt(31)), None)
        }
      } else if (r < 55) {
        if (rng.nextBoolean()) { val (a, size) = sub(pick(own(c))); (CoreOp.store(a, BigInt(64, rng), size), None) }
        else { val w = pick(racy); (CoreOp.store(w, racyValue(c, w)), None) }
      } else if (r < 68) (flags(CoreOp.amo(pick(counters), 1 + rng.nextInt(100), BreezeAmoFunc.Add)), None)
      else if (r < 75) {
        val size = 2 + rng.nextInt(2)
        val w = pick(own(c)) + (if (size == 2) 4 * rng.nextInt(2) else 0)
        (flags(CoreOp.amo(w, BigInt(64, rng), pick(funcs), size)), None)
      } else if (r < 83) (flags(CoreOp.lr(pick(counters))), Some(BigInt(1 + rng.nextInt(100))))
      else if (r < 86) (CoreOp.fence, None)
      else (CoreOp.load(pick(priv(c)) << 5), None)
    }
    for (c <- 0 until n) {
      cores(c).issueProb = 0.7; cores(c).lateReadyProb = 0.6
      tlbs(c).missProb = 0.02; tlbs(c).busyProb = 0.03
      feeders(c).next = () => gen(c); feeders(c).remaining = opsPerCore
      l1is(c).issueProb = 0.05
      for (_ <- 0 until opsPerCore / 16) l1is(c).pending += ClientReq.read(pick(shared), check = false)
    }
    mem.arReadyProb = 0.7; mem.awReadyProb = 0.7; mem.wReadyProb = 0.8; mem.rValidProb = 0.8
    mem.minLatency = 1; mem.maxLatency = 12
    dma.issueProb = 0.05
    for (_ <- 0 until opsPerCore / 16) dma.pending += ClientReq.read(pick(shared), check = false)
    bench.runUntil(feeders.forall(_.idle) && cores.forall(_.idle), limit = 4000000)
    l1is.foreach(_.pending.clear()); dma.pending.clear()
    tlbs.foreach { t => t.missProb = 0; t.busyProb = 0 }
    finish()
    info(s"oracle counts ${oracle.counts.toSeq.sorted.mkString(", ")}; probes " +
      s"Inv ${monitor.probes.count(_._3 == "Inv")} Down ${monitor.probes.count(_._3 == "Down")}; " +
      s"Puts ${monitor.answers.count(_._3 == "Put")}")
    oracle.counts("amo") must be > 0
    oracle.counts("scOk") must be > 0
    monitor.probes.exists(_._3 == "Inv") mustBe true
    monitor.probes.exists(_._3 == "Down") mustBe true
    mem.writes must not be empty
  }

  for ((name, g, ops) <- Seq(("two cores", two, 1000), ("four cores", four, 1000),
      ("two cores on the stress geometry", BreezeMemGeometry.stress.copy(nCores = 2), 2000),
      ("four cores on the stress geometry", BreezeMemGeometry.stress.copy(nCores = 4), 2000));
      seed <- Seq(81, 82))
    s"random contended traffic, $name, seed $seed: monitor clean, oracle and final memory consistent" in
      withMc(g, seed = seed + 10 * g.nCores + (if (g.l1Sets == 2) 100 else 0)) { e => randomTraffic(e, ops) }
}
