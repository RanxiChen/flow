package flow.rvv

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import java.nio.file.Paths

class RvvR04Spec extends AnyFreeSpec with ChiselSim {
  "16 dependent dots use the in-unit bypass and agree with Spike" in {
    val f=R02Fixture.load(Paths.get(sys.env("R04_FIXTURES"),"chain/fixture.json"))
    val p=RvvParams()
    simulate(new RvvCoprocessor(p)) { dut =>
      val m=new RvvProtocolDriver(dut,p,f,0,randomize=false,injectKills=false,readLatency=1).run()
      println(s"R04_P6 readCycles=${m.chainReadCycles} contract=66")
      assert(m.chainReadCycles<=66 && m.chainReadCycles>=64,"16 LMUL4 dots must read without instruction gaps")
    }
  }
  for((bytes,latency) <- Seq((16384,1),(512,80))) {
    s"prefetch respects store order, WAR, credits and age wrap with buffer=$bytes latency=$latency" in {
      val f=R02Fixture.load(Paths.get(sys.env("R04_FIXTURES"),"prefetch/fixture.json"))
      val p=RvvParams(returnBytes=bytes)
      simulate(new RvvCoprocessor(p)) { dut =>
        val m=new RvvProtocolDriver(dut,p,f,0,randomize=false,injectKills=false,readLatency=latency,writeLatency=40).run()
        println(s"R04_PREFETCH buffer=$bytes latency=$latency depth=${m.prefetchDepth} fullQueue=${m.fullQueuePrefetch} WAR=${m.prefetchWarCycles} peak=${m.peakBuffered}")
        assert(m.fullQueuePrefetch>0,"AR must run past a full MAC dispatch queue")
        assert(m.peakBuffered<=bytes,"reserved return credit exceeds capacity")
        assert(f.records.size>(1<<p.ageBits),"program must cross sequence wrap")
      }
    }
  }
}
