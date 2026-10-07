package flow.cluster

import java.nio.file.{Files, Path, Paths}

import flow.config.{BreezeClusterConfig, BreezeClusterPresets, PrivilegeProfile}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** Self-checking programs from tests/cluster on the real BreezeCluster
  * (docs/tasks/CLUSTER-sim-abi.md, program table in
  * docs/tasks/CLUSTER-sim-program-table.md). One elaboration per
  * configuration group; every program in the group must pass.
  */
class ClusterProgramSpec extends AnyFreeSpec with Matchers {
  private val singlePrograms = Seq("fencei_smc", "sv39_basic", "sv39_ptw_cache", "trap_misc",
    "mmio_console", "timer_irq", "lrsc_amo_single")
  private val multiPrograms = Seq("mh_amo_lock", "mh_lrsc_counter", "mh_message_pass", "mh_ipi",
    "mh_sv39", "mh_fencei")
  private val consoleExpected = Map("mmio_console" -> "breeze cluster console ok\nX\n")

  private lazy val buildDir: Path =
    Seq("../tests/cluster/build", "tests/cluster/build").map(Paths.get(_)).find(Files.isDirectory(_))
      .getOrElse(fail("tests/cluster/build not found: run `make -C tests/cluster` (RISCV_PREFIX=...)"))

  private def elf(name: String): Path = {
    val p = buildDir.resolve(s"$name.elf")
    if (!Files.isRegularFile(p)) fail(s"missing $p: run `make -C tests/cluster`")
    p
  }

  private def linux(c: BreezeClusterConfig): BreezeClusterConfig = c.copy(privilegeProfile = PrivilegeProfile.Linux)

  private def describe(name: String, r: ClusterRunResult): String = {
    val codes = r.tohost.zipWithIndex.map {
      case (Some(v), h) if v == 1 => s"h$h=pass"
      case (Some(v), h) => s"h$h=fail(${v >> 1})"
      case (None, h) => s"h$h=none"
    }.mkString(" ")
    s"$name: ${if (r.passed) "PASS" else "FAIL"} cycles=${r.cycles} retired=${r.retired.mkString("/")} " +
      s"$codes ${r.reason}"
  }

  private def group(title: String, base: ClusterRunConfig, names: Seq[String]): Unit =
    title in {
      val elfs = names.map(elf)
      val results = ClusterSim.runAll(base.copy(elf = elfs.head), elfs).map(_._2)
      results.size mustBe names.size
      val lines = names.zip(results).map { case (n, r) => describe(n, r) }
      info(lines.mkString("\n"))
      val failed = names.zip(results).collect { case (n, r) if !r.passed => describe(n, r) + "\nconsole:\n" + r.console }
      withClue(failed.mkString("\n\n")) { failed mustBe empty }
      for ((n, r) <- names.zip(results); text <- consoleExpected.get(n.stripSuffix("_2").stripSuffix("_4")))
        withClue(s"$n console: ") { r.console mustBe text }
    }

  private def placeholder: Path = Paths.get("unused.elf")

  group("single-hart system programs, single core, Linux profile",
    ClusterRunConfig(cfg = linux(BreezeClusterPresets.single), elf = placeholder, harts = 1),
    singlePrograms)

  group("multi-hart programs, dual (2 harts), Linux profile",
    ClusterRunConfig(cfg = linux(BreezeClusterPresets.dual), elf = placeholder, harts = 2,
      maxCycles = 4000000),
    multiPrograms.map(_ + "_2"))

  group("multi-hart programs, small (4 harts), Linux profile",
    ClusterRunConfig(cfg = linux(BreezeClusterPresets.small), elf = placeholder, harts = 4,
      maxCycles = 6000000),
    multiPrograms.map(_ + "_4"))

  group("multi-hart programs, small (4 harts), AXI backpressure",
    ClusterRunConfig(cfg = linux(BreezeClusterPresets.small), elf = placeholder, harts = 4,
      maxCycles = 8000000, axiBackpressure = true, seed = 7),
    multiPrograms.map(_ + "_4"))
}
