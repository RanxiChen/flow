package flow.cluster

import java.nio.file.{Files, Paths}
import flow.config.{BreezeClusterPresets, PrivilegeProfile}
import org.scalatest.freespec.AnyFreeSpec

/** Real OpenSBI firmware and real scalar RTL, separate from Linux runtime proof. */
class BreezePmuSbiSpec extends AnyFreeSpec {
  "real OpenSBI repeatedly rearms generic cycles/instructions and a frontend raw event" in {
    val elf = Paths.get(sys.props.getOrElse("flow.pmu.sbi.elf", "../tests/cluster/build/pmu-sbi/pmu_sbi.elf"))
    assert(Files.isRegularFile(elf), s"missing $elf: build tests/cluster/pmu_sbi with OpenSBI 1.9")
    val cfg = BreezeClusterPresets.single.copy(privilegeProfile=PrivilegeProfile.Linux)
    // The production PMU DT covers 272 raw IDs using 12 masked rows.
    // OpenSBI initialization is substantial. Keep a bounded budget and optional
    // commit logging so progress is visible without weakening the self-check.
    val result = ClusterSim.run(ClusterRunConfig(cfg=cfg,elf=elf,maxCycles=10000000,
      traceLog=sys.props.get("flow.pmu.sbi.trace").contains("true")))
    info(s"cycles=${result.cycles} retired=${result.retired.mkString(",")} reason=${result.reason}\n${result.console}")
    assert(result.passed, s"${result.reason}\n${result.console}")
    for(event <- Seq("cycles","instructions","icache-miss","all"))
      assert(result.console.contains(s"PMU_SBI $event PASS"), s"missing $event marker: ${result.console}")
  }
}
