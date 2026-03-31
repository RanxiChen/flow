package flow.cache

import chisel3._
import chisel3.util._
import flow.interface.{BreezeCacheReqIO, BreezeCacheRespIO}
import _root_.circt.stage.ChiselStage

class BreezeCache extends Module {
    val io = IO(new Bundle{
        val in = Flipped(Decoupled(new BreezeCacheReqIO))
        val out = Decoupled(new BreezeCacheRespIO)
    })
}

object GenerateBreezeCacheVerilogFile extends App {
    ChiselStage.emitSystemVerilogFile(
        new BreezeCache,
        Array("--target-dir", "build"),
        firtoolOpts = Array(
            "-disable-all-randomization",
            "-strip-debug-info",
            "-default-layer-specialization=enable"
        )
    )
} 
