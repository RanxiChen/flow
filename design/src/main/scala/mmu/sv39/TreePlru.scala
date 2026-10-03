package flow.mmu.sv39

import chisel3._
import chisel3.util._

/** Heap-ordered binary tree: each bit points toward the least recently used child. */
object TreePlru {
  private def check(ways: Int): Unit = require(ways >= 1 && (ways & (ways - 1)) == 0)
  def victim(state: UInt, ways: Int): UInt = {
    check(ways)
    def descend(node: Int, first: Int, count: Int): UInt = {
      if (count == 1) first.U(math.max(1, log2Ceil(ways)).W)
      else Mux(state(node), descend(2 * node + 2, first + count / 2, count / 2),
        descend(2 * node + 1, first, count / 2))
    }
    descend(0, 0, ways)
  }
  def touch(state: UInt, way: UInt, ways: Int): UInt = {
    check(ways)
    if (ways == 1) 0.U(0.W)
    else {
      val bits = Wire(Vec(ways - 1, Bool()))
      for (i <- 0 until ways - 1) bits(i) := state(i)
      def update(node: Int, first: Int, count: Int): Unit = if (count > 1) {
        when(way >= first.U && way < (first + count).U) {
          bits(node) := way < (first + count / 2).U
        }
        update(2 * node + 1, first, count / 2)
        update(2 * node + 2, first + count / 2, count / 2)
      }
      update(0, 0, ways); bits.asUInt
    }
  }
}
