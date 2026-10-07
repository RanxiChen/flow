package flow.rvv

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class RvvR03Spec extends AnyFreeSpec with ChiselSim {
  "a full scoreboard stalls dispatch allocation until an instruction completes" in {
    val p=RvvParams()
    simulate(new RvvScoreboard(p,1)) { d =>
      d.io.nextAge.poke(0.U); d.io.allocate.valid.poke(false.B); d.io.check(0).valid.poke(false.B)
      d.io.progress(0).valid.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      for(j <- 0 until p.scoreboardDepth) {
        d.io.allocate.ready.expect(true.B); d.io.slot.expect(j.U)
        d.io.allocate.bits.age.poke(j.U)
        d.io.allocate.bits.decoded.readMask.poke(0.U)
        d.io.allocate.bits.decoded.writeMask.poke((BigInt(1)<<j%32).U)
        d.io.allocate.valid.poke(true.B); d.clock.step(1)
      }
      d.io.allocate.ready.expect(false.B); d.clock.step(3)
      d.io.allocate.ready.expect(false.B); d.io.allocate.valid.poke(false.B)
      d.io.progress(0).valid.poke(true.B); d.io.progress(0).bits.slot.poke(0.U)
      d.io.progress(0).bits.readDone.poke(0.U); d.io.progress(0).bits.writeDone.poke(1.U)
      d.io.progress(0).bits.finished.poke(true.B)
      d.io.allocate.ready.expect(false.B) // progress has no combinational state bypass
      d.clock.step(1)
      d.io.progress(0).valid.poke(false.B)
      d.io.allocate.ready.expect(true.B); d.io.slot.expect(0.U)
    }
  }
  "a disabled BRAM read cannot consume a simultaneous same-row write" in {
    val p=RvvParams()
    simulate(new RvvRegisterFile(p)) { d =>
      d.io.readValid.foreach(_.poke(false.B)); d.io.readRows.foreach(_.poke(0.U))
      d.io.write.foreach(_.valid.poke(false.B)); d.io.age.foreach(_.poke(0.U))
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def write(value: Int): Unit = {
        d.io.write(0).valid.poke(true.B); d.io.write(0).bits.row.poke(4.U)
        d.io.write(0).bits.data.poke(value.U)
        d.io.write(0).bits.enables.poke(((BigInt(1)<<p.rowBytes)-1).U)
        d.clock.step(1); d.io.write(0).valid.poke(false.B)
      }
      write(0x1234)
      d.io.readRows(0).poke(4.U); d.io.readValid(0).poke(true.B); d.clock.step(1)
      d.io.readData(0).expect(0x1234.U); d.io.readValid(0).poke(false.B)
      write(0x5678); d.io.readData(0).expect(0x1234.U) // held value is not consumed
      d.io.readValid(0).poke(true.B); d.clock.step(1); d.io.readData(0).expect(0x5678.U)
    }
  }
  "modular ages preserve RAW WAR WAW across wrap and bound the live age window" in {
    val p=RvvParams()
    simulate(new RvvScoreboard(p,1)) { d =>
      d.io.nextAge.poke(1.U); d.io.allocate.valid.poke(false.B)
      d.io.check(0).valid.poke(false.B); d.io.progress(0).valid.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val end=(BigInt(1)<<p.ageBits)
      for((age,j) <- Seq(end-2,end-1,BigInt(0)).zipWithIndex) {
        d.io.allocate.valid.poke(true.B); d.io.allocate.bits.age.poke(age.U)
        d.io.allocate.bits.decoded.readMask.poke((if(j==1) 4 else 0).U)
        d.io.allocate.bits.decoded.writeMask.poke((1<<j).U); d.clock.step(1)
      }
      d.io.allocate.valid.poke(false.B); d.io.check(0).valid.poke(true.B)
      d.io.check(0).slot.poke(0.U); d.io.check(0).reads.poke(7.U); d.io.check(0).writes.poke(7.U)
      d.io.raw(0).expect(false.B); d.io.war(0).expect(false.B); d.io.waw(0).expect(false.B)
      d.io.check(0).slot.poke(2.U)
      d.io.raw(0).expect(true.B); d.io.war(0).expect(true.B); d.io.waw(0).expect(true.B)
      d.io.nextAge.poke((p.ageLimit-2).U); d.io.ageAllowed.expect(false.B)
      d.io.nextAge.poke((p.ageLimit-3).U); d.io.ageAllowed.expect(true.B)
    }
  }
}
