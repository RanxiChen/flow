package flow.top

import _root_.circt.stage.ChiselStage
import flow.air.AirCore

private object AirEmitter {
  def emit(profile: String, compressed: Boolean): Unit = {
    val targetDir = os.pwd / "build" / "rtl" / profile
    ChiselStage.emitSystemVerilogFile(
      new AirCore(
        resetVector = BigInt("10000000", 16),
        withCompressed = compressed,
        withTrace = false),
      Array("--target-dir", targetDir.toString),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
    val files = os.list(targetDir).filter(_.ext == "sv").map(_.last).sorted
    require(files.nonEmpty, s"no SystemVerilog files emitted into $targetDir")
    os.write.over(targetDir / "filelist.f", files.mkString("", "\n", "\n"))
    os.write.over(targetDir / "air-profile.txt",
      s"isa=${if (compressed) "rv64ic" else "rv64i"}_zicsr_zifencei\n" +
        "abi=lp64\nbus_data_width=32\nphysical_address_width=32\n" +
        "reset_vector=0x10000000\nrf=single_m9k_1r1w\ntrace=false\n")
  }
}

object GenerateAirI extends App {
  AirEmitter.emit("air-i", compressed = false)
}

object GenerateAirIC extends App {
  AirEmitter.emit("air-ic", compressed = true)
}
