package flow.mmu

import chisel3._
import chisel3.util._
import flow.interface.BreezePmpRange

/** Last byte within a 128-byte block, and carry into the next block. */
class BreezePmpAccessEnd extends Bundle {
  val low = UInt(7.W)
  val carry = Bool()
}

object BreezePmpDecode {
  def accessEnd(addressLow: UInt, sizeLog2: UInt): BreezePmpAccessEnd = {
    val out = Wire(new BreezePmpAccessEnd)
    val span = MuxLookup(sizeLog2, 0.U(7.W))(
      (0 until 8).map(n => n.U -> ((1 << n) - 1).U(7.W)))
    val sum = addressLow(6, 0) +& span
    out.low := sum(6, 0)
    out.carry := sum(7)
    out
  }

  def range(cfg: UInt, addr: UInt, previousAddr: UInt): BreezePmpRange = {
    val out = Wire(new BreezePmpRange)
    val mode = cfg(4, 3)
    val boundary = Cat(0.U(9.W), addr, 0.U(2.W))
    val previous = Cat(0.U(9.W), previousAddr, 0.U(2.W))
    // Preserve the existing 54-bit PMP encoding, including the largest NAPOT.
    // Bit j toggles in addr ^ (addr + 1) iff all lower j bits are one.
    // Express that prefix directly, including 54'h3fffffffffffff, without
    // materializing an increment and then XORing every encoded address bit.
    val napotMask = Cat((0 until 54).reverse.map(j =>
      if (j == 0) true.B else addr(j - 1, 0).andR))
    val byteMask = Cat(0.U(9.W), Mux(mode(0), napotMask, 0.U(54.W)), 3.U(2.W))
    val pow2Lower = boundary & ~byteMask
    // pow2Lower clears every bit in byteMask. Their sum has no carries
    // and equals boundary | byteMask; retain the full 65-bit boundary.
    val pow2Upper = (boundary | byteMask) + 1.U
    out.lower := Mux(mode(1), pow2Lower, previous)
    out.upper := Mux(mode(1), pow2Upper, boundary)
    // NA4/NAPOT always have positive size inside the 56-bit aperture; only
    // TOR needs a boundary comparison. OFF stays empty even when lo < hi.
    out.nonempty := mode(1) || (mode(0) && previousAddr < addr)
    out.lowerBlockPrevious := out.lower(64, 7) - 1.U
    out.upperBlockPrevious := out.upper(64, 7) - 1.U
    out
  }
}
