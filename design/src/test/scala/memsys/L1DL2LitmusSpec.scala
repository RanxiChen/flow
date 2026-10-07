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

/** Releases queued ops to cores at chosen cycles (litmus timing sweep). It is
  * listed before the cores in the bench, so a release reaches the core's
  * drive in the same cycle.
  */
class OpScheduler(cores: Seq[CoreDriver]) extends CycleAgent {
  val name = "litmus-scheduler"
  private val plan = ArrayBuffer.empty[(Long, Int, Seq[CoreOp])]
  def at(cycle: Long, core: Int, ops: Seq[CoreOp]): Unit = plan += ((cycle, core, ops))
  protected def drive(): Unit = {
    val due = plan.filter(_._1 <= now)
    if (due.nonEmpty) {
      due.foreach { case (_, c, ops) => cores(c).enqueue(ops: _*) }
      plan.filterInPlace(_._1 > now)
      progress()
    }
  }
  def sample(): Unit = ()
  def idle: Boolean = plan.isEmpty
  override def describe: String = s"$name: ${plan.size} releases pending"
}

/** RVWMO litmus shapes on nCores real L1Ds and the real L2, driven at the
  * L1DCoreIO boundary. Each round uses fresh lines, warms every variable
  * into one of four states (I: uncached; S: every participant shares it;
  * W: its writer holds it M; O: another participant holds it M), then
  * releases the threads at swept relative start cycles. The sweep span is
  * measured from the slowest store-miss ownership round trip T of the
  * geometry (calibrate).
  *
  * A forbidden outcome fails at once with its round, sweep point and warm
  * states. Each shape also names required outcomes - the premise the
  * forbidden check depends on, and both sides of each race - which must be
  * observed at least Threshold times, so a sweep that never reaches the
  * interesting interleaving fails instead of passing silently.
  *
  * Fence is L1DOp.Fence (waits for `drained`) = FENCE RW,RW. Plain
  * loads/stores carry no aq/rl (L1D honours aq/rl only on LR/SC/AMO, as in
  * the ISA), so release/acquire shapes use AMOSWAP.rl, AMOOR.aq and LR.aq.
  * Without a fence a store miss answers in S2 before ownership
  * (l1d-rtl-spec 6.2), so younger accesses may become visible first: the
  * no-fence MP is coverage only.
  */
class L1DL2LitmusSpec extends AnyFreeSpec with Matchers with ChiselSim {
  import WordClass._

  private val two = BreezeMemGeometry(nCores = 2)
  private val four = BreezeMemGeometry(nCores = 4)
  /** Every required outcome must be observed at least this often. */
  private val Threshold = 8

  class Env(val d: L1DL2MultiHarness, val g: BreezeMemGeometry, seed: Int) {
    val n: Int = g.nCores
    val rng = new Random(seed)
    val arch = new GoldenMem(seed)
    val dram = new GoldenMem(seed)
    val oracle = new MultiCoreOracle(arch)
    val devices = (0 until n).map(c => new AxiLiteDevice(d.io.mmio(c), rng, seed + 7 + c))
    val cores = (0 until n).map(c =>
      new CoreDriver(d.io.core(c), arch, Some(devices(c)), rng, s"core$c", c, Some(oracle)))
    val sched = new OpScheduler(cores)
    val ptws = (0 until n).map(c => new PtwDriver(d.io.ptw(c), arch))
    val tlbs = (0 until n).map(c => new TlbKnobs(d.io.tlb(c), rng))
    val mem = new AxiMemory(d.io.mem, dram, rng)
    val l1is = (0 until n).map(c => new ReadClientAgent(d.io.l1i(c), s"l1i$c", arch, rng, 2))
    val dma = new ReadClientAgent(d.io.dma, "dma", arch, rng, 1)
    val monitor = new CoherenceMonitor(d.io.obs)
    val bench = new Bench(d.clock, Seq(sched) ++ tlbs ++ cores ++ ptws ++ Seq(mem) ++ devices ++ l1is ++ Seq(dma, monitor))
    /** Next tag in the same L1D set and the same L2 set. */
    val stride: BigInt = (BigInt(g.l1Sets) max BigInt(CoherenceParams(g).l2Sets)) * LineBytes
    def ram(off: BigInt): BigInt = MainRam + off
    /** Per-core private line: gap loads hit it without touching shared lines. */
    def priv(c: Int): BigInt = ram(0x80000 + c * 0x1000)

    def runOps(c: Int, ops: CoreOp*): Unit = { cores(c).enqueue(ops: _*); bench.quiesce() }
    def together(ops: (Int, Seq[CoreOp])*): Unit = { ops.foreach { case (c, o) => cores(c).enqueue(o: _*) }; bench.quiesce() }

    /** Current line contents through L2 (DMA Read; Down pulls dirty data). */
    def readLines(ls: Seq[BigInt]): Map[BigInt, BigInt] = {
      val before = dma.results.size
      ls.foreach(l => dma.pending += ClientReq.read(l, check = false))
      bench.quiesce()
      dma.results.drop(before).map(r => r._2.line -> r._3).toMap
    }

    def finish(): Unit = {
      bench.quiesce()
      val ls = (oracle.lines ++ arch.touched).toSeq.sorted
      val back = readLines(ls)
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

  private def withLitmus(g: BreezeMemGeometry, seed: Int)(body: Env => Unit): Unit =
    simulate(new L1DL2MultiHarness(g)) { d =>
      init(d)
      val env = new Env(d, g, seed)
      body(env)
      env.finish()
    }

  // ===========================================================================
  // Litmus framework
  // ===========================================================================

  /** One program step; `read` = (result name, variable) for a value-returning step. */
  private case class Step(op: CoreOp, read: Option[(String, String)] = None)

  /** One litmus instance: fresh lines, unique values, the label of every value.
    * Old value (after warm) reads as "0"; written values read as their label.
    */
  private class Inst(e: Env, val r: Int, vars: Seq[String], spread: Boolean) {
    // Reserve a disjoint block even for the furthest same-set variable.
    // Keep the original round's set rotation inside that block.
    private val block = e.stride * (vars.size + 1)
    private val base = e.ram(BigInt(0x100000) + BigInt(r) * block + (BigInt(r) * 0x100 % e.stride))
    /** `spread` puts the variables into the same L1D and L2 set. */
    val addr: Map[String, BigInt] = vars.zipWithIndex.map { case (v, i) =>
      v -> (base + i * (if (spread) e.stride else BigInt(LineBytes)))
    }.toMap
    require(addr.values.map(lineOf).toSet.size == vars.size, "litmus variables must use distinct lines")
    if (spread) {
      require(addr.values.map(a => lineOf(a) % e.g.l1Sets).toSet.size == 1, "spread must share an L1D set")
      require(addr.values.map(a => lineOf(a) % CoherenceParams(e.g).l2Sets).toSet.size == 1,
        "spread must share an L2 set")
    }
    val old = mutable.Map.empty[String, BigInt]
    private val labels = mutable.Map.empty[(String, BigInt), String]
    private var serial = 0
    def value(v: String, lab: String): BigInt = {
      serial += 1
      val x = (BigInt(vars.indexOf(v) + 1) << 56) | (BigInt(r) << 16) | BigInt(serial)
      labels((v, x)) = lab
      x
    }
    def label(v: String, x: BigInt): String =
      if (old.get(v).contains(x)) "0"
      else labels.getOrElse((v, x), throw new AssertionError(
        s"round $r: ${hex(x)} read from $v is neither its old value ${old.get(v).map(hex)} nor a value written to it"))

    def st(v: String, lab: String): Step = Step(CoreOp.store(addr(v), value(v, lab)))
    def ld(v: String, as: String): Step = Step(CoreOp.load(addr(v)), Some((as, v)))
    def fence: Step = Step(CoreOp.fence)
    def gap(c: Int, k: Int): Seq[Step] = Seq.fill(k)(Step(CoreOp.load(e.priv(c))))
    def swapRl(v: String, lab: String): Step =
      Step(CoreOp.amo(addr(v), value(v, lab), BreezeAmoFunc.Swap).copy(rl = true))
    /** AMOOR.aq with 0: an acquire read that rewrites the same value. */
    def orAq(v: String, as: String): Step =
      Step(CoreOp.amo(addr(v), 0, BreezeAmoFunc.Or).copy(aq = true), Some((as, v)))
    def lrAq(v: String, as: String): Step = Step(CoreOp.lr(addr(v)).copy(aq = true), Some((as, v)))
  }

  private case class Outcome(reads: Map[String, String], fin: Map[String, String]) {
    def apply(k: String): String = reads(k)
    def f(v: String): String = fin(v)
    override def toString: String =
      (reads.toSeq.sorted.map { case (k, v) => s"$k=$v" } ++ fin.toSeq.sorted.map { case (k, v) => s"f$k=$v" })
        .mkString(" ")
  }

  /** Sweep axis in units of T: `points` values evenly from lo*T to hi*T. */
  private case class Axis(name: String, lo: Double, hi: Double, points: Int)

  private case class Shape(
      name: String,
      vars: Seq[String],
      /** Racy for plain load/store shapes (oracle also checks every read); Free with AMO/LR. */
      cls: WordClass,
      /** Writer of each variable used for warm state W. */
      writer: Map[String, Int],
      cores: Seq[Int],
      axes: Seq[Axis],
      passes: Int,
      /** Start offset of each participant at a sweep point (normalised to min 0). */
      starts: Map[String, Int] => Map[Int, Int],
      prog: (Inst, Map[String, Int]) => Map[Int, Seq[Step]],
      /** Read final values back through L2 after the round. */
      finals: Boolean,
      /** Some(reason) for an outcome RVWMO forbids. */
      forbidden: Outcome => Option[String],
      /** Outcomes that must each be observed at least Threshold times. */
      required: Seq[(String, Outcome => Boolean)],
      /** Append a wider grid after every original point (coverage expansion). */
      extraAxes: Seq[Axis] = Seq.empty)

  private def req(name: String)(p: Outcome => Boolean): (String, Outcome => Boolean) = (name, p)
  private def ban(cond: Boolean, why: String): Option[String] = if (cond) Some(why) else None
  private val twoCores: Map[String, Int] => Map[Int, Int] = pt => Map(0 -> 0, 1 -> pt("d"))

  /** Slowest store-miss ownership round trip (fire → grant) over uncached,
    * shared-elsewhere and M-elsewhere lines, three samples each.
    */
  private def calibrate(e: Env): Int = {
    import e._
    val samples = for (k <- 0 until 3; m <- 0 until 3) yield {
      val a = ram(0x60000 + (k * 3 + m) * 0x40)
      m match {
        case 1 => runOps(1, CoreOp.load(a))
        case 2 => runOps(1, CoreOp.store(a, BigInt(0x1000 + k)))
        case _ =>
      }
      runOps(0, CoreOp.store(a, BigInt(0x2000 + k * 3 + m)))
      val t = cores(0).last
      val g = monitor.grants.filter(x => x._2 == 0 && x._4 == lineOf(a) && x._1 >= t.fired).map(_._1)
      if (g.isEmpty) throw new AssertionError(s"calibration store ${hex(a)} got no grant")
      (g.min - t.fired).toInt
    }
    (samples.max max 8) min 300
  }

  private def axisValues(a: Axis, t: Int): Seq[Int] = {
    val lo = math.round(a.lo * t).toInt
    val hi = math.round(a.hi * t).toInt
    if (a.points <= 1 || hi == lo) Seq(lo)
    else (0 until a.points).map(i => lo + ((hi - lo).toLong * i / (a.points - 1)).toInt).distinct
  }

  private def applyWarm(e: Env, s: Shape, inst: Inst, warm: Seq[Char]): Unit = {
    import e._
    for ((v, w) <- s.vars.zip(warm)) {
      val a = inst.addr(v)
      inst.old(v) = arch.read(a, 8)
      w match {
        case 'S' => together(s.cores.map(c => c -> Seq(CoreOp.load(a))): _*)
        case 'W' =>
          val p = inst.value(v, "pre"); runOps(s.writer(v), CoreOp.store(a, p)); inst.old(v) = p
        case 'O' =>
          val others = s.cores.filter(_ != s.writer(v))
          val p = inst.value(v, "pre"); runOps(others(inst.r / 4 % others.size), CoreOp.store(a, p)); inst.old(v) = p
        case _ =>
      }
    }
  }

  private def runShape(e: Env, s: Shape): Unit = {
    import e._
    for (c <- s.cores) runOps(c, CoreOp.load(priv(c)))
    val t = calibrate(e)
    def grid(axes: Seq[Axis]): Seq[Map[String, Int]] =
      axes.foldLeft(Seq(Map.empty[String, Int])) { (acc, a) =>
        for (m <- acc; v <- axisValues(a, t)) yield m + (a.name -> v)
      }
    require(s.extraAxes.isEmpty || s.extraAxes.map(_.name) == s.axes.map(_.name), "expanded grid axes must match")
    val points = (grid(s.axes) ++ (if (s.extraAxes.isEmpty) Seq.empty else grid(s.extraAxes))).distinct
    // Integer-cycle deduplication can leave fewer than the planned points.
    // Preserve every original pass and repeat the whole sweep until at least
    // the task's nominal round count is exercised; never reduce the scale.
    val nominalRounds = s.passes * s.axes.map(_.points).product
    val passes = s.passes max ((nominalRounds + points.size - 1) / points.size)
    val combos = s.vars.foldLeft(Seq(Seq.empty[Char]))((acc, _) => for (c <- acc; w <- "ISWO") yield c :+ w)
    val seen = mutable.Map.empty[String, Int].withDefaultValue(0)
    val hits = mutable.Map.empty[String, ArrayBuffer[Map[String, Int]]]
    s.required.foreach(r => hits(r._1) = ArrayBuffer.empty)
    var r = 0
    for (_ <- 0 until passes; pt <- points) {
      val warm = combos((r * 5) % combos.size)
      val inst = new Inst(e, r, s.vars, spread = r % 3 == 0)
      val where = s"round $r, ${pt.toSeq.sorted.map { case (k, v) => s"$k=$v" }.mkString(", ")}, " +
        s"warm ${s.vars.zip(warm).map { case (v, w) => s"$v:$w" }.mkString(" ")}, T=$t"
      try {
        s.vars.foreach(v => oracle.classify(inst.addr(v), s.cls))
        applyWarm(e, s, inst, warm)
        val progs = s.prog(inst, pt)
        val st = s.starts(pt)
        val min = st.values.min
        val mark = progs.keys.map(c => c -> cores(c).history.size).toMap
        val base = bench.cycle + 2
        for ((c, steps) <- progs) sched.at(base + st(c) - min, c, steps.map(_.op))
        bench.quiesce()
        val reads = for ((c, steps) <- progs.toSeq; (step, txn) <- steps.zip(cores(c).history.drop(mark(c)));
                         (as, v) <- step.read) yield {
          if (!txn.done || txn.killed) throw new AssertionError(s"${s.name}: $txn did not complete at $where")
          as -> inst.label(v, txn.value)
        }
        for ((c, steps) <- progs if cores(c).history.size - mark(c) != steps.size)
          throw new AssertionError(s"${s.name}: core $c issued ${cores(c).history.size - mark(c)} of ${steps.size} steps at $where")
        val fin = if (!s.finals) Map.empty[String, String] else {
          val back = readLines(s.vars.map(v => lineOf(inst.addr(v))).distinct)
          s.vars.map { v =>
            val a = inst.addr(v)
            v -> inst.label(v, (back(lineOf(a)) >> (8 * (a & 31).toInt)) & mask(64))
          }.toMap
        }
        val o = Outcome(reads.toMap, fin)
        s.forbidden(o).foreach(why => throw new AssertionError(s"${s.name}: forbidden outcome [$o] ($why) at $where"))
        seen(o.toString) += 1
        for ((name, p) <- s.required if p(o)) hits(name) += pt
      } catch {
        case t: Throwable =>
          info(s"${s.name}: failure at $where, cycle ${bench.cycle}\n${monitor.describe}")
          throw t
      }
      r += 1
    }
    def range(ps: Seq[Map[String, Int]]): String =
      if (ps.isEmpty) "never" else s.axes.map(a => s"${a.name} ${ps.map(_(a.name)).min}..${ps.map(_(a.name)).max}").mkString(", ")
    info(s"${s.name}: T=$t cycles, $r rounds, oracle ${oracle.counts.toSeq.sorted.mkString(" ")}")
    info(s"${s.name} scan: ${points.size} unique points, $passes passes, nominal minimum $nominalRounds rounds")
    assert(r >= nominalRounds, s"${s.name}: $r rounds below the planned $nominalRounds")
    info(s"${s.name} outcomes: " + seen.toSeq.sortBy(-_._2).map { case (k, v) => s"[$k]=$v" }.mkString(", "))
    for ((name, _) <- s.required)
      info(s"${s.name} required '$name': ${hits(name).size} hits (${range(hits(name).toSeq)})")
    for ((name, _) <- s.required)
      assert(hits(name).size >= Threshold, s"${s.name}: required outcome '$name' observed ${hits(name).size} < $Threshold " +
        s"times in $r rounds (T=$t); outcomes ${seen.toSeq.sortBy(-_._2).mkString(", ")}")
  }

  // ===========================================================================
  // Shapes. Forbidden outcomes per RVWMO (RISC-V unprivileged spec, ch. 17
  // and appendix A litmus tests); FENCE = fence rw,rw.
  // ===========================================================================

  private val oneAxis = Seq(Axis("d", -1, 2, 96))
  private val symAxis = Seq(Axis("d", -2, 2, 96))
  /** Same-address shapes: offset d and an intra-thread gap g (private hit loads). */
  private val gapAxes = Seq(Axis("d", -1, 2, 48), Axis("g", 0, 1, 4))
  private def rank(s: String): Int = s.toInt

  private val shapes: Seq[(Shape, BreezeMemGeometry, Int)] = Seq(
    // MP+fence.rw.rw: P0 Wx=1; F; Wy=1 || P1 Ry; F; Rx. Forbidden r1=1, r2=0 (fence PPO rule 4).
    (Shape("MP+fence.rw.rw", Seq("x", "y"), Racy, Map("x" -> 0, "y" -> 0), Seq(0, 1), oneAxis, 4, twoCores,
      (i, _) => Map(0 -> Seq(i.st("x", "1"), i.fence, i.st("y", "1")),
        1 -> Seq(i.ld("y", "r1"), i.fence, i.ld("x", "r2"))),
      finals = false,
      o => ban(o("r1") == "1" && o("r2") == "0", "flag seen before data"),
      Seq(req("flag new")(o => o("r1") == "1"), req("flag old")(o => o("r1") == "0"))), two, 31),
    // MP with release/acquire AMOs: P0 Wx=1; AMOSWAP.rl y=1 || P1 AMOOR.aq y; Rx.
    // Forbidden r1=1, r2=0 (PPO rules 5 acquire, 6 release). Plain SD/LD carry no aq/rl.
    (Shape("MP+amoswap.rl+amoor.aq", Seq("x", "y"), Free, Map("x" -> 0, "y" -> 0), Seq(0, 1), oneAxis, 4, twoCores,
      (i, _) => Map(0 -> Seq(i.st("x", "1"), i.swapRl("y", "1")),
        1 -> Seq(i.orAq("y", "r1"), i.ld("x", "r2"))),
      finals = false,
      o => ban(o("r1") == "1" && o("r2") == "0", "acquire saw release flag before data"),
      Seq(req("flag new")(o => o("r1") == "1"), req("flag old")(o => o("r1") == "0"))), two, 32),
    // MP with AMOSWAP.rl and LR.aq as the acquire read. Forbidden r1=1, r2=0 (PPO rules 5, 6).
    (Shape("MP+amoswap.rl+lr.aq", Seq("x", "y"), Free, Map("x" -> 0, "y" -> 0), Seq(0, 1), oneAxis, 4, twoCores,
      (i, _) => Map(0 -> Seq(i.st("x", "1"), i.swapRl("y", "1")),
        1 -> Seq(i.lrAq("y", "r1"), i.ld("x", "r2"))),
      finals = false,
      o => ban(o("r1") == "1" && o("r2") == "0", "LR.aq saw release flag before data"),
      Seq(req("flag new")(o => o("r1") == "1"), req("flag old")(o => o("r1") == "0"))), two, 33),
    // MP without fences: RVWMO allows every outcome (no PPO between the stores or the loads). Coverage only.
    (Shape("MP (no fence, coverage)", Seq("x", "y"), Racy, Map("x" -> 0, "y" -> 0), Seq(0, 1), oneAxis, 2, twoCores,
      (i, _) => Map(0 -> Seq(i.st("x", "1"), i.st("y", "1")), 1 -> Seq(i.ld("y", "r1"), i.ld("x", "r2"))),
      finals = false, _ => None, Seq.empty), two, 34),
    // SB+fence.rw.rw: P0 Wx=1; F; Ry || P1 Wy=1; F; Rx. Forbidden r0=0, r1=0.
    (Shape("SB+fence.rw.rw", Seq("x", "y"), Racy, Map("x" -> 0, "y" -> 1), Seq(0, 1), symAxis, 4, twoCores,
      (i, _) => Map(0 -> Seq(i.st("x", "1"), i.fence, i.ld("y", "r0")),
        1 -> Seq(i.st("y", "1"), i.fence, i.ld("x", "r1"))),
      finals = false,
      o => ban(o("r0") == "0" && o("r1") == "0", "both loads passed both stores"),
      Seq(req("P0 first (r0=0 r1=1)")(o => o("r0") == "0" && o("r1") == "1"),
        req("P1 first (r0=1 r1=0)")(o => o("r0") == "1" && o("r1") == "0"))), two, 35),
    // LB+fence.rw.rw: P0 Rx; F; Wy=1 || P1 Ry; F; Wx=1. Forbidden r0=1, r1=1 (load-buffering cycle).
    (Shape("LB+fence.rw.rw", Seq("x", "y"), Racy, Map("x" -> 1, "y" -> 0), Seq(0, 1), symAxis, 4, twoCores,
      (i, _) => Map(0 -> Seq(i.ld("x", "r0"), i.fence, i.st("y", "1")),
        1 -> Seq(i.ld("y", "r1"), i.fence, i.st("x", "1"))),
      finals = false,
      o => ban(o("r0") == "1" && o("r1") == "1", "each load saw the other thread's later store"),
      Seq(req("P0 first (r0=0 r1=1)")(o => o("r0") == "0" && o("r1") == "1"),
        req("P1 first (r0=1 r1=0)")(o => o("r0") == "1" && o("r1") == "0"))), two, 36),
    // CoRR: P0 Wx=1 || P1 Rx; Rx. Forbidden r1=1, r2=0 (coherence: same-address loads in order).
    (Shape("CoRR", Seq("x"), Racy, Map("x" -> 0), Seq(0, 1), gapAxes, 2, twoCores,
      (i, pt) => Map(0 -> Seq(i.st("x", "1")),
        1 -> (Seq(i.ld("x", "r1")) ++ i.gap(1, pt("g")) ++ Seq(i.ld("x", "r2")))),
      finals = false,
      o => ban(o("r1") == "1" && o("r2") == "0", "second read went back to the old value"),
      Seq(req("first read new")(o => o("r1") == "1"),
        req("store between the reads (r1=0 r2=1)")(o => o("r1") == "0" && o("r2") == "1"))), two, 37),
    // CoWR: P0 Wx=a; Rx || P1 Wx=b. Forbidden r0=old (own store not visible), and r0=b with final a
    // (b read after own a must be coherence-after a).
    (Shape("CoWR", Seq("x"), Racy, Map("x" -> 0), Seq(0, 1), gapAxes, 2, twoCores,
      (i, pt) => Map(0 -> (Seq(i.st("x", "a")) ++ i.gap(0, pt("g")) ++ Seq(i.ld("x", "r0"))),
        1 -> Seq(i.st("x", "b"))),
      finals = true,
      o => ban(o("r0") == "0", "own store not visible to the next load")
        .orElse(ban(o("r0") == "b" && o.f("x") == "a", "read b after own a, but a is coherence-last")),
      Seq(req("read other store (r0=b)")(o => o("r0") == "b"),
        req("final a")(o => o.f("x") == "a"), req("final b")(o => o.f("x") == "b"))), two, 38),
    // CoWW: P0 Wx=1; Wx=2 || P1 Rx; Rx. Forbidden final != 2 and any observer order going back.
    (Shape("CoWW", Seq("x"), Racy, Map("x" -> 0), Seq(0, 1), gapAxes, 2, twoCores,
      (i, pt) => Map(0 -> (Seq(i.st("x", "1")) ++ i.gap(0, pt("g")) ++ Seq(i.st("x", "2"))),
        1 -> (Seq(i.ld("x", "r1")) ++ i.gap(1, pt("g")) ++ Seq(i.ld("x", "r2")))),
      finals = true,
      o => ban(o.f("x") != "2", "coherence order against program order")
        .orElse(ban(rank(o("r2")) < rank(o("r1")), "observer saw the two stores out of order")),
      Seq(req("intermediate value observed")(o => o("r1") == "1" || o("r2") == "1"))), two, 39),
    // CoRW: P0 Rx; Wx=a || P1 Wx=b. Forbidden r0=a, and r0=b with final b (b read before own a
    // must be coherence-before a).
    (Shape("CoRW", Seq("x"), Racy, Map("x" -> 0), Seq(0, 1), gapAxes, 2, twoCores,
      (i, pt) => Map(0 -> (Seq(i.ld("x", "r0")) ++ i.gap(0, pt("g")) ++ Seq(i.st("x", "a"))),
        1 -> Seq(i.st("x", "b"))),
      finals = true,
      o => ban(o("r0") == "a", "load saw its own later store")
        .orElse(ban(o("r0") == "b" && o.f("x") == "b", "read b before own a, but b is coherence-last")),
      Seq(req("read other store (r0=b)")(o => o("r0") == "b"),
        req("final a")(o => o.f("x") == "a"), req("final b")(o => o.f("x") == "b"))), two, 40),
    // 2+2W+fence.rw.rw: P0 Wx=a1; F; Wy=a2 || P1 Wy=b1; F; Wx=b2. Forbidden final x=a1, y=b1.
    (Shape("2+2W+fence.rw.rw", Seq("x", "y"), Racy, Map("x" -> 0, "y" -> 1), Seq(0, 1), symAxis, 4, twoCores,
      (i, _) => Map(0 -> Seq(i.st("x", "a1"), i.fence, i.st("y", "a2")),
        1 -> Seq(i.st("y", "b1"), i.fence, i.st("x", "b2"))),
      finals = true,
      o => ban(o.f("x") == "a1" && o.f("y") == "b1", "each first store is coherence-last"),
      Seq(req("interleaved (x=b2 y=a2)")(o => o.f("x") == "b2" && o.f("y") == "a2"),
        req("P1 first (x=a1 y=a2)")(o => o.f("x") == "a1" && o.f("y") == "a2"),
        req("P0 first (x=b2 y=b1)")(o => o.f("x") == "b2" && o.f("y") == "b1"))), two, 41),
    // WRC+fences: P0 Wx=1 || P1 Rx; F; Wy=1 || P2 Ry; F; Rx. Forbidden r1=1, r2=1, r3=0
    // (RVWMO is multi-copy atomic).
    (Shape("WRC+fence.rw.rws", Seq("x", "y"), Racy, Map("x" -> 0, "y" -> 1), Seq(0, 1, 2),
      Seq(Axis("d1", -1, 2, 16), Axis("d2", -1, 2, 16)), 1,
      pt => Map(0 -> 0, 1 -> pt("d1"), 2 -> (pt("d1") + pt("d2"))),
      (i, _) => Map(0 -> Seq(i.st("x", "1")),
        1 -> Seq(i.ld("x", "r1"), i.fence, i.st("y", "1")),
        2 -> Seq(i.ld("y", "r2"), i.fence, i.ld("x", "r3"))),
      finals = false,
      o => ban(o("r1") == "1" && o("r2") == "1" && o("r3") == "0", "causally later reader missed x"),
      Seq(req("chain observed (r1=1 r2=1)")(o => o("r1") == "1" && o("r2") == "1"))), four, 42),
    // ISA2+fences: P0 Wx=1; F; Wy=1 || P1 Ry; F; Wz=1 || P2 Rz; F; Rx. Forbidden r1=1, r2=1, r3=0.
    (Shape("ISA2+fence.rw.rws", Seq("x", "y", "z"), Racy, Map("x" -> 0, "y" -> 0, "z" -> 1), Seq(0, 1, 2),
      Seq(Axis("d1", -1, 2, 16), Axis("d2", -1, 2, 16)), 1,
      pt => Map(0 -> 0, 1 -> pt("d1"), 2 -> (pt("d1") + pt("d2"))),
      (i, _) => Map(0 -> Seq(i.st("x", "1"), i.fence, i.st("y", "1")),
        1 -> Seq(i.ld("y", "r1"), i.fence, i.st("z", "1")),
        2 -> Seq(i.ld("z", "r2"), i.fence, i.ld("x", "r3"))),
      finals = false,
      o => ban(o("r1") == "1" && o("r2") == "1" && o("r3") == "0", "transitive order lost"),
      Seq(req("chain observed (r1=1 r2=1)")(o => o("r1") == "1" && o("r2") == "1")),
      extraAxes = Seq(Axis("d1", -1, 6, 32), Axis("d2", -1, 6, 32))), four, 43),
    // IRIW+fences: P0 Wx=1 || P1 Wy=1 || P2 Rx; F; Ry || P3 Ry; F; Rx. Forbidden a=1 b=0 c=1 d=0
    // (readers disagree on the order of independent writes; RVWMO is multi-copy atomic).
    (Shape("IRIW+fence.rw.rws", Seq("x", "y"), Racy, Map("x" -> 0, "y" -> 1), Seq(0, 1, 2, 3),
      Seq(Axis("w", -1, 1, 16), Axis("s", -1, 2, 16)), 1,
      pt => Map(0 -> 0, 1 -> pt("w"), 2 -> pt("s"), 3 -> (pt("w") + pt("s"))),
      (i, _) => Map(0 -> Seq(i.st("x", "1")), 1 -> Seq(i.st("y", "1")),
        2 -> Seq(i.ld("x", "a"), i.fence, i.ld("y", "b")),
        3 -> Seq(i.ld("y", "c"), i.fence, i.ld("x", "d"))),
      finals = false,
      o => ban(o("a") == "1" && o("b") == "0" && o("c") == "1" && o("d") == "0", "readers saw the writes in opposite orders"),
      Seq(req("P2 sees x first (a=1 b=0)")(o => o("a") == "1" && o("b") == "0"),
        req("P3 sees y first (c=1 d=0)")(o => o("c") == "1" && o("d") == "0")),
      extraAxes = Seq(Axis("w", -8, 8, 32), Axis("s", -1, 6, 32))), four, 44)
  )

  for ((s, g, seed) <- shapes)
    s"${s.name} (${g.nCores} cores, seed $seed): no forbidden outcome, every required outcome observed" in
      withLitmus(g, seed) { e => runShape(e, s) }
}
