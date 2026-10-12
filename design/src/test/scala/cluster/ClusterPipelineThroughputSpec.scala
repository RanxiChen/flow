package flow.cluster

import flow.config.{BreezeClusterPresets, PrivilegeProfile}
import java.nio.file.{Files, Paths}
import scala.collection.mutable.ArrayBuffer
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** Deployment configuration, including Linux RVC/MMU frontend and coherent
  * DMA. The firmware executes in M/Bare; this is not an Sv39 userspace test.
  */
class ClusterPipelineThroughputSpec extends AnyFreeSpec with Matchers {
  "warm deployment instruction streams report issue and retire throughput independently of functional PASS" in {
    val root = Paths.get(sys.env("PIPELINE_EVIDENCE"))
    val names = Seq("alu-independent", "alu-dependent", "load-hit")
    val cfg = BreezeClusterPresets.single.copy(privilegeProfile = PrivilegeProfile.Linux, coherentDma = true)
    val runs = names.map { name =>
      ClusterRunConfig(cfg, root.resolve(s"$name.elf"), observeBackend = true,
        pipelineLog = Some(root.resolve(s"cluster-$name.csv")))
    }
    val results = ClusterSim.runMany(runs, "pipeline-probe")
    val summary = new java.io.PrintWriter(Files.newBufferedWriter(root.resolve("cluster-summary.csv")))
    summary.println("case,pass,retired,span,ipc,id_count,ex_count,commit_count,distinct_ex_commit_overlap,load_miss,store_miss,l2_reads,replays")
    val slow = ArrayBuffer.empty[String]
    try {
      for ((name, (run, result)) <- names.zip(results)) {
        val image = Elf64.load(run.elf)
        val begin = image.symbol("probe_begin")
        val end = image.symbol("probe_end")
        val source = scala.io.Source.fromFile(root.resolve(s"cluster-$name.csv").toFile)
        val rows = try source.getLines().drop(1).map(_.split(",")).toVector finally source.close()
        def isWindow(pc: String): Boolean = { val p = BigInt(pc, 16); p >= begin && p < end }
        val observed = rows.filter(r => r(7) == "1" && isWindow(r(8)))
        // Every pass must execute each of the 64 distinct sequential PCs once.
        observed.size mustBe 192
        observed.map(r => BigInt(r(8),16)) mustBe Vector.fill(3)((0 until 64).map(i => begin + 4*i)).flatten
        val warm = observed.drop(64)
        val first = warm.head(0).toLong
        val last = warm.last(0).toLong
        // Two separate branch-free windows; do not charge loop checks/branches
        // to the steady-state retirement span.
        val spans = warm.grouped(64).map(g => g.last(0).toLong - g.head(0).toLong + 1).toVector
        val span = spans.sum
        val ipc = 128.0 / span
        val active = rows.filter(r => r(0).toLong >= first && r(0).toLong <= last)
        val ids = active.count(_(2) == "1")
        val exs = active.count(r => r(3) == "1" && isWindow(r(4)))
        val commits = active.count(r => r(5) == "1" && isWindow(r(6)))
        val overlap = active.count(r => r(3) == "1" && r(5) == "1" &&
          isWindow(r(4)) && isWindow(r(6)) && r(4) != r(6))
        val loadMisses = active.map(_(19).toInt).sum
        val storeMisses = active.map(_(20).toInt).sum
        val misses = loadMisses + storeMisses
        val reads = active.map(_(21).toInt).sum
        val replays = active.map(_(15).toInt).sum
        summary.println(s"$name,${result.passed},128,$span,$ipc,$ids,$exs,$commits,$overlap,$loadMisses,$storeMisses,$reads,$replays")
        summary.flush()
        info(s"PIPE_CLUSTER name=$name functional=${result.passed} warm_retired=128 span=$span ipc=$ipc " +
          s"distinct_ex_commit_overlap=$overlap misses=$misses l2_reads=$reads replay=$replays")
        withClue(result.reason) { result.passed mustBe true }
        if (name == "load-hit") misses mustBe 0
        // A diagnostic threshold, not a rewrite of any frozen P1 golden:
        // a warmed, unrolled independent ALU stream should sustain >=0.5 IPC.
        if (name == "alu-independent" && ipc < 0.5) slow += s"$name IPC=$ipc < diagnostic target 0.5"
      }
    } finally summary.close()
    withClue("Functional PASS does not establish pipelining: " + slow.mkString("; ")) { slow mustBe empty }
  }
}
