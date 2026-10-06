package flow.coherence

import chisel3._
import chisel3.util._
import flow.config.BreezeMemGeometry

/** Derived protocol/L2 constants (coherence-l2-rtl-spec §0.2). No geometry literals elsewhere. */
final case class CoherenceParams(g: BreezeMemGeometry) {
  val nCores: Int = g.nCores
  val lineBytes: Int = g.lineBytes
  val lineBits: Int = lineBytes * 8
  val offBits: Int = log2Ceil(lineBytes)
  val paddrBits: Int = g.paddrBits
  val lineAddrBits: Int = paddrBits - offBits
  val l2Ways: Int = g.l2Ways
  val l2Sets: Int = g.nCores * g.l2BytesPerCore / (g.l2Ways * g.lineBytes)
  val setBits: Int = log2Ceil(l2Sets) max 1
  val tagBits: Int = paddrBits - log2Ceil(l2Sets) - offBits
  val wayBits: Int = log2Ceil(l2Ways) max 1
  val plruBits: Int = (l2Ways - 1) max 1
  val sharerBits: Int = nCores
  val coreBits: Int = log2Ceil(nCores) max 1
  val l2Slots: Int = g.l2Slots
  val slotBits: Int = log2Ceil(l2Slots) max 1
  val memDataBits: Int = 64
  val memBeats: Int = lineBits / memDataBits
  /** REQ ports: L1D(0..n-1), L1I(0..n-1), DMA. */
  val nReqPorts: Int = 2 * nCores + 1
  def l1dPort(c: Int): Int = c
  def l1iPort(c: Int): Int = nCores + c
  val dmaPort: Int = 2 * nCores
  val portBits: Int = log2Ceil(nReqPorts) max 1
  /** RSP↓ FIFO depth per client (§2.2): L1D 2, L1I 2, DMA 1. */
  def rspDownDepth(port: Int): Int = if (port == dmaPort) 1 else 2

  def setOf(lineAddr: UInt): UInt =
    if (l2Sets == 1) 0.U(1.W) else lineAddr(log2Ceil(l2Sets) - 1, 0)
  def tagOf(lineAddr: UInt): UInt = lineAddr(lineAddrBits - 1, log2Ceil(l2Sets))
  /** Reconstruct a physical line address without the single-set placeholder bit. */
  def lineOf(tag: UInt, set: UInt): UInt = if (l2Sets == 1) tag else tag ## set
}
