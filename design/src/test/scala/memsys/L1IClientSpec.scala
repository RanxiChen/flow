package flow.memsys

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.coherence._
import flow.config.BreezeMemGeometry
import flow.l1i.L1IClient
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class L1IClientSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private def init(d: L1IClient): Unit = {
    d.io.demand.req.poke(false.B); d.io.demand.paddr.poke(0.U)
    d.io.prefetch.valid.poke(false.B); d.io.prefetch.bits.poke(0.U)
    d.io.coh.req.ready.poke(false.B); d.io.coh.rspDown.valid.poke(false.B)
    d.io.coh.rspDown.bits.op.poke(RspDownOp.ReadData); d.io.coh.rspDown.bits.id.poke(0.U)
    d.io.coh.rspDown.bits.error.poke(false.B); d.io.coh.rspDown.bits.data.poke(0.U)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
  }
  "two Read IDs stay stable under backpressure and demultiplex reordered data and errors" in {
    simulate(new L1IClient(BreezeMemGeometry.singleCore)) { d =>
      init(d)
      d.io.prefetch.bits.poke(0x80000020L.U); d.io.prefetch.valid.poke(true.B)
      d.io.prefetch.ready.expect(true.B); d.clock.step(); d.io.prefetch.valid.poke(false.B)
      d.clock.step(); d.io.coh.req.valid.expect(true.B); d.io.coh.req.bits.id.expect(1.U)
      d.io.demand.req.poke(true.B); d.io.demand.paddr.poke(0x80000000L.U); d.clock.step()
      d.io.demand.req.poke(false.B)
      for(_ <- 0 until 5) {
        d.io.coh.req.valid.expect(true.B); d.io.coh.req.bits.op.expect(ReqOp.Read)
        d.io.coh.req.bits.id.expect(1.U); d.io.coh.req.bits.addr.expect((0x80000020L >> 5).U)
        d.clock.step()
      }
      d.io.coh.req.ready.poke(true.B); d.clock.step(); d.clock.step()
      d.io.coh.req.valid.expect(true.B); d.io.coh.req.bits.id.expect(0.U)
      d.io.coh.req.bits.addr.expect((0x80000000L >> 5).U); d.clock.step()
      d.io.coh.rspDown.valid.poke(true.B); d.io.coh.rspDown.bits.id.poke(0.U)
      d.io.coh.rspDown.bits.data.poke(0x1234.U)
      d.io.demandRsp.vld.expect(true.B); d.io.demandRsp.data.expect(0x1234.U)
      d.io.demandRsp.error.expect(false.B); d.io.prefetchRsp.valid.expect(false.B); d.clock.step()
      d.io.coh.rspDown.bits.id.poke(1.U); d.io.coh.rspDown.bits.error.poke(true.B)
      d.io.demandRsp.vld.expect(false.B); d.io.prefetchRsp.valid.expect(true.B)
      d.io.prefetchRsp.bits.error.expect(true.B); d.clock.step()
      d.io.coh.rspDown.valid.poke(false.B); d.io.prefetch.ready.expect(true.B)
    }
  }
  "a high physical address returns an error without a wrapped coherence request" in {
    simulate(new L1IClient(BreezeMemGeometry.singleCore)) { d =>
      init(d); d.io.demand.req.poke(true.B); d.io.demand.paddr.poke(BigInt("180000000",16).U)
      d.clock.step(); d.io.demand.req.poke(false.B)
      d.io.demandRsp.vld.expect(true.B); d.io.demandRsp.error.expect(true.B)
      for(_ <- 0 until 6) { d.io.coh.req.valid.expect(false.B); d.clock.step() }
    }
  }
}
