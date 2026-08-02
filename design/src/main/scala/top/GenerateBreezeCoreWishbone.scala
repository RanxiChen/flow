package flow.top

import _root_.circt.stage.ChiselStage
import flow.config.BreezeCoreConfigs

/** The single canonical elaboration entry point for the SoC-facing core RTL.
  *
  * Run from the design/ sbt project with:
  *
  *   sbt elaborate
  *
  * The stable output is the split SystemVerilog set named by
  * build/rtl/filelist.f. BreezeCoreWishbone.sv remains the top-level module.
  * LiteX integration consumes the manifest rather than guessing submodules.
  */
object GenerateBreezeCoreWishbone extends App {
  ChiselStage.emitSystemVerilogFile(
    new BreezeCoreWishbone(BreezeCoreConfigs.baseline()),
    Array("--target-dir", "build/rtl"),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}
