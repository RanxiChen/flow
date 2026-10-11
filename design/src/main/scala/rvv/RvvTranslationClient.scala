package flow.rvv

import chisel3._
import chisel3.util._
import flow.interface._
import flow.mmu.BreezePmpChecker
import flow.mmu.sv39._
import flow.platform.{PMAChecker, PMAAccessType}

/** Accepted translation tags always receive a response, including after a
  * vector kill. Every byte in the page segment is permission checked before
  * returning Ok; checking just the first address would miss PMP boundaries.
  */
class RvvTranslationClient(p: RvvParams) extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new RvvTranslationRequest(p)))
    val response = Decoupled(new RvvTranslationResponse(p))
    val tlb = Flipped(new TlbPortIO(withPmpCandidate = true))
    val context = Input(new BreezeMmuContext(64))
    val idle = Output(Bool())
  })
  val idle :: send :: waitTlb :: check :: respond :: Nil = Enum(5)
  val state = RegInit(idle)
  val q = Reg(new RvvTranslationRequest(p))
  val context = Reg(new BreezeMmuContext(64))
  val result = Reg(new RvvTranslationResponse(p))
  val position = RegInit(0.U(p.lengthBits.W))
  io.request.ready := state === idle
  io.response.valid := state === respond; io.response.bits := result
  io.idle := state === idle
  io.tlb.kill := false.B
  io.tlb.req.valid := state === send
  io.tlb.req.bits.vaddr := q.va
  io.tlb.req.bits.cmd := Mux(q.write, MmuCmd.Store, MmuCmd.Load)
  when(io.request.fire) {
    assert(io.request.bits.bytes =/= 0.U && io.request.bits.bytes <= p.maxBytes.U)
    q := io.request.bits; context := io.context; state := send; position := 0.U
    result := 0.U.asTypeOf(result); result.id := io.request.bits.id
    result.cause := Mux(io.request.bits.write, 7.U, 5.U)
  }
  when(io.tlb.req.fire) { state := waitTlb }
  when(state === waitTlb && io.tlb.resp.valid) {
    when(io.tlb.resp.bits.miss) { state := send }
      .elsewhen(io.tlb.resp.bits.pageFault || io.tlb.resp.bits.accessFault) {
        result.exception := true.B
        result.cause := Mux(io.tlb.resp.bits.pageFault, Mux(q.write, 15.U, 13.U), Mux(q.write, 7.U, 5.U))
        state := respond
      }.otherwise { result.pa := io.tlb.resp.bits.paddr; state := check }
  }
  val pa = result.pa + position
  val pmp = Module(new BreezePmpChecker(64, decoded = true))
  pmp.io.addr := pa; pmp.io.sizeLog2 := 0.U
  pmp.io.context := context
  pmp.io.privilege := Mux(context.mprv, context.mpp, context.privilege)
  pmp.io.access := Mux(q.write, BreezeMmuAccess.Store, BreezeMmuAccess.Load)
  val pma = Module(new PMAChecker)
  pma.io.query.addr := pa; pma.io.query.sizeLog2 := 0.U
  pma.io.query.accessType := Mux(q.write, PMAAccessType.Store, PMAAccessType.Load)
  when(state === check) {
    when(!pmp.io.allowed || !pma.io.result.allowed || !pma.io.result.cacheable || pma.io.result.device) {
      result.exception := !pmp.io.allowed || !pma.io.result.allowed
      result.cacheable := pma.io.result.cacheable; result.device := pma.io.result.device
      state := respond
    }.elsewhen(position + 1.U === q.bytes) {
      result.cacheable := true.B; state := respond
    }.otherwise { position := position + 1.U }
  }
  when(io.response.fire) { state := idle }
}

/** Scalar lookup still has the original one-cycle response. Vector requests
  * use otherwise free cycles; response ownership is registered, including
  * the kill domain of the scalar request.
  */
class RvvTlbArbiter extends Module {
  val io = IO(new Bundle {
    val scalarWants = Input(Bool())
    val scalar = new TlbPortIO(withPmpCandidate = true)
    val vector = new TlbPortIO(withPmpCandidate = true)
    val tlb = Flipped(new TlbPortIO(withPmpCandidate = true))
  })
  val chooseVector = !io.scalarWants && io.vector.req.valid
  val previousVector = RegNext(chooseVector && io.tlb.req.fire, false.B)
  io.tlb.req.valid := Mux(chooseVector, io.vector.req.valid && !io.scalar.kill, io.scalar.req.valid)
  io.tlb.req.bits := Mux(chooseVector, io.vector.req.bits, io.scalar.req.bits)
  io.tlb.kill := io.scalar.kill && !previousVector
  io.scalar.canAccept := io.tlb.canAccept && !chooseVector
  io.vector.canAccept := io.tlb.canAccept && chooseVector
  io.scalar.req.ready := io.tlb.req.ready && !chooseVector
  io.vector.req.ready := io.tlb.req.ready && chooseVector && !io.scalar.kill
  for ((port, vector) <- Seq(io.scalar -> false, io.vector -> true)) {
    port.resp.valid := io.tlb.resp.valid && previousVector === vector.B
    port.resp.bits := io.tlb.resp.bits
    port.candidatePaddr.get := io.tlb.candidatePaddr.get
  }
}
