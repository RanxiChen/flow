package flow.cache

import chisel3._
import chisel3.util._
import flow.interface.BreezeAmoFunc

/** Combinational RV64A AMO ALU.
  *
  * Lives inside each private L1D; the atomicity of an AMO comes from holding
  * the line in M across the read-modify-write window, not from this ALU.
  *
  * For word (isWord) operations the caller places the addressed 32-bit half in
  * the low 32 bits of `oldOperand`/`rs2`; the result's low 32 bits carry the
  * new value and the upper 32 bits are undefined (the caller masks them on the
  * line merge). Min/Max compare signed, MinU/MaxU unsigned, at the operation
  * width.
  */
class BreezeAmoAlu extends Module {
  val io = IO(new Bundle {
    val func = Input(BreezeAmoFunc())
    val isWord = Input(Bool())
    val oldOperand = Input(UInt(64.W))
    val rs2 = Input(UInt(64.W))
    val newOperand = Output(UInt(64.W))
  })

  private def compute(width: Int): UInt = {
    val old = io.oldOperand(width - 1, 0)
    val rhs = io.rs2(width - 1, 0)
    val oldS = old.asSInt
    val rhsS = rhs.asSInt
    MuxLookup(io.func, rhs)(Seq(
      BreezeAmoFunc.Swap -> rhs,
      BreezeAmoFunc.Add  -> (old + rhs),
      BreezeAmoFunc.Xor  -> (old ^ rhs),
      BreezeAmoFunc.And  -> (old & rhs),
      BreezeAmoFunc.Or   -> (old | rhs),
      BreezeAmoFunc.Min  -> Mux(oldS < rhsS, old, rhs),
      BreezeAmoFunc.Max  -> Mux(oldS > rhsS, old, rhs),
      BreezeAmoFunc.MinU -> Mux(old < rhs, old, rhs),
      BreezeAmoFunc.MaxU -> Mux(old > rhs, old, rhs)
    ))(width - 1, 0)
  }

  io.newOperand := Mux(io.isWord, compute(32).pad(64), compute(64))
}
