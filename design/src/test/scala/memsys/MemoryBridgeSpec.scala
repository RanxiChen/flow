package flow.memsys

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.bus._
import flow.coherence._
import flow.config.BreezeMemGeometry
import org.scalatest.freespec.AnyFreeSpec

class MemoryBridgeSpec extends AnyFreeSpec with ChiselSim {
  "DMA keeps its line request stable and maps word offset, mask, errors and consecutive beats" in {
    simulate(new DmaWishboneClient(BreezeMemGeometry.singleCore)) { d =>
      val wb = d.io.wishbone
      wb.cyc.poke(false.B); wb.stb.poke(false.B); wb.we.poke(false.B)
      wb.adr.poke(0.U); wb.dat_w.poke(0.U); wb.sel.poke(0.U); wb.cti.poke(0.U); wb.bte.poke(0.U)
      d.io.coh.req.ready.poke(false.B); d.io.coh.rspDown.valid.poke(false.B)
      d.io.coh.rspDown.bits.op.poke(RspDownOp.ReadData); d.io.coh.rspDown.bits.id.poke(0.U)
      d.io.coh.rspDown.bits.data.poke(0.U); d.io.coh.rspDown.bits.error.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      wb.cyc.poke(true.B); wb.stb.poke(true.B); wb.we.poke(true.B)
      wb.adr.poke((0x80000018L >> 3).U); wb.sel.poke(0x81.U); wb.dat_w.poke(0x1234.U)
      d.clock.step()
      for (_ <- 0 until 4) {
        d.io.coh.req.valid.expect(true.B); d.io.coh.req.bits.op.expect(ReqOp.MaskWrite)
        d.io.coh.req.bits.addr.expect((0x80000000L >> 5).U)
        d.io.coh.req.bits.mask.expect((BigInt(0x81) << 24).U)
        d.io.coh.req.bits.data.expect((BigInt(0x1234) << 192).U)
        wb.ack.expect(false.B); d.clock.step()
      }
      d.io.coh.req.ready.poke(true.B); d.clock.step()
      d.io.coh.rspDown.valid.poke(true.B); d.io.coh.rspDown.bits.op.poke(RspDownOp.WriteAck)
      d.clock.step(); d.io.coh.rspDown.valid.poke(false.B)
      wb.ack.expect(true.B); wb.err.expect(false.B); d.clock.step()
      wb.we.poke(false.B); wb.adr.poke((0x80000008L >> 3).U); d.clock.step()
      d.io.coh.req.bits.op.expect(ReqOp.Read); d.clock.step()
      d.io.coh.rspDown.bits.op.poke(RspDownOp.ReadData)
      d.io.coh.rspDown.bits.data.poke((BigInt(0x5678) << 64).U)
      d.io.coh.rspDown.valid.poke(true.B); d.clock.step()
      d.io.coh.rspDown.valid.poke(false.B); wb.ack.expect(true.B); wb.dat_r.expect(0x5678.U)
      d.clock.step(); wb.adr.poke((0x80000010L >> 3).U); d.clock.step(); d.clock.step()
      d.io.coh.rspDown.bits.error.poke(true.B); d.io.coh.rspDown.valid.poke(true.B); d.clock.step()
      wb.ack.expect(false.B); wb.err.expect(true.B)
    }
  }

  "AXI bridge accepts W before AW and holds B and each read beat with ID and LAST" in {
    simulate(new Axi4WishboneBridge(Axi4Params(32,64,2))) { d =>
      d.io.axi.aw.valid.poke(false.B); d.io.axi.ar.valid.poke(false.B); d.io.axi.w.valid.poke(false.B)
      for (a <- Seq(d.io.axi.aw.bits, d.io.axi.ar.bits)) {
        a.addr.poke(0.U); a.id.poke(0.U); a.len.poke(0.U); a.size.poke(3.U); a.burst.poke(1.U); a.prot.poke(0.U)
      }
      d.io.axi.w.bits.data.poke(0.U); d.io.axi.w.bits.strb.poke(0.U); d.io.axi.w.bits.last.poke(true.B)
      d.io.axi.r.ready.poke(false.B); d.io.axi.b.ready.poke(false.B)
      d.io.wishbone.ack.poke(false.B); d.io.wishbone.err.poke(false.B); d.io.wishbone.dat_r.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.axi.w.valid.poke(true.B); d.io.axi.w.bits.data.poke(0x1234.U); d.io.axi.w.bits.strb.poke(0x45.U)
      d.clock.step(); d.io.axi.w.valid.poke(false.B); d.clock.step(3)
      d.io.wishbone.cyc.expect(false.B)
      d.io.axi.aw.valid.poke(true.B); d.io.axi.aw.bits.addr.poke(0x80000018L.U); d.io.axi.aw.bits.id.poke(2.U)
      d.clock.step(); d.io.axi.aw.valid.poke(false.B); d.clock.step()
      for (_ <- 0 until 4) {
        d.io.wishbone.cyc.expect(true.B); d.io.wishbone.we.expect(true.B)
        d.io.wishbone.adr.expect((0x80000018L >> 3).U); d.io.wishbone.sel.expect(0x45.U)
        d.io.wishbone.dat_w.expect(0x1234.U); d.clock.step()
      }
      d.io.wishbone.err.poke(true.B); d.clock.step(); d.io.wishbone.err.poke(false.B)
      for (_ <- 0 until 4) {
        d.io.axi.b.valid.expect(true.B); d.io.axi.b.bits.id.expect(2.U); d.io.axi.b.bits.resp.expect(2.U)
        d.io.wishbone.cyc.expect(false.B); d.clock.step()
      }
      d.io.axi.b.ready.poke(true.B); d.clock.step()
      d.io.axi.ar.valid.poke(true.B); d.io.axi.ar.bits.addr.poke(0x80000000L.U)
      d.io.axi.ar.bits.id.poke(3.U); d.io.axi.ar.bits.len.poke(3.U)
      d.clock.step(); d.io.axi.ar.valid.poke(false.B); d.clock.step()
      for (beat <- 0 until 4) {
        d.io.wishbone.cyc.expect(true.B); d.io.wishbone.we.expect(false.B)
        d.io.wishbone.adr.expect(((0x80000000L >> 3)+beat).U)
        d.io.wishbone.dat_r.poke((100+beat).U); d.io.wishbone.ack.poke(true.B)
        d.clock.step(); d.io.wishbone.ack.poke(false.B)
        for (_ <- 0 until 3) {
          d.io.axi.r.valid.expect(true.B); d.io.axi.r.bits.id.expect(3.U)
          d.io.axi.r.bits.data.expect((100+beat).U); d.io.axi.r.bits.last.expect((beat == 3).B)
          d.io.wishbone.cyc.expect(false.B); d.clock.step()
        }
        d.io.axi.r.ready.poke(true.B); d.clock.step(); d.io.axi.r.ready.poke(false.B)
      }
      d.io.axi.r.valid.expect(false.B); d.io.wishbone.cyc.expect(false.B)
    }
  }

  "MMIO arbitration retains owner across independent W/AW and a stalled response" in {
    simulate(new Axi4LiteArbiter(2)) { d =>
      for (c <- d.io.clients) {
        c.ar.valid.poke(false.B); c.ar.bits.addr.poke(0.U); c.ar.bits.prot.poke(0.U)
        c.aw.valid.poke(false.B); c.aw.bits.addr.poke(0.U); c.aw.bits.prot.poke(0.U)
        c.w.valid.poke(false.B); c.w.bits.data.poke(0.U); c.w.bits.strb.poke(0.U)
        c.r.ready.poke(false.B); c.b.ready.poke(false.B)
      }
      d.io.out.ar.ready.poke(true.B); d.io.out.aw.ready.poke(false.B); d.io.out.w.ready.poke(true.B)
      d.io.out.r.valid.poke(false.B); d.io.out.r.bits.data.poke(0.U); d.io.out.r.bits.resp.poke(0.U)
      d.io.out.b.valid.poke(false.B); d.io.out.b.bits.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val c = d.io.clients(0); val other = d.io.clients(1)
      c.w.valid.poke(true.B); c.w.bits.data.poke(77.U); c.w.bits.strb.poke(0xff.U)
      d.clock.step(); d.io.out.w.valid.expect(true.B); d.clock.step(); c.w.valid.poke(false.B)
      other.ar.valid.poke(true.B); other.ar.bits.addr.poke(0x200.U)
      c.aw.valid.poke(true.B); c.aw.bits.addr.poke(0x100.U)
      for (_ <- 0 until 4) { d.io.out.aw.bits.addr.expect(0x100.U); other.ar.ready.expect(false.B); d.clock.step() }
      d.io.out.aw.ready.poke(true.B); d.clock.step(); c.aw.valid.poke(false.B)
      d.io.out.b.valid.poke(true.B); d.io.out.b.bits.poke(2.U)
      for (_ <- 0 until 4) { c.b.valid.expect(true.B); other.b.valid.expect(false.B); other.ar.ready.expect(false.B); d.clock.step() }
      c.b.ready.poke(true.B); d.clock.step(); d.io.out.b.valid.poke(false.B)
      d.clock.step(); other.ar.ready.expect(true.B); d.io.out.ar.bits.addr.expect(0x200.U)
    }
  }
}
