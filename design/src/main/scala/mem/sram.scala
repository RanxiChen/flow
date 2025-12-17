package mem

import chisel3._
import chisel3.util._
import chisel3.util.experimental._
class SRAMMem(val depth: Int =128 ) extends Module {
    val io = IO(new Bundle{
        val imem_port = new Bundle {
            val req_addr = Input(UInt(64.W))
            val resp_addr = Output(UInt(64.W))
            val data = Output(UInt(32.W))
            val can_next = Output(Bool())
        }
        val dmem_port = new Bundle {
            val req_addr = Input(UInt(64.W))
            val resp_addr = Output(UInt(64.W))
            val mem_valid = Input(Bool())
            val wt_rd = Input(Bool()) // 1 for write, 0 for read
            val wdata = Input(UInt(64.W))
            val wmask = Input(UInt(8.W))
            val rdata = Output(UInt(64.W))
            val can_next = Output(Bool())
        }
    })
    //initial IO
    io.imem_port.resp_addr := 0.U
    io.imem_port.data := 0.U
    io.imem_port.can_next := false.B
    io.dmem_port.resp_addr := 0.U
    io.dmem_port.rdata := 0.U
    io.dmem_port.can_next := false.B
    val mem = SyncReadMem(depth, UInt(64.W))
    loadMemoryFromFileInline(mem, "mem.hex")
    //always can response in next cycle
    //imem part
    def fetch_resp(req_addr: UInt): (UInt, UInt) ={
        val resp_addr = RegNext(req_addr)
        val imem_content = mem.read(req_addr >> 3.U)
        val head_tail_immem = Wire(Bool())
        head_tail_immem := (resp_addr >> 2.U)(0)
        val data = Mux(!head_tail_immem, imem_content(31,0), imem_content(63,32))
        (resp_addr, data)
    }
    def xlen_read(req_addr: UInt):(UInt,UInt) ={
        val mem_addr = RegNext(req_addr >> 3.U)
        val mem_content = mem.read(req_addr >> 3.U)
        (mem_addr, mem_content)
    }
    def xlen_write(req_addr: UInt, wdata: UInt, wmask: UInt): Unit ={
                
    }
    when(io.dmem_port.mem_valid){

    }.otherwise{
        
    }    
}
import _root_.circt.stage.ChiselStage
object GenerateSRAMMem extends App {
    ChiselStage.emitSystemVerilogFile(
        new SRAMMem(depth=128),
        Array("--target-dir", "build"),
        firtoolOpts = Array(
            "-disable-all-randomization", 
            "-strip-debug-info", 
            "-default-layer-specialization=enable"
        )
    )
}