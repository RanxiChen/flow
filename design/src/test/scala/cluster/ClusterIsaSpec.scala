package flow.cluster

import flow.config.{BreezeClusterConfig, BreezeClusterPresets, PrivilegeProfile}
import flow.memsys.MemTestKit.hex
import org.scalatest.freespec.AnyFreeSpec

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

/** riscv-tests -p- programs on the real BreezeCluster
  * (docs/tasks/CLUSTER-sim-abi.md, docs/tasks/CLUSTER-sim-tests.md).
  * The program list of each suite is the suite's Makefrag in the pinned
  * submodule, so a missing ELF is a failure rather than a silent skip.
  */
class ClusterIsaSpec extends AnyFreeSpec {
  private val root = ClusterSim.repoRoot
  private val elfDir: Path = root.resolve("design/build/riscv-tests")
  private val BuildHint = "build them with tools/build_riscv_tests.sh"
  private val notBuilt: Set[String] = {
    val f = elfDir.resolve("NOT_BUILT.txt")
    if (Files.isRegularFile(f)) Files.readAllLines(f).asScala.map(_.trim).filter(_.nonEmpty).toSet else Set.empty
  }

  private def suiteTests(suite: String): Seq[String] = {
    val frag = root.resolve(s"third_party/riscv-tests/isa/$suite/Makefrag")
    require(Files.isRegularFile(frag), s"$frag missing: git submodule update --init --recursive third_party/riscv-tests")
    val lines = Files.readAllLines(frag).asScala.toSeq
    val start = lines.indexWhere(_.trim.startsWith(s"${suite}_sc_tests"))
    require(start >= 0, s"no ${suite}_sc_tests in $frag")
    val body = lines.drop(start).takeWhile(_.trim.nonEmpty).mkString(" ")
    body.replace(s"${suite}_sc_tests", "").replace("=", " ").replace("\\", " ")
      .split("\\s+").filter(_.nonEmpty).map(t => s"$suite-p-$t").toSeq
  }

  /** p-environment failure encoding (env/p/riscv_test.h): an unexpected
    * trap ends in other_exception, which writes TESTNUM | 1337.
    */
  private def trapped(v: BigInt): Boolean = v != 1 && (v & 1337) == 1337

  /** Programs this design must not pass, with the end they must reach.
    * Each entry cites the design decision; nothing else may fail.
    */
  private val expectedUnsupported: Map[String, (String, BigInt => Boolean)] = Map(
    // Misaligned data accesses trap (L1DCache misaligned check, cause 4/6;
    // breeze-mmu-closure-checklist C2). ma_data expects hardware support and
    // has no handler, so its first test (TESTNUM 1) ends in other_exception.
    "rv64ui-p-ma_data" -> ("misaligned data access traps", v => v == (1 | 1337)),
    // Zacas is not implemented: InstDecode decodes no AMO funct5 00101, so
    // AMOCAS raises illegal instruction.
    "rv64ua-p-amocas_w" -> ("Zacas not implemented", trapped),
    "rv64ua-p-amocas_d" -> ("Zacas not implemented", trapped),
    "rv64ua-p-amocas_q" -> ("Zacas not implemented", trapped))

  private val linuxSingle: BreezeClusterConfig =
    BreezeClusterPresets.single.copy(privilegeProfile = PrivilegeProfile.Linux)
  private val linuxDual: BreezeClusterConfig =
    BreezeClusterPresets.dual.copy(privilegeProfile = PrivilegeProfile.Linux)

  private final case class Planned(name: String, label: String, run: Option[ClusterRunConfig])

  private def plan(cfg: BreezeClusterConfig, label: String, suites: Seq[String],
      backpressure: Boolean = false): Seq[Planned] =
    suites.flatMap(suiteTests).map { name =>
      val elf = elfDir.resolve(name)
      Planned(name, label, if (Files.isRegularFile(elf))
        Some(ClusterRunConfig(cfg, elf, axiBackpressure = backpressure, seed = name.hashCode)) else None)
    }

  private val Suites = Seq("rv64ui", "rv64um", "rv64ua", "rv64uc", "rv64mi", "rv64si")
  // One elaboration per cluster configuration: the single-core suites and
  // the backpressure rerun share a simulation; the dual-core run has its own.
  private lazy val singlePlan: Seq[Planned] =
    plan(linuxSingle, "single", Suites) ++ plan(linuxSingle, "single-backpressure", Seq("rv64ui", "rv64ua"), true)
  private lazy val dualPlan: Seq[Planned] = plan(linuxDual, "dual", Seq("rv64ui"))
  private def execute(p: Seq[Planned], tag: String): Map[(String, String), Option[ClusterRunResult]] = {
    val runs = p.flatMap(_.run)
    val results = if (runs.isEmpty) Map.empty[ClusterRunConfig, ClusterRunResult] else ClusterSim.runMany(runs, tag).toMap
    p.map(x => (x.label, x.name) -> x.run.map(results)).toMap
  }
  private lazy val singleResults = execute(singlePlan, "isa")
  private lazy val dualResults = execute(dualPlan, "isa")

  private def judge(label: String, suites: Seq[String],
      results: => Map[(String, String), Option[ClusterRunResult]]): Unit = {
    val names = suites.flatMap(suiteTests)
    val failures = names.flatMap { name =>
      val unsupported = expectedUnsupported.get(name)
      results((label, name)) match {
        case None if unsupported.nonEmpty && notBuilt(name) =>
          info(s"$name: not built (toolchain lacks the extension; design: ${unsupported.get._1})")
          None
        case None => Some(s"$name: ELF missing in $elfDir; $BuildHint")
        case Some(r) =>
          val v = r.tohost.headOption.flatten
          info(s"$name: ${if (r.passed) "PASS" else "FAIL"} cycles ${r.cycles} retired ${r.retired.mkString("/")}" +
            v.map(x => s" tohost ${hex(x)}").getOrElse(""))
          unsupported match {
            case Some((why, ends)) =>
              if (r.passed) Some(s"$name: expected unsupported ($why) but passed")
              else if (!v.exists(ends)) Some(s"$name: expected unsupported ($why) end not reached:\n${r.reason}")
              else { info(s"$name: unsupported as expected ($why)"); None }
            case None => if (r.passed) None else Some(r.reason)
          }
      }
    }
    if (failures.nonEmpty) fail(s"${failures.size}/${names.size} failed in $label:\n" + failures.mkString("\n\n"))
  }

  for (suite <- Suites)
    s"$suite -p- programs pass on the single-core Linux-profile cluster" in judge("single", Seq(suite), singleResults)

  "rv64ui and rv64ua pass with random AXI memory and MMIO backpressure" in
    judge("single-backpressure", Seq("rv64ui", "rv64ua"), singleResults)

  "rv64ui passes on the dual-core cluster while hart 1 is parked by RISCV_MULTICORE_DISABLE" in
    judge("dual", Seq("rv64ui"), dualResults)
}
