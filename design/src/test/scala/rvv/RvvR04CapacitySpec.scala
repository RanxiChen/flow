package flow.rvv

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import java.nio.file.Paths

/** Additional measurements; neither replaces the prescribed default gates. */
class RvvR04CapacitySpec extends AnyFreeSpec with ChiselSim {
  for((viq,descriptors) <- Seq((64,32),(128,64))) {
    s"capacity viq=$viq descriptors=$descriptors measures latency40 without changing golden checks" in {
      val f=R02Fixture.load(Paths.get(sys.env("R02_FIXTURES"),"gemv/fixture.json"))
      val p=RvvParams(viqDepth=viq,memoryInflight=descriptors)
      simulate(new RvvCoprocessor(p)) { dut =>
        val m=new RvvProtocolDriver(dut,p,f,0,randomize=false,readLatency=40,
          writeLatency=1,translationLatency=2,injectKills=false).run()
        assert(m.kernelReadBeats==896 && m.r04Links.size==224 && m.linkDelays.size==224 && m.requestAdvances.size==223)
        println(s"R04_CAPACITY viq=$viq descriptors=$descriptors peakFraction=${m.kernelReadBeats.toDouble/(m.kernelLastRead-m.kernelFirstRead+1)} kernelCycles=${m.kernelLastB-m.kernelFirstIssue+1} linkMax=${m.r04Links.max} advanceMin=${m.requestAdvances.min} prefetchDepth=${m.prefetchDepth} peakBuffer=${m.peakBuffered}")
      }
    }
  }
}
