package mem

import chisel3._
import chisel3.util._
import chisel3.experimental._
import chisel3.util.experimental.loadMemoryFromFileInline
class mem_req extends Bundle{
    val req_addr = UInt(64.W)
    val wt_rd = Bool() // 1 for write, 0 for read
    val wdata = UInt(64.W)
}
class mem_resp extends Bundle{
    val resp_addr = UInt(64.W)
    val rdata = UInt(64.W)
    val sucess = Bool() // false if error occurs
}
/**
  * The state machine handles requests issued by the CPU 
  * and generates read and write requests to memory.
  * When both fetch and memory operations need to be handled simultaneously, 
  * write requests are given priority: the memory is read first, 
  * and then the write is performed.
  * If fetch and memory read requests need to be handled, 
  * they are processed alternately.
  */
class SRAMMemCtrl extends Module {
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
        val sram = new Bundle {
            val mem_req = DecoupledIO(new mem_req)
            val mem_resp = Flipped(DecoupledIO(new mem_resp))
        }
    })
    //default output
    io.imem_port.can_next := false.B
    io.imem_port.resp_addr := 0.U
    io.imem_port.data := 0.U
    io.dmem_port.can_next := false.B
    io.dmem_port.resp_addr := 0.U
    io.dmem_port.rdata := 0.U
    io.sram.mem_req.valid := false.B
    io.sram.mem_req.bits.req_addr := 0.U
    io.sram.mem_req.bits.wt_rd := false.B
    io.sram.mem_req.bits.wdata := 0.U
    io.sram.mem_resp.ready := false.B
    
    // receive cpu request and generate sram request
    // depends on imem , dmem port, decide 2 or 1 sram request
    object MemCtrlState extends ChiselEnum {
        val sIDLE,sREAD1,sREAD2,sWRITE1,sWRITE2,sWRITE3,sWRITE4,sDONE = Value
    }
    object MemOP extends ChiselEnum{
        val puts,reads = Value
    }
    val state = RegInit(MemCtrlState.sIDLE)
    val mem_state = RegInit(MemOP.reads)
    val fetch_pending = WireInit(true.B)
    val addrReg = RegInit(0.U(64.W))
    val wdataReg = RegInit(0.U(64.W))
    val wmaskReg = RegInit(0.U(8.W))
    val fetch_mem = RegInit(false.B) // true for fetch , false for dmem read
    val read_write_op = RegInit(false.B) // true for read , false for write
    val data_write_mem = RegInit(0.U(64.W))
    val mem_rdata = RegInit(0.U(64.W))
    val resp_addr_reg = RegInit(0.U(64.W))
    val write_2_sram_data = RegInit(0.U(64.W))
    def repleaseMask(data:UInt, wdataReg:UInt, wmaskReg:UInt):UInt ={
        val _buf = Wire(Vec(8,UInt(8.W)))
        for(i <- 0 to 7 ){
            _buf(i) := Mux(
                wmaskReg(i),
                wdataReg(8*i+7,8*i),
                data(8*i+7,8*i)
            )   
        }
        _buf.asUInt
    }
    //state convertion
    when(state === MemCtrlState.sIDLE){
        //switch next
        when(io.dmem_port.mem_valid){
            when(io.dmem_port.wt_rd){
                state := MemCtrlState.sWRITE1
                addrReg := io.dmem_port.req_addr
                wdataReg := io.dmem_port.wdata
                wmaskReg := io.dmem_port.wmask
            }.otherwise{
                state := MemCtrlState.sREAD1
                when(fetch_pending){
                    fetch_mem := false.B
                    addrReg := io.dmem_port.req_addr
                }.otherwise{
                    fetch_mem := true.B
                    addrReg := io.imem_port.req_addr
                    fetch_pending := true.B
                }
            }
        }.otherwise{
            //only fetch
            state := MemCtrlState.sREAD1
            fetch_mem := true.B
            addrReg := io.imem_port.req_addr
        }
    }.elsewhen(state === MemCtrlState.sREAD1){
        // puts req
        io.sram.mem_req.valid := true.B
        io.sram.mem_req.bits.req_addr := addrReg(63,3) ## "b000".U(3.W)
        io.sram.mem_req.bits.wt_rd := false.B
        io.sram.mem_req.bits.wdata := 0.U
        when(io.sram.mem_req.fire){
            state := MemCtrlState.sREAD2
        }
    }.elsewhen(state === MemCtrlState.sREAD2){
        // wait resp
        io.sram.mem_resp.ready := true.B
        when(io.sram.mem_resp.fire){
            state := MemCtrlState.sDONE
            read_write_op := true.B
            resp_addr_reg := io.sram.mem_resp.bits.resp_addr
            mem_rdata := io.sram.mem_resp.bits.rdata
        }
    }.elsewhen(state === MemCtrlState.sWRITE1){
        // puts req
        io.sram.mem_req.valid := true.B
        io.sram.mem_req.bits.req_addr := addrReg(63,3) ## "b000".U(3.W)
        io.sram.mem_req.bits.wt_rd := false.B
        io.sram.mem_req.bits.wdata := 0.U
        when(io.sram.mem_req.fire){
            state := MemCtrlState.sWRITE2
        }
    }.elsewhen(state === MemCtrlState.sWRITE2){
        //wait read
        io.sram.mem_resp.ready := true.B
        when(io.sram.mem_resp.fire){
            state := MemCtrlState.sWRITE3
            resp_addr_reg := io.sram.mem_resp.bits.resp_addr
            data_write_mem := repleaseMask(io.sram.mem_resp.bits.rdata, wdataReg, wmaskReg)
        }
    }.elsewhen(state === MemCtrlState.sWRITE3){
        // put write req
        io.sram.mem_req.valid := true.B
        io.sram.mem_req.bits.req_addr := addrReg(63,3) ## "b000".U(3.W)
        io.sram.mem_req.bits.wt_rd := true.B
        io.sram.mem_req.bits.wdata := data_write_mem
        when(io.sram.mem_req.fire){
            state := MemCtrlState.sWRITE4
        }
    }.elsewhen(state === MemCtrlState.sWRITE4){
        // wait write done
        io.sram.mem_resp.ready := true.B
        when(io.sram.mem_resp.fire){
            state := MemCtrlState.sDONE
            read_write_op := false.B
            resp_addr_reg := io.sram.mem_resp.bits.resp_addr
        }
    }.elsewhen(state === MemCtrlState.sDONE){
        state := MemCtrlState.sIDLE
        //dump
        when(read_write_op){
            //finish read
            when(fetch_mem){
                //fetch
                io.imem_port.can_next := true.B
                io.imem_port.resp_addr := addrReg
                io.imem_port.data := Mux(
                    !addrReg(2),
                    mem_rdata(31,0),
                    mem_rdata(63,32)
                )
            }.otherwise{
                //dmem read
                io.dmem_port.can_next := true.B
                io.dmem_port.resp_addr := resp_addr_reg
                io.dmem_port.rdata := mem_rdata
            }
        }.otherwise{
            //finish write
            io.dmem_port.can_next := true.B
            io.dmem_port.resp_addr := resp_addr_reg
        }
    }.otherwise{
        state := MemCtrlState.sIDLE
    }
    val dump=false
    if(dump){
        printf(cf"[SRAM]MemCtrl State : ${state}\n")
    }
}

class SRAMLayer(val depth: Int = 1024,val path: String = "mem.hex") extends Module {
    val io = IO(new Bundle{
        val in = Flipped(DecoupledIO(new mem_req))
        val out = DecoupledIO(new mem_resp)
    })
    val content = SyncReadMem(depth, UInt(64.W)) // SRAM
    loadMemoryFromFileInline(content, path)
    object SRAMCtrlState extends ChiselEnum {
        val sIDLE,sREAD,SREADNOP,sDONE,sWRITE,sFAIL = Value
    }
    val unaccessible = WireDefault(false.B)
    unaccessible := (io.in.bits.req_addr >> 3.U)  >= depth.U || (io.in.bits.req_addr(2,0) =/= 0.U)
    val state = RegInit(SRAMCtrlState.sIDLE)
    val addrReg = RegInit(0.U(64.W))
    val wdataReg = RegInit(0.U(64.W))
    val wt_rdReg = RegInit(false.B)
    val dataReg = RegInit(0.U(64.W))
    dataReg := content.read(addrReg>> 3.U, state === SRAMCtrlState.sREAD) // data will valid when state == nop
    when(state === SRAMCtrlState.sIDLE){
        when(io.in.fire){
            addrReg := io.in.bits.req_addr
            wdataReg := io.in.bits.wdata
            wt_rdReg := io.in.bits.wt_rd
            when(unaccessible){
                // handle error or invalid address
                state := SRAMCtrlState.sFAIL
            }
            when(io.in.bits.wt_rd === false.B){
                state := SRAMCtrlState.sREAD
            }.otherwise{
                state := SRAMCtrlState.sWRITE
            }
        }
    }.elsewhen(state === SRAMCtrlState.sREAD){
        state := SRAMCtrlState.SREADNOP
    }.elsewhen(state === SRAMCtrlState.SREADNOP){
        state := SRAMCtrlState.sDONE
    }.elsewhen(state === SRAMCtrlState.sWRITE){
        content.write(addrReg >> 3.U , wdataReg)
        state := SRAMCtrlState.sDONE
    }.elsewhen(state === SRAMCtrlState.sDONE){
        when(io.out.fire){
            state := SRAMCtrlState.sIDLE
        }
    }.elsewhen(state === SRAMCtrlState.sFAIL){
        when(io.out.fire){
            state := SRAMCtrlState.sIDLE
        }
    }.otherwise{
        state := SRAMCtrlState.sIDLE
    }
    //output logic
    io.in.ready := (state === SRAMCtrlState.sIDLE)
    io.out.valid := (state === SRAMCtrlState.sDONE) || (state === SRAMCtrlState.sFAIL)
    io.out.bits.resp_addr := addrReg
    io.out.bits.rdata := Mux(state === SRAMCtrlState.sFAIL, 0.U, dataReg)
    io.out.bits.sucess := Mux(state === SRAMCtrlState.sFAIL, false.B, true.B)
    val dump=true
    if(dump){
        printf(cf"[SRAM]State : ${state}\n")
        when(state === SRAMCtrlState.sDONE){
            printf(cf"[SRAM]Access Done : Addr = 0x${Hexadecimal(io.out.bits.resp_addr)}, Data = 0x${io.out.bits.rdata}%0x\n")
        }
    }
}

class SRAMMemory(val depth: Int = 1024,val path: String = "mem.hex") extends MemModule {
    val ctrl = Module(new SRAMMemCtrl)
    val sram = Module(new SRAMLayer(depth, path))
    //connect ctrl and sram
    ctrl.io.sram.mem_req <> sram.io.in
    ctrl.io.sram.mem_resp <> sram.io.out
    //connect cpu and ctrl
    //imem part
    ctrl.io.imem_port <> io.imem_port
    //dmem part
    ctrl.io.dmem_port <> io.dmem_port

}

import _root_.circt.stage.ChiselStage
object GenerateSRAMLayer extends App {
    ChiselStage.emitSystemVerilogFile(
        new SRAMLayer(1024),
        Array("--target-dir", "build"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )
}

object GenerateSRAMMemory extends App {
    ChiselStage.emitSystemVerilogFile(
        new SRAMMemory(1024),
        Array("--target-dir", "build"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )
}           