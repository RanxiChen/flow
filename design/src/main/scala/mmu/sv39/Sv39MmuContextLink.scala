package flow.mmu.sv39

import chisel3._
import flow.interface.BreezeMmuContext

/** Combinational CSR projection shared by per-core and standalone integration. */
object Sv39MmuContextLink {
  def apply(mmu: Sv39Mmu, context: BreezeMmuContext): Unit = {
    mmu.io.csr.sv39 := context.satp(63, 60) === 8.U
    mmu.io.csr.asid := context.satp(59, 44)
    mmu.io.csr.rootPpn := context.satp(43, 0)
    mmu.io.csr.priv := context.privilege
    mmu.io.csr.mprv := context.mprv
    mmu.io.csr.mpp := context.mpp
    mmu.io.csr.sum := context.sum
    mmu.io.csr.mxr := context.mxr
  }
}
