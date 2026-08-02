package flow.top

import _root_.circt.stage.ChiselStage
import flow.config.BreezeCoreConfigs

/** The single canonical elaboration entry point for the SoC-facing core RTL.
  *
  * Run from the design/ sbt project with:
  *
  *   sbt elaborate
  *
  * The stable output is build/rtl/BreezeCoreWishbone.sv. LiteX integration is
  * deliberately outside this App and will be designed separately.
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
