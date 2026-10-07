package flow.rvv

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import java.nio.file.{Files,Paths}

class RvvPerformanceSpec extends AnyFreeSpec with ChiselSim {
  for((name,latency,buffer) <- Seq(("P1",1,16384),("P2",40,16384),("P3",40,2560))) {
    s"$name measures the prescribed GEMV kernel against Spike" in {
      val root=Paths.get(sys.env("R02_FIXTURES"))
      val fixture=R02Fixture.load(root.resolve("gemv/fixture.json"))
      val p=RvvParams(returnBytes=buffer)
      simulate(new RvvCoprocessor(p)) { dut =>
        val m=new RvvProtocolDriver(dut,p,fixture,0,randomize=false,readLatency=latency,
          writeLatency=1,translationLatency=2,injectKills=false).run()
        val window=m.kernelLastRead-m.kernelFirstRead+1
        val bandwidth=m.kernelReadBeats.toDouble/window
        val cycles=m.kernelLastB-m.kernelFirstIssue+1
        val ideal=m.kernelReadBeats
        assert(ideal==896L/4*4,"GEMV read-byte accounting must include all 224 weight blocks")
        assert(m.r04Links.size==224,"all R04 readiness pairs must be observed")
        assert(m.linkDelays.size==224 && m.requestAdvances.size==223,"all link/advance pairs must be observed")
        val required=if(name=="P1") "0.95" else if(name=="P2") "0.90" else "null"
        val bandwidthPass=if(name=="P3") "null" else (bandwidth>=required.toDouble).toString
        val result=s"""{"contract":"$name","readLatency":$latency,"returnBytes":$buffer,"readBytes":${m.kernelReadBeats*p.memBytes},"readWindowCycles":$window,"peakFraction":$bandwidth,"kernelCycles":$cycles,"idealReadCycles":$ideal,"kernelToIdealRatio":${cycles.toDouble/ideal},"bufferedBytesAtKernelB":${m.kernelRemaining},"bufferedBytesAtDrain":${m.remainingBytes},"linkMin":${m.linkDelays.min},"linkMax":${m.linkDelays.max},"advanceMin":${m.requestAdvances.min},"advanceMax":${m.requestAdvances.max},"r04LinkMin":${m.r04Links.min},"r04LinkMax":${m.r04Links.max},"prefetchDepth":${m.prefetchDepth},"peakBufferedBytes":${m.peakBuffered}}"""
        println(s"R02_PERFORMANCE $result")
        val out=Paths.get(sys.env("R02_RESULTS")); Files.createDirectories(out)
        Files.writeString(out.resolve(s"$name.json"),result+"\n")
        val pairs=s"""{"contract":"$name","bandwidthRequired":$required,"bandwidthPass":$bandwidthPass,"P4Pass":${m.linkDelays.max<=2},"P5Pass":${m.requestAdvances.min>0},"linkDelays":${m.linkDelays.mkString("[",",","]")},"requestAdvances":${m.requestAdvances.mkString("[",",","]")}}"""
        Files.writeString(out.resolve(s"$name-pairs.json"),pairs+"\n")
        // Threshold failures are reported as measured failures; measurements
        // remain available so S1 can proceed without weakening any contract.
      }
    }
  }
}
