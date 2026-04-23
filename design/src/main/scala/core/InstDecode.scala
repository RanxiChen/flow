package flow.core
import chisel3._
import chisel3.util._
import flow.core.IMM_TYPE.CSR_Type

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
    val is_w = Output(Bool())
    val csr_addr = Output(UInt(12.W))
    val csr_cmd = Output(UInt(CSR_CMD.width.W))
}

class RV64IZicsrDecoder extends Module {
    val io = IO(new Bundle{
        val inst = Input(UInt(32.W))
        val I_ctrl = Output(new EXE_Ctrl)
        val illegal_inst = Output(Bool())
    })
    //default values
    io.I_ctrl.alu_op := ALU_OP.XXX.U
    io.I_ctrl.sel_imm := IMM_TYPE.I_Type.U
    io.I_ctrl.wb_en := false.B
    io.I_ctrl.sel_wb := SEL_WB.XXX.U
    io.I_ctrl.sel_alu2 := SEL_ALU2.IMM.U
    io.I_ctrl.sel_alu1 := SEL_ALU1.XXX.U
    io.I_ctrl.is_w := false.B
    io.I_ctrl.bru_op := BRU_OP.XXX.U
    io.I_ctrl.sel_jpc_i := SEL_JPC_I.XXX.U
    io.I_ctrl.sel_jpc_o := SEL_JPC_O.XXX.U
    io.I_ctrl.redir_inst := false.B
    io.I_ctrl.bru_inst := false.B
    io.I_ctrl.mem_cmd := MEM_TYPE.NOT_MEM.U
    io.I_ctrl.csr_addr := 0.U
    io.I_ctrl.csr_cmd := CSR_CMD.NOP.U
    io.illegal_inst := true.B
    //manual decode
    val opcode = io.inst(6,0)
    val funct3 = io.inst(14,12)
    val funct7 = io.inst(31,25)
    val funct6 = io.inst(31,26)
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
                is("b000".U){io.I_ctrl.alu_op := ALU_OP.ADD.U; io.illegal_inst := false.B} //ADDI
                is("b010".U){io.I_ctrl.alu_op := ALU_OP.SLT.U; io.illegal_inst := false.B} //SLTI
                is("b011".U){io.I_ctrl.alu_op := ALU_OP.SLTU.U; io.illegal_inst := false.B} //SLTIU
                is("b100".U){io.I_ctrl.alu_op := ALU_OP.XOR.U; io.illegal_inst := false.B} //XORI
                is("b110".U){io.I_ctrl.alu_op := ALU_OP.OR.U; io.illegal_inst := false.B} //ORI
                is("b111".U){io.I_ctrl.alu_op := ALU_OP.AND.U; io.illegal_inst := false.B} //ANDI
                is("b001".U){
                    when(funct6 === "b000000".U){
                        io.I_ctrl.alu_op := ALU_OP.SLL.U //SLLI
                        io.illegal_inst := false.B
                    }
                }
                is("b101".U){
                    when(funct6 === "b000000".U){
                        io.I_ctrl.alu_op := ALU_OP.SRL.U //SRLI
                        io.illegal_inst := false.B
                    }.elsewhen(funct6 === "b010000".U){
                        io.I_ctrl.alu_op := ALU_OP.SRA.U //SRAI
                        io.illegal_inst := false.B
                    }
                }
            }            
        }
        is(OPCODE.OP_IMM_32){
            // RV64I W-suffix I-type ALU instructions.
            io.I_ctrl.wb_en := true.B
            io.I_ctrl.sel_wb := SEL_WB.ALU.U
            io.I_ctrl.sel_imm := IMM_TYPE.I_Type.U
            io.I_ctrl.sel_alu2 := SEL_ALU2.IMM.U
            io.I_ctrl.sel_alu1 := SEL_ALU1.RS1.U
            io.I_ctrl.mem_cmd := MEM_TYPE.NOT_MEM.U
            io.I_ctrl.is_w := true.B
            switch(funct3){
                is("b000".U){io.I_ctrl.alu_op := ALU_OP.ADD.U; io.illegal_inst := false.B} //ADDIW
                is("b001".U){
                    when(funct7 === "b0000000".U){
                        io.I_ctrl.alu_op := ALU_OP.SLL.U //SLLIW
                        io.illegal_inst := false.B
                    }
                }
                is("b101".U){
                    when(funct7 === "b0000000".U){
                        io.I_ctrl.alu_op := ALU_OP.SRL.U //SRLIW
                        io.illegal_inst := false.B
                    }.elsewhen(funct7 === "b0100000".U){
                        io.I_ctrl.alu_op := ALU_OP.SRA.U //SRAIW
                        io.illegal_inst := false.B
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
            io.illegal_inst := false.B
        }
        is(OPCODE.AUIPC){
            //AUIPC instruction
            io.I_ctrl.wb_en := true.B
            io.I_ctrl.sel_wb := SEL_WB.ALU.U
            io.I_ctrl.sel_imm := IMM_TYPE.U_Type.U
            io.I_ctrl.sel_alu2 := SEL_ALU2.IMM.U
            io.I_ctrl.sel_alu1 := SEL_ALU1.PC.U
            io.I_ctrl.alu_op := ALU_OP.ADD.U
            io.illegal_inst := false.B
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
                        is("b0000000".U){io.I_ctrl.alu_op := ALU_OP.ADD.U; io.illegal_inst := false.B} //ADD
                        is("b0100000".U){io.I_ctrl.alu_op := ALU_OP.SUB.U; io.illegal_inst := false.B} //SUB
                    }
                }
                is("b001".U){
                    when(funct7 === "b0000000".U){io.I_ctrl.alu_op := ALU_OP.SLL.U; io.illegal_inst := false.B} //SLL
                }
                is("b010".U){
                    when(funct7 === "b0000000".U){io.I_ctrl.alu_op := ALU_OP.SLT.U; io.illegal_inst := false.B} //SLT
                }
                is("b011".U){
                    when(funct7 === "b0000000".U){io.I_ctrl.alu_op := ALU_OP.SLTU.U; io.illegal_inst := false.B} //SLTU
                }
                is("b100".U){
                    when(funct7 === "b0000000".U){io.I_ctrl.alu_op := ALU_OP.XOR.U; io.illegal_inst := false.B} //XOR
                }
                is("b101".U){
                    when(funct7 === "b0000000".U){
                        io.I_ctrl.alu_op := ALU_OP.SRL.U //SRL
                        io.illegal_inst := false.B
                    }.elsewhen(funct7 === "b0100000".U){
                        io.I_ctrl.alu_op := ALU_OP.SRA.U //SRA
                        io.illegal_inst := false.B
                    }
                }
                is("b110".U){
                    when(funct7 === "b0000000".U){io.I_ctrl.alu_op := ALU_OP.OR.U; io.illegal_inst := false.B} //OR
                }
                is("b111".U){
                    when(funct7 === "b0000000".U){io.I_ctrl.alu_op := ALU_OP.AND.U; io.illegal_inst := false.B} //AND
                }
            }
        }
        is(OPCODE.OP_32){
            // RV64I W-suffix R-type ALU instructions.
            io.I_ctrl.wb_en := true.B
            io.I_ctrl.sel_wb := SEL_WB.ALU.U
            io.I_ctrl.sel_imm := IMM_TYPE.I_Type.U
            io.I_ctrl.sel_alu2 := SEL_ALU2.RS2.U
            io.I_ctrl.sel_alu1 := SEL_ALU1.RS1.U
            io.I_ctrl.is_w := true.B
            switch(funct3){
                is("b000".U){
                    switch(funct7){
                        is("b0000000".U){io.I_ctrl.alu_op := ALU_OP.ADD.U; io.illegal_inst := false.B} //ADDW
                        is("b0100000".U){io.I_ctrl.alu_op := ALU_OP.SUB.U; io.illegal_inst := false.B} //SUBW
                    }
                }
                is("b001".U){
                    when(funct7 === "b0000000".U){
                        io.I_ctrl.alu_op := ALU_OP.SLL.U //SLLW
                        io.illegal_inst := false.B
                    }
                }
                is("b101".U){
                    when(funct7 === "b0000000".U){
                        io.I_ctrl.alu_op := ALU_OP.SRL.U //SRLW
                        io.illegal_inst := false.B
                    }.elsewhen(funct7 === "b0100000".U){
                        io.I_ctrl.alu_op := ALU_OP.SRA.U //SRAW
                        io.illegal_inst := false.B
                    }
                }
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
            io.illegal_inst := false.B
        }
        is(OPCODE.JALR){
            when(funct3 === "b000".U) {
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
                io.illegal_inst := false.B
            }
        }
        is(OPCODE.BRANCH){
            io.I_ctrl.bru_inst := true.B
            io.I_ctrl.sel_imm := IMM_TYPE.B_Type.U
            io.I_ctrl.sel_jpc_i := SEL_JPC_I.PC.U
            io.I_ctrl.sel_jpc_o := SEL_JPC_O.Normal.U
            switch(funct3){
                is("b000".U){io.I_ctrl.bru_op := BRU_OP.BEQ.U; io.illegal_inst := false.B} //BEQ
                is("b001".U){io.I_ctrl.bru_op := BRU_OP.BNE.U; io.illegal_inst := false.B} //BNE
                is("b100".U){io.I_ctrl.bru_op := BRU_OP.BLT.U; io.illegal_inst := false.B} //BLT
                is("b101".U){io.I_ctrl.bru_op := BRU_OP.BGE.U; io.illegal_inst := false.B} //BGE
                is("b110".U){io.I_ctrl.bru_op := BRU_OP.BLTU.U; io.illegal_inst := false.B} //BLTU
                is("b111".U){io.I_ctrl.bru_op := BRU_OP.BGEU.U; io.illegal_inst := false.B} //BGEU
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
                is("b000".U){io.I_ctrl.mem_cmd := MEM_TYPE.LB.U; io.illegal_inst := false.B} //LB
                is("b001".U){io.I_ctrl.mem_cmd := MEM_TYPE.LH.U; io.illegal_inst := false.B} //LH
                is("b010".U){io.I_ctrl.mem_cmd := MEM_TYPE.LW.U; io.illegal_inst := false.B} //LW
                is("b011".U){io.I_ctrl.mem_cmd := MEM_TYPE.LD.U; io.illegal_inst := false.B} //LD
                is("b100".U){io.I_ctrl.mem_cmd := MEM_TYPE.LBU.U; io.illegal_inst := false.B} //LBU
                is("b101".U){io.I_ctrl.mem_cmd := MEM_TYPE.LHU.U; io.illegal_inst := false.B} //LHU
                is("b110".U){io.I_ctrl.mem_cmd := MEM_TYPE.LWU.U; io.illegal_inst := false.B} //LWU
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
                is("b000".U){io.I_ctrl.mem_cmd := MEM_TYPE.SB.U; io.illegal_inst := false.B} //SB
                is("b001".U){io.I_ctrl.mem_cmd := MEM_TYPE.SH.U; io.illegal_inst := false.B} //SH
                is("b010".U){io.I_ctrl.mem_cmd := MEM_TYPE.SW.U; io.illegal_inst := false.B} //SW
                is("b011".U){io.I_ctrl.mem_cmd := MEM_TYPE.SD.U; io.illegal_inst := false.B} //SD
            }
        }
        is(OPCODE.MISC_MEM){
            switch(funct3){
                is("b000".U){
                    // FENCE is currently satisfied by the in-order memory pipeline.
                    // Decode it as an explicit NOP while preserving the original inst bits.
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
                    io.I_ctrl.is_w := false.B
                    io.I_ctrl.csr_addr := 0.U
                    io.I_ctrl.csr_cmd := CSR_CMD.NOP.U
                    io.illegal_inst := false.B
                } //FENCE
            }
        }
        is(OPCODE.SYSTEM){
            switch(funct3){
                is("b000".U){
                    when(
                        io.inst(31, 20) === SIM_SYSTEM.ESTOP_IMM12 &&
                        io.inst(19, 15) === 0.U &&
                        io.inst(11, 7) === 0.U
                    ) {
                        io.I_ctrl.mem_cmd := MEM_TYPE.NOT_MEM.U
                        io.illegal_inst := false.B
                    }
                }
                is("b001".U){
                    io.I_ctrl.sel_imm :=  IMM_TYPE.CSR_Type.U
                    io.I_ctrl.wb_en := true.B
                    io.I_ctrl.sel_wb := SEL_WB.CSR.U
                    io.I_ctrl.sel_alu1 := SEL_ALU1.RS1.U
                    io.I_ctrl.sel_alu2 := SEL_ALU2.IMM.U
                    io.I_ctrl.csr_cmd := CSR_CMD.RW.U
                    io.I_ctrl.alu_op := ALU_OP.RS1.U
                    io.I_ctrl.csr_addr := io.inst(31,20)
                    io.illegal_inst := false.B
                } //CSRRW
                is("b010".U){
                    io.I_ctrl.sel_imm :=  IMM_TYPE.CSR_Type.U
                    io.I_ctrl.wb_en := true.B
                    io.I_ctrl.sel_wb := SEL_WB.CSR.U
                    io.I_ctrl.sel_alu1 := SEL_ALU1.RS1.U
                    io.I_ctrl.sel_alu2 := SEL_ALU2.IMM.U
                    io.I_ctrl.csr_cmd := CSR_CMD.RS.U
                    io.I_ctrl.alu_op := ALU_OP.RS1.U
                    io.I_ctrl.csr_addr := io.inst(31,20)
                    io.illegal_inst := false.B
                } //CSRRS
                is("b011".U){
                    io.I_ctrl.sel_imm :=  IMM_TYPE.CSR_Type.U
                    io.I_ctrl.wb_en := true.B
                    io.I_ctrl.sel_wb := SEL_WB.CSR.U
                    io.I_ctrl.sel_alu1 := SEL_ALU1.RS1.U
                    io.I_ctrl.sel_alu2 := SEL_ALU2.IMM.U
                    io.I_ctrl.csr_cmd := CSR_CMD.RC.U
                    io.I_ctrl.alu_op := ALU_OP.RS1.U
                    io.I_ctrl.csr_addr := io.inst(31,20)
                    io.illegal_inst := false.B
                } //CSRRC
                is("b101".U){
                    io.I_ctrl.sel_imm :=  IMM_TYPE.CSR_Type.U
                    io.I_ctrl.wb_en := true.B
                    io.I_ctrl.sel_wb := SEL_WB.CSR.U
                    io.I_ctrl.sel_alu1 := SEL_ALU1.RS1.U
                    io.I_ctrl.sel_alu2 := SEL_ALU2.IMM.U
                    io.I_ctrl.csr_cmd := CSR_CMD.RWI.U
                    io.I_ctrl.alu_op := ALU_OP.RS2.U
                    io.I_ctrl.csr_addr := io.inst(31,20)
                    io.illegal_inst := false.B
                } //CSRRWI
                is("b110".U){
                    io.I_ctrl.sel_imm :=  IMM_TYPE.CSR_Type.U
                    io.I_ctrl.wb_en := true.B
                    io.I_ctrl.sel_wb := SEL_WB.CSR.U
                    io.I_ctrl.sel_alu1 := SEL_ALU1.RS1.U
                    io.I_ctrl.sel_alu2 := SEL_ALU2.IMM.U
                    io.I_ctrl.csr_cmd := CSR_CMD.RSI.U
                    io.I_ctrl.alu_op := ALU_OP.RS2.U
                    io.I_ctrl.csr_addr := io.inst(31,20)
                    io.illegal_inst := false.B
                } //CSRRSI
                is("b111".U){
                    io.I_ctrl.sel_imm :=  IMM_TYPE.CSR_Type.U
                    io.I_ctrl.wb_en := true.B
                    io.I_ctrl.sel_wb := SEL_WB.CSR.U
                    io.I_ctrl.sel_alu1 := SEL_ALU1.RS1.U
                    io.I_ctrl.sel_alu2 := SEL_ALU2.IMM.U
                    io.I_ctrl.csr_cmd := CSR_CMD.RCI.U
                    io.I_ctrl.alu_op := ALU_OP.RS2.U
                    io.I_ctrl.csr_addr := io.inst(31,20)
                    io.illegal_inst := false.B
                } //CSRRCI
            }
        }
    }
}


class Decoder extends Module {
    val io = IO(new Bundle{
        val inst = Input(UInt(32.W))
        val exe_ctrl = Output(new EXE_Ctrl)
        val illegal_inst = Output(Bool())
    })
    val rv64iZicsrDecoder = Module(new RV64IZicsrDecoder())
    rv64iZicsrDecoder.io.inst := io.inst
    io.exe_ctrl := rv64iZicsrDecoder.io.I_ctrl
    io.illegal_inst := rv64iZicsrDecoder.io.illegal_inst
}
