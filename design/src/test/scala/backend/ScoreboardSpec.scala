package flow.backend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class ScoreboardSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private def idle(d: Scoreboard): Unit = {
    d.io.set.valid.poke(false.B); d.io.set.bits.rd.idx.poke(0.U)
    d.io.set.bits.rd.isFp.poke(false.B); d.io.set.bits.source.poke(0.U)
    d.io.clear.valid.poke(false.B); d.io.clear.bits.idx.poke(0.U); d.io.clear.bits.isFp.poke(false.B)
    d.io.idValid.poke(true.B); d.io.idLeave.poke(false.B); d.io.csr.poke(false.B)
    d.io.fpFlagsPending.poke(false.B)
    for (p <- d.io.pipe) {
      p.valid.poke(false.B); p.bits.rd.idx.poke(0.U)
      p.bits.rd.isFp.poke(false.B); p.bits.source.poke(0.U)
    }
    for (o <- d.io.operands) { o.used.poke(false.B); o.rd.idx.poke(0.U); o.rd.isFp.poke(false.B) }
  }
  "S01_S02_S03_S08_S10: all four sources, f0, cross-bank RAW/WAW and actual-write release" in {
    simulate(new Scoreboard) { d =>
      idle(d); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      for (source <- 0 until 4; fp <- Seq(false, true)) {
        val rd = if (fp) 0 else source + 1
        d.io.set.valid.poke(true.B); d.io.set.bits.rd.idx.poke(rd.U)
        d.io.set.bits.rd.isFp.poke(fp.B); d.io.set.bits.source.poke(source.U)
        d.clock.step(); d.io.set.valid.poke(false.B)
        val busy = if (fp) d.io.fprBusy else d.io.gprBusy
        busy.expect((BigInt(1) << rd).U)
        for (operand <- 0 until 4) {
          d.io.operands(operand).used.poke(true.B); d.io.operands(operand).rd.idx.poke(rd.U)
          d.io.operands(operand).rd.isFp.poke(fp.B)
          d.io.hazard.expect(true.B); d.io.sourceStall(source).expect(true.B)
          // The same numeric index in the other bank is independent.
          d.io.operands(operand).rd.isFp.poke((!fp).B); d.io.hazard.expect(false.B)
          d.io.operands(operand).rd.isFp.poke(fp.B)
          d.io.clear.valid.poke(true.B); d.io.clear.bits.idx.poke(rd.U); d.io.clear.bits.isFp.poke(fp.B)
          d.io.hazard.expect(false.B); d.io.sourceStall(source).expect(false.B)
          d.io.clear.valid.poke(false.B); d.io.operands(operand).used.poke(false.B)
        }
        // Removing killed pipe metadata cannot clear a committed destination.
        d.clock.step(3); busy.expect((BigInt(1) << rd).U)
        d.io.clear.valid.poke(true.B); d.clock.step(); d.io.clear.valid.poke(false.B); busy.expect(0.U)
      }
      d.io.set.valid.poke(true.B); d.io.set.bits.rd.isFp.poke(false.B); d.io.set.bits.rd.idx.poke(0.U)
      d.clock.step(); d.io.set.valid.poke(false.B); d.io.gprBusy.expect(0.U)
      // Different destinations set and clear on the same edge both take effect.
      d.io.set.valid.poke(true.B); d.io.set.bits.rd.idx.poke(5.U); d.clock.step()
      d.io.set.bits.rd.idx.poke(6.U); d.io.clear.valid.poke(true.B)
      d.io.clear.bits.isFp.poke(false.B); d.io.clear.bits.idx.poke(5.U); d.clock.step()
      d.io.set.valid.poke(false.B); d.io.clear.valid.poke(false.B); d.io.gprBusy.expect(64.U)
    }
  }
  "T17_component_S14_S16: CSR uses current busy and waits one cycle beyond final write" in {
    simulate(new Scoreboard) { d =>
      idle(d); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.csr.poke(true.B)
      for (source <- 0 until 4) {
        d.io.pipe(2).valid.poke(true.B); d.io.pipe(2).bits.source.poke(source.U)
        d.io.pipe(2).bits.rd.isFp.poke((source == LongSource.FPU).B)
        d.io.pipe(2).bits.rd.idx.poke(1.U)
        d.io.csrDrainOk.expect(false.B); d.io.sourceStall(source).expect(true.B)
        d.io.set.valid.poke(true.B)
        d.io.set.bits.source.poke(source.U); d.io.set.bits.rd.idx.poke(1.U)
        d.io.set.bits.rd.isFp.poke((source == LongSource.FPU).B); d.clock.step()
        d.io.set.valid.poke(false.B); d.io.pipe(2).valid.poke(false.B)
        d.io.csrDrainOk.expect(false.B)
        d.io.clear.valid.poke(true.B); d.io.clear.bits.idx.poke(1.U)
        d.io.clear.bits.isFp.poke((source == LongSource.FPU).B)
        d.io.csrDrainOk.expect(false.B); d.io.hazard.expect(true.B)
        d.clock.step(); d.io.clear.valid.poke(false.B)
        d.io.csrDrainOk.expect(true.B); d.io.hazard.expect(false.B)
      }
    }
  }
  "S01_S14: FP-to-x0 flags keep CSR draining without making x0 busy" in {
    simulate(new Scoreboard) { d =>
      idle(d); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.csr.poke(true.B); d.io.fpFlagsPending.poke(true.B)
      d.io.gprBusy.expect(0.U); d.io.fprBusy.expect(0.U)
      d.io.csrDrainOk.expect(false.B); d.io.sourceStall(LongSource.FPU).expect(true.B)
      d.clock.step(4); d.io.csrDrainOk.expect(false.B)
      d.io.fpFlagsPending.poke(false.B)
      d.io.csrDrainOk.expect(true.B); d.io.sourceStall(LongSource.FPU).expect(false.B)
    }
  }
}
