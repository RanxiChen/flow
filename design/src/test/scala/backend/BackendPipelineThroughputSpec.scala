package flow.backend

import chisel3._
import flow.config.{BreezeClusterPresets, PrivilegeProfile}
import flow.fpu.BreezeFpChiselSim
import java.nio.file.{Files, Paths}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** Same production backend; a continuously valid source and the existing
  * native-L1D contract model isolate backend throughput from instruction fetch.
  * No production RTL, frozen expectation or architectural result is changed.
  */
class BackendPipelineThroughputSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  import Instructions._
  private val cfg = BreezeClusterPresets.single.copy(privilegeProfile = PrivilegeProfile.Linux,
    coherentDma = true).coreCfg(enableTandem = true).backendCfg

  "independent ALUs, dependent ALUs and native hit loads overlap and preserve all results" in {
    simulate(new BreezeBackend(cfg, enabledebug = true)) { d =>
      val count = 64
      for (name <- Seq("alu-independent", "alu-dependent", "load-hit-model")) {
        val m = new Environment(d)
        m.reset()
        val program = (0 until count).map { i =>
          name match {
            case "alu-independent" => addi(1 + i % 16, 0, i + 1)
            case "alu-dependent" => addi(1, 1, 1)
            case _ => m.values(BigInt(i * 8)) = BigInt(1000 + i); ld(1 + i % 16, i * 8)
          }
        }
        val expectedWrites = (0 until count).map { i =>
          (if(name == "alu-dependent") 1 else 1 + i % 16) ->
            BigInt(if(name == "load-hit-model") 1000 + i else i + 1)
        }
        val pcs = program.map(m.issue)
        for (_ <- 0 until 80) m.step()
        val commits = m.events.filter(_.kind == "commit")
        commits.map(_.pc).toSeq mustBe pcs
        m.events.filter(_.kind == "gpr").map(e => e.rd -> e.data).toSeq mustBe expectedWrites
        m.all("fatal") mustBe empty
        m.all("replay") mustBe empty
        val ids = m.all("id")
        val gaps = ids.sliding(2).map(p => p(1) - p(0)).toSeq
        var pending = 0
        var maximum = 0
        for (cycle <- 0 until m.cycle) {
          pending += m.events.count(e => e.kind == "id" && e.cycle == cycle)
          maximum = maximum.max(pending)
          pending -= m.events.count(e => e.kind == "commit" && e.cycle == cycle)
        }
        pending mustBe 0
        info(s"PIPE_BACKEND name=$name count=$count id_gaps=${gaps.distinct.sorted.mkString("/")} " +
          s"commit_span=${commits.last.cycle - commits.head.cycle} max_inflight=$maximum")
        sys.env.get("PIPELINE_EVIDENCE").foreach { root =>
          val path = Paths.get(root, s"backend-$name.csv")
          Files.createDirectories(path.getParent)
          val w = new java.io.PrintWriter(Files.newBufferedWriter(path))
          w.println("cycle,event,pc,rd,data")
          m.events.foreach(e => w.println(s"${e.cycle},${e.kind},${e.pc.toString(16)},${e.rd},${e.data.toString(16)}"))
          w.close()
        }
        // A one-instruction-per-cycle contract is checked independently of
        // the result scoreboard; a serial implementation must fail this gate.
        withClue(name) {
          gaps mustBe Seq.fill(count - 1)(1)
          maximum must be >= 4
          commits.sliding(2).map(p => p(1).cycle - p(0).cycle).toSeq mustBe Seq.fill(count - 1)(1)
        }
      }
    }
  }
}
