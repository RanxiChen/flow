package flow.backend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class WritebackSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private def idle(d: Writeback): Unit = {
    d.io.late.valid.poke(false.B); d.io.late.bits.rd.idx.poke(1.U)
    d.io.late.bits.rd.isFp.poke(false.B); d.io.late.bits.data.poke(101.U); d.io.late.bits.error.poke(false.B)
    d.io.div.valid.poke(false.B); d.io.div.bits.rd.poke(2.U); d.io.div.bits.data.poke(102.U)
    d.io.mul.valid.poke(false.B); d.io.mul.bits.rd.poke(3.U); d.io.mul.bits.data.poke(103.U)
    d.io.fp.valid.poke(false.B); d.io.fp.bits.rd.idx.poke(4.U)
    d.io.fp.bits.rd.isFp.poke(false.B); d.io.fp.bits.data.poke(104.U); d.io.fp.bits.flags.poke(17.U)
    d.io.ordinary.valid.poke(false.B); d.io.ordinary.bits.rd.idx.poke(5.U)
    d.io.ordinary.bits.rd.isFp.poke(false.B); d.io.ordinary.bits.data.poke(105.U)
    d.io.ordinary.bits.flags.poke(0.U)
  }
  "T12_component_T13_component_S06: fixed priority, four consecutive writes, WB priority without pipeline hold" in {
    simulate(new Writeback) { d =>
      idle(d); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.late.valid.poke(true.B); d.io.ordinary.valid.poke(true.B)
      d.io.grant.expect(0.U); d.io.conflict.expect(0.U)
      d.io.gprWrite.valid.expect(false.B); d.io.late.ready.expect(true.B)
      d.io.clear.valid.expect(false.B)
      d.clock.step(); d.io.ordinary.valid.poke(false.B); d.io.late.valid.poke(false.B)
      d.io.div.valid.poke(true.B); d.io.mul.valid.poke(true.B); d.io.fp.valid.poke(true.B)
      d.io.grant.expect(0.U); d.io.conflict.expect(1.U)
      d.io.gprWrite.valid.expect(true.B); d.io.gprWrite.bits.idx.expect(5.U)
      d.io.gprWrite.bits.data.expect(105.U); d.io.late.ready.expect(false.B)
      d.io.clear.valid.expect(false.B)
      d.clock.step()
      val valid = Seq(d.io.div.valid, d.io.mul.valid, d.io.fp.valid)
      val ready = Seq(d.io.div.ready, d.io.mul.ready, d.io.fp.ready)
      for (cycle <- 0 until 4) {
        d.io.grant.expect((1 << cycle).U); d.io.conflict.expect(0.U)
        d.io.gprWrite.valid.expect(true.B); d.io.gprWrite.bits.idx.expect((cycle + 1).U)
        d.io.gprWrite.bits.data.expect((101 + cycle).U)
        d.io.clear.valid.expect(true.B); d.io.clear.bits.idx.expect((cycle + 1).U)
        d.io.fpFlags.valid.expect((cycle == 3).B)
        d.io.late.ready.expect(true.B)
        for (i <- 0 until 3) ready(i).expect((i+1 == cycle).B)
        d.clock.step(); if(cycle>0) valid(cycle-1).poke(false.B)
      }
      d.io.ordinary.valid.poke(true.B)
      d.io.conflict.expect(0.U); d.io.gprWrite.valid.expect(false.B)
      d.clock.step(); d.io.ordinary.valid.poke(false.B)
      d.io.gprWrite.valid.expect(true.B); d.io.clear.valid.expect(false.B)
      d.io.gprWrite.bits.idx.expect(5.U); d.io.gprWrite.bits.data.expect(105.U)
      d.clock.step(); d.io.gprWrite.valid.expect(false.B)
    }
  }
  "S01_S06_S08: f0 late occupies FPR port, independent GPR write and fatal error" in {
    simulate(new Writeback) { d =>
      idle(d); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.late.valid.poke(true.B); d.io.late.bits.rd.idx.poke(0.U); d.io.late.bits.rd.isFp.poke(true.B)
      d.io.ordinary.valid.poke(true.B)
      d.io.conflict.expect(0.U); d.io.fprWrite.valid.expect(false.B); d.io.gprWrite.valid.expect(false.B)
      d.clock.step(); d.io.late.valid.poke(false.B); d.io.ordinary.valid.poke(false.B)
      d.io.conflict.expect(0.U); d.io.fprWrite.valid.expect(true.B)
      d.io.fprWrite.bits.idx.expect(0.U); d.io.gprWrite.valid.expect(true.B)
      d.io.clear.valid.expect(true.B); d.io.clear.bits.idx.expect(0.U); d.io.clear.bits.isFp.expect(true.B)
      d.clock.step()
      // Error reception N has no combinational fatal; W2 captured on N
      // still writes f5 on N+1 while the old erroneous f0 clears busy.
      d.io.late.valid.poke(true.B); d.io.late.bits.error.poke(true.B)
      d.io.ordinary.valid.poke(true.B); d.io.ordinary.bits.rd.isFp.poke(true.B)
      d.io.hartFatal.expect(false.B); d.io.lateWriteError.expect(false.B)
      d.clock.step(); d.io.late.valid.poke(false.B); d.io.ordinary.valid.poke(false.B)
      d.io.clear.valid.expect(true.B)
      d.io.fprWrite.valid.expect(true.B); d.io.fprWrite.bits.idx.expect(5.U)
      d.io.fprWrite.bits.data.expect(105.U); d.io.lateWriteError.expect(true.B)
      d.io.hartFatal.expect(true.B); d.clock.step(); d.io.late.valid.poke(false.B)
      d.io.hartFatal.expect(true.B); d.io.clear.valid.expect(false.B); d.io.lateWriteError.expect(false.B)
      d.io.fprWrite.valid.expect(false.B)
      // x0 is suppressed, and reset discards any buffered writes/fatal.
      d.io.ordinary.valid.poke(true.B); d.io.ordinary.bits.rd.isFp.poke(false.B)
      d.io.ordinary.bits.rd.idx.poke(0.U); d.clock.step(); d.io.ordinary.valid.poke(false.B)
      d.io.gprWrite.valid.expect(false.B); d.io.clear.valid.expect(false.B)
      d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.w2.valid.expect(false.B); d.io.hartFatal.expect(false.B)
    }
  }

  "SOC3b_lateReg_backpressure_and_same_edge_replace_use_old_payload" in {
    simulate(new Writeback) { d =>
      idle(d); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.late.valid.poke(true.B); d.io.ordinary.valid.poke(true.B)
      d.io.late.ready.expect(true.B); d.io.gprWrite.valid.expect(false.B)
      d.clock.step()
      // A is buffered; B remains unchanged while ordinary W2 blocks A.
      d.io.late.bits.rd.idx.poke(6.U); d.io.late.bits.data.poke(106.U)
      for(_ <- 0 until 2) {
        d.io.late.ready.expect(false.B); d.io.gprWrite.bits.idx.expect(5.U)
        d.io.grant.expect(0.U); d.io.clear.valid.expect(false.B)
        d.clock.step()
      }
      d.io.ordinary.valid.poke(false.B); d.io.late.ready.expect(false.B)
      d.clock.step()
      d.io.late.ready.expect(true.B); d.io.grant.expect(1.U)
      d.io.gprWrite.bits.idx.expect(1.U); d.io.gprWrite.bits.data.expect(101.U)
      d.io.clear.bits.idx.expect(1.U); d.io.lateWriteError.expect(false.B)
      d.clock.step(); d.io.late.valid.poke(false.B)
      d.io.gprWrite.bits.idx.expect(6.U); d.io.gprWrite.bits.data.expect(106.U)
      d.io.clear.bits.idx.expect(6.U); d.io.grant.expect(1.U)
      d.clock.step(); d.io.gprWrite.valid.expect(false.B); d.io.clear.valid.expect(false.B)
    }
  }
}
