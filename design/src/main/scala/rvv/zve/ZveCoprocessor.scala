package flow.rvv.zve

import chisel3._
import chisel3.util._
import flow.rvv.RvvAxi

class ZveCoprocessorIO(p: ZveParams, debug: Boolean) extends Bundle {
  val issue=Flipped(Decoupled(new ZveIssue(p)))
  val verdict=Decoupled(new ZveVerdict(p))
  val commit=Input(Bool()); val kill=Input(Bool()); val serialGo=Input(Bool())
  val completion=Decoupled(new ZveCompletion(p))
  val translation=Decoupled(new ZveTranslationRequest(p))
  val translated=Flipped(Decoupled(new ZveTranslationResponse(p)))
  val order=Decoupled(new ZveMemoryOrder); val release=Decoupled(UInt(16.W))
  val invalidate=Decoupled(UInt(64.W)); val scalarWritesVisible=Input(Bool())
  val axi=new RvvAxi(p.vrf); val sharedFp=new ZveFpService
  val stream=new ZveLineIO(p)
  val drained=Output(Bool()); val scalarMemoryBlocked=Output(Bool())
  /** Accepted vector memory accesses / stores not yet completed. */
  val memoryLive=Output(Bool()); val storeLive=Output(Bool())
  val occupancy=Output(UInt(log2Ceil(p.window+1).W))
  /** Post-commit bus error on the stream VLSU (cannot trap precisely). */
  val fatal=Output(Bool())
  val perfBusy=Output(Vec(5,Bool()))
  val perfStreamIdCount=Output(UInt(3.W))
  val perfStreamLoadStart=Output(Bool()); val perfStreamLoadDone=Output(Bool())
  val debugVrf=if(debug) Some(Flipped(new ZveVrfPort(p))) else None
}

/** Independent coprocessor. No GPR/FPR file, TLB, PTW, cache or SoC instance.
  * FP32 conversion and half-width FMA are local; slow FP arithmetic, address
  * translation and memory ordering have explicit tagged service boundaries. */
class ZveCoprocessor(val p: ZveParams = ZveParams(), debug: Boolean = false) extends Module {
  val io=IO(new ZveCoprocessorIO(p,debug))
  val window=Module(new ZveWindow(p)); val access=Module(new ZveVrfAccess(p,p.units+(if(debug) 1 else 0)))
  window.io.issue.valid := io.issue.valid; window.io.issue.bits := ZveDecode(io.issue.bits,p)
  io.issue.ready := window.io.issue.ready
  io.verdict <> window.io.verdict; io.completion <> window.io.completion
  window.io.commit := io.commit; window.io.kill := io.kill; window.io.serialGo := io.serialGo
  val alu=Module(new ZveAluVectorEngine(p)); val slow=Module(new ZveSlowUnit(p))
  val conversion=Module(new ZveFpVectorEngine(p,p.conversionLanes))
  val fma=Module(new ZveFpVectorEngine(p,p.fmaLanes))
  val cross=Module(new ZveElementEngine(p)); val memory=Module(new ZveMemoryEngine(p))
  val stream=Module(new ZveStreamEngine(p)); val mac=Module(new ZveMacVectorEngine(p))
  io.perfBusy := VecInit(Seq(!alu.io.drained,!mac.io.drained,!conversion.io.drained,
    !fma.io.drained,!stream.io.drained))
  io.perfStreamIdCount := stream.io.perfIdCount
  io.perfStreamLoadStart := stream.io.in.fire && stream.io.in.bits.issue.scalar.instruction(6,0) === 7.U
  io.perfStreamLoadDone := stream.io.perfLoadDone
  // Order follows ZveUnit.
  val inputs=Seq(alu.io.in,slow.io.in,conversion.io.in,fma.io.in,cross.io.in,memory.io.in,stream.io.in,mac.io.in)
  val completions=Seq(alu.io.complete,slow.io.complete,conversion.io.complete,fma.io.complete,cross.io.complete,memory.io.complete,stream.io.complete,mac.io.complete)
  val ports=Seq(alu.io.vrf,slow.io.vrf,conversion.io.vrf,fma.io.vrf,cross.io.vrf,memory.io.vrf,stream.io.vrf,mac.io.vrf)
  require(inputs.length==p.units)
  for(u<-0 until p.units) {
    inputs(u) <> window.io.execute(u); window.io.complete(u) <> completions(u)
    access.io.ports(u) <> ports(u)
  }
  io.debugVrf.foreach(access.io.ports(p.units) <> _)
  Seq(alu.io.mask,slow.io.mask,conversion.io.mask,fma.io.mask,cross.io.mask,memory.io.mask,stream.io.mask,mac.io.mask).foreach(_ := access.io.mask)
  io.stream <> stream.io.line; io.fatal := stream.io.fatal
  io.memoryLive := window.io.memoryLive; io.storeLive := window.io.storeLive; io.occupancy := window.io.occupancy
  for(l<-0 until p.conversionLanes) {
    val service=Module(new ZveFpnewService(1)); service.io.service <> conversion.io.fp(l)
  }
  for(l<-0 until p.fmaLanes) {
    val service=Module(new ZveFpnewService(0)); service.io.service <> fma.io.fp(l)
  }
  val shared=Module(new ZveSharedFpMux)
  shared.io.clients(0) <> slow.io.fp; shared.io.clients(1) <> cross.io.fp
  io.sharedFp <> shared.io.service
  io.translation <> memory.io.translation; memory.io.translated <> io.translated
  io.order <> memory.io.order; io.release <> memory.io.release; io.invalidate <> memory.io.invalidate
  memory.io.scalarWritesVisible := io.scalarWritesVisible; io.axi <> memory.io.axi
  io.scalarMemoryBlocked := memory.io.scalarMemoryBlocked || window.io.serialActive
  io.drained := window.io.drained && access.io.drained && shared.io.drained &&
    Seq(alu.io.drained,slow.io.drained,conversion.io.drained,fma.io.drained,cross.io.drained,memory.io.drained,stream.io.drained,mac.io.drained).reduce(_ && _)
}

/** The only additional arithmetic in this shell replaces the future shared
  * scalar FPU, with the production service protocol and real FPnew arithmetic. */
class ZveStandaloneHarness(p: ZveParams = ZveParams()) extends Module {
  val io=IO(new Bundle {
    val issue=Flipped(Decoupled(new ZveIssue(p))); val verdict=Decoupled(new ZveVerdict(p))
    val commit=Input(Bool()); val kill=Input(Bool()); val serialGo=Input(Bool())
    val completion=Decoupled(new ZveCompletion(p))
    val translation=Decoupled(new ZveTranslationRequest(p)); val translated=Flipped(Decoupled(new ZveTranslationResponse(p)))
    val order=Decoupled(new ZveMemoryOrder); val release=Decoupled(UInt(16.W))
    val invalidate=Decoupled(UInt(64.W)); val scalarWritesVisible=Input(Bool())
    val axi=new RvvAxi(p.vrf); val debugVrf=Flipped(new ZveVrfPort(p))
    val drained=Output(Bool()); val scalarMemoryBlocked=Output(Bool())
  })
  val core=Module(new ZveCoprocessor(p,true)); val service=Module(new ZveFpnewService(2))
  core.io.issue <> io.issue; io.verdict <> core.io.verdict; io.completion <> core.io.completion
  core.io.commit := io.commit; core.io.kill := io.kill; core.io.serialGo := io.serialGo
  io.translation <> core.io.translation; core.io.translated <> io.translated
  io.order <> core.io.order; io.release <> core.io.release; io.invalidate <> core.io.invalidate
  core.io.scalarWritesVisible := io.scalarWritesVisible; io.axi <> core.io.axi
  core.io.debugVrf.get <> io.debugVrf; service.io.service <> core.io.sharedFp
  // Only pre-translated issues use the stream VLSU; this shell never sets pre.
  core.io.stream.request.ready := false.B
  core.io.stream.response.valid := false.B; core.io.stream.response.bits := 0.U.asTypeOf(core.io.stream.response.bits)
  io.drained := core.io.drained && service.io.drained; io.scalarMemoryBlocked := core.io.scalarMemoryBlocked
}
