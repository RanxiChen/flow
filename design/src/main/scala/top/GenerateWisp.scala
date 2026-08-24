package flow.top

import _root_.circt.stage.ChiselStage
import flow.accelerator.matrix.MatrixEngine
import flow.wisp.WispCore

/** Emit the production Wisp v0 RTL consumed by LiteX. */
object GenerateWisp extends App {
  private val targetDir = os.pwd / "build" / "rtl" / "wisp"
  ChiselStage.emitSystemVerilogFile(
    new WispCore(resetVector = BigInt("10000000", 16), withTrace = false),
    Array("--target-dir", targetDir.toString),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  val files = os.list(targetDir).filter(_.ext == "sv").map(_.last).sorted
  require(files.nonEmpty, s"no SystemVerilog files emitted into $targetDir")
  os.write.over(targetDir / "filelist.f", files.mkString("", "\n", "\n"))
  os.write.over(targetDir / "wisp-profile.txt",
    "isa=rv64i_zicsr_zifencei\nabi=lp64\nbus_data_width=32\nreset_vector=0x10000000\ntrace=false\n")
}

/** Emit a Wisp top with an observable 4x4 INT8 systolic array for Quartus area
  * measurement.  The array is intentionally not yet connected to LiteX CSRs
  * or scratchpads.
  */
object GenerateWispMatrixArea extends App {
  private val targetDir = os.pwd / "build" / "rtl" / "wisp-matrix-area"
  ChiselStage.emitSystemVerilogFile(
    new WispCore(
      resetVector = BigInt("10000000", 16),
      withTrace = false,
      withMatrixAreaProbe = true),
    Array("--target-dir", targetDir.toString),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  val files = os.list(targetDir).filter(_.ext == "sv").map(_.last).sorted
  require(files.nonEmpty, s"no SystemVerilog files emitted into $targetDir")
  os.write.over(targetDir / "filelist.f", files.mkString("", "\n", "\n"))
  os.write.over(targetDir / "wisp-profile.txt",
    "isa=rv64i_zicsr_zifencei\nabi=lp64\nbus_data_width=32\n" +
      "reset_vector=0x10000000\ntrace=false\nmatrix_area_probe=4x4_int8_int32\n")
}

/** Emit the complete single-command matrix engine used by the LiteX wrapper. */
object GenerateMatrixEngine extends App {
  private val targetDir = os.pwd / "build" / "rtl" / "matrix-engine-m9k"
  ChiselStage.emitSystemVerilogFile(
    new MatrixEngine(),
    Array("--target-dir", targetDir.toString),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  val files = os.list(targetDir).filter(_.ext == "sv").map(_.last).sorted
  require(files.nonEmpty, s"no SystemVerilog files emitted into $targetDir")
  os.write.over(targetDir / "filelist.f", files.mkString("", "\n", "\n"))
  os.write.over(targetDir / "matrix-profile.txt",
    "array=4x4\ninput=sint8\naccumulator=sint32\nspm_depth=256\n" +
      "command_slots=1\ninterrupt=false\n")
}
