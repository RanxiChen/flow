package flow.memsys

import chisel3._
import chisel3.simulator.PeekPokeAPI
import scala.collection.mutable

/** Shared constants and reference helpers for the memory-system tests. */
object MemTestKit {
  val LineBytes = 32
  val MainRam: BigInt = BigInt("80000000", 16)
  val Rom: BigInt = BigInt("10000000", 16)
  val Device: BigInt = BigInt("12001000", 16)
  val Hole: BigInt = BigInt("40000000", 16)

  def lineOf(a: BigInt): BigInt = a >> 5
  def mask(bits: Int): BigInt = (BigInt(1) << bits) - 1
  def signExtend(v: BigInt, bits: Int): BigInt =
    if (bits >= 64) v & mask(64)
    else if (v.testBit(bits - 1)) (v | (mask(64) ^ mask(bits))) & mask(64)
    else v
  /** Load result as l1d-rtl-spec 5.4 formats it from the addressed bytes. */
  def formatLoad(raw: BigInt, size: Int, signed: Boolean, flw: Boolean = false): BigInt = {
    if (flw) return (mask(32) << 32) | (raw & mask(32))
    val bits = 8 << size
    val v = raw & mask(bits)
    if (signed) signExtend(v, bits) else v
  }
  def hex(v: BigInt): String = "0x" + v.toString(16)
  def enumIs(e: Data, lit: Data): Boolean = e.litValue == lit.litValue
}

/** Byte-addressed reference memory with a deterministic background and
  * per-line write history. Two instances with the same seed start equal.
  */
class GoldenMem(seed: Int) {
  import MemTestKit._
  private val bytes = mutable.Map.empty[BigInt, Int]
  private val history = mutable.Map.empty[BigInt, mutable.ArrayBuffer[(Long, BigInt)]]
  val touched: mutable.Set[BigInt] = mutable.Set.empty[BigInt]

  def background(a: BigInt): Int = {
    var x = a.toLong * 0x9E3779B97F4A7C15L + seed
    x ^= x >>> 29; x *= 0xBF58476D1CE4E5B9L; x ^= x >>> 32
    (x & 0xff).toInt
  }
  def byte(a: BigInt): Int = bytes.getOrElse(a, background(a))
  def read(a: BigInt, n: Int): BigInt =
    (0 until n).foldLeft(BigInt(0))((acc, i) => acc | (BigInt(byte(a + i)) << (8 * i)))
  def line(l: BigInt): BigInt = read(l << 5, LineBytes)

  private def record(lines: Seq[BigInt], cycle: Long)(update: => Unit): Unit = {
    lines.foreach(l => history.getOrElseUpdate(l, mutable.ArrayBuffer((-1L, line(l)))))
    update
    lines.foreach { l => touched += l; history(l) += ((cycle, line(l))) }
  }
  def write(a: BigInt, n: Int, v: BigInt, cycle: Long = 0): Unit =
    record((0 to (lineOf(a + n - 1) - lineOf(a)).toInt).map(lineOf(a) + _), cycle) {
      for (i <- 0 until n) bytes(a + i) = ((v >> (8 * i)) & 0xff).toInt
    }
  def writeLine(l: BigInt, data: BigInt, cycle: Long = 0): Unit = write(l << 5, LineBytes, data, cycle)
  def writeMasked(l: BigInt, byteMask: BigInt, data: BigInt, cycle: Long = 0): Unit =
    record(Seq(l), cycle) {
      for (b <- 0 until LineBytes if byteMask.testBit(b)) bytes((l << 5) + b) = ((data >> (8 * b)) & 0xff).toInt
    }
  /** The line held `v` at some time in [from, to]. */
  def heldDuring(l: BigInt, from: Long, to: Long, v: BigInt): Boolean = history.get(l) match {
    case None => v == line(l)
    case Some(h) =>
      val start = h.lastIndexWhere(_._1 < from) max 0
      h.drop(start).takeWhile(_._1 <= to).exists(_._2 == v)
  }
}

/** One cycle-level participant. Each cycle the bench calls every agent's
  * `drive` (pokes that do not depend on this cycle's outputs), then
  * `decide` (pokes that do), then `sample` (peeks and handshakes).
  */
trait CycleAgent extends PeekPokeAPI {
  def name: String
  protected var now: Long = 0
  private var moved = false
  protected def progress(): Unit = moved = true
  def takeProgress(): Boolean = { val m = moved; moved = false; m }
  def check(cond: Boolean, msg: => String): Unit =
    if (!cond) throw new AssertionError(s"[$name @ cycle $now] $msg")
  final def drivePhase(cycle: Long): Unit = { now = cycle; drive() }
  protected def drive(): Unit
  def decide(): Unit = ()
  def sample(): Unit
  def idle: Boolean
  def describe: String = name
}

class Bench(clock: Clock, val agents: Seq[CycleAgent], val watchdog: Int = 4000) extends PeekPokeAPI {
  var cycle = 0L
  private var lastProgress = 0L
  def step(): Unit = {
    agents.foreach(_.drivePhase(cycle))
    agents.foreach(_.decide())
    agents.foreach(_.sample())
    clock.step()
    cycle += 1
    if (agents.map(_.takeProgress()).exists(identity) || agents.forall(_.idle)) lastProgress = cycle
    if (cycle - lastProgress > watchdog)
      throw new AssertionError(s"no progress for $watchdog cycles at cycle $cycle\n" +
        agents.map(_.describe).mkString("\n"))
  }
  def steps(n: Int): Unit = for (_ <- 0 until n) step()
  def runUntil(cond: => Boolean, limit: Int = 400000): Unit = {
    val start = cycle
    while (!cond) {
      step()
      if (cycle - start > limit) throw new AssertionError(s"condition not reached in $limit cycles\n" +
        agents.map(_.describe).mkString("\n"))
    }
  }
  def quiesce(): Unit = { runUntil(agents.forall(_.idle)); steps(4); runUntil(agents.forall(_.idle)) }
}
