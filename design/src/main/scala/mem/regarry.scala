package mem
import chisel3._
import chisel3.util._

class regarryMem(val depth: Int =128 ) extends Module {
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
    val mem = Reg(Vec(depth, UInt(64.W)))
    //reset and load value
    when(reset.asBool){
        mem(0) := "h00678813_00100793".U
        mem(1) := "h40f80433_0107f8b3".U
        mem(2) := "h0057f313_10204293".U
        mem(3) := "h00500433_0027d393".U
        mem(4) := "h00402103_00002083".U//load x1 from 0x0,x2 from 0x4
        mem(5) := "h00002083_00002023".U//store 0 to addr0,and read to x1
        mem(6) := "h00000013_0ff11073".U // write x2 to printer and nop
        mem(7) := "h00000013_0ff8d073".U // write 0x3 to printer and nop
        mem(8) := "h00000013_0ff011f3".U // write printer to x3 and nop
        mem(9) := "h00000013_30101273".U // read misa
        mem(10):= "h00000013_8ff21073".U // write x4 to printer        
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