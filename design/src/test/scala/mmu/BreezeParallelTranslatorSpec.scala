package flow.mmu

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.frontend.BreezeFetchTranslator
import flow.interface._
import org.scalatest.freespec.AnyFreeSpec

class BreezeParallelTranslatorSpec extends AnyFreeSpec with ChiselSim {
  "data translation and array lookup start together but a denied store never reaches memory" in {
    simulate(new BreezeDataTranslator(64, parallelLookup = true)) { d =>
      d.io.cpu.req.poke(0.U.asTypeOf(new BackendMemReq(64)))
      d.io.translateReq.ready.poke(false.B)
      d.io.translateRsp.valid.poke(false.B)
      d.io.translateRsp.bits.poke(0.U.asTypeOf(new BreezeTranslationResp(64)))
      d.io.arrayReq.get.ready.poke(false.B)
      d.io.memReq.ready.poke(false.B)
      d.io.memRsp.valid.poke(false.B)
      d.io.memRsp.bits.poke(0.U.asTypeOf(new BackendMemResp))
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val va = BigInt("40000120", 16)
      val pa = BigInt("11000120", 16)
      def issue(op: BreezeMemOp.Type): Unit = {
        d.io.cpu.req.addr.poke(va.U); d.io.cpu.req.memOp.poke(op)
        d.io.cpu.req.isWrite.poke((op == BreezeMemOp.Store).B)
        d.io.cpu.req.sizeLog2.poke(3.U)
        d.io.cpu.req.valid.poke(true.B); d.clock.step(); d.io.cpu.req.valid.poke(false.B)
        d.io.translateReq.valid.expect(true.B)
        d.io.arrayReq.get.valid.expect(true.B); d.io.arrayReq.get.bits.expect(va.U)
        d.io.memReq.valid.expect(false.B)
        d.io.arrayReq.get.ready.poke(true.B); d.clock.step()
        d.io.arrayReq.get.valid.expect(false.B)
        d.io.translateReq.ready.poke(true.B); d.clock.step(); d.io.translateReq.ready.poke(false.B)
        for (_ <- 0 until 5) {
          d.io.memReq.valid.expect(false.B); d.io.arrayReq.get.valid.expect(false.B); d.clock.step()
        }
      }
      issue(BreezeMemOp.Store)
      d.io.translateRsp.bits.accessFault.poke(true.B)
      d.io.translateRsp.valid.poke(true.B); d.clock.step(); d.io.translateRsp.valid.poke(false.B)
      d.io.cpu.rsp.valid.expect(true.B); d.io.cpu.rsp.error.expect(true.B)
      d.io.memReq.valid.expect(false.B); d.clock.step()
      issue(BreezeMemOp.Load)
      d.io.translateRsp.bits.accessFault.poke(false.B)
      d.io.translateRsp.bits.paddr.poke(pa.U)
      d.io.translateRsp.valid.poke(true.B); d.clock.step(); d.io.translateRsp.valid.poke(false.B)
      for (_ <- 0 until 4) {
        d.io.memReq.valid.expect(true.B); d.io.memReq.bits.addr.expect(pa.U); d.clock.step()
      }
      d.io.memReq.ready.poke(true.B); d.clock.step()
      d.io.memReq.valid.expect(false.B)
      d.io.memRsp.valid.poke(true.B); d.io.memRsp.bits.data.poke(42.U)
      d.clock.step(); d.io.memRsp.valid.poke(false.B)
      d.io.cpu.rsp.valid.expect(true.B); d.io.cpu.rsp.data.expect(42.U)
    }
  }

  "instruction translation overlaps array lookup and redirect cancels the pending fetch" in {
    simulate(new BreezeFetchTranslator(64, parallelLookup = true)) { d =>
      d.io.inReq.valid.poke(false.B)
      d.io.inReq.bits.poke(0.U.asTypeOf(new BreezeCacheReqIO(64)))
      d.io.inRsp.ready.poke(true.B)
      d.io.cacheReq.ready.poke(false.B)
      d.io.cacheRsp.valid.poke(false.B)
      d.io.cacheRsp.bits.poke(0.U.asTypeOf(new BreezeCacheRespIO(64, 32)))
      d.io.translateReq.ready.poke(false.B)
      d.io.translateRsp.valid.poke(false.B)
      d.io.translateRsp.bits.poke(0.U.asTypeOf(new BreezeTranslationResp(64)))
      d.io.arrayReq.get.ready.poke(true.B); d.io.kill.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val va = BigInt("40000000", 16)
      def issue(): Unit = {
        d.io.inReq.bits.vaddr.poke(va.U); d.io.inReq.valid.poke(true.B)
        d.clock.step(); d.io.inReq.valid.poke(false.B)
        d.io.translateReq.valid.expect(true.B); d.io.arrayReq.get.valid.expect(true.B)
        d.io.arrayReq.get.bits.expect(va.U); d.io.cacheReq.valid.expect(false.B)
        d.clock.step(); d.io.arrayReq.get.valid.expect(false.B)
      }
      issue()
      d.io.kill.poke(true.B); d.clock.step(); d.io.kill.poke(false.B)
      d.io.cacheReq.valid.expect(false.B); d.io.inRsp.valid.expect(false.B)
      issue()
      d.io.translateReq.ready.poke(true.B); d.clock.step(); d.io.translateReq.ready.poke(false.B)
      d.io.translateRsp.bits.paddr.poke("h10000000".U)
      d.io.translateRsp.valid.poke(true.B); d.clock.step(); d.io.translateRsp.valid.poke(false.B)
      for (_ <- 0 until 4) {
        d.io.cacheReq.valid.expect(true.B)
        d.io.cacheReq.bits.vaddr.expect(va.U); d.io.cacheReq.bits.paddr.expect("h10000000".U)
        d.clock.step()
      }
      d.io.cacheReq.ready.poke(true.B); d.clock.step()
      d.io.cacheReq.valid.expect(false.B)
      d.io.cacheRsp.valid.poke(true.B); d.io.cacheRsp.bits.data.poke("h13".U)
      d.io.inRsp.valid.expect(true.B); d.io.inRsp.bits.data.expect("h13".U)
    }
  }
}
