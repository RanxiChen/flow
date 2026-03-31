package core

import chisel3._
import chisel3.util._

import chisel3.util.experimental.loadMemoryFromFileInline
import chisel3.ActualDirection.Default
import top.FEMux
import top.GlobalSilent
import top.tcm
import mem.ITCM
import _root_.flow.interface.ICacheIO
import cache.ICache
import _root_.circt.stage.ChiselStage
import _root_.flow.interface.NativeMemIO
class InstPack extends Bundle {
    val data = UInt(32.W)
    val pc   = UInt(64.W)
    val instruction_address_misaligned = Bool()
    val instruction_access_fault = Bool()
}
/**
  * Control signals from backend to frontend
  */
class FE_BE_Bundle extends Bundle {
    val flush = Bool() //TODO: now just use pc_misfetch to clear pipeline, reserved for fence
    val pc_misfetch = Bool()
    val pc_redir = UInt(64.W)
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
// stand in fe, used by simple frontend
class IMEM_IO extends Bundle {
    val req_valid = Output(Bool())
    val req_addr = Output(UInt(64.W))
    val resp_data = Input(UInt(32.W)) // instruction data
    val resp_ack = Input(Bool()) // response valid
} 

/**
  * A simplified frontend that only fetches instructions continuously from memory
  * no cache, no pipeline, just dispatch request to bus interface and get response
  * impl by fsm, since directly connect to bus interface and backend will advance just when
  * there is data in inst buffer
  */
class SimpleFrontend() extends Module {
    val io = IO(new Bundle {
        val reset_addr = Input(UInt(64.W))
        val fetch = Decoupled(new InstPack)
        val memreq = new IMEM_IO
        val be_ctrl = Flipped(new FE_BE_Bundle)
    })
    //initial IO
    io.memreq.req_addr := 0.U
    io.memreq.req_valid := false.B
    io.fetch.valid := false.B
    io.fetch.bits := 0.U.asTypeOf(new InstPack)
    val dump = false
    val BOOT_ADDR = io.reset_addr
    val redir_pc = RegInit(BOOT_ADDR)
    val should_redir = RegInit(false.B)
    val pc = RegInit(BOOT_ADDR)

    object State extends ChiselEnum {
        val idle, req = Value
    }
    import State._
    val state = RegInit(idle)

    when(state === idle){
        when(io.be_ctrl.pc_misfetch){
            pc := io.be_ctrl.pc_redir
        }
        when(io.fetch.ready){
            // has space to accept instruction
            state := req
        }
    }.elsewhen(state === req){
        //dispatch request
        io.memreq.req_addr := pc
        io.memreq.req_valid := true.B
        when(io.be_ctrl.pc_misfetch){
            should_redir := true.B
            redir_pc := io.be_ctrl.pc_redir
        }
        when(io.memreq.resp_ack){
            state := idle
            pc := Mux(should_redir, redir_pc, pc + 4.U)
            when(should_redir){
                should_redir := false.B
            }
        }
    }
    // dump to inst buf
    io.fetch.valid := (state === req) && io.memreq.resp_ack
    io.fetch.bits.data := io.memreq.resp_data
    io.fetch.bits.pc := pc
    io.fetch.bits.instruction_address_misaligned := pc(1,0) =/= 0.U
    io.fetch.bits.instruction_access_fault := false.B // no access fault in this simple frontend
    if(dump){
        printf(cf"[FE] state=${state}, pc=0x${pc}%0x, req_valid=${io.memreq.req_valid} ,resp_valid=${io.memreq.resp_ack} redir=${io.be_ctrl.pc_misfetch}")
        when(io.memreq.resp_ack){
            printf(cf", inst=0x${io.memreq.resp_data}%0x")
        }
        printf(cf"\n")
    }
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
        val ctrl = Flipped(new FE_BE_Bundle)
    })
    import top.DefaultConfig._
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
        when(io.fetch.fire){
            printf(cf"fire\n")
        }.otherwise{
            printf(cf"stall\n")
        }
    }
}

/**
  * 带 I-Cache 的三级流水线前端
  *
  * 流水线结构：
  *   S0: PC 生成，发送 cache 请求，检测 PC 对齐异常
  *   S1: 等待 cache tag check，传递 PC 和异常标志
  *   S2: 接收 cache 数据，打包成 InstPack 输出
  *
  * 握手协议：
  *   - imem.req: S0 发送地址请求给 cache
  *   - imem.resp: S2 接收 cache 返回的指令数据
  *   - instpack: S2 输出指令包给后端
  *   - be_ctrl: 后端控制信号（分支跳转）
  */
class gustFrontend(val dumplog: Boolean = false) extends Module {
    val io = IO(new Bundle{
        val reset_addr = Input(UInt(64.W))
        val imem = Flipped(new ICacheIO)
        val instpack = Decoupled(new InstPack)
        val be_ctrl = Flipped(new FE_BE_Bundle)
    })

    // ========== PC 寄存器 ==========
    val pc = RegInit(io.reset_addr)

    // ========== 流水线级间寄存器 ==========

    // S0 → S1
    val s1_valid = RegInit(false.B)
    val s1_pc = RegInit(0.U(64.W))
    val s1_misaligned = RegInit(false.B)

    // S1 → S2
    val s2_valid = RegInit(false.B)
    val s2_pc = RegInit(0.U(64.W))
    val s2_misaligned = RegInit(false.B)

    // ========== S0 阶段：PC 生成和异常检测 ==========

    val s0_valid = WireDefault(true.B)
    val s0_misaligned = pc(1, 0) =/= 0.U

    // ========== 背压传播（Stall 逻辑）==========

    // S2 阻塞：后端不能接收新指令
    val stall_s2 = s2_valid && !io.instpack.ready

    // S1 阻塞：S2 阻塞
    val stall_s1 = stall_s2

    // S0 阻塞：S1 阻塞 或 cache 不 ready
    val stall_s0 = stall_s1 || !io.imem.req.ready

    // ========== 分支跳转和流水线冲刷 ==========

    val flush_pipeline = io.be_ctrl.pc_misfetch

    // ========== S0 → Cache 请求握手 ==========

    io.imem.req.valid := s0_valid && !stall_s0
    io.imem.req.bits.addr := pc

    val s0_fire = io.imem.req.fire

    // ========== S2 阶段：接收 cache 数据 ==========

    io.imem.resp.ready := !stall_s2

    val s2_has_data = io.imem.resp.fire

    // ========== S2 → InstPack 输出 ==========

    io.instpack.valid := s2_valid && io.imem.resp.valid
    io.instpack.bits.data := io.imem.resp.bits.data
    io.instpack.bits.pc := s2_pc
    io.instpack.bits.instruction_address_misaligned := s2_misaligned
    io.instpack.bits.instruction_access_fault := false.B

    val s2_output_fire = io.instpack.fire

    // ========== PC 更新逻辑 ==========

    when(flush_pipeline) {
        pc := io.be_ctrl.pc_redir
    }.elsewhen(!stall_s0 && s0_fire) {
        pc := pc + 4.U
    }

    // ========== S0 → S1 流水线前进 ==========

    when(flush_pipeline) {
        s1_valid := false.B
        s1_pc := 0.U
        s1_misaligned := false.B
    }.elsewhen(!stall_s0 && s0_fire) {
        s1_valid := true.B
        s1_pc := pc
        s1_misaligned := s0_misaligned
    }

    // ========== S1 → S2 流水线前进 ==========

    when(flush_pipeline) {
        s2_valid := false.B
        s2_pc := 0.U
        s2_misaligned := false.B
    }.elsewhen(!stall_s1 && s1_valid) {
        s2_valid := true.B
        s2_pc := s1_pc
        s2_misaligned := s1_misaligned
    }.elsewhen(s2_output_fire) {
        s2_valid := false.B
    }

    // ========== 调试输出 ==========

    if(dumplog) {
        printf(cf"[gustFe] ")
        printf(cf"S0(pc=0x${pc}%x,v=${s0_valid},ma=${s0_misaligned}) ")
        printf(cf"S1(pc=0x${s1_pc}%x,v=${s1_valid}) ")
        printf(cf"S2(pc=0x${s2_pc}%x,v=${s2_valid}) ")
        when(s0_fire) { printf(cf"REQ ") }
        when(s2_has_data) { printf(cf"RESP ") }
        when(io.instpack.fire) {
            printf(cf"OUT(inst=0x${io.instpack.bits.data}%x) ")
        }
        when(flush_pipeline) {
            printf(cf"FLUSH(tgt=0x${io.be_ctrl.pc_redir}%x) ")
        }
        when(stall_s0) { printf(cf"STALL ") }
        printf(cf"\n")
    }
}
/**
  * 带 I-Cache 的三级流水线前端
  * 配有完整的测试代码环境
  *
  */
class GustEngine(val dumplog: Boolean = false) extends Module {
    val io = IO(new Bundle{
        val reset_addr = Input(UInt(64.W))
        val instpack = Decoupled(new InstPack)
        val be_ctrl = Flipped(new FE_BE_Bundle)
        val mem = new NativeMemIO
    })
    val frontend = Module(new gustFrontend(dumplog))
    val icache = Module(new cache.ICache(dumplog))
    // 连接前端和 I-Cache
    frontend.io.reset_addr := io.reset_addr
    frontend.io.be_ctrl := io.be_ctrl
    frontend.io.instpack <> io.instpack
    frontend.io.imem <> icache.io.dst
    // 连接 I-Cache 和内存接口
    icache.io.commer <> io.mem
}

object FireGustFrontend extends App {
    println("Generating the FireGustEngine hardware")
    ChiselStage.emitSystemVerilogFile(
        new GustEngine(),
        Array("--target-dir", "../sim/GustEngine/rtl"),
        firtoolOpts = Array(
            "-disable-all-randomization",
            "-strip-debug-info",
            "-default-layer-specialization=enable"
        )
    )
}
