package flow.rvv.zve

import chisel3._
import chisel3.util._

/** Independent FP datapath shell; the debug port is a test client, not a SoC
  * dependency. Production composition uses the same VRF/engine/services. */
class ZveFpVectorHarness(p: ZveParams, lanes: Int, kind: Int) extends Module {
  val io=IO(new Bundle {
    val issue=Flipped(Decoupled(new ZveIssue(p)))
    val debug=Flipped(new ZveVrfPort(p))
    val completion=Decoupled(new ZveCompletion(p))
    val drained=Output(Bool())
  })
  val access=Module(new ZveVrfAccess(p,2))
  val engine=Module(new ZveFpVectorEngine(p,lanes))
  access.io.ports(0) <> io.debug; access.io.ports(1) <> engine.io.vrf
  engine.io.mask := access.io.mask
  engine.io.in.valid := io.issue.valid; engine.io.in.bits := ZveDecode(io.issue.bits,p)
  io.issue.ready := engine.io.in.ready
  io.completion <> engine.io.complete
  val services=Seq.fill(lanes)(Module(new ZveFpnewService(kind)))
  services.zipWithIndex.foreach { case(s,l) => s.io.service <> engine.io.fp(l) }
  io.drained := engine.io.drained && access.io.drained && services.map(_.io.drained).reduce(_ && _)
}
