package flow.mmu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.interface._
import flow.l1i.FetchTlbClient
import org.scalatest.freespec.AnyFreeSpec

class FetchPmpCandidateSpec extends AnyFreeSpec with ChiselSim {
  "SOC3d fetch candidate checks execute bounds but cannot authorize a miss or page fault" in {
    simulate(new FetchTlbClient(withPmpCandidate = true)) { d =>
      d.io.context.poke(0.U.asTypeOf(new BreezeMmuContext(64)))
      d.io.context.privilege.poke(1.U)
      d.io.context.pmpcfg(0).poke(0x1d.U) // NAPOT R/X, 128 bytes at 0x80000000
      d.io.context.pmpaddr(0).poke(((BigInt("80000000", 16) >> 2) | 15).U)
      d.io.kill.poke(false.B); d.io.block.poke(false.B)
      d.io.request.valid.poke(false.B)
      d.io.request.bits.poke(0.U.asTypeOf(new BreezeTranslationReq(64)))
      d.io.request.bits.access.poke(BreezeMmuAccess.Fetch)
      d.io.response.ready.poke(false.B)
      d.io.tlb.req.ready.poke(false.B); d.io.tlb.resp.valid.poke(false.B)
      d.io.tlb.resp.bits.poke(0.U.asTypeOf(d.io.tlb.resp.bits))
      d.io.tlb.candidatePaddr.get.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val va = BigInt("4000007e", 16); val pa = BigInt("8000007e", 16)
      def issue(size: Int): Unit = {
        d.io.request.bits.vaddr.poke(va.U); d.io.request.bits.sizeLog2.poke(size.U)
        d.io.request.valid.poke(true.B); d.io.request.ready.expect(true.B)
        d.clock.step(); d.io.request.valid.poke(false.B)
        for (_ <- 0 until 3) {
          d.io.tlb.req.valid.expect(true.B); d.io.tlb.req.bits.vaddr.expect(va.U)
          d.clock.step()
        }
        d.io.tlb.req.ready.poke(true.B); d.clock.step(); d.io.tlb.req.ready.poke(false.B)
      }
      def reply(hit: Boolean, pageFault: Boolean = false, miss: Boolean = false): Unit = {
        d.io.tlb.resp.bits.hit.poke(hit.B); d.io.tlb.resp.bits.pageFault.poke(pageFault.B)
        d.io.tlb.resp.bits.miss.poke(miss.B)
        d.io.tlb.resp.bits.paddr.poke((if (hit) pa else BigInt(0)).U)
        d.io.tlb.candidatePaddr.get.poke(pa.U); d.io.tlb.resp.valid.poke(true.B)
        d.clock.step(); d.io.tlb.resp.valid.poke(false.B)
      }
      def result(fault: Boolean, pageFault: Boolean = false): Unit = {
        // The response owns its original address and flags after the TLB
        // candidate changes, including while the frontend backpressures it.
        d.io.tlb.candidatePaddr.get.poke(0.U)
        for (_ <- 0 until 4) {
          d.io.response.valid.expect(true.B); d.io.response.bits.vaddr.expect(va.U)
          d.io.response.bits.paddr.expect((if (pageFault) BigInt(0) else pa).U)
          d.io.response.bits.accessFault.expect(fault.B); d.io.response.bits.pageFault.expect(pageFault.B)
          d.clock.step()
        }
        d.io.response.ready.poke(true.B); d.clock.step(); d.io.response.ready.poke(false.B)
      }
      issue(1); reply(hit = true); result(fault = false) // ends at 0x7f
      issue(2); reply(hit = true); result(fault = true)  // carries past PMP upper bound
      issue(1); reply(hit = false, pageFault = true); result(fault = false, pageFault = true)
      issue(1); reply(hit = false, miss = true)
      d.io.response.valid.expect(false.B); d.io.tlb.req.valid.expect(true.B)
      d.io.tlb.req.ready.poke(true.B); d.clock.step(); d.io.tlb.req.ready.poke(false.B)
      reply(hit = true); result(fault = false)
      issue(1); reply(hit = true)
      d.io.kill.poke(true.B); d.io.response.valid.expect(false.B)
      d.clock.step(); d.io.kill.poke(false.B); d.io.request.ready.expect(true.B)
    }
  }
}
