package hello

import chisel3._
import chisel3.util._
import chisel3.util.experimental._
import core.core_in_order
class tinymem extends Module {
    val io = IO(new Bundle{
        val addr = Input(UInt(7.W))
        val data = Output(UInt(32.W))
    })
    val mem = SyncReadMem(128, UInt(32.W))
    loadMemoryFromFileInline(mem, "tinymem_init.hex")
    io.data := mem.read(io.addr)
}

import _root_.circt.stage.ChiselStage
object GenerateHellMemFile extends App {
    ChiselStage.emitSystemVerilogFile(
        new tinymem(),
        Array("--target-dir", "build"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )
}
