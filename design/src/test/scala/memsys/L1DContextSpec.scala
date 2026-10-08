package flow.memsys

import chisel3._
import chisel3.util._
import flow.coherence.RspDownOp
import flow.config.PrivilegeProfile
import flow.core.{CSRFile, CSR_CMD, CSRMAP}
import flow.interface._

/** Drive the actual CSRW evaluate/commit datapath, rather than raw PMP bits.
  * Backend instruction serialization remains covered by its existing suites.
  */
class L1DCsrContextHarness extends L1DPermissionHarness {
  val write = IO(Input(Valid(new Bundle {
    val address = UInt(12.W)
    val data = UInt(64.W)
  })))
  val grantRelease = IO(Input(Bool()))
  val pending = RegInit(false.B)
  val responseId = Reg(UInt(1.W))
  when(cache.io.coh.req.fire) { pending := true.B; responseId := cache.io.coh.req.bits.id }
  cache.io.coh.rspDown.valid := pending && grantRelease
  cache.io.coh.rspDown.bits := 0.U.asTypeOf(cache.io.coh.rspDown.bits)
  cache.io.coh.rspDown.bits.op := RspDownOp.DataE
  cache.io.coh.rspDown.bits.id := responseId
  when(cache.io.coh.rspDown.fire) { pending := false.B }
  val csr = Module(new CSRFile(64, privilegeProfile = PrivilegeProfile.Linux))
  csr.io.csr_addr := write.bits.address
  csr.io.csr_cmd := CSR_CMD.RW.U
  csr.io.csr_reg_data := write.bits.data
  csr.io.rs1_id := 1.U; csr.io.rd_id := 0.U
  csr.io.commit_valid := write.valid
  csr.io.commit_addr := write.bits.address
  csr.io.commit_wdata := csr.io.csr_new_data
  csr.io.commit_write_en := csr.io.csr_write_en
  csr.io.fp_commit_valid := false.B; csr.io.fp_flags := 0.U
  csr.io.retire_valid := write.valid
  csr.io.hpmEvents := 0.U.asTypeOf(csr.io.hpmEvents)
  csr.io.machineTimerInterrupt := false.B; csr.io.machineSoftwareInterrupt := false.B
  csr.io.machineExternalInterrupt := false.B; csr.io.supervisorExternalInterrupt := false.B
  csr.io.time := 0.U; csr.io.trap := 0.U.asTypeOf(csr.io.trap)
  csr.io.mret_commit := false.B; csr.io.sret_commit := false.B
  cache.io.core.csr := csr.io.mmu_context
  mmu.io.csr.priv := csr.io.mmu_context.privilege
  mmu.io.csr.mprv := csr.io.mmu_context.mprv
  mmu.io.csr.mpp := csr.io.mmu_context.mpp
}

class L1DContextSpec extends L1DPermissionsSpec {
  private val address = BigInt("80000000",16)
  private def start(d: L1DCsrContextHarness): Unit = {
    d.write.valid.poke(false.B); d.write.bits.address.poke(0x340.U); d.write.bits.data.poke(0.U)
    d.grantRelease.poke(false.B)
    init(d, false)
  }
  private def csrw(d: L1DCsrContextHarness, addr: Int, value: BigInt): Unit = {
    d.write.bits.address.poke(addr.U); d.write.bits.data.poke(value.U); d.write.valid.poke(true.B)
    d.clock.step(); d.write.valid.poke(false.B)
  }
  private def launch(d: L1DCsrContextHarness, op: L1DOp.Type): Unit = {
    d.io.core.req.bits.vaddr.poke(address.U); d.io.core.req.bits.op.poke(op)
    d.io.core.req.valid.poke(true.B); d.io.core.req.ready.expect(true.B); d.clock.step()
    d.io.core.req.valid.poke(false.B)
  }
  private def decide(d: L1DCsrContextHarness, denied: Boolean, store: Boolean, atomic: Boolean = false): Unit = {
    var seen = false
    if (atomic && !denied) d.grantRelease.poke(true.B)
    for (_ <- 0 until 24) {
      if (d.io.core.resp.valid.peek().litToBoolean) {
        seen mustBe false; seen = true
        d.io.core.resp.bits.kind.expect(if (denied) L1DRespKind.Exc else if (atomic) L1DRespKind.Done else L1DRespKind.Mshr)
        if (denied) d.io.core.resp.bits.excCause.expect((if (store) 7 else 5).U)
      }
      if (denied) { d.io.coherenceRequest.expect(false.B); d.io.mmioRequest.expect(false.B) }
      d.clock.step()
    }
    seen mustBe true
  }
  "SOC3: CSRW pmpcfg0/pmpaddr0/MPRV/MPP immediately followed by load/store/AMO, both directions" in {
    simulate(new L1DCsrContextHarness) { d =>
      for (op <- Seq(L1DOp.Load, L1DOp.Store, L1DOp.AMO);
           changed <- Seq("cfg", "addr", "mprv", "mpp"); denied <- Seq(false, true)) {
        start(d)
        csrw(d, CSRMAP.pmpaddr0, BigInt("1fffffff",16))
        csrw(d, CSRMAP.pmpcfg0, 0x1f)
        csrw(d, CSRMAP.mstatus, (BigInt(1)<<17)) // MPRV, MPP=U
        changed match {
          case "cfg" =>
            csrw(d, CSRMAP.pmpcfg0, if (denied) 0x1f else 0x18)
            csrw(d, CSRMAP.pmpcfg0, if (denied) 0x18 else 0x1f)
          case "addr" =>
            csrw(d, CSRMAP.pmpaddr0, if (denied) BigInt("1fffffff",16) else 0)
            csrw(d, CSRMAP.pmpaddr0, if (denied) 0 else BigInt("1fffffff",16))
          case "mprv" =>
            csrw(d, CSRMAP.pmpcfg0, 0)
            csrw(d, CSRMAP.mstatus, if (denied) BigInt(0) else BigInt(1)<<17)
            csrw(d, CSRMAP.mstatus, if (denied) BigInt(1)<<17 else BigInt(0))
          case "mpp" =>
            csrw(d, CSRMAP.pmpcfg0, 0)
            csrw(d, CSRMAP.mstatus, (BigInt(1)<<17) | (if (denied) BigInt(3)<<11 else BigInt(0)))
            csrw(d, CSRMAP.mstatus, (BigInt(1)<<17) | (if (denied) BigInt(0) else BigInt(3)<<11))
        }
        launch(d, op); decide(d, denied, op != L1DOp.Load, op == L1DOp.AMO)
      }
    }
  }
  "SOC3: a multi-cycle S2 Hold invalidates permission snapshots on both context transitions" in {
    simulate(new L1DCsrContextHarness) { d =>
      start(d)
      // A first committed miss occupies the MSHR, so a younger load holds.
      csrw(d, CSRMAP.pmpaddr0, BigInt("1fffffff",16)); csrw(d, CSRMAP.pmpcfg0, 0x1f)
      csrw(d, CSRMAP.mstatus, BigInt(1)<<17)
      launch(d, L1DOp.Load); decide(d, denied=false, store=false)
      d.io.core.req.bits.vaddr.poke((address+0x40).U)
      d.io.core.req.valid.poke(true.B); d.io.core.req.ready.expect(true.B); d.clock.step()
      d.io.core.req.valid.poke(false.B); d.clock.step(5)
      d.io.core.s2Hold.expect(true.B); d.io.core.resp.valid.expect(false.B)
      csrw(d, CSRMAP.pmpcfg0, 0x18); d.clock.step(4)
      d.io.core.s2Hold.expect(true.B); d.io.core.resp.valid.expect(false.B)
      csrw(d, CSRMAP.pmpcfg0, 0x1f); d.clock.step(4)
      d.io.core.s2Hold.expect(true.B); d.io.core.resp.valid.expect(false.B)
      csrw(d, CSRMAP.pmpcfg0, 0x18)
      d.grantRelease.poke(true.B) // finish only the already committed first miss
      var faultSeen = false
      for (_ <- 0 until 40) {
        if (d.io.core.resp.valid.peek().litToBoolean) {
          faultSeen mustBe false; faultSeen = true
          d.io.core.resp.bits.kind.expect(L1DRespKind.Exc)
          d.io.core.resp.bits.excCause.expect(5.U)
        }
        d.clock.step()
      }
      faultSeen mustBe true
    }
  }
  "SOC3: PTW denial and a permission-context change with a PTW item in S2 retain one response" in {
    simulate(new L1DCsrContextHarness) { d =>
      for (changeInS2 <- Seq(false, true)) {
        start(d)
        csrw(d, CSRMAP.pmpaddr0, BigInt("1fffffff",16)); csrw(d, CSRMAP.pmpcfg0, 0x1f)
        if (!changeInS2) csrw(d, CSRMAP.pmpcfg0, 0x18)
        d.io.ptw.req.bits.paddr.poke(address.U); d.io.ptw.req.valid.poke(true.B)
        d.io.ptw.req.ready.expect(true.B); d.clock.step(); d.io.ptw.req.valid.poke(false.B)
        if (changeInS2) {
          // CSR and S1->S2 capture share an edge. Post-edge S2 must not use
          // the old allowed bit or allocate while refreshing its own checker.
          csrw(d, CSRMAP.pmpcfg0, 0x18)
        }
        var seen = false
        for (_ <- 0 until 12) {
          d.io.coherenceRequest.expect(false.B); d.io.mmioRequest.expect(false.B)
          if (d.io.ptw.resp.valid.peek().litToBoolean) {
            seen mustBe false; seen = true; d.io.ptw.resp.bits.accessFault.expect(true.B)
          }
          d.clock.step()
        }
        seen mustBe true
      }
    }
  }
}
