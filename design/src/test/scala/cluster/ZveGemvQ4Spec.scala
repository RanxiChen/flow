package flow.cluster

import flow.config.{BreezeClusterPresets, PrivilegeProfile}
import org.scalatest.freespec.AnyFreeSpec
import java.nio.file.Files

/** §9 software kernel measurement, with cycle/readback values from the ELF. */
class ZveGemvQ4Spec extends AnyFreeSpec {
  "scalar and R07 vector Q4_0 x Q8_0 GEMV agree with the f32 reference" in {
    val root = ClusterSim.repoRoot
    val symbols = Seq("benchmark_cycles", "benchmark_blocks", "benchmark_weight_bytes", "benchmark_max_error")
    val jobs = Seq(
      ("scalar", BreezeClusterPresets.single.copy(privilegeProfile = PrivilegeProfile.Linux)),
      ("vector", BreezeClusterPresets.singleZve32f.copy(privilegeProfile = PrivilegeProfile.Linux)),
      ("vector_n64", BreezeClusterPresets.singleZve32f.copy(privilegeProfile = PrivilegeProfile.Linux)),
      ("vector_4acc", BreezeClusterPresets.singleZve32f.copy(privilegeProfile = PrivilegeProfile.Linux)))
    val selected = sys.props.get("gemv.only").map(only => jobs.filter(_._1 == only)).getOrElse(jobs)
    require(selected.nonEmpty, "gemv.only must name a benchmark variant")
    val runs = selected.map { case (name, cfg) =>
      val elf = root.resolve(s"tests/cluster/build/zve_gemv_q4_$name.elf")
      require(Files.isRegularFile(elf), s"missing $elf")
      val c = ClusterRunConfig(cfg = cfg, elf = elf, maxCycles = 5000000,
        watchdog = 100000, observeBackend = true, readSymbols = symbols)
      name -> ClusterSim.run(c)
    }
    for ((name, r) <- runs) {
      info(s"$name pass=${r.passed} totalCycles=${r.cycles} " +
        s"kernelCycles=${r.readback.getOrElse("benchmark_cycles", BigInt(-1))} " +
        s"blocks=${r.readback.getOrElse("benchmark_blocks", BigInt(-1))} " +
        s"weightBytes=${r.readback.getOrElse("benchmark_weight_bytes", BigInt(-1))} " +
        s"maxErrorBits=${r.readback.getOrElse("benchmark_max_error", BigInt(-1)).toString(16)} " +
        s"replay=${r.replayCounts.mkString(",")} soloCycles=${r.soloCycles} " +
        s"zvePerf=${r.zvePerf.toSeq.sortBy(_._1).mkString(",")} ${r.reason}")
      assert(r.passed, s"$name: ${r.reason}")
      if (name.startsWith("vector"))
        assert(r.replayCounts.lift(6).contains(0L), s"$name: vector verdict replay")
    }
  }
}
