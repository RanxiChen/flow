package flow.mmu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.core.PRIV_MODE
import flow.interface._
import org.scalatest.freespec.AnyFreeSpec

class BreezeMmuSpec extends AnyFreeSpec with ChiselSim {
  "walk a three-level Sv39 page table and refill the DTLB" in {
    simulate(new BreezeMmu(64, entries = 4)) { dut =>
      dut.io.killI.poke(false.B)
      dut.io.sfence.poke(0.U.asTypeOf(new BreezeSfenceReq(64)))
      dut.io.context.satp.poke(((BigInt(8) << 60) | BigInt(0x100)).U)
      dut.io.context.privilege.poke(PRIV_MODE.S.U)
      dut.io.context.mprv.poke(false.B)
      dut.io.context.mpp.poke(PRIV_MODE.M.U)
      dut.io.context.sum.poke(false.B)
      dut.io.context.mxr.poke(false.B)
      for (n <- 0 until 16) {
        dut.io.context.pmpcfg(n).poke((if (n == 0) 0x0f else 0).U)
        dut.io.context.pmpaddr(n).poke(
          (if (n == 0) (BigInt(1) << 54) - 1 else BigInt(0)).U)
      }
      dut.io.i.req.valid.poke(false.B); dut.io.i.resp.ready.poke(true.B)
      dut.io.d.req.valid.poke(false.B); dut.io.d.resp.ready.poke(true.B)
      dut.io.memReq.ready.poke(true.B)
      dut.io.memRsp.valid.poke(false.B)
      dut.io.memRsp.bits.poke(0.U.asTypeOf(new BackendMemResp))
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)

      val va = BigInt("40001234", 16)
      val ptes = Map(
        BigInt("100008", 16) -> ((BigInt(0x101) << 10) | 1),
        BigInt("101000", 16) -> ((BigInt(0x102) << 10) | 1),
        BigInt("102008", 16) -> ((BigInt(0x80001) << 10) | 0xcf))
      dut.io.d.req.bits.vaddr.poke(va.U)
      dut.io.d.req.bits.access.poke(BreezeMmuAccess.Load)
      dut.io.d.req.bits.sizeLog2.poke(3.U)
      dut.io.d.req.valid.poke(true.B)
      while (!dut.io.d.req.ready.peek().litToBoolean) dut.clock.step(1)
      dut.clock.step(1); dut.io.d.req.valid.poke(false.B)

      var guard = 0
      while (!dut.io.d.resp.valid.peek().litToBoolean && guard < 80) {
        if (dut.io.memReq.valid.peek().litToBoolean) {
          val addr = dut.io.memReq.bits.addr.peekValue().asBigInt
          assert(ptes.contains(addr), f"unexpected PTW address 0x$addr%x")
          dut.clock.step(1)
          dut.io.memRsp.bits.valid.poke(true.B)
          dut.io.memRsp.bits.data.poke(ptes(addr).U)
          dut.io.memRsp.bits.isWriteAck.poke(false.B)
          dut.io.memRsp.bits.error.poke(false.B)
          dut.io.memRsp.bits.pageFault.poke(false.B)
          dut.io.memRsp.bits.faultAddr.poke(0.U)
          dut.io.memRsp.valid.poke(true.B)
          dut.clock.step(1)
          dut.io.memRsp.valid.poke(false.B)
        } else dut.clock.step(1)
        guard += 1
      }
      assert(guard < 80)
      dut.io.d.resp.bits.pageFault.expect(false.B)
      dut.io.d.resp.bits.accessFault.expect(false.B)
      dut.io.d.resp.bits.paddr.expect(BigInt("80001234", 16).U)
    }
  }

  "reject a non-canonical Sv39 address before issuing a PTW request" in {
    simulate(new BreezeMmu(64, entries = 4)) { dut =>
      dut.io.killI.poke(false.B)
      dut.io.sfence.poke(0.U.asTypeOf(new BreezeSfenceReq(64)))
      dut.io.context.poke(0.U.asTypeOf(new BreezeMmuContext(64)))
      dut.io.context.satp.poke((BigInt(8) << 60).U)
      dut.io.context.privilege.poke(PRIV_MODE.S.U)
      dut.io.i.req.valid.poke(false.B); dut.io.i.resp.ready.poke(true.B)
      dut.io.d.req.valid.poke(false.B); dut.io.d.resp.ready.poke(true.B)
      dut.io.memReq.ready.poke(true.B); dut.io.memRsp.valid.poke(false.B)
      dut.io.memRsp.bits.poke(0.U.asTypeOf(new BackendMemResp))
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
      dut.io.i.req.bits.vaddr.poke(BigInt("0000008000000000", 16).U)
      dut.io.i.req.bits.access.poke(BreezeMmuAccess.Fetch)
      dut.io.i.req.bits.sizeLog2.poke(2.U)
      dut.io.i.req.valid.poke(true.B); dut.clock.step(1); dut.io.i.req.valid.poke(false.B)
      while (!dut.io.i.resp.valid.peek().litToBoolean) {
        dut.io.memReq.valid.expect(false.B); dut.clock.step(1)
      }
      dut.io.i.resp.bits.pageFault.expect(true.B)
    }
  }
}
