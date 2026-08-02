package flow.backend

import chisel3._
import chisel3.util._
import flow.config.BackendConfig
import flow.interface._
import flow.core._
import flow.platform.BreezeMcuPlatform

class BreezeBackend(
    val cfg: BackendConfig = BackendConfig(),
    val enabledebug: Boolean = false
) extends Module {
    val io = IO(new Bundle {
        val resetAddr = Input(UInt(cfg.VLEN.W))
        val machineTimerInterrupt = Input(Bool())
        val externalInterrupts = Input(UInt(BreezeMcuPlatform.ExternalInterruptWidth.W))
        val fetchBuffer = Flipped(Decoupled(new FrontendFetchBundle(cfg.VLEN, cfg.ghrLength)))
        val dmem = new BackendMemIO(cfg.VLEN)
        val dcacheFlushReq = Output(Bool())
        val dcacheFlushDone = Input(Bool())
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
    val csrFile = Module(new CSRFile(cfg.VLEN, enabledebug = enabledebug))
    val memWbReg = RegInit(0.U.asTypeOf(new BreezeBackendMEMWB(cfg.VLEN, cfg.enableTandem)))

    val decodeReady = Wire(Bool())
    val decodeFire = Wire(Bool())
    val decodeValid = io.fetchBuffer.valid
    val decodePc = Mux(decodeValid, io.fetchBuffer.bits.pc, 0.U(cfg.VLEN.W))
    val decodeInst = Mux(decodeValid, io.fetchBuffer.bits.inst, nopInst)
    val decodeInstructionAccessFault = decodeValid && io.fetchBuffer.bits.instructionAccessFault
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
    csrFile.io.commit_valid := false.B
    csrFile.io.commit_addr := 0.U
    csrFile.io.commit_wdata := 0.U
    csrFile.io.commit_write_en := false.B
    csrFile.io.retire_valid := memWbReg.valid &&
        !memWbReg.instruction_access_fault &&
        !memWbReg.illegal_inst && !memWbReg.csr_illegal && !memWbReg.is_ecall &&
        !memWbReg.load_addr_misaligned && !memWbReg.store_addr_misaligned &&
        !memWbReg.load_access_fault && !memWbReg.store_access_fault
    csrFile.io.machineTimerInterrupt := io.machineTimerInterrupt
    csrFile.io.machineExternalInterrupt := io.externalInterrupts.orR
    csrFile.io.trap.valid := false.B
    csrFile.io.trap.is_interrupt := false.B
    csrFile.io.trap.cause := 0.U
    csrFile.io.trap.pc := 0.U
    csrFile.io.trap.tval := 0.U
    csrFile.io.mret_commit := false.B

    val wbData = Wire(UInt(cfg.VLEN.W))
    val estopCommitted = Wire(Bool())

    regFile.io.rs1_addr := rs1Addr
    regFile.io.rs2_addr := rs2Addr
    regFile.io.rd_addr := memWbReg.rd_addr
    regFile.io.rd_en := memWbReg.valid && memWbReg.wb_en &&
        !memWbReg.instruction_access_fault &&
        !memWbReg.illegal_inst && !memWbReg.csr_illegal && !memWbReg.is_ecall &&
        !memWbReg.load_addr_misaligned && !memWbReg.store_addr_misaligned &&
        !memWbReg.load_access_fault && !memWbReg.store_access_fault
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
    val decodeUsesRs1 = Wire(Bool())
    val decodeUsesRs2 = Wire(Bool())

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

    decodeUsesRs1 := decoder.io.exe_ctrl.sel_alu1 === SEL_ALU1.RS1.U ||
        decoder.io.exe_ctrl.bru_inst ||
        decoder.io.exe_ctrl.sel_jpc_i === SEL_JPC_I.RS1.U ||
        decoder.io.exe_ctrl.csr_cmd === CSR_CMD.RW.U ||
        decoder.io.exe_ctrl.csr_cmd === CSR_CMD.RS.U ||
        decoder.io.exe_ctrl.csr_cmd === CSR_CMD.RC.U
    decodeUsesRs2 := decoder.io.exe_ctrl.sel_alu2 === SEL_ALU2.RS2.U || decoder.io.exe_ctrl.bru_inst

    val idExeReg = RegInit(0.U.asTypeOf(new BreezeBackendIDEXE(cfg.VLEN, cfg.ghrLength)))
    val actualTaken = Wire(Bool())
    val actualTarget = Wire(UInt(cfg.VLEN.W))
    val redirectDirectionMismatch = Wire(Bool())
    val redirectTargetMismatch = Wire(Bool())
    val redirectNeeded = Wire(Bool())
    val fenceiFlush = Wire(Bool())
    val fenceiPending = Wire(Bool())
    val fenceiFlushIssuedReg = RegInit(false.B)
    val frontendRedirectNeeded = Wire(Bool())
    val predictionMiss = Wire(Bool())
    val pipelineHold = Wire(Bool())
    val csrHold = Wire(Bool())
    val loadUseHazard = Wire(Bool())
    val csrUseHazard = Wire(Bool())
    val csrStateHazard = Wire(Bool())
    val csrRegHazard = Wire(Bool())
    val idExePendingCsrRd = Wire(Bool())
    val exeMemPendingCsrRd = Wire(Bool())
    val memWbPendingCsrRd = Wire(Bool())
    val idExePendingCsrState = Wire(Bool())
    val exeMemPendingCsrState = Wire(Bool())
    val frontendBtbUpdateValid = Wire(Bool())
    val frontendPhtUpdateValid = Wire(Bool())

    val alu = Module(new ALU(cfg.VLEN))
    val bru = Module(new BRU(cfg.VLEN))
    val jau = Module(new JAU(cfg.VLEN))

    when(reset.asBool || frontendRedirectNeeded) {
        idExeReg.valid := false.B
        idExeReg.pc := 0.U
        idExeReg.inst := nopInst
        idExeReg.instruction_access_fault := false.B
        idExeReg.illegal_inst := false.B
        idExeReg.is_ecall := false.B
        idExeReg.is_mret := false.B
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
        idExeReg.ctrl.fencei := false.B
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
        idExeReg.instruction_access_fault := decodeInstructionAccessFault
        idExeReg.illegal_inst := decoder.io.illegal_inst && !decodeInstructionAccessFault
        idExeReg.is_ecall := decoder.io.exe_ctrl.is_ecall && !decodeInstructionAccessFault
        idExeReg.is_mret := decoder.io.exe_ctrl.is_mret && !decodeInstructionAccessFault
        idExeReg.pred := io.fetchBuffer.bits.pred
        idExeReg.ctrl := decoder.io.exe_ctrl
        when(decodeInstructionAccessFault) {
            idExeReg.ctrl.redir_inst := false.B
            idExeReg.ctrl.bru_inst := false.B
            idExeReg.ctrl.mem_cmd := MEM_TYPE.NOT_MEM.U
            idExeReg.ctrl.wb_en := false.B
            idExeReg.ctrl.csr_cmd := CSR_CMD.NOP.U
            idExeReg.ctrl.fencei := false.B
        }
        idExeReg.estop := decodeEstop && !decodeInstructionAccessFault
        idExeReg.rs1_addr := rs1Addr
        idExeReg.rs2_addr := rs2Addr
        idExeReg.rd_addr := rdAddr
        idExeReg.rs1_data := regFile.io.rs1_data
        idExeReg.rs2_data := regFile.io.rs2_data
        idExeReg.imm := immGen.io.imm
        idExeReg.src1 := src1
        idExeReg.src2 := src2
    }.elsewhen(decodeReady || !pipelineHold) {
        idExeReg.valid := false.B
        idExeReg.pc := 0.U
        idExeReg.inst := nopInst
        idExeReg.instruction_access_fault := false.B
        idExeReg.illegal_inst := false.B
        idExeReg.is_ecall := false.B
        idExeReg.is_mret := false.B
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
        idExeReg.ctrl.fencei := false.B
        idExeReg.estop := false.B
        idExeReg.rs1_addr := 0.U
        idExeReg.rs2_addr := 0.U
        idExeReg.rd_addr := 0.U
        idExeReg.rs1_data := 0.U
        idExeReg.rs2_data := 0.U
        idExeReg.imm := 0.U
        idExeReg.src1 := 0.U
        idExeReg.src2 := 0.U
    }.otherwise {
        // ID/EXE is stalled behind a memory operation. Preserve any older
        // producer values that are forwardable now; those producers may have
        // left MEM/WB by the time the stalled instruction is allowed to run.
        idExeReg.rs1_data := exeRs1Data
        idExeReg.rs2_data := exeRs2Data
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
    val memReqSizeLog2 = Wire(UInt(3.W))
    val memAddrMisaligned = Wire(Bool())
    val loadAddrMisaligned = Wire(Bool())
    val storeAddrMisaligned = Wire(Bool())
    val exeMemNeedsDmem = Wire(Bool())
    val exceptionRedirect = Wire(Bool())
    val interruptRedirect = Wire(Bool())
    val pipelineEmpty = Wire(Bool())
    val architecturalNextPc = RegInit(0.U(cfg.VLEN.W))
    val exeNextPc = Wire(UInt(cfg.VLEN.W))
    val mtvecBase = Wire(UInt(cfg.VLEN.W))
    val interruptTrapTarget = Wire(UInt(cfg.VLEN.W))

    mtvecBase := Cat(csrFile.io.mtvec(cfg.VLEN - 1, 2), 0.U(2.W))
    interruptTrapTarget := Mux(
        csrFile.io.mtvec(1, 0) === 1.U,
        mtvecBase + (csrFile.io.interruptCause << 2),
        mtvecBase
    )

    actualTaken := Mux(
        idExeReg.ctrl.bru_inst,
        bru.io.take_branch,
        idExeReg.ctrl.redir_inst
    )
    actualTarget := jau.io.jmp_addr
    exeNextPc := Mux(actualTaken, actualTarget, idExeReg.pc + 4.U)

    redirectDirectionMismatch := idExeReg.valid && !idExeReg.instruction_access_fault &&
        (actualTaken =/= idExeReg.pred.predTaken)
    redirectTargetMismatch := idExeReg.valid && !idExeReg.instruction_access_fault &&
        actualTaken && idExeReg.pred.predTaken &&
        (actualTarget =/= idExeReg.pred.predPc)
    redirectNeeded := redirectDirectionMismatch || redirectTargetMismatch
    val wbTrap = memWbReg.instruction_access_fault || memWbReg.illegal_inst ||
        memWbReg.csr_illegal || memWbReg.is_ecall ||
        memWbReg.load_addr_misaligned || memWbReg.store_addr_misaligned ||
        memWbReg.load_access_fault || memWbReg.store_access_fault
    val mretRedirect = Wire(Bool())
    exceptionRedirect := memWbReg.valid && wbTrap
    mretRedirect := memWbReg.valid && memWbReg.is_mret
    pipelineEmpty := !idExeReg.valid && !exeMemReg.valid &&
        !memWbReg.valid && !memWaitingRespReg
    interruptRedirect := csrFile.io.interruptPending && pipelineEmpty
    frontendRedirectNeeded := fenceiFlush || redirectNeeded || exceptionRedirect ||
        mretRedirect || interruptRedirect
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
        when(idExeReg.valid && !idExeReg.instruction_access_fault) {
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
    memReqSizeLog2 := 0.U
    switch(exeMemReg.mem_cmd) {
        is(MEM_TYPE.LB.U) { memReqSizeLog2 := 0.U; memRspData := Cat(Fill(cfg.VLEN - 8, loadAlignBuf(7)), loadAlignBuf(7, 0)) }
        is(MEM_TYPE.LBU.U) { memReqSizeLog2 := 0.U; memRspData := Cat(0.U((cfg.VLEN - 8).W), loadAlignBuf(7, 0)) }
        is(MEM_TYPE.LH.U) { memReqSizeLog2 := 1.U; memRspData := Cat(Fill(cfg.VLEN - 16, loadAlignBuf(15)), loadAlignBuf(15, 0)) }
        is(MEM_TYPE.LHU.U) { memReqSizeLog2 := 1.U; memRspData := Cat(0.U((cfg.VLEN - 16).W), loadAlignBuf(15, 0)) }
        is(MEM_TYPE.LW.U) { memReqSizeLog2 := 2.U; memRspData := Cat(Fill(cfg.VLEN - 32, loadAlignBuf(31)), loadAlignBuf(31, 0)) }
        is(MEM_TYPE.LWU.U) { memReqSizeLog2 := 2.U; memRspData := Cat(0.U((cfg.VLEN - 32).W), loadAlignBuf(31, 0)) }
        is(MEM_TYPE.LD.U) { memReqSizeLog2 := 3.U; memRspData := io.dmem.rsp.data }
        is(MEM_TYPE.SB.U) {
            memReqSizeLog2 := 0.U
            memReqWData := Fill(8, exeMemReg.rs2_data(7, 0))
            memReqWMask := UIntToOH(memOffset, 8)
        }
        is(MEM_TYPE.SH.U) {
            memReqSizeLog2 := 1.U
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
            memReqSizeLog2 := 2.U
            memReqWData := Fill(2, exeMemReg.rs2_data(31, 0))
            memReqWMask := Mux(memOffset(2), "b11110000".U, "b00001111".U)
        }
        is(MEM_TYPE.SD.U) {
            memReqSizeLog2 := 3.U
            memReqWData := exeMemReg.rs2_data
            memReqWMask := "b11111111".U
        }
    }

    exeRs1Data := idExeReg.rs1_data
    exeRs2Data := idExeReg.rs2_data

    // Forward the older MEM/WB result first. A matching EXE/MEM producer below
    // must win when two in-flight instructions write the same register.
    when(
        memWbReg.valid &&
        memWbReg.wb_en &&
        (memWbReg.rd_addr =/= 0.U) &&
        (memWbReg.wb_sel =/= SEL_WB.CSR.U)
    ) {
        when(idExeReg.rs1_addr === memWbReg.rd_addr) {
            exeRs1Data := wbData
        }
        when(idExeReg.rs2_addr === memWbReg.rd_addr) {
            exeRs2Data := wbData
        }
    }

    when(
        exeMemReg.valid &&
        exeMemReg.wb_en &&
        (exeMemReg.rd_addr =/= 0.U) &&
        (exeMemReg.mem_cmd === MEM_TYPE.NOT_MEM.U) &&
        (exeMemReg.wb_sel === SEL_WB.ALU.U)
    ) {
        when(idExeReg.rs1_addr === exeMemReg.rd_addr) {
            exeRs1Data := exeMemReg.data
        }
        when(idExeReg.rs2_addr === exeMemReg.rd_addr) {
            exeRs2Data := exeMemReg.data
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
    idExePendingCsrRd := idExeReg.valid &&
        idExeReg.ctrl.wb_en &&
        (idExeReg.ctrl.sel_wb === SEL_WB.CSR.U) &&
        (idExeReg.rd_addr =/= 0.U)
    exeMemPendingCsrRd := exeMemReg.valid &&
        exeMemReg.wb_en &&
        (exeMemReg.wb_sel === SEL_WB.CSR.U) &&
        (exeMemReg.rd_addr =/= 0.U)
    memWbPendingCsrRd := memWbReg.valid &&
        memWbReg.wb_en &&
        (memWbReg.wb_sel === SEL_WB.CSR.U) &&
        (memWbReg.rd_addr =/= 0.U)
    csrUseHazard := (
        idExePendingCsrRd && (
            (decodeUsesRs1 && (rs1Addr === idExeReg.rd_addr)) ||
            (decodeUsesRs2 && (rs2Addr === idExeReg.rd_addr))
        )
    ) || (
        exeMemPendingCsrRd && (
            (decodeUsesRs1 && (rs1Addr === exeMemReg.rd_addr)) ||
            (decodeUsesRs2 && (rs2Addr === exeMemReg.rd_addr))
        )
    ) || (
        memWbPendingCsrRd && (
            (decodeUsesRs1 && (rs1Addr === memWbReg.rd_addr)) ||
            (decodeUsesRs2 && (rs2Addr === memWbReg.rd_addr))
        )
    )
    idExePendingCsrState := idExeReg.valid && (idExeReg.ctrl.csr_cmd =/= CSR_CMD.NOP.U)
    exeMemPendingCsrState := exeMemReg.valid && csrFile.io.csr_write_en
    csrStateHazard := (decoder.io.exe_ctrl.csr_cmd =/= CSR_CMD.NOP.U) && (
        (idExePendingCsrState && (decoder.io.exe_ctrl.csr_addr === idExeReg.ctrl.csr_addr)) ||
        (exeMemPendingCsrState && (decoder.io.exe_ctrl.csr_addr === exeMemReg.csr_addr))
    )
    // CSR hazards: only stall decode, NOT idExe→exeMem.
    // CSR producers must flow through to memWb so the register file is updated.
    //
    // csrRegHazard: conservative stall — when a CSR instruction enters decode,
    // if ANY prior instruction in the pipeline (idExe, exeMem, memWb) has a
    // pending register write to rs1/rs2 that the CSR reads, stall until the
    // pipeline is clear. This avoids broken forwarding (CORE-003) and the
    // RegFile synchronous read-before-write race.
    csrRegHazard := (decoder.io.exe_ctrl.csr_cmd =/= CSR_CMD.NOP.U) && (
        (idExeReg.valid && idExeReg.ctrl.wb_en && (idExeReg.rd_addr =/= 0.U) && (
            (decodeUsesRs1 && (rs1Addr === idExeReg.rd_addr)) ||
            (decodeUsesRs2 && (rs2Addr === idExeReg.rd_addr))
        )) ||
        (exeMemReg.valid && exeMemReg.wb_en && (exeMemReg.rd_addr =/= 0.U) && (
            (decodeUsesRs1 && (rs1Addr === exeMemReg.rd_addr)) ||
            (decodeUsesRs2 && (rs2Addr === exeMemReg.rd_addr))
        )) ||
        (memWbReg.valid && memWbReg.wb_en && (memWbReg.rd_addr =/= 0.U) &&
            (memWbReg.wb_sel =/= SEL_WB.CSR.U) && (
            (decodeUsesRs1 && (rs1Addr === memWbReg.rd_addr)) ||
            (decodeUsesRs2 && (rs2Addr === memWbReg.rd_addr))
        ))
    )
    csrHold := csrUseHazard || csrStateHazard || csrRegHazard

    // Hold the pipeline in the request cycle as well, otherwise exeMemReg can be
    // overwritten before the outstanding memory operation receives a response.
    pipelineHold := memReqIssued || (memWaitingRespReg && !io.dmem.rsp.valid) ||
        fenceiPending || loadUseHazard

    csrFile.io.csr_addr := exeMemReg.csr_addr
    csrFile.io.csr_cmd := exeMemReg.csr_cmd
    csrFile.io.csr_reg_data := exeMemReg.data
    csrFile.io.rs1_id := exeMemReg.rs1_addr
    csrFile.io.rd_id := exeMemReg.rd_addr
    csrFile.io.commit_valid := memWbReg.valid
    csrFile.io.commit_addr := memWbReg.csr_addr
    csrFile.io.commit_wdata := memWbReg.csr_new_data
    csrFile.io.commit_write_en := memWbReg.csr_write_en
    // Compute trap cause at WB stage: priority-encode the exception bools
    val mcauseVal = Wire(UInt(cfg.VLEN.W))
    mcauseVal := Mux1H(Seq(
        memWbReg.instruction_access_fault -> BigInt(1).U(cfg.VLEN.W),
        memWbReg.store_addr_misaligned -> BigInt(6).U(cfg.VLEN.W),
        memWbReg.load_addr_misaligned  -> BigInt(4).U(cfg.VLEN.W),
        memWbReg.store_access_fault    -> BigInt(7).U(cfg.VLEN.W),
        memWbReg.load_access_fault     -> BigInt(5).U(cfg.VLEN.W),
        memWbReg.is_ecall              -> BigInt(11).U(cfg.VLEN.W),
        memWbReg.csr_illegal           -> BigInt(2).U(cfg.VLEN.W),
        memWbReg.illegal_inst          -> BigInt(2).U(cfg.VLEN.W),
        true.B                         -> 0.U(cfg.VLEN.W)
    ))

    // Compute trap value at WB stage: faulting address or zero
    val mtvalVal = Wire(UInt(cfg.VLEN.W))
    mtvalVal := Mux1H(Seq(
        memWbReg.instruction_access_fault -> memWbReg.pc,
        memWbReg.store_addr_misaligned -> memWbReg.alu_data,
        memWbReg.load_addr_misaligned  -> memWbReg.alu_data,
        memWbReg.store_access_fault    -> memWbReg.alu_data,
        memWbReg.load_access_fault     -> memWbReg.alu_data,
        memWbReg.is_ecall              -> 0.U(cfg.VLEN.W),
        memWbReg.csr_illegal           -> 0.U(cfg.VLEN.W),
        memWbReg.illegal_inst          -> 0.U(cfg.VLEN.W),
        true.B                         -> 0.U(cfg.VLEN.W)
    ))

    csrFile.io.trap.valid        := exceptionRedirect || interruptRedirect
    csrFile.io.trap.is_interrupt := interruptRedirect
    csrFile.io.trap.cause        := Mux(interruptRedirect, csrFile.io.interruptCause, mcauseVal)
    csrFile.io.trap.pc           := Mux(interruptRedirect, architecturalNextPc, memWbReg.pc)
    csrFile.io.trap.tval         := Mux(interruptRedirect, 0.U, mtvalVal)
    csrFile.io.mret_commit       := memWbReg.valid && memWbReg.is_mret

    when(reset.asBool) {
        architecturalNextPc := io.resetAddr
    }.elsewhen(mretRedirect) {
        architecturalNextPc := csrFile.io.mepc_out
    }.elsewhen(memWbReg.valid && !wbTrap) {
        architecturalNextPc := memWbReg.nextPc
    }

    fenceiPending := exeMemReg.valid && exeMemReg.fencei
    io.dcacheFlushReq := fenceiPending && !fenceiFlushIssuedReg
    fenceiFlush := fenceiPending && fenceiFlushIssuedReg && io.dcacheFlushDone

    when(reset.asBool || fenceiFlush || exceptionRedirect || mretRedirect || interruptRedirect) {
        fenceiFlushIssuedReg := false.B
    }.elsewhen(io.dcacheFlushReq) {
        fenceiFlushIssuedReg := true.B
    }

    when(reset.asBool || fenceiFlush || exceptionRedirect || mretRedirect || interruptRedirect) {
        exeMemReg.valid := false.B
        exeMemReg.pc := 0.U
        exeMemReg.nextPc := 0.U
        exeMemReg.inst := nopInst
        exeMemReg.instruction_access_fault := false.B
        exeMemReg.illegal_inst := false.B
        exeMemReg.is_ecall := false.B
        exeMemReg.is_mret := false.B
        exeMemReg.csr_illegal := false.B
        exeMemReg.pred.predType := FrontendPredType.NONE
        exeMemReg.pred.predTaken := false.B
        exeMemReg.pred.predPc := 0.U
        exeMemReg.pred.phtIdx := 0.U
        exeMemReg.estop := false.B
        exeMemReg.fencei := false.B
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
    }.elsewhen(memRspFire && loadUseHazard) {
        // The completed load has been consumed by MEM/WB, but its dependent
        // instruction must remain in ID/EXE for one more cycle. Insert a bubble
        // here so the completed load cannot be issued again next cycle.
        exeMemReg.valid := false.B
        exeMemReg.trace.foreach(_.valid := false.B)
    }.elsewhen(!pipelineHold) {
        exeMemReg.valid := idExeReg.valid
        exeMemReg.pc := idExeReg.pc
        exeMemReg.nextPc := exeNextPc
        exeMemReg.inst := idExeReg.inst
        exeMemReg.instruction_access_fault := idExeReg.instruction_access_fault
        exeMemReg.illegal_inst := idExeReg.illegal_inst
        exeMemReg.is_ecall := idExeReg.is_ecall
        exeMemReg.is_mret := idExeReg.is_mret
        exeMemReg.csr_illegal := csrFile.io.csr_illegal && !idExeReg.instruction_access_fault
        exeMemReg.pred := idExeReg.pred
        exeMemReg.estop := idExeReg.estop
        exeMemReg.fencei := idExeReg.ctrl.fencei
        exeMemReg.data := alu.io.alu_out
        // Stores need the forwarded rs2 value, especially for an adjacent
        // load-to-store dependency.
        exeMemReg.rs2_data := exeRs2Data
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

    when(reset.asBool || exceptionRedirect || mretRedirect || interruptRedirect) {
        memWbReg.valid := false.B
        memWbReg.pc := 0.U
        memWbReg.nextPc := 0.U
        memWbReg.inst := nopInst
        memWbReg.instruction_access_fault := false.B
        memWbReg.illegal_inst := false.B
        memWbReg.is_ecall := false.B
        memWbReg.is_mret := false.B
        memWbReg.csr_illegal := false.B
        memWbReg.load_addr_misaligned := false.B
        memWbReg.store_addr_misaligned := false.B
        memWbReg.load_access_fault := false.B
        memWbReg.store_access_fault := false.B
        memWbReg.estop := false.B
        memWbReg.wb_en := false.B
        memWbReg.wb_sel := SEL_WB.XXX.U
        memWbReg.rd_addr := 0.U
        memWbReg.alu_data := 0.U
        memWbReg.mem_data := 0.U
        memWbReg.csr_data := 0.U
        memWbReg.csr_addr := 0.U
        memWbReg.csr_new_data := 0.U
        memWbReg.csr_write_en := false.B
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
        // FENCE.I retires only after DCache clean completes and the frontend
        // flush is emitted; do not repeatedly retire it while the cache scans.
        memWbReg.valid := exeMemReg.valid && (!exeMemReg.fencei || fenceiFlush)
        memWbReg.pc := exeMemReg.pc
        memWbReg.nextPc := exeMemReg.nextPc
        memWbReg.inst := exeMemReg.inst
        memWbReg.instruction_access_fault := exeMemReg.instruction_access_fault
        memWbReg.illegal_inst := exeMemReg.illegal_inst
        memWbReg.is_ecall := exeMemReg.is_ecall
        memWbReg.is_mret := exeMemReg.is_mret
        memWbReg.csr_illegal := exeMemReg.csr_illegal
        memWbReg.estop := exeMemReg.estop
        memWbReg.load_addr_misaligned := loadAddrMisaligned
        memWbReg.store_addr_misaligned := storeAddrMisaligned
        memWbReg.load_access_fault := false.B
        memWbReg.store_access_fault := false.B
        memWbReg.wb_en := exeMemReg.wb_en && !memAddrMisaligned && !exeMemReg.instruction_access_fault
        memWbReg.wb_sel := exeMemReg.wb_sel
        memWbReg.rd_addr := exeMemReg.rd_addr
        memWbReg.alu_data := exeMemReg.data
        memWbReg.mem_data := 0.U
        memWbReg.csr_data := csrFile.io.csr_old_data
        memWbReg.csr_addr := exeMemReg.csr_addr
        memWbReg.csr_new_data := csrFile.io.csr_new_data
        memWbReg.csr_write_en := csrFile.io.csr_write_en && !memAddrMisaligned &&
            !exeMemReg.instruction_access_fault
        memWbReg.trace.zip(exeMemReg.trace).foreach { case (wbTrace, exeTrace) =>
            wbTrace := exeTrace
            wbTrace.valid := exeMemReg.valid && (!exeMemReg.fencei || fenceiFlush)
            wbTrace.rdWriteEn := exeTrace.rdWriteEn && !memAddrMisaligned
            wbTrace.rdData := MuxLookup(exeMemReg.wb_sel, 0.U(cfg.VLEN.W))(
                Seq(
                    SEL_WB.ALU.U -> exeMemReg.data,
                    SEL_WB.MEM.U -> 0.U(cfg.VLEN.W),
                    SEL_WB.CSR.U -> csrFile.io.csr_old_data
                )
            )
            wbTrace.memRData := 0.U
            wbTrace.memWData := Mux(exeTrace.memIsWrite, memReqWData, 0.U)
            wbTrace.memWMask := Mux(exeTrace.memIsWrite, memReqWMask, 0.U)
        }
    }.elsewhen(memRspFire) {
        memWbReg.valid := exeMemReg.valid
        memWbReg.pc := exeMemReg.pc
        memWbReg.nextPc := exeMemReg.nextPc
        memWbReg.inst := exeMemReg.inst
        memWbReg.instruction_access_fault := exeMemReg.instruction_access_fault
        memWbReg.illegal_inst := exeMemReg.illegal_inst
        memWbReg.is_ecall := exeMemReg.is_ecall
        memWbReg.is_mret := exeMemReg.is_mret
        memWbReg.csr_illegal := exeMemReg.csr_illegal
        memWbReg.estop := exeMemReg.estop
        memWbReg.load_addr_misaligned := false.B
        memWbReg.store_addr_misaligned := false.B
        memWbReg.load_access_fault := exeMemIsLoad && io.dmem.rsp.error
        memWbReg.store_access_fault := exeMemIsStore && io.dmem.rsp.error
        memWbReg.wb_en := exeMemReg.wb_en && !exeMemReg.instruction_access_fault &&
            !io.dmem.rsp.error
        memWbReg.wb_sel := exeMemReg.wb_sel
        memWbReg.rd_addr := exeMemReg.rd_addr
        memWbReg.alu_data := exeMemReg.data
        memWbReg.mem_data := Mux(exeMemIsLoad && !io.dmem.rsp.error, memRspData, 0.U)
        memWbReg.csr_data := csrFile.io.csr_old_data
        memWbReg.csr_addr := exeMemReg.csr_addr
        memWbReg.csr_new_data := csrFile.io.csr_new_data
        memWbReg.csr_write_en := csrFile.io.csr_write_en &&
            !exeMemReg.instruction_access_fault && !io.dmem.rsp.error
        memWbReg.trace.zip(exeMemReg.trace).foreach { case (wbTrace, exeTrace) =>
            wbTrace := exeTrace
            wbTrace.valid := exeMemReg.valid
            wbTrace.rdWriteEn := exeTrace.rdWriteEn && !io.dmem.rsp.error
            wbTrace.rdData := MuxLookup(exeMemReg.wb_sel, 0.U(cfg.VLEN.W))(
                Seq(
                    SEL_WB.ALU.U -> exeMemReg.data,
                    SEL_WB.MEM.U -> Mux(exeMemIsLoad && !io.dmem.rsp.error, memRspData, 0.U),
                    SEL_WB.CSR.U -> csrFile.io.csr_old_data
                )
            )
            wbTrace.memRData := Mux(exeMemIsLoad && !io.dmem.rsp.error, memRspData, 0.U)
            wbTrace.memWData := Mux(exeTrace.memIsWrite, memReqWData, 0.U)
            wbTrace.memWMask := Mux(exeTrace.memIsWrite, memReqWMask, 0.U)
        }
    }.otherwise {
        // A memory instruction occupies EXE/MEM until its response arrives.
        // Do not leave the previous MEM/WB entry valid during those wait
        // cycles, otherwise one instruction appears to retire repeatedly.
        memWbReg.valid := false.B
        memWbReg.csr_write_en := false.B
        memWbReg.trace.foreach(_.valid := false.B)
    }

    // A pending enabled interrupt stops issue while older instructions drain.
    decodeReady := !pipelineHold && !csrHold && !frontendRedirectNeeded &&
        !csrFile.io.interruptPending
    decodeFire := decodeValid && decodeReady
    io.fetchBuffer.ready := decodeReady

    io.dmem.req.valid := memReqIssued
    io.dmem.req.isWrite := exeMemIsStore
    io.dmem.req.addr := exeMemReg.data
    io.dmem.req.sizeLog2 := memReqSizeLog2
    io.dmem.req.wdata := memReqWData
    io.dmem.req.wmask := memReqWMask

    io.frontendRedirect.valid := frontendRedirectNeeded
    io.frontendRedirect.flush := frontendRedirectNeeded
    io.frontendRedirect.cacheFlush := fenceiFlush
    io.frontendRedirect.target := Mux1H(Seq(
        fenceiFlush        -> (exeMemReg.pc + 4.U),
        mretRedirect       -> csrFile.io.mepc_out,
        interruptRedirect  -> interruptTrapTarget,
        exceptionRedirect  -> mtvecBase,
        redirectNeeded     -> actualTarget
    ))
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
        debug.redirectValid := frontendRedirectNeeded
        debug.csrMtvec       := csrFile.io.mtvec
        debug.csrMcause      := csrFile.io.debug.get.mcause
        debug.csrMepc        := csrFile.io.debug.get.mepc
        debug.memWbException := memWbReg.instruction_access_fault || memWbReg.illegal_inst
        debug.memWbTrapValid := wbTrap || interruptRedirect
        debug.memWbIsEcall := memWbReg.is_ecall
        debug.memWbIsMret := memWbReg.is_mret
        debug.csrIllegal := exeMemReg.csr_illegal
    }
}
