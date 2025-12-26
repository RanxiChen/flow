package core

import chisel3._
import chisel3.util._
import top.DefaultConfig._
class DMemPort extends Bundle {
    val req_addr = Output(UInt(64.W))
    val rdata = Input(UInt(64.W))
    val wt_rd = Output(Bool())
    val mem_valid = Output(Bool())
    val wdata = Output(UInt(64.W))
    val wmask = Output(UInt(8.W))
    val resp_addr = Input(UInt(64.W))
    val can_next = Input(Bool())
}
/*
class IbufPackWithValid extends Bundle {
    val valid = Bool()
    val pack = new InstPack
}
class IBuf extends Module {
    val io = IO(new Bundle {
        val enq = Flipped(DecoupledIO(new InstPack))
        val deq = DecoupledIO(new InstPack)
        val flush = Input(Bool())
    })
    /**
      * Instruction Buffer, will be flushed when flush is high
      * the entry is 2, allow both enq and deq
      * when flush happends,flush the whole buffer
      * when impl, when flush happends, the enq are connected to buble
      * the deq will output buble until the flush is done
      */
    val ibuf = Module(new Queue(new IbufPackWithValid, 2))
    object State extends ChiselEnum {
        val sNormal, sFlush = Value
    }
    val state = RegInit(State.sNormal)
    //state transition
    when(state === State.sNormal){
        when(io.flush){
            state := State.sFlush
        }
    }.elsewhen(state === State.sFlush){
        when(!io.flush && ibuf.io.count === 0.U){
            state := State.sNormal
        }
    }.otherwise{
        state := State.sNormal
    }
    //work
    when(state === State.sNormal){
        io.enq.ready := ibuf.io.enq.ready
        ibuf.io.enq.valid := io.enq.valid
        ibuf.io.enq.bits.pack := io.enq.bits
        ibuf.io.enq.bits.valid := true.B
        io.deq.valid := ibuf.io.deq.valid
        io.deq.bits := ibuf.io.deq.bits.pack
        ibuf.io.deq.ready := io.deq.ready
    }
}
*/


class Backend extends Module{
    val XLEN = 64
    val dumplog = true
    //IO
    val io = IO(new Bundle{
        val imem = Flipped(DecoupledIO(new InstPack))
        val dmem = new DMemPort
        val fctl = new FrontendCtrl
    })
    //counter instrctions    
    val id_cnt = RegInit(0.U(64.W))
    val exe_cnt = RegInit(0.U(64.W))
    val mem_cnt = RegInit(0.U(64.W))
    val wb_cnt = RegInit(0.U(64.W)) 
    val mem_stall = WireDefault(false.B)
    val pc_error_predict = WireDefault(false.B)
    val instruction_address_misaligned = WireDefault(false.B)
    val sucessful_fetch = WireDefault(false.B)

    //initial IO
    io.fctl.flush := false.B
    io.fctl.pc_redir := ADDR_XXX.U(64.W)
    io.fctl.pc_misfetch := false.B
    io.dmem.req_addr := 0.U
    io.dmem.wt_rd := false.B
    io.dmem.mem_valid := false.B
    io.dmem.wdata := 0.U
    io.dmem.wmask := 0.U
    //IBUF
    val inst_buf_flush = WireDefault(false.B) //flush when mispredict
    //Fetch
    //val ibuf = Module(new IBuf)
    val ibuf = Module(new Queue(new InstPack, 2,hasFlush = true))
    //ibuf.io.flush := inst_buf_flush
    ibuf.io.flush.get := inst_buf_flush
    //frontend feed inst to ibuf
    ibuf.io.enq <> io.imem

    val ibuf_pc = RegInit(0.U(64.W)) //ibuf content
    val ibuf_inst = RegInit(0.U(32.W)) //ibuf content
    val if_id_inst = WireDefault(0.U(32.W)) // used by id stage
    val if_id_pc = WireDefault(0.U(64.W)) // used by id stage
    
    val inst_buf_buble = RegInit(true.B) //flag indicate buble
    val inst_buf_stall = WireDefault(false.B) //stall when needed,such as mem take longer time
    ibuf.io.deq.ready := !inst_buf_stall
    //when ibuf is empty,or stall,or flush,then insert nop
    ibuf_pc := ibuf.io.deq.bits.pc
    ibuf_inst := ibuf.io.deq.bits.data
    inst_buf_buble := !ibuf.io.deq.fire || inst_buf_flush
    if_id_inst := Mux(inst_buf_buble, "h00000013".U, ibuf_inst) //nop if buble
    if_id_pc := Mux(inst_buf_buble, ADDR_XXX.U(64.W), ibuf_pc)
    id_cnt := Mux(inst_buf_buble, 0.U, id_cnt+1.U)
    
 
    //decode
    val id = Module(new Decoder())
    id.io.inst := if_id_inst
    val rf = Module(new RegFile(64,dumplog))
    val id_rs1_addr = if_id_inst(19,15)
    val id_rs2_addr = if_id_inst(24,20)
    rf.io.rs1_addr := id_rs1_addr
    rf.io.rs2_addr := id_rs2_addr
    val imm_gen = Module(new ImmGen(64))
    imm_gen.io.inst := if_id_inst
    imm_gen.io.type_sel := id.io.exe_ctrl.sel_imm
    class EXE_Reg extends Bundle {
        val ctrl = new EXE_Ctrl
        val pc   = UInt(64.W)
        val rs1_data = UInt(XLEN.W)
        val rs2_data = UInt(XLEN.W)
        val rs1_addr = UInt(5.W)
        val rs2_addr = UInt(5.W)
        val rd = UInt(5.W)
        val imm = UInt(XLEN.W)
        val buble = Bool()
    }
    val exe_reg_flush = WireDefault(false.B)
    val exe_reg = Reg(new EXE_Reg)
    val rs2_data_id = WireDefault(0.U(XLEN.W))
    val rs1_data_id = WireDefault(0.U(XLEN.W))
    when(rf.io.rd_en){
        when(id_rs1_addr === 0.U){
            rs1_data_id := 0.U
        }.elsewhen(id_rs1_addr === rf.io.rd_addr){
            rs1_data_id := rf.io.rd_data            
        }.otherwise{
            rs1_data_id := rf.io.rs1_data
        }
        when(id_rs2_addr === 0.U){
            rs2_data_id := 0.U
        }.elsewhen(id_rs2_addr === rf.io.rd_addr){
            rs2_data_id := rf.io.rd_data            
        }.otherwise{
            rs2_data_id := rf.io.rs2_data
        }
    }.otherwise{
        rs1_data_id := rf.io.rs1_data
        rs2_data_id := rf.io.rs2_data
    }
    when(reset.asBool || exe_reg_flush){
        exe_reg.ctrl.alu_op := ALU_OP.XXX.U
        exe_reg.ctrl.bru_op := BRU_OP.XXX.U
        exe_reg.ctrl.mem_cmd := MEM_TYPE.NOT_MEM.U
        exe_reg.ctrl.sel_alu1 := SEL_ALU1.XXX.U
        exe_reg.ctrl.sel_alu2 := SEL_ALU2.XXX.U
        exe_reg.ctrl.sel_imm := IMM_TYPE.I_Type.U
        exe_reg.ctrl.sel_jpc_i := SEL_JPC_I.XXX.U
        exe_reg.ctrl.sel_jpc_o := SEL_JPC_O.XXX.U
        exe_reg.ctrl.sel_wb := SEL_WB.XXX.U
        exe_reg.ctrl.wb_en := false.B
        exe_reg.pc := ADDR_XXX.U(64.W)
        exe_reg.rs1_data := 0.U
        exe_reg.rs2_data := 0.U
        exe_reg.rs1_addr := 0.U
        exe_reg.rs2_addr := 0.U
        exe_reg.imm := 0.U
        exe_reg.rd := 0.U
        exe_reg.buble := true.B
        exe_cnt := 0.U
    }.elsewhen(!mem_stall){
        exe_reg.ctrl := id.io.exe_ctrl
        exe_reg.pc := if_id_pc
        exe_reg.rs1_data := rs1_data_id
        exe_reg.rs2_data := rs2_data_id
        exe_reg.rs1_addr := if_id_inst(19,15)
        exe_reg.rs2_addr := if_id_inst(24,20)
        exe_reg.imm := imm_gen.io.imm
        exe_reg.rd := if_id_inst(11,7)
        exe_reg.buble := inst_buf_buble
        exe_cnt := id_cnt
    }
    //EXE stage
    val rs1_data_wire = WireDefault(0.U(XLEN.W))
    val rs2_data_wire = WireDefault(0.U(XLEN.W))
    val alu = Module(new ALU(XLEN))
    val bru = Module(new BRU(XLEN))
    val jau = Module(new JAU(XLEN))
    alu.io.alu_op := exe_reg.ctrl.alu_op
    alu.io.alu_in1 := MuxLookup(
        exe_reg.ctrl.sel_alu1,
        0.U)(
        Seq(
            SEL_ALU1.RS1.U -> rs1_data_wire,
            SEL_ALU1.PC.U  -> exe_reg.pc,
            SEL_ALU1.ZERO.U-> 0.U
        )
    )
    alu.io.alu_in2 := MuxLookup(
        exe_reg.ctrl.sel_alu2,
        0.U)(
        Seq(
            SEL_ALU2.IMM.U -> exe_reg.imm,
            SEL_ALU2.RS2.U -> rs2_data_wire,
            SEL_ALU2.CONST4.U -> 4.U
        )
    )
    bru.io.bru_op := exe_reg.ctrl.bru_op
    bru.io.rs1_data := rs1_data_wire
    bru.io.rs2_data := rs2_data_wire
    jau.io.sel_jpc_i := exe_reg.ctrl.sel_jpc_i
    jau.io.sel_jpc_o := exe_reg.ctrl.sel_jpc_o
    jau.io.pc := exe_reg.pc
    jau.io.rs1_data := rs1_data_wire
    jau.io.imm := exe_reg.imm
    pc_error_predict := (jau.io.jmp_addr =/= if_id_pc) && 
         !inst_buf_buble && (exe_reg.ctrl.redir_inst || 
            exe_reg.ctrl.bru_inst && bru.io.take_branch
        )
    inst_buf_flush := pc_error_predict
    exe_reg_flush := pc_error_predict
    io.fctl.pc_redir:= jau.io.jmp_addr
    io.fctl.pc_misfetch := pc_error_predict
    inst_buf_flush := pc_error_predict
    //Mem Stage
    class MEM_Reg extends Bundle {
        val data = UInt(XLEN.W)
        val rs2 = UInt(XLEN.W)
        val mem_cmd = UInt(MEM_TYPE.width.W)
        val rd = UInt(5.W)
        val rs1_addr = UInt(5.W)
        val csr_addr = UInt(12.W)
        val csr_cmd = UInt(CSR_CMD.width.W)
        val wb_en = Bool()
        val wb_sel = UInt(SEL_WB.width.W)
        val buble = Bool()
    }
    val mem_reg_flush = WireDefault(false.B)
    val mem_reg = Reg(new MEM_Reg)
    when(reset.asBool || mem_reg_flush){
        mem_reg.data := 0.U
        mem_reg.rs2 := 0.U
        mem_reg.mem_cmd := MEM_TYPE.NOT_MEM.U
        mem_reg.rd := 0.U
        mem_reg.wb_en := false.B
        mem_reg.wb_sel := SEL_WB.XXX.U
        mem_reg.buble := true.B
        mem_cnt := 0.U
        mem_reg.rs1_addr := 0.U
        mem_reg.csr_addr := 0.U
        mem_reg.csr_cmd := CSR_CMD.XXX.U
    }.elsewhen(!mem_stall){
        mem_reg.data := alu.io.alu_out
        mem_reg.rs2 := rs2_data_wire
        mem_reg.mem_cmd := exe_reg.ctrl.mem_cmd
        mem_reg.rd := exe_reg.rd
        mem_reg.wb_en := exe_reg.ctrl.wb_en
        mem_reg.wb_sel := exe_reg.ctrl.sel_wb
        mem_reg.buble := exe_reg.buble
        mem_reg.rs1_addr := exe_reg.rs1_addr
        mem_reg.csr_addr := exe_reg.ctrl.csr_addr
        mem_reg.csr_cmd := exe_reg.ctrl.csr_cmd
        mem_cnt := exe_cnt
    }
    val mem_base_addr = WireDefault(0.U(XLEN.W))
    mem_base_addr := mem_reg.data >> 3.U << 3.U // align to 8 bytes
    val mem_offset = WireDefault(0.U(3.W))
    mem_offset := mem_reg.data(2,0)
    io.dmem.req_addr := mem_base_addr
    val load_address_misaligned_xcpt = WireDefault(false.B)
    val store_address_misaligned_xcpt = WireDefault(false.B)
    when(!mem_reg.buble){
        switch(mem_reg.mem_cmd){
            is(MEM_TYPE.SB.U){
                io.dmem.wt_rd := true.B
                io.dmem.wdata := Fill(8, mem_reg.rs2(7,0))
                io.dmem.wmask := UIntToOH(mem_offset,8)
                store_address_misaligned_xcpt := false.B
            }
            is(MEM_TYPE.SH.U){
                io.dmem.wt_rd := true.B
                io.dmem.wdata := Fill(4, mem_reg.rs2(15,0))
                store_address_misaligned_xcpt := mem_offset(0)
                switch(mem_offset(2,1)){
                    is("b00".U){
                        io.dmem.wmask := "b00000011".U
                    }
                    is("b01".U){
                        io.dmem.wmask := "b00001100".U
                    }
                    is("b10".U){
                        io.dmem.wmask := "b00110000".U
                    }
                    is("b11".U){
                        io.dmem.wmask := "b11000000".U
                    }
                }
            }
            is(MEM_TYPE.SW.U){
                io.dmem.wt_rd := true.B
                io.dmem.wdata := Fill(2, mem_reg.rs2(31,0))
                store_address_misaligned_xcpt := mem_offset(1,0) =/= 0.U
                io.dmem.wmask := Mux(
                    mem_offset(2),//switch upper or lower 4 bytes
                    "b11110000".U,
                    "b00001111".U
                )
            }
            is(MEM_TYPE.SD.U){
                io.dmem.wt_rd := true.B
                io.dmem.wdata := mem_reg.rs2
                io.dmem.wmask := "b11111111".U
                store_address_misaligned_xcpt := mem_offset =/= 0.U
            }
            is(MEM_TYPE.LB.U, MEM_TYPE.LH.U, MEM_TYPE.LW.U,
               MEM_TYPE.LBU.U, MEM_TYPE.LHU.U){
                io.dmem.wt_rd := false.B
            }
        }
    }
    val mem_data_loaded = Wire(UInt(XLEN.W))
    mem_data_loaded := 0.U
    val align_load_buf = Wire(UInt(64.W))
    align_load_buf := 0.U
    when(!mem_reg.buble){
        switch(mem_reg.mem_cmd){
            is(MEM_TYPE.LB.U){
                load_address_misaligned_xcpt := false.B
                align_load_buf := (io.dmem.rdata >> (mem_offset << 3.U) ) & "hff".U 
                mem_data_loaded := Fill(56, align_load_buf(7)) ## align_load_buf(7,0)
            }
            is(MEM_TYPE.LH.U){
                load_address_misaligned_xcpt := mem_offset(0)
                align_load_buf := (io.dmem.rdata >> (mem_offset << 3.U) ) & "hffff".U
                mem_data_loaded := Fill(48, align_load_buf(15)) ## align_load_buf(15,0)
            }
            is(MEM_TYPE.LW.U){
                load_address_misaligned_xcpt := mem_offset(1,0) =/= 0.U
                align_load_buf := (io.dmem.rdata >> (mem_offset << 3.U) ) & "hffff_ffff".U
                mem_data_loaded := Fill(32, align_load_buf(31)) ## align_load_buf(31,0)
            }
            is(MEM_TYPE.LD.U){
                load_address_misaligned_xcpt := mem_offset =/= 0.U
                mem_data_loaded := io.dmem.rdata
            }
            is(MEM_TYPE.LBU.U){
                load_address_misaligned_xcpt := false.B
                align_load_buf := (io.dmem.rdata >> (mem_offset << 3.U) ) & "hff".U 
                mem_data_loaded := Fill(56, false.B) ## align_load_buf(7,0)
            }
            is(MEM_TYPE.LHU.U){
                load_address_misaligned_xcpt := mem_offset(0)
                align_load_buf := (io.dmem.rdata >> (mem_offset << 3.U) ) & "hffff".U
                mem_data_loaded := Fill(48, false.B) ## align_load_buf(15,0)
            }
            is(MEM_TYPE.LWU.U){
                load_address_misaligned_xcpt := mem_offset(1,0) =/= 0.U
                align_load_buf := (io.dmem.rdata >> (mem_offset << 3.U) ) & "hffff_ffff".U
                mem_data_loaded := Fill(32, false.B) ## align_load_buf(31,0)
            }
        }
    }
    io.dmem.mem_valid := mem_reg.mem_cmd =/= MEM_TYPE.NOT_MEM.U && !mem_reg.buble && 
        !load_address_misaligned_xcpt && !store_address_misaligned_xcpt
    mem_stall := io.dmem.mem_valid && !io.dmem.can_next
    inst_buf_stall := mem_stall
    val csr = Module(new CSRFile(XLEN,dumplog))
    csr.io.csr_addr := mem_reg.csr_addr
    csr.io.csr_cmd := mem_reg.csr_cmd
    csr.io.csr_reg_data := mem_reg.data
    csr.io.rs1_id := mem_reg.rs1_addr
    csr.io.rd_id := mem_reg.rd


    //WB state
    class WB_Reg extends Bundle {
        val wb_en = Bool()
        val sel_wb = UInt(SEL_WB.width.W)
        val rd_addr = UInt(5.W)
        val alu_out = UInt(XLEN.W)
        val mem_data = UInt(XLEN.W)
        val csr_data = UInt(XLEN.W)
        val buble = Bool()
    }
    val wb_reg_flush = WireDefault(false.B)
    val wb_reg = Reg(new WB_Reg)
    when(reset.asBool || wb_reg_flush){
        wb_reg.wb_en := false.B
        wb_reg.sel_wb := SEL_WB.XXX.U
        wb_reg.rd_addr := 0.U
        wb_reg.alu_out := 0.U
        wb_reg.mem_data := 0.U
        wb_reg.buble := true.B
        wb_reg.csr_data := 0.U
        wb_cnt := 0.U
    }.elsewhen(io.dmem.can_next && (io.dmem.req_addr === io.dmem.resp_addr) ||
        mem_reg.mem_cmd === MEM_TYPE.NOT_MEM.U
        ){
        wb_reg.wb_en := mem_reg.wb_en
        wb_reg.sel_wb := mem_reg.wb_sel
        wb_reg.rd_addr := mem_reg.rd
        wb_reg.alu_out := mem_reg.data
        wb_reg.mem_data := mem_data_loaded
        wb_reg.buble := mem_reg.buble
        wb_reg.csr_data := csr.io.csr_wdata
        wb_cnt := mem_cnt
    }
    rf.io.rd_en := wb_reg.wb_en && !wb_reg.buble
    rf.io.rd_addr := wb_reg.rd_addr
    val wb_data_wire = MuxLookup(
        wb_reg.sel_wb,
        0.U)(
        Seq(
            SEL_WB.ALU.U -> wb_reg.alu_out,
            SEL_WB.MEM.U -> wb_reg.mem_data,
            SEL_WB.CSR.U -> wb_reg.csr_data
        )
    )
    rf.io.rd_data := wb_data_wire
    //bypass
    when(!exe_reg.buble){
        //default
        rs1_data_wire := exe_reg.rs1_data
        rs2_data_wire := exe_reg.rs2_data
        //bypass from wb
        when(wb_reg.wb_en && !wb_reg.buble){
            when(exe_reg.rs1_addr === wb_reg.rd_addr){
                rs1_data_wire := wb_data_wire 
            }
            when(exe_reg.rs2_addr === wb_reg.rd_addr){
                rs2_data_wire := wb_data_wire
            }
        }
        //bypass from mem 
        when(!mem_reg.buble && mem_reg.mem_cmd === MEM_TYPE.NOT_MEM.U){
            //bypass from mem
            when(exe_reg.rs1_addr === mem_reg.rd){
                rs1_data_wire := mem_reg.data
            }
            when(exe_reg.rs2_addr === mem_reg.rd){
                rs2_data_wire := mem_reg.data
            }
        }.elsewhen(!mem_reg.buble && mem_reg.mem_cmd =/= MEM_TYPE.NOT_MEM.U && io.dmem.can_next){
            //bypass from mem data
            when(exe_reg.rs1_addr === mem_reg.rd){
                rs1_data_wire := mem_data_loaded
            }
            when(exe_reg.rs2_addr === mem_reg.rd){
                rs2_data_wire := mem_data_loaded
            }
        }
        
        
        //bypass from zero
        when(exe_reg.rs1_addr === 0.U){
            rs1_data_wire := 0.U
        }
        when(exe_reg.rs2_addr === 0.U){
            rs2_data_wire := 0.U
        }
    }
    //retire
    val priv_wb_cnt = RegNext(wb_cnt)
    val retire_inst = WireDefault(false.B)
    retire_inst := wb_cnt =/= 0.U && wb_cnt =/= priv_wb_cnt
    csr.io.core_retire := retire_inst
    /**
     *Debug Signal Dump
     */
    val coretimer = RegInit(0.U(16.W))
    coretimer := coretimer + 1.U
    if(dumplog){
    printf(cf"[InstStats]\t")
    printf(cf"[${coretimer}%3d]")
    printf(cf"[ID]${id_cnt}%4d")
    printf(cf"[EXE]${exe_cnt}%4d")
    printf(cf"[MEM]${mem_cnt}%4d")
    printf(cf"[WB]${wb_cnt}%4d\n")
    printf(cf"[PiPeLine]")
    printf(cf"[${coretimer}%3d]")
    printf(cf"[ID]") 
    when(!inst_buf_buble){
        //normal instruction in ibuf
        printf(cf"pc=0x${if_id_pc}%4x,inst=0x${if_id_inst}%8x")
    }.otherwise{
        //one buble
        printf(cf"XXXXXXXXX=buble=XXXXXXXXX")
    }
    //printf(cf"[ID]pc=${inst_buf.pc}%4x,inst=${inst_buf.inst}%8x,buble=${inst_buf.buble}\n")
    printf(cf"[EXE]")
    when(!exe_reg.buble){
        //printf(cf"pc=0x${exe_reg.pc}%4x")
        //printf(cf"alu_in1=0x${alu.io.alu_in1}%8x,alu_in2=0x${alu.io.alu_in2}%8x,alu_op=0x${alu.io.alu_op}%4x,alu_out=0x${alu.io.alu_out}%8x" )
        when(exe_reg.ctrl.bru_inst){
            printf(cf"br in1=0x${bru.io.rs1_data}%8x,in2=0x${bru.io.rs2_data}%8x,taken=${bru.io.take_branch},dest=0x${jau.io.jmp_addr}%4x")
        }.elsewhen(exe_reg.ctrl.redir_inst){
            printf(cf"jmp in1=0x${jau.io.rs1_data}%8x,imm=0x${jau.io.imm}%8x,jmp_addr=0x${jau.io.jmp_addr}%8x,dst=0x${jau.io.jmp_addr}%4x")
        }.otherwise{
            //printf(cf"alu in1=0x${alu.io.alu_in1}%8x,in2=0x${alu.io.alu_in2}%8x,alu_out=0x${alu.io.alu_out}%8x")
            printf(cf"pc=0x${exe_reg.pc}%4x,alu_out=0x${alu.io.alu_out}%8x")
        }
    }.otherwise{
        printf(cf"XXXXXXXXX=buble=XXXXXXX")
    }
    printf(cf"[MEM]")
    when(!mem_reg.buble){
        printf(cf"addr=0x${mem_reg.csr_addr}%4x")
        switch(mem_reg.mem_cmd){
            is(MEM_TYPE.NOT_MEM.U){
                switch(mem_reg.csr_cmd){
                    is(CSR_CMD.NOP.U){
                       printf(cf",NOT MEM") 
                    }
                    is(CSR_CMD.RW.U, CSR_CMD.RWI.U, CSR_CMD.RS.U, CSR_CMD.RC.U, CSR_CMD.RSI.U, CSR_CMD.RCI.U){
                        //printf(cf",CSR    ")
                        printf(cf",CSR,in_data=0x${mem_reg.data}%8x")
                    }
                }
            }
            is(MEM_TYPE.LB.U, MEM_TYPE.LH.U, MEM_TYPE.LW.U){
                printf(cf",LOAD   ")
            }
            is(MEM_TYPE.SB.U, MEM_TYPE.SH.U, MEM_TYPE.SW.U){
                printf(cf",STORE  ")
            }
        }
    }.otherwise{
        printf(cf"XXXXXXXXX=buble=XXXXXXX")
    }
    printf(cf"[WB]")
    when(!wb_reg.buble){
        when(wb_reg.wb_en){
            printf(cf"write x[${wb_reg.rd_addr}%2d],data=0x${wb_data_wire}%8x")
            //printf(cf",csr_data=0x${wb_reg.csr_data}%8x")
            //printf(cf",wb_sel=${wb_reg.sel_wb}%2d")
        }.otherwise{
            printf("NOT WB")
        }
    }.otherwise{
        printf(cf"XXXXXXXXX=buble=XXXXXXX")
    }
    printf("\n")
    printf("***************************************************************\n")
    }
}

import _root_.circt.stage.ChiselStage
object GenerateFile extends App {
    ChiselStage.emitSystemVerilogFile(
        new Backend,
        Array("--target-dir", "build"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )
}

