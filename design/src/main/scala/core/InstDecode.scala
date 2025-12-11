package core
import chisel3._
import chisel3.util._
import core.IMM_TYPE.CSR_Type

class EXE_Ctrl extends Bundle {
    val alu_op = Output(UInt(ALU_OP.width.W))
    val bru_op = Output(UInt(BRU_OP.width.W))
    val sel_alu1 = Output(UInt(SEL_ALU1.width.W))
    val sel_alu2 = Output(UInt(SEL_ALU2.width.W))
    val sel_jpc_i = Output(UInt(SEL_JPC_I.width.W))
    val sel_jpc_o = Output(UInt(SEL_JPC_O.width.W))
    val redir_inst = Output(Bool())// unconditional jump instruction
    val bru_inst = Output(Bool()) // conditional branch instruction
    val mem_cmd = Output(UInt(MEM_TYPE.width.W))
    val sel_wb = Output(UInt(SEL_WB.width.W))
    val wb_en = Output(Bool())
    val sel_imm = Output(UInt(IMM_TYPE.width.W))
    val csr_addr = Output(UInt(12.W))
    val csr_cmd = Output(UInt(CSR_CMD.width.W))
}

class RV32IDecoder extends Module {
    val io = IO(new Bundle{
        val inst = Input(UInt(32.W))
        val I_ctrl = Output(new EXE_Ctrl)
    })
    //default values
    io.I_ctrl.alu_op := ALU_OP.XXX.U
    io.I_ctrl.sel_imm := IMM_TYPE.I_Type.U
    io.I_ctrl.wb_en := false.B
    io.I_ctrl.sel_wb := SEL_WB.XXX.U
    io.I_ctrl.sel_alu2 := SEL_ALU2.IMM.U
    io.I_ctrl.sel_alu1 := SEL_ALU1.XXX.U
    io.I_ctrl.bru_op := BRU_OP.XXX.U
    io.I_ctrl.sel_jpc_i := SEL_JPC_I.XXX.U
    io.I_ctrl.sel_jpc_o := SEL_JPC_O.XXX.U
    io.I_ctrl.redir_inst := false.B
    io.I_ctrl.bru_inst := false.B
    io.I_ctrl.mem_cmd := MEM_TYPE.NOT_MEM.U
    io.I_ctrl.csr_addr := 0.U
    io.I_ctrl.csr_cmd := CSR_CMD.NOP.U
    //manual decode
    val opcode = io.inst(6,0)
    val funct3 = io.inst(14,12)
    val funct7 = io.inst(31,25)
    switch(opcode){
        is(OPCODE.OP_IMM){
            //I-type ALU instructions
            io.I_ctrl.wb_en := true.B
            io.I_ctrl.sel_wb := SEL_WB.ALU.U
            io.I_ctrl.sel_imm := IMM_TYPE.I_Type.U
            io.I_ctrl.sel_alu2 := SEL_ALU2.IMM.U
            io.I_ctrl.sel_alu1 := SEL_ALU1.RS1.U
            io.I_ctrl.mem_cmd := MEM_TYPE.NOT_MEM.U
            switch(funct3){
                is("b000".U){io.I_ctrl.alu_op := ALU_OP.ADD.U} //ADDI
                is("b010".U){io.I_ctrl.alu_op := ALU_OP.SLT.U} //SLTI
                is("b011".U){io.I_ctrl.alu_op := ALU_OP.SLTU.U} //SLTIU
                is("b100".U){io.I_ctrl.alu_op := ALU_OP.XOR.U} //XORI
                is("b110".U){io.I_ctrl.alu_op := ALU_OP.OR.U} //ORI
                is("b111".U){io.I_ctrl.alu_op := ALU_OP.AND.U} //ANDI
                is("b001".U){io.I_ctrl.alu_op := ALU_OP.SLL.U} //SLLI
                is("b101".U){
                    when(funct7 === "b0000000".U){
                        io.I_ctrl.alu_op := ALU_OP.SRL.U //SRLI
                    }.elsewhen(funct7 === "b0100000".U){
                        io.I_ctrl.alu_op := ALU_OP.SRA.U //SRAI
                    }
                }
            }            
        }
        is(OPCODE.LUI){
            //LUI instruction
            io.I_ctrl.wb_en := true.B
            io.I_ctrl.sel_wb := SEL_WB.ALU.U
            io.I_ctrl.sel_imm := IMM_TYPE.U_Type.U
            io.I_ctrl.sel_alu2 := SEL_ALU2.IMM.U
            io.I_ctrl.sel_alu1 := SEL_ALU1.ZERO.U
            io.I_ctrl.alu_op := ALU_OP.ADD.U
        }
        is(OPCODE.AUIPC){
            //AUIPC instruction
            io.I_ctrl.wb_en := true.B
            io.I_ctrl.sel_wb := SEL_WB.ALU.U
            io.I_ctrl.sel_imm := IMM_TYPE.U_Type.U
            io.I_ctrl.sel_alu2 := SEL_ALU2.IMM.U
            io.I_ctrl.sel_alu1 := SEL_ALU1.PC.U
            io.I_ctrl.alu_op := ALU_OP.ADD.U
        }
        is(OPCODE.OP){
            //R type
            io.I_ctrl.wb_en := true.B
            io.I_ctrl.sel_wb := SEL_WB.ALU.U
            io.I_ctrl.sel_imm := IMM_TYPE.I_Type.U
            io.I_ctrl.sel_alu2 := SEL_ALU2.RS2.U
            io.I_ctrl.sel_alu1 := SEL_ALU1.RS1.U
            switch(funct3){
                is("b000".U){
                    switch(funct7){
                        is("b0000000".U){io.I_ctrl.alu_op := ALU_OP.ADD.U} //ADD
                        is("b0100000".U){io.I_ctrl.alu_op := ALU_OP.SUB.U} //SUB
                    }
                }
                is("b001".U){io.I_ctrl.alu_op := ALU_OP.SLL.U} //SLL
                is("b010".U){io.I_ctrl.alu_op := ALU_OP.SLT.U} //SLT
                is("b011".U){io.I_ctrl.alu_op := ALU_OP.SLTU.U} //SLTU
                is("b100".U){io.I_ctrl.alu_op := ALU_OP.XOR.U} //XOR
                is("b101".U){
                    when(funct7 === "b0000000".U){
                        io.I_ctrl.alu_op := ALU_OP.SRL.U //SRL
                    }.elsewhen(funct7 === "b0100000".U){
                        io.I_ctrl.alu_op := ALU_OP.SRA.U //SRA
                    }
                }
                is("b110".U){io.I_ctrl.alu_op := ALU_OP.OR.U} //OR
                is("b111".U){io.I_ctrl.alu_op := ALU_OP.AND.U} //AND
            }
        }
        is(OPCODE.JAL){
            io.I_ctrl.sel_imm := IMM_TYPE.J_Type.U
            io.I_ctrl.wb_en := true.B
            io.I_ctrl.sel_wb := SEL_WB.ALU.U
            io.I_ctrl.redir_inst := true.B
            io.I_ctrl.bru_inst := false.B
            io.I_ctrl.sel_alu1 := SEL_ALU1.PC.U
            io.I_ctrl.sel_alu2 := SEL_ALU2.CONST4.U
            io.I_ctrl.sel_jpc_i := SEL_JPC_I.PC.U
            io.I_ctrl.sel_jpc_o := SEL_JPC_O.Normal.U
            io.I_ctrl.alu_op := ALU_OP.ADD.U
        }
        is(OPCODE.JALR){
            io.I_ctrl.sel_imm := IMM_TYPE.I_Type.U
            io.I_ctrl.wb_en := true.B
            io.I_ctrl.sel_wb := SEL_WB.ALU.U
            io.I_ctrl.redir_inst := true.B
            io.I_ctrl.bru_inst := false.B
            io.I_ctrl.sel_alu1 := SEL_ALU1.PC.U
            io.I_ctrl.sel_alu2 := SEL_ALU2.CONST4.U
            io.I_ctrl.sel_jpc_i := SEL_JPC_I.RS1.U
            io.I_ctrl.sel_jpc_o := SEL_JPC_O.Jalr.U
            io.I_ctrl.alu_op := ALU_OP.ADD.U
        }
        is(OPCODE.BRANCH){
            io.I_ctrl.bru_inst := true.B
            io.I_ctrl.sel_imm := IMM_TYPE.B_Type.U
            io.I_ctrl.sel_jpc_i := SEL_JPC_I.PC.U
            io.I_ctrl.sel_jpc_o := SEL_JPC_O.Normal.U
            switch(funct3){
                is("b000".U){io.I_ctrl.bru_op := BRU_OP.BEQ.U} //BEQ
                is("b001".U){io.I_ctrl.bru_op := BRU_OP.BNE.U} //BNE
                is("b100".U){io.I_ctrl.bru_op := BRU_OP.BLT.U} //BLT
                is("b101".U){io.I_ctrl.bru_op := BRU_OP.BGE.U} //BGE
                is("b110".U){io.I_ctrl.bru_op := BRU_OP.BLTU.U} //BLTU
                is("b111".U){io.I_ctrl.bru_op := BRU_OP.BGEU.U} //BGEU
            }
        }
        is(OPCODE.LOAD){
            io.I_ctrl.sel_imm := IMM_TYPE.I_Type.U
            io.I_ctrl.wb_en := true.B
            io.I_ctrl.sel_wb := SEL_WB.MEM.U
            io.I_ctrl.sel_alu1 := SEL_ALU1.RS1.U
            io.I_ctrl.sel_alu2 := SEL_ALU2.IMM.U
            io.I_ctrl.alu_op := ALU_OP.ADD.U
            switch(funct3){
                is("b000".U){io.I_ctrl.mem_cmd := MEM_TYPE.LB.U} //LB
                is("b001".U){io.I_ctrl.mem_cmd := MEM_TYPE.LH.U} //LH
                is("b010".U){io.I_ctrl.mem_cmd := MEM_TYPE.LW.U} //LW
                is("b100".U){io.I_ctrl.mem_cmd := MEM_TYPE.LBU.U} //LBU
                is("b101".U){io.I_ctrl.mem_cmd := MEM_TYPE.LHU.U} //LHU
            }
        }
        is(OPCODE.STORE){
            io.I_ctrl.sel_imm := IMM_TYPE.S_Type.U
            io.I_ctrl.wb_en := false.B
            io.I_ctrl.sel_wb := SEL_WB.XXX.U
            io.I_ctrl.sel_alu1 := SEL_ALU1.RS1.U
            io.I_ctrl.sel_alu2 := SEL_ALU2.IMM.U
            io.I_ctrl.alu_op := ALU_OP.ADD.U
            switch(funct3){
                is("b000".U){io.I_ctrl.mem_cmd := MEM_TYPE.SB.U} //SB
                is("b001".U){io.I_ctrl.mem_cmd := MEM_TYPE.SH.U} //SH
                is("b010".U){io.I_ctrl.mem_cmd := MEM_TYPE.SW.U} //SW
            }
        }
        is(OPCODE.SYSTEM){
            //CSR instructions
            io.I_ctrl.sel_imm :=  IMM_TYPE.CSR_Type.U
            io.I_ctrl.wb_en := true.B
            io.I_ctrl.sel_wb := SEL_WB.CSR.U
            io.I_ctrl.sel_alu1 := SEL_ALU1.RS1.U
            io.I_ctrl.sel_alu2 := SEL_ALU2.IMM.U
            io.I_ctrl.alu_op := ALU_OP.XXX.U
            io.I_ctrl.csr_addr := io.inst(31,20)
            switch(funct3){
                is("b001".U){io.I_ctrl.csr_cmd := CSR_CMD.RW.U} //CSRRW
                is("b010".U){io.I_ctrl.csr_cmd := CSR_CMD.RS.U} //CSRRS
                is("b011".U){io.I_ctrl.csr_cmd := CSR_CMD.RC.U} //CSRRC
                is("b101".U){io.I_ctrl.csr_cmd := CSR_CMD.RWI.U} //CSRRWI
                is("b110".U){io.I_ctrl.csr_cmd := CSR_CMD.RSI.U} //CSRRSI
                is("b111".U){io.I_ctrl.csr_cmd := CSR_CMD.RCI.U} //CSRRCI
            }
            switch(funct3){
                is("b000".U, "b010".U, "b011".U){
                    io.I_ctrl.alu_op := ALU_OP.RS1.U
                }
                is("b110".U, "b101".U,"b111".U){
                    io.I_ctrl.alu_op := ALU_OP.RS2.U
                }
            }
        }
    }
}


class Decoder extends Module {
    val io = IO(new Bundle{
        val inst = Input(UInt(32.W))
        val exe_ctrl = Output(new EXE_Ctrl)
    })
    val rv32i_decoder = Module(new RV32IDecoder())
    rv32i_decoder.io.inst := io.inst
    io.exe_ctrl := rv32i_decoder.io.I_ctrl
}
