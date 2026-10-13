package flow.rvv.zve

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class ZveWindowSpec extends AnyFreeSpec with ChiselSim {
  private val p = ZveParams(window=4)
  private def bit(n: Int): BigInt = BigInt(1) << n
  "queued WAR ownership blocks a young writer while unrelated units execute" in {
    simulate(new ZveWindow(p)) { d =>
      d.io.issue.valid.poke(false.B); d.io.commit.poke(false.B); d.io.kill.poke(false.B); d.io.serialGo.poke(false.B)
      d.io.verdict.ready.poke(true.B); d.io.completion.ready.poke(true.B)
      d.io.execute.foreach(_.ready.poke(false.B)); d.io.complete.foreach(_.valid.poke(false.B))
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def send(unit: Int, reads: BigInt, writes: BigInt): (Int, Int) = {
        val c=d.io.issue.bits
        c.issue.scalar.instruction.poke(0x57.U); c.issue.scalar.rs1.poke(0.U); c.issue.scalar.rs2.poke(0.U)
        c.issue.scalar.vl.poke(16.U); c.issue.scalar.vtype.poke(16.U); c.issue.scalar.vstart.poke(0.U)
        c.issue.scalar.vxrm.poke(0.U); c.issue.scalar.frm.poke(0.U); c.issue.scalar.rd.poke(0.U)
        c.issue.fpScalar.poke(0.U); c.unit.poke(unit.U); c.operation.poke(0.U)
        c.legal.poke(true.B); c.serial.poke(false.B); c.reads.poke(reads.U); c.writes.poke(writes.U)
        c.slot.poke(0.U); c.age.poke(0.U)
        d.io.issue.valid.poke(true.B); d.io.issue.ready.expect(true.B); d.clock.step()
        d.io.issue.valid.poke(false.B); d.io.verdict.valid.expect(true.B)
        val slot=d.io.verdict.bits.slot.peek().litValue.toInt; val age=d.io.verdict.bits.age.peek().litValue.toInt
        d.clock.step(); d.io.commit.poke(true.B); d.clock.step(); d.io.commit.poke(false.B)
        (slot,age)
      }
      // Slow queued instruction reads v8; its execution port remains stopped.
      val old=send(ZveUnit.integer,bit(8),bit(12))
      val young=send(ZveUnit.alu,bit(1),bit(8))
      val independent=send(ZveUnit.conversion,bit(2),bit(3))
      d.clock.step(3)
      d.io.execute(ZveUnit.integer).valid.expect(true.B)
      d.io.execute(ZveUnit.alu).valid.expect(false.B)
      d.io.execute(ZveUnit.conversion).valid.expect(true.B)
      d.io.execute(ZveUnit.conversion).ready.poke(true.B); d.clock.step()
      d.io.execute(ZveUnit.conversion).ready.poke(false.B)
      def finish(unit: Int, item: (Int,Int), flags: Int): Unit = {
        val r=d.io.complete(unit)
        r.bits.slot.poke(item._1.U); r.bits.age.poke(item._2.U); r.bits.flags.poke(flags.U)
        r.bits.saturated.poke(false.B); r.bits.scalarValid.poke(false.B); r.bits.scalarFloating.poke(false.B)
        r.bits.scalarRd.poke(0.U); r.bits.scalarData.poke(0.U); r.bits.exception.poke(false.B)
        r.bits.cause.poke(0.U); r.bits.tval.poke(0.U); r.bits.vstart.poke(0.U)
        r.bits.changeVl.poke(false.B); r.bits.newVl.poke(0.U); r.valid.poke(true.B)
        var wait=0
        while(!r.ready.peek().litToBoolean && wait<20) { d.clock.step(); wait+=1 }
        assert(wait<20); d.clock.step(); r.valid.poke(false.B)
      }
      finish(ZveUnit.conversion,independent,1)
      d.io.execute(ZveUnit.alu).valid.expect(false.B)
      d.io.execute(ZveUnit.integer).ready.poke(true.B); d.clock.step()
      d.io.execute(ZveUnit.integer).ready.poke(false.B)
      finish(ZveUnit.integer,old,0)
      d.clock.step(2); d.io.execute(ZveUnit.alu).valid.expect(true.B)
      d.io.execute(ZveUnit.alu).bits.age.expect(young._2.U)
      d.io.execute(ZveUnit.alu).ready.poke(true.B); d.clock.step(); d.io.execute(ZveUnit.alu).ready.poke(false.B)
      finish(ZveUnit.alu,young,0); d.clock.step(3); d.io.drained.expect(true.B)
    }
  }

  "commit wins over same-cycle kill, and buffered state events keep drained low" in {
    simulate(new ZveWindow(p)) { d =>
      d.io.issue.valid.poke(false.B); d.io.commit.poke(false.B); d.io.kill.poke(false.B); d.io.serialGo.poke(false.B)
      d.io.verdict.ready.poke(true.B); d.io.completion.ready.poke(false.B)
      d.io.execute.foreach(_.ready.poke(true.B)); d.io.complete.foreach(_.valid.poke(false.B))
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val c=d.io.issue.bits
      c.issue.scalar.instruction.poke(0x57.U); c.issue.scalar.rs1.poke(0.U); c.issue.scalar.rs2.poke(0.U)
      c.issue.scalar.vl.poke(0.U); c.issue.scalar.vtype.poke(16.U); c.issue.scalar.vstart.poke(0.U)
      c.issue.scalar.vxrm.poke(0.U); c.issue.scalar.frm.poke(0.U); c.issue.scalar.rd.poke(0.U)
      c.issue.fpScalar.poke(0.U); c.unit.poke(0.U); c.operation.poke(0.U); c.legal.poke(true.B)
      c.serial.poke(false.B); c.reads.poke(0.U); c.writes.poke(0.U); c.slot.poke(0.U); c.age.poke(0.U)
      d.io.issue.valid.poke(true.B); d.clock.step(); d.io.issue.valid.poke(false.B)
      val slot=d.io.verdict.bits.slot.peek().litValue; val age=d.io.verdict.bits.age.peek().litValue
      d.clock.step(); d.io.commit.poke(true.B); d.io.kill.poke(true.B); d.clock.step()
      d.io.commit.poke(false.B); d.io.kill.poke(false.B); d.clock.step(3)
      val r=d.io.complete(0); r.bits.slot.poke(slot.U); r.bits.age.poke(age.U); r.bits.flags.poke(3.U)
      r.bits.saturated.poke(true.B); r.bits.scalarValid.poke(false.B); r.bits.scalarFloating.poke(false.B)
      r.bits.scalarRd.poke(0.U); r.bits.scalarData.poke(0.U); r.bits.exception.poke(false.B)
      r.bits.cause.poke(0.U); r.bits.tval.poke(0.U); r.bits.vstart.poke(0.U)
      r.bits.changeVl.poke(false.B); r.bits.newVl.poke(0.U); r.valid.poke(true.B)
      r.ready.expect(true.B); d.clock.step(); r.valid.poke(false.B)
      d.io.occupancy.expect(0.U); d.io.drained.expect(false.B)
      d.io.completion.valid.expect(true.B); d.io.completion.bits.flags.expect(3.U)
      d.clock.step(4); d.io.completion.bits.flags.expect(3.U); d.io.completion.bits.saturated.expect(true.B)
      d.io.completion.ready.poke(true.B); d.clock.step(); d.io.drained.expect(true.B)
    }
  }
}
