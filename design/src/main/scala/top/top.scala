package top

import chisel3._
import chisel3.util._
import core._
import mem._

class flow_top extends Module {
    val io = IO(new Bundle{
        val error = Output(Bool())
    })
    io.error := false.B
    val core = Module(new core_in_order())
    val mem = Module(new regarryMem(128))
    //connect
    core.io.itcm <> mem.io.imem_port
    core.io.dtcm <> mem.io.dmem_port
}

import _root_.circt.stage.ChiselStage
object GenerateTop extends App {
    ChiselStage.emitSystemVerilogFile(
        new flow_top,
        Array("--target-dir", "build"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )
}