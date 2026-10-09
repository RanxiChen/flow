package flow.mmu.sv39

import chisel3._
import chisel3.util._

object MmuCmd extends ChiselEnum { val Fetch, Load, Store = Value }
class MmuCsrIO extends Bundle {
  val sv39 = Bool(); val asid = UInt(16.W); val rootPpn = UInt(44.W)
  val priv = UInt(2.W); val mprv = Bool(); val mpp = UInt(2.W)
  val sum = Bool(); val mxr = Bool()
}
class TlbReq extends Bundle { val vaddr = UInt(64.W); val cmd = MmuCmd() }
class TlbResp extends Bundle {
  val hit = Bool(); val miss = Bool(); val pageFault = Bool(); val accessFault = Bool()
  val paddr = UInt(64.W)
}
class TlbPortIO(val withPmpCandidate: Boolean = false) extends Bundle {
  val req = Flipped(Decoupled(new TlbReq))
  val resp = Valid(new TlbResp)
  val kill = Input(Bool())
  // Internal timing sideband only. It has the same response cycle, is not
  // permission-qualified, and must never authorize an access by itself.
  val candidatePaddr = if (withPmpCandidate) Some(Output(UInt(64.W))) else None
}
class SfenceIO extends Bundle {
  val valid = Bool(); val rs1Nz = Bool(); val rs2Nz = Bool()
  val vaddr = UInt(64.W); val asid = UInt(16.W)
}
class PtwMemReq extends Bundle { val paddr = UInt(56.W) }
class PtwMemResp extends Bundle { val data = UInt(64.W); val accessFault = Bool() }
class PtwMemIO extends Bundle {
  val req = Decoupled(new PtwMemReq)
  val resp = Flipped(Valid(new PtwMemResp))
}
class PageEntry extends Bundle {
  val asid = UInt(16.W); val g = Bool(); val ppn = UInt(44.W)
  val r = Bool(); val w = Bool(); val x = Bool(); val u = Bool(); val a = Bool(); val d = Bool()
  def assignPage(e: PageEntry): Unit = {
    asid := e.asid; g := e.g; ppn := e.ppn
    r := e.r; w := e.w; x := e.x; u := e.u; a := e.a; d := e.d
  }
}
class BaseEntry(tagBits: Int) extends PageEntry { val tag = UInt(tagBits.W) }
class SuperEntry extends PageEntry {
  val valid = Bool(); val vpn = UInt(27.W); val level = UInt(2.W)
}
class TlbRefill extends PageEntry { val vpn = UInt(27.W); val level = UInt(2.W) }
class TlbMiss extends Bundle {
  val valid = Bool(); val granted = Bool()
  val vpn = UInt(27.W); val asid = UInt(16.W); val rootPpn = UInt(44.W)
}
class PtwDone extends Bundle {
  val side = Bool(); val pageFault = Bool(); val accessFault = Bool()
  val refillValid = Bool(); val refill = new TlbRefill
}
class WalkCacheLookup(keyBits: Int) extends Bundle {
  val key = Input(UInt(keyBits.W)); val asid = Input(UInt(16.W))
  val hit = Output(Bool()); val ppn = Output(UInt(44.W))
}
class WalkCacheFill(keyBits: Int) extends Bundle {
  val key = UInt(keyBits.W); val asid = UInt(16.W); val ppn = UInt(44.W)
}
class WalkCachePort(keyBits: Int) extends Bundle {
  val lookup = new WalkCacheLookup(keyBits)
  val lookupFire = Input(Bool())
  val fill = Flipped(Valid(new WalkCacheFill(keyBits)))
}
object PteDecode {
  def ppn(pte: UInt): UInt = pte(53, 10)
  def leaf(pte: UInt): Bool = pte(1) || pte(3)
  def bad(pte: UInt): Bool = !pte(0) || (!pte(1) && pte(2)) || pte(63, 54).orR
  def permissions(e: PageEntry, pte: UInt): Unit = {
    e.ppn := ppn(pte); e.r := pte(1); e.w := pte(2); e.x := pte(3)
    e.u := pte(4); e.g := pte(5); e.a := pte(6); e.d := pte(7)
  }
}
