package flow.cache

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import flow.l1i.{L1ICache, L1IParams}
import flow.interface._
import org.scalatest.freespec.AnyFreeSpec

// Export actual SRAM controls, not just the new request handshake: the tests
// prove that arrays were read before translation and are not read again on reuse.
class ParallelICacheHarness extends L1ICache(L1IParams(), parallelLookup = true) {
  val observed = IO(new Bundle {
    val read = Output(Bool())
    val saved = Output(Bool())
    val reuse = Output(Bool())
  })
  observed.read := data_array.head.io.re
  observed.saved := snapshotValid
  observed.reuse := s1_useSnapshot
}

class BreezeParallelLookupSpec extends AnyFreeSpec with ChiselSim {
  "I-cache reads a virtual index before authorization, compares physical tags and discards on flush" in {
    simulate(new ParallelICacheHarness) { d =>
      d.io.arrayReq.get.valid.poke(false.B); d.io.arrayReq.get.bits.poke(0.U)
      d.io.dreq.valid.poke(false.B)
      d.io.dreq.bits.vaddr.poke(0.U); d.io.dreq.bits.paddr.poke(0.U)
      d.io.drsp.ready.poke(true.B); d.io.flush.poke(false.B)
      d.io.next_level_rsp.vld.poke(false.B)
      d.io.next_level_rsp.data.poke(0.U); d.io.next_level_rsp.error.poke(false.B)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      val va = BigInt("40000000", 16)
      val pa = BigInt("10000000", 16)
      def early(): Unit = {
        d.io.arrayReq.get.bits.poke(va.U); d.io.arrayReq.get.valid.poke(true.B)
        d.io.arrayReq.get.ready.expect(true.B); d.observed.read.expect(true.B)
        d.clock.step(); d.io.arrayReq.get.valid.poke(false.B); d.clock.step()
        d.observed.saved.expect(true.B)
      }
      def request(paddr: BigInt, reuse: Boolean): Unit = {
        d.io.dreq.bits.vaddr.poke(va.U); d.io.dreq.bits.paddr.poke(paddr.U)
        d.io.dreq.valid.poke(true.B); d.io.dreq.ready.expect(true.B)
        if (reuse) d.observed.read.expect(false.B)
        d.clock.step(); d.io.dreq.valid.poke(false.B)
        d.observed.reuse.expect(reuse.B)
      }
      def refill(paddr: BigInt, data: BigInt): Unit = {
        var guard = 0
        while (!d.io.next_level_req.req.peek().litToBoolean && guard < 20) { d.clock.step(); guard += 1 }
        assert(guard < 20)
        d.io.next_level_req.paddr.expect(paddr.U)
        d.clock.step(); d.io.next_level_rsp.data.poke(data.U)
        d.io.next_level_rsp.vld.poke(true.B); d.clock.step()
        d.io.next_level_rsp.vld.poke(false.B)
        d.io.drsp.valid.expect(true.B); d.io.drsp.bits.data.expect((data & 0xffffffffL).U)
        d.clock.step(2)
      }
      early()
      for (_ <- 0 until 8) { d.io.next_level_req.req.expect(false.B); d.io.drsp.valid.expect(false.B); d.clock.step() }
      request(pa, true); refill(pa, BigInt("12345678", 16))
      early(); request(pa, true)
      d.io.drsp.valid.expect(true.B); d.io.drsp.bits.data.expect("h12345678".U)
      d.clock.step(2)
      early(); request(pa + 4096, true); refill(pa + 4096, BigInt("76543210", 16))
      early(); d.io.flush.poke(true.B); d.clock.step(); d.io.flush.poke(false.B)
      d.observed.saved.expect(false.B)
      request(pa, false); refill(pa, BigInt("abcdef12", 16))
      // Early array reads are harmless even if later physical PMA denies.
      early(); request(BigInt("20000000", 16), true)
      d.io.drsp.valid.expect(true.B); d.io.drsp.bits.accessFault.expect(true.B)
      d.io.next_level_req.req.expect(false.B)
    }
  }
}
