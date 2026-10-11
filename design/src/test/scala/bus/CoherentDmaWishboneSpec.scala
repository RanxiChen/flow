package flow.bus

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.coherence._
import flow.config.BreezeClusterPresets
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class CoherentDmaWishboneSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val g = BreezeClusterPresets.single.mem
  private def init(d: CoherentDmaWishbone): Unit = {
    val b = d.io.wishbone
    b.cyc.poke(false.B); b.stb.poke(false.B); b.we.poke(false.B)
    b.adr.poke(0.U); b.dat_w.poke(0.U); b.sel.poke(255.U); b.cti.poke(0.U); b.bte.poke(0.U)
    d.io.home.req.ready.poke(false.B); d.io.home.rspDown.valid.poke(false.B)
    d.io.home.rspDown.bits.op.poke(RspDownOp.ReadData)
    d.io.home.rspDown.bits.id.poke(0.U); d.io.home.rspDown.bits.error.poke(false.B)
    d.io.home.rspDown.bits.data.poke(0.U)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }
  private def begin(d: CoherentDmaWishbone, address: BigInt, write: Boolean,
      data: BigInt = 0, mask: Int = 255): Unit = {
    d.io.wishbone.adr.poke((address >> 3).U); d.io.wishbone.we.poke(write.B)
    d.io.wishbone.dat_w.poke(data.U); d.io.wishbone.sel.poke(mask.U)
    d.io.wishbone.cyc.poke(true.B); d.io.wishbone.stb.poke(true.B); d.clock.step()
  }
  "DMA byte masks and data select all four line words, remain stable under backpressure, and ACK only coherent completion" in {
    simulate(new CoherentDmaWishbone(g)) { d =>
      init(d)
      val payload = BigInt("8877665544332211", 16)
      val returned = (0 until 4).foldLeft(BigInt(0))((a, k) => a | ((payload + k) << (k * 64)))
      for (write <- Seq(false, true); k <- 0 until 4) {
        val address = BigInt("80000100", 16) + 8 * k
        begin(d, address, write, payload, 0xa5)
        for (_ <- 0 until 4) {
          d.io.home.req.valid.expect(true.B)
          d.io.home.req.bits.addr.expect((address >> 5).U)
          d.io.home.req.bits.op.expect(if (write) ReqOp.MaskWrite else ReqOp.Read)
          d.io.home.req.bits.data.expect((payload << (64 * k)).U)
          d.io.home.req.bits.mask.expect((BigInt(0xa5) << (8 * k)).U)
          d.io.wishbone.ack.expect(false.B); d.io.wishbone.err.expect(false.B); d.clock.step()
        }
        d.io.home.req.ready.poke(true.B); d.clock.step(); d.io.home.req.ready.poke(false.B)
        d.io.home.req.valid.expect(false.B); d.clock.step(3); d.io.wishbone.ack.expect(false.B)
        d.io.home.rspDown.bits.op.poke(if (write) RspDownOp.WriteAck else RspDownOp.ReadData)
        d.io.home.rspDown.bits.data.poke(returned.U)
        d.io.home.rspDown.valid.poke(true.B); d.clock.step(); d.io.home.rspDown.valid.poke(false.B)
        d.io.wishbone.ack.expect(true.B); d.io.wishbone.err.expect(false.B)
        d.io.wishbone.dat_r.expect((payload + k).U)
        d.io.wishbone.cyc.poke(false.B); d.io.wishbone.stb.poke(false.B); d.clock.step()
      }
    }
  }
  "DMA denies MMIO, holes and ROM writes without issuing Home requests, and returns downstream errors without ACK" in {
    simulate(new CoherentDmaWishbone(g)) { d =>
      init(d)
      for (address <- Seq(BigInt("12001000", 16), BigInt("40000000", 16), BigInt("10010000", 16))) {
        begin(d, address, true)
        d.io.home.req.valid.expect(false.B); d.io.wishbone.err.expect(true.B); d.io.wishbone.ack.expect(false.B)
        d.io.wishbone.cyc.poke(false.B); d.io.wishbone.stb.poke(false.B); d.clock.step()
      }
      for (write <- Seq(false, true)) {
        begin(d, BigInt("11000000", 16), write)
        d.io.home.req.valid.expect(true.B)
        d.io.home.req.ready.poke(true.B); d.clock.step(); d.io.home.req.ready.poke(false.B)
        d.io.home.rspDown.bits.op.poke(if (write) RspDownOp.WriteAck else RspDownOp.ReadData)
        d.io.home.rspDown.bits.error.poke(true.B); d.io.home.rspDown.valid.poke(true.B); d.clock.step()
        d.io.home.rspDown.valid.poke(false.B)
        d.io.wishbone.err.expect(true.B); d.io.wishbone.ack.expect(false.B)
        d.io.wishbone.cyc.poke(false.B); d.io.wishbone.stb.poke(false.B); d.clock.step()
      }
    }
  }
  "an aborted DMA cycle drains its old response and cannot acknowledge a replacement cycle" in {
    simulate(new CoherentDmaWishbone(g)) { d =>
      init(d); begin(d, BigInt("80000000", 16), false)
      d.io.home.req.ready.poke(true.B); d.clock.step(); d.io.home.req.ready.poke(false.B)
      d.io.wishbone.cyc.poke(false.B); d.io.wishbone.stb.poke(false.B); d.clock.step()
      d.io.wishbone.cyc.poke(true.B); d.io.wishbone.stb.poke(true.B)
      d.io.home.rspDown.valid.poke(true.B); d.clock.step(); d.io.home.rspDown.valid.poke(false.B)
      d.io.wishbone.ack.expect(false.B); d.io.wishbone.err.expect(false.B); d.clock.step(2)
      d.io.home.req.valid.expect(true.B)
    }
  }
}
