package core

import chisel3._
import chisel3.util._
import os.read
import os.write
/**
  * Register File, used to store general purpose registers
  * @param XLEN
  */
class RegFile(XLEN:Int=64,val dumplog:Boolean=false) extends Module {
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
    if(dumplog){
    printf(cf"[RegFile]\n")
    for(i <- 0 until 32){
        printf(cf"x[${i}%2d]=0x${content(i)}%x    ")
        if(i%4 == 3){
            printf("\n")
        }        
    }
    printf("\n")
    }
}
class CSRFile(XLEN:Int=64,val dumplog:Boolean=false) extends Module {
    val io = IO(new Bundle{
        val csr_addr = Input(UInt(12.W))
        val csr_cmd  = Input(UInt(CSR_CMD.width.W))
        val csr_reg_data = Input(UInt(XLEN.W))
        val csr_wdata = Output(UInt(XLEN.W))
        val rs1_id = Input(UInt(5.W))
        val rd_id = Input(UInt(5.W))
        //Privilege related
        val priv = Output(UInt(2.W))
    })
    // csr supported
    val printer = RegInit(0.U(XLEN.W))
    val misa = RegInit(CoreParam.misa.U(64.W))
    val mvendorid = RegInit(0.U(32.W))
    val marchid = RegInit(0.U(XLEN.W))
    val mimpid = RegInit(0.U(XLEN.W))
    val mhartid = RegInit(0.U(XLEN.W)) // now just single core system
    val csrFile = Seq(
        BitPat(CSRMAP.printer.U) -> printer,
        BitPat(CSRMAP.misa.U)    -> misa,
        BitPat(CSRMAP.mvendorid.U)-> mvendorid,
        BitPat(CSRMAP.marchid.U)  -> marchid,
        BitPat(CSRMAP.mimpid.U)    -> mimpid,
        BitPat(CSRMAP.mhartid.U)  -> mhartid
    )
    val old_csr_val = WireDefault(0.U(XLEN.W))
    val new_csr_val = WireDefault(old_csr_val)
    val read_csr = Wire(Bool())
    val write_csr = Wire(Bool())
    val uimm = io.csr_reg_data(4,0)
    read_csr := false.B
    write_csr := false.B
    when(read_csr){
        old_csr_val := Lookup(io.csr_addr, 0.U, csrFile)
    }.otherwise{
        old_csr_val := 0.U
    }
    switch(io.csr_cmd){
        is(CSR_CMD.NOP.U){
            //nop
            read_csr := false.B
            write_csr := false.B
        }
        is(CSR_CMD.RW.U){
            read_csr := Mux(io.rd_id =/= 0.U, true.B, false.B)
            write_csr := true.B
            new_csr_val := io.csr_reg_data
            //printf(cf"[CSR] rw = 0x${io.csr_reg_data}%x\n")
        }
        is(CSR_CMD.RS.U){
            read_csr := true.B
            write_csr := Mux(io.rs1_id =/= 0.U, true.B, false.B)
            new_csr_val := old_csr_val | io.csr_reg_data
        }
        is(CSR_CMD.RC.U){
            read_csr := true.B
            write_csr := Mux(io.rs1_id =/= 0.U, true.B, false.B)
            new_csr_val := old_csr_val & (~io.csr_reg_data)
        }
        is(CSR_CMD.RWI.U){
           read_csr := Mux(io.rd_id =/= 0.U, true.B, false.B)
           write_csr := true.B
           new_csr_val := io.csr_reg_data 
        }
        is(CSR_CMD.RSI.U){
            read_csr := true.B
            write_csr := Mux(uimm =/= 0.U, true.B, false.B)
            new_csr_val := old_csr_val | io.csr_reg_data
        }
        is(CSR_CMD.RCI.U){
            read_csr := true.B
            write_csr := Mux(uimm =/= 0.U, true.B, false.B)
            new_csr_val := old_csr_val & (~io.csr_reg_data)
        }
    }
    when(write_csr){
        switch(io.csr_addr){
            is(CSRMAP.printer.U){
                printer := new_csr_val
                if(dumplog){
                    printf(cf"[INFO] printer = 0x${new_csr_val}%x\n")
                }
            }
            is(CSRMAP.misa.U){
                //currently misa is read only
                if(dumplog){
                    printf(cf"[INFO] misa = 0x${misa}%x\n")
                }
            }
            is(CSRMAP.mvendorid.U, CSRMAP.marchid.U, CSRMAP.mimpid.U){
                //currently mvendorid is read only
                if(dumplog){
                    printf(cf"[INFO] unimplemented csr\n")
                }
            }
            is(CSRMAP.mhartid.U){
                //currently mhartid is read only
                if(dumplog){
                    printf(cf"[INFO] mhartid = 0\n")
                }
            }
        }
    }
    io.csr_wdata := old_csr_val
    val priv = RegInit(PrivConst.MACHINE)
    io.priv := priv
    val csr_err_priv = WireDefault(false.B)
    csr_err_priv := ( io.csr_addr(9,8) > priv) || (io.csr_addr(11,10) === "b11".U && write_csr )

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
    val imm_csr = Fill(XLEN-5,0.U) ## io.inst(19,15)
    io.imm := 0.U
    io.imm := MuxLookup(io.type_sel, 0.U)(
        Seq(
            IMM_TYPE.I_Type.U -> immi,
            IMM_TYPE.S_Type.U -> imms,
            IMM_TYPE.B_Type.U -> immb,
            IMM_TYPE.U_Type.U -> immu,
            IMM_TYPE.J_Type.U -> immj,
            IMM_TYPE.CSR_Type.U -> imm_csr
        )
    )
}



class core_in_order extends Module{
    val XLEN = 64
    val dumplog = true
    //IO
    val io = IO(new Bundle{
        val itcm = new Bundle {
            val req_addr = Output(UInt(64.W))
            val resp_addr = Input(UInt(64.W))
            val data = Input(UInt(32.W))
            val can_next = Input(Bool())
        }
        val dtcm = new Bundle {
            val req_addr = Output(UInt(64.W))
            val rdata = Input(UInt(64.W))
            val wt_rd = Output(Bool())
            val mem_valid = Output(Bool())
            val wdata = Output(UInt(64.W))
            val wmask = Output(UInt(8.W))
            val resp_addr = Input(UInt(64.W))
            val can_next = Input(Bool())
        }        
    })
    //initial IO
    io.dtcm.req_addr := 0.U
    io.dtcm.wt_rd := false.B
    io.dtcm.wdata := 0.U
    io.dtcm.wmask := 0.U
    //counter instrctions    
    val id_cnt = RegInit(0.U(64.W))
    val exe_cnt = RegInit(0.U(64.W))
    val mem_cnt = RegInit(0.U(64.W))
    val wb_cnt = RegInit(0.U(64.W)) 
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
    when(io.itcm.can_next && (io.itcm.resp_addr === io.itcm.req_addr)){
        pc := next_pc
    }
    //Fetch
    io.itcm.req_addr := pc 
    class Inst_Buf extends  Bundle {
        val inst = UInt(32.W)
        val pc   = UInt(64.W)
        val buble = Bool()
    }
    val inst_buf = Reg(new Inst_Buf)
    val inst_buf_flush = WireDefault(false.B)
    val fetch_inst_valid = WireDefault(false.B)
    fetch_inst_valid := io.itcm.data(1,0) === "b11".U
    when(reset.asBool || inst_buf_flush){
        //Reset or Flush
        inst_buf.inst := "h00000013".U //nop
        inst_buf.pc := 0.U
        inst_buf.buble := true.B
        id_cnt := 0.U
    }.elsewhen(io.itcm.can_next){
        //Normal fetch
        inst_buf.inst := io.itcm.data
        inst_buf.pc := pc
        inst_buf.buble := false.B
        when(
            io.itcm.resp_addr === io.itcm.req_addr &&
            (
                (io.itcm.resp_addr =/= inst_buf.pc &&
                    io.itcm.data =/= inst_buf.inst) || inst_buf.buble
            )&& fetch_inst_valid
        ){
            id_cnt := id_cnt + 1.U
        }
    }
 
    //decode
    val id = Module(new Decoder())
    id.io.inst := inst_buf.inst
    val rf = Module(new RegFile(64,dumplog))
    val id_rs1_addr = inst_buf.inst(19,15)
    val id_rs2_addr = inst_buf.inst(24,20)
    rf.io.rs1_addr := id_rs1_addr
    rf.io.rs2_addr := id_rs2_addr
    val imm_gen = Module(new ImmGen(64))
    imm_gen.io.inst := inst_buf.inst
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
        exe_reg.pc := 0.U
        exe_reg.rs1_data := 0.U
        exe_reg.rs2_data := 0.U
        exe_reg.rs1_addr := 0.U
        exe_reg.rs2_addr := 0.U
        exe_reg.imm := 0.U
        exe_reg.rd := 0.U
        exe_reg.buble := true.B
        exe_cnt := 0.U
    }.otherwise{
        exe_reg.ctrl := id.io.exe_ctrl
        exe_reg.pc := inst_buf.pc
        exe_reg.rs1_data := rs1_data_id
        exe_reg.rs2_data := rs2_data_id
        exe_reg.rs1_addr := inst_buf.inst(19,15)
        exe_reg.rs2_addr := inst_buf.inst(24,20)
        exe_reg.imm := imm_gen.io.imm
        exe_reg.rd := inst_buf.inst(11,7)
        exe_reg.buble := inst_buf.buble
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
    pc_error_predict := (jau.io.jmp_addr =/= inst_buf.pc) && 
         !inst_buf.buble && (exe_reg.ctrl.redir_inst || 
            exe_reg.ctrl.bru_inst && bru.io.take_branch
        )
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
    }.otherwise{
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
    io.dtcm.req_addr := mem_reg.data
    when(!mem_reg.buble){
        switch(mem_reg.mem_cmd){
            is(MEM_TYPE.SB.U){
                io.dtcm.wt_rd := true.B
                io.dtcm.wdata := Fill(8, mem_reg.rs2(7,0))
                io.dtcm.wmask := "b00000001".U
            }
            is(MEM_TYPE.SH.U){
                io.dtcm.wt_rd := true.B
                io.dtcm.wdata := Fill(4, mem_reg.rs2(15,0))
                io.dtcm.wmask := "b00000011".U
            }
            is(MEM_TYPE.SW.U){
                io.dtcm.wt_rd := true.B
                io.dtcm.wdata := Fill(2, mem_reg.rs2(31,0))
                io.dtcm.wmask := "b00001111".U
            }
            is(MEM_TYPE.LB.U, MEM_TYPE.LH.U, MEM_TYPE.LW.U,
               MEM_TYPE.LBU.U, MEM_TYPE.LHU.U){
                io.dtcm.wt_rd := false.B
            }
        }
    }
    val mem_data_loaded = Wire(UInt(XLEN.W))
    mem_data_loaded := 0.U
    when(!mem_reg.buble){
        switch(mem_reg.mem_cmd){
            is(MEM_TYPE.LB.U){
                mem_data_loaded := Fill(56, io.dtcm.rdata(7)) ## io.dtcm.rdata(7,0)
            }
            is(MEM_TYPE.LH.U){
                mem_data_loaded := Fill(48, io.dtcm.rdata(15)) ## io.dtcm.rdata(15,0)
            }
            is(MEM_TYPE.LW.U){
                mem_data_loaded := Fill(32, io.dtcm.rdata(31)) ## io.dtcm.rdata(31,0)
            }
            is(MEM_TYPE.LBU.U){
                mem_data_loaded := Fill(56, false.B) ## io.dtcm.rdata(7,0)
            }
            is(MEM_TYPE.LHU.U){
                mem_data_loaded := Fill(48, false.B) ## io.dtcm.rdata(15,0)
            }
            is(MEM_TYPE.LWU.U){
                mem_data_loaded := Fill(32, false.B) ## io.dtcm.rdata(31,0)
            }
        }
    }
    io.dtcm.mem_valid := mem_reg.mem_cmd =/= MEM_TYPE.NOT_MEM.U && !mem_reg.buble
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
    }.elsewhen(io.dtcm.can_next && (mem_reg.data === io.dtcm.resp_addr) ||
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
        }.elsewhen(!mem_reg.buble && mem_reg.mem_cmd =/= MEM_TYPE.NOT_MEM.U && io.dtcm.can_next){
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
    /**
     *Debug Signal Dump
     */
    val coretimer = RegInit(0.U(8.W))
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
    printf(cf"[IF]pc=0x${io.itcm.req_addr}%4x")
    when(io.itcm.can_next){
        printf(cf",inst=0x${io.itcm.data}%8x")
    }.otherwise{
        printf(cf",XXXXX=XXXXXXXXX")
    }
    //printf(cf"[ID]pc=${inst_buf.pc}%4x,inst=${inst_buf.inst}%8x,buble=${inst_buf.buble}\n")
    printf(cf"[ID]")
    when(!inst_buf.buble){
        //normal instruction in id stage
        printf(cf"pc=0x${inst_buf.pc}%4x,inst=0x${inst_buf.inst}%8x")
    }.otherwise{
        //one buble
        printf(cf"XXXXXXXXX=buble=XXXXXXXXX")
    }
    printf(cf"[EXE]")
    when(!exe_reg.buble){
        //printf(cf"pc=0x${exe_reg.pc}%4x")
        printf(cf"alu_in1=0x${alu.io.alu_in1}%8x,alu_in2=0x${alu.io.alu_in2}%8x,alu_op=0x${alu.io.alu_op}%4x,alu_out=0x${alu.io.alu_out}%8x" )
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
            printf(cf",csr_data=0x${wb_reg.csr_data}%8x")
            printf(cf",wb_sel=${wb_reg.sel_wb}%2d")
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
object GenerateCoreVerilogFile extends App {
    ChiselStage.emitSystemVerilogFile(
        new core_in_order,
        Array("--target-dir", "build"),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
    )
}
