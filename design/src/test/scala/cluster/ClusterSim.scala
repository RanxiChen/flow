package flow.cluster

import chisel3._
import chisel3.simulator.{ChiselSim, PeekPokeAPI}
import chisel3.testing.HasTestingDirectory
import flow.bus.Axi4LiteMasterIO
import flow.coherence.ReqOp
import flow.config.BreezeClusterConfig
import flow.fpu.BreezeFpSources
import flow.interface.TracePayload
import flow.memsys.{AxiMemory, CycleAgent, GoldenMem}
import flow.memsys.MemTestKit.{hex, mask}
import flow.sim.{RawCommitEvent, RawCommitEventLogFormatter}
import flow.top.BreezeCluster
import svsim.{CommonCompilationSettings, CommonSettingsModifications}

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

/** Whole-cluster simulation (docs/tasks/CLUSTER-sim-abi.md). */
final case class ClusterRunConfig(
    cfg: BreezeClusterConfig,
    elf: Path,
    harts: Int = 1,
    resetAddr: BigInt = BigInt(0x80000000L),
    maxCycles: Long = 2000000,
    watchdog: Long = 20000,
    timerDivider: Int = 16,
    axiBackpressure: Boolean = false,
    seed: Int = 1,
    traceLog: Boolean = false)

final case class ClusterRunResult(cycles: Long, retired: Seq[Long], tohost: Seq[Option[BigInt]],
    console: String, passed: Boolean, reason: String,
    /** Accesses to MMIO addresses with no model (read 0 / write dropped). */
    unmappedMmio: Seq[String] = Seq.empty)

// =============================================================================
// ELF64 little-endian loader (PT_LOAD segments and the symbol table)
// =============================================================================

final case class ElfSegment(paddr: BigInt, data: Array[Byte], memSize: BigInt)
final case class ElfImage(path: Path, entry: BigInt, segments: Seq[ElfSegment], symbols: Map[String, BigInt]) {
  def symbol(name: String): BigInt =
    symbols.getOrElse(name, throw new IllegalArgumentException(s"$path has no symbol '$name'"))
}

object Elf64 {
  def load(path: Path): ElfImage = {
    require(Files.isRegularFile(path), s"ELF not found: $path")
    val b = Files.readAllBytes(path)
    def u(off: Long, n: Int): BigInt =
      (0 until n).foldLeft(BigInt(0))((acc, i) => acc | (BigInt(b((off + i).toInt) & 0xff) << (8 * i)))
    def i(off: Long, n: Int): Long = u(off, n).toLong
    require(b.length >= 64 && b(0) == 0x7f && b(1) == 'E' && b(2) == 'L' && b(3) == 'F', s"$path: not an ELF")
    require(b(4) == 2 && b(5) == 1, s"$path: need ELF64 little-endian")
    require(i(0x12, 2) == 243, s"$path: e_machine ${i(0x12, 2)} is not RISC-V")
    val entry = u(0x18, 8)
    val phoff = i(0x20, 8); val shoff = i(0x28, 8)
    val phentsize = i(0x36, 2); val phnum = i(0x38, 2)
    val shentsize = i(0x3a, 2); val shnum = i(0x3c, 2)
    val segments = (0L until phnum).map(k => phoff + k * phentsize).filter(p => i(p, 4) == 1).map { p =>
      val off = i(p + 8, 8); val filesz = i(p + 32, 8)
      ElfSegment(u(p + 24, 8), b.slice(off.toInt, (off + filesz).toInt), u(p + 40, 8))
    }
    val sections = (0L until shnum).map(k => shoff + k * shentsize)
    val symbols = mutable.Map.empty[String, BigInt]
    for (s <- sections if i(s + 4, 4) == 2) { // SHT_SYMTAB
      val symOff = i(s + 24, 8); val symSize = i(s + 32, 8); val entSize = i(s + 56, 8) max 24
      val strOff = i(sections(i(s + 40, 4).toInt) + 24, 8)
      for (e <- symOff until symOff + symSize by entSize) {
        val nameOff = strOff + i(e, 4)
        val name = new String(b.drop(nameOff.toInt).takeWhile(_ != 0), "US-ASCII")
        if (name.nonEmpty) symbols(name) = u(e + 8, 8)
      }
    }
    ElfImage(path, entry, segments, symbols.toMap)
  }
}

// =============================================================================
// MMIO: CLINT (machine_timer), console (soc_ctrl), everything else recorded
// =============================================================================

/** AXI-Lite slave behind the cluster's MMIO arbiter. Platform addresses come
  * from config/breeze_mcu_platform.json: machine_timer 0x0200_0000 (msip[h]
  * at +4h, mtimecmp[h] at +0x4000+8h, mtime at +0xBFF8) and soc_ctrl
  * 0x1200_0000 (every strobed written byte is a console character).
  */
class ClusterMmio(axi: Axi4LiteMasterIO, nHarts: Int, timerDivider: Int, rng: Random, backpressure: Boolean)
    extends CycleAgent {
  val name = "mmio"
  val ClintBase: BigInt = BigInt("02000000", 16)
  val ClintSize: BigInt = BigInt("10000", 16)
  val MtimecmpOff: BigInt = BigInt("4000", 16)
  val MtimeOff: BigInt = BigInt("bff8", 16)
  val ConsoleBase: BigInt = BigInt("12000000", 16)
  val ConsoleSize: BigInt = BigInt("1000", 16)

  val msip: Array[Boolean] = Array.fill(nHarts)(false)
  val mtimecmp: Array[BigInt] = Array.fill(nHarts)(mask(64))
  var mtime: BigInt = 0
  val console = new StringBuilder
  val unmapped = ArrayBuffer.empty[String]
  private var divCount = 0
  private var rPending: Option[(Long, BigInt)] = None
  private var bPending: Option[Long] = None
  private var aw: Option[BigInt] = None
  private var w: Option[(BigInt, Int)] = None
  private var arReady, awReady, wReady, rValid, bValid = false

  def mtip(h: Int): Boolean = mtime >= mtimecmp(h)
  private def latency: Int = if (backpressure) 1 + rng.nextInt(8) else 1
  private def ready: Boolean = !backpressure || rng.nextDouble() < 0.6

  private def read(a: BigInt): BigInt = {
    val base = a & ~BigInt(7)
    if (base >= ClintBase && base < ClintBase + ClintSize) {
      val off = base - ClintBase
      if (off < MtimecmpOff) {
        val h0 = (off / 4).toInt
        def bit(h: Int): BigInt = if (h < nHarts && msip(h)) BigInt(1) else BigInt(0)
        bit(h0) | (bit(h0 + 1) << 32)
      } else if (off == MtimeOff) mtime
      else {
        val h = ((off - MtimecmpOff) / 8).toInt
        if (off < MtimeOff && h < nHarts) mtimecmp(h) else { unmapped += s"R ${hex(a)} (CLINT hole)"; BigInt(0) }
      }
    } else if (base >= ConsoleBase && base < ConsoleBase + ConsoleSize) BigInt(0)
    else { unmapped += s"R ${hex(a)}"; BigInt(0) }
  }

  private def merge(old: BigInt, d: BigInt, strb: Int): BigInt =
    (0 until 8).foldLeft(old) { (v, i) =>
      if (((strb >> i) & 1) == 0) v else (v & ~(BigInt(0xff) << (8 * i))) | (((d >> (8 * i)) & 0xff) << (8 * i))
    }

  private def write(a: BigInt, d: BigInt, strb: Int): Unit = {
    val base = a & ~BigInt(7)
    if (base >= ClintBase && base < ClintBase + ClintSize) {
      val off = base - ClintBase
      if (off < MtimecmpOff) {
        val h0 = (off / 4).toInt
        if ((strb & 0x0f) != 0 && h0 < nHarts) msip(h0) = d.testBit(0)
        if ((strb & 0xf0) != 0 && h0 + 1 < nHarts) msip(h0 + 1) = d.testBit(32)
      } else if (off == MtimeOff) mtime = merge(mtime, d, strb)
      else {
        val h = ((off - MtimecmpOff) / 8).toInt
        if (off < MtimeOff && h < nHarts) mtimecmp(h) = merge(mtimecmp(h), d, strb)
        else unmapped += s"W ${hex(a)} = ${hex(d)} strb ${strb.toHexString} (CLINT hole)"
      }
    } else if (base >= ConsoleBase && base < ConsoleBase + ConsoleSize) {
      for (i <- 0 until 8 if ((strb >> i) & 1) == 1) console += ((d >> (8 * i)) & 0xff).toChar
    } else unmapped += s"W ${hex(a)} = ${hex(d)} strb ${strb.toHexString}"
  }

  protected def drive(): Unit = {
    divCount += 1
    if (divCount >= timerDivider) { divCount = 0; mtime = (mtime + 1) & mask(64) }
    arReady = rPending.isEmpty && ready
    awReady = aw.isEmpty && bPending.isEmpty && ready
    wReady = w.isEmpty && bPending.isEmpty && ready
    axi.ar.ready.poke(arReady.B); axi.aw.ready.poke(awReady.B); axi.w.ready.poke(wReady.B)
    rValid = rPending.exists(_._1 <= now)
    bValid = bPending.exists(_ <= now)
    axi.r.valid.poke(rValid.B)
    axi.r.bits.data.poke(rPending.map(_._2).getOrElse(BigInt(0)).U(64.W))
    axi.r.bits.resp.poke(0.U)
    axi.b.valid.poke(bValid.B)
    axi.b.bits.poke(0.U)
  }
  def sample(): Unit = {
    if (arReady && axi.ar.valid.peek().litToBoolean) {
      rPending = Some((now + latency, read(axi.ar.bits.addr.peek().litValue))); progress()
    }
    if (awReady && axi.aw.valid.peek().litToBoolean) { aw = Some(axi.aw.bits.addr.peek().litValue); progress() }
    if (wReady && axi.w.valid.peek().litToBoolean) {
      w = Some((axi.w.bits.data.peek().litValue, axi.w.bits.strb.peek().litValue.toInt)); progress()
    }
    if (rValid && axi.r.ready.peek().litToBoolean) { rPending = None; progress() }
    if (bValid && axi.b.ready.peek().litToBoolean) { bPending = None; progress() }
    (aw, w) match {
      case (Some(a), Some((d, s))) => write(a, d, s); bPending = Some(now + latency); aw = None; w = None
      case _ =>
    }
  }
  def idle: Boolean = rPending.isEmpty && bPending.isEmpty && aw.isEmpty && w.isEmpty
}

// =============================================================================
// Runner
// =============================================================================

/** Raised for a run failure detected by the harness; the DUT stays usable. */
final class ClusterRunFailure(msg: String) extends Exception(msg)

object ClusterSim extends PeekPokeAPI {
  private object Simulator extends ChiselSim

  private implicit val fpnewCompilationSettings: CommonSettingsModifications =
    (settings: CommonCompilationSettings) => {
      val include = BreezeFpSources.includeDir.toString
      val includes = settings.includeDirs.getOrElse(Seq.empty)
      settings.copy(includeDirs = Some((includes :+ include).distinct))
    }

  /** Repository root: the ancestor of the working directory holding .gitmodules. */
  lazy val repoRoot: Path = {
    val cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize
    Iterator.iterate(cwd)(_.getParent).takeWhile(_ != null)
      .find(p => Files.isRegularFile(p.resolve(".gitmodules")) && Files.isDirectory(p.resolve("design")))
      .getOrElse(throw new IllegalStateException(s"cannot locate the flow repository root from $cwd"))
  }

  val TohostStride = 64
  private val RingDepth = 32
  /** Diagnostics of the program in flight, kept for simulator failures. */
  private var liveReport: String => String = why => why

  def run(c: ClusterRunConfig): ClusterRunResult = runAll(c, Seq(c.elf)).head._2

  /** Runs every ELF in one elaboration: the DUT is reset and memory reloaded
    * between programs. A harness-detected failure does not stop later
    * programs; a simulator failure (RTL assertion, Verilator stop) restarts
    * the remaining programs in a fresh simulation.
    */
  def runAll(base: ClusterRunConfig, elfs: Seq[Path], tag: String = "run"): Seq[(Path, ClusterRunResult)] =
    runMany(elfs.map(e => base.copy(elf = e)), tag).map { case (c, r) => c.elf -> r }

  /** Like runAll, but each run carries its own run-time settings (harts,
    * backpressure, seed, ...). All runs must share one cluster configuration.
    */
  def runMany(runs: Seq[ClusterRunConfig], tag: String = "run"): Seq[(ClusterRunConfig, ClusterRunResult)] = {
    require(runs.nonEmpty, "no programs to run")
    val cfg = runs.head.cfg
    for (c <- runs) {
      require(c.cfg == cfg, "runMany: every run must use the same BreezeClusterConfig")
      require(c.harts >= 1 && c.harts <= cfg.nCores, s"harts ${c.harts} vs nCores ${cfg.nCores}")
    }
    val results = mutable.Map.empty[Int, ClusterRunResult]
    var remaining = runs.indices.toList
    var attempt = 0
    while (remaining.nonEmpty) {
      val dirName = s"${cfg.profileName}-$tag-$attempt".replaceAll("[^A-Za-z0-9._-]", "_")
      implicit val dir: HasTestingDirectory = new HasTestingDirectory {
        override def getDirectory: Path = repoRoot.resolve("design/build/cluster-sim").resolve(dirName)
      }
      attempt += 1
      var started = false
      try {
        Simulator.simulateRaw(new BreezeCluster(cfg, enableTandem = true)) { dut =>
          started = true
          while (remaining.nonEmpty) {
            val k = remaining.head
            results(k) = try runOne(dut, runs(k)) catch {
              case f: ClusterRunFailure => ClusterRunResult(0, Nil, Nil, "", passed = false, f.getMessage)
            }
            remaining = remaining.tail
          }
        }
      } catch {
        case t: Throwable if remaining.nonEmpty =>
          val why = s"${t.getClass.getName}: ${t.getMessage}"
          if (!started) {
            // Elaboration or Verilator build failed: no program can run.
            for (k <- remaining) results(k) = ClusterRunResult(0, Nil, Nil, "", passed = false, s"build failure: $why")
            remaining = Nil
          } else {
            // The simulator itself stopped (RTL assertion, $fatal) while
            // running remaining.head; continue the rest in a fresh simulation.
            results(remaining.head) = ClusterRunResult(0, Nil, Nil, "", passed = false,
              liveReport(s"simulator failure: $why"))
            remaining = remaining.tail
          }
      }
    }
    runs.indices.map(k => runs(k) -> results(k))
  }

  private def readEvent(t: TracePayload): RawCommitEvent = RawCommitEvent(
    valid = true,
    pc = t.pc.peek().litValue, inst = t.inst.peek().litValue, nextPc = t.nextPc.peek().litValue,
    estop = t.estop.peek().litToBoolean,
    rdWriteEn = t.rdWriteEn.peek().litToBoolean, rdAddr = t.rdAddr.peek().litValue.toInt,
    rdData = t.rdData.peek().litValue,
    memEn = t.memEn.peek().litToBoolean, memIsWrite = t.memIsWrite.peek().litToBoolean,
    memAddr = t.memAddr.peek().litValue, memAlignedAddr = t.memAlignedAddr.peek().litValue,
    memRData = t.memRData.peek().litValue, memWData = t.memWData.peek().litValue,
    memWMask = t.memWMask.peek().litValue,
    rdPending = t.rdPending.peek().litToBoolean, rdIsFp = t.rdIsFp.peek().litToBoolean)

  /** A plain (non-AMO, non-SC) store: 32-bit STORE/STORE-FP or any compressed store. */
  private def plainStore(e: RawCommitEvent): Boolean = e.memEn && e.memIsWrite && {
    val op = (e.inst & 0x7f).toInt
    if ((e.inst & 3) != 3) true else op == 0x23 || op == 0x27
  }

  private def idleInputs(dut: BreezeCluster, c: ClusterRunConfig): Unit = {
    val io = dut.io
    io.resetAddr.poke(c.resetAddr.U)
    io.time.poke(0.U)
    for (h <- 0 until c.cfg.nCores) {
      io.msip(h).poke(false.B); io.mtip(h).poke(false.B)
      io.externalInterrupts(h).poke(0.U); io.supervisorExternalInterrupts(h).poke(false.B)
    }
    io.dma.req.valid.poke(false.B); io.dma.req.bits.op.poke(ReqOp.Read); io.dma.req.bits.addr.poke(0.U)
    io.dma.req.bits.id.poke(0.U); io.dma.req.bits.mask.poke(0.U); io.dma.req.bits.data.poke(0.U)
    io.dma.rspDown.ready.poke(true.B)
    val m = io.mem
    m.ar.ready.poke(false.B); m.aw.ready.poke(false.B); m.w.ready.poke(false.B)
    m.r.valid.poke(false.B); m.r.bits.id.poke(0.U); m.r.bits.data.poke(0.U); m.r.bits.resp.poke(0.U)
    m.r.bits.last.poke(false.B); m.b.valid.poke(false.B); m.b.bits.id.poke(0.U); m.b.bits.resp.poke(0.U)
    val x = io.mmio
    x.ar.ready.poke(false.B); x.aw.ready.poke(false.B); x.w.ready.poke(false.B)
    x.r.valid.poke(false.B); x.r.bits.data.poke(0.U); x.r.bits.resp.poke(0.U)
    x.b.valid.poke(false.B); x.b.bits.poke(0.U)
  }

  private def runOne(dut: BreezeCluster, c: ClusterRunConfig): ClusterRunResult = {
    val image = try Elf64.load(c.elf) catch {
      case e: Exception => throw new ClusterRunFailure(s"cannot load ${c.elf}: ${e.getMessage}")
    }
    val tohostAddr = image.symbols.getOrElse("tohost",
      throw new ClusterRunFailure(s"${c.elf} has no 'tohost' symbol"))
    val n = c.cfg.nCores
    val rng = new Random(c.seed)
    // Zero background: a real RAM is undefined, but programs may rely on
    // zero-filled .bss beyond the loaded image.
    val mem = new GoldenMem(c.seed) { override def background(a: BigInt): Int = 0 }
    for (s <- image.segments) {
      for (k <- s.data.indices by 8) {
        val chunk = s.data.slice(k, k + 8)
        val v = chunk.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (byte, j)) => acc | (BigInt(byte & 0xff) << (8 * j)) }
        mem.write(s.paddr + k, chunk.length, v)
      }
    }
    val axi = new AxiMemory(dut.io.mem, mem, rng)
    val mmio = new ClusterMmio(dut.io.mmio, n, c.timerDivider, rng, c.axiBackpressure)
    if (c.axiBackpressure) {
      axi.minLatency = 2; axi.maxLatency = 24
      axi.arReadyProb = 0.5; axi.awReadyProb = 0.5; axi.wReadyProb = 0.6
      axi.rValidProb = 0.6; axi.bValidProb = 0.5
    }
    val agents: Seq[CycleAgent] = Seq(axi, mmio)

    idleInputs(dut, c)
    dut.reset.poke(true.B)
    dut.clock.step(5)
    dut.reset.poke(false.B)

    val retired = Array.fill(n)(0L)
    val tohost = Array.fill[Option[BigInt]](n)(None)
    val ring = Array.fill(n)(mutable.Queue.empty[String])
    val lastPc = Array.fill[BigInt](n)(-1)
    var cycle = 0L
    var lastCommit = 0L
    var verdict: Option[(Boolean, String)] = None

    def note(h: Int, s: String): Unit = { ring(h).enqueue(s); while (ring(h).size > RingDepth) ring(h).dequeue() }
    def report(why: String): String = {
      val harts = (0 until n).map { h =>
        s"hart $h: retired ${retired(h)}, last pc ${if (lastPc(h) < 0) "-" else hex(lastPc(h))}, " +
          s"tohost ${tohost(h).map(hex).getOrElse("-")}\n    " + ring(h).mkString("\n    ")
      }
      s"${c.elf.getFileName}: $why at cycle $cycle\n" + harts.mkString("\n") +
        s"\nconsole: ${mmio.console}\n${axi.describe}" +
        (if (mmio.unmapped.isEmpty) "" else s"\nunmapped MMIO (last 8): ${mmio.unmapped.takeRight(8).mkString("; ")}")
    }
    liveReport = report(_)

    try {
      while (verdict.isEmpty) {
        agents.foreach(_.drivePhase(cycle))
        dut.io.time.poke(mmio.mtime.U(64.W))
        for (h <- 0 until n) { dut.io.msip(h).poke(mmio.msip(h).B); dut.io.mtip(h).poke(mmio.mtip(h).B) }
        agents.foreach(_.decide())
        agents.foreach(_.sample())

        for (h <- 0 until n) {
          val t = dut.io.retire(h)
          if (t.lateWriteValid.peek().litToBoolean) {
            val fp = t.lateWriteIsFp.peek().litToBoolean
            note(h, s"[LATE] cycle=$cycle ${if (fp) "f" else "x"}${t.lateWriteRd.peek().litValue}=" +
              s"${hex(t.lateWriteData.peek().litValue)}${if (t.lateWriteError.peek().litToBoolean) " error" else ""}")
          }
          if (t.valid.peek().litToBoolean) {
            val e = readEvent(t)
            val line = RawCommitEventLogFormatter.format(cycle.toInt, e)
            note(h, line)
            if (c.traceLog) println(s"[hart $h] $line")
            retired(h) += 1; lastPc(h) = e.pc; lastCommit = cycle
            val slot = tohostAddr + TohostStride * h
            if (h < c.harts && tohost(h).isEmpty && plainStore(e) && e.memWMask != 0) {
              val off = e.memWMask.lowestSetBit
              val bytes = e.memWMask.bitCount
              val addr = e.memAlignedAddr + off
              if (addr >= slot && addr < slot + 8) {
                val v = ((e.memWData >> (8 * off)) & mask(8 * bytes)) << (8 * (addr - slot).toInt)
                if (v != 0) {
                  tohost(h) = Some(v)
                  if (v != 1) verdict = Some((false, report(s"hart $h wrote tohost ${hex(v)} (test ${v >> 1})")))
                }
              }
            }
          }
          if (dut.io.hartFatal(h).peek().litToBoolean) verdict = Some((false, report(s"hart $h hartFatal")))
        }
        if (verdict.isEmpty && (0 until c.harts).forall(h => tohost(h).contains(BigInt(1))))
          verdict = Some((true, s"${c.elf.getFileName}: all ${c.harts} hart(s) passed at cycle $cycle"))

        dut.clock.step()
        cycle += 1
        if (verdict.isEmpty && cycle - lastCommit > c.watchdog)
          verdict = Some((false, report(s"no commit on any hart for ${c.watchdog} cycles")))
        if (verdict.isEmpty && cycle >= c.maxCycles)
          verdict = Some((false, report(s"maxCycles ${c.maxCycles} reached")))
      }
    } catch {
      // Model protocol checks (AxiMemory/ClusterMmio) fail this program only.
      // If the simulator itself died, the probe step rethrows to runMany.
      case a: AssertionError =>
        try dut.clock.step() catch { case _: Throwable => throw a }
        verdict = Some((false, report(s"harness check: ${a.getMessage}")))
    }
    val (ok, why) = verdict.get
    ClusterRunResult(cycle, retired.toSeq, tohost.toSeq, mmio.console.toString, ok, why, mmio.unmapped.toSeq)
  }
}
