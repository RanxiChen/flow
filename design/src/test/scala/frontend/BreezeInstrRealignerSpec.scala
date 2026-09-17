package flow.frontend

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class BreezeInstrRealignerSpec extends AnyFreeSpec with ChiselSim {
  "assemble compressed, aligned, and cross-word instructions" in {
    simulate(new BreezeInstrRealigner(64)) { dut =>
      dut.io.redirect.poke(false.B)
      dut.io.req.valid.poke(false.B)
      dut.io.resp.ready.poke(true.B)
      dut.io.wordReq.ready.poke(true.B)
      dut.io.wordRsp.valid.poke(false.B)
      dut.io.wordRsp.bits.vaddr.poke(0.U)
      dut.io.wordRsp.bits.data.poke(0.U)
      dut.io.wordRsp.bits.accessFault.poke(false.B)
      dut.io.wordRsp.bits.pageFault.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)

      def request(pc: BigInt, words: Map[BigInt, BigInt]): (BigInt, BigInt) = {
        dut.io.req.bits.pc.poke(pc.U); dut.io.req.valid.poke(true.B)
        while (!dut.io.req.ready.peek().litToBoolean) dut.clock.step(1)
        dut.clock.step(1); dut.io.req.valid.poke(false.B)
        var guard = 0
        while (!dut.io.resp.valid.peek().litToBoolean && guard < 40) {
          if (dut.io.wordReq.valid.peek().litToBoolean) {
            val addr = dut.io.wordReq.bits.vaddr.peekValue().asBigInt
            dut.clock.step(1)
            dut.io.wordRsp.bits.vaddr.poke(addr.U)
            dut.io.wordRsp.bits.data.poke(words(addr).U)
            dut.io.wordRsp.valid.poke(true.B)
            dut.clock.step(1)
            dut.io.wordRsp.valid.poke(false.B)
          } else dut.clock.step(1)
          guard += 1
        }
        assert(guard < 40)
        val raw = dut.io.resp.bits.rawInst.peekValue().asBigInt
        val len = dut.io.resp.bits.instLen.peekValue().asBigInt
        dut.clock.step(1)
        (raw, len)
      }

      assert(request(0x1000, Map(BigInt(0x1000) -> BigInt("00930001", 16))) ==
        (BigInt(1), BigInt(2)))
      assert(request(0x1002, Map(BigInt(0x1000) -> BigInt("00930001", 16),
                          BigInt(0x1004) -> BigInt("12340010", 16))) ==
        (BigInt("00100093", 16), BigInt(4)))
    }
  }

  for (pageFault <- Seq(false, true)) {
    s"report the second parcel address for cross-page fetch fault page=$pageFault" in {
      simulate(new BreezeInstrRealigner(64)) { dut =>
        dut.io.redirect.poke(false.B)
        dut.io.req.valid.poke(false.B)
        dut.io.resp.ready.poke(false.B)
        dut.io.wordReq.ready.poke(true.B)
        dut.io.wordRsp.valid.poke(false.B)
        dut.io.wordRsp.bits.vaddr.poke(0.U)
        dut.io.wordRsp.bits.data.poke(0.U)
        dut.io.wordRsp.bits.accessFault.poke(false.B)
        dut.io.wordRsp.bits.pageFault.poke(false.B)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        dut.io.req.bits.pc.poke(0x1ffe.U)
        dut.io.req.valid.poke(true.B); dut.clock.step()
        dut.io.req.valid.poke(false.B)
        for (addr <- Seq(0x1ffc, 0x2000)) {
          var guard = 0
          while (!dut.io.wordReq.valid.peek().litToBoolean && guard < 20) {
            dut.clock.step(); guard += 1
          }
          assert(guard < 20)
          dut.io.wordReq.bits.vaddr.expect(addr.U)
          dut.clock.step()
          dut.io.wordRsp.bits.vaddr.poke(addr.U)
          dut.io.wordRsp.bits.data.poke(BigInt("00930000", 16).U)
          dut.io.wordRsp.bits.accessFault.poke((addr == 0x2000 && !pageFault).B)
          dut.io.wordRsp.bits.pageFault.poke((addr == 0x2000 && pageFault).B)
          dut.io.wordRsp.valid.poke(true.B); dut.clock.step()
          dut.io.wordRsp.valid.poke(false.B)
        }
        dut.io.resp.valid.expect(true.B)
        dut.io.resp.bits.pc.expect(0x1ffe.U)
        dut.io.resp.bits.faultVaddr.expect(0x2000.U)
        dut.io.resp.bits.pageFault.expect(pageFault.B)
        dut.io.resp.bits.accessFault.expect((!pageFault).B)
      }
    }
  }
}
