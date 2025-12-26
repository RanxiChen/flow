package hello

import chisel3._
import chisel3.util._
import chisel3.util.experimental._
class tinymem(val path:String = "" ) extends Module {
    val io = IO(new Bundle{
        val addr = Input(UInt(7.W))
        val data = Output(UInt(32.W))
    })
    //val mem = SyncReadMem(128, UInt(32.W))
    //loadMemoryFromFileInline(mem, "tinymem_init.hex")
    //io.data := mem.read(io.addr)
    val mem = Reg(Vec(128, UInt(64.W)))
    io.data := mem(io.addr)(31,0)
    if(path != ""){
        println(s"loading memory init file from $path")
        val content = os.read.lines(os.Path(path))
        for(item <- content){
            println(s"mem init line: $item")
        }
        when(reset.asBool){
            for((item, idx) <- content.zipWithIndex){
                mem(idx) := ("h" + item).U
            }
        }
    }
}

import _root_.circt.stage.ChiselStage
object GenerateHellMemFile extends App {
    ChiselStage.emitSystemVerilogFile(
        new tinymem(),
        Array("--target-dir", "build"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )
}

object HelloMemMain extends App {
    //read file contents
    if(args.length < 1){
        println("Usage: HelloMemMain <mem_init_file_path>")
        System.exit(1)
    }
    val path = args(0)
    ChiselStage.emitSystemVerilogFile(
        new tinymem(path),
        Array("--target-dir", "build"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )
}
