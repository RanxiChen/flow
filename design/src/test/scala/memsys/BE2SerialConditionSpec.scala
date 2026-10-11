package flow.memsys

import chisel3._
import chisel3.util._
import flow.backend.BreezeBackend
import flow.config.{BackendConfig, BreezeMemGeometry, PrivilegeProfile}
import flow.fpu.BreezeFpChiselSim
import flow.interface.FrontendFetchBundle
import flow.l1d.L1DCache
import flow.mmu.sv39.{PtwMemIO, Sv39Mmu}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** Observe the proposed R7 sampling boundary around the production L1D.
  * CPU requests are idle; PTW is still an independent, legal requester.
  * This does not implement BE2 or assume that a sampled condition is safe.
  */
class BE2SerialConditionHarness extends L1DPermissionHarness {
  val sampledDrained = IO(Output(Bool()))
  sampledDrained := RegNext(cache.io.core.drained, false.B)
}

/** A source-contract audit, not a BE2 acceptance test. Passing reproduces a
  * counterexample to treating current drained as monotonic under CPU silence.
  */
class BE2SerialConditionSpec extends L1DPermissionsSpec {
  "B3 audit: a previously accepted PTW miss invalidates sampled drained with no CPU requests" in {
    simulate(new BE2SerialConditionHarness) { d =>
      init(d)
      d.io.core.req.valid.expect(false.B)
      d.io.core.drained.expect(true.B)
      d.sampledDrained.expect(true.B)
      // This is the physical PTE service used by Sv39Ptw. The line is not
      // resident. PTE accesses run independently of the CPU serial owner.
      d.io.ptw.req.bits.paddr.poke(BigInt("80000000", 16).U)
      d.io.ptw.req.valid.poke(true.B)
      d.io.ptw.req.ready.expect(true.B)
      d.clock.step() // S1
      d.io.ptw.req.valid.poke(false.B)
      d.clock.step() // S2
      d.clock.step() // S3: miss allocates at the following edge
      d.io.core.req.valid.expect(false.B)
      d.io.core.drained.expect(true.B)
      d.sampledDrained.expect(true.B)
      d.io.coherenceRequest.expect(false.B)
      println("BE2_B3 cycle=D CPU_idle=1 PTW=S3 raw_drained=1 sampled_drained=1")
      d.clock.step()
      d.io.core.req.valid.expect(false.B)
      d.io.core.drained.expect(false.B)
      d.sampledDrained.expect(true.B)
      d.io.coherenceRequest.expect(true.B)
      println("BE2_B3 cycle=D+1 CPU_idle=1 PTW=MSHR raw_drained=0 sampled_drained=1")
      // Delaying CPU issue cannot prevent this transition: the PTW request
      // was accepted three edges earlier, before sampling the ready bit.
    }
  }
}

class BE2SerialBackendProbe extends BreezeBackend(BackendConfig(privilegeProfile = PrivilegeProfile.Linux)) {
  val fenceAtWb = IO(Output(Bool()))
  fenceAtWb := wb.valid && wb.fencei
}

/** Production backend/L1D connection with the physical PTW service exposed
  * as an environment port, as in the cache permission suites. No CPU memory
  * request or coherence response is fabricated. PMP is configured by CSRW.
  */
class BE2FencePtwHarness extends Module {
  val io = IO(new Bundle {
    val fetch = Flipped(Decoupled(new FrontendFetchBundle(64, 8)))
    val ptw = Flipped(new PtwMemIO)
    val drained = Output(Bool())
    val sampledDrained = Output(Bool())
    val fenceAtWb = Output(Bool())
    val committed = Output(Bool())
    val cpuRequest = Output(Bool())
    val missRequest = Output(Bool())
  })
  val backend = Module(new BE2SerialBackendProbe)
  val cache = Module(new L1DCache(BreezeMemGeometry.singleCore, withPmpCandidate = true))
  val dtlb = Module(new Sv39Mmu(withPmpCandidate = true))
  backend.io.fetchBuffer <> io.fetch
  backend.io.l1d <> cache.io.core
  backend.io.resetAddr := 0x400.U
  backend.io.machineTimerInterrupt := false.B
  backend.io.machineSoftwareInterrupt := false.B
  backend.io.time := 0.U
  backend.io.externalInterrupts := 0.U
  backend.io.supervisorExternalInterrupt := false.B
  backend.io.hpmEvents := 0.U.asTypeOf(backend.io.hpmEvents)
  // The exposed PTW service may be active while FENCE.I waits. FENCE.I,
  // unlike SFENCE.VMA, does not require the MMU to be idle.
  backend.io.mmuIdle := false.B
  cache.io.ptw <> io.ptw
  cache.io.tlb <> dtlb.io.dtlb
  dtlb.io.csr := 0.U.asTypeOf(dtlb.io.csr)
  dtlb.io.csr.priv := 3.U
  dtlb.io.itlb.req.valid := false.B
  dtlb.io.itlb.req.bits := 0.U.asTypeOf(dtlb.io.itlb.req.bits)
  dtlb.io.itlb.kill := false.B
  dtlb.io.sfence := 0.U.asTypeOf(dtlb.io.sfence)
  dtlb.io.ptwMem.req.ready := false.B
  dtlb.io.ptwMem.resp.valid := false.B
  dtlb.io.ptwMem.resp.bits := 0.U.asTypeOf(dtlb.io.ptwMem.resp.bits)
  cache.io.coh.req.ready := false.B
  cache.io.coh.rspUp.ready := true.B
  cache.io.coh.snp.valid := false.B
  cache.io.coh.snp.bits := 0.U.asTypeOf(cache.io.coh.snp.bits)
  cache.io.coh.rspDown.valid := false.B
  cache.io.coh.rspDown.bits := 0.U.asTypeOf(cache.io.coh.rspDown.bits)
  cache.io.mmio.ar.ready := true.B
  cache.io.mmio.aw.ready := true.B
  cache.io.mmio.w.ready := true.B
  cache.io.mmio.r.valid := false.B
  cache.io.mmio.r.bits := 0.U.asTypeOf(cache.io.mmio.r.bits)
  cache.io.mmio.b.valid := false.B
  cache.io.mmio.b.bits := 0.U
  io.drained := cache.io.core.drained
  io.sampledDrained := RegNext(cache.io.core.drained, false.B)
  io.fenceAtWb := backend.fenceAtWb
  io.committed := backend.io.observe.commit
  io.cpuRequest := cache.io.core.req.valid
  io.missRequest := cache.io.coh.req.valid
}

class BE2FencePtwSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  "B3 audit: FENCE.I reaches WB exactly when an older accepted PTW makes sampled drained stale" in {
    simulate(new BE2FencePtwHarness) { d =>
      d.io.fetch.valid.poke(false.B)
      d.io.ptw.req.valid.poke(false.B)
      d.io.ptw.req.bits.paddr.poke(BigInt("80000000", 16).U)
      d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(140)
      var pc = BigInt(0x400)
      def offer(inst: BigInt): Unit = {
        val f = d.io.fetch.bits
        f.pc.poke(pc.U); f.inst.poke(inst.U); f.rawInst.poke(inst.U); f.instLen.poke(4.U)
        f.isCompressed.poke(false.B); f.illegalCompressed.poke(false.B)
        f.instructionAccessFault.poke(false.B); f.instructionPageFault.poke(false.B)
        f.instructionFaultSecondParcel.poke(false.B)
        f.pred.predType.poke(flow.interface.FrontendPredType.NONE)
        f.pred.predTaken.poke(false.B); f.pred.predPc.poke((pc + 4).U); f.pred.phtIdx.poke(0.U)
        d.io.fetch.valid.poke(true.B)
      }
      def send(inst: BigInt): Unit = {
        offer(inst)
        var waited = 0
        while (!d.io.fetch.ready.peek().litToBoolean && waited < 40) { d.clock.step(); waited += 1 }
        waited must be < 40
        d.clock.step(); d.io.fetch.valid.poke(false.B); pc += 4
      }
      // A legal read/write/execute NAPOT PMP entry covers the physical PTE.
      send(BigInt("fff00093", 16)) // addi x1,x0,-1
      send((BigInt(0x3b0) << 20) | (BigInt(1) << 15) | (BigInt(1) << 12) | 0x73)
      send(BigInt("01f00093", 16)) // addi x1,x0,31
      send((BigInt(0x3a0) << 20) | (BigInt(1) << 15) | (BigInt(1) << 12) | 0x73)
      d.clock.step(8)
      d.io.drained.expect(true.B)
      // PTW S0 and FENCE.I ID are accepted at the same edge. The PTW is
      // accepted before the serial owner reaches WB, so a later S0 gate
      // cannot cancel it (accepted PTW survives CPU kill).
      offer(BigInt(0x100f))
      d.io.fetch.ready.expect(true.B)
      d.io.ptw.req.valid.poke(true.B); d.io.ptw.req.ready.expect(true.B)
      d.clock.step(); d.io.fetch.valid.poke(false.B); d.io.ptw.req.valid.poke(false.B)
      d.clock.step(2)
      d.io.drained.expect(true.B); d.io.sampledDrained.expect(true.B)
      d.io.fenceAtWb.expect(false.B); d.io.cpuRequest.expect(false.B)
      println("BE2_B3 integrated cycle=D FENCE=PERM PTW=S3 raw_drained=1")
      d.clock.step()
      d.io.fenceAtWb.expect(true.B)
      d.io.cpuRequest.expect(false.B)
      d.io.drained.expect(false.B); d.io.sampledDrained.expect(true.B)
      d.io.committed.expect(false.B); d.io.missRequest.expect(true.B)
      println("BE2_B3 integrated cycle=D+1 FENCE=WB CPU_idle=1 raw_drained=0 sampled_drained=1 baseline_commit=0")
    }
  }
}
