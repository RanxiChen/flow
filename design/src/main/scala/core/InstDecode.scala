package core
import chisel3._
import chisel3.util._

class EXE_Ctrl extends Bundle {
    val alu_op = Output(UInt(ALU_OP.width.W))
    val bru_op = Output(UInt(BRU_OP.width.W))
    val sel_alu1 = Output(UInt(SEL_ALU1.width.W))
    val sel_alu2 = Output(UInt(SEL_ALU2.width.W))
    val sel_jpc_i = Output(UInt(SEL_JPC_I.width.W))
    val sel_jpc_o = Output(UInt(SEL_JPC_O.width.W))
    val redir_inst = Output(Bool())
    val bru_inst = Output(Bool())
    val mem_cmd = Output(UInt(MEM_TYPE.width.W))
    val sel_wb = Output(UInt(SEL_WB.width.W))
    val wb_en = Output(Bool())
}



class RV32IDecoder extends Module {
    val io = IO(new Bundle{
        val inst = Input(UInt(32.W))
        val exe_ctrl = Output(new EXE_Ctrl)
    })
    //default values
    io.exe_ctrl.alu_op := ALU_OP.ADD.U
    io.exe_ctrl.bru_op := BRU_OP.BEQ.U
    io.exe_ctrl.sel_alu1 := SEL_ALU1.RS1.U
    io.exe_ctrl.sel_alu2 := SEL_ALU2.IMM.U
    io.exe_ctrl.sel_jpc_i := SEL_JPC_I.PC.U
    io.exe_ctrl.sel_jpc_o := SEL_JPC_O.Normal.U
    io.exe_ctrl.redir_inst := false.B
    io.exe_ctrl.bru_inst := false.B
    io.exe_ctrl.mem_cmd := MEM_TYPE.NOT_MEM.U
    io.exe_ctrl.wb_en := false.B
    io.exe_ctrl.sel_wb := SEL_WB.ALU.U
}
/**
  * One Simple circuit using DecodeTable to make me familar with DecodeTable
  * @In 8 bits
  * @Out 4 bits
  * using head 4 bits, map to Out[3:2]
  * using last 4 bits, map to Out[1:0]
  * head use to add
  * tail used as reverse, I mean 0 --> 3, 1 --> 2, 2 --> 1, 3 -->0
  */
class MuxDecode extends Module {
    val io = IO(new Bundle{
        val in = Input(UInt(8.W))
        val out = Output(UInt(4.W))
    })
    ???    
}