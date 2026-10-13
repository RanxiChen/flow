package flow.rvv.zve

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import svsim.{CommonCompilationSettings,CommonSettingsModifications}
import flow.fpu.BreezeFpSources

class ZveProductionSpec extends AnyFreeSpec with ChiselSim {
  override implicit def commonSettingsModifications: CommonSettingsModifications = {
    val inherited=super.commonSettingsModifications
    (settings: CommonCompilationSettings) => {
      val base=inherited(settings)
      base.copy(includeDirs=Some((base.includeDirs.getOrElse(Seq.empty) :+ BreezeFpSources.includeDir.toString).distinct))
    }
  }
  "production top without debug VRF or local slow FPU compiles and drains after reset" in {
    simulate(new ZveCoprocessor()) { d =>
      d.io.issue.valid.poke(false.B); d.io.verdict.ready.poke(true.B); d.io.completion.ready.poke(true.B)
      d.io.commit.poke(false.B); d.io.kill.poke(false.B); d.io.serialGo.poke(false.B)
      d.io.translation.ready.poke(true.B); d.io.translated.valid.poke(false.B)
      d.io.order.ready.poke(true.B); d.io.release.ready.poke(true.B); d.io.invalidate.ready.poke(true.B)
      d.io.scalarWritesVisible.poke(true.B); d.io.sharedFp.request.ready.poke(true.B); d.io.sharedFp.response.valid.poke(false.B)
      d.io.axi.ar.ready.poke(true.B); d.io.axi.aw.ready.poke(true.B); d.io.axi.w.ready.poke(true.B)
      d.io.axi.r.valid.poke(false.B); d.io.axi.b.valid.poke(false.B)
      d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B); d.clock.step(6)
      d.io.drained.expect(true.B); d.io.scalarMemoryBlocked.expect(false.B)
    }
  }
}
