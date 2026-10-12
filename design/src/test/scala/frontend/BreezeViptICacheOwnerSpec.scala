package flow.frontend

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.BreezeMemGeometry
import flow.interface._
import flow.mmu.sv39._
import org.scalatest.freespec.AnyFreeSpec

/** Actual pipelined iTLB; Bare/M-mode isolates return ownership from PTW. */
class ViptOwnerHarness extends Module {
  private val p = BreezeFrontendPipelineParams()
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new PipelineFetchRequest(p.historyBits)))
    val response = Decoupled(new PipelineFetchWindow(p))
    val cancel = Input(Bool()); val cancelYounger = Input(Bool()); val fenceI = Input(Bool())
    val refillReq = new L1CacheMissReqIO(64)
    val refillRsp = new L1CacheMissRespIO(256)
    val f1 = Valid(new PipelineFetchWindow(p))
  })
  val mmu = Module(new Sv39Mmu(Sv39MmuParams(), withPmpCandidate = true,
    withFetchCandidates = true, pipelinedFetchKill = true))
  val cache = Module(new BreezeViptICache(BreezeMemGeometry(nCores = 1), p, Sv39MmuParams().itlb))
  cache.io.tlb <> mmu.io.itlb
  mmu.io.csr := 0.U.asTypeOf(mmu.io.csr); mmu.io.csr.priv := 3.U
  mmu.io.sfence := 0.U.asTypeOf(mmu.io.sfence)
  mmu.io.dtlb.req.valid := false.B; mmu.io.dtlb.req.bits := 0.U.asTypeOf(mmu.io.dtlb.req.bits)
  mmu.io.dtlb.kill := false.B
  mmu.io.ptwMem.req.ready := true.B; mmu.io.ptwMem.resp.valid := false.B
  mmu.io.ptwMem.resp.bits := 0.U.asTypeOf(mmu.io.ptwMem.resp.bits)
  cache.io.context := 0.U.asTypeOf(cache.io.context)
  cache.io.context.privilege := 3.U; cache.io.context.pmpRangesValid := true.B
  cache.io.request <> io.request; io.response <> cache.io.response
  cache.io.cancel := io.cancel; cache.io.cancelYounger := io.cancelYounger; cache.io.fenceI := io.fenceI
  cache.io.block := false.B; cache.io.stopF0 := false.B; cache.io.discardDeferred := false.B
  cache.io.prediction := 0.U.asTypeOf(cache.io.prediction); cache.io.historyAfter := 0.U
  io.refillReq <> cache.io.refillReq; cache.io.refillRsp <> io.refillRsp
  io.f1 := cache.io.f1
}

class BreezeViptICacheOwnerSpec extends AnyFreeSpec with ChiselSim {
  private val base = BigInt("80000000", 16)
  private val words = (0 until 8).map(i => BigInt("12300013", 16) + (BigInt(i) << 20))
  private val line = words.zipWithIndex.map { case (w, i) => w << (32 * i) }.reduce(_ | _)
  private class Driver(d: ViptOwnerHarness) {
    d.io.request.valid.poke(false.B); d.io.request.bits.pc.poke(base.U); d.io.request.bits.history.poke(0.U)
    d.io.response.ready.poke(false.B)
    d.io.cancel.poke(false.B); d.io.cancelYounger.poke(false.B); d.io.fenceI.poke(false.B)
    d.io.refillRsp.vld.poke(false.B); d.io.refillRsp.data.poke(line.U); d.io.refillRsp.error.poke(false.B)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
    def issue(pc: BigInt): Unit = {
      d.io.request.bits.pc.poke(pc.U); d.io.request.valid.poke(true.B)
      d.io.request.ready.expect(true.B); d.clock.step(); d.io.request.valid.poke(false.B)
    }
    def waitRefill(): Unit = {
      var left = 12
      while (!d.io.refillReq.req.peek().litToBoolean && left > 0) { d.clock.step(); left -= 1 }
      assert(left > 0, "missing demand refill")
      d.io.refillReq.paddr.expect(base.U); d.clock.step()
    }
    def returnLine(error: Boolean = false): Unit = {
      d.io.refillRsp.error.poke(error.B); d.io.refillRsp.vld.poke(true.B)
      d.clock.step(); d.io.refillRsp.vld.poke(false.B); d.io.refillRsp.error.poke(false.B)
    }
    def waitResponse(): Unit = {
      var left = 16
      while (!d.io.response.valid.peek().litToBoolean && left > 0) { d.clock.step(); left -= 1 }
      assert(left > 0, "missing owned response")
    }
    def expectWord(pc: BigInt): Unit = {
      d.io.response.valid.expect(true.B); d.io.response.bits.pc.expect(pc.U)
      d.io.response.bits.data.expect(words(((pc - base) / 4).toInt).U)
      d.io.response.bits.pageFault(0).expect(false.B); d.io.response.bits.accessFault(0).expect(false.B)
    }
    def warm(): Unit = {
      issue(base); waitRefill(); returnLine(); waitResponse(); expectWord(base)
      d.io.response.ready.poke(true.B); d.clock.step(); d.io.response.ready.poke(false.B)
      d.io.response.valid.expect(false.B)
    }
  }

  "reserved private writes preserve both stalled windows and full-queue replacement" in {
    simulate(new ViptOwnerHarness) { d =>
      val h = new Driver(d); h.warm()
      h.issue(base); d.io.f1.valid.expect(true.B)
      h.issue(base + 4); h.expectWord(base); d.io.f1.valid.expect(true.B)
      d.clock.step() // Both visible slots are now owned; no third write is permitted.
      for (_ <- 0 until 12) { h.expectWord(base); d.io.request.ready.expect(false.B); d.clock.step() }
      d.io.response.ready.poke(true.B)
      h.issue(base + 8) // Full queue consumes A and reserves C on the same edge.
      h.expectWord(base + 4); d.io.f1.valid.expect(true.B)
      d.clock.step(); h.expectWord(base + 8)
      d.clock.step(); d.io.response.valid.expect(false.B)
    }
  }

  "same-edge F1 completion and all cancellation kinds cannot leak or overwrite the consumed head" in {
    simulate(new ViptOwnerHarness) { d =>
      val h = new Driver(d)
      for (mode <- 0 until 3) {
        if (mode == 0) h.warm()
        h.issue(base); h.issue(base + 4); h.expectWord(base)
        d.io.response.ready.poke(true.B)
        if (mode == 0) d.io.cancelYounger.poke(true.B)
        else if (mode == 1) d.io.cancel.poke(true.B)
        else { d.io.cancel.poke(true.B); d.io.fenceI.poke(true.B) }
        d.io.f1.valid.expect(false.B)
        if (mode == 0) h.expectWord(base) else d.io.response.valid.expect(false.B)
        d.clock.step()
        d.io.cancelYounger.poke(false.B); d.io.cancel.poke(false.B); d.io.fenceI.poke(false.B)
        d.io.response.ready.poke(false.B)
        for (_ <- 0 until 3) { d.io.response.valid.expect(false.B); d.clock.step() }
        if (mode < 2) {
          h.issue(base + 8); d.clock.step(); h.expectWord(base + 8)
          d.io.response.ready.poke(true.B); d.clock.step(); d.io.response.ready.poke(false.B)
        } else h.warm() // fence.i must refill the same address, rather than reuse old data.
      }
    }
  }

  "miss candidates stay private and a canceled refill fault does not become a later response" in {
    simulate(new ViptOwnerHarness) { d =>
      val h = new Driver(d)
      h.issue(base); h.waitRefill()
      for (_ <- 0 until 8) { d.io.response.valid.expect(false.B); d.io.f1.valid.expect(false.B); d.clock.step() }
      h.returnLine(error = true) // Registered fault now owns the private write edge.
      d.io.f1.valid.expect(true.B)
      d.io.cancelYounger.poke(true.B); d.io.f1.valid.expect(false.B); d.clock.step()
      d.io.cancelYounger.poke(false.B)
      for (_ <- 0 until 4) { d.io.response.valid.expect(false.B); d.clock.step() }
      h.warm()
    }
  }
}
