package flow.rvv

import chisel3._
import chisel3.util._
import flow.backend.{CoprocessorDecision, FpResult}

class RvvServices(p: RvvParams) extends Bundle {
  val translation = Decoupled(new RvvTranslationRequest(p))
  val translated = Flipped(Decoupled(new RvvTranslationResponse(p)))
  val axi = new RvvAxi(p)
  val invalidate = Decoupled(UInt(64.W))
  val scalarWritesVisible = Input(Bool())
  val conflictQuery = Input(new RvvConflict)
  val conflict = Output(Bool())
}

/** ID owns bounded credits including the EX slot and ingress FIFO. RVV
  * ready terminates at this FIFO and never controls EX/MEM/PERM/WB progress.
  * WB pops its registered verdict locally; delayed commit authorizes RVV.
  */
class RvvMount(p: RvvParams) extends Module {
  val io = IO(new Bundle {
    val reserve = Input(Bool()); val available = Output(Bool())
    val issue = Flipped(Valid(new RvvIssue(p)))
    val consume = Input(Bool()); val commitD = Input(Bool()); val killD = Input(Bool())
    val decision = Valid(new CoprocessorDecision)
    val verdict = Output(new RvvVerdict(p))
    val result = Decoupled(new FpResult)
    val returned = Output(UInt(6.W)); val drained = Output(Bool())
    val vxsat = Output(Bool()); val services = new RvvServices(p)
    val fatal = Output(Bool())
  })
  val rvv = Module(new RvvCoprocessor(p))
  io.fatal := rvv.io.fatal
  val ingress = withReset(reset.asBool || io.killD) { Module(new Queue(new RvvIssue(p), p.viqDepth, useSyncReadMem = true)) }
  ingress.io.enq.valid := io.issue.valid && !io.killD
  ingress.io.enq.bits := io.issue.bits
  rvv.io.issue <> ingress.io.deq
  when(io.issue.valid && !io.killD) { assert(ingress.io.enq.ready, "reserved vector request rejected") }
  val decisions = withReset(reset.asBool || io.killD) { Module(new Queue(new RvvVerdict(p), p.viqDepth, useSyncReadMem = true)) }
  decisions.io.enq <> rvv.io.verdict
  decisions.io.deq.ready := io.consume
  io.decision.valid := decisions.io.deq.valid && !io.killD
  io.verdict := decisions.io.deq.bits
  io.decision.bits.exception := decisions.io.deq.bits.kind === 2.U || decisions.io.deq.bits.kind === 3.U
  io.decision.bits.cause := Mux(decisions.io.deq.bits.kind === 3.U, 2.U, decisions.io.deq.bits.cause)
  io.decision.bits.tval := decisions.io.deq.bits.tval
  io.decision.bits.vstartMarker := decisions.io.deq.bits.kind === 2.U
  when(io.consume) { assert(decisions.io.deq.valid, "WB consumed a missing vector verdict") }
  rvv.io.commit := io.commitD; rvv.io.killUncommitted := io.killD; rvv.io.serialGo := false.B
  val used = RegInit(0.U(log2Ceil(p.viqDepth + 1).W))
  val uncommitted = RegInit(0.U(log2Ceil(p.viqDepth + 1).W))
  val killed = Mux(io.killD, uncommitted - io.commitD.asUInt, 0.U)
  io.returned := rvv.io.creditReturn.asUInt +& killed
  io.available := used < p.viqDepth.U
  used := used + io.reserve.asUInt - io.returned
  uncommitted := Mux(io.killD, 0.U, uncommitted + io.reserve.asUInt - io.commitD.asUInt)
  assert(used <= p.viqDepth.U && io.returned <= used, "vector credit ownership overflow/underflow")
  when(io.reserve) { assert(io.available && !io.killD) }
  when(io.commitD) { assert(uncommitted =/= 0.U) }
  io.result.valid := rvv.io.scalarResult.valid && !rvv.io.fatal
  io.result.bits := 0.U.asTypeOf(io.result.bits)
  io.result.bits.rd.idx := rvv.io.scalarResult.bits.rd
  io.result.bits.rd.isFp := rvv.io.scalarResult.bits.floating
  io.result.bits.data := rvv.io.scalarResult.bits.data
  rvv.io.scalarResult.ready := io.result.ready && !rvv.io.fatal
  io.drained := used === 0.U && uncommitted === 0.U && ingress.io.count === 0.U &&
    decisions.io.count === 0.U && rvv.io.drained
  io.vxsat := rvv.io.vxsat
  io.services.translation <> rvv.io.translation
  rvv.io.translated <> io.services.translated
  io.services.axi <> rvv.io.axi
  io.services.invalidate <> rvv.io.invalidate
  rvv.io.scalarWritesVisible := io.services.scalarWritesVisible
  rvv.io.conflictQuery := io.services.conflictQuery
  io.services.conflict := rvv.io.conflict
}
