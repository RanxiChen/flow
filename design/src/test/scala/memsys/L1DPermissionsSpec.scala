package flow.memsys

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import flow.config.BreezeMemGeometry
import flow.interface._
import flow.l1d.L1DCache
import flow.mmu.sv39._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class L1DPermissionHarness(killOnFault: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val core = new L1DCoreIO
    val ptw = Flipped(new PtwMemIO)
    val coherenceRequest = Output(Bool())
    val mmioRequest = Output(Bool())
  })
  val cache = Module(new L1DCache(BreezeMemGeometry.singleCore))
  val mmu = Module(new Sv39Mmu)
  cache.io.core <> io.core; cache.io.ptw <> io.ptw
  if (killOnFault) {
    val fault = cache.io.core.resp.valid && cache.io.core.resp.bits.kind === L1DRespKind.Exc
    cache.io.core.s2Kill := io.core.s2Kill || fault
    cache.io.core.trapClearRsv := io.core.trapClearRsv || fault
  }
  cache.io.tlb <> mmu.io.dtlb
  mmu.io.csr.sv39 := io.core.csr.satp(63,60) === 8.U
  mmu.io.csr.asid := io.core.csr.satp(59,44); mmu.io.csr.rootPpn := io.core.csr.satp(43,0)
  mmu.io.csr.priv := io.core.csr.privilege; mmu.io.csr.mprv := io.core.csr.mprv
  mmu.io.csr.mpp := io.core.csr.mpp; mmu.io.csr.sum := io.core.csr.sum; mmu.io.csr.mxr := io.core.csr.mxr
  mmu.io.itlb.req.valid := false.B; mmu.io.itlb.req.bits := 0.U.asTypeOf(mmu.io.itlb.req.bits)
  mmu.io.itlb.kill := false.B; mmu.io.sfence := 0.U.asTypeOf(mmu.io.sfence)
  mmu.io.ptwMem.req.ready := false.B; mmu.io.ptwMem.resp.valid := false.B
  mmu.io.ptwMem.resp.bits := 0.U.asTypeOf(mmu.io.ptwMem.resp.bits)
  cache.io.coh.req.ready := true.B; cache.io.coh.rspUp.ready := true.B
  cache.io.coh.snp.valid := false.B; cache.io.coh.snp.bits := 0.U.asTypeOf(cache.io.coh.snp.bits)
  cache.io.coh.rspDown.valid := false.B; cache.io.coh.rspDown.bits := 0.U.asTypeOf(cache.io.coh.rspDown.bits)
  cache.io.mmio.ar.ready := true.B; cache.io.mmio.aw.ready := true.B; cache.io.mmio.w.ready := true.B
  cache.io.mmio.r.valid := false.B; cache.io.mmio.r.bits := 0.U.asTypeOf(cache.io.mmio.r.bits)
  cache.io.mmio.b.valid := false.B; cache.io.mmio.b.bits := 0.U
  io.coherenceRequest := cache.io.coh.req.valid
  io.mmioRequest := cache.io.mmio.ar.valid || cache.io.mmio.aw.valid
}

class L1DPermissionsSpec extends AnyFreeSpec with Matchers with ChiselSim {
  protected def init(d: L1DPermissionHarness, pmp: Boolean = true): Unit = {
    d.io.core.req.valid.poke(false.B)
    d.io.core.req.bits.op.poke(L1DOp.Load); d.io.core.req.bits.vaddr.poke(0.U)
    d.io.core.req.bits.size.poke(3.U); d.io.core.req.bits.signed.poke(false.B)
    d.io.core.req.bits.amoFunc.poke(BreezeAmoFunc.Swap)
    d.io.core.req.bits.aq.poke(false.B); d.io.core.req.bits.rl.poke(false.B)
    d.io.core.req.bits.wdata.poke(0.U); d.io.core.req.bits.rd.isFp.poke(false.B)
    d.io.core.req.bits.rd.idx.poke(1.U); d.io.core.req.bits.isFlw.poke(false.B)
    d.io.core.s1Kill.poke(false.B); d.io.core.s2Kill.poke(false.B)
    d.io.core.trapClearRsv.poke(false.B); d.io.core.late.ready.poke(true.B)
    d.io.core.csr.satp.poke(0.U); d.io.core.csr.privilege.poke(3.U)
    d.io.core.csr.mprv.poke(false.B); d.io.core.csr.mpp.poke(0.U)
    d.io.core.csr.sum.poke(false.B); d.io.core.csr.mxr.poke(false.B); d.io.core.csr.adue.poke(false.B)
    for(i <- 0 until 16) { d.io.core.csr.pmpcfg(i).poke(0.U); d.io.core.csr.pmpaddr(i).poke(0.U) }
    if(pmp) { d.io.core.csr.pmpcfg(0).poke(0x1f.U); d.io.core.csr.pmpaddr(0).poke(0x1fffffff.U) }
    d.io.ptw.req.valid.poke(false.B); d.io.ptw.req.bits.paddr.poke(0.U)
    d.reset.poke(true.B); d.clock.step(2); d.reset.poke(false.B); d.clock.step(132)
  }
  protected def fault(d: L1DPermissionHarness, addr: BigInt, op: L1DOp.Type, cause: Int): Unit = {
    d.io.core.req.bits.vaddr.poke(addr.U); d.io.core.req.bits.op.poke(op)
    d.io.core.req.valid.poke(true.B); d.io.core.req.ready.expect(true.B); d.clock.step()
    d.io.core.req.valid.poke(false.B)
    var seen=false
    for(_ <- 0 until 5) {
      d.io.coherenceRequest.expect(false.B); d.io.mmioRequest.expect(false.B)
      if(d.io.core.resp.valid.peek().litToBoolean) {
        seen mustBe false; seen=true
        d.io.core.resp.bits.kind.expect(L1DRespKind.Exc)
        d.io.core.resp.bits.excCause.expect(cause.U); d.io.core.resp.bits.tval.expect(addr.U)
      }
      d.clock.step()
    }
    seen mustBe true
  }
  "64-bit CPU PA above 4 GiB must fault instead of aliasing low RAM" in {
    simulate(new L1DPermissionHarness) { d => init(d); fault(d,BigInt("180000000",16),L1DOp.Load,5); fault(d,BigInt("180000000",16),L1DOp.Store,7) }
  }
  "WB fault cancellation and reservation clearing cannot combinationally suppress their fault" in {
    simulate(new L1DPermissionHarness(killOnFault = true)) { d =>
      init(d); fault(d, BigInt("180000000",16), L1DOp.Store, 7)
    }
  }
  "MPRV selects MPP for PMP instead of bypassing protection as M-mode" in {
    simulate(new L1DPermissionHarness) { d => init(d,false); d.io.core.csr.mprv.poke(true.B); fault(d,BigInt("80000000",16),L1DOp.Load,5) }
  }
  "ROM LR, SC and AMO use atomic PMA and the appropriate fault class" in {
    simulate(new L1DPermissionHarness) { d => init(d); fault(d,BigInt("10000000",16),L1DOp.LR,5); fault(d,BigInt("10000000",16),L1DOp.SC,7); fault(d,BigInt("10000000",16),L1DOp.AMO,7) }
  }
  "56-bit PTW address above 4 GiB and a device PTE read return accessFault without memory traffic" in {
    simulate(new L1DPermissionHarness) { d =>
      init(d)
      for(addr <- Seq(BigInt("180000000",16),BigInt("12000000",16))) {
        d.io.ptw.req.bits.paddr.poke(addr.U); d.io.ptw.req.valid.poke(true.B)
        d.io.ptw.req.ready.expect(true.B); d.clock.step(); d.io.ptw.req.valid.poke(false.B)
        var seen=false
        for(_ <- 0 until 5) {
          d.io.coherenceRequest.expect(false.B); d.io.mmioRequest.expect(false.B)
          if(d.io.ptw.resp.valid.peek().litToBoolean) { seen mustBe false; seen=true; d.io.ptw.resp.bits.accessFault.expect(true.B) }
          d.clock.step()
        }
        seen mustBe true
      }
    }
  }
}
