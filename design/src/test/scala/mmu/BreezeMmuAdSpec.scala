package flow.mmu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.core.PRIV_MODE
import flow.interface._
import org.scalatest.freespec.AnyFreeSpec

class BreezeMmuAdSpec extends AnyFreeSpec with ChiselSim {
  private def init(dut: BreezeMmu): Unit = {
    dut.io.context.poke(0.U.asTypeOf(new BreezeMmuContext(64)))
    dut.io.context.privilege.poke(PRIV_MODE.S.U)
    dut.io.context.satp.poke(((BigInt(8) << 60) | 0x100).U)
    dut.io.context.adue.poke(true.B)
    dut.io.context.pmpcfg(0).poke(0x0f.U)
    dut.io.context.pmpaddr(0).poke(((BigInt(1) << 54) - 1).U)
    dut.io.killI.poke(false.B)
    dut.io.sfence.poke(0.U.asTypeOf(new BreezeSfenceReq(64)))
    for (port <- Seq(dut.io.i, dut.io.d)) {
      port.req.valid.poke(false.B)
      port.req.bits.poke(0.U.asTypeOf(new BreezeTranslationReq(64)))
      port.resp.ready.poke(false.B)
    }
    dut.io.memReq.ready.poke(true.B)
    dut.io.memRsp.valid.poke(false.B)
    dut.io.memRsp.bits.poke(0.U.asTypeOf(new BackendMemResp))
    dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
  }

  "update a clean TLB hit and retry a concurrently replaced leaf" in {
    simulate(new BreezeMmu(64, entries = 4)) { dut =>
      init(dut)
      val va = BigInt("40001238", 16)
      val first = (BigInt(0x80000) << 10) | 7 // 1 GiB leaf, A=D=0
      val replacement = (BigInt(0xc0000) << 10) | 7
      var pte = first
      var updates = 0
      var reads = 0
      var replaceOnUpdate = false

      def translate(access: BreezeMmuAccess.Type, pa: BigInt): Unit = {
        dut.io.d.req.bits.vaddr.poke(va.U)
        dut.io.d.req.bits.access.poke(access)
        dut.io.d.req.bits.sizeLog2.poke(3.U)
        dut.io.d.req.valid.poke(true.B)
        dut.io.d.req.ready.expect(true.B)
        dut.clock.step(); dut.io.d.req.valid.poke(false.B)
        var cycles = 0
        while (!dut.io.d.resp.valid.peek().litToBoolean && cycles < 100) {
          if (dut.io.memReq.valid.peek().litToBoolean) {
            dut.io.memReq.bits.addr.expect(0x100008.U)
            val isUpdate = dut.io.memReq.bits.memOp.peek().litValue == BreezeMemOp.Amo.litValue
            if (isUpdate && replaceOnUpdate) { pte = replacement; replaceOnUpdate = false }
            val old = pte
            if (isUpdate) {
              updates += 1
              dut.io.memReq.bits.amoFunc.expect(BreezeAmoFunc.PteSetAd)
              val expected = dut.io.memReq.bits.wdata.peek().litValue
              val mask = dut.io.memReq.bits.wmask.peek().litValue
              assert(mask == (if (access == BreezeMmuAccess.Store) 0xc0 else 0x40))
              if (expected == old) pte |= mask
            } else reads += 1
            dut.clock.step()
            dut.io.memRsp.bits.data.poke(old.U)
            dut.io.memRsp.valid.poke(true.B)
            dut.io.memRsp.ready.expect(true.B)
            dut.clock.step(); dut.io.memRsp.valid.poke(false.B)
          } else dut.clock.step()
          cycles += 1
        }
        assert(cycles < 100)
        dut.io.d.resp.bits.pageFault.expect(false.B)
        dut.io.d.resp.bits.accessFault.expect(false.B)
        dut.io.d.resp.bits.paddr.expect(pa.U)
        dut.io.d.resp.ready.poke(true.B); dut.clock.step()
        dut.io.d.resp.ready.poke(false.B)
      }
      translate(BreezeMmuAccess.Load, BigInt("80001238", 16))
      assert(pte == (first | 0x40) && updates == 1)
      replaceOnUpdate = true
      translate(BreezeMmuAccess.Store, BigInt("c0001238", 16))
      assert(pte == (replacement | 0xc0) && updates == 3 && reads == 3)
      // The stale clean entry must not win over the newly refilled mapping.
      translate(BreezeMmuAccess.Store, BigInt("c0001238", 16))
      assert(updates == 3 && reads == 3)
    }
  }

  for (reservedBit <- Seq(4, 6, 7)) {
    s"reject non-leaf PTE reserved bit $reservedBit before reading the next level" in {
      simulate(new BreezeMmu(64, entries = 4)) { dut =>
        init(dut)
        dut.io.d.req.bits.vaddr.poke(0x40000000L.U)
        dut.io.d.req.bits.access.poke(BreezeMmuAccess.Load)
        dut.io.d.req.bits.sizeLog2.poke(3.U)
        dut.io.d.req.valid.poke(true.B); dut.clock.step()
        dut.io.d.req.valid.poke(false.B)
        var cycles = 0
        while (!dut.io.memReq.valid.peek().litToBoolean && cycles < 30) {
          dut.clock.step(); cycles += 1
        }
        assert(cycles < 30)
        dut.clock.step()
        dut.io.memRsp.bits.data.poke(((BigInt(0x101) << 10) | 1 | (BigInt(1) << reservedBit)).U)
        dut.io.memRsp.valid.poke(true.B); dut.clock.step()
        dut.io.memRsp.valid.poke(false.B)
        cycles = 0
        while (!dut.io.d.resp.valid.peek().litToBoolean && cycles < 30) {
          dut.io.memReq.valid.expect(false.B)
          dut.clock.step(); cycles += 1
        }
        assert(cycles < 30)
        dut.io.d.resp.bits.pageFault.expect(true.B)
        dut.io.d.resp.bits.accessFault.expect(false.B)
      }
    }
  }

  "ignore empty TOR entries even when an access straddles their boundaries" in {
    simulate(new BreezePmpChecker(64)) { dut =>
      dut.io.context.poke(0.U.asTypeOf(new BreezeMmuContext(64)))
      dut.io.privilege.poke(PRIV_MODE.S.U)
      dut.io.access.poke(BreezeMmuAccess.Load)
      dut.io.addr.poke(0x1000.U)
      dut.io.sizeLog2.poke(4.U)
      dut.io.context.pmpaddr(0).poke((0x1008 >> 2).U)
      dut.io.context.pmpcfg(1).poke(0x08.U) // empty TOR, no permissions
      dut.io.context.pmpcfg(2).poke(0x19.U) // allow all using NAPOT
      dut.io.context.pmpaddr(2).poke(((BigInt(1) << 54) - 1).U)
      for (upper <- Seq(0x1008, 0x1004)) {
        dut.io.context.pmpaddr(1).poke((upper >> 2).U)
        dut.io.allowed.expect(true.B)
      }
    }
  }
}
