package flow.memsys

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.coherence.CoherenceParams
import flow.config.BreezeMemGeometry
import flow.l2.L2MemEngine
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** Local acceptance, AR dispatch, RAW ordering and R assembly are checked
  * separately. AXI R is in AR order, as required by the memory-engine contract.
  */
class L2MemEngineSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val p = CoherenceParams(BreezeMemGeometry.singleCore)
  private def init(d: L2MemEngine): Unit = {
    d.io.readReq.valid.poke(false.B); d.io.readReq.bits.slot.poke(0.U); d.io.readReq.bits.addr.poke(0.U)
    d.io.wbPush.valid.poke(false.B); d.io.wbPush.bits.addr.poke(0.U); d.io.wbPush.bits.data.poke(0.U)
    d.io.mem.ar.ready.poke(false.B); d.io.mem.aw.ready.poke(false.B); d.io.mem.w.ready.poke(false.B)
    d.io.mem.r.valid.poke(false.B); d.io.mem.r.bits.id.poke(0.U); d.io.mem.r.bits.data.poke(0.U)
    d.io.mem.r.bits.last.poke(false.B); d.io.mem.r.bits.resp.poke(0.U)
    d.io.mem.b.valid.poke(false.B); d.io.mem.b.bits.id.poke(0.U); d.io.mem.b.bits.resp.poke(0.U)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }
  private def read(d: L2MemEngine, slot: Int, addr: Int): Unit = {
    d.io.readReq.bits.slot.poke(slot.U); d.io.readReq.bits.addr.poke(addr.U)
    d.io.readReq.valid.poke(true.B); d.io.readReq.ready.expect(true.B)
    d.clock.step(); d.io.readReq.valid.poke(false.B)
  }
  private def response(d: L2MemEngine, slot: Int, words: Seq[BigInt], errorBeat: Int = -1): Unit = {
    for ((word, i) <- words.zipWithIndex) {
      d.io.mem.r.valid.poke(true.B); d.io.mem.r.bits.id.poke(slot.U)
      d.io.mem.r.bits.data.poke(word.U); d.io.mem.r.bits.last.poke((i == p.memBeats-1).B)
      d.io.mem.r.bits.resp.poke((if (i == errorBeat) 2 else 0).U)
      d.io.readDone.valid.expect((i == p.memBeats-1).B)
      if (i == p.memBeats-1) {
        d.io.readDone.bits.slot.expect(slot.U)
        val line = words.zipWithIndex.map { case (w,b) => w << (64*b) }.reduce(_ | _)
        d.io.readDone.bits.data.expect(line.U); d.io.readDone.bits.error.expect((errorBeat >= 0).B)
      }
      d.clock.step(); d.io.mem.r.valid.poke(false.B); d.clock.step()
    }
  }
  "SOC3e reserves two reads without AR ready and retains IDs/data through dispatch and R stalls" in {
    simulate(new L2MemEngine(p)) { d =>
      init(d); d.io.mem.ar.valid.expect(false.B)
      read(d, 1, 0x4000000); read(d, 0, 0x4000001)
      d.io.readReq.valid.poke(true.B); d.io.readReq.ready.expect(false.B)
      d.io.readReq.valid.poke(false.B)
      for (_ <- 0 until 5) {
        d.io.mem.ar.valid.expect(true.B); d.io.mem.ar.bits.id.expect(1.U)
        d.io.mem.ar.bits.addr.expect(BigInt("80000000",16).U); d.io.wbFree.expect(false.B)
        d.clock.step()
      }
      d.io.mem.ar.ready.poke(true.B); d.clock.step()
      d.io.mem.ar.bits.id.expect(0.U); d.io.mem.ar.bits.addr.expect(BigInt("80000020",16).U)
      d.clock.step(); d.io.mem.ar.valid.expect(false.B)
      // Dispatched reads still own the response slots, preventing over-admission.
      d.io.readReq.valid.poke(true.B); d.io.readReq.ready.expect(false.B); d.io.readReq.valid.poke(false.B)
      response(d, 1, Seq(BigInt(1), BigInt("8000000000000000",16), BigInt(3), BigInt(4)), 1)
      d.io.readReq.ready.expect(true.B)
      response(d, 0, Seq(BigInt(9), BigInt(10), BigInt(11), BigInt(12)))
      d.io.writeErrors.expect(0.U)
    }
  }
  "SOC3e write wins same-edge acceptance and same-line read waits for B while another line dispatches" in {
    simulate(new L2MemEngine(p)) { d =>
      init(d)
      val words = Seq(BigInt(11),BigInt(22),BigInt(33),BigInt(44))
      val line = words.zipWithIndex.map { case(w,i) => w << (64*i) }.reduce(_ | _)
      d.io.wbFree.expect(true.B); d.io.wbPush.valid.poke(true.B)
      d.io.wbPush.bits.addr.poke(0x4000000.U); d.io.wbPush.bits.data.poke(line.U)
      d.io.readReq.valid.poke(true.B); d.io.readReq.bits.addr.poke(0x4000000.U)
      d.io.readReq.ready.expect(false.B); d.clock.step(); d.io.wbPush.valid.poke(false.B)
      d.io.readReq.ready.expect(false.B)
      d.io.readReq.valid.poke(false.B); read(d, 1, 0x4000001)
      d.io.mem.ar.ready.poke(true.B); d.clock.step(); d.io.mem.ar.valid.expect(false.B)
      d.io.mem.aw.ready.poke(true.B); d.io.mem.w.ready.poke(true.B)
      for ((word,i) <- words.zipWithIndex) {
        d.io.mem.w.valid.expect(true.B); d.io.mem.w.bits.data.expect(word.U)
        d.io.mem.w.bits.strb.expect(255.U); d.io.mem.w.bits.last.expect((i==3).B); d.clock.step()
      }
      d.io.mem.b.ready.expect(true.B)
      d.io.readReq.valid.poke(true.B); d.io.readReq.bits.slot.poke(0.U)
      d.io.readReq.bits.addr.poke(0x4000000.U)
      d.io.readReq.ready.expect(false.B); d.clock.step(4)
      d.io.mem.b.valid.poke(true.B); d.io.mem.b.bits.resp.poke(2.U)
      d.io.readReq.ready.expect(false.B); d.clock.step(); d.io.mem.b.valid.poke(false.B)
      d.io.writeErrors.expect(1.U); d.io.readReq.ready.expect(true.B)
      d.clock.step(); d.io.readReq.valid.poke(false.B); d.clock.step()
      response(d, 1, words); response(d, 0, words)
    }
  }
}
