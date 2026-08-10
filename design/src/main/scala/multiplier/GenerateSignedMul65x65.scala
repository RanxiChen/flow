package flow.multiplier

import _root_.circt.stage.ChiselStage

/** Generates SystemVerilog for the signed 65×65 Booth-Dadda multiplier. */
object GenerateSignedMul65x65 extends App {
  val targetDir = os.pwd / "build" / "multiplier"
  os.makeDir.all(targetDir)

  ChiselStage.emitSystemVerilogFile(
    new SignedMul65x65,
    Array("--target-dir", targetDir.toString),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )

  println(s"[GenerateSignedMul65x65] SystemVerilog written to $targetDir")
}
