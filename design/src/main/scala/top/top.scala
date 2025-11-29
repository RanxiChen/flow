package top

import chisel3._
import chisel3.util._
import core._

class flow_top extends Module {
    val io = IO(new Bundle{
        val error = Output(Bool())
    })
    io.error := false.B
}

import _root_.circt.stage.ChiselStage
object GenerateTop extends App {
    ChiselStage.emitSystemVerilogFile(
        new flow_top,
        Array("--target-dir", "build"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )
}