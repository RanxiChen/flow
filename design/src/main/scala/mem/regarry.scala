package mem
import chisel3._
import chisel3.util._
import core.{IMemPort,DMemPort}
class MemIO extends Bundle{
    val imem_port = Flipped(new IMemPort)
    val dmem_port = Flipped(new DMemPort)
}
abstract class MemModule extends Module {
    val io = IO(new MemIO)
} 

class regarryMem(val depth: Int =128,val path: String = "mem.hex") extends MemModule {
    val mem = Reg(Vec(depth, UInt(64.W)))
    //reset and load value
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
    //imem part
    io.imem_port.resp_addr := io.imem_port.req_addr
    io.imem_port.can_next := true.B
    val fetch_addr = Wire(UInt(64.W))
    fetch_addr := io.imem_port.req_addr >> 3.U
    val head_tail_immem = Wire(Bool())
    head_tail_immem := (io.imem_port.req_addr >> 2.U)(0)
    io.imem_port.data := Mux(!head_tail_immem, mem(fetch_addr)(31,0), mem(fetch_addr)(63,32))
    //mem part
    io.dmem_port.resp_addr := io.dmem_port.req_addr
    io.dmem_port.can_next := true.B
    io.dmem_port.rdata := 0.U
    val _buf = Wire(Vec(8,UInt(8.W)))
    _buf := (0.U).asTypeOf(Vec(8,UInt(8.W)))
    when(io.dmem_port.mem_valid){
        when(io.dmem_port.wt_rd){
            //write data to mem
            for(i <- 0 to 7 ){
                _buf(i) := Mux(
                    io.dmem_port.wmask(i),
                    io.dmem_port.wdata(8*i+7,8*i),
                    mem(io.dmem_port.req_addr >> 4.U)(8*i+7,8*i)
                )   
            }
            mem(io.dmem_port.req_addr) := _buf.asUInt
        }.otherwise{
            //read data from mem
            val tail_head_dmem = io.dmem_port.req_addr(2)
            val head_dmem = Wire(UInt(32.W))
            val tail_dmem = Wire(UInt(32.W))
            head_dmem :=  mem(io.dmem_port.req_addr >> 3.U )(31,0)
            tail_dmem :=  mem(io.dmem_port.req_addr >> 3.U )(63,32)
            io.dmem_port.rdata := Mux(!tail_head_dmem, head_dmem, tail_dmem)
        }
    }
}