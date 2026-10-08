package flow.cluster

import flow.config.{BreezeClusterPresets, PrivilegeProfile}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** SOC-3b board-preparation subset: existing self-checking programs,
  * one cluster elaboration, no changes to their pass/fail or watchdogs.
  */
class ClusterWbSplitSmokeSpec extends AnyFreeSpec with Matchers {
  "SOC3b single-core atomic, trap and MMIO programs" in {
    val root = ClusterSim.repoRoot
    val cfg = BreezeClusterPresets.single.copy(privilegeProfile = PrivilegeProfile.Linux)
    val names = Seq("lrsc_amo_single", "trap_misc", "mmio_console")
    val runs = names.map { name =>
      ClusterRunConfig(cfg, root.resolve(s"tests/cluster/build/$name.elf"))
    }
    val results = ClusterSim.runMany(runs, "soc3b-smoke")
    results.size mustBe names.size
    for ((name, (_, result)) <- names.zip(results)) {
      info(s"$name: passed=${result.passed} cycles=${result.cycles} retired=${result.retired.mkString("/")}")
      withClue(s"$name: ${result.reason}\n${result.console}") { result.passed mustBe true }
      if (name == "mmio_console") result.console mustBe "breeze cluster console ok\nX\n"
    }
  }
}
