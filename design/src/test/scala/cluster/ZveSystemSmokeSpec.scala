package flow.cluster

import java.nio.file.{Files, Path}

import flow.config.{BreezeClusterPresets, PrivilegeProfile}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** single-zve32f integration smoke (docs/tasks/ZVE32F-single-decoupled.md §9).
  * One elaboration: the directed vector smoke plus scalar programs that must
  * be unaffected by the vector mount, TLB sharing and the L2 vector port.
  * Directed verification and conformance are separate follow-up suites. */
class ZveSystemSmokeSpec extends AnyFreeSpec with Matchers {
  private val root = ClusterSim.repoRoot
  private val cfg = BreezeClusterPresets.singleZve32f.copy(privilegeProfile = PrivilegeProfile.Linux)
  private def required(paths: Seq[Path]): Seq[Path] = {
    val missing = paths.filterNot(Files.isRegularFile(_))
    require(missing.isEmpty, s"missing regression ELFs: ${missing.mkString(", ")}")
    paths
  }

  "single-zve32f runs the vector smoke and unchanged scalar programs" in {
    val smoke = root.resolve("tests/cluster/build/zve_smoke.elf")
    require(Files.isRegularFile(smoke), s"missing $smoke")
    val cluster = required(Seq("fencei_smc", "sv39_basic", "sv39_ptw_cache", "trap_misc", "lrsc_amo_single")
      .map(n => root.resolve(s"tests/cluster/build/$n.elf")))
    val isaDir = root.resolve("design/build/riscv-tests")
    val isa = required(Seq("rv64ui-p-add", "rv64ui-p-ld", "rv64ui-p-sd", "rv64ui-p-sb", "rv64ui-p-lbu",
      "rv64um-p-mul", "rv64um-p-div", "rv64ua-p-amoadd_d", "rv64ua-p-lrsc", "rv64uf-p-fadd",
      "rv64ud-p-fmadd", "rv64si-p-csr", "rv64mi-p-csr").map(n => isaDir.resolve(n)))
    val elfs = smoke +: (cluster ++ isa)
    val base = ClusterRunConfig(cfg = cfg, elf = smoke, harts = 1, maxCycles = 400000,
      observeBackend = true)
    val runs = elfs.map { elf =>
      base.copy(elf = elf, pipelineLog = if (elf == smoke)
        Some(root.resolve("design/build/cluster-sim/zve-smoke-pipeline.csv")) else None)
    }
    val results = ClusterSim.runMany(runs, tag = "zve-smoke").map { case (c, r) => c.elf -> r }
    val lines = results.map { case (p, r) =>
      s"${p.getFileName}: ${if (r.passed) "PASS" else "FAIL"} cycles=${r.cycles} replay=${r.replayCounts.mkString(",")} tohost=${r.tohost.map(_.map(_.toString(16)))} ${r.reason}"
    }
    info(lines.mkString("\n"))
    val failed = results.collect { case (p, r) if !r.passed => s"${p.getFileName}: ${r.reason}\n${r.console}" }
    withClue(failed.mkString("\n\n")) { failed mustBe empty }
    withClue("vector smoke verdict replay") { results.head._2.replayCounts(6) mustBe 0L }
  }
}
