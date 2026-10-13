package flow.rvv.zve

import chisel3._
import chisel3.util._

/** Single-lane floating-point queue endpoint (vfmul, vfdiv, vfrdiv, vfsqrt)
  * on the shared scalar FPU. R07: the narrow integer engine that used to sit
  * here was a second full ZveElementEngine; that work now goes to the cross
  * unit. */
class ZveSlowUnit(p: ZveParams) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ZveCommand(p)))
    val vrf=new ZveVrfPort(p); val mask=Input(UInt(p.vrf.vlen.W))
    val fp=new ZveFpService
    val complete=Decoupled(new ZveCompletion(p)); val drained=Output(Bool())
  })
  val floating=Module(new ZveFpVectorEngine(p,1))
  when(io.in.fire) {
    assert(Seq(ZveOp.vfmul,ZveOp.vfdiv,ZveOp.vfrdiv,ZveOp.vfsqrt).map(n=>io.in.bits.operation===n.U).reduce(_ || _),
      "non-FP instruction routed to the slow FP queue")
  }
  floating.io.in <> io.in; floating.io.mask := io.mask
  io.vrf <> floating.io.vrf; io.fp <> floating.io.fp(0)
  io.complete <> floating.io.complete
  io.drained := floating.io.drained
}

/** Vector clients share a future SoC-owned FPU. Their local tags are kept
  * intact; bit 15 records the client and is stripped from each response. */
class ZveSharedFpMux extends Module {
  val io=IO(new Bundle {
    val clients=Vec(2,Flipped(new ZveFpService))
    val service=new ZveFpService
    val drained=Output(Bool())
  })
  val arb=Module(new RRArbiter(new ZveFpRequest,2))
  for(c<-0 until 2) {
    arb.io.in(c).valid := io.clients(c).request.valid
    arb.io.in(c).bits := io.clients(c).request.bits
    arb.io.in(c).bits.tag := Cat(c.U(1.W),io.clients(c).request.bits.tag(14,0))
    io.clients(c).request.ready := arb.io.in(c).ready
    when(io.clients(c).request.fire) { assert(!io.clients(c).request.bits.tag(15),"FP client tag exceeds its namespace") }
  }
  val requests=Module(new Queue(new ZveFpRequest,2,pipe=false,flow=false))
  requests.io.enq <> arb.io.out; io.service.request <> requests.io.deq
  val responses=Seq.fill(2)(Module(new Queue(new ZveFpResponse,2,pipe=false,flow=false)))
  val owner=io.service.response.bits.tag(15)
  for(c<-0 until 2) {
    responses(c).io.enq.valid := io.service.response.valid && owner===c.U
    responses(c).io.enq.bits := io.service.response.bits
    responses(c).io.enq.bits.tag := io.service.response.bits.tag(14,0)
    io.clients(c).response <> responses(c).io.deq
  }
  io.service.response.ready := Mux(owner,responses(1).io.enq.ready,responses(0).io.enq.ready)
  val inFlight=RegInit(0.U(16.W))
  val starts=PopCount(io.clients.map(_.request.fire)); val ends=PopCount(io.clients.map(_.response.fire))
  when(starts=/=ends) { inFlight := inFlight+starts-ends }
  assert(inFlight+&starts>=ends,"shared FP response has no request owner")
  io.drained := inFlight===0.U && !requests.io.deq.valid && !responses.map(_.io.deq.valid).reduce(_ || _)
}
