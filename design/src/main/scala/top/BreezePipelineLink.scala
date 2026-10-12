package flow.top

import chisel3._
import chisel3.util._
import flow.backend.BreezeBackend
import flow.frontend.BreezeFrontendPipeline
import flow.interface.FrontendPredType

/** Wires only: no legacy frontend, fetch FIFO, skid or translation adapter.
  * Global restart is registered with the committed CSR permission event.
  * EX resolution is accepted directly in its reserved slot, without delay.
  */
object BreezePipelineLink {
  def apply(backend: BreezeBackend, frontend: BreezeFrontendPipeline): Unit = {
    frontend.io.resetPc := backend.io.resetAddr
    frontend.io.context := backend.io.mmuContext
    frontend.io.block := backend.io.translationBlocked
    val restart = RegNext(backend.io.frontendRestart, 0.U.asTypeOf(backend.io.frontendRestart))
    frontend.io.redirect.valid := restart.valid
    frontend.io.redirect.bits := restart.target
    frontend.io.fenceI := restart.cacheFlush
    // Flush translation and cancel its cache query on the same edge. Backend
    // observation remains at WB; no younger ID may issue in this restart gap.
    val sfence = RegNext(backend.io.sfence, 0.U.asTypeOf(backend.io.sfence))
    frontend.io.sfence.valid := sfence.valid
    frontend.io.sfence.rs1Nz := sfence.useVaddr
    frontend.io.sfence.rs2Nz := sfence.useAsid
    frontend.io.sfence.vaddr := sfence.vaddr
    frontend.io.sfence.asid := sfence.asid
    frontend.io.resolve <> backend.io.frontendResolve
    frontend.io.resolutionReserve.get := backend.io.frontendResolutionReserve
    backend.io.frontendResolutionAvailable := frontend.io.resolutionAvailable
    frontend.io.committedBranch := backend.io.committedBranch

    val out = frontend.io.instruction
    val in = backend.io.fetchBuffer
    in.valid := out.valid
    out.ready := in.ready
    in.bits := 0.U.asTypeOf(in.bits)
    in.bits.pc := out.bits.pc
    in.bits.inst := out.bits.instruction
    in.bits.rawInst := out.bits.raw
    in.bits.instLen := out.bits.length
    in.bits.isCompressed := out.bits.compressed
    in.bits.illegalCompressed := out.bits.illegal || (out.bits.compressed && !backend.cfg.enableCompressed.B)
    in.bits.instructionPageFault := out.bits.pageFault
    in.bits.instructionAccessFault := out.bits.accessFault
    in.bits.instructionFaultSecondParcel := out.bits.faultSecondParcel
    in.bits.pred.predType := FrontendPredType(out.bits.kind)
    in.bits.pred.predTaken := out.bits.predictedTaken
    in.bits.pred.predPc := out.bits.predictedTarget
    in.bits.pred.phtIdx := out.bits.phtIndex
    in.bits.pred.historyBefore := out.bits.historyBefore
    in.bits.pred.eventPc := out.bits.eventPc
    in.bits.pred.rasPush := out.bits.rasPush
    in.bits.pred.rasPop := out.bits.rasPop
  }
}
