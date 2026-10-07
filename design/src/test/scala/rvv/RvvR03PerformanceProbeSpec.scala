package flow.rvv

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import java.nio.file.{Files,Paths}

/** Diagnostic parameters only. All default P1-P5 cases remain in the R02 suite. */
class RvvR03PerformanceProbeSpec extends AnyFreeSpec with ChiselSim {
  for((name,macDepth,scoreDepth) <- Seq(("mac12-sb16",12,16),("mac12-sb24",12,24),("mac2-sb24",2,24))) {
    s"$name diagnoses the latency window without changing defaults" in {
      val fixture=R02Fixture.load(Paths.get(sys.env("R02_FIXTURES")).resolve("gemv/fixture.json"))
      val p=RvvParams(unitDepths=Seq(4,2,macDepth,2,2),scoreboardDepth=scoreDepth)
      simulate(new RvvCoprocessor(p)) { dut =>
        val m=new RvvProtocolDriver(dut,p,fixture,0,randomize=false,readLatency=40,
          writeLatency=1,translationLatency=2,injectKills=false).run()
        assert(m.kernelReadBeats==896 && m.linkDelays.size==224 && m.requestAdvances.size==223)
        val window=m.kernelLastRead-m.kernelFirstRead+1
        val result=s"""{"probe":"$name","macDepth":$macDepth,"scoreboardDepth":$scoreDepth,"peakFraction":${m.kernelReadBeats.toDouble/window},"readWindowCycles":$window,"kernelCycles":${m.kernelLastB-m.kernelFirstIssue+1},"bufferedBytesAtKernelB":${m.kernelRemaining},"linkMax":${m.linkDelays.max},"advanceMin":${m.requestAdvances.min}}"""
        val out=Paths.get(sys.env("R03_PROBES")); Files.createDirectories(out)
        Files.writeString(out.resolve(s"$name.json"),result+"\n")
        println(s"R03_PARAMETER_PROBE $result")
      }
    }
  }
}
