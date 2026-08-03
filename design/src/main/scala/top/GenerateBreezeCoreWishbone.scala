package flow.top

import _root_.circt.stage.ChiselStage
import flow.config.BreezeCoreConfigs

/** The single canonical elaboration entry point for the SoC-facing core RTL.
  *
  * Run from the design/ sbt project with:
  *
  *   sbt elaborate
  *   sbt "runMain flow.top.GenerateBreezeCoreWishbone gshare"
  *
  * The stable output is the split SystemVerilog set named by
  * build/rtl/<preset>/filelist.f. BreezeCoreWishbone.sv remains the top-level
  * module. LiteX integration consumes the selected manifest rather than
  * guessing submodules.
  */
object GenerateBreezeCoreWishbone extends App {
  require(args.length <= 1, "usage: GenerateBreezeCoreWishbone [baseline|gshare]")

  private val corePreset = args.headOption.getOrElse("baseline")
  private val coreConfig = corePreset match {
    case "baseline" => BreezeCoreConfigs.baseline(enableTandem = true)
    case "gshare"   => BreezeCoreConfigs.gshare(enableTandem = true)
    case other =>
      throw new IllegalArgumentException(
        s"unsupported core preset: $other (expected baseline or gshare)"
      )
  }
  private val targetDir = os.pwd / "build" / "rtl" / corePreset

  println(s"[BreezeCore RTL] core_preset=$corePreset target_dir=$targetDir")
  ChiselStage.emitSystemVerilogFile(
    // Keep the architectural retirement trace on the canonical SoC top. It is
    // consumed by simulation bring-up monitors and does not affect execution.
    new BreezeCoreWishbone(coreConfig),
    Array("--target-dir", targetDir.toString),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
  os.write.over(
    targetDir / "core-preset.txt",
    s"$corePreset\n",
    createFolders = true
  )
}
