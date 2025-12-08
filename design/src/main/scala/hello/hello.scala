package hello
import chisel3._
import chisel3.util._

class helloModule extends Module {
    val io = IO(new Bundle{
        val out = Output(UInt(32.W))
    })
    val timer = RegInit(0.U(32.W))
    timer := timer + 1.U
    io.out := timer
}

object Start extends App {
    println("Hello, world!")
}

import _root_.circt.stage.ChiselStage
object GenerateHello extends App {
    ChiselStage.emitSystemVerilogFile(
        new helloModule,
        Array("--target-dir", "build"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )
}