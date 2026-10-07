package flow.memsys

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.coherence._
import flow.config.BreezeMemGeometry
import flow.interface._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

import MemTestKit._

/** Cycle-scheduled test actions (trap pulses, PTW reads). It is the first
  * bench agent, so an action at cycle t is visible to every other agent's
  * pokes in cycle t.
  */
class FaultScript extends CycleAgent {
  val name = "script"
  private val at = mutable.Map.empty[Long, ArrayBuffer[() => Unit]]
  var perCycle: Long => Unit = _ => ()
  def schedule(cycle: Long)(f: => Unit): Unit = at.getOrElseUpdate(cycle, ArrayBuffer.empty) += (() => f)
  protected def drive(): Unit = {
    at.remove(now).foreach { fs => fs.foreach(_()); progress() }
    perCycle(now)
  }
  def sample(): Unit = ()
  def idle: Boolean = at.isEmpty
  override def describe: String = s"$name: ${at.keys.toSeq.sorted.mkString(", ")}"
}

/** Multi-core kill, trap, page-fault, refill-error and PTW interactions on
  * nCores real L1Ds and the real L2. Same monitor/oracle/final-read-back
  * discipline as L1DL2MultiCoreSpec; the monitor additionally accepts error
  * grants, which must only ever target lines the test poisoned.
  */
class L1DL2MultiCoreFaultSpec extends AnyFreeSpec with Matchers with ChiselSim {
  import WordClass._

  private val two = BreezeMemGeometry(nCores = 2)
  private val four = BreezeMemGeometry(nCores = 4)
  private val stress2 = BreezeMemGeometry.stress.copy(nCores = 2)
  /** Every dTLB lookup in this region page-faults (IdentityTlb faultRegion). */
  private val FaultBase = BigInt("8f000000", 16)
  private val FaultBytes = BigInt(0x10000)
  private def inFault(a: BigInt): Boolean = a >= FaultBase && a < FaultBase + FaultBytes

  class Env(val d: L1DL2MultiHarness, val g: BreezeMemGeometry, seed: Int) {
    val n: Int = g.nCores
    val rng = new Random(seed)
    val arch = new GoldenMem(seed)
    val dram = new GoldenMem(seed)
    val oracle = new MultiCoreOracle(arch)
    val script = new FaultScript
    val devices = (0 until n).map(c => new AxiLiteDevice(d.io.mmio(c), rng, seed + 7 + c))
    val cores = (0 until n).map(c =>
      new CoreDriver(d.io.core(c), arch, Some(devices(c)), rng, s"core$c", c, Some(oracle)))
    val feeders = (0 until n).map(c => new ProgramFeeder(cores(c), s"program$c"))
    val ptws = (0 until n).map(c => new PtwDriver(d.io.ptw(c), arch))
    val tlbs = (0 until n).map(c => new TlbKnobs(d.io.tlb(c), rng))
    val mem = new AxiMemory(d.io.mem, dram, rng)
    val l1is = (0 until n).map(c => new ReadClientAgent(d.io.l1i(c), s"l1i$c", arch, rng, 2))
    val dma = new ReadClientAgent(d.io.dma, "dma", arch, rng, 1)
    val monitor = new CoherenceMonitor(d.io.obs, allowErrors = true)
    val bench = new Bench(d.clock, Seq(script) ++ feeders ++ tlbs ++ cores ++ ptws ++ Seq(mem) ++ devices ++
      l1is ++ Seq(dma, monitor))
    /** Next tag in the same L1D set and the same L2 set. */
    val stride: BigInt = (BigInt(g.l1Sets) max BigInt(CoherenceParams(g).l2Sets)) * LineBytes
    /** Lines ever given AXI read errors. */
    val poisoned = mutable.Set.empty[BigInt]
    def ram(off: BigInt): BigInt = MainRam + off
    def fault(off: BigInt): BigInt = FaultBase + off
    def poison(l: BigInt): Unit = { mem.errorLines += l; poisoned += l }
    def exc(o: CoreOp, cause: Int): CoreOp = o.copy(expectExc = Some(cause))

    // PTW reads are not in a core's program order: classified words are
    // judged as an extra reader (id 100 + core) — a value the word really
    // held, never older than one this PTW already saw (no torn values).
    // Free words are only used sequentially and must be exact.
    for (c <- 0 until n) ptws(c).judge = Some((a: BigInt, v: BigInt) => oracle.classOf(a) match {
      case Free =>
        if (v != arch.read(a & ~BigInt(7), 8))
          throw new AssertionError(s"PTW$c ${hex(a)}: ${hex(v)}, expected ${hex(arch.read(a & ~BigInt(7), 8))}")
      case _ => oracle.load(100 + c, CoreOp.load(a & ~BigInt(7)), v, bench.cycle)
    })

    def runOps(c: Int, ops: CoreOp*): Unit = { cores(c).enqueue(ops: _*); bench.quiesce() }
    def together(ops: (Int, Seq[CoreOp])*): Unit = { ops.foreach { case (c, o) => cores(c).enqueue(o: _*) }; bench.quiesce() }
    def ld(c: Int, a: BigInt, size: Int = 3): BigInt = { runOps(c, CoreOp.load(a, size)); cores(c).last.value }
    def txns(c: Int, a: BigInt, op: L1DOp.Type): Seq[CoreTxn] =
      cores(c).history.filter(t => t.op.addr == a && enumIs(t.op.op, op)).toSeq
    def reqsOf(c: Int, l: BigInt, op: String): Seq[Long] =
      monitor.reqs.filter(r => r._2 == c && r._4 == l && r._3 == op).map(_._1).toSeq
    def answersOf(c: Int, l: BigInt, op: String): Seq[Long] =
      monitor.answers.filter(a => a._2 == c && a._4 == l && a._3 == op).map(_._1).toSeq

    def finish(): Unit = {
      script.perCycle = _ => ()
      cores.foreach(_.trapClear = false)
      bench.quiesce()
      // Error lines are never installed (coherence-l2 5.2); with errors
      // cleared the read-back below sees plain memory for every line.
      mem.errorLines.clear()
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
      for (e <- monitor.errorGrants) withClue(s"error grant $e: ") { poisoned must contain(e._4) }
      monitor.reqs.filter(r => inFault(r._4 << 5)) mustBe empty
      mem.reads.filter(r => inFault(r._2 << 5)) mustBe empty
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

  private def withFault(g: BreezeMemGeometry = two, seed: Int = 1)(body: Env => Unit): Unit =
    simulate(new L1DL2MultiHarness(g, Some((FaultBase, FaultBytes)))) { d =>
      init(d)
      val env = new Env(d, g, seed)
      body(env)
      env.finish()
    }

  // ===========================================================================
  // 1. kill × coherence
  // ===========================================================================

  // A plain store answers Mshr when its MSHR is allocated (l1d-rtl-spec 5.2,
  // 6.2), so after its GetM is sent it is already committed and cannot be
  // killed. Killing a store before GetM is therefore the only store case;
  // "GetM sent, then killed" is covered by the LR/AMO cases below.

  "a store held in S2 behind a busy MSHR is killed, sends no GetM, and still answers another core's Inv" in
    withFault(seed = 21) { e =>
      import e._
      val a = ram(0x400); val b = ram(0x440); val lb = lineOf(b)
      oracle.classifyLine(lb, Seq(Owned(0), Owned(1), Free, Free))
      ld(0, b); ld(1, b)
      monitor.state(0, lb) mustBe 'S'
      mem.minLatency = 150; mem.maxLatency = 150
      // The load miss of a occupies the single MSHR; the upgrade of b waits in S2.
      cores(0).enqueue(CoreOp.load(a), CoreOp.store(b, 0x1111).copy(s2KillAfter = Some(90)))
      bench.steps(8)
      cores(1).enqueue(CoreOp.store(b + 8, 0x2222))
      bench.quiesce()
      mem.minLatency = 2; mem.maxLatency = 6
      val st = txns(0, b, L1DOp.Store).last
      st.killed mustBe true
      st.respCycle mustBe -1L
      reqsOf(0, lb, "GetM") mustBe empty
      // The held store did not block the probe (l1d 10.1: it is s2Hold'd).
      answersOf(0, lb, "InvAck").last must be < st.killedAt
      monitor.state(0, lb) mustBe 'I'
      ld(0, b + 8) mustBe 0x2222
      ld(0, b) mustBe oracle.initial(b)
    }

  "a store killed at its S2 response sends no GetM, from S and from I, while another core takes the line" in
    withFault(seed = 22) { e =>
      import e._
      for (v <- 0 until 8) {
        val b = ram(0x1000 + v * 2 * LineBytes); val lb = lineOf(b)
        oracle.classifyLine(lb, Seq(Owned(0), Owned(1), Free, Free))
        if (v % 2 == 0) { ld(0, b); ld(1, b) }
        together(
          0 -> (Seq.fill(v / 2)(CoreOp.fence) :+ CoreOp.store(b, 0x3300 + v).copy(s2KillAtResp = true)),
          1 -> Seq(CoreOp.store(b + 8, 0x4400 + v)))
        withClue(s"variant $v: ") {
          txns(0, b, L1DOp.Store).last.killed mustBe true
          reqsOf(0, lb, "GetM") mustBe empty
          ld(0, b) mustBe oracle.initial(b)
          ld(0, b + 8) mustBe 0x4400 + v
        }
      }
    }

  "an AMO killed while its GetM waits or at its RMW write edge never updates a counter another core hammers" in
    withFault(seed = 23) { e =>
      import e._
      val w = ram(0x600); val l = lineOf(w)
      oracle.classify(w, Counter)
      var inWait = 0
      var atEdge = 0
      for (r <- 0 until 48) {
        runOps(1, CoreOp.amo(w, 1, BreezeAmoFunc.Add))
        val k = CoreOp.amo(w, 100, BreezeAmoFunc.Add)
        val killed = if (r % 4 == 3) k.copy(s2KillAtResp = true) else k.copy(s2KillAfter = Some(3 + r / 2))
        together(0 -> Seq(killed, CoreOp.load(w)),
          1 -> (Seq.fill(r % 3)(CoreOp.amo(w, 1, BreezeAmoFunc.Add)) :+ CoreOp.load(w)))
        val t = cores(0).history.filter(x => enumIs(x.op.op, L1DOp.AMO)).last
        if (t.killed && t.kind == "Done") atEdge += 1
        if (t.killed && t.kind.isEmpty) {
          val sent = reqsOf(0, l, "GetM").filter(c => c > t.fired && c < t.killedAt)
          val granted = monitor.grants.filter(g => g._2 == 0 && g._4 == l && g._1 > t.killedAt)
          if (sent.nonEmpty && granted.nonEmpty) inWait += 1
        }
      }
      info(s"killed in GetM wait $inWait, killed at the write edge $atEdge")
      inWait must be > 0
      atEdge must be > 0
      val committed = cores.flatMap(_.history).filter(t => enumIs(t.op.op, L1DOp.AMO) && t.done && !t.killed)
      oracle.counterTotal(w) mustBe committed.map(_.op.data).sum
    }

  "a killed LR leaves no reservation: the next SC fails with or without another core's store" in
    withFault(seed = 24) { e =>
      import e._
      def kills(x: BigInt): Seq[CoreOp] = Seq(CoreOp.lr(x).copy(s1Kill = true), CoreOp.lr(x).copy(s2KillAtResp = true),
        CoreOp.lr(x).copy(s2KillAfter = Some(6)))
      for (v <- 0 until 6) {
        val x = ram(0x2000 + v * 2 * LineBytes); val l = lineOf(x)
        // Variants 2 and 5 miss: core 1 holds M, so the LR waits for GetM + Inv.
        runOps(1, CoreOp.store(x, 0x10 + v))
        if (v % 3 != 2) runOps(0, CoreOp.load(x))
        val grantsBefore = monitor.grantsTo(0, l).size
        runOps(0, kills(x)(v % 3))
        txns(0, x, L1DOp.LR).last.killed mustBe true
        if (v % 3 == 2) withClue("a GetM accepted before the kill still installs: ") {
          monitor.grantsTo(0, l).size must be > grantsBefore
        }
        if (v >= 3) runOps(1, CoreOp.store(x, 0x90 + v))
        runOps(0, CoreOp.sc(x, 0x55, success = false))
        ld(0, x) mustBe (if (v >= 3) 0x90 + v else 0x10 + v)
        // Control: the same core can still pair a live LR with its SC.
        runOps(0, CoreOp.lr(x), CoreOp.sc(x, 0x60 + v, success = true))
        ld(1, x) mustBe 0x60 + v
      }
    }

  "a trap clearing the reservation in the cycle another core's probe arrives ends the LR window at once" in
    withFault(seed = 25) { e =>
      import e._
      var exact = 0
      val rel = mutable.Map.empty[String, Int].withDefaultValue(0)
      for (dly <- 0 until 28) {
        val x = ram(0x3000 + dly * 2 * LineBytes); val l = lineOf(x)
        runOps(0, CoreOp.lr(x))
        val start = bench.cycle
        script.schedule(start + dly) { cores(0).trapClear = true }
        script.schedule(start + dly + 1) { cores(0).trapClear = false }
        cores(1).enqueue(CoreOp.store(x, 0x700 + dly))
        bench.quiesce()
        val trap = start + dly
        val snp = monitor.probes.filter(p => p._2 == 0 && p._4 == l).last._1
        val ack = answersOf(0, l, "InvAck").last
        rel(if (trap < snp) "before" else if (trap == snp) "same" else "after") += 1
        if (trap == snp) exact += 1
        // Without the trap the Inv would wait for the 80-cycle window.
        withClue(s"delay $dly, trap $trap, probe $snp, answer $ack: ") { ack must be <= (trap max snp) + 20 }
        runOps(0, CoreOp.sc(x, 1, success = false))
        ld(0, x) mustBe 0x700 + dly
      }
      info(s"trap relative to probe arrival: $rel")
      withClue(s"no trap landed in the probe's arrival cycle: $rel ") { exact must be > 0 }
    }

  // ===========================================================================
  // 2. translation wait / page fault × coherence
  // ===========================================================================

  "a probe from another core is answered while the core waits for a TLB miss with X in S2 and Y in S1" in
    withFault(seed = 26) { e =>
      import e._
      for (v <- 0 until 3) {
        val b = ram(0x4000 + v * 2 * LineBytes); val lb = lineOf(b)
        val x = ram(0x5000 + v * 2 * LineBytes)
        oracle.classifyLine(lb, Seq(Owned(0), Owned(1), Free, Free))
        ld(0, b)
        cores(0).killOnExc = v == 2
        // v0: X = load elsewhere, Y = store to the probed line.
        // v1: X itself is the store to the probed line.
        // v2: X page-faults; the trap kills the younger store Y.
        val ops = v match {
          case 0 => Seq(CoreOp.load(x), CoreOp.store(b, 0x5100))
          case 1 => Seq(CoreOp.store(b, 0x5200), CoreOp.load(x))
          case _ => Seq(exc(CoreOp.load(fault(0x40)), 13), CoreOp.store(b, 0x5300))
        }
        tlbs(0).missUntil = bench.cycle + 300
        val first = cores(0).history.size
        cores(0).enqueue(ops: _*)
        bench.steps(8)
        cores(1).enqueue(CoreOp.store(b + 8, 0x6100 + v))
        bench.runUntil(cores(1).idle)
        val xTxn = cores(0).history(first)
        withClue(s"variant $v: ") {
          bench.cycle must be < tlbs(0).missUntil
          xTxn.respCycle mustBe -1L
          answersOf(0, lb, "InvAck") must not be empty
        }
        bench.quiesce()
        cores(0).killOnExc = false
        withClue(s"variant $v: ") {
          ld(1, b + 8) mustBe 0x6100 + v
          if (v == 2) {
            // Y is killed by the trap if it entered S1 behind X (l1d 7.1);
            // a Y still waiting in front of S0 issues afterwards.
            val y = txns(0, b, L1DOp.Store).last
            info(s"Y fired ${y.fired}, X fault response ${xTxn.respCycle}, Y killed ${y.killed}")
            if (y.fired < xTxn.respCycle) y.killed mustBe true
            ld(0, b) mustBe (if (y.killed) oracle.initial(b) else BigInt(0x5300))
          } else ld(0, b) mustBe BigInt(0x5100 + 0x100 * v)
        }
      }
    }

  "page-faulting accesses send no request, keep the reservation unless a trap clears it, and leave probes working" in
    withFault(seed = 27) { e =>
      import e._
      val w = ram(0x6000); val c = ram(0x6040); val lc = lineOf(c)
      oracle.classifyLine(lc, Seq(Owned(0), Owned(1), Free, Free))
      // No faulting SC here: whether an SC that traps still "executes" for
      // reservation clearing is not stated by l1d 8.1; it is checked below
      // only after a trap has cleared the reservation anyway.
      def faults(k: Int): Seq[CoreOp] = Seq(exc(CoreOp.load(fault(0x40 * k)), 13),
        exc(CoreOp.store(fault(0x40 * k + 8), 1), 15), exc(CoreOp.amo(fault(0x40 * k + 16), 1, BreezeAmoFunc.Add), 15),
        exc(CoreOp.lr(fault(0x40 * k + 24)), 13))
      ld(0, c)
      runOps(0, CoreOp.lr(w))
      // A fault is not one of the reservation-clearing events (l1d 8.1).
      together(0 -> faults(1), 1 -> Seq(CoreOp.store(c + 8, 0x7100)))
      runOps(0, CoreOp.sc(w, 0x71, success = true))
      answersOf(0, lc, "InvAck") must not be empty
      // With the backend trap model the first fault pulses trapClearRsv.
      cores(0).killOnExc = true
      runOps(0, CoreOp.lr(w))
      runOps(0, exc(CoreOp.load(fault(0x80)), 13))
      runOps(0, exc(CoreOp.sc(fault(0x88), 1, success = false), 15))
      cores(0).killOnExc = false
      runOps(0, CoreOp.sc(w, 0x72, success = false))
      ld(1, w) mustBe 0x71
      ld(0, c + 8) mustBe 0x7100
    }

  // ===========================================================================
  // 3. refill errors × multiple cores
  // ===========================================================================

  // coherence-l2 5.2/7.1: an AXI read error is never installed; the request
  // gets DataE/DataS/ReadData with error=1 and the slot is released. Every
  // later request therefore misses again and sees whatever memory returns.

  "a refill error is not installed in L2: each core's later miss reads memory again" in withFault(seed = 31) { e =>
    import e._
    val a = ram(0x7000); val l = lineOf(a)
    poison(l)
    runOps(0, CoreOp.load(a).copy(refillError = true))
    mem.readsOf(l) mustBe 1
    monitor.state(0, l) mustBe 'I'
    runOps(1, CoreOp.load(a + 8).copy(refillError = true))
    mem.readsOf(l) mustBe 2
    together(0 -> Seq(CoreOp.load(a + 16).copy(refillError = true)), 1 -> Seq(CoreOp.load(a).copy(refillError = true)))
    mem.readsOf(l) mustBe 4
    l1is(0).pending += ClientReq(ReqOp.Read, l, expectError = true)
    dma.pending += ClientReq(ReqOp.Read, l, expectError = true)
    bench.quiesce()
    mem.readsOf(l) mustBe 6
    mem.errorLines -= l
    ld(1, a) mustBe arch.read(a, 8)
    mem.readsOf(l) mustBe 7
    ld(0, a + 8) mustBe arch.read(a + 8, 8)
    mem.readsOf(l) mustBe 7
    monitor.errorGrants.size mustBe 4
  }

  "store, AMO, LR and PTW refill errors write nothing and the other core reads the original line" in
    withFault(seed = 32) { e =>
      import e._
      val s = ram(0x7100); val l = lineOf(s)
      poison(l)
      runOps(0, CoreOp.store(s, 0x1234).copy(refillError = true))
      runOps(0, exc(CoreOp.amo(s + 8, 1, BreezeAmoFunc.Add), 7), exc(CoreOp.lr(s + 16), 5))
      ptws(0).read(s + 24, expectFault = true); bench.quiesce()
      runOps(1, CoreOp.load(s).copy(refillError = true), exc(CoreOp.amo(s, 1, BreezeAmoFunc.Swap), 7))
      // SC without a reservation fails without a miss (l1d 5.2).
      runOps(0, CoreOp.sc(s, 1, success = false))
      reqsOf(0, l, "GetM").size mustBe 3
      mem.errorLines -= l
      for (k <- 0 until 4) ld(1, s + 8 * k) mustBe arch.read(s + 8 * k, 8)
      monitor.state(0, l) mustBe 'I'
    }

  "a refill error after the L2 victim was evicted from another core keeps that core's dirty data" in
    withFault(stress2, seed = 33) { e =>
      import e._
      val a = ram(0); val b = a + stride; val c = a + 2 * stride
      oracle.classifyLine(lineOf(a), Seq.fill(4)(Owned(1)))
      oracle.classifyLine(lineOf(b), Seq.fill(4)(Owned(1)))
      runOps(1, CoreOp.store(a, 0xa1), CoreOp.store(b + 8, 0xb2))
      poison(lineOf(c))
      runOps(0, CoreOp.load(c).copy(refillError = true))
      mem.writes.map(_._2) must contain atLeastOneOf(lineOf(a), lineOf(b))
      ld(0, a) mustBe 0xa1
      ld(0, b + 8) mustBe 0xb2
      mem.errorLines -= lineOf(c)
      ld(1, c) mustBe arch.read(c, 8)
      ld(1, a) mustBe 0xa1
    }

  // ===========================================================================
  // 4. PTW × multiple cores
  // ===========================================================================

  "PTW reads of a PTE another core keeps rewriting see whole values in coherence order" in withFault(seed = 41) { e =>
    import e._
    val pte = ram(0x8000); val l = lineOf(pte)
    oracle.classifyLine(l, Seq(Owned(1), Free, Free, Free))
    val writes = (0 until 48).map { i =>
      i % 4 match {
        case 0 => CoreOp.store(pte + (i % 8), BigInt(i + 1), 0)
        case 1 => CoreOp.amo(pte, BigInt(1) << (6 + i % 2), BreezeAmoFunc.Or)
        case 2 => CoreOp.store(pte + 4, BigInt(0x10000 + i), 2)
        case _ => CoreOp.store(pte, (BigInt(i) << 56) | BigInt(0x20000 + i))
      }
    }
    cores(1).issueProb = 0.4
    cores(1).enqueue(writes: _*)
    val start = bench.cycle
    for (i <- 0 until 48) script.schedule(start + 1 + 17 * i) { ptws(0).read(pte) }
    bench.quiesce()
    ptws(0).results.size mustBe 48
    info(s"distinct PTE values seen ${ptws(0).results.map(_._2).distinct.size}")
    ptws(0).results.map(_._2).distinct.size must be > 2
    monitor.probesTo(1, l) must contain("Down")
    monitor.probesTo(0, l) must contain("Inv")
  }

  "a PTW read of a line another core holds M pulls the data with Down; one of the core's own M line hits" in
    withFault(seed = 42) { e =>
      import e._
      val p = ram(0x8100); val q = ram(0x8140)
      runOps(1, CoreOp.store(p + 8, 0xabcd))
      ptws(0).read(p + 8); bench.quiesce()
      ptws(0).results.last._2 mustBe 0xabcd
      monitor.probesTo(1, lineOf(p)) mustBe Seq("Down")
      monitor.state(0, lineOf(p)) mustBe 'S'
      runOps(0, CoreOp.store(q + 16, 0x5151))
      val before = monitor.reqs.size
      ptws(0).read(q + 16); bench.quiesce()
      ptws(0).results.last._2 mustBe 0x5151
      monitor.reqs.size mustBe before
      // And while the other core rewrites it, the PTW still reads through Down.
      together(1 -> Seq(CoreOp.store(q + 16, 0x6262)))
      ptws(0).read(q + 16); bench.quiesce()
      ptws(0).results.last._2 mustBe 0x6262
    }

  // ===========================================================================
  // 5. Random
  // ===========================================================================

  /** L1DL2MultiCoreSpec.randomTraffic plus s1/s2 kills (also of SCs),
    * trap pulses, page faults (backend trap model), refill errors on lines
    * kept out of the oracle and PTW reads of shared lines.
    */
  private def randomTraffic(e: Env, opsPerCore: Int, sharedLines: Int = 4): Unit = {
    import e._
    val shared = (0 until sharedLines).map(i => lineOf(ram(0x2000 + (i % 2) * LineBytes + (i / 2) * stride)))
    for ((l, i) <- shared.zipWithIndex)
      oracle.classifyLine(l, Seq(Counter, Racy, Owned(i % n), Owned((i + 1) % n)))
    val basePrivateLines = g.l1dWays + 1
    val privateLines = if (basePrivateLines * (n / 2) + sharedLines / 2 > g.l2Ways)
      basePrivateLines else (g.l1dWays max g.l2Ways) + 1
    val priv = (0 until n).map(c => (0 until privateLines).map(t =>
      lineOf(ram(BigInt(0x100000) * (c + 1) + (c % 2) * LineBytes + t * stride))))
    for (c <- 0 until n; l <- priv(c)) oracle.classifyLine(l, Seq.fill(4)(Owned(c)))
    // Same L1D/L2 sets as the shared lines, other tags: their misses evict.
    val errLines = (0 until 4).map(i => lineOf(ram(0x2000 + (i % 2) * LineBytes + (i / 2 + 8) * stride)))
    errLines.foreach(poison)
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
    def maybeKill(o: CoreOp): CoreOp = rng.nextInt(100) match {
      case x if x < 2 => o.copy(s1Kill = true)
      case x if x < 4 => o.copy(s2KillAtResp = true)
      case x if x < 7 => o.copy(s2KillAfter = Some(2 + rng.nextInt(30)))
      case _ => o
    }
    def faultOp(): CoreOp = {
      val a = fault(rng.nextInt(64) * LineBytes)
      rng.nextInt(5) match {
        case 0 => exc(CoreOp.load(a + 8 * rng.nextInt(4)), 13)
        case 1 => exc(CoreOp.store(a + 8, BigInt(64, rng)), 15)
        case 2 => exc(CoreOp.amo(a + 16, 1, pick(funcs)), 15)
        case 3 => exc(CoreOp.lr(a), 13)
        case _ => exc(CoreOp.sc(a + 24, 1, success = false), 15)
      }
    }
    def errorOp(): CoreOp = {
      val a = (pick(errLines) << 5) + 8 * rng.nextInt(4)
      rng.nextInt(4) match {
        case 0 => CoreOp.load(a).copy(refillError = true)
        case 1 => CoreOp.store(a, BigInt(64, rng)).copy(refillError = true)
        case 2 => exc(CoreOp.amo(a, 1, BreezeAmoFunc.Add), 7)
        case _ => exc(CoreOp.lr(a), 5)
      }
    }
    def base(c: Int): (CoreOp, Option[BigInt]) = {
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
    def gen(c: Int): (CoreOp, Option[BigInt]) = rng.nextInt(100) match {
      case x if x < 3 => (faultOp(), None)
      case x if x < 5 => (errorOp(), None)
      case _ => val (o, inc) = base(c); (maybeKill(o), inc)
    }
    for (c <- 0 until n) {
      cores(c).issueProb = 0.7; cores(c).lateReadyProb = 0.6
      cores(c).killOnExc = true
      tlbs(c).missProb = 0.02; tlbs(c).busyProb = 0.03
      feeders(c).next = () => gen(c); feeders(c).remaining = opsPerCore
      feeders(c).scMutate = o => if (rng.nextInt(20) == 0) o.copy(s2KillAtResp = true) else o
      l1is(c).issueProb = 0.05
      for (_ <- 0 until opsPerCore / 16) l1is(c).pending += ClientReq.read(pick(shared), check = false)
      for (_ <- 0 until opsPerCore / 200) l1is(c).pending += ClientReq(ReqOp.Read, pick(errLines), expectError = true)
    }
    script.perCycle = _ => for (c <- 0 until n) {
      cores(c).trapClear = rng.nextInt(500) == 0
      if (rng.nextInt(100) == 0 && ptws(c).pending.size < 2) {
        if (rng.nextInt(10) == 0) ptws(c).read(pick(errLines) << 5, expectFault = true)
        else ptws(c).read(pick(words))
      }
    }
    mem.arReadyProb = 0.7; mem.awReadyProb = 0.7; mem.wReadyProb = 0.8; mem.rValidProb = 0.8
    mem.minLatency = 1; mem.maxLatency = 12
    dma.issueProb = 0.05
    for (_ <- 0 until opsPerCore / 16) dma.pending += ClientReq.read(pick(shared), check = false)
    bench.runUntil(feeders.forall(_.idle) && cores.forall(_.idle), limit = 4000000)
    script.perCycle = _ => ()
    cores.foreach { c => c.trapClear = false }
    l1is.foreach(_.pending.clear()); dma.pending.clear()
    tlbs.foreach { t => t.missProb = 0; t.busyProb = 0 }
    finish()
    val killed = cores.map(_.history.count(_.killed)).sum
    val faults = cores.map(_.history.count(t => t.op.expectExc.nonEmpty && t.done)).sum
    val errors = cores.map(_.history.count(t => t.op.refillError && t.done)).sum
    val ptwFaults = ptws.map(_.results.count(_._3)).sum
    info(s"oracle counts ${oracle.counts.toSeq.sorted.mkString(", ")}; killed $killed (LR ${feeders.map(_.killedLr).sum}, " +
      s"SC ${feeders.map(_.killedSc).sum}); exceptions $faults; refill errors $errors (grants ${monitor.errorGrants.size}); " +
      s"PTW ${ptws.map(_.results.size).sum} (faults $ptwFaults); probes Inv ${monitor.probes.count(_._3 == "Inv")} " +
      s"Down ${monitor.probes.count(_._3 == "Down")}; Puts ${monitor.answers.count(_._3 == "Put")}")
    oracle.counts("amo") must be > 0
    oracle.counts("scOk") must be > 0
    killed must be > 0
    feeders.map(_.killedLr).sum must be > 0
    faults must be > 0
    errors must be > 0
    ptwFaults must be > 0
    ptws.map(_.results.count(!_._3)).sum must be > 0
    monitor.probes.exists(_._3 == "Inv") mustBe true
    monitor.probes.exists(_._3 == "Down") mustBe true
    mem.writes must not be empty
  }

  for ((name, g, ops, seeds) <- Seq(("two cores", two, 1000, Seq(91, 92)), ("four cores", four, 1000, Seq(91)),
      ("two cores on the stress geometry", stress2, 2000, Seq(91, 92)),
      ("four cores on the stress geometry", BreezeMemGeometry.stress.copy(nCores = 4), 2000, Seq(91)));
      seed <- seeds)
    s"random traffic with kills, traps, page faults, refill errors and PTW reads, $name, seed $seed" in
      withFault(g, seed = seed + 10 * g.nCores + (if (g.l1Sets == 2) 100 else 0)) { e => randomTraffic(e, ops) }
}
