package flow.memsys

import chisel3._
import chisel3.util._
import flow.backend.BreezeBackend
import flow.coherence.RspDownOp
import flow.config.{BackendConfig, BreezeMemGeometry, PrivilegeProfile}
import flow.fpu.BreezeFpChiselSim
import flow.interface.FrontendFetchBundle
import flow.l1d.L1DCache
import flow.mmu.sv39.{MmuCmd, Sv39Mmu, TlbPortIO}
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

/** Production backend/L1D/MMU/PTW connections. The fetch queue is stimulated
  * independently of a younger iTLB request, as at the backend boundary.
  * CSRW and MRET configure real PMP/Sv39/S-mode state before that request.
  */
class BE2FencePtwHarness(g: BreezeMemGeometry = BreezeMemGeometry.singleCore) extends Module {
  val io = IO(new Bundle {
    val fetch = Flipped(Decoupled(new FrontendFetchBundle(64, 8)))
    val itlb = new TlbPortIO(withPmpCandidate = true)
    val ptwReady = Output(Bool())
    val ptwAddress = Output(UInt(56.W))
    val grant = Input(Bool())
    val grantData = Input(UInt(256.W))
    val sampledMmuIdle = Output(Bool())
    val privilege = Output(UInt(2.W))
    val satp = Output(UInt(64.W))
    val drained = Output(Bool())
    val sampledDrained = Output(Bool())
    val fenceAtWb = Output(Bool())
    val committed = Output(Bool())
    val cpuRequest = Output(Bool())
    val missRequest = Output(Bool())
  })
  val backend = Module(new BE2SerialBackendProbe)
  val cache = Module(new L1DCache(g, withPmpCandidate = true))
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
  backend.io.mmuIdle := dtlb.io.idle
  cache.io.ptw <> dtlb.io.ptwMem
  cache.io.tlb <> dtlb.io.dtlb
  val context = backend.io.mmuContext
  dtlb.io.csr.sv39 := context.satp(63, 60) === 8.U
  dtlb.io.csr.asid := context.satp(59, 44)
  dtlb.io.csr.rootPpn := context.satp(43, 0)
  dtlb.io.csr.priv := context.privilege
  dtlb.io.csr.mprv := context.mprv; dtlb.io.csr.mpp := context.mpp
  dtlb.io.csr.sum := context.sum; dtlb.io.csr.mxr := context.mxr
  dtlb.io.itlb <> io.itlb
  dtlb.io.sfence.valid := backend.io.sfence.valid
  dtlb.io.sfence.rs1Nz := backend.io.sfence.useVaddr
  dtlb.io.sfence.rs2Nz := backend.io.sfence.useAsid
  dtlb.io.sfence.vaddr := backend.io.sfence.vaddr
  dtlb.io.sfence.asid := backend.io.sfence.asid
  cache.io.coh.req.ready := true.B
  cache.io.coh.rspUp.ready := true.B
  cache.io.coh.snp.valid := false.B
  cache.io.coh.snp.bits := 0.U.asTypeOf(cache.io.coh.snp.bits)
  val responseId = RegEnable(cache.io.coh.req.bits.id, cache.io.coh.req.fire)
  cache.io.coh.rspDown.valid := io.grant
  cache.io.coh.rspDown.bits := 0.U.asTypeOf(cache.io.coh.rspDown.bits)
  cache.io.coh.rspDown.bits.op := RspDownOp.DataE
  cache.io.coh.rspDown.bits.id := responseId
  cache.io.coh.rspDown.bits.data := io.grantData
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
  io.ptwReady := dtlb.io.ptwMem.req.valid && dtlb.io.ptwMem.req.ready
  io.ptwAddress := dtlb.io.ptwMem.req.bits.paddr
  io.privilege := context.privilege
  io.satp := context.satp
  io.sampledMmuIdle := RegNext(dtlb.io.idle, false.B)
}

class BE2FencePtwSpec extends AnyFreeSpec with Matchers with BreezeFpChiselSim {
  for ((geometry, g) <- Seq("default" -> BreezeMemGeometry.singleCore,
      "BE2 deployment" -> flow.config.BreezeClusterPresets.single.mem);
      warm <- Seq(false, true)) {
  s"B3 audit: real Sv39 PTW ${if (warm) "hit" else "miss"} has identical sampled WB conditions ($geometry)" in {
    simulate(new BE2FencePtwHarness(g)) { d =>
      d.io.fetch.valid.poke(false.B)
      d.io.itlb.req.valid.poke(false.B); d.io.itlb.kill.poke(false.B)
      d.io.itlb.req.bits.vaddr.poke(BigInt("40000000", 16).U)
      d.io.itlb.req.bits.cmd.poke(MmuCmd.Fetch)
      d.io.grant.poke(false.B); d.io.grantData.poke(0.U)
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
      def csrw(addr: Int, rs: Int = 1): Unit = {
        send((BigInt(addr) << 20) | (BigInt(rs) << 15) | (BigInt(1) << 12) | 0x73)
        d.clock.step(8)
      }
      def shift(rd: Int, amount: Int): BigInt =
        (BigInt(amount) << 20) | (BigInt(rd) << 15) | (BigInt(1) << 12) | (BigInt(rd) << 7) | 0x13
      if (warm) {
        // Warm the root-PTE line by a real CPU load and coherent refill.
        send(BigInt("00100a13", 16)); send(shift(20, 31)) // x20=0x80000000
        send((BigInt(8) << 20) | (BigInt(20) << 15) | (BigInt(3) << 12) | (BigInt(21) << 7) | 3)
        var waited = 0
        while (!d.io.missRequest.peek().litToBoolean && waited < 40) { d.clock.step(); waited += 1 }
        waited must be < 40
        d.clock.step() // coherent Get accepted; the MSHR now waits
        val leaf = (BigInt(0x80000) << 10) | 0xcf // aligned 1 GiB RWXAD leaf
        d.io.grantData.poke((leaf << 64).U); d.io.grant.poke(true.B)
        d.clock.step(); d.io.grant.poke(false.B); d.clock.step(20)
        d.io.drained.expect(true.B)
      }
      send(BigInt("00100093", 16)); send(shift(1, 19)) // root PPN = 0x80000
      send(BigInt("00100113", 16)); send(shift(2, 63)) // Sv39 mode = 8
      send(BigInt("0020e0b3", 16)); csrw(0x180) // or x1,x1,x2; csrw satp
      send(BigInt("00100093", 16)); send(shift(1, 11)); csrw(0x300) // MPP=S
      send(BigInt("60000093", 16)); csrw(0x341) // mepc=0x600
      send(BigInt("30200073", 16)); d.clock.step(8); pc = 0x600 // MRET
      d.io.privilege.expect(1.U)
      d.io.satp.expect(BigInt("8000000000080000", 16).U)
      d.io.drained.expect(true.B)
      // A real iTLB miss owns a real Sv39Ptw request for root PTE[1].
      d.io.itlb.req.valid.poke(true.B); d.io.itlb.req.ready.expect(true.B)
      d.clock.step(); d.io.itlb.req.valid.poke(false.B)
      var waited = 0
      while (!d.io.ptwReady.peek().litToBoolean && waited < 20) { d.clock.step(); waited += 1 }
      waited must be < 20
      d.io.ptwAddress.expect(BigInt("80000008", 16).U)
      // PTW S0 and FENCE.I ID are accepted at the same edge. The PTW is
      // accepted before the serial owner reaches WB, so a later S0 gate
      // cannot cancel it (accepted PTW survives CPU kill).
      offer(BigInt(0x100f))
      d.io.fetch.ready.expect(true.B)
      d.clock.step(); d.io.fetch.valid.poke(false.B)
      d.clock.step(2)
      d.io.drained.expect(true.B); d.io.sampledDrained.expect(true.B)
      d.io.fenceAtWb.expect(false.B); d.io.cpuRequest.expect(false.B)
      d.io.sampledMmuIdle.expect(false.B)
      println(s"BE2_B3 real_${if (warm) "hit" else "miss"} cycle=D FENCE=PERM PTW=S3 raw_drained=1 sampled_idle=0")
      d.clock.step()
      d.io.fenceAtWb.expect(true.B)
      d.io.cpuRequest.expect(false.B)
      d.io.drained.expect(warm.B); d.io.sampledDrained.expect(true.B)
      d.io.sampledMmuIdle.expect(false.B)
      d.io.committed.expect(true.B); d.io.missRequest.expect((!warm).B)
      println(s"BE2_B3 real_${if (warm) "hit" else "miss"} cycle=D+1 FENCE=WB CPU_idle=1 raw_drained=${if (warm) 1 else 0} sampled_drained=1 sampled_idle=0 baseline_commit=${if (warm) 1 else 0}")
    }
  }
  }
}
