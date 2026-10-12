package flow.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

/** Exercise the production ID/EX ownership protocol through real banked training. */
class BreezeResolutionReservationSpec extends AnyFreeSpec with ChiselSim {
  for (enabled <- Seq(true, false)) {
    s"reserved EX feedback never waits for training and global restart releases only unfilled records ($enabled)" in {
      simulate(new BreezeFrontendPipeline(backendConnected = true, predictorEnabled = enabled)) { d =>
        d.io.resetPc.poke(0x80000000L.U)
        d.io.context.poke(0.U.asTypeOf(d.io.context))
        d.io.context.privilege.poke(3.U); d.io.context.mpp.poke(3.U)
        d.io.context.pmpRangesValid.poke(true.B)
        d.io.block.poke(true.B)
        d.io.redirect.valid.poke(false.B); d.io.redirect.bits.poke(0x80000000L.U)
        d.io.fenceI.poke(false.B); d.io.sfence.poke(0.U.asTypeOf(d.io.sfence))
        d.io.resolve.valid.poke(false.B); d.io.resolve.bits.poke(0.U.asTypeOf(d.io.resolve.bits))
        d.io.resolutionReserve.get.poke(false.B)
        d.io.committedBranch.poke(0.U.asTypeOf(d.io.committedBranch))
        d.io.instruction.ready.poke(true.B)
        d.io.refillRsp.vld.poke(false.B); d.io.refillRsp.data.poke(0.U); d.io.refillRsp.error.poke(false.B)
        d.io.ptwMem.req.ready.poke(true.B); d.io.ptwMem.resp.valid.poke(false.B)
        d.io.ptwMem.resp.bits.poke(0.U.asTypeOf(d.io.ptwMem.resp.bits))
        d.io.dtlb.get.req.valid.poke(false.B)
        d.io.dtlb.get.req.bits.poke(0.U.asTypeOf(d.io.dtlb.get.req.bits))
        d.io.dtlb.get.kill.poke(false.B)
        d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
        var trained = 0
        def tick(): Unit = {
          if(d.io.debug.trainingFire.peek().litToBoolean) trained += 1
          d.clock.step()
        }
        // Four ID owners can enter before any reaches EX.
        d.io.resolutionReserve.get.poke(true.B)
        for (_ <- 0 until 4) { d.io.resolutionAvailable.expect(true.B); tick() }
        d.io.resolutionReserve.get.poke(false.B)
        d.io.resolutionAvailable.expect(false.B)
        d.io.debug.resolutionUsed.expect(4.U); d.io.debug.resolutionReserved.expect(4.U)
        d.io.resolve.bits.kind.poke(1.U); d.io.resolve.bits.taken.poke(true.B)
        d.io.resolve.bits.target.poke(0x80000040L.U); d.io.resolve.bits.nextPc.poke(0x80000040L.U)
        d.io.resolve.valid.poke(true.B)
        for (i <- 0 until 4) {
          d.io.resolve.bits.eventPc.poke((0x80000002L + 4 * i).U)
          d.io.resolve.ready.expect(true.B); tick()
        }
        d.io.resolve.valid.poke(false.B)
        for (_ <- 0 until 40) tick()
        assert(trained == 4)
        d.io.debug.resolutionUsed.expect(0.U); d.io.debug.resolutionReserved.expect(0.U)

        // Sustained conditional branches exceed the banked predictor's II=2.
        // Model ID admission followed by EX one cycle later, including training pressure.
        var issued = 0; var resolved = 0; var pending = false; var waits = 0
        for (cycle <- 0 until 160) {
          val admit = issued < 32 && d.io.resolutionAvailable.peek().litToBoolean
          if(issued < 32 && !admit) waits += 1
          d.io.resolutionReserve.get.poke(admit.B)
          d.io.resolve.valid.poke(pending.B)
          d.io.resolve.bits.eventPc.poke((0x80000102L + 4 * resolved).U)
          if(pending) { d.io.resolve.ready.expect(true.B); resolved += 1 }
          if(admit) issued += 1
          tick(); pending = admit
        }
        assert(issued == 32 && resolved == 32 && trained == 36)
        if(enabled) assert(waits > 0, "test must exercise real training backpressure")
        d.io.debug.resolutionUsed.expect(0.U); d.io.debug.resolutionReserved.expect(0.U)

        // One accepted training owner survives cancellation; two pre-EX owners do not.
        d.io.resolve.valid.poke(false.B); d.io.resolutionReserve.get.poke(true.B)
        for (_ <- 0 until 3) tick()
        d.io.resolutionReserve.get.poke(false.B); d.io.resolve.valid.poke(true.B)
        d.io.resolve.ready.expect(true.B); tick()
        d.io.resolve.valid.poke(false.B); d.io.redirect.valid.poke(true.B)
        d.io.resolutionAvailable.expect(false.B); tick()
        d.io.redirect.valid.poke(false.B)
        d.io.debug.resolutionReserved.expect(0.U); d.io.debug.resolutionUsed.expect(1.U)
        for (_ <- 0 until 40) tick()
        assert(trained == 37)
        d.io.debug.resolutionUsed.expect(0.U); d.io.resolutionAvailable.expect(true.B)
      }
    }
  }
}
