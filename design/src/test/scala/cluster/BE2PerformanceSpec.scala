package flow.cluster

import flow.config.{BreezeClusterPresets, PrivilegeProfile}
import java.nio.file.Paths
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** Matching cold-start firmware; original Fibonacci main/runtime/pass marker.
  * Observation ports exist only in this explicitly selected simulation mode.
  */
class BE2PerformanceSpec extends AnyFreeSpec with Matchers {
  "P1 matched cold programs and original Fibonacci firmware" in {
    val root = ClusterSim.repoRoot
    val cfg = BreezeClusterPresets.single.copy(privilegeProfile=PrivilegeProfile.Linux)
    val names = Seq("lrsc_amo_single", "trap_misc", "mmio_console", "fibonacci")
    val runs = names.take(3).map(n => ClusterRunConfig(cfg,
      root.resolve(s"tests/cluster/build/$n.elf"), observeBackend=true)) :+
      ClusterRunConfig(cfg, Paths.get(sys.env("BE2_FIB_ELF")), resetAddr=BigInt(0x10000000),
        tohostSymbol="__breeze_result", passValue=BigInt("425245455a455001",16), observeBackend=true)
    val results = ClusterSim.runMany(runs, "be2-p1")
    for((name,(_,result)) <- names.zip(results)) {
      withClue(s"$name: ${result.reason}") { result.passed mustBe true }
      if(name=="mmio_console") result.console mustBe "breeze cluster console ok\nX\n"
      info(s"P1 $name cycles=${result.cycles} retired=${result.retired.mkString("/")} " +
        s"replay=${result.replayCounts.mkString("/")} soloWB=${result.soloCycles}")
    }
  }
}
