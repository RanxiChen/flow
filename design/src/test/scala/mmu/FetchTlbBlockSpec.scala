package flow.mmu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.interface._
import flow.l1i.FetchTlbClient
import org.scalatest.freespec.AnyFreeSpec

class FetchTlbBlockSpec extends AnyFreeSpec with ChiselSim {
  "BE2 SFENCE blocks queued and retry translations until kill or unblock" in {
    simulate(new FetchTlbClient) { d =>
      d.io.context.poke(0.U.asTypeOf(new BreezeMmuContext(64)))
      d.io.request.bits.poke(0.U.asTypeOf(new BreezeTranslationReq(64)))
      d.io.request.bits.access.poke(BreezeMmuAccess.Fetch)
      d.io.request.bits.vaddr.poke(0x400.U)
      d.io.request.valid.poke(false.B); d.io.response.ready.poke(true.B)
      d.io.tlb.req.ready.poke(true.B); d.io.tlb.resp.valid.poke(false.B)
      d.io.tlb.resp.bits.poke(0.U.asTypeOf(d.io.tlb.resp.bits))
      d.io.block.poke(false.B); d.io.kill.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      def queue(): Unit = {
        d.io.request.valid.poke(true.B); d.io.request.ready.expect(true.B)
        d.clock.step(); d.io.request.valid.poke(false.B)
      }
      def blocked(): Unit = {
        d.io.block.poke(true.B)
        for (_ <- 0 until 5) {
          d.io.request.ready.expect(false.B); d.io.tlb.req.valid.expect(false.B)
          d.io.response.valid.expect(false.B); d.clock.step()
        }
      }
      queue(); blocked()
      d.io.block.poke(false.B); d.io.tlb.req.valid.expect(true.B)
      d.io.tlb.req.bits.vaddr.expect(0x400.U); d.clock.step()
      d.io.block.poke(true.B); d.io.tlb.resp.valid.poke(true.B)
      d.io.tlb.resp.bits.miss.poke(true.B); d.clock.step()
      d.io.tlb.resp.valid.poke(false.B); blocked()
      d.io.kill.poke(true.B); d.clock.step(); d.io.kill.poke(false.B)
      d.io.tlb.req.valid.expect(false.B); d.io.response.valid.expect(false.B)
      d.io.block.poke(false.B); d.io.request.ready.expect(true.B)
      queue(); d.io.tlb.req.valid.expect(true.B); d.clock.step()
      d.io.tlb.resp.valid.poke(true.B); d.io.tlb.resp.bits.miss.poke(false.B)
      d.io.tlb.resp.bits.hit.poke(true.B); d.io.tlb.resp.bits.paddr.poke(0x800.U)
      d.clock.step(); d.io.tlb.resp.valid.poke(false.B)
      d.io.response.valid.expect(true.B); d.io.response.bits.vaddr.expect(0x400.U)
      d.io.response.bits.paddr.expect(0x800.U); d.clock.step()
      d.io.request.ready.expect(true.B)
    }
  }
}
