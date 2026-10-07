package flow.memsys

import flow.interface._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

import MemTestKit._

/** How the oracle judges one aligned 8 B word.
  *  - Owned(c): only core c writes it. Core c's accesses are exact against
  *    `arch`; other cores must read a value the word held, never older than
  *    one they already read (CoRR).
  *  - Racy: any core writes unique full-word values. A load must return the
  *    initial value or a written one; per writer, observed write order never
  *    goes back; a core sees its own latest store until another core's.
  *  - Counter: only AMOADD.D, LR.D→SC.D (data = LR value + inc) and 8 B
  *    loads. Every successful update must extend one chain from the initial
  *    value (atomicity); loads never go back.
  *  - Free: sequential directed use; `arch` updated at commit, loads are
  *    asserted by the test itself, final value exact.
  */
sealed trait WordClass
object WordClass {
  final case class Owned(core: Int) extends WordClass
  case object Racy extends WordClass
  case object Counter extends WordClass
  case object Free extends WordClass
}

/** Multi-core reference. The commit point of a core's access is its S2
  * response (or `late` for a load miss), in that core's program order.
  * Across cores no global order is assumed: a store miss answers in S2
  * before it obtains ownership (l1d-rtl-spec 6.2), so response time is not
  * the coherence order. Checks therefore use only per-word facts that hold
  * under any coherence order, plus exact checks where one core is the only
  * writer.
  */
class MultiCoreOracle(arch: GoldenMem) {
  import WordClass._
  private val classes = mutable.Map.empty[BigInt, WordClass]
  private val init = mutable.Map.empty[BigInt, BigInt]
  // Owned: value history (index 0 = initial) and per-reader last index.
  private val history = mutable.Map.empty[BigInt, ArrayBuffer[BigInt]]
  private val readIdx = mutable.Map.empty[(Int, BigInt), Int]
  // Racy: value -> (writer, seq); per-writer last seq/value; per reader state.
  private val racyOf = mutable.Map.empty[(BigInt, BigInt), (Int, Int)]
  private val racyLast = mutable.Map.empty[(BigInt, Int), (Int, BigInt)]
  private val racySeen = mutable.Map.empty[(Int, BigInt, Int), Int]
  private val racySawWrite = mutable.Set.empty[(Int, BigInt)]
  private val racyOtherSinceOwn = mutable.Set.empty[(Int, BigInt)]
  // Counter: committed updates (offset of old value from init, inc), loads, per-core floor.
  private val chain = mutable.Map.empty[BigInt, ArrayBuffer[(BigInt, BigInt, Int, String)]]
  private val counterReads = mutable.Map.empty[BigInt, ArrayBuffer[(Int, BigInt)]]
  private val counterFloor = mutable.Map.empty[(Int, BigInt), BigInt]
  private val lrOf = mutable.Map.empty[Int, (BigInt, BigInt)]
  val counts: mutable.Map[String, Int] = mutable.Map.empty[String, Int].withDefaultValue(0)

  private def fail(core: Int, cycle: Long, msg: String): Nothing =
    throw new AssertionError(s"[oracle core $core @ cycle $cycle] $msg")
  private def wordOf(a: BigInt): BigInt = a & ~BigInt(7)
  private def wrap(v: BigInt): BigInt = v.mod(BigInt(1) << 64)
  private def sub(raw: BigInt, off: Int, n: Int): BigInt = (raw >> (8 * off)) & mask(8 * n)

  def classify(word: BigInt, c: WordClass): Unit = {
    val w = wordOf(word)
    require(!classes.contains(w), s"word ${hex(w)} classified twice")
    classes(w) = c
    init(w) = arch.read(w, 8)
    c match {
      case Owned(_) => history(w) = ArrayBuffer(init(w))
      case Counter => chain(w) = ArrayBuffer.empty; counterReads(w) = ArrayBuffer.empty
      case _ =>
    }
  }
  def classifyLine(line: BigInt, words: Seq[WordClass]): Unit =
    words.zipWithIndex.foreach { case (c, i) => classify((line << 5) + 8 * i, c) }
  def classOf(a: BigInt): WordClass = classes.getOrElse(wordOf(a), Free)
  def lines: Set[BigInt] = classes.keys.map(lineOf).toSet
  def initial(word: BigInt): BigInt = init(wordOf(word))

  private def requireAligned(core: Int, o: CoreOp, cycle: Long): Unit =
    if ((o.addr & ((1 << o.size) - 1)) != 0) fail(core, cycle, s"$o is misaligned")
  private def requireFull(core: Int, o: CoreOp, cycle: Long, what: String): Unit =
    if (o.size != 3 || (o.addr & 7) != 0) fail(core, cycle, s"$o: $what words take only aligned 8 B accesses")

  private def ownedWrite(w: BigInt, a: BigInt, n: Int, v: BigInt, cycle: Long): Unit = {
    arch.write(a, n, v, cycle)
    history(w) += arch.read(w, 8)
  }

  /** Value observed by a load or LR (`data` as formatted by the L1D). */
  def load(core: Int, o: CoreOp, data: BigInt, cycle: Long): Unit = {
    requireAligned(core, o, cycle)
    val w = wordOf(o.addr)
    val off = (o.addr & 7).toInt
    val n = 1 << o.size
    counts("load") += 1
    classOf(w) match {
      case Free =>
      case Owned(c) if c == core =>
        val exp = formatLoad(arch.read(o.addr, n), o.size, o.signed, o.isFlw)
        if (data != exp) fail(core, cycle, s"$o on own word: ${hex(data)}, expected ${hex(exp)}")
        readIdx((core, w)) = history(w).size - 1
      case Owned(c) =>
        val h = history(w)
        val from = readIdx.getOrElse((core, w), 0)
        val idx = (from until h.size).find(i => formatLoad(sub(h(i), off, n), o.size, o.signed, o.isFlw) == data)
        if (idx.isEmpty) fail(core, cycle, s"$o on core $c's word read ${hex(data)}, not a value the word held " +
          s"at or after index $from: ${h.drop(from).map(hex).mkString(", ")}")
        readIdx((core, w)) = idx.get
      case Racy =>
        requireFull(core, o, cycle, "racy")
        racyRead(core, w, data, o, cycle)
      case Counter =>
        requireFull(core, o, cycle, "counter")
        counterRead(core, w, data, o, cycle)
    }
  }

  private def racyRead(core: Int, w: BigInt, v: BigInt, o: CoreOp, cycle: Long): Unit =
    racyOf.get((w, v)) match {
      case None =>
        if (v != init(w)) fail(core, cycle, s"$o read ${hex(v)}, never written to ${hex(w)}")
        if (racySawWrite((core, w))) fail(core, cycle, s"$o read the initial value after observing a write")
        if (racyLast.contains((w, core))) fail(core, cycle, s"$o read the initial value after its own store")
      case Some((writer, seq)) =>
        if (writer == core) {
          val own = racyLast((w, core))._1
          if (seq != own) fail(core, cycle, s"$o read its own store #$seq, but its latest is #$own")
          if (racyOtherSinceOwn((core, w)))
            fail(core, cycle, s"$o read its own store #$seq after observing a later store by another core (CoRR)")
        } else {
          val seen = racySeen.getOrElse((core, w, writer), 0)
          if (seq < seen) fail(core, cycle, s"$o read core $writer's store #$seq after #$seen (CoRR)")
          racySeen((core, w, writer)) = seq
          if (racyLast.contains((w, core))) racyOtherSinceOwn += ((core, w))
        }
        racySawWrite += ((core, w))
    }

  private def counterRead(core: Int, w: BigInt, v: BigInt, o: CoreOp, cycle: Long): Unit = {
    val d = wrap(v - init(w))
    val floor = counterFloor.getOrElse((core, w), BigInt(0))
    if (d < floor) fail(core, cycle, s"$o read counter offset $d below $floor already observed or written")
    counterFloor((core, w)) = d
    counterReads(w) += ((core, d))
  }

  def store(core: Int, o: CoreOp, cycle: Long): Unit = {
    requireAligned(core, o, cycle)
    val w = wordOf(o.addr)
    val n = 1 << o.size
    val v = o.data & mask(8 * n)
    counts("store") += 1
    classOf(w) match {
      case Free => arch.write(o.addr, n, v, cycle)
      case Owned(c) =>
        if (c != core) fail(core, cycle, s"$o stores to core $c's word")
        ownedWrite(w, o.addr, n, v, cycle)
      case Racy =>
        requireFull(core, o, cycle, "racy")
        if (v == init(w) || racyOf.contains((w, v))) fail(core, cycle, s"$o: racy store values must be unique")
        val seq = racyLast.get((w, core)).map(_._1 + 1).getOrElse(1)
        racyOf((w, v)) = (core, seq)
        racyLast((w, core)) = (seq, v)
        racyOtherSinceOwn -= ((core, w))
      case Counter => fail(core, cycle, s"$o: plain store to a counter")
    }
  }

  /** `old` is the returned (sign-extended) old value; `alu` maps the raw
    * old operand to the new one with the independent reference ALU.
    */
  def amo(core: Int, o: CoreOp, old: BigInt, alu: BigInt => BigInt, cycle: Long): Unit = {
    requireAligned(core, o, cycle)
    val w = wordOf(o.addr)
    val n = 1 << o.size
    counts("amo") += 1
    classOf(w) match {
      case Free => arch.write(o.addr, n, alu(arch.read(o.addr, n)), cycle)
      case Owned(c) =>
        if (c != core) fail(core, cycle, s"$o: AMO on core $c's word")
        val raw = arch.read(o.addr, n)
        val exp = formatLoad(raw, o.size, signed = true)
        if (old != exp) fail(core, cycle, s"$o on own word returned ${hex(old)}, expected ${hex(exp)}")
        ownedWrite(w, o.addr, n, alu(raw), cycle)
      case Counter =>
        requireFull(core, o, cycle, "counter")
        if (!enumIs(o.amoFunc, BreezeAmoFunc.Add) || o.data <= 0 || o.data >= (BigInt(1) << 32))
          fail(core, cycle, s"$o: counters take AMOADD.D with a small positive increment")
        counterUpdate(core, w, old, o.data, "amo", o, cycle)
      case Racy => fail(core, cycle, s"$o: AMO on a racy word")
    }
  }

  private def counterUpdate(core: Int, w: BigInt, old: BigInt, inc: BigInt, kind: String, o: CoreOp, cycle: Long): Unit = {
    val d = wrap(old - init(w))
    val floor = counterFloor.getOrElse((core, w), BigInt(0))
    if (d < floor) fail(core, cycle, s"$o: old counter offset $d below $floor already observed or written")
    chain(w) += ((d, inc, core, kind))
    counterFloor((core, w)) = d + inc
  }

  def lr(core: Int, o: CoreOp, data: BigInt, cycle: Long): Unit = {
    counts("lr") += 1
    classOf(o.addr) match {
      case Counter | Free | Owned(`core`) => load(core, o.copy(signed = true), data, cycle)
      case other => fail(core, cycle, s"$o: LR on a $other word")
    }
    lrOf(core) = (wordOf(o.addr), data)
  }

  def sc(core: Int, o: CoreOp, success: Boolean, cycle: Long): Unit = {
    requireAligned(core, o, cycle)
    val w = wordOf(o.addr)
    val n = 1 << o.size
    counts(if (success) "scOk" else "scFail") += 1
    val lr = lrOf.remove(core)
    classOf(w) match {
      case Free => if (success) arch.write(o.addr, n, o.data & mask(8 * n), cycle)
      case Owned(c) =>
        if (c != core) fail(core, cycle, s"$o: SC on core $c's word")
        if (success) ownedWrite(w, o.addr, n, o.data & mask(8 * n), cycle)
      case Counter =>
        requireFull(core, o, cycle, "counter")
        if (!lr.exists(_._1 == w)) fail(core, cycle, s"$o: counter SC without a preceding LR of the word")
        if (success) {
          val old = lr.get._2
          val inc = wrap(o.data - old)
          if (inc == 0 || inc >= (BigInt(1) << 32)) fail(core, cycle, s"$o: counter SC must add a small increment")
          counterUpdate(core, w, old, inc, "sc", o, cycle)
        }
      case Racy => fail(core, cycle, s"$o: SC on a racy word")
    }
  }

  /** Total committed increment of a counter word. */
  def counterTotal(word: BigInt): BigInt = chain(wordOf(word)).map(_._2).sum
  def successes(word: BigInt, core: Int, kind: String): Int =
    chain(wordOf(word)).count(e => e._3 == core && e._4 == kind)

  /** Check final line contents read back through L2 (`line` -> data). */
  def finish(readBack: Map[BigInt, BigInt]): Unit = {
    def finalWord(w: BigInt): BigInt = {
      val l = lineOf(w)
      val d = readBack.getOrElse(l, throw new AssertionError(s"line ${hex(l << 5)} was not read back"))
      (d >> (8 * (w & 31).toInt)) & mask(64)
    }
    for ((w, c) <- classes.toSeq.sortBy(_._1)) {
      val v = finalWord(w)
      c match {
        case Owned(_) | Free =>
          if (v != arch.read(w, 8)) throw new AssertionError(s"word ${hex(w)} ($c): final ${hex(v)}, expected ${hex(arch.read(w, 8))}")
        case Racy =>
          val lasts = racyLast.collect { case ((`w`, _), (_, x)) => x }.toSet
          val ok = if (lasts.isEmpty) v == init(w) else lasts.contains(v)
          if (!ok) throw new AssertionError(s"racy word ${hex(w)}: final ${hex(v)} is not any core's last store " +
            s"(${lasts.map(hex).mkString(", ")}; initial ${hex(init(w))})")
        case Counter =>
          // Atomicity: sorted by old offset, every update starts where the previous ended.
          val ups = chain(w).sortBy(_._1)
          var cur = BigInt(0)
          val points = mutable.Set(cur)
          for ((d, inc, core, kind) <- ups) {
            if (d != cur) throw new AssertionError(s"counter ${hex(w)}: $kind by core $core read offset $d, " +
              s"but the chain is at $cur (lost or duplicated update)")
            cur += inc; points += cur
          }
          if (wrap(v - init(w)) != cur)
            throw new AssertionError(s"counter ${hex(w)}: final offset ${wrap(v - init(w))}, chain total $cur")
          for ((core, d) <- counterReads(w) if !points(d))
            throw new AssertionError(s"counter ${hex(w)}: core $core read offset $d, which no update produced")
      }
    }
  }
}

/** Keeps a core's request queue topped up from `next`. An LR returned by
  * `next` with `scInc` stops the feed until the LR completes; then the SC
  * with data = LR value + inc is issued and the feed waits for its result.
  */
class ProgramFeeder(core: CoreDriver, val name: String) extends CycleAgent {
  var remaining = 0
  var next: () => (CoreOp, Option[BigInt]) = () => throw new IllegalStateException(s"$name has no program")
  var successes = 0
  var failures = 0
  var stopAfterSuccesses: Option[Int] = None
  var depth = 2
  private var waitLr: Option[BigInt] = None
  private var waitSc = false
  core.onDone = done

  private def done(t: CoreTxn): Unit = {
    if (enumIs(t.op.op, L1DOp.LR) && waitLr.nonEmpty) {
      val sc = CoreOp(L1DOp.SC, t.op.addr, t.op.size, data = (t.value + waitLr.get) & mask(8 << t.op.size),
        aq = t.op.aq, rl = t.op.rl)
      waitLr = None; waitSc = true
      core.enqueue(sc)
    } else if (enumIs(t.op.op, L1DOp.SC) && waitSc) {
      waitSc = false
      if (t.value == 0) successes += 1 else failures += 1
      if (stopAfterSuccesses.exists(successes >= _)) remaining = 0
    }
  }

  protected def drive(): Unit =
    while (waitLr.isEmpty && !waitSc && remaining > 0 && core.pending.size < depth) {
      val (op, inc) = next()
      remaining -= 1
      core.enqueue(op)
      if (enumIs(op.op, L1DOp.LR) && inc.nonEmpty) waitLr = inc
    }
  def sample(): Unit = ()
  def idle: Boolean = remaining == 0 && waitLr.isEmpty && !waitSc
  override def describe: String =
    s"$name: remaining $remaining, waiting LR ${waitLr.nonEmpty}, waiting SC $waitSc, SC ok $successes fail $failures"
}
