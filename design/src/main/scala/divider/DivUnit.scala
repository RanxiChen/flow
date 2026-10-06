package flow.divider

import chisel3._
import chisel3.util._
import flow.backend.{IntDivRequest, IntMduIO}

class DivUnit extends Module {
  val io = IO(new IntMduIO(new IntDivRequest))
  val occupied = RegInit(false.B)
  val committed = RegInit(false.B)
  val done = RegInit(false.B)
  val rd = Reg(UInt(5.W))
  val data = Reg(UInt(64.W))
  val quotientNeg = Reg(Bool())
  val remainderNeg = Reg(Bool())
  val isRemainder = Reg(Bool())
  val isWord = Reg(Bool())
  val divider = Module(new UnsignedRadix4Divider)
  val arithmeticActive = IO(Output(Bool()))
  arithmeticActive := divider.io.busy
  val cancel = io.killUncommitted && occupied && !committed && !io.commit
  io.req.ready := !occupied
  io.result.valid := occupied && committed && done
  io.result.bits.rd := rd
  io.result.bits.data := data
  divider.io.flush := cancel
  divider.io.in_valid := io.req.fire && !io.req.bits.fastValid && !io.killUncommitted
  divider.io.dividend := io.req.bits.dividendMag
  divider.io.divisor := io.req.bits.divisorMag
  val quotient = Mux(quotientNeg, 0.U(64.W) - divider.io.quotient, divider.io.quotient)
  val remainder = Mux(remainderNeg, 0.U(64.W) - divider.io.remainder, divider.io.remainder)
  val selected = Mux(isRemainder, remainder, quotient)

  when(io.req.fire && !io.killUncommitted) {
    occupied := true.B
    committed := false.B
    done := io.req.bits.fastValid
    data := io.req.bits.fastData
    rd := io.req.bits.rd
    quotientNeg := io.req.bits.quotientNeg
    remainderNeg := io.req.bits.remainderNeg
    isRemainder := io.req.bits.isRemainder
    isWord := io.req.bits.isWord
  }
  when(occupied && !done && divider.io.out_valid) {
    done := true.B
    data := Mux(isWord, Cat(Fill(32, selected(31)), selected(31, 0)), selected)
  }
  when(io.commit) { committed := true.B }
  when(cancel || io.result.fire) {
    occupied := false.B
    committed := false.B
    done := false.B
  }
  when(!reset.asBool) {
    assert(!io.commit || (occupied && !committed), "[MDU S04] DIV commit without an uncommitted item")
    assert(!(io.killUncommitted && io.req.fire), "[MDU] WB kill must suppress younger EX request")
    assert(!io.req.fire || io.req.bits.rd =/= 0.U, "[MDU S01] DIV x0 must not issue")
    assert(!occupied || !io.req.ready, "[MDU S12] occupied DIV accepted a request")
    assert(!(io.result.fire && io.req.fire), "[MDU S12] DIV release and receive overlapped")
  }
  val pastActive = RegNext(!reset.asBool, false.B)
  val wasBlocked = RegNext(io.result.valid && !io.result.ready, false.B)
  val heldResult = RegNext(io.result.bits.asUInt)
  when(pastActive && !reset.asBool && wasBlocked) {
    assert(io.result.valid && io.result.bits.asUInt === heldResult, "[MDU S07] stalled DIV result changed")
  }
}
