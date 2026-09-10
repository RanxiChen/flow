package flow.mmu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.core.PRIV_MODE
import flow.interface._
import org.scalatest.freespec.AnyFreeSpec

class BreezePmpSharingSpec extends AnyFreeSpec with ChiselSim {
  "preserve walk, A/D update and final PMP checks across stalls and TLB hits" in {
    simulate(new BreezeMmu(64, entries = 4)) { dut =>
      dut.io.context.poke(0.U.asTypeOf(new BreezeMmuContext(64)))
      dut.io.context.privilege.poke(PRIV_MODE.S.U)
      dut.io.context.satp.poke(((BigInt(8) << 60) | 0x100).U)
      dut.io.context.adue.poke(true.B)
      dut.io.killI.poke(false.B)
      dut.io.sfence.poke(0.U.asTypeOf(new BreezeSfenceReq(64)))
      dut.io.i.req.valid.poke(false.B)
      dut.io.i.req.bits.poke(0.U.asTypeOf(new BreezeTranslationReq(64)))
      dut.io.i.resp.ready.poke(false.B)
      dut.io.d.req.valid.poke(false.B)
      dut.io.d.req.bits.poke(0.U.asTypeOf(new BreezeTranslationReq(64)))
      dut.io.d.resp.ready.poke(false.B)
      dut.io.memReq.ready.poke(false.B)
      dut.io.memRsp.valid.poke(false.B)
      dut.io.memRsp.bits.poke(0.U.asTypeOf(new BackendMemResp))
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)

      // Disjoint 4 KiB NAPOT regions for the root PTE and final data.
      dut.io.context.pmpaddr(0).poke(((BigInt(0x100000) >> 2) | 0x1ff).U)
      dut.io.context.pmpaddr(1).poke(((BigInt("80001000", 16) >> 2) | 0x1ff).U)
      dut.io.context.pmpcfg(0).poke(0x1b.U) // NAPOT R/W page table
      dut.io.context.pmpcfg(1).poke(0x19.U) // NAPOT read-only data
      val va = BigInt("40001238", 16)
      val pa = BigInt("80001238", 16)
      val leaf = BigInt(0x80000) << 10 // aligned 1 GiB leaf

      def await(cond: => Boolean): Unit = {
        var cycles = 0
        while (!cond && cycles < 40) { dut.clock.step(1); cycles += 1 }
        assert(cond, "MMU response/request timeout")
      }
      def issue(access: BreezeMmuAccess.Type, fetch: Boolean = false): Unit = {
        val port = if (fetch) dut.io.i else dut.io.d
        port.req.bits.vaddr.poke(va.U)
        port.req.bits.sizeLog2.poke((if (fetch) 2 else 3).U)
        port.req.bits.access.poke(access)
        port.req.valid.poke(true.B)
        port.req.ready.expect(true.B)
        dut.clock.step(1)
        port.req.valid.poke(false.B)
      }
      def memory(op: BreezeMemOp.Type, data: BigInt): Unit = {
        await(dut.io.memReq.valid.peek().litToBoolean)
        for (_ <- 0 until 3) {
          dut.io.memReq.valid.expect(true.B)
          dut.io.memReq.bits.addr.expect(0x100008.U)
          dut.io.memReq.bits.sizeLog2.expect(3.U)
          dut.io.memReq.bits.memOp.expect(op)
          dut.io.d.resp.valid.expect(false.B)
          if (op == BreezeMemOp.Amo) dut.io.memReq.bits.wdata.expect(0xc0.U)
          dut.clock.step(1)
        }
        dut.io.memReq.ready.poke(true.B); dut.clock.step(1)
        dut.io.memReq.ready.poke(false.B)
        dut.io.memRsp.bits.data.poke(data.U)
        dut.io.memRsp.valid.poke(true.B)
        dut.io.memRsp.ready.expect(true.B)
        dut.clock.step(1); dut.io.memRsp.valid.poke(false.B)
      }
      def response(fault: Boolean, fetch: Boolean = false): Unit = {
        val port = if (fetch) dut.io.i else dut.io.d
        var cycles = 0
        while (!port.resp.valid.peek().litToBoolean && cycles < 40) {
          dut.io.memReq.valid.expect(false.B)
          dut.clock.step(1); cycles += 1
        }
        port.resp.valid.expect(true.B)
        for (_ <- 0 until 3) {
          port.resp.valid.expect(true.B)
          port.resp.bits.pageFault.expect(false.B)
          port.resp.bits.accessFault.expect(fault.B)
          if (!fault) port.resp.bits.paddr.expect(pa.U)
          dut.io.memReq.valid.expect(false.B)
          dut.clock.step(1)
        }
        port.resp.ready.poke(true.B); dut.clock.step(1)
        port.resp.ready.poke(false.B)
      }
      def fence(): Unit = {
        dut.io.sfence.valid.poke(true.B); dut.clock.step(1)
        dut.io.sfence.valid.poke(false.B)
      }

      issue(BreezeMmuAccess.Load)
      memory(BreezeMemOp.Load, leaf | 0xcf) // V,R,W,A,D
      response(fault = false)
      // TLB hit must still check final write permission, with no PTW traffic.
      issue(BreezeMmuAccess.Store)
      response(fault = true)

      fence()
      dut.io.context.pmpcfg(0).poke(0x19.U) // PTE read allowed, A/D write denied
      dut.io.context.pmpcfg(1).poke(0x1b.U)
      issue(BreezeMmuAccess.Store)
      memory(BreezeMemOp.Load, leaf | 7) // missing A/D
      response(fault = true) // no AMO may be issued

      dut.io.context.pmpcfg(0).poke(0x1b.U)
      issue(BreezeMmuAccess.Store)
      memory(BreezeMemOp.Load, leaf | 7)
      memory(BreezeMemOp.Amo, 0)
      response(fault = false)

      // Bare mode still checks PMP, including execute permissions.
      dut.io.context.satp.poke(0.U)
      dut.io.context.pmpaddr(1).poke(((va & ~BigInt(0xfff)) >> 2 | 0x1ff).U)
      issue(BreezeMmuAccess.Fetch, fetch = true)
      response(fault = true, fetch = true)

      // Cancellation while a translated fetch is waiting for its PTE.
      dut.io.context.satp.poke(((BigInt(8) << 60) | 0x100).U)
      issue(BreezeMmuAccess.Fetch, fetch = true)
      dut.io.killI.poke(true.B); dut.clock.step(1); dut.io.killI.poke(false.B)
      memory(BreezeMemOp.Load, leaf | 0xcf)
      for (_ <- 0 until 8) { dut.io.i.resp.valid.expect(false.B); dut.clock.step(1) }
      dut.io.i.req.ready.expect(true.B)
    }
  }
}
