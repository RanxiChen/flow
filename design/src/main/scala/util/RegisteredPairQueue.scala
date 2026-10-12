package flow.util

import chisel3._
import chisel3.util._

/** Two non-flow entries with the same interface cycles as Queue(gen, 2).
  * Explicit independent slots permit FPGA FF/CE inference for wide shallow
  * storage. Only ownership resets; invalid payload has no architectural use.
  * pipe=true permits a full queue to replace its consumed head at the edge.
  */
class RegisteredPairQueue[T <: Data](gen: T, pipe: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val deq = Decoupled(gen)
    val count = Output(UInt(2.W))
  })
  val first = Reg(gen)
  val second = Reg(gen)
  val head = RegInit(false.B)
  val tail = RegInit(false.B)
  val count = RegInit(0.U(2.W))
  io.enq.ready := count =/= 2.U || (if (pipe) io.deq.ready else false.B)
  io.deq.valid := count =/= 0.U
  io.deq.bits := Mux(head, second, first)
  io.count := count
  when(io.enq.fire) {
    when(tail) { second := io.enq.bits }.otherwise { first := io.enq.bits }
    tail := !tail
  }
  when(io.deq.fire) { head := !head }
  count := count + io.enq.fire.asUInt - io.deq.fire.asUInt
  when(!reset.asBool) { assert(count <= 2.U, "pair queue ownership overflow") }
}
