package flow.frontend

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import flow.buffer.FetchBuffer
import flow.config.{BreezeFrontendConfig, GShareBranchPredictorConfig}
import flow.interface._
import flow.l1i.FetchTlbClient
import flow.mmu.sv39.TlbPortIO
import flow.platform.BreezeMcuPlatform
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** The same frontend, fetch buffer, skid/redirect boundary and TLB adapter
  * used by BreezeCluster. External TLB/refill responses are explicit stimuli.
  */
class Soc3cFrontendFixture extends Module {
  val cfg = BreezeFrontendConfig(branchPredCfg = GShareBranchPredictorConfig(ghrLength = 4, btbEntryNum = 4),
    enableCompressed = true, enableMmu = true)
  val frontend = Module(new BreezeFrontend(cfg, enabledebug = true))
  val boundary = Module(new BreezeFrontendBoundary(4))
  val buffer = Module(new FetchBuffer(64, 6, 4))
  val fetchTlb = Module(new FetchTlbClient)
  val io = IO(new Bundle {
    val resetAddr = Input(UInt(64.W))
    val fast = Input(new FrontendRedirectIO(64))
    val slow = Input(new FrontendRedirectIO(64))
    val applied = Output(new FrontendRedirectIO(64))
    val block = Input(Bool())
    val out = Decoupled(new FrontendFetchBundle(64, 4))
    val tlb = Flipped(new TlbPortIO)
    val missReq = Output(chiselTypeOf(frontend.io.nextLevelReq))
    val missRsp = Input(chiselTypeOf(frontend.io.nextLevelRsp))
  })
  frontend.io.resetAddr := io.resetAddr
  frontend.io.btbUpdate := 0.U.asTypeOf(frontend.io.btbUpdate)
  frontend.io.phtUpdate := 0.U.asTypeOf(frontend.io.phtUpdate)
  frontend.io.ghrUpdate := 0.U.asTypeOf(frontend.io.ghrUpdate)
  boundary.io.fast := io.fast; boundary.io.slow := io.slow
  io.applied := boundary.io.applied
  frontend.io.beRedirect := boundary.io.applied
  buffer.io.in <> frontend.io.fetchBuffer
  buffer.io.flush := boundary.io.applied.flush
  boundary.io.in <> buffer.io.out
  io.out <> boundary.io.out
  fetchTlb.io.context := 0.U.asTypeOf(fetchTlb.io.context)
  fetchTlb.io.context.privilege := 3.U
  fetchTlb.io.kill := boundary.io.applied.flush
  fetchTlb.io.block := io.block
  fetchTlb.io.request <> frontend.io.translateReq
  frontend.io.translateRsp <> fetchTlb.io.response
  fetchTlb.io.tlb <> io.tlb
  io.missReq := frontend.io.nextLevelReq
  frontend.io.nextLevelRsp := io.missRsp
}

class BreezeFrontendSoc3cSpec extends AnyFreeSpec with Matchers with ChiselSim {
  private val boot = BreezeMcuPlatform.ResetVector
  private def line(inst: BigInt): BigInt = (0 until 8).map(i => inst << (32 * i)).reduce(_ | _)
  private def redirect(port: FrontendRedirectIO, valid: Boolean = false, target: BigInt = 0,
      flush: Boolean = false, cache: Boolean = false): Unit = {
    port.valid.poke(valid.B); port.target.poke(target.U)
    port.flush.poke(flush.B); port.cacheFlush.poke(cache.B)
  }
  "C2c two skid slots retain corrected fetches during ID hold and discard both kill edges" in {
    simulate(new BreezeFrontendBoundary(4)) { d =>
      redirect(d.io.fast); redirect(d.io.slow)
      d.io.out.ready.poke(false.B); d.io.in.valid.poke(false.B)
      val b = d.io.in.bits
      b.pc.poke(0.U); b.inst.poke(0x13.U); b.rawInst.poke(0x13.U); b.instLen.poke(4.U)
      b.isCompressed.poke(false.B); b.illegalCompressed.poke(false.B)
      b.instructionAccessFault.poke(false.B); b.instructionPageFault.poke(false.B)
      b.instructionFaultSecondParcel.poke(false.B)
      b.pred.predType.poke(FrontendPredType.NONE); b.pred.predTaken.poke(false.B)
      b.pred.predPc.poke(0.U); b.pred.phtIdx.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
      d.io.in.valid.poke(true.B); b.pc.poke(0x408.U)
      redirect(d.io.fast, valid = true, target = 0x40c, flush = true)
      d.clock.step(); redirect(d.io.fast)
      for (pc <- Seq(0x40c, 0x410)) {
        b.pc.poke(pc.U); d.io.in.ready.expect(true.B); d.clock.step()
      }
      d.io.in.ready.expect(false.B); d.io.in.valid.poke(false.B)
      d.io.out.ready.poke(true.B)
      for (pc <- Seq(0x40c, 0x410)) {
        d.io.out.valid.expect(true.B); d.io.out.bits.pc.expect(pc.U); d.clock.step()
      }
      d.io.out.valid.expect(false.B)
      d.io.out.ready.poke(false.B)
      redirect(d.io.slow, valid = true, target = 0x500, flush = true)
      d.io.in.valid.poke(true.B); b.pc.poke(0x414.U); d.clock.step()
      redirect(d.io.slow); b.pc.poke(0x418.U); d.clock.step()
      d.io.in.valid.poke(false.B); d.io.out.valid.expect(false.B)
      // Empty bypass is visible in the same cycle, with ready independent
      // of whether the downstream happens to accept the instruction.
      d.io.in.valid.poke(true.B); b.pc.poke(0x500.U)
      d.io.in.ready.expect(true.B); d.io.out.valid.expect(true.B); d.io.out.bits.pc.expect(0x500.U)
    }
  }
  private class Driver(val d: Soc3cFrontendFixture) {
    var tlbReply: Option[BigInt] = None
    val fetched = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
    def init(): Unit = {
      d.io.resetAddr.poke(boot.U); redirect(d.io.fast); redirect(d.io.slow)
      d.io.block.poke(false.B); d.io.out.ready.poke(true.B)
      d.io.tlb.req.ready.poke(true.B); d.io.tlb.resp.valid.poke(false.B)
      d.io.tlb.resp.bits.hit.poke(true.B); d.io.tlb.resp.bits.miss.poke(false.B)
      d.io.tlb.resp.bits.accessFault.poke(false.B); d.io.tlb.resp.bits.pageFault.poke(false.B)
      d.io.tlb.resp.bits.paddr.poke(0.U)
      d.io.missRsp.vld.poke(false.B); d.io.missRsp.error.poke(false.B); d.io.missRsp.data.poke(0.U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B)
    }
    def tick(refill: Option[BigInt] = None, autoTlb: Boolean = true): Unit = {
      d.io.tlb.resp.valid.poke(tlbReply.nonEmpty.B)
      d.io.tlb.resp.bits.paddr.poke(tlbReply.getOrElse(BigInt(0)).U)
      d.io.missRsp.vld.poke(refill.nonEmpty.B)
      d.io.missRsp.data.poke(refill.getOrElse(BigInt(0)).U)
      if (d.io.out.valid.peek().litToBoolean && d.io.out.ready.peek().litToBoolean)
        fetched += ((d.io.out.bits.pc.peek().litValue, d.io.out.bits.inst.peek().litValue))
      tlbReply = if (autoTlb && d.io.tlb.req.valid.peek().litToBoolean)
        Some(d.io.tlb.req.bits.vaddr.peek().litValue) else None
      d.clock.step()
      d.io.missRsp.vld.poke(false.B); d.io.tlb.resp.valid.poke(false.B)
    }
    def until(cond: => Boolean): Unit = {
      var n = 0
      while (!cond && n < 150) { tick(); n += 1 }
      assert(cond, "frontend event not reached within 150 cycles")
    }
    def fresh(target: BigInt): Unit = {
      until(d.io.missReq.req.peek().litToBoolean)
      d.io.missReq.paddr.expect((target & ~BigInt(31)).U)
      tick(); tick(Some(line(BigInt(0x05500293)))) // addi x5,x0,85
      until(fetched.exists(_._1 == target))
      fetched.find(_._1 == target).get._2 mustBe BigInt(0x05500293)
      fetched.exists(_._2 == BigInt(0x06300493)) mustBe false // stale addi x9,x0,99
    }
  }
  for (returnOffset <- Seq(0, 1, 3)) {
    s"C2b FENCEI discards an outstanding old refill at offset $returnOffset around delayed flush" in {
      simulate(new Soc3cFrontendFixture) { d =>
        val m = new Driver(d); m.init()
        m.until(d.io.missReq.req.peek().litToBoolean)
        d.io.missReq.paddr.expect((boot & ~BigInt(31)).U)
        m.tick() // accept the one-shot request before scheduling its response.
        val target = boot + 0x200
        redirect(d.io.slow, valid = true, target = target, flush = true, cache = true)
        d.io.out.ready.poke(false.B) // WB kill and the following ID gap.
        d.io.applied.valid.expect(false.B); d.io.applied.cacheFlush.expect(false.B)
        m.tick(if (returnOffset == 0) Some(line(BigInt(0x06300493))) else None)
        redirect(d.io.slow)
        d.io.applied.valid.expect(true.B); d.io.applied.cacheFlush.expect(true.B)
        d.io.applied.target.expect(target.U)
        for (offset <- 1 to 3) {
          m.tick(if (returnOffset == offset) Some(line(BigInt(0x06300493))) else None)
        }
        d.io.out.ready.poke(true.B)
        m.fresh(target)
        m.fetched.exists(_._1 == boot) mustBe false
      }
    }
  }
  "C2b SFENCE discards a gap translation response and fetches with fresh translation" in {
    simulate(new Soc3cFrontendFixture) { d =>
      val m = new Driver(d); m.init()
      m.until(d.io.tlb.req.valid.peek().litToBoolean)
      d.io.tlb.req.bits.vaddr.expect(boot.U)
      val target = boot + 0x200
      redirect(d.io.slow, valid = true, target = target, flush = true)
      d.io.block.poke(true.B); d.io.out.ready.poke(false.B)
      m.tick(autoTlb = false) // old request was accepted in the redirect gap.
      redirect(d.io.slow)
      d.io.applied.valid.expect(true.B); d.io.tlb.kill.expect(true.B)
      m.tlbReply = Some(boot)
      m.tick(autoTlb = false) // old TLB response coincides with the applied kill.
      m.tlbReply = Some(boot)
      m.tick(autoTlb = false) // a late old response must also be ignored in Idle.
      d.io.missReq.req.expect(false.B); d.io.out.valid.expect(false.B)
      d.io.block.poke(false.B); d.io.out.ready.poke(true.B)
      m.fresh(target)
      m.fetched.exists(_._1 == boot) mustBe false
    }
  }
  "C2 slow redirect overrides a fast redirect at its next-cycle application" in {
    simulate(new Soc3cFrontendFixture) { d =>
      val m = new Driver(d); m.init(); d.io.block.poke(true.B)
      redirect(d.io.fast, valid = true, target = boot + 0x100, flush = true)
      redirect(d.io.slow, valid = true, target = boot + 0x200, flush = true)
      d.io.applied.target.expect((boot + 0x100).U)
      m.tick(); redirect(d.io.slow)
      d.io.applied.target.expect((boot + 0x200).U)
      m.tick(); redirect(d.io.fast)
      d.io.applied.valid.expect(false.B)
    }
  }
}
