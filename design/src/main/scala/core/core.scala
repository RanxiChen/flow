package core

import chisel3._
import chisel3.util._
/**
  * Register File, used to store general purpose registers
  * @param XLEN
  */
class RegFile(XLEN:Int=64) extends Module {
    val io = IO(new Bundle{
        val rs1_addr = Input(UInt(5.W))
        val rs2_addr = Input(UInt(5.W))
        val rd_addr  = Input(UInt(5.W))
        val rd_data  = Input(UInt(XLEN.W))
        val rd_en    = Input(Bool())
        val rs1_data = Output(UInt(XLEN.W))
        val rs2_data = Output(UInt(XLEN.W))
    })
    val content = RegInit(VecInit(Seq.fill(32)(0.U(XLEN.W))))
    io.rs1_data := Mux(io.rs1_addr === 0.U, 0.U, content(io.rs1_addr))
    io.rs2_data := Mux(io.rs2_addr === 0.U, 0.U, content(io.rs2_addr))
    when(io.rd_en && (io.rd_addr =/= 0.U)){
        content(io.rd_addr) := io.rd_data
    }        
}
class ImmGen(XLEN:Int=32) extends Module {
    val io = IO(new Bundle{
        val inst = Input(UInt(32.W))
        val imm    = Output(UInt(XLEN.W))
        val type_sel = Input(UInt(IMM_TYPE.width.W))
    })
    val immi = Fill(XLEN-11,io.inst(31)) ## io.inst(30,20)
    val imms = Fill(XLEN-11,io.inst(31)) ## io.inst(30,25) ## io.inst(11,7)
    val immb = Fill(XLEN-12,io.inst(31)) ## io.inst(7) ## io.inst(30,25) ## io.inst(11,8) ## false.B
    val immj = Fill(XLEN-20,io.inst(31)) ## io.inst(19,12) ## io.inst(20) ## io.inst(30,21) ## false.B
    val immu = Fill(XLEN-32,io.inst(31)) ## io.inst(31,12) ## Fill(12,false.B)
    io.imm := 0.U
    io.imm := MuxLookup(io.type_sel, 0.U)(
        Seq(
            IMM_TYPE.I_Type.U -> immi,
            IMM_TYPE.S_Type.U -> imms,
            IMM_TYPE.B_Type.U -> immb,
            IMM_TYPE.U_Type.U -> immu,
            IMM_TYPE.J_Type.U -> immj
        )
    )
}



class core_in_order extends Module{
    //IO
    val io = IO(new Bundle{
        val itcm = new Bundle {
            val addr = Output(UInt(64.W))
            val data = Input(UInt(32.W))
            val can_next = Input(Bool())
        }
        val dtcm = new Bundle {
            val addr = Output(UInt(64.W))
            val size = Output(UInt(2.W))
            val rdata = Input(UInt(64.W))
            val wt_rd = Output(Bool())
            val wdata = Output(UInt(64.W))
        }        
    })
    //initial IO
    io.dtcm.addr := 0.U
    io.dtcm.size := 0.U
    io.dtcm.wt_rd := false.B
    io.dtcm.wdata := 0.U
    io.itcm.addr := 0.U
    //PC generate
    val pc = RegInit(0.U(64.W))
    val pc_error_predict = WireDefault(false.B)
    val pc_from_exe = WireDefault(0.U(64.W))
    val next_pc = MuxCase(
        pc + 4.U,
        Seq(
            pc_error_predict -> pc_from_exe
        )
    )
    when(io.itcm.can_next){
        pc := next_pc
    }
    //Fetch
    io.itcm.addr := pc 
    class Inst_Buf extends  Bundle {
        val inst = UInt(32.W)
        val pc   = UInt(64.W)
        val buble = Bool()
    }
    val inst_buf = Reg(new Inst_Buf)
    val inst_buf_flush = WireDefault(false.B)
    when(reset.asBool || inst_buf_flush){
        //Reset or Flush
        inst_buf.inst := "h00000013".U //nop
        inst_buf.pc := 0.U
        inst_buf.buble := true.B
    }.elsewhen(io.itcm.can_next){
        //Normal fetch
        inst_buf.inst := io.itcm.data
        inst_buf.pc := pc
        inst_buf.buble := false.B
    }        
}

import _root_.circt.stage.ChiselStage
object GenerateCoreVerilogFile extends App {
    ChiselStage.emitSystemVerilogFile(
        new core_in_order,
        Array("--target-dir", "build"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )
}