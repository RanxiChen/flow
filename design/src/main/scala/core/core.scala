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

class core extends Module{
    //IO
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
    //Fetch
    
}

import _root_.circt.stage.ChiselStage
object GenerateCoreVerilogFile extends App {
    ChiselStage.emitSystemVerilogFile(
        new RegFile(64),
        Array("--target-dir", "build"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )
}