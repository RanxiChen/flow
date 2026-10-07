package flow.rvv

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class RvvSmokeSpec extends AnyFreeSpec with ChiselSim {
  for((v,d) <- Seq((512,512),(256,256),(512,256))) {
    s"C1 elaborates and Verilator compiles VLEN=$v DLEN=$d" in {
      simulate(new RvvCoprocessor(RvvParams(vlen=v,dlen=d,lanes=d/64,memoryBits=d))) { dut =>
        dut.io.issue.valid.poke(false.B); dut.io.verdict.ready.poke(true.B)
        dut.io.commit.poke(false.B); dut.io.killUncommitted.poke(false.B); dut.io.serialGo.poke(false.B)
        dut.io.translation.ready.poke(true.B); dut.io.translated.valid.poke(false.B)
        dut.io.scalarResult.ready.poke(true.B); dut.io.conflictQuery.valid.poke(false.B)
        dut.io.invalidate.ready.poke(true.B); dut.io.scalarWritesVisible.poke(true.B)
        dut.io.axi.ar.ready.poke(true.B); dut.io.axi.aw.ready.poke(true.B); dut.io.axi.w.ready.poke(true.B)
        dut.io.axi.r.valid.poke(false.B); dut.io.axi.b.valid.poke(false.B)
        dut.reset.poke(true.B); dut.clock.step(3); dut.reset.poke(false.B); dut.clock.step(3)
        dut.io.drained.expect(true.B)
      }
    }
  }
}
