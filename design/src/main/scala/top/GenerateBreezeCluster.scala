package flow.top

import _root_.circt.stage.ChiselStage
import flow.config.BreezeBuildOptions

/** AXI SoC generator; optional debug is passive and enables retirement traces. */
object GenerateBreezeCluster extends App {
    private val options = BreezeBuildOptions.parse(args.toSeq)
    private val cfg = options.config
    private val debug = options.debug
    private val enableTandem = options.enableTandem
    private val hangThreshold = sys.env.getOrElse("BREEZE_HANG_CYCLES", "10000000").toInt
    require(hangThreshold > 0, "BREEZE_HANG_CYCLES must be positive")
    private val inputs = BreezeBuildArtifacts.prepare(os.pwd)
    private val targetDir = BreezeBuildArtifacts.targetDirectory(os.pwd, options)
    // Keep older generated artifacts intact, but build this manifest only
    // from a fresh emission. Directory scans must not re-admit deleted RTL.
    private val emittedDir = targetDir / "emitted" / java.util.UUID.randomUUID().toString
    ChiselStage.emitSystemVerilogFile(
        new BreezeClusterAxi(cfg, enableTandem, debug, hangThreshold),
        Array("--target-dir", emittedDir.toString, "--full-stacktrace"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable"))
    BreezeBuildArtifacts.publish(inputs, targetDir, emittedDir, options, hangThreshold)
}
