package flow.l1i

import chisel3.util.log2Ceil
import flow.config.BreezeMemGeometry

/** Derived I-cache geometry; config.scala migration is deliberately separate. */
final case class L1IParams(g: BreezeMemGeometry = BreezeMemGeometry(l1Sets = 64)) {
  val VLEN = 64
  val PLEN = 64 // retain high PA until PMA; protocol itself is g.paddrBits
  val FETCH_WIDTH = 32
  val ICACHE_LINE_BYTES = g.lineBytes
  val ICACHE_SET_NUM = g.l1Sets
  val ICACHE_WAY_NUM = g.l1iWays
  val ICACHE_LINE_WIDTH = g.lineBytes * 8
  val ICACHE_BYTES_OFFSET_WIDTH = log2Ceil(FETCH_WIDTH / 8)
  val ICACHE_LINE_OFFSET_WIDTH = log2Ceil(g.lineBytes) - ICACHE_BYTES_OFFSET_WIDTH
  val ICACHE_INDEX_WIDTH = log2Ceil(g.l1Sets)
  val ICACHE_TAG_WIDTH = PLEN - log2Ceil(g.lineBytes) - ICACHE_INDEX_WIDTH
  val PLRU_WIDTH = g.l1iWays - 1
  val META_WIDTH = PLRU_WIDTH + g.l1iWays
}
