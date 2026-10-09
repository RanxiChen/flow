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
    val napotMask = addr ^ (addr + 1.U)
    val byteMask = Cat(0.U(9.W), Mux(mode(0), napotMask, 0.U(54.W)), 3.U(2.W))
    val pow2Lower = boundary & ~byteMask
    val pow2Upper = pow2Lower + byteMask + 1.U
    out.lower := Mux(mode(1), pow2Lower, previous)
    out.upper := Mux(mode(1), pow2Upper, boundary)
    out.nonempty := mode =/= 0.U && out.lower < out.upper
    out.lowerBlockPrevious := out.lower(64, 7) - 1.U
    out.upperBlockPrevious := out.upper(64, 7) - 1.U
    out
  }
}
