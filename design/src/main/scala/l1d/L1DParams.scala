package flow.l1d

import chisel3.util._
import flow.config.BreezeMemGeometry
import flow.coherence.CoherenceParams

/** Derived L1D constants (l1d-rtl-spec §0.2). No geometry literals elsewhere. */
final case class L1DParams(g: BreezeMemGeometry) {
  val coh: CoherenceParams = CoherenceParams(g)
  val ways: Int = g.l1dWays
  val sets: Int = g.l1Sets
  val offBits: Int = log2Ceil(g.lineBytes)
  val idxBits: Int = log2Ceil(sets)
  val idxW: Int = idxBits max 1
  val tagBits: Int = g.paddrBits - idxBits - offBits
  val wordsPerLine: Int = g.lineBytes / 8
  val wordBits: Int = log2Ceil(wordsPerLine) max 1
  val wayBits: Int = log2Ceil(ways) max 1
  val capacityBytes: Int = sets * ways * g.lineBytes
  val lineAddrBits: Int = g.paddrBits - offBits
  val nMshrs: Int = g.l1dMshrs
  val plruBits: Int = (ways - 1) max 1
  val paddrBits: Int = g.paddrBits
  /** S0 store→load conflict compares page-offset 8 B word address [11:3]. */
  val conflictHi: Int = 11
  val conflictLo: Int = 3
  /** LR forward-progress window (§8.1). */
  val rsvWindow: Int = 80
}
