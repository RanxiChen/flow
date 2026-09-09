package flow.mmu

import chisel3._
import chisel3.util._
import flow.core.PRIV_MODE
import flow.interface._

/** First-match PMP checker for 16 RV64 entries (TOR, NA4 and NAPOT). */
class BreezePmpChecker(val xlen: Int = 64) extends Module {
  val io = IO(new Bundle {
    val addr = Input(UInt(xlen.W))
    val sizeLog2 = Input(UInt(3.W))
    val access = Input(BreezeMmuAccess())
    val privilege = Input(UInt(PRIV_MODE.width.W))
    val context = Input(new BreezeMmuContext(xlen))
    val allowed = Output(Bool())
  })

  val accessStart = Cat(0.U(1.W), io.addr)
  val accessLast = Wire(UInt(65.W))
  accessLast := accessStart + ((1.U(65.W) << io.sizeLog2) - 1.U)

  // sizeLog2 is three bits: an access spans at most 128 bytes and can
  // therefore touch at most two adjacent 128-byte blocks, even unaligned.
  private val blockBits = 7
  val startHigh = accessStart(64, blockBits)
  val lastHigh = accessLast(64, blockBits)
  val startLow = accessStart(blockBits - 1, 0)
  val lastLow = accessLast(blockBits - 1, 0)
  val sameBlock = startHigh === lastHigh

  // TOR entries share their boundaries with their neighbours. Compute each
  // endpoint/boundary relation once, rather than comparing against selected
  // TOR/NA4/NAPOT lower and upper bounds inside every entry.
  val boundaries = io.context.pmpaddr.map(addr => Cat(0.U(9.W), addr, 0.U(2.W)))
  def belowBoundary(high: UInt, low: UInt, boundary: UInt): Bool = {
    val boundHigh = boundary(64, blockBits)
    val boundLow = boundary(blockBits - 1, 0)
    high < boundHigh || (high === boundHigh && low < boundLow)
  }
  val startBelow = boundaries.map(b => belowBoundary(startHigh, startLow, b))
  val lastBelow = boundaries.map(b => belowBoundary(lastHigh, lastLow, b))
  val overlaps = Wire(Vec(16, Bool()))
  val contains = Wire(Vec(16, Bool()))
  val permissions = Wire(Vec(16, Bool()))

  for (n <- 0 until 16) {
    val cfg = io.context.pmpcfg(n)
    val a = cfg(4, 3)
    val encoded = io.context.pmpaddr(n)
    val napotMask = encoded ^ (encoded + 1.U)
    val byteMask = Cat(0.U(9.W), Mux(a(0), napotMask, 0.U(54.W)), 3.U(2.W))
    val comparand = boundaries(n)
    val startMatch = ((accessStart ^ comparand) & ~byteMask) === 0.U
    val lastMatch = ((accessLast ^ comparand) & ~byteMask) === 0.U

    // Endpoint matches also prove full containment for a power-of-two region.
    // For overlap, a small region may lie entirely between the two endpoints.
    // Cover that case using only seven-bit comparisons and block equality;
    // do not assume that callers have removed misaligned accesses.
    val smallRegion = !byteMask(64, blockBits).orR
    val baseLow = comparand(blockBits - 1, 0) & ~byteMask(blockBits - 1, 0)
    val baseHigh = comparand(64, blockBits)
    val baseInStartBlock = startHigh === baseHigh && startLow <= baseLow
    val baseInLastBlock = lastHigh === baseHigh && baseLow <= lastLow
    val coversBase = Mux(sameBlock,
      baseInStartBlock && baseInLastBlock, baseInStartBlock || baseInLastBlock)
    val pow2Overlap = startMatch || lastMatch || (smallRegion && coversBase)
    val pow2Contains = startMatch && lastMatch

    val startAtOrAboveLower = if (n == 0) true.B else !startBelow(n - 1)
    val lastAtOrAboveLower = if (n == 0) true.B else !lastBelow(n - 1)
    val torOverlap = startBelow(n) && lastAtOrAboveLower
    val torContains = startAtOrAboveLower && lastBelow(n)
    overlaps(n) := Mux(a(1), pow2Overlap, a(0) && torOverlap)
    contains(n) := Mux(a(1), pow2Contains, torContains)
    val rwx = MuxLookup(io.access, false.B)(Seq(
      BreezeMmuAccess.Fetch -> cfg(2),
      BreezeMmuAccess.Load -> cfg(0),
      BreezeMmuAccess.Store -> cfg(1)))
    permissions(n) := contains(n) &&
      Mux(io.privilege === PRIV_MODE.M.U && !cfg(7), true.B, rwx)
  }

  val anyMatch = overlaps.asUInt.orR
  val selected = PriorityEncoder(overlaps.asUInt)
  io.allowed := Mux(anyMatch, permissions(selected), io.privilege === PRIV_MODE.M.U)
}
