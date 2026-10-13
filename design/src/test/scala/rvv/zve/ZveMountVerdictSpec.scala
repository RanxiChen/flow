package flow.rvv.zve

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.fpu.BreezeFpSources
import org.scalatest.freespec.AnyFreeSpec
import svsim.{CommonCompilationSettings, CommonSettingsModifications}

/** T4: killed verdict ownership must not leak into a reused window slot. */
class ZveMountVerdictSpec extends AnyFreeSpec with ChiselSim {
  override implicit def commonSettingsModifications: CommonSettingsModifications = {
    val inherited = super.commonSettingsModifications
    (settings: CommonCompilationSettings) => {
      val base = inherited(settings)
      base.copy(includeDirs = Some((base.includeDirs.getOrElse(Seq.empty) :+
        BreezeFpSources.includeDir.toString).distinct))
    }
  }

  "registered verdict queue clears on kill before the slot is reused" in {
    simulate(new ZveMount(ZveParams.soc(32, 32))) { d =>
      val io = d.io
      io.reserve.poke(false.B); io.exPending.poke(false.B)
      io.issue.valid.poke(false.B); io.consume.poke(false.B)
      io.killD.poke(false.B); io.serialHold.poke(false.B)
      io.result.ready.poke(true.B)
      io.context.poke(0.U.asTypeOf(io.context))
      io.tlb.req.ready.poke(false.B); io.tlb.canAccept.poke(false.B)
      io.tlb.resp.valid.poke(false.B); io.tlb.candidatePaddr.get.poke(0.U)
      io.stream.request.ready.poke(false.B); io.stream.response.valid.poke(false.B)
      io.axi.ar.ready.poke(false.B); io.axi.aw.ready.poke(false.B)
      io.axi.w.ready.poke(false.B); io.axi.r.valid.poke(false.B)
      io.axi.b.valid.poke(false.B)
      d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)

      // Illegal vector instructions get a verdict without executing any unit.
      // Kill frees the only live slot, so each following issue reuses slot 0.
      for (_ <- 0 until 3) {
        io.issue.bits.poke(0.U.asTypeOf(io.issue.bits))
        io.issue.bits.scalar.instruction.poke("hffffffff".U)
        io.issue.valid.poke(true.B); d.clock.step()
        io.issue.valid.poke(false.B)
        var n = 0
        while (!io.decision.valid.peek().litToBoolean && n < 16) {
          d.clock.step(); n += 1
        }
        assert(n < 16, "verdict did not reach the registered queue")
        io.decision.bits.exception.expect(true.B)
        io.killD.poke(true.B)
        io.decision.valid.expect(false.B)
        d.clock.step(); io.killD.poke(false.B)
        d.clock.step(2)
        io.decision.valid.expect(false.B)
        io.drained.expect(true.B)
      }
    }
  }
}
