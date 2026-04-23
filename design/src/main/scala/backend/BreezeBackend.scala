package flow.backend

import chisel3._
import chisel3.util._
import flow.config.BackendConfig
import flow.interface._
import flow.core._

class BreezeBackend(
    val cfg: BackendConfig = BackendConfig(),
    val enabledebug: Boolean = false
) extends Module {
    val io = IO(new Bundle {
        val resetAddr = Input(UInt(cfg.VLEN.W))
        val fetchBuffer = Flipped(Decoupled(new FrontendFetchBundle(cfg.VLEN, cfg.ghrLength)))
        val dmem = new BackendMemIO(cfg.VLEN)
        val frontendBtbUpdate = Output(new BreezeBTBUpdateReq(cfg.VLEN))
        val frontendPhtUpdate = Output(new BreezePHTUpdateReq(cfg.ghrLength.max(1)))
        val frontendGhrUpdate = Output(new BreezeGHRUpdateReq)
        val frontendRedirect = Output(new FrontendRedirectIO(cfg.VLEN))
        val estop = Output(Bool())
        val tandem = if (cfg.enableTandem) Some(Output(new TracePayload(cfg.VLEN))) else None
        val debug = if (enabledebug) Some(new BackendDebugIO(cfg.VLEN)) else None
    })

    val nopInst = "h00000013".U(32.W)

    val decoder = Module(new Decoder())
    val immGen = Module(new ImmGen(cfg.VLEN))
    val regFile = Module(new RegFile(cfg.VLEN))
    val csrFile = Module(new CSRFile(cfg.VLEN))
    val memWbReg = RegInit(0.U.asTypeOf(new BreezeBackendMEMWB(cfg.VLEN, cfg.enableTandem)))

    val decodeReady = Wire(Bool())
    val decodeFire = Wire(Bool())
    val decodeValid = io.fetchBuffer.valid
    val decodePc = Mux(decodeValid, io.fetchBuffer.bits.pc, 0.U(cfg.VLEN.W))
    val decodeInst = Mux(decodeValid, io.fetchBuffer.bits.inst, nopInst)
    val decodeEstop = Wire(Bool())

    val rs1Addr = decodeInst(19, 15)
    val rs2Addr = decodeInst(24, 20)
    val rdAddr = decodeInst(11, 7)
    decodeEstop := decodeInst(6, 0) === OPCODE.SYSTEM &&
        decodeInst(14, 12) === 0.U &&
        decodeInst(31, 20) === SIM_SYSTEM.ESTOP_IMM12 &&
        decodeInst(19, 15) === 0.U &&
        decodeInst(11, 7) === 0.U

    decoder.io.inst := decodeInst
    immGen.io.inst := decodeInst
    immGen.io.type_sel := decoder.io.exe_ctrl.sel_imm

    csrFile.io.csr_addr := 0.U
    csrFile.io.csr_cmd := CSR_CMD.NOP.U
    csrFile.io.csr_reg_data := 0.U
    csrFile.io.rs1_id := 0.U
    csrFile.io.rd_id := 0.U
    csrFile.io.core_retire := false.B

    val wbData = Wire(UInt(cfg.VLEN.W))
    val estopCommitted = Wire(Bool())

    regFile.io.rs1_addr := rs1Addr
    regFile.io.rs2_addr := rs2Addr
    regFile.io.rd_addr := memWbReg.rd_addr
    regFile.io.rd_en := memWbReg.valid && memWbReg.wb_en
    wbData := MuxLookup(memWbReg.wb_sel, 0.U(cfg.VLEN.W))(
        Seq(
            SEL_WB.ALU.U -> memWbReg.alu_data,
            SEL_WB.MEM.U -> memWbReg.mem_data,
            SEL_WB.CSR.U -> memWbReg.csr_data
        )
    )
    regFile.io.rd_data := wbData
    estopCommitted := memWbReg.valid && memWbReg.estop

    val src1 = Wire(UInt(cfg.VLEN.W))
    val src2 = Wire(UInt(cfg.VLEN.W))
    val exeRs1Data = Wire(UInt(cfg.VLEN.W))
    val exeRs2Data = Wire(UInt(cfg.VLEN.W))
    val exeSrc1 = Wire(UInt(cfg.VLEN.W))
    val exeSrc2 = Wire(UInt(cfg.VLEN.W))

    src1 := MuxLookup(decoder.io.exe_ctrl.sel_alu1, 0.U(cfg.VLEN.W))(
        Seq(
            SEL_ALU1.RS1.U -> regFile.io.rs1_data,
            SEL_ALU1.PC.U -> decodePc,
            SEL_ALU1.ZERO.U -> 0.U(cfg.VLEN.W)
        )
    )

    src2 := MuxLookup(decoder.io.exe_ctrl.sel_alu2, 0.U(cfg.VLEN.W))(
        Seq(
            SEL_ALU2.RS2.U -> regFile.io.rs2_data,
            SEL_ALU2.IMM.U -> immGen.io.imm,
            SEL_ALU2.CONST4.U -> 4.U(cfg.VLEN.W),
            SEL_ALU2.CONST0.U -> 0.U(cfg.VLEN.W)
        )
    )

    val idExeReg = RegInit(0.U.asTypeOf(new BreezeBackendIDEXE(cfg.VLEN, cfg.ghrLength)))
    val actualTaken = Wire(Bool())
    val actualTarget = Wire(UInt(cfg.VLEN.W))
    val redirectDirectionMismatch = Wire(Bool())
    val redirectTargetMismatch = Wire(Bool())
    val redirectNeeded = Wire(Bool())
    val predictionMiss = Wire(Bool())
    val pipelineHold = Wire(Bool())
    val loadUseHazard = Wire(Bool())
    val frontendBtbUpdateValid = Wire(Bool())
    val frontendPhtUpdateValid = Wire(Bool())

    val alu = Module(new ALU(cfg.VLEN))
    val bru = Module(new BRU(cfg.VLEN))
    val jau = Module(new JAU(cfg.VLEN))

    when(reset.asBool || redirectNeeded) {
        idExeReg.valid := false.B
        idExeReg.pc := 0.U
        idExeReg.inst := nopInst
        idExeReg.illegal_inst := false.B
        idExeReg.pred.predType := FrontendPredType.NONE
        idExeReg.pred.predTaken := false.B
        idExeReg.pred.predPc := 0.U
        idExeReg.pred.phtIdx := 0.U
        idExeReg.ctrl.alu_op := ALU_OP.XXX.U
        idExeReg.ctrl.bru_op := BRU_OP.XXX.U
        idExeReg.ctrl.sel_alu1 := SEL_ALU1.XXX.U
        idExeReg.ctrl.sel_alu2 := SEL_ALU2.XXX.U
        idExeReg.ctrl.sel_jpc_i := SEL_JPC_I.XXX.U
        idExeReg.ctrl.sel_jpc_o := SEL_JPC_O.XXX.U
        idExeReg.ctrl.redir_inst := false.B
        idExeReg.ctrl.bru_inst := false.B
        idExeReg.ctrl.mem_cmd := MEM_TYPE.NOT_MEM.U
        idExeReg.ctrl.sel_wb := SEL_WB.XXX.U
        idExeReg.ctrl.wb_en := false.B
        idExeReg.ctrl.sel_imm := IMM_TYPE.I_Type.U
        idExeReg.ctrl.is_w := false.B
        idExeReg.ctrl.csr_addr := 0.U
        idExeReg.ctrl.csr_cmd := CSR_CMD.NOP.U
        idExeReg.estop := false.B
        idExeReg.rs1_addr := 0.U
        idExeReg.rs2_addr := 0.U
        idExeReg.rd_addr := 0.U
        idExeReg.rs1_data := 0.U
        idExeReg.rs2_data := 0.U
        idExeReg.imm := 0.U
        idExeReg.src1 := 0.U
        idExeReg.src2 := 0.U
    }.elsewhen(decodeFire) {
        idExeReg.valid := true.B
        idExeReg.pc := decodePc
        idExeReg.inst := decodeInst
        idExeReg.illegal_inst := decoder.io.illegal_inst
        idExeReg.pred := io.fetchBuffer.bits.pred
        idExeReg.ctrl := decoder.io.exe_ctrl
        idExeReg.estop := decodeEstop
        idExeReg.rs1_addr := rs1Addr
        idExeReg.rs2_addr := rs2Addr
        idExeReg.rd_addr := rdAddr
        idExeReg.rs1_data := regFile.io.rs1_data
        idExeReg.rs2_data := regFile.io.rs2_data
        idExeReg.imm := immGen.io.imm
        idExeReg.src1 := src1
        idExeReg.src2 := src2
    }.elsewhen(decodeReady) {
        idExeReg.valid := false.B
        idExeReg.pc := 0.U
        idExeReg.inst := nopInst
        idExeReg.illegal_inst := false.B
        idExeReg.pred.predType := FrontendPredType.NONE
        idExeReg.pred.predTaken := false.B
        idExeReg.pred.predPc := 0.U
        idExeReg.pred.phtIdx := 0.U
        idExeReg.ctrl.alu_op := ALU_OP.XXX.U
        idExeReg.ctrl.bru_op := BRU_OP.XXX.U
        idExeReg.ctrl.sel_alu1 := SEL_ALU1.XXX.U
        idExeReg.ctrl.sel_alu2 := SEL_ALU2.XXX.U
        idExeReg.ctrl.sel_jpc_i := SEL_JPC_I.XXX.U
        idExeReg.ctrl.sel_jpc_o := SEL_JPC_O.XXX.U
        idExeReg.ctrl.redir_inst := false.B
        idExeReg.ctrl.bru_inst := false.B
        idExeReg.ctrl.mem_cmd := MEM_TYPE.NOT_MEM.U
        idExeReg.ctrl.sel_wb := SEL_WB.XXX.U
        idExeReg.ctrl.wb_en := false.B
        idExeReg.ctrl.sel_imm := IMM_TYPE.I_Type.U
        idExeReg.ctrl.is_w := false.B
        idExeReg.ctrl.csr_addr := 0.U
        idExeReg.ctrl.csr_cmd := CSR_CMD.NOP.U
        idExeReg.estop := false.B
        idExeReg.rs1_addr := 0.U
        idExeReg.rs2_addr := 0.U
        idExeReg.rd_addr := 0.U
        idExeReg.rs1_data := 0.U
        idExeReg.rs2_data := 0.U
        idExeReg.imm := 0.U
        idExeReg.src1 := 0.U
        idExeReg.src2 := 0.U
    }

    alu.io.alu_op := idExeReg.ctrl.alu_op
    alu.io.alu_in1 := exeSrc1
    alu.io.alu_in2 := exeSrc2
    alu.io.is_w := idExeReg.ctrl.is_w

    bru.io.bru_op := idExeReg.ctrl.bru_op
    bru.io.rs1_data := exeRs1Data
    bru.io.rs2_data := exeRs2Data

    jau.io.sel_jpc_i := idExeReg.ctrl.sel_jpc_i
    jau.io.sel_jpc_o := idExeReg.ctrl.sel_jpc_o
    jau.io.pc := idExeReg.pc
    jau.io.rs1_data := exeRs1Data
    jau.io.imm := idExeReg.imm

    val exeMemReg = RegInit(0.U.asTypeOf(new BreezeBackendEXEMEM(cfg.VLEN, cfg.ghrLength, cfg.enableTandem)))
    val memWaitingRespReg = RegInit(false.B)
    val memReqIssued = Wire(Bool())
    val memRspFire = Wire(Bool())
    val exeMemIsMem = Wire(Bool())
    val exeMemIsLoad = Wire(Bool())
    val exeMemIsStore = Wire(Bool())
    val memBaseAddr = Wire(UInt(cfg.VLEN.W))
    val memOffset = Wire(UInt(3.W))
    val memRspData = Wire(UInt(cfg.VLEN.W))
    val loadAlignBuf = Wire(UInt(64.W))
    val memReqWData = Wire(UInt(64.W))
    val memReqWMask = Wire(UInt(8.W))
    val memAddrMisaligned = Wire(Bool())
    val loadAddrMisaligned = Wire(Bool())
    val storeAddrMisaligned = Wire(Bool())
    val exeMemNeedsDmem = Wire(Bool())
    val exeNextPc = Wire(UInt(cfg.VLEN.W))

    actualTaken := Mux(
        idExeReg.ctrl.bru_inst,
        bru.io.take_branch,
        idExeReg.ctrl.redir_inst
    )
    actualTarget := jau.io.jmp_addr
    exeNextPc := Mux(actualTaken, actualTarget, idExeReg.pc + 4.U)

    redirectDirectionMismatch := idExeReg.valid && (actualTaken =/= idExeReg.pred.predTaken)
    redirectTargetMismatch := idExeReg.valid && actualTaken && idExeReg.pred.predTaken &&
        (actualTarget =/= idExeReg.pred.predPc)
    redirectNeeded := redirectDirectionMismatch || redirectTargetMismatch
    predictionMiss := redirectNeeded

    io.frontendBtbUpdate.valid := false.B
    io.frontendBtbUpdate.pc := 0.U
    io.frontendBtbUpdate.target := 0.U
    io.frontendBtbUpdate.predType := FrontendPredType.NONE
    io.frontendBtbUpdate.taken := false.B

    io.frontendPhtUpdate.valid := false.B
    io.frontendPhtUpdate.idx := 0.U
    io.frontendPhtUpdate.taken := false.B
    io.frontendGhrUpdate.valid := false.B
    io.frontendGhrUpdate.taken := false.B

    frontendBtbUpdateValid := false.B
    frontendPhtUpdateValid := false.B

    if (cfg.branchPredKind == flow.config.FrontendBranchPredictorKind.GShare) {
        when(idExeReg.valid) {
            switch(idExeReg.pred.predType) {
                is(FrontendPredType.BR) {
                    frontendBtbUpdateValid := true.B
                    frontendPhtUpdateValid := true.B
                    io.frontendBtbUpdate.pc := idExeReg.pc
                    io.frontendBtbUpdate.target := actualTarget
                    io.frontendBtbUpdate.predType := FrontendPredType.BR
                    io.frontendBtbUpdate.taken := actualTaken
                    io.frontendPhtUpdate.idx := idExeReg.pred.phtIdx
                    io.frontendPhtUpdate.taken := actualTaken
                    io.frontendGhrUpdate.valid := true.B
                    io.frontendGhrUpdate.taken := actualTaken
                }
                is(FrontendPredType.JAL) {
                    frontendBtbUpdateValid := true.B
                    io.frontendBtbUpdate.pc := idExeReg.pc
                    io.frontendBtbUpdate.target := actualTarget
                    io.frontendBtbUpdate.predType := FrontendPredType.JAL
                    io.frontendBtbUpdate.taken := true.B
                }
                is(FrontendPredType.JALR) {
                    when(predictionMiss) {
                        frontendBtbUpdateValid := true.B
                        io.frontendBtbUpdate.pc := idExeReg.pc
                        io.frontendBtbUpdate.target := actualTarget
                        io.frontendBtbUpdate.predType := FrontendPredType.JALR
                        io.frontendBtbUpdate.taken := true.B
                    }
                }
            }
        }
    }

    io.frontendBtbUpdate.valid := frontendBtbUpdateValid
    io.frontendPhtUpdate.valid := frontendPhtUpdateValid

    exeMemIsMem := exeMemReg.valid && (exeMemReg.mem_cmd =/= MEM_TYPE.NOT_MEM.U)
    exeMemIsLoad := exeMemIsMem && (
        exeMemReg.mem_cmd === MEM_TYPE.LB.U ||
        exeMemReg.mem_cmd === MEM_TYPE.LBU.U ||
        exeMemReg.mem_cmd === MEM_TYPE.LH.U ||
        exeMemReg.mem_cmd === MEM_TYPE.LHU.U ||
        exeMemReg.mem_cmd === MEM_TYPE.LW.U ||
        exeMemReg.mem_cmd === MEM_TYPE.LWU.U ||
        exeMemReg.mem_cmd === MEM_TYPE.LD.U
    )
    exeMemIsStore := exeMemIsMem && (
        exeMemReg.mem_cmd === MEM_TYPE.SB.U ||
        exeMemReg.mem_cmd === MEM_TYPE.SH.U ||
        exeMemReg.mem_cmd === MEM_TYPE.SW.U ||
        exeMemReg.mem_cmd === MEM_TYPE.SD.U
    )
    memAddrMisaligned := MuxLookup(exeMemReg.mem_cmd, false.B)(
        Seq(
            MEM_TYPE.LH.U -> exeMemReg.data(0),
            MEM_TYPE.LHU.U -> exeMemReg.data(0),
            MEM_TYPE.SH.U -> exeMemReg.data(0),
            MEM_TYPE.LW.U -> exeMemReg.data(1, 0).orR,
            MEM_TYPE.LWU.U -> exeMemReg.data(1, 0).orR,
            MEM_TYPE.SW.U -> exeMemReg.data(1, 0).orR,
            MEM_TYPE.LD.U -> exeMemReg.data(2, 0).orR,
            MEM_TYPE.SD.U -> exeMemReg.data(2, 0).orR
        )
    )
    loadAddrMisaligned := exeMemIsLoad && memAddrMisaligned
    storeAddrMisaligned := exeMemIsStore && memAddrMisaligned
    exeMemNeedsDmem := exeMemIsMem && !memAddrMisaligned
    memReqIssued := exeMemNeedsDmem && !memWaitingRespReg
    memRspFire := memWaitingRespReg && io.dmem.rsp.valid
    memBaseAddr := (exeMemReg.data >> 3.U) << 3.U
    memOffset := exeMemReg.data(2, 0)
    loadAlignBuf := (io.dmem.rsp.data >> (memOffset << 3.U))(63, 0)
    memRspData := 0.U
    memReqWData := 0.U
    memReqWMask := 0.U
    switch(exeMemReg.mem_cmd) {
        is(MEM_TYPE.LB.U) { memRspData := Cat(Fill(cfg.VLEN - 8, loadAlignBuf(7)), loadAlignBuf(7, 0)) }
        is(MEM_TYPE.LBU.U) { memRspData := Cat(0.U((cfg.VLEN - 8).W), loadAlignBuf(7, 0)) }
        is(MEM_TYPE.LH.U) { memRspData := Cat(Fill(cfg.VLEN - 16, loadAlignBuf(15)), loadAlignBuf(15, 0)) }
        is(MEM_TYPE.LHU.U) { memRspData := Cat(0.U((cfg.VLEN - 16).W), loadAlignBuf(15, 0)) }
        is(MEM_TYPE.LW.U) { memRspData := Cat(Fill(cfg.VLEN - 32, loadAlignBuf(31)), loadAlignBuf(31, 0)) }
        is(MEM_TYPE.LWU.U) { memRspData := Cat(0.U((cfg.VLEN - 32).W), loadAlignBuf(31, 0)) }
        is(MEM_TYPE.LD.U) { memRspData := io.dmem.rsp.data }
        is(MEM_TYPE.SB.U) {
            memReqWData := Fill(8, exeMemReg.rs2_data(7, 0))
            memReqWMask := UIntToOH(memOffset, 8)
        }
        is(MEM_TYPE.SH.U) {
            memReqWData := Fill(4, exeMemReg.rs2_data(15, 0))
            memReqWMask := MuxLookup(memOffset(2, 1), 0.U(8.W))(
                Seq(
                    "b00".U -> "b00000011".U,
                    "b01".U -> "b00001100".U,
                    "b10".U -> "b00110000".U,
                    "b11".U -> "b11000000".U
                )
            )
        }
        is(MEM_TYPE.SW.U) {
            memReqWData := Fill(2, exeMemReg.rs2_data(31, 0))
            memReqWMask := Mux(memOffset(2), "b11110000".U, "b00001111".U)
        }
        is(MEM_TYPE.SD.U) {
            memReqWData := exeMemReg.rs2_data
            memReqWMask := "b11111111".U
        }
    }

    exeRs1Data := idExeReg.rs1_data
    exeRs2Data := idExeReg.rs2_data

    when(
        exeMemReg.valid &&
        exeMemReg.wb_en &&
        (exeMemReg.rd_addr =/= 0.U) &&
        (exeMemReg.mem_cmd === MEM_TYPE.NOT_MEM.U)
    ) {
        when(idExeReg.rs1_addr === exeMemReg.rd_addr) {
            exeRs1Data := exeMemReg.data
        }
        when(idExeReg.rs2_addr === exeMemReg.rd_addr) {
            exeRs2Data := exeMemReg.data
        }
    }

    when(
        memWbReg.valid &&
        memWbReg.wb_en &&
        (memWbReg.rd_addr =/= 0.U)
    ) {
        when(idExeReg.rs1_addr === memWbReg.rd_addr) {
            exeRs1Data := wbData
        }
        when(idExeReg.rs2_addr === memWbReg.rd_addr) {
            exeRs2Data := wbData
        }
    }

    exeSrc1 := MuxLookup(idExeReg.ctrl.sel_alu1, 0.U(cfg.VLEN.W))(
        Seq(
            SEL_ALU1.RS1.U -> exeRs1Data,
            SEL_ALU1.PC.U -> idExeReg.pc,
            SEL_ALU1.ZERO.U -> 0.U(cfg.VLEN.W)
        )
    )

    exeSrc2 := MuxLookup(idExeReg.ctrl.sel_alu2, 0.U(cfg.VLEN.W))(
        Seq(
            SEL_ALU2.RS2.U -> exeRs2Data,
            SEL_ALU2.IMM.U -> idExeReg.imm,
            SEL_ALU2.CONST4.U -> 4.U(cfg.VLEN.W),
            SEL_ALU2.CONST0.U -> 0.U(cfg.VLEN.W)
        )
    )

    loadUseHazard := exeMemReg.valid && memWaitingRespReg && exeMemIsLoad && exeMemReg.wb_en && (
        (idExeReg.rs1_addr =/= 0.U && idExeReg.rs1_addr === exeMemReg.rd_addr) ||
        (idExeReg.rs2_addr =/= 0.U && idExeReg.rs2_addr === exeMemReg.rd_addr)
    )
    // Hold the pipeline in the request cycle as well, otherwise exeMemReg can be
    // overwritten before the outstanding memory operation receives a response.
    pipelineHold := memReqIssued || (memWaitingRespReg && !io.dmem.rsp.valid) || loadUseHazard

    csrFile.io.csr_addr := exeMemReg.csr_addr
    csrFile.io.csr_cmd := exeMemReg.csr_cmd
    csrFile.io.csr_reg_data := exeMemReg.data
    csrFile.io.rs1_id := exeMemReg.rs1_addr
    csrFile.io.rd_id := exeMemReg.rd_addr

    when(reset.asBool) {
        exeMemReg.valid := false.B
        exeMemReg.pc := 0.U
        exeMemReg.inst := nopInst
        exeMemReg.illegal_inst := false.B
        exeMemReg.pred.predType := FrontendPredType.NONE
        exeMemReg.pred.predTaken := false.B
        exeMemReg.pred.predPc := 0.U
        exeMemReg.pred.phtIdx := 0.U
        exeMemReg.estop := false.B
        exeMemReg.data := 0.U
        exeMemReg.rs2_data := 0.U
        exeMemReg.mem_cmd := MEM_TYPE.NOT_MEM.U
        exeMemReg.rd_addr := 0.U
        exeMemReg.rs1_addr := 0.U
        exeMemReg.csr_addr := 0.U
        exeMemReg.csr_cmd := CSR_CMD.NOP.U
        exeMemReg.wb_en := false.B
        exeMemReg.wb_sel := SEL_WB.XXX.U
        exeMemReg.actual_taken := false.B
        exeMemReg.actual_target := 0.U
        exeMemReg.trace.foreach { trace =>
            trace.valid := false.B
            trace.pc := 0.U
            trace.inst := 0.U
            trace.nextPc := 0.U
            trace.estop := false.B
            trace.rdWriteEn := false.B
            trace.rdAddr := 0.U
            trace.rdData := 0.U
            trace.memEn := false.B
            trace.memIsWrite := false.B
            trace.memAddr := 0.U
            trace.memAlignedAddr := 0.U
            trace.memRData := 0.U
            trace.memWData := 0.U
            trace.memWMask := 0.U
        }
    }.elsewhen(!pipelineHold) {
        exeMemReg.valid := idExeReg.valid
        exeMemReg.pc := idExeReg.pc
        exeMemReg.inst := idExeReg.inst
        exeMemReg.illegal_inst := idExeReg.illegal_inst
        exeMemReg.pred := idExeReg.pred
        exeMemReg.estop := idExeReg.estop
        exeMemReg.data := alu.io.alu_out
        exeMemReg.rs2_data := idExeReg.rs2_data
        exeMemReg.mem_cmd := idExeReg.ctrl.mem_cmd
        exeMemReg.rd_addr := idExeReg.rd_addr
        exeMemReg.rs1_addr := idExeReg.rs1_addr
        exeMemReg.csr_addr := idExeReg.ctrl.csr_addr
        exeMemReg.csr_cmd := idExeReg.ctrl.csr_cmd
        exeMemReg.wb_en := idExeReg.ctrl.wb_en
        exeMemReg.wb_sel := idExeReg.ctrl.sel_wb
        exeMemReg.actual_taken := actualTaken
        exeMemReg.actual_target := actualTarget
        exeMemReg.trace.foreach { trace =>
            trace.valid := idExeReg.valid
            trace.pc := idExeReg.pc
            trace.inst := idExeReg.inst
            trace.nextPc := exeNextPc
            trace.estop := idExeReg.estop
            trace.rdWriteEn := idExeReg.ctrl.wb_en && (idExeReg.rd_addr =/= 0.U)
            trace.rdAddr := idExeReg.rd_addr
            trace.rdData := 0.U
            trace.memEn := idExeReg.ctrl.mem_cmd =/= MEM_TYPE.NOT_MEM.U
            trace.memIsWrite := idExeReg.ctrl.mem_cmd === MEM_TYPE.SB.U ||
                idExeReg.ctrl.mem_cmd === MEM_TYPE.SH.U ||
                idExeReg.ctrl.mem_cmd === MEM_TYPE.SW.U ||
                idExeReg.ctrl.mem_cmd === MEM_TYPE.SD.U
            trace.memAddr := alu.io.alu_out
            trace.memAlignedAddr := (alu.io.alu_out >> 3.U) << 3.U
            trace.memRData := 0.U
            trace.memWData := 0.U
            trace.memWMask := 0.U
        }
    }

    when(reset.asBool) {
        memWaitingRespReg := false.B
    }.elsewhen(memRspFire) {
        memWaitingRespReg := false.B
    }.elsewhen(memReqIssued) {
        memWaitingRespReg := true.B
    }

    when(reset.asBool) {
        memWbReg.valid := false.B
        memWbReg.pc := 0.U
        memWbReg.inst := nopInst
        memWbReg.illegal_inst := false.B
        memWbReg.load_addr_misaligned := false.B
        memWbReg.store_addr_misaligned := false.B
        memWbReg.estop := false.B
        memWbReg.wb_en := false.B
        memWbReg.wb_sel := SEL_WB.XXX.U
        memWbReg.rd_addr := 0.U
        memWbReg.alu_data := 0.U
        memWbReg.mem_data := 0.U
        memWbReg.csr_data := 0.U
        memWbReg.trace.foreach { trace =>
            trace.valid := false.B
            trace.pc := 0.U
            trace.inst := 0.U
            trace.nextPc := 0.U
            trace.estop := false.B
            trace.rdWriteEn := false.B
            trace.rdAddr := 0.U
            trace.rdData := 0.U
            trace.memEn := false.B
            trace.memIsWrite := false.B
            trace.memAddr := 0.U
            trace.memAlignedAddr := 0.U
            trace.memRData := 0.U
            trace.memWData := 0.U
            trace.memWMask := 0.U
        }
    }.elsewhen(!exeMemReg.valid || !exeMemNeedsDmem) {
        memWbReg.valid := exeMemReg.valid
        memWbReg.pc := exeMemReg.pc
        memWbReg.inst := exeMemReg.inst
        memWbReg.illegal_inst := exeMemReg.illegal_inst
        memWbReg.estop := exeMemReg.estop
        memWbReg.load_addr_misaligned := loadAddrMisaligned
        memWbReg.store_addr_misaligned := storeAddrMisaligned
        memWbReg.wb_en := exeMemReg.wb_en && !memAddrMisaligned
        memWbReg.wb_sel := exeMemReg.wb_sel
        memWbReg.rd_addr := exeMemReg.rd_addr
        memWbReg.alu_data := exeMemReg.data
        memWbReg.mem_data := 0.U
        memWbReg.csr_data := csrFile.io.csr_wdata
        memWbReg.trace.zip(exeMemReg.trace).foreach { case (wbTrace, exeTrace) =>
            wbTrace := exeTrace
            wbTrace.valid := exeMemReg.valid
            wbTrace.rdWriteEn := exeTrace.rdWriteEn && !memAddrMisaligned
            wbTrace.rdData := MuxLookup(exeMemReg.wb_sel, 0.U(cfg.VLEN.W))(
                Seq(
                    SEL_WB.ALU.U -> exeMemReg.data,
                    SEL_WB.MEM.U -> 0.U(cfg.VLEN.W),
                    SEL_WB.CSR.U -> csrFile.io.csr_wdata
                )
            )
            wbTrace.memRData := 0.U
            wbTrace.memWData := Mux(wbTrace.memIsWrite, memReqWData, 0.U)
            wbTrace.memWMask := Mux(wbTrace.memIsWrite, memReqWMask, 0.U)
        }
    }.elsewhen(memRspFire) {
        memWbReg.valid := exeMemReg.valid
        memWbReg.pc := exeMemReg.pc
        memWbReg.inst := exeMemReg.inst
        memWbReg.illegal_inst := exeMemReg.illegal_inst
        memWbReg.estop := exeMemReg.estop
        memWbReg.load_addr_misaligned := false.B
        memWbReg.store_addr_misaligned := false.B
        memWbReg.wb_en := exeMemReg.wb_en
        memWbReg.wb_sel := exeMemReg.wb_sel
        memWbReg.rd_addr := exeMemReg.rd_addr
        memWbReg.alu_data := exeMemReg.data
        memWbReg.mem_data := Mux(exeMemIsLoad, memRspData, 0.U)
        memWbReg.csr_data := csrFile.io.csr_wdata
        memWbReg.trace.zip(exeMemReg.trace).foreach { case (wbTrace, exeTrace) =>
            wbTrace := exeTrace
            wbTrace.valid := exeMemReg.valid
            wbTrace.rdData := MuxLookup(exeMemReg.wb_sel, 0.U(cfg.VLEN.W))(
                Seq(
                    SEL_WB.ALU.U -> exeMemReg.data,
                    SEL_WB.MEM.U -> Mux(exeMemIsLoad, memRspData, 0.U),
                    SEL_WB.CSR.U -> csrFile.io.csr_wdata
                )
            )
            wbTrace.memRData := Mux(exeMemIsLoad, memRspData, 0.U)
            wbTrace.memWData := Mux(wbTrace.memIsWrite, memReqWData, 0.U)
            wbTrace.memWMask := Mux(wbTrace.memIsWrite, memReqWMask, 0.U)
        }
    }

    decodeReady := !pipelineHold && !redirectNeeded
    decodeFire := decodeValid && decodeReady
    io.fetchBuffer.ready := decodeReady

    io.dmem.req.valid := memReqIssued
    io.dmem.req.isWrite := exeMemIsStore
    io.dmem.req.addr := memBaseAddr
    io.dmem.req.wdata := memReqWData
    io.dmem.req.wmask := memReqWMask

    io.frontendRedirect.valid := redirectNeeded
    io.frontendRedirect.flush := redirectNeeded
    io.frontendRedirect.target := Mux(redirectNeeded, actualTarget, io.resetAddr)
    io.estop := estopCommitted
    io.tandem.zip(memWbReg.trace).foreach { case (tandem, trace) =>
        tandem := trace
    }

    io.debug.foreach { debug =>
        debug.decodeValid := decodeValid
        debug.decodeInst := decodeInst
        debug.decodePc := decodePc
        debug.idExeValid := idExeReg.valid
        debug.idExeInst := idExeReg.inst
        debug.idExePc := idExeReg.pc
        debug.idExeRs1Addr := idExeReg.rs1_addr
        debug.idExeRs2Addr := idExeReg.rs2_addr
        debug.idExeSrc1 := idExeReg.src1
        debug.idExeSrc2 := idExeReg.src2
        debug.exeSrc1 := exeSrc1
        debug.exeSrc2 := exeSrc2
        debug.exeAluOut := alu.io.alu_out
        debug.exeBruTaken := bru.io.take_branch
        debug.exeJumpAddr := jau.io.jmp_addr
        debug.exeMemValid := exeMemReg.valid
        debug.exeMemPc := exeMemReg.pc
        debug.exeMemData := exeMemReg.data
        debug.exeMemRdAddr := exeMemReg.rd_addr
        debug.memWaitingResp := memWaitingRespReg
        debug.memWbValid := memWbReg.valid
        debug.memWbPc := memWbReg.pc
        debug.memWbInst := memWbReg.inst
        debug.wbData := wbData
        debug.exeBypassRs1 := exeRs1Data
        debug.exeBypassRs2 := exeRs2Data
        debug.loadUseHazard := loadUseHazard
        debug.redirectValid := redirectNeeded
    }
}
