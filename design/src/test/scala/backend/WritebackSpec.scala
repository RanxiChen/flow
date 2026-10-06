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
      d.io.late.valid.poke(true.B); d.io.div.valid.poke(true.B)
      d.io.mul.valid.poke(true.B); d.io.fp.valid.poke(true.B); d.io.ordinary.valid.poke(true.B)
      d.io.grant.expect(0.U); d.io.conflict.expect(1.U)
      d.io.gprWrite.valid.expect(true.B); d.io.gprWrite.bits.idx.expect(5.U)
      d.io.late.ready.expect(false.B); d.io.clear.valid.expect(false.B)
      d.clock.step(); d.io.ordinary.valid.poke(false.B)
      val valid = Seq(d.io.late.valid, d.io.div.valid, d.io.mul.valid, d.io.fp.valid)
      val ready = Seq(d.io.late.ready, d.io.div.ready, d.io.mul.ready, d.io.fp.ready)
      for (cycle <- 0 until 4) {
        d.io.grant.expect((1 << cycle).U); d.io.conflict.expect(0.U)
        d.io.gprWrite.valid.expect(true.B); d.io.gprWrite.bits.idx.expect((cycle + 1).U)
        d.io.gprWrite.bits.data.expect((101 + cycle).U)
        d.io.clear.valid.expect(true.B); d.io.clear.bits.idx.expect((cycle + 1).U)
        d.io.fpFlags.valid.expect((cycle == 3).B)
        for (i <- 0 until 4) ready(i).expect((i == cycle).B)
        d.clock.step(); valid(cycle).poke(false.B)
      }
      d.io.ordinary.valid.poke(true.B)
      d.io.conflict.expect(0.U); d.io.gprWrite.valid.expect(true.B)
      d.io.gprWrite.bits.idx.expect(5.U); d.io.gprWrite.bits.data.expect(105.U)
    }
  }
  "S01_S06_S08: f0 late occupies FPR port, independent GPR write and fatal error" in {
    simulate(new Writeback) { d =>
      idle(d); d.reset.poke(true.B); d.clock.step(); d.reset.poke(false.B)
      d.io.late.valid.poke(true.B); d.io.late.bits.rd.idx.poke(0.U); d.io.late.bits.rd.isFp.poke(true.B)
      d.io.ordinary.valid.poke(true.B)
      d.io.conflict.expect(0.U); d.io.fprWrite.valid.expect(true.B)
      d.io.fprWrite.bits.idx.expect(0.U); d.io.gprWrite.valid.expect(true.B)
      d.io.ordinary.bits.rd.isFp.poke(true.B); d.io.conflict.expect(1.U)
      d.io.late.ready.expect(false.B); d.io.fprWrite.bits.idx.expect(5.U)
      d.io.ordinary.valid.poke(false.B); d.io.late.bits.error.poke(true.B)
      d.io.clear.valid.expect(true.B); d.io.fprWrite.valid.expect(false.B)
      d.io.hartFatal.expect(true.B); d.clock.step(); d.io.late.valid.poke(false.B)
      d.io.hartFatal.expect(true.B); d.io.clear.valid.expect(false.B)
      d.io.ordinary.valid.poke(true.B); d.io.fprWrite.valid.expect(false.B)
    }
  }
}
