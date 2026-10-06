package flow.memsys

import scala.collection.mutable

/** Pure-Scala verification infrastructure for the memory system (v1-mem-plan step 5).
  * No Chisel here: models and checkers are unit-tested on their own (CheckersSpec).
  */

/** Line-granular coherent memory with version history.
  *
  * The value of a line changes only when a writer holding M writes it (or a
  * DMA MaskWrite completes). Readers check against the current value; a
  * non-coherent Read (L1I/DMA) may return any version written since it was
  * issued, so versions are kept.
  */
final class GoldenMemory(lineBytes: Int, init: BigInt => BigInt) {
  private val versions = mutable.Map.empty[BigInt, mutable.ArrayBuffer[BigInt]]
  private def hist(line: BigInt) = versions.getOrElseUpdate(line, mutable.ArrayBuffer(init(line)))

  def current(line: BigInt): BigInt = hist(line).last
  def version(line: BigInt): Int = hist(line).size - 1
  def write(line: BigInt, value: BigInt): Unit = hist(line) += value

  /** Byte-masked update (MaskWrite, or an L1D store merged into a line). */
  def writeMasked(line: BigInt, data: BigInt, mask: BigInt): Unit = {
    var v = current(line)
    for (b <- 0 until lineBytes if mask.testBit(b)) {
      val m = BigInt(0xff) << (8 * b)
      v = (v & ~m) | (data & m)
    }
    write(line, v)
  }

  /** True when `value` equals some version >= `since`. */
  def seenSince(line: BigInt, since: Int, value: BigInt): Boolean = hist(line).drop(since).contains(value)

  def lines: Iterable[BigInt] = versions.keys
}

/** 8 B word view of the same idea, for L1D single-core tests driven through the backend port. */
final class GoldenWords(init: BigInt => BigInt) {
  private val versions = mutable.Map.empty[BigInt, mutable.ArrayBuffer[BigInt]]
  private def hist(word: BigInt) = versions.getOrElseUpdate(word, mutable.ArrayBuffer(init(word)))
  def current(word: BigInt): BigInt = hist(word).last
  def version(word: BigInt): Int = hist(word).size - 1
  def store(word: BigInt, data: BigInt, byteMask: Int): Unit = {
    var v = current(word)
    for (b <- 0 until 8 if (byteMask >> b & 1) == 1) {
      val m = BigInt(0xff) << (8 * b)
      v = (v & ~m) | (data & m)
    }
    hist(word) += v
  }
  def seenSince(word: BigInt, since: Int, value: BigInt): Boolean = hist(word).drop(since).contains(value)
  def valueAt(word: BigInt, v: Int): BigInt = hist(word)(v)
}

/** Local line state as seen by an agent, in protocol order. */
object LState extends Enumeration { val I, S, E, M = Value }

/** Single-Writer / Multiple-Reader and data-value invariant across caches.
  *
  * Called every cycle with each agent's view of its lines. A line may have
  * one E/M copy and no S copies, or only S copies. Every valid copy must hold
  * the golden value. Copies are reported in the state the agent acts on: an
  * agent that has answered an Inv is already I.
  */
final class SwmrMonitor(golden: GoldenMemory) {
  def check(cycle: Long, copies: Seq[(Int, BigInt, LState.Value, BigInt)]): Unit = {
    for ((line, cs) <- copies.groupBy(_._2)) {
      val owners = cs.filter(c => c._3 == LState.E || c._3 == LState.M)
      val sharers = cs.filter(_._3 == LState.S)
      if (owners.size > 1 || owners.nonEmpty && sharers.nonEmpty)
        throw new AssertionError(
          f"SWMR violated at cycle $cycle line 0x$line%x: " +
            cs.map(c => s"agent${c._1}=${c._3}").mkString(", "))
      for ((agent, _, st, data) <- cs if st != LState.I && data != golden.current(line))
        throw new AssertionError(
          f"stale copy at cycle $cycle: agent$agent line 0x$line%x state $st holds 0x$data%x, golden 0x${golden.current(line)}%x")
    }
  }
}

/** Every open transaction must close within `limit` cycles; the whole system
  * must make progress (some message fires) within `idleLimit` cycles while
  * anything is open. Failure messages carry a dump for debugging deadlocks.
  */
final class Watchdog(limit: Int, idleLimit: Int) {
  private val open = mutable.LinkedHashMap.empty[String, Long]
  private var lastProgress = 0L

  def start(key: String, cycle: Long): Unit = {
    if (open.contains(key)) throw new AssertionError(s"watchdog: transaction $key opened twice")
    open(key) = cycle
  }
  def finish(key: String): Unit = {
    if (open.remove(key).isEmpty) throw new AssertionError(s"watchdog: closing unknown transaction $key")
  }
  def progress(cycle: Long): Unit = lastProgress = cycle
  def isOpen(key: String): Boolean = open.contains(key)
  def openCount: Int = open.size

  def tick(cycle: Long, dump: => String): Unit = {
    for ((k, t) <- open if cycle - t > limit)
      throw new AssertionError(s"watchdog: $k open for ${cycle - t} cycles (limit $limit)\n$dump")
    if (open.nonEmpty && cycle - lastProgress > idleLimit)
      throw new AssertionError(
        s"watchdog: no progress for ${cycle - lastProgress} cycles with ${open.size} open: " +
          open.keys.mkString(", ") + s"\n$dump")
  }
}

/** Deterministic, seed-reported randomness for reproducible failures. */
final class Rng(val seed: Long) {
  private val r = new scala.util.Random(seed)
  def chance(p: Double): Boolean = r.nextDouble() < p
  def int(n: Int): Int = r.nextInt(n)
  def pick[T](xs: Seq[T]): T = xs(r.nextInt(xs.size))
  def bits(n: Int): BigInt = BigInt(n, r)
}
