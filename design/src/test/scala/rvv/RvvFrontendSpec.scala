package flow.rvv

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class RvvFrontendSpec extends AnyFreeSpec with ChiselSim {
  private val p=RvvParams(viqDepth=4,translationIds=2)
  private def initialize(d: RvvFrontend): Unit = {
    d.io.issue.valid.poke(false.B); d.io.verdict.ready.poke(false.B)
    d.io.commit.poke(false.B); d.io.kill.poke(false.B); d.io.serialGo.poke(false.B)
    d.io.translation.ready.poke(true.B); d.io.translated.valid.poke(false.B)
    d.io.dispatch.ready.poke(false.B); d.io.scalarQuery.valid.poke(false.B)
    d.io.scalarQuery.pa.poke(0.U); d.io.scalarQuery.bytes.poke(0.U); d.io.scalarQuery.write.poke(false.B)
    d.io.vectorQueryValid.poke(false.B); d.io.released.valid.poke(false.B)
    d.reset.poke(true.B); d.clock.step(3); d.reset.poke(false.B)
  }
  private def issue(d: RvvFrontend,word: BigInt=BigInt("02056407",16),vl: Int=64,vstart: Int=0): Unit = {
    val i=d.io.issue.bits
    i.instruction.poke(word.U); i.rs1.poke("h81000ff0".U); i.rs2.poke(0.U)
    i.vl.poke(vl.U); i.vtype.poke(18.U); i.vstart.poke(vstart.U)
    i.vxrm.poke(0.U); i.frm.poke(0.U); i.rd.poke(0.U)
    d.io.issue.valid.poke(true.B); d.io.issue.ready.expect(true.B)
    d.clock.step(1); d.io.issue.valid.poke(false.B)
  }
  private def request(d: RvvFrontend): Int = {
    var cycles=0
    while(!d.io.translation.valid.peek().litToBoolean && cycles<20) { d.clock.step(1); cycles+=1 }
    d.io.translation.valid.expect(true.B)
    val id=d.io.translation.bits.id.peek().litValue.toInt
    d.clock.step(1); id
  }
  private def response(d: RvvFrontend,id: Int,pa: BigInt,exception: Boolean=false,device: Boolean=false): Unit = {
    d.io.translated.valid.poke(true.B); d.io.translated.bits.id.poke(id.U)
    d.io.translated.bits.pa.poke(pa.U); d.io.translated.bits.exception.poke(exception.B)
    d.io.translated.bits.device.poke(device.B); d.io.translated.bits.cacheable.poke((!device).B)
    d.io.translated.bits.cause.poke(13.U); d.clock.step(1); d.io.translated.valid.poke(false.B)
  }
  private def verdict(d: RvvFrontend,kind: Int): Unit = {
    var cycles=0
    while(!d.io.verdict.valid.peek().litToBoolean && cycles<20) { d.clock.step(1); cycles+=1 }
    d.io.verdict.valid.expect(true.B); d.io.verdict.bits.kind.expect(kind.U)
  }
  "kill drains orphan tags; commit and kill preserve the oldest; query covers commit and queued memory" in {
    simulate(new RvvFrontend(p)) { d =>
      initialize(d)
      issue(d); val old=request(d)
      d.io.kill.poke(true.B); d.clock.step(1); d.io.kill.poke(false.B)
      issue(d); val fresh=request(d); assert(old!=fresh)
      response(d,old,BigInt("a0000000",16),exception=true)
      d.io.verdict.valid.expect(false.B)
      response(d,fresh,BigInt("90000ff0",16))
      val second=request(d); response(d,second,BigInt("94000000",16))
      verdict(d,0)
      d.io.scalarQuery.valid.poke(true.B); d.io.scalarQuery.pa.poke("h90000ff0".U)
      d.io.scalarQuery.bytes.poke(8.U); d.io.scalarQuery.write.poke(true.B)
      d.io.scalarConflict.expect(false.B) // pretranslated, still uncommitted
      d.io.verdict.ready.poke(true.B); d.clock.step(1); d.io.verdict.ready.poke(false.B)
      // A younger judged item is revoked when the oldest is committed with kill.
      issue(d,BigInt("5e05c257",16),vl=1)
      verdict(d,0)
      d.io.commit.poke(true.B); d.io.kill.poke(true.B)
      d.io.verdict.valid.expect(false.B); d.io.scalarConflict.expect(true.B)
      d.clock.step(1); d.io.commit.poke(false.B); d.io.kill.poke(false.B)
      d.io.scalarConflict.expect(true.B); d.io.verdict.valid.expect(false.B)
      d.io.scalarQuery.pa.poke("h94000000".U); d.io.scalarConflict.expect(true.B)
      val age=d.io.dispatch.bits.age.peek().litValue
      d.io.dispatch.valid.expect(true.B)
      d.io.released.valid.poke(true.B); d.io.released.bits.poke(age.U); d.clock.step(1)
      d.io.released.valid.poke(false.B); d.io.scalarConflict.expect(false.B)
    }
  }
  "capacity reservations cover the last VIQ position and simultaneous commits" in {
    simulate(new RvvFrontend(p)) { d =>
      initialize(d)
      for(_ <- 0 until 4) issue(d,BigInt("5e05c257",16),vl=1)
      d.io.issue.ready.expect(false.B)
      for(_ <- 0 until 4) {
        verdict(d,0); d.io.verdict.ready.poke(true.B); d.io.commit.poke(true.B)
        d.clock.step(1); d.io.verdict.ready.poke(false.B); d.io.commit.poke(false.B)
      }
      d.io.issue.ready.expect(false.B)
      d.io.dispatch.ready.poke(true.B); d.clock.step(1)
      d.io.issue.ready.expect(true.B)
      issue(d,BigInt("5e05c257",16),vl=1)
    }
  }
  "Serial decisions cover mask, FOF, vstart, strided, indexed, segmented, faults and device" in {
    simulate(new RvvFrontend(p)) { d =>
      initialize(d)
      val base=BigInt("02056407",16)
      val serialWords=Seq(base & ~(BigInt(1)<<25),base | (BigInt(16)<<20),
        base | (BigInt(2)<<26),base | (BigInt(1)<<26),base | (BigInt(1)<<29))
      for(word <- serialWords) {
        issue(d,word); verdict(d,3); d.io.translation.valid.expect(false.B)
        d.io.kill.poke(true.B); d.clock.step(1); d.io.kill.poke(false.B)
      }
      issue(d,base,vstart=1); verdict(d,3); d.io.translation.valid.expect(false.B)
      d.io.kill.poke(true.B); d.clock.step(1); d.io.kill.poke(false.B)
      for((fault,device) <- Seq((true,false),(false,true))) {
        issue(d); val id=request(d); response(d,id,BigInt("90000ff0",16),fault,device)
        verdict(d,3); d.io.kill.poke(true.B); d.clock.step(1); d.io.kill.poke(false.B)
      }
      issue(d,base,vl=0); verdict(d,0); d.io.translation.valid.expect(false.B)
    }
  }
  "kill revokes a backpressured verdict on the same edge" in {
    simulate(new RvvFrontend(p)) { d =>
      initialize(d); issue(d,BigInt("5e05c257",16),vl=1); verdict(d,0)
      d.io.verdict.ready.poke(true.B); d.io.kill.poke(true.B)
      d.io.verdict.valid.expect(false.B); d.clock.step(1); d.io.kill.poke(false.B)
      d.io.verdict.valid.expect(false.B)
      issue(d,BigInt("5e05c257",16),vl=1); verdict(d,0)
    }
  }
}
