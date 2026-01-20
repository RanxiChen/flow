package core

import chisel3._
import chisel3.util._
import top.DefaultConfig._
import top.DefaultConfig._
import chisel3.util.experimental.loadMemoryFromFileInline
import chisel3.ActualDirection.Default
import top.FEMux
import top.GlobalSilent
import top.tcm
import mem.ITCM
class InstPack extends Bundle {
    val data = UInt(32.W)
    val pc   = UInt(64.W)
    val xcpt = Bool()
    val instruction_address_misaligned = Bool()
    val instruction_access_fault = Bool()
}

class FrontendCtrl extends Bundle {
    val flush = Bool() //TODO: now just use pc_misfetch to clear pipeline
    val pc_misfetch = Bool()
    val pc_redir = UInt(64.W)
    val weak_be = Bool() // Back-End is busy, not want new instructions
}

class IMemPort extends Bundle {
    val req_addr = Output(UInt(64.W))
    val resp_addr = Input(UInt(64.W))
    val data = Input(UInt(32.W))
    val can_next = Input(Bool())
}
class MemReq extends Bundle {
    val addr = UInt(64.W)
}
class MemResp extends Bundle {
    val addr = UInt(64.W)
    val data = UInt(32.W)
}
/**
  * A simplified frontend that only fetches instructions continuously from memory
  * no cache, no pipeline, just fetch from bus
  */
class SimpleFrontend(useFASE: Boolean=false) extends Module {
    val io = IO(new Bundle {
        val fetch = Decoupled(new InstPack)
        val memreq = new IMemPort
        val ctrl = Flipped(new FrontendCtrl)
        val ext_drain = if(useFASE)Some(Input(Bool())) else None
        val ext_frozen = if(useFASE)Some(Output(Bool())) else None
    })
    val fetch_wait_resp = WireDefault(false.B) // indicate waiting for memory response
    val pc = RegInit(BOOT_ADDR.U(64.W))
    val npc = MuxCase(pc + 4.U, IndexedSeq(
        fetch_wait_resp -> pc, // wait for response, do not update pc
        io.ctrl.pc_misfetch -> io.ctrl.pc_redir,
        io.ctrl.weak_be -> pc // backend is busy, do not update pc
        )
    )
    pc := npc
    // request memory
    io.memreq.req_addr := pc
    fetch_wait_resp := !io.memreq.can_next
    // puch instruction to backend
    val inst_pack = Wire(new InstPack)
    inst_pack.pc := io.memreq.resp_addr
    inst_pack.data := io.memreq.data
    inst_pack.xcpt := false.B // TODO: handle exception
    inst_pack.instruction_address_misaligned := false.B
    inst_pack.instruction_access_fault := false.B
    io.fetch.valid := !fetch_wait_resp
    io.fetch.bits := inst_pack
    
}
/**
  * This module is reserved for pipelined cache access, 
  * but it is currently unavailable 
  * because the frontend acutally used is simplified for now 
  * and will only continuously fetch instructions from the bus.
  */
class Frontend() extends Module {
    val io = IO(new Bundle {
        val fetch = Decoupled(new InstPack)
        val memreq = new IMemPort
        val ctrl = Flipped(new FrontendCtrl)
    })
    // at this stage, we will not get data from memory
    //initial IO
    io.memreq <> DontCare
    io.memreq.req_addr := 0.U
    //frontend pipeline
    //start, pc reg
    //flush_pc : redirect pc when misfetch
    val flush_pc = WireDefault(false.B)
    flush_pc := io.ctrl.pc_misfetch
    val pc = RegInit(ADDR_XXX.U(64.W))
    when(flush_pc){
        pc := io.ctrl.pc_redir
    }.otherwise{
        pc := pc + 4.U
    }
    //fetch stage
    /**
      * s0: use pc, dispatch request to moudle
      * check whether pc is aligned, since misaligned access will be rised here
      * drive each mem module
      * @param pc: current pc
      * @return s0_instruction_address_misaligned: whether pc is misaligned
      * @return source_mux: which source to fetch instruction from
      */
    val addr_valid = Wire(Bool())
    addr_valid := pc(1,0) === 0.U
    // misaligned address exception will rise there
    val s0_instruction_address_misaligned = pc(1,0) =/= 0.U
    // dispatch request
    val source_mux = Wire(UInt(FEMux.width.W))
    //TODO:read address map from config
    val cf = tcm(BigInt(0x00000000),BigInt("00FFFFFF",16))
    when( cf.start_addr.U <= pc && pc <= cf.end_addr.U){
        source_mux := FEMux.itcm.U
    }.otherwise{
        //default branch, including xcpt case
        source_mux := FEMux.nop.U
    }
    val itcm = Module(new ITCM(cf))
    itcm.io.fetch_port.req_addr := pc
    val itcm_data = itcm.io.fetch_port.resp_data
    //s0,s1 boundary reg
    val s1_pc = RegInit(ADDR_XXX.U(64.W))
    val s1_instruction_address_misaligned = RegInit(false.B)
    val s1_source_mux = RegInit(FEMux.sleep.U(FEMux.width.W))
    //ctrl signal 
    val s0_s1_fire = WireDefault(true.B)
    // s0 to s1
    val flush_s0_s1 = WireDefault(false.B)
    flush_s0_s1 := io.ctrl.pc_misfetch // now flush caused by pc misfetch
    when(flush_s0_s1){
        //discard all s0 info
        s1_pc := ADDR_XXX.U(64.W)
        s1_instruction_address_misaligned := false.B
        s1_source_mux := FEMux.sleep.U(FEMux.width.W)
    }.elsewhen(s0_s1_fire){
        s1_pc := pc
        s1_instruction_address_misaligned := s0_instruction_address_misaligned
        s1_source_mux := source_mux
    }
    itcm.io.fetch_port.flush := flush_s0_s1
    /**
      * s1: get data from memory module
      * since now, just sram
      * and merge data to instpack
      */
    val s1_data = Wire(UInt(32.W))
    val s1_data_valid = Wire(Bool())
    val mis_access_fault = Wire(Bool())
    val flush_s1 = WireDefault(false.B) // discard s1 data
    when(flush_s1){
        //force invalid
        s1_data := 0.U
        s1_data_valid := false.B
        mis_access_fault := false.B
    }.elsewhen(s1_source_mux === FEMux.itcm.U){
        s1_data := itcm_data
        s1_data_valid := true.B
        mis_access_fault := false.B
    }.elsewhen(s1_source_mux === FEMux.nop.U){
        s1_data := 0.U //nop
        s1_data_valid := true.B
        mis_access_fault := true.B
    }.elsewhen(s1_source_mux === FEMux.sleep.U){
        //sleep, after reset or flush
        s1_data := 0.U //nop, an invalid instruction
        s1_data_valid := false.B
        mis_access_fault := false.B
    }.otherwise{
        s1_data := 0.U //nop
        s1_data_valid := false.B
        mis_access_fault := false.B
    }
    //instpack handshake
    io.fetch.valid := s1_data_valid
    io.fetch.bits.data := s1_data
    io.fetch.bits.pc := s1_pc
    io.fetch.bits.xcpt := mis_access_fault || s1_instruction_address_misaligned
    io.fetch.bits.instruction_address_misaligned := s1_instruction_address_misaligned
    io.fetch.bits.instruction_access_fault := mis_access_fault 
    //dump log
    if(!GlobalSilent.silent){
        printf(cf"[Frontend]")
        printf(cf"[s0]")
        printf(cf"pc=0x${pc}%0x,")
        printf(cf"addr_mux=${source_mux}%d,")
        printf(cf"[s1]")
        printf(cf"inst=0x${s1_data}%0x,pc=0x${s1_pc}%0x,")
        printf(cf"xcpt=${io.fetch.bits.xcpt}%d,")
        when(io.fetch.fire){
            printf(cf"fire\n")
        }.otherwise{
            printf(cf"stall\n")
        }
    }
}