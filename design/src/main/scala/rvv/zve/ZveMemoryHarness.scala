package flow.rvv.zve

import chisel3._

/** Window+VLSU+physical VRF integration shell. Arithmetic is deliberately not
  * instantiated here; the separate full-coprocessor gate covers that topology. */
class ZveMemoryHarness(p: ZveParams = ZveParams()) extends Module {
  val io=IO(new ZveCoprocessorIO(p,true))
  val window=Module(new ZveWindow(p)); val memory=Module(new ZveMemoryEngine(p)); val access=Module(new ZveVrfAccess(p,2))
  val decoded=ZveDecode(io.issue.bits,p)
  window.io.issue.valid := io.issue.valid; window.io.issue.bits := decoded; io.issue.ready := window.io.issue.ready
  when(io.issue.fire) { assert(decoded.unit===ZveUnit.memory.U,"non-memory instruction in memory-only harness") }
  io.verdict <> window.io.verdict; io.completion <> window.io.completion
  window.io.commit := io.commit; window.io.kill := io.kill; window.io.serialGo := io.serialGo
  memory.io.in <> window.io.execute(ZveUnit.memory); window.io.complete(ZveUnit.memory) <> memory.io.complete
  for(u<-0 until p.units if u!=ZveUnit.memory) {
    window.io.execute(u).ready := false.B
    window.io.complete(u).valid := false.B; window.io.complete(u).bits := 0.U.asTypeOf(new ZveCompletion(p))
  }
  access.io.ports(0) <> memory.io.vrf; access.io.ports(1) <> io.debugVrf.get
  memory.io.mask := access.io.mask
  io.translation <> memory.io.translation; memory.io.translated <> io.translated
  io.order <> memory.io.order; io.release <> memory.io.release; io.invalidate <> memory.io.invalidate
  memory.io.scalarWritesVisible := io.scalarWritesVisible; io.axi <> memory.io.axi
  io.sharedFp.request.valid := false.B; io.sharedFp.request.bits := 0.U.asTypeOf(new ZveFpRequest)
  io.sharedFp.response.ready := true.B
  io.stream.request.valid := false.B; io.stream.request.bits := 0.U.asTypeOf(io.stream.request.bits)
  io.stream.response.ready := true.B
  io.memoryLive := window.io.memoryLive; io.storeLive := window.io.storeLive; io.fatal := false.B; io.occupancy := window.io.occupancy
  io.drained := window.io.drained && memory.io.drained && access.io.drained
  io.scalarMemoryBlocked := memory.io.scalarMemoryBlocked || window.io.serialActive
}
