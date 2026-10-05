package flow.interface

import chisel3._
import chisel3.util._

/** Cache-side directions, exactly docs/l1d-rtl-spec.md §1.1.
  * Backend uses Flipped(new L1DCoreIO). There is deliberately no hold input.
  */
object L1DOp extends ChiselEnum {
  val Load, Store, LR, SC, AMO, Fence = Value
}
object L1DRespKind extends ChiselEnum {
  val Done, Mshr, Exc = Value
}
class L1DDestination extends Bundle {
  val isFp = Bool()
  val idx = UInt(5.W)
}
class L1DCoreReq extends Bundle {
  val op = L1DOp()
  val vaddr = UInt(64.W)
  val size = UInt(2.W)
  val signed = Bool()
  val amoFunc = BreezeAmoFunc()
  val aq = Bool()
  val rl = Bool()
  val wdata = UInt(64.W)
  val rd = new L1DDestination
  val isFlw = Bool()
}
class L1DCoreResp extends Bundle {
  val kind = L1DRespKind()
  val data = UInt(64.W)
  val excCause = UInt(64.W)
  val tval = UInt(64.W)
}
class L1DLate extends Bundle {
  val rd = new L1DDestination
  val data = UInt(64.W)
  val error = Bool()
}
class L1DCoreIO extends Bundle {
  val req = Flipped(Decoupled(new L1DCoreReq))
  val s1Kill = Input(Bool())
  val s2Kill = Input(Bool())
  val resp = Valid(new L1DCoreResp)
  val s2Hold = Output(Bool())
  val late = Decoupled(new L1DLate)
  val drained = Output(Bool())
  val mmioBusy = Output(Bool())
  val trapClearRsv = Input(Bool())
  val csr = Input(new BreezeMmuContext(64))
}
