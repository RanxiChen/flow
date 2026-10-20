package flow.top

import circt.stage.ChiselStage
import flow.config.BreezeMemGeometry
import flow.frontend.{BreezeFrontendPipeline, BreezeFrontendTimingHarness}
import org.scalatest.freespec.AnyFreeSpec

/** Structural acceptance for the extracted boundary, alongside behavioral regressions. */
class MmuExtractionElabSpec extends AnyFreeSpec {
  "production frontend exposes translation and contains no MMU or page walker" in {
    val rtl = ChiselStage.emitSystemVerilog(new BreezeFrontendPipeline(),
      firtoolOpts = Array("-disable-all-randomization"))
    assert(rtl.contains("io_itlb_req_valid"))
    assert(!rtl.contains("module Sv39Mmu"))
    assert(!rtl.contains("module Sv39Ptw"))
    assert(!rtl.contains("io_ptwMem"))
    assert(!rtl.contains("io_dtlb"))
  }
  "standalone frontend L2 system connects an external MMU per hart" in {
    ChiselStage.emitSystemVerilog(new BreezeFrontendL2System(BreezeMemGeometry.singleCore),
      firtoolOpts = Array("-disable-all-randomization"))
  }
  "standalone timing fixture still connects translation without changing its boundary" in {
    ChiselStage.emitSystemVerilog(new BreezeFrontendTimingHarness,
      firtoolOpts = Array("-disable-all-randomization"))
  }
}
