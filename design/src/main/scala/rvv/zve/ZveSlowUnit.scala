package flow.rvv.zve

import chisel3._
import chisel3.util._

/** One queue endpoint owns either the narrow integer engine or the external
  * single-element floating-point path. Other queue endpoints are independent. */
class ZveSlowUnit(p: ZveParams) extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new ZveCommand(p)))
    val vrf=new ZveVrfPort(p); val mask=Input(UInt(p.vrf.vlen.W))
    val fp=new ZveFpService
    val complete=Decoupled(new ZveCompletion(p)); val drained=Output(Bool())
  })
  val integer=Module(new ZveElementEngine(p)); val floating=Module(new ZveFpVectorEngine(p,1))
  val useFloat=RegInit(false.B)
  val requestFloat=Seq(ZveOp.vfmul,ZveOp.vfdiv,ZveOp.vfrdiv,ZveOp.vfsqrt).map(n=>io.in.bits.operation===n.U).reduce(_ || _)
  io.in.ready := integer.io.drained && floating.io.drained
  integer.io.in.valid := io.in.valid && !requestFloat && floating.io.drained
  floating.io.in.valid := io.in.valid && requestFloat && integer.io.drained
  integer.io.in.bits := io.in.bits; floating.io.in.bits := io.in.bits
  when(io.in.fire) { useFloat := requestFloat }
  integer.io.mask := io.mask; floating.io.mask := io.mask
  io.vrf.read.valid := Mux(useFloat,floating.io.vrf.read.valid,integer.io.vrf.read.valid)
  io.vrf.read.bits := Mux(useFloat,floating.io.vrf.read.bits,integer.io.vrf.read.bits)
  io.vrf.write.valid := Mux(useFloat,floating.io.vrf.write.valid,integer.io.vrf.write.valid)
  io.vrf.write.bits := Mux(useFloat,floating.io.vrf.write.bits,integer.io.vrf.write.bits)
  for((unit,choose)<-Seq((floating.io.vrf,useFloat),(integer.io.vrf,!useFloat))) {
    unit.read.ready := choose && io.vrf.read.ready; unit.write.ready := choose && io.vrf.write.ready
    unit.returned.valid := choose && io.vrf.returned.valid; unit.returned.bits := io.vrf.returned.bits
    unit.written.valid := choose && io.vrf.written.valid; unit.written.bits := io.vrf.written.bits
  }
  io.vrf.returned.ready := Mux(useFloat,floating.io.vrf.returned.ready,integer.io.vrf.returned.ready)
  io.vrf.written.ready := Mux(useFloat,floating.io.vrf.written.ready,integer.io.vrf.written.ready)
  io.complete.valid := Mux(useFloat,floating.io.complete.valid,integer.io.complete.valid)
  io.complete.bits := Mux(useFloat,floating.io.complete.bits,integer.io.complete.bits)
  floating.io.complete.ready := io.complete.ready && useFloat
  integer.io.complete.ready := io.complete.ready && !useFloat
  io.fp <> floating.io.fp(0)
  integer.io.fp.request.ready := false.B; integer.io.fp.response.valid := false.B
  integer.io.fp.response.bits := 0.U.asTypeOf(new ZveFpResponse)
  assert(!integer.io.fp.request.valid,"FP reduction routed to integer queue")
  io.drained := integer.io.drained && floating.io.drained
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
