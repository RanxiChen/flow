package flow.rvv

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import java.nio.file.{Files,Paths}

class RvvIntegrationSpec extends AnyFreeSpec with ChiselSim {
  private val fixtureRoot=Paths.get(sys.env.getOrElse("R02_FIXTURES","../rvv/r02/fixtures"))
  "C2 directed instructions, memory overlap and hazards agree with executed Spike" in {
    val fixture=R02Fixture.load(fixtureRoot.resolve("directed/fixture.json"))
    simulate(new RvvCoprocessor()) { dut =>
      for(seed <- 0L until 8L) {
        val result=new RvvProtocolDriver(dut,RvvParams(),fixture,seed).run()
        println(s"R02_C2 seed=$seed cycles=${result.cycles} readBeats=${result.readBeats}")
      }
    }
  }
  "C3 1000 independently executed Spike random instruction seeds" in {
    simulate(new RvvCoprocessor()) { dut =>
      for(seed <- 0 until 1000) {
        val fixture=R02Fixture.load(fixtureRoot.resolve(f"seed-$seed%04d/fixture.json"))
        val result=new RvvProtocolDriver(dut,RvvParams(),fixture,seed).run()
        println(s"R02_C3 seed=$seed cycles=${result.cycles}")
      }
    }
  }
}
