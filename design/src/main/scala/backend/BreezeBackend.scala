package flow.backend

import chisel3._
import chisel3.util._
import flow.config.BackendConfig
import flow.interface._
import flow.core._
import flow.divider.RiscvDivUnit
import flow.multiplier.RiscvMulUnit
import flow.fpu._
import flow.platform.BreezeMcuPlatform

class BreezeBackend(
    val cfg: BackendConfig = BackendConfig(),
    val enabledebug: Boolean = false,
    val hartId: Int = 0
) extends Module {
    require(cfg.VLEN == 64, "RV64 M-extension backend requires VLEN=64")
    require(hartId >= 0, "backend hartId must be non-negative")
    val io = IO(new Bundle {
        val resetAddr = Input(UInt(cfg.VLEN.W))
        val machineTimerInterrupt = Input(Bool())
        val machineSoftwareInterrupt = Input(Bool())
        val time = Input(UInt(cfg.VLEN.W))
        val externalInterrupts = Input(UInt(BreezeMcuPlatform.ExternalInterruptWidth.W))
        val supervisorExternalInterrupt = Input(Bool())
        val fetchBuffer = Flipped(Decoupled(new FrontendFetchBundle(cfg.VLEN, cfg.ghrLength)))
        val dmem = new BackendMemIO(cfg.VLEN)
        val dcacheFlushReq = Output(Bool())
        val dcacheFlushDone = Input(Bool())
        val hpmEvents = Input(new BreezeHpmEvents)
        val frontendBtbUpdate = Output(new BreezeBTBUpdateReq(cfg.VLEN))
        val frontendPhtUpdate = Output(new BreezePHTUpdateReq(cfg.ghrLength.max(1)))
        val frontendGhrUpdate = Output(new BreezeGHRUpdateReq)
        val frontendRedirect = Output(new FrontendRedirectIO(cfg.VLEN))
        val mmuContext = Output(new BreezeMmuContext(cfg.VLEN))
        val sfence = Output(new BreezeSfenceReq(cfg.VLEN))
        // One-cycle pulse on every taken trap (exception or interrupt); the
        // D$ clears its LR/SC reservation on it.
        val reservationKill = Output(Bool())
        val estop = Output(Bool())
        val tandem = if (cfg.enableTandem) Some(Output(new TracePayload(cfg.VLEN))) else None
        val debug = if (enabledebug) Some(new BackendDebugIO(cfg.VLEN)) else None
    })

    val nopInst = "h00000013".U(32.W)

    val decoder = Module(new Decoder())
    val fpDecoder = Module(new BreezeFpDecoder)
    val immGen = Module(new ImmGen(cfg.VLEN))
    val regFile = Module(new RegFile(cfg.VLEN))
    val fpRegFile = Module(new BreezeFpRegFile)
    val csrFile = Module(new CSRFile(cfg.VLEN, enabledebug = enabledebug,
        hartId = hartId, privilegeProfile = cfg.privilegeProfile,
        enableCompressed = cfg.enableCompressed))
    val memWbReg = RegInit(0.U.asTypeOf(new BreezeBackendMEMWB(cfg.VLEN, cfg.enableTandem)))
    val retireValid = Wire(Bool())

    val decodeReady = Wire(Bool())
    val decodeFire = Wire(Bool())
    val decodeValid = io.fetchBuffer.valid
    val decodePc = Mux(decodeValid, io.fetchBuffer.bits.pc, 0.U(cfg.VLEN.W))
    val decodeInst = Mux(decodeValid, io.fetchBuffer.bits.inst, nopInst)
    val decodeRawInst = Mux(decodeValid, io.fetchBuffer.bits.rawInst, nopInst)
    // The architectural instruction length is either 2 (RV64C) or 4 bytes.
    // Treat every other value as 4 so legacy producers, reset bubbles, and
    // malformed metadata can never create a zero-length sequential PC.
    val decodeInstLen = Mux(decodeValid && io.fetchBuffer.bits.instLen === 2.U, 2.U(3.W), 4.U(3.W))
    val decodeInstructionAccessFault = decodeValid && io.fetchBuffer.bits.instructionAccessFault
    val decodeInstructionPageFault = decodeValid && io.fetchBuffer.bits.instructionPageFault
    val decodeInstructionFault = decodeInstructionAccessFault || decodeInstructionPageFault
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
    fpDecoder.io.inst := decodeInst
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
    csrFile.io.sret_commit := false.B
    csrFile.io.fp_commit_valid := false.B
    csrFile.io.fp_flags := 0.U
    retireValid := memWbReg.valid &&
        !memWbReg.instruction_access_fault && !memWbReg.instruction_page_fault &&
        !memWbReg.illegal_inst && !memWbReg.csr_illegal &&
        !memWbReg.is_ecall && !memWbReg.is_ebreak &&
        !memWbReg.load_addr_misaligned && !memWbReg.store_addr_misaligned &&
        !memWbReg.load_access_fault && !memWbReg.store_access_fault &&
        !memWbReg.load_page_fault && !memWbReg.store_page_fault
    csrFile.io.retire_valid := retireValid
    csrFile.io.hpmEvents := io.hpmEvents
    val retiredOpcode = memWbReg.inst(6, 0)
    val retiredControl = retiredOpcode === OPCODE.BRANCH ||
        retiredOpcode === OPCODE.JAL || retiredOpcode === OPCODE.JALR
    csrFile.io.hpmEvents.controlRetired := retireValid && retiredControl
    csrFile.io.hpmEvents.controlTaken := retireValid && retiredControl &&
        memWbReg.nextPc =/= (memWbReg.pc + memWbReg.instLen)
    csrFile.io.hpmEvents.predictionMiss := retireValid && retiredControl &&
        memWbReg.prediction_miss
    csrFile.io.machineTimerInterrupt := io.machineTimerInterrupt
    csrFile.io.machineSoftwareInterrupt := io.machineSoftwareInterrupt
    csrFile.io.time := io.time
    csrFile.io.machineExternalInterrupt := io.externalInterrupts.orR
    csrFile.io.supervisorExternalInterrupt := io.supervisorExternalInterrupt
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
        !memWbReg.instruction_access_fault && !memWbReg.instruction_page_fault &&
        !memWbReg.illegal_inst && !memWbReg.csr_illegal &&
        !memWbReg.is_ecall && !memWbReg.is_ebreak &&
        !memWbReg.load_addr_misaligned && !memWbReg.store_addr_misaligned &&
        !memWbReg.load_access_fault && !memWbReg.store_access_fault &&
        !memWbReg.load_page_fault && !memWbReg.store_page_fault
    wbData := MuxLookup(memWbReg.wb_sel, 0.U(cfg.VLEN.W))(
        Seq(
            SEL_WB.ALU.U -> memWbReg.alu_data,
            SEL_WB.MEM.U -> memWbReg.mem_data,
            SEL_WB.CSR.U -> memWbReg.csr_data,
            SEL_WB.MUL.U -> memWbReg.mul_data
        )
    )
    regFile.io.rd_data := wbData
    val memWbFpWrite = RegInit(false.B)
    val memWbFpData = RegInit(0.U(64.W))
    val memWbFpFlagsValid = RegInit(false.B)
    val memWbFpFlags = RegInit(0.U(5.W))
    fpRegFile.io.rs1Addr := rs1Addr
    fpRegFile.io.rs2Addr := rs2Addr
    fpRegFile.io.rs3Addr := decodeInst(31, 27)
    fpRegFile.io.rdAddr := memWbReg.rd_addr
    fpRegFile.io.rdData := memWbFpData
    fpRegFile.io.rdEn := memWbReg.valid && memWbFpWrite &&
        !memWbReg.instruction_access_fault && !memWbReg.instruction_page_fault &&
        !memWbReg.illegal_inst &&
        !memWbReg.load_addr_misaligned && !memWbReg.load_access_fault &&
        !memWbReg.load_page_fault
    estopCommitted := memWbReg.valid && memWbReg.estop

    val src1 = Wire(UInt(cfg.VLEN.W))
    val src2 = Wire(UInt(cfg.VLEN.W))
    val exeRs1Data = Wire(UInt(cfg.VLEN.W))
    val exeRs2Data = Wire(UInt(cfg.VLEN.W))
    val exeSrc1 = Wire(UInt(cfg.VLEN.W))
    val exeSrc2 = Wire(UInt(cfg.VLEN.W))
    val mulOperandA = Wire(SInt(65.W))
    val mulOperandB = Wire(SInt(65.W))
    val divEffectiveDividend = Wire(UInt(64.W))
    val divEffectiveDivisor = Wire(UInt(64.W))
    val divDividendMag = Wire(UInt(64.W))
    val divDivisorMag = Wire(UInt(64.W))
    val divQuotientNeg = Wire(Bool())
    val divRemainderNeg = Wire(Bool())
    val divIsSigned = Wire(Bool())
    val divIsRemainder = Wire(Bool())
    val divIsWord = Wire(Bool())
    val divFast = Wire(Bool())
    val divFastResult = Wire(UInt(64.W))
    val decodeUsesRs1 = Wire(Bool())
    val decodeUsesRs2 = Wire(Bool())
    val fpImmediate = Wire(UInt(64.W))
    val fpLocalResult = Wire(UInt(64.W))
    val fpRegHazard = Wire(Bool())
    val fpCsrHazard = Wire(Bool())

    val idFpCtrl = RegInit(0.U.asTypeOf(new BreezeFpCtrl))
    val idFpOperand1 = RegInit(0.U(64.W))
    val idFpOperand2 = RegInit(0.U(64.W))
    val idFpOperand3 = RegInit(0.U(64.W))
    val exeFpCtrl = RegInit(0.U.asTypeOf(new BreezeFpCtrl))
    val exeFpOperand1 = RegInit(0.U(64.W))
    val exeFpOperand2 = RegInit(0.U(64.W))
    val exeFpOperand3 = RegInit(0.U(64.W))
    val exeFpRm = RegInit(0.U(3.W))

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
            SEL_ALU2.CONST4.U -> decodeInstLen,
            SEL_ALU2.CONST0.U -> 0.U(cfg.VLEN.W)
        )
    )

    decodeUsesRs1 := decoder.io.exe_ctrl.sel_alu1 === SEL_ALU1.RS1.U ||
        decoder.io.exe_ctrl.bru_inst ||
        decoder.io.exe_ctrl.is_sfence_vma ||
        decoder.io.exe_ctrl.sel_jpc_i === SEL_JPC_I.RS1.U ||
        decoder.io.exe_ctrl.csr_cmd === CSR_CMD.RW.U ||
        decoder.io.exe_ctrl.csr_cmd === CSR_CMD.RS.U ||
        decoder.io.exe_ctrl.csr_cmd === CSR_CMD.RC.U
    // Stores and AMOs use rs2 as write data even though the ALU's second
    // operand is an immediate/constant. Keep that architectural source in the
    // dependency model so a CSR/load result is not sampled one value early.
    decodeUsesRs2 := decoder.io.exe_ctrl.sel_alu2 === SEL_ALU2.RS2.U ||
        decoder.io.exe_ctrl.bru_inst || decoder.io.exe_ctrl.is_sfence_vma ||
        decoder.io.exe_ctrl.mem_op === BreezeMemOp.Store ||
        decoder.io.exe_ctrl.mem_op === BreezeMemOp.Amo

    val idExeReg = RegInit(0.U.asTypeOf(new BreezeBackendIDEXE(cfg.VLEN, cfg.ghrLength)))
    fpImmediate := Mux(
        idFpCtrl.isStore,
        Cat(Fill(52, idExeReg.inst(31)), idExeReg.inst(31, 25), idExeReg.inst(11, 7)),
        Cat(Fill(52, idExeReg.inst(31)), idExeReg.inst(31, 20))
    )
    fpLocalResult := Mux(
        idFpCtrl.localOp === BreezeFpLocalOp.FMV_X.U,
        Mux(idFpCtrl.isDouble, idFpOperand1,
            Cat(Fill(32, idFpOperand1(31)), idFpOperand1(31, 0))),
        Mux(idFpCtrl.isDouble, exeRs1Data, Cat("hffffffff".U(32.W), exeRs1Data(31, 0)))
    )

    val actualTaken = Wire(Bool())
    val actualTarget = Wire(UInt(cfg.VLEN.W))
    val redirectDirectionMismatch = Wire(Bool())
    val redirectTargetMismatch = Wire(Bool())
    val redirectNeeded = Wire(Bool())
    val fenceiFlush = Wire(Bool())
    val fenceiPending = Wire(Bool())
    val fenceiFlushIssuedReg = RegInit(false.B)
    val frontendRedirectNeeded = Wire(Bool())
    val wfiCommit = Wire(Bool())
    val sfenceExecute = Wire(Bool())
    val satpCommit = Wire(Bool())
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
        idExeReg.rawInst := nopInst
        idExeReg.instLen := 4.U
        idExeReg.instruction_access_fault := false.B
        idExeReg.instruction_page_fault := false.B
        idExeReg.illegal_inst := false.B
        idExeReg.is_ecall := false.B
        idExeReg.is_ebreak := false.B
        idExeReg.is_mret := false.B
        idExeReg.is_sret := false.B
        idExeReg.is_wfi := false.B
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
        idExeReg.ctrl.mul_valid := false.B
        idExeReg.ctrl.mul_op := MUL_OP.XXX.U
        idExeReg.ctrl.div_valid := false.B
        idExeReg.ctrl.div_op := DIV_OP.XXX.U
        idExeReg.ctrl.csr_addr := 0.U
        idExeReg.ctrl.csr_cmd := CSR_CMD.NOP.U
        idExeReg.ctrl.fencei := false.B
        idExeReg.ctrl.mem_op := BreezeMemOp.Load
        idExeReg.ctrl.amo_func := BreezeAmoFunc.Swap
        idExeReg.ctrl.amo_aq := false.B
        idExeReg.ctrl.amo_rl := false.B
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
        idExeReg.rawInst := decodeRawInst
        idExeReg.instLen := decodeInstLen
        idExeReg.instruction_access_fault := decodeInstructionAccessFault
        idExeReg.instruction_page_fault := decodeInstructionPageFault
        idExeReg.illegal_inst := (io.fetchBuffer.bits.illegalCompressed ||
            (decoder.io.illegal_inst && !fpDecoder.io.ctrl.valid) ||
            (fpDecoder.io.ctrl.valid && !csrFile.io.fp_enabled) ||
            (decoder.io.exe_ctrl.is_mret && csrFile.io.mret_illegal) ||
            (decoder.io.exe_ctrl.is_sret && csrFile.io.sret_illegal) ||
            (decoder.io.exe_ctrl.is_wfi && csrFile.io.wfi_illegal) ||
            (decoder.io.exe_ctrl.is_sfence_vma && csrFile.io.sfence_vma_illegal)) &&
            !decodeInstructionFault
        idExeReg.is_ecall := decoder.io.exe_ctrl.is_ecall && !decodeInstructionFault
        idExeReg.is_ebreak := decoder.io.exe_ctrl.is_ebreak && !decodeInstructionFault
        idExeReg.is_mret := decoder.io.exe_ctrl.is_mret && !csrFile.io.mret_illegal &&
            !decodeInstructionFault
        idExeReg.is_sret := decoder.io.exe_ctrl.is_sret && !csrFile.io.sret_illegal &&
            !decodeInstructionFault
        idExeReg.is_wfi := decoder.io.exe_ctrl.is_wfi && !csrFile.io.wfi_illegal &&
            !decodeInstructionFault
        idExeReg.pred := io.fetchBuffer.bits.pred
        idExeReg.ctrl := decoder.io.exe_ctrl
        when(decodeInstructionFault) {
            idExeReg.ctrl.redir_inst := false.B
            idExeReg.ctrl.bru_inst := false.B
            idExeReg.ctrl.mem_cmd := MEM_TYPE.NOT_MEM.U
            idExeReg.ctrl.wb_en := false.B
            idExeReg.ctrl.mul_valid := false.B
            idExeReg.ctrl.div_valid := false.B
            idExeReg.ctrl.csr_cmd := CSR_CMD.NOP.U
            idExeReg.ctrl.fencei := false.B
            idExeReg.ctrl.mem_op := BreezeMemOp.Load
        }
        idExeReg.estop := decodeEstop && !decodeInstructionFault
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
        idExeReg.rawInst := nopInst
        idExeReg.instLen := 4.U
        idExeReg.instruction_access_fault := false.B
        idExeReg.instruction_page_fault := false.B
        idExeReg.illegal_inst := false.B
        idExeReg.is_ecall := false.B
        idExeReg.is_ebreak := false.B
        idExeReg.is_mret := false.B
        idExeReg.is_sret := false.B
        idExeReg.is_wfi := false.B
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
        idExeReg.ctrl.mul_valid := false.B
        idExeReg.ctrl.mul_op := MUL_OP.XXX.U
        idExeReg.ctrl.div_valid := false.B
        idExeReg.ctrl.div_op := DIV_OP.XXX.U
        idExeReg.ctrl.csr_addr := 0.U
        idExeReg.ctrl.csr_cmd := CSR_CMD.NOP.U
        idExeReg.ctrl.fencei := false.B
        idExeReg.ctrl.mem_op := BreezeMemOp.Load
        idExeReg.ctrl.amo_func := BreezeAmoFunc.Swap
        idExeReg.ctrl.amo_aq := false.B
        idExeReg.ctrl.amo_rl := false.B
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

    // Floating-point decode and operands follow exactly the same validity and
    // hold conditions as ID/EXE.  Keeping them as sideband registers avoids
    // perturbing the established integer pipeline bundle.
    when(reset.asBool || frontendRedirectNeeded) {
        idFpCtrl := 0.U.asTypeOf(new BreezeFpCtrl)
        idFpOperand1 := 0.U
        idFpOperand2 := 0.U
        idFpOperand3 := 0.U
    }.elsewhen(decodeFire) {
        idFpCtrl := fpDecoder.io.ctrl
        when(decodeInstructionFault ||
            (fpDecoder.io.ctrl.valid && !csrFile.io.fp_enabled)) {
            idFpCtrl := 0.U.asTypeOf(new BreezeFpCtrl)
        }
        idFpOperand1 := fpRegFile.io.rs1Data
        idFpOperand2 := fpRegFile.io.rs2Data
        idFpOperand3 := fpRegFile.io.rs3Data
    }.elsewhen(decodeReady || !pipelineHold) {
        idFpCtrl := 0.U.asTypeOf(new BreezeFpCtrl)
        idFpOperand1 := 0.U
        idFpOperand2 := 0.U
        idFpOperand3 := 0.U
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
    // RV64A sideband carried alongside exeMemReg (the EXEMEM bundle layout in
    // interface.scala is frozen; these registers follow exactly the same
    // reset/flush/advance conditions as exeMemReg below).
    val exeMemMemOp = RegInit(BreezeMemOp.Load)
    val exeMemAmoFunc = RegInit(BreezeAmoFunc.Swap)
    val exeMemAq = RegInit(false.B)
    val exeMemRl = RegInit(false.B)
    val memWaitingRespReg = RegInit(false.B)
    val mulWaitingRespReg = RegInit(false.B)
    val divWaitingRespReg = RegInit(false.B)
    val fpWaitingRespReg = RegInit(false.B)
    val mulUnit = Module(new RiscvMulUnit)
    val divUnit = Module(new RiscvDivUnit)
    val fpUnit = Module(new BreezeFpUnit)
    val memReqIssued = Wire(Bool())
    val memRspFire = Wire(Bool())
    val mulReqIssued = Wire(Bool())
    val mulRspFire = Wire(Bool())
    val divReqIssued = Wire(Bool())
    val divRspFire = Wire(Bool())
    val divFastCompletion = Wire(Bool())
    val fpReqIssued = Wire(Bool())
    val fpReqAccepted = Wire(Bool())
    val fpRspFire = Wire(Bool())
    val exeMemIsMem = Wire(Bool())
    val exeMemIsLoad = Wire(Bool())
    val exeMemIsStore = Wire(Bool())
    val exeMemIsMul = Wire(Bool())
    val exeMemIsDiv = Wire(Bool())
    val exeMemIsFp = Wire(Bool())
    val memBaseAddr = Wire(UInt(cfg.VLEN.W))
    val memOffset = Wire(UInt(3.W))
    val memRspData = Wire(UInt(cfg.VLEN.W))
    val completionValid = Wire(Bool())
    val completionRd = Wire(UInt(5.W))
    val completionData = Wire(UInt(cfg.VLEN.W))
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
    val wfiSleepingReg = RegInit(false.B)
    val wfiInFlight = Wire(Bool())
    val exeNextPc = Wire(UInt(cfg.VLEN.W))

    actualTaken := Mux(
        idExeReg.ctrl.bru_inst,
        bru.io.take_branch,
        idExeReg.ctrl.redir_inst
    )
    actualTarget := jau.io.jmp_addr
    exeNextPc := Mux(actualTaken, actualTarget, idExeReg.pc + idExeReg.instLen)

    // ID/EXE remains valid while an older memory operation holds the pipeline.
    // Do not resolve or train a branch until that instruction can advance: a
    // dependent branch may still contain the pre-load register value here.
    redirectDirectionMismatch := idExeReg.valid && !pipelineHold &&
        !idExeReg.instruction_access_fault && !idExeReg.instruction_page_fault &&
        (actualTaken =/= idExeReg.pred.predTaken)
    redirectTargetMismatch := idExeReg.valid && !pipelineHold &&
        !idExeReg.instruction_access_fault && !idExeReg.instruction_page_fault &&
        actualTaken && idExeReg.pred.predTaken &&
        (actualTarget =/= idExeReg.pred.predPc)
    redirectNeeded := redirectDirectionMismatch || redirectTargetMismatch
    val wbTrap = memWbReg.instruction_access_fault || memWbReg.instruction_page_fault ||
        memWbReg.illegal_inst ||
        memWbReg.csr_illegal || memWbReg.is_ecall || memWbReg.is_ebreak ||
        memWbReg.load_addr_misaligned || memWbReg.store_addr_misaligned ||
        memWbReg.load_access_fault || memWbReg.store_access_fault ||
        memWbReg.load_page_fault || memWbReg.store_page_fault
    wfiCommit := memWbReg.valid && memWbReg.is_wfi && !wbTrap
    wfiInFlight := (idExeReg.valid && idExeReg.is_wfi) ||
        (exeMemReg.valid && exeMemReg.is_wfi) ||
        (memWbReg.valid && memWbReg.is_wfi)

    // WFI retires exactly once, after every older instruction. Stop decode as
    // soon as it enters the pipeline so no younger side effect can issue.
    // Wakeup deliberately ignores global xIE; interruptPending remains the
    // separate decision to take a trap.
    when(reset.asBool) {
        wfiSleepingReg := false.B
    }.elsewhen(csrFile.io.wfiWakeup) {
        wfiSleepingReg := false.B
    }.elsewhen(wfiCommit) {
        wfiSleepingReg := true.B
    }
    when(wfiCommit) {
        assert(!idExeReg.valid && !exeMemReg.valid,
            "[BreezeBackend] younger instruction remained in flight at WFI commit")
    }
    val mretRedirect = Wire(Bool())
    val sretRedirect = Wire(Bool())
    val xretRedirect = Wire(Bool())
    exceptionRedirect := memWbReg.valid && wbTrap
    mretRedirect := memWbReg.valid && memWbReg.is_mret
    sretRedirect := memWbReg.valid && memWbReg.is_sret
    xretRedirect := mretRedirect || sretRedirect
    sfenceExecute := idExeReg.valid && idExeReg.ctrl.is_sfence_vma &&
        !pipelineHold && !idExeReg.illegal_inst && !idExeReg.instruction_access_fault &&
        !idExeReg.instruction_page_fault
    satpCommit := memWbReg.valid && memWbReg.csr_write_en &&
        memWbReg.csr_addr === CSRMAP.satp.U && !memWbReg.csr_illegal
    pipelineEmpty := !idExeReg.valid && !exeMemReg.valid &&
        !memWbReg.valid && !memWaitingRespReg && !mulWaitingRespReg &&
        !divWaitingRespReg && !fpWaitingRespReg
    interruptRedirect := csrFile.io.interruptPending && pipelineEmpty
    frontendRedirectNeeded := fenceiFlush || sfenceExecute || satpCommit ||
        redirectNeeded || exceptionRedirect || xretRedirect || interruptRedirect || wfiCommit
    predictionMiss := redirectNeeded

    // Train BTB in the cycle after EXE resolution. Keep redirect/PHT/GHR
    // resolution in EXE; only the BTB request crosses this register boundary.
    val exeBtbUpdate = Wire(new BreezeBTBUpdateReq(cfg.VLEN))
    val memBtbUpdate = RegInit(0.U.asTypeOf(new BreezeBTBUpdateReq(cfg.VLEN)))
    // These events cancel younger work. A branch's own redirectNeeded must
    // not cancel its training, nor a younger branch cancel a pending update.
    val btbOlderKill = exceptionRedirect || xretRedirect || satpCommit ||
        interruptRedirect || wfiCommit || fenceiFlush

    exeBtbUpdate.valid := false.B
    exeBtbUpdate.pc := 0.U
    exeBtbUpdate.target := 0.U
    exeBtbUpdate.predType := FrontendPredType.NONE
    exeBtbUpdate.taken := false.B

    io.frontendPhtUpdate.valid := false.B
    io.frontendPhtUpdate.idx := 0.U
    io.frontendPhtUpdate.taken := false.B
    io.frontendGhrUpdate.valid := false.B
    io.frontendGhrUpdate.taken := false.B

    frontendBtbUpdateValid := false.B
    frontendPhtUpdateValid := false.B

    if (cfg.branchPredKind == flow.config.FrontendBranchPredictorKind.GShare) {
        when(idExeReg.valid && !pipelineHold && !idExeReg.instruction_access_fault &&
            !idExeReg.instruction_page_fault) {
            switch(idExeReg.pred.predType) {
                is(FrontendPredType.BR) {
                    frontendBtbUpdateValid := true.B
                    frontendPhtUpdateValid := true.B
                    exeBtbUpdate.pc := idExeReg.pc
                    exeBtbUpdate.target := actualTarget
                    exeBtbUpdate.predType := FrontendPredType.BR
                    exeBtbUpdate.taken := actualTaken
                    io.frontendPhtUpdate.idx := idExeReg.pred.phtIdx
                    io.frontendPhtUpdate.taken := actualTaken
                    io.frontendGhrUpdate.valid := true.B
                    io.frontendGhrUpdate.taken := actualTaken
                }
                is(FrontendPredType.JAL) {
                    frontendBtbUpdateValid := true.B
                    exeBtbUpdate.pc := idExeReg.pc
                    exeBtbUpdate.target := actualTarget
                    exeBtbUpdate.predType := FrontendPredType.JAL
                    exeBtbUpdate.taken := true.B
                }
                is(FrontendPredType.JALR) {
                    when(predictionMiss) {
                        frontendBtbUpdateValid := true.B
                        exeBtbUpdate.pc := idExeReg.pc
                        exeBtbUpdate.target := actualTarget
                        exeBtbUpdate.predType := FrontendPredType.JALR
                        exeBtbUpdate.taken := true.B
                    }
                }
            }
        }
    }

    exeBtbUpdate.valid := frontendBtbUpdateValid
    // Consume each request once, even if MEM stalls. With no BTB backpressure,
    // consecutive branches can replace the request every cycle.
    memBtbUpdate.valid := exeBtbUpdate.valid && !btbOlderKill
    when(exeBtbUpdate.valid && !btbOlderKill) {
        memBtbUpdate.pc := exeBtbUpdate.pc
        memBtbUpdate.target := exeBtbUpdate.target
        memBtbUpdate.predType := exeBtbUpdate.predType
        memBtbUpdate.taken := exeBtbUpdate.taken
    }
    io.frontendBtbUpdate := memBtbUpdate
    io.frontendBtbUpdate.valid := memBtbUpdate.valid && !btbOlderKill && !reset.asBool

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
    exeMemIsMul := exeMemReg.valid && exeMemReg.wb_en && exeMemReg.mul_valid &&
        !exeMemReg.instruction_access_fault && !exeMemReg.instruction_page_fault
    exeMemIsDiv := exeMemReg.valid && exeMemReg.wb_en && exeMemReg.div_valid &&
        !exeMemReg.instruction_access_fault && !exeMemReg.instruction_page_fault
    exeMemIsFp := exeMemReg.valid && exeFpCtrl.fpuValid &&
        !exeMemReg.instruction_access_fault && !exeMemReg.instruction_page_fault &&
        !exeMemReg.illegal_inst
    // LR/AMO carry a load-type mem_cmd (LW/LD) and SC a store-type one
    // (SW/SD), so exeMemIsLoad/exeMemIsStore include them; the wires below
    // single the atomics out where the classification differs.
    val exeMemIsLr = exeMemIsMem && exeMemMemOp === BreezeMemOp.Lr
    val exeMemIsSc = exeMemIsMem && exeMemMemOp === BreezeMemOp.Sc
    val exeMemIsAmo = exeMemIsMem && exeMemMemOp === BreezeMemOp.Amo
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
    // A misaligned AMO is a store/AMO address-misaligned (cause 6), not a
    // load one, even though its mem_cmd is load-typed for the data path.
    loadAddrMisaligned := exeMemIsLoad && memAddrMisaligned && !exeMemIsAmo
    storeAddrMisaligned := (exeMemIsStore || exeMemIsAmo) && memAddrMisaligned
    exeMemNeedsDmem := exeMemIsMem && !memAddrMisaligned
    memReqIssued := exeMemNeedsDmem && !memWaitingRespReg
    memRspFire := memWaitingRespReg && io.dmem.rsp.valid
    mulReqIssued := exeMemIsMul && !mulWaitingRespReg &&
        !exceptionRedirect && !xretRedirect && !interruptRedirect && !fenceiFlush
    mulRspFire := mulWaitingRespReg && mulUnit.io.out_valid
    mulUnit.io.flush := frontendRedirectNeeded
    mulUnit.io.in_valid := mulReqIssued
    mulUnit.io.a := exeMemReg.mul_a
    mulUnit.io.b := exeMemReg.mul_b
    mulUnit.io.op := exeMemReg.mul_op
    divFastCompletion := exeMemIsDiv && exeMemReg.div_fast
    divReqIssued := exeMemIsDiv && !exeMemReg.div_fast && !divWaitingRespReg &&
        !exceptionRedirect && !xretRedirect && !interruptRedirect && !fenceiFlush
    divRspFire := divWaitingRespReg && divUnit.io.out_valid
    divUnit.io.flush := frontendRedirectNeeded
    divUnit.io.in_valid := divReqIssued
    divUnit.io.dividend_mag := exeMemReg.div_dividend_mag
    divUnit.io.divisor_mag := exeMemReg.div_divisor_mag
    divUnit.io.quotient_neg := exeMemReg.div_quotient_neg
    divUnit.io.remainder_neg := exeMemReg.div_remainder_neg
    divUnit.io.is_remainder := exeMemReg.div_is_remainder
    divUnit.io.is_word := exeMemReg.div_is_word
    fpReqIssued := exeMemIsFp && !fpWaitingRespReg &&
        !exceptionRedirect && !xretRedirect && !interruptRedirect && !fenceiFlush
    fpReqAccepted := fpReqIssued && fpUnit.io.inReady
    fpRspFire := fpWaitingRespReg && fpUnit.io.outValid
    // A younger branch may resolve in the same cycle that an older FP result
    // completes and is forwarded.  Do not feed that redirect back into FPnew:
    // the older result must commit before the younger redirect takes effect.
    fpUnit.io.flush := reset.asBool || fenceiFlush || exceptionRedirect ||
        xretRedirect || interruptRedirect
    fpUnit.io.inValid := fpReqIssued
    fpUnit.io.outReady := fpWaitingRespReg
    fpUnit.io.operandA := exeFpOperand1
    fpUnit.io.operandB := exeFpOperand2
    fpUnit.io.operandC := exeFpOperand3
    fpUnit.io.rm := exeFpRm
    fpUnit.io.operation := exeFpCtrl.operation
    fpUnit.io.opMod := exeFpCtrl.opMod
    fpUnit.io.srcFmt := exeFpCtrl.srcFmt
    fpUnit.io.dstFmt := exeFpCtrl.dstFmt
    fpUnit.io.intFmt := exeFpCtrl.intFmt
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
    when(exeMemIsAmo) {
        // AMO carries the raw rs2 operand; the D$ positions it inside the
        // line itself and ignores the byte mask.
        memReqWData := exeMemReg.rs2_data
        memReqWMask := 0.U
    }
    // SC writes back its raw 0/1 result; LR/AMO responses are the aligned
    // 64-bit old word and go through the regular load extraction above.
    val memCompletionData = Mux(exeMemIsSc, io.dmem.rsp.data, memRspData)

    // All register-producing, unknown/fixed-latency backends converge here.
    // This is both the MEM completion result and the highest-priority EXE bypass.
    completionValid := (memRspFire && (exeMemIsLoad || exeMemIsSc) &&
        exeMemReg.wb_en && !io.dmem.rsp.error) ||
        (mulRspFire && exeMemReg.wb_en) || divFastCompletion ||
        (divRspFire && exeMemReg.wb_en) ||
        (fpRspFire && exeFpCtrl.writesGpr)
    completionRd := exeMemReg.rd_addr
    completionData := MuxCase(memCompletionData, Seq(
        mulRspFire -> mulUnit.io.result,
        divFastCompletion -> exeMemReg.div_fast_result,
        divRspFire -> divUnit.io.result,
        fpRspFire -> fpUnit.io.result
    ))
    assert(PopCount(Seq(memRspFire, mulRspFire, divFastCompletion, divRspFire, fpRspFire)) <= 1.U,
        "[BreezeBackend] multiple long-latency backends completed in one cycle")

    exeRs1Data := idExeReg.rs1_data
    exeRs2Data := idExeReg.rs2_data

    // Forward the older MEM/WB result first. A matching EXE/MEM producer below
    // must win when two in-flight instructions write the same register.
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

    // A completing load/multiply is younger than MEM/WB and must win the
    // forwarding priority.  The held ID/EXE instruction can advance in this
    // same cycle without an extra load-use/multiply-use bubble.
    when(completionValid && (completionRd =/= 0.U)) {
        when(idExeReg.rs1_addr === completionRd) {
            exeRs1Data := completionData
        }
        when(idExeReg.rs2_addr === completionRd) {
            exeRs2Data := completionData
        }
    }

    // SignedMul65x65 always consumes signed 65-bit values.  Sign/zero
    // extension here encodes each RISC-V multiplication variant.
    mulOperandA := Cat(exeRs1Data(63), exeRs1Data).asSInt
    mulOperandB := Cat(exeRs2Data(63), exeRs2Data).asSInt
    switch(idExeReg.ctrl.mul_op) {
        is(MUL_OP.MULHSU.U) {
            mulOperandB := Cat(0.U(1.W), exeRs2Data).asSInt
        }
        is(MUL_OP.MULHU.U) {
            mulOperandA := Cat(0.U(1.W), exeRs1Data).asSInt
            mulOperandB := Cat(0.U(1.W), exeRs2Data).asSInt
        }
        is(MUL_OP.MULW.U) {
            mulOperandA := Cat(Fill(33, exeRs1Data(31)), exeRs1Data(31, 0)).asSInt
            mulOperandB := Cat(Fill(33, exeRs2Data(31)), exeRs2Data(31, 0)).asSInt
        }
    }

    // Division preprocessing is entirely in EXE.  The iterative unit only
    // sees unsigned magnitudes; architectural divide-by-zero and signed
    // overflow are converted into a one-cycle MEM completion.
    divIsSigned := !idExeReg.ctrl.div_op(0)
    divIsRemainder := idExeReg.ctrl.div_op(1)
    divIsWord := idExeReg.ctrl.div_op(2)
    val divWordDividend = Mux(
        divIsSigned,
        Cat(Fill(32, exeRs1Data(31)), exeRs1Data(31, 0)),
        Cat(0.U(32.W), exeRs1Data(31, 0))
    )
    val divWordDivisor = Mux(
        divIsSigned,
        Cat(Fill(32, exeRs2Data(31)), exeRs2Data(31, 0)),
        Cat(0.U(32.W), exeRs2Data(31, 0))
    )
    divEffectiveDividend := Mux(divIsWord, divWordDividend, exeRs1Data)
    divEffectiveDivisor := Mux(divIsWord, divWordDivisor, exeRs2Data)

    val divDividendNeg = divIsSigned && divEffectiveDividend(63)
    val divDivisorNeg = divIsSigned && divEffectiveDivisor(63)
    divDividendMag := Mux(
        divDividendNeg,
        0.U(64.W) - divEffectiveDividend,
        divEffectiveDividend
    )
    divDivisorMag := Mux(
        divDivisorNeg,
        0.U(64.W) - divEffectiveDivisor,
        divEffectiveDivisor
    )
    divQuotientNeg := divDividendNeg ^ divDivisorNeg
    divRemainderNeg := divDividendNeg

    val divByZero = divEffectiveDivisor === 0.U
    val divSignedMin = Mux(
        divIsWord,
        "hffffffff80000000".U(64.W),
        "h8000000000000000".U(64.W)
    )
    val divSignedOverflow = divIsSigned &&
        (divEffectiveDividend === divSignedMin) &&
        (divEffectiveDivisor === Fill(64, 1.U(1.W)))
    val divSpecialRaw = Mux(
        divByZero,
        Mux(divIsRemainder, divEffectiveDividend, Fill(64, 1.U(1.W))),
        Mux(divIsRemainder, 0.U(64.W), divEffectiveDividend)
    )
    val divSpecialWord = Cat(Fill(32, divSpecialRaw(31)), divSpecialRaw(31, 0))
    divFast := idExeReg.ctrl.div_valid && (divByZero || divSignedOverflow)
    divFastResult := Mux(divIsWord, divSpecialWord, divSpecialRaw)

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
            SEL_ALU2.CONST4.U -> idExeReg.instLen,
            SEL_ALU2.CONST0.U -> 0.U(cfg.VLEN.W)
        )
    )

    loadUseHazard := exeMemReg.valid && memWaitingRespReg && !memRspFire &&
        (exeMemIsLoad || exeMemIsSc) && exeMemReg.wb_en && (
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

    def fpSourceMatches(ctrl: BreezeFpCtrl, rd: UInt): Bool =
        (ctrl.usesFpr1 && rs1Addr === rd) ||
        (ctrl.usesFpr2 && rs2Addr === rd) ||
        (ctrl.usesFpr3 && decodeInst(31, 27) === rd)
    fpRegHazard := fpDecoder.io.ctrl.valid && (
        (idExeReg.valid && idFpCtrl.writesFpr && fpSourceMatches(fpDecoder.io.ctrl, idExeReg.rd_addr)) ||
        (exeMemReg.valid && exeFpCtrl.writesFpr && fpSourceMatches(fpDecoder.io.ctrl, exeMemReg.rd_addr))
    )
    val idCsrAffectsFp = idExeReg.ctrl.csr_addr === CSRMAP.mstatus.U ||
        idExeReg.ctrl.csr_addr === CSRMAP.sstatus.U ||
        idExeReg.ctrl.csr_addr === CSRMAP.frm.U || idExeReg.ctrl.csr_addr === CSRMAP.fcsr.U
    val exeCsrAffectsFp = exeMemReg.csr_addr === CSRMAP.mstatus.U ||
        exeMemReg.csr_addr === CSRMAP.sstatus.U ||
        exeMemReg.csr_addr === CSRMAP.frm.U || exeMemReg.csr_addr === CSRMAP.fcsr.U
    val memWbCsrAffectsFp = memWbReg.csr_addr === CSRMAP.mstatus.U ||
        memWbReg.csr_addr === CSRMAP.sstatus.U ||
        memWbReg.csr_addr === CSRMAP.frm.U || memWbReg.csr_addr === CSRMAP.fcsr.U
    fpCsrHazard := fpDecoder.io.ctrl.valid && (
        (idExePendingCsrState && idCsrAffectsFp) ||
        (exeMemPendingCsrState && exeCsrAffectsFp) ||
        (memWbReg.valid && memWbReg.csr_write_en && memWbCsrAffectsFp)
    )

    // Hold the pipeline in the request cycle as well, otherwise exeMemReg can be
    // overwritten before the outstanding memory operation receives a response.
    pipelineHold := memReqIssued || (memWaitingRespReg && !memRspFire) ||
        mulReqIssued || (mulWaitingRespReg && !mulRspFire) ||
        divReqIssued || (divWaitingRespReg && !divRspFire) ||
        fpReqIssued || (fpWaitingRespReg && !fpRspFire) || fenceiPending

    csrFile.io.csr_addr := exeMemReg.csr_addr
    csrFile.io.csr_cmd := exeMemReg.csr_cmd
    csrFile.io.csr_reg_data := exeMemReg.data
    csrFile.io.rs1_id := exeMemReg.rs1_addr
    csrFile.io.rd_id := exeMemReg.rd_addr
    csrFile.io.commit_valid := memWbReg.valid
    csrFile.io.commit_addr := memWbReg.csr_addr
    csrFile.io.commit_wdata := memWbReg.csr_new_data
    csrFile.io.commit_write_en := memWbReg.csr_write_en &&
        !memWbReg.csr_illegal && !memWbReg.illegal_inst
    // Compute trap cause at WB stage: priority-encode the exception bools
    val ecallCause = MuxLookup(csrFile.io.current_privilege, BigInt(11).U(cfg.VLEN.W))(Seq(
        PRIV_MODE.U.U -> BigInt(8).U(cfg.VLEN.W),
        PRIV_MODE.S.U -> BigInt(9).U(cfg.VLEN.W)
    ))
    val mcauseVal = Wire(UInt(cfg.VLEN.W))
    mcauseVal := Mux1H(Seq(
        memWbReg.instruction_access_fault -> BigInt(1).U(cfg.VLEN.W),
        memWbReg.instruction_page_fault -> BigInt(12).U(cfg.VLEN.W),
        memWbReg.store_addr_misaligned -> BigInt(6).U(cfg.VLEN.W),
        memWbReg.load_addr_misaligned  -> BigInt(4).U(cfg.VLEN.W),
        memWbReg.store_access_fault    -> BigInt(7).U(cfg.VLEN.W),
        memWbReg.load_access_fault     -> BigInt(5).U(cfg.VLEN.W),
        memWbReg.store_page_fault      -> BigInt(15).U(cfg.VLEN.W),
        memWbReg.load_page_fault       -> BigInt(13).U(cfg.VLEN.W),
        memWbReg.is_ecall              -> ecallCause,
        memWbReg.is_ebreak             -> BigInt(3).U(cfg.VLEN.W),
        memWbReg.csr_illegal           -> BigInt(2).U(cfg.VLEN.W),
        memWbReg.illegal_inst          -> BigInt(2).U(cfg.VLEN.W),
        true.B                         -> 0.U(cfg.VLEN.W)
    ))

    // Compute trap value at WB stage: faulting address or zero
    val mtvalVal = Wire(UInt(cfg.VLEN.W))
    mtvalVal := Mux1H(Seq(
        memWbReg.instruction_access_fault -> memWbReg.pc,
        memWbReg.instruction_page_fault -> memWbReg.pc,
        memWbReg.store_addr_misaligned -> memWbReg.alu_data,
        memWbReg.load_addr_misaligned  -> memWbReg.alu_data,
        memWbReg.store_access_fault    -> memWbReg.alu_data,
        memWbReg.load_access_fault     -> memWbReg.alu_data,
        memWbReg.store_page_fault      -> memWbReg.alu_data,
        memWbReg.load_page_fault       -> memWbReg.alu_data,
        memWbReg.is_ecall              -> 0.U(cfg.VLEN.W),
        memWbReg.is_ebreak             -> 0.U(cfg.VLEN.W),
        memWbReg.csr_illegal           -> 0.U(cfg.VLEN.W),
        memWbReg.illegal_inst          -> memWbReg.rawInst,
        true.B                         -> 0.U(cfg.VLEN.W)
    ))

    csrFile.io.trap.valid        := exceptionRedirect || interruptRedirect
    csrFile.io.trap.is_interrupt := interruptRedirect
    csrFile.io.trap.cause        := Mux(interruptRedirect, csrFile.io.interruptCause, mcauseVal)
    csrFile.io.trap.pc           := Mux(interruptRedirect, architecturalNextPc, memWbReg.pc)
    csrFile.io.trap.tval         := Mux(interruptRedirect, 0.U, mtvalVal)
    csrFile.io.mret_commit       := memWbReg.valid && memWbReg.is_mret
    csrFile.io.sret_commit       := memWbReg.valid && memWbReg.is_sret

    if (cfg.enableTandem) {
        val trapTraceCount = RegInit(0.U(9.W))
        val wfiTraceCount = RegInit(0.U(9.W))
        when(csrFile.io.trap.valid && trapTraceCount < 256.U) {
            printf(cf"[CORE-TRAP] hart=${hartId.U} interrupt=${csrFile.io.trap.is_interrupt} cause=0x${csrFile.io.trap.cause}%x pc=0x${csrFile.io.trap.pc}%x tval=0x${csrFile.io.trap.tval}%x privilege=${csrFile.io.current_privilege} target=0x${csrFile.io.trap_target}%x msip=${io.machineSoftwareInterrupt} mtip=${io.machineTimerInterrupt} meip=${io.externalInterrupts.orR} seip=${io.supervisorExternalInterrupt}\n")
            trapTraceCount := trapTraceCount + 1.U
        }
        when(wfiCommit && !csrFile.io.wfiWakeup && wfiTraceCount < 256.U) {
            printf(cf"[CORE-WFI-SLEEP] hart=${hartId.U} pc=0x${memWbReg.pc}%x next=0x${memWbReg.nextPc}%x\n")
            wfiTraceCount := wfiTraceCount + 1.U
        }.elsewhen(wfiSleepingReg && csrFile.io.wfiWakeup && wfiTraceCount < 256.U) {
            printf(cf"[CORE-WFI-WAKE] hart=${hartId.U} msip=${io.machineSoftwareInterrupt} mtip=${io.machineTimerInterrupt} meip=${io.externalInterrupts.orR} seip=${io.supervisorExternalInterrupt}\n")
            wfiTraceCount := wfiTraceCount + 1.U
        }
    }

    when(reset.asBool) {
        architecturalNextPc := io.resetAddr
    }.elsewhen(xretRedirect) {
        architecturalNextPc := csrFile.io.xret_target
    }.elsewhen(memWbReg.valid && !wbTrap) {
        architecturalNextPc := memWbReg.nextPc
    }

    fenceiPending := exeMemReg.valid && exeMemReg.fencei
    io.dcacheFlushReq := fenceiPending && !fenceiFlushIssuedReg
    fenceiFlush := fenceiPending && fenceiFlushIssuedReg && io.dcacheFlushDone

    when(reset.asBool || fenceiFlush || satpCommit || exceptionRedirect || xretRedirect ||
            interruptRedirect || wfiCommit) {
        fenceiFlushIssuedReg := false.B
    }.elsewhen(io.dcacheFlushReq) {
        fenceiFlushIssuedReg := true.B
    }

    when(reset.asBool || fenceiFlush || satpCommit || exceptionRedirect || xretRedirect ||
            interruptRedirect || wfiCommit) {
        exeMemReg.valid := false.B
        exeMemReg.pc := 0.U
        exeMemReg.nextPc := 0.U
        exeMemReg.inst := nopInst
        exeMemReg.rawInst := nopInst
        exeMemReg.instLen := 4.U
        exeMemReg.instruction_access_fault := false.B
        exeMemReg.instruction_page_fault := false.B
        exeMemReg.illegal_inst := false.B
        exeMemReg.is_ecall := false.B
        exeMemReg.is_ebreak := false.B
        exeMemReg.is_mret := false.B
        exeMemReg.is_sret := false.B
        exeMemReg.is_wfi := false.B
        exeMemReg.csr_illegal := false.B
        exeMemReg.pred.predType := FrontendPredType.NONE
        exeMemReg.pred.predTaken := false.B
        exeMemReg.pred.predPc := 0.U
        exeMemReg.pred.phtIdx := 0.U
        exeMemReg.estop := false.B
        exeMemReg.fencei := false.B
        exeMemReg.data := 0.U
        exeMemReg.rs2_data := 0.U
        exeMemReg.mul_a := 0.S
        exeMemReg.mul_b := 0.S
        exeMemReg.mul_valid := false.B
        exeMemReg.mul_op := MUL_OP.XXX.U
        exeMemReg.div_valid := false.B
        exeMemReg.div_fast := false.B
        exeMemReg.div_fast_result := 0.U
        exeMemReg.div_dividend_mag := 0.U
        exeMemReg.div_divisor_mag := 0.U
        exeMemReg.div_quotient_neg := false.B
        exeMemReg.div_remainder_neg := false.B
        exeMemReg.div_is_remainder := false.B
        exeMemReg.div_is_word := false.B
        exeMemReg.mem_cmd := MEM_TYPE.NOT_MEM.U
        exeMemMemOp := BreezeMemOp.Load
        exeMemAmoFunc := BreezeAmoFunc.Swap
        exeMemAq := false.B
        exeMemRl := false.B
        exeMemReg.rd_addr := 0.U
        exeMemReg.rs1_addr := 0.U
        exeMemReg.csr_addr := 0.U
        exeMemReg.csr_cmd := CSR_CMD.NOP.U
        exeMemReg.wb_en := false.B
        exeMemReg.wb_sel := SEL_WB.XXX.U
        exeMemReg.actual_taken := false.B
        exeMemReg.actual_target := 0.U
        exeMemReg.prediction_miss := false.B
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
        exeMemReg.nextPc := exeNextPc
        exeMemReg.inst := idExeReg.inst
        exeMemReg.rawInst := idExeReg.rawInst
        exeMemReg.instLen := idExeReg.instLen
        exeMemReg.instruction_access_fault := idExeReg.instruction_access_fault
        exeMemReg.instruction_page_fault := idExeReg.instruction_page_fault
        exeMemReg.illegal_inst := idExeReg.illegal_inst
        exeMemReg.is_ecall := idExeReg.is_ecall
        exeMemReg.is_ebreak := idExeReg.is_ebreak
        exeMemReg.is_mret := idExeReg.is_mret
        exeMemReg.is_sret := idExeReg.is_sret
        exeMemReg.is_wfi := idExeReg.is_wfi
        // CSRFile is driven from exeMemReg below, so its legality result belongs
        // to the instruction already in EXE/MEM, not the incoming ID/EXE
        // instruction captured by this assignment. Sampling it here shifts an
        // illegal-CSR exception onto the following instruction and lets the
        // unsupported CSR itself retire. Legality is sampled at EXE/MEM ->
        // MEM/WB instead, alongside csr_old_data/csr_new_data.
        exeMemReg.csr_illegal := false.B
        exeMemReg.pred := idExeReg.pred
        exeMemReg.estop := idExeReg.estop
        exeMemReg.fencei := idExeReg.ctrl.fencei
        exeMemReg.data := Mux(idFpCtrl.isLoad || idFpCtrl.isStore,
            exeRs1Data + fpImmediate,
            Mux(idFpCtrl.localOp =/= BreezeFpLocalOp.NONE.U, fpLocalResult, alu.io.alu_out))
        // Stores need the forwarded rs2 value, especially for an adjacent
        // load-to-store dependency.
        exeMemReg.rs2_data := Mux(idFpCtrl.isStore, idFpOperand2, exeRs2Data)
        exeMemReg.mul_a := mulOperandA
        exeMemReg.mul_b := mulOperandB
        exeMemReg.mul_valid := idExeReg.ctrl.mul_valid
        exeMemReg.mul_op := idExeReg.ctrl.mul_op
        exeMemReg.div_valid := idExeReg.ctrl.div_valid
        exeMemReg.div_fast := divFast
        exeMemReg.div_fast_result := divFastResult
        exeMemReg.div_dividend_mag := divDividendMag
        exeMemReg.div_divisor_mag := divDivisorMag
        exeMemReg.div_quotient_neg := divQuotientNeg
        exeMemReg.div_remainder_neg := divRemainderNeg
        exeMemReg.div_is_remainder := divIsRemainder
        exeMemReg.div_is_word := divIsWord
        exeMemReg.mem_cmd := MuxCase(idExeReg.ctrl.mem_cmd, Seq(
            (idFpCtrl.isLoad && idFpCtrl.isDouble) -> MEM_TYPE.LD.U,
            (idFpCtrl.isLoad && !idFpCtrl.isDouble) -> MEM_TYPE.LW.U,
            (idFpCtrl.isStore && idFpCtrl.isDouble) -> MEM_TYPE.SD.U,
            (idFpCtrl.isStore && !idFpCtrl.isDouble) -> MEM_TYPE.SW.U
        ))
        exeMemMemOp := Mux(idFpCtrl.isStore, BreezeMemOp.Store,
            Mux(idFpCtrl.isLoad, BreezeMemOp.Load, idExeReg.ctrl.mem_op))
        exeMemAmoFunc := idExeReg.ctrl.amo_func
        exeMemAq := idExeReg.ctrl.amo_aq
        exeMemRl := idExeReg.ctrl.amo_rl
        exeMemReg.rd_addr := idExeReg.rd_addr
        exeMemReg.rs1_addr := idExeReg.rs1_addr
        exeMemReg.csr_addr := idExeReg.ctrl.csr_addr
        exeMemReg.csr_cmd := idExeReg.ctrl.csr_cmd
        exeMemReg.wb_en := idExeReg.ctrl.wb_en || idFpCtrl.writesGpr
        exeMemReg.wb_sel := Mux(idFpCtrl.fpuValid && idFpCtrl.writesGpr, SEL_WB.MUL.U,
            Mux(idFpCtrl.localOp === BreezeFpLocalOp.FMV_X.U, SEL_WB.ALU.U,
                idExeReg.ctrl.sel_wb))
        exeMemReg.actual_taken := actualTaken
        exeMemReg.actual_target := actualTarget
        exeMemReg.prediction_miss := predictionMiss
        exeMemReg.trace.foreach { trace =>
            trace.valid := idExeReg.valid
            trace.pc := idExeReg.pc
            trace.inst := idExeReg.rawInst
            trace.nextPc := exeNextPc
            trace.estop := idExeReg.estop
            trace.rdWriteEn := idExeReg.ctrl.wb_en && (idExeReg.rd_addr =/= 0.U)
            trace.rdAddr := idExeReg.rd_addr
            trace.rdData := 0.U
            trace.memEn := idExeReg.ctrl.mem_cmd =/= MEM_TYPE.NOT_MEM.U ||
                idFpCtrl.isLoad || idFpCtrl.isStore
            trace.memIsWrite := idFpCtrl.isStore || idExeReg.ctrl.mem_cmd === MEM_TYPE.SB.U ||
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

    when(reset.asBool || fenceiFlush || satpCommit || exceptionRedirect || xretRedirect ||
            interruptRedirect || wfiCommit) {
        exeFpCtrl := 0.U.asTypeOf(new BreezeFpCtrl)
        exeFpOperand1 := 0.U
        exeFpOperand2 := 0.U
        exeFpOperand3 := 0.U
        exeFpRm := 0.U
    }.elsewhen(!pipelineHold) {
        val fpSource1 = Mux(idFpCtrl.usesGpr1, exeRs1Data, idFpOperand1)
        exeFpCtrl := idFpCtrl
        // FPnew ADD consumes operands 1 and 2; all other scalar operations
        // consume operands starting at operand 0.
        exeFpOperand1 := Mux(idFpCtrl.operation === BreezeFpOp.ADD.U, 0.U, fpSource1)
        exeFpOperand2 := Mux(idFpCtrl.operation === BreezeFpOp.ADD.U, fpSource1, idFpOperand2)
        exeFpOperand3 := Mux(idFpCtrl.operation === BreezeFpOp.ADD.U, idFpOperand2, idFpOperand3)
        exeFpRm := Mux(idFpCtrl.usesArchitecturalRm && idFpCtrl.rm === 7.U,
            csrFile.io.frm, idFpCtrl.rm)
    }

    when(reset.asBool) {
        memWaitingRespReg := false.B
    }.elsewhen(memRspFire) {
        memWaitingRespReg := false.B
    }.elsewhen(memReqIssued) {
        memWaitingRespReg := true.B
    }

    when(reset.asBool || frontendRedirectNeeded) {
        mulWaitingRespReg := false.B
    }.elsewhen(mulRspFire) {
        mulWaitingRespReg := false.B
    }.elsewhen(mulReqIssued) {
        mulWaitingRespReg := true.B
    }

    when(reset.asBool || frontendRedirectNeeded) {
        divWaitingRespReg := false.B
    }.elsewhen(divRspFire) {
        divWaitingRespReg := false.B
    }.elsewhen(divReqIssued) {
        divWaitingRespReg := true.B
    }

    when(reset.asBool || frontendRedirectNeeded) {
        fpWaitingRespReg := false.B
    }.elsewhen(fpRspFire) {
        fpWaitingRespReg := false.B
    }.elsewhen(fpReqAccepted) {
        fpWaitingRespReg := true.B
    }

    when(reset.asBool || satpCommit || exceptionRedirect || xretRedirect || interruptRedirect ||
            wfiCommit) {
        memWbReg.valid := false.B
        memWbReg.pc := 0.U
        memWbReg.nextPc := 0.U
        memWbReg.inst := nopInst
        memWbReg.rawInst := nopInst
        memWbReg.instLen := 4.U
        memWbReg.instruction_access_fault := false.B
        memWbReg.instruction_page_fault := false.B
        memWbReg.illegal_inst := false.B
        memWbReg.is_ecall := false.B
        memWbReg.is_ebreak := false.B
        memWbReg.is_mret := false.B
        memWbReg.is_sret := false.B
        memWbReg.is_wfi := false.B
        memWbReg.csr_illegal := false.B
        memWbReg.load_addr_misaligned := false.B
        memWbReg.store_addr_misaligned := false.B
        memWbReg.load_access_fault := false.B
        memWbReg.store_access_fault := false.B
        memWbReg.load_page_fault := false.B
        memWbReg.store_page_fault := false.B
        memWbReg.estop := false.B
        memWbReg.wb_en := false.B
        memWbReg.wb_sel := SEL_WB.XXX.U
        memWbReg.rd_addr := 0.U
        memWbReg.alu_data := 0.U
        memWbReg.mem_data := 0.U
        memWbReg.csr_data := 0.U
        memWbReg.mul_data := 0.U
        memWbReg.csr_addr := 0.U
        memWbReg.csr_new_data := 0.U
        memWbReg.csr_write_en := false.B
        memWbReg.prediction_miss := false.B
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
    }.elsewhen(!exeMemReg.valid || (!exeMemNeedsDmem && !exeMemIsMul && !exeMemIsDiv && !exeMemIsFp)) {
        // FENCE.I retires only after DCache clean completes and the frontend
        // flush is emitted; do not repeatedly retire it while the cache scans.
        memWbReg.valid := exeMemReg.valid && (!exeMemReg.fencei || fenceiFlush)
        memWbReg.pc := exeMemReg.pc
        memWbReg.nextPc := exeMemReg.nextPc
        memWbReg.inst := exeMemReg.inst
        memWbReg.rawInst := exeMemReg.rawInst
        memWbReg.instLen := exeMemReg.instLen
        memWbReg.instruction_access_fault := exeMemReg.instruction_access_fault
        memWbReg.instruction_page_fault := exeMemReg.instruction_page_fault
        memWbReg.illegal_inst := exeMemReg.illegal_inst
        memWbReg.is_ecall := exeMemReg.is_ecall
        memWbReg.is_ebreak := exeMemReg.is_ebreak
        memWbReg.is_mret := exeMemReg.is_mret
        memWbReg.is_sret := exeMemReg.is_sret
        memWbReg.is_wfi := exeMemReg.is_wfi
        memWbReg.csr_illegal := csrFile.io.csr_illegal &&
            !exeMemReg.instruction_access_fault && !exeMemReg.instruction_page_fault
        memWbReg.estop := exeMemReg.estop
        memWbReg.load_addr_misaligned := loadAddrMisaligned
        memWbReg.store_addr_misaligned := storeAddrMisaligned
        memWbReg.load_access_fault := false.B
        memWbReg.store_access_fault := false.B
        memWbReg.load_page_fault := false.B
        memWbReg.store_page_fault := false.B
        memWbReg.wb_en := exeMemReg.wb_en && !memAddrMisaligned &&
            !exeMemReg.instruction_access_fault && !exeMemReg.instruction_page_fault
        memWbReg.wb_sel := exeMemReg.wb_sel
        memWbReg.rd_addr := exeMemReg.rd_addr
        memWbReg.alu_data := exeMemReg.data
        memWbReg.mem_data := 0.U
        memWbReg.csr_data := csrFile.io.csr_old_data
        memWbReg.mul_data := 0.U
        memWbReg.csr_addr := exeMemReg.csr_addr
        memWbReg.csr_new_data := csrFile.io.csr_new_data
        memWbReg.csr_write_en := csrFile.io.csr_write_en && !memAddrMisaligned &&
            !exeMemReg.instruction_access_fault && !exeMemReg.instruction_page_fault
        memWbReg.prediction_miss := exeMemReg.prediction_miss
        memWbReg.trace.zip(exeMemReg.trace).foreach { case (wbTrace, exeTrace) =>
            wbTrace := exeTrace
            wbTrace.valid := exeMemReg.valid && (!exeMemReg.fencei || fenceiFlush)
            wbTrace.rdWriteEn := exeTrace.rdWriteEn && !memAddrMisaligned
            wbTrace.rdData := MuxLookup(exeMemReg.wb_sel, 0.U(cfg.VLEN.W))(
                Seq(
                    SEL_WB.ALU.U -> exeMemReg.data,
                    SEL_WB.MEM.U -> 0.U(cfg.VLEN.W),
                    SEL_WB.CSR.U -> csrFile.io.csr_old_data,
                    SEL_WB.MUL.U -> 0.U(cfg.VLEN.W)
                )
            )
            wbTrace.memRData := 0.U
            wbTrace.memWData := Mux(exeTrace.memIsWrite, memReqWData, 0.U)
            wbTrace.memWMask := Mux(exeTrace.memIsWrite, memReqWMask, 0.U)
        }
    }.elsewhen(memRspFire || mulRspFire || divFastCompletion || divRspFire || fpRspFire) {
        memWbReg.valid := exeMemReg.valid
        memWbReg.pc := exeMemReg.pc
        memWbReg.nextPc := exeMemReg.nextPc
        memWbReg.inst := exeMemReg.inst
        memWbReg.rawInst := exeMemReg.rawInst
        memWbReg.instLen := exeMemReg.instLen
        memWbReg.instruction_access_fault := exeMemReg.instruction_access_fault
        memWbReg.instruction_page_fault := exeMemReg.instruction_page_fault
        memWbReg.illegal_inst := exeMemReg.illegal_inst
        memWbReg.is_ecall := exeMemReg.is_ecall
        memWbReg.is_ebreak := exeMemReg.is_ebreak
        memWbReg.is_mret := exeMemReg.is_mret
        memWbReg.is_sret := exeMemReg.is_sret
        memWbReg.is_wfi := exeMemReg.is_wfi
        memWbReg.csr_illegal := exeMemReg.csr_illegal
        memWbReg.estop := exeMemReg.estop
        memWbReg.load_addr_misaligned := false.B
        memWbReg.store_addr_misaligned := false.B
        // AMO faults classify as store/AMO access faults (cause 7) despite the
        // load-typed mem_cmd; LR stays a load access fault (cause 5).
        memWbReg.load_access_fault := memRspFire && exeMemIsLoad && !exeMemIsAmo &&
            io.dmem.rsp.error && !io.dmem.rsp.pageFault
        memWbReg.store_access_fault := memRspFire && (exeMemIsStore || exeMemIsAmo) &&
            io.dmem.rsp.error && !io.dmem.rsp.pageFault
        memWbReg.load_page_fault := memRspFire && exeMemIsLoad && !exeMemIsAmo &&
            io.dmem.rsp.pageFault
        memWbReg.store_page_fault := memRspFire && (exeMemIsStore || exeMemIsAmo) &&
            io.dmem.rsp.pageFault
        memWbReg.wb_en := exeMemReg.wb_en && !exeMemReg.instruction_access_fault &&
            !exeMemReg.instruction_page_fault &&
            !(memRspFire && io.dmem.rsp.error)
        memWbReg.wb_sel := exeMemReg.wb_sel
        memWbReg.rd_addr := exeMemReg.rd_addr
        memWbReg.alu_data := exeMemReg.data
        memWbReg.mem_data := Mux((exeMemIsLoad || exeMemIsSc) && !io.dmem.rsp.error,
            memCompletionData, 0.U)
        memWbReg.csr_data := csrFile.io.csr_old_data
        memWbReg.mul_data := MuxCase(0.U(cfg.VLEN.W), Seq(
            mulRspFire -> mulUnit.io.result,
            divFastCompletion -> exeMemReg.div_fast_result,
            divRspFire -> divUnit.io.result,
            fpRspFire -> fpUnit.io.result
        ))
        memWbReg.csr_addr := exeMemReg.csr_addr
        memWbReg.csr_new_data := csrFile.io.csr_new_data
        memWbReg.csr_write_en := csrFile.io.csr_write_en &&
            !exeMemReg.instruction_access_fault && !exeMemReg.instruction_page_fault &&
            !(memRspFire && io.dmem.rsp.error)
        memWbReg.prediction_miss := exeMemReg.prediction_miss
        memWbReg.trace.zip(exeMemReg.trace).foreach { case (wbTrace, exeTrace) =>
            wbTrace := exeTrace
            wbTrace.valid := exeMemReg.valid
            wbTrace.rdWriteEn := exeTrace.rdWriteEn && !(memRspFire && io.dmem.rsp.error)
            wbTrace.rdData := MuxLookup(exeMemReg.wb_sel, 0.U(cfg.VLEN.W))(
                Seq(
                    SEL_WB.ALU.U -> exeMemReg.data,
                    SEL_WB.MEM.U -> Mux((exeMemIsLoad || exeMemIsSc) && !io.dmem.rsp.error,
                        memCompletionData, 0.U),
                    SEL_WB.CSR.U -> csrFile.io.csr_old_data,
                    SEL_WB.MUL.U -> MuxCase(0.U(cfg.VLEN.W), Seq(
                        mulRspFire -> mulUnit.io.result,
                        divFastCompletion -> exeMemReg.div_fast_result,
                        divRspFire -> divUnit.io.result,
                        fpRspFire -> fpUnit.io.result
                    ))
                )
            )
            wbTrace.memRData := Mux((exeMemIsLoad || exeMemIsSc) && !io.dmem.rsp.error,
                memCompletionData, 0.U)
            wbTrace.memWData := Mux(exeTrace.memIsWrite, memReqWData, 0.U)
            wbTrace.memWMask := Mux(exeTrace.memIsWrite, memReqWMask, 0.U)
        }
    }.otherwise {
        // A memory, multiplication, or division instruction occupies EXE/MEM
        // until its completion arrives.
        // Do not leave the previous MEM/WB entry valid during those wait
        // cycles, otherwise one instruction appears to retire repeatedly.
        memWbReg.valid := false.B
        memWbReg.csr_write_en := false.B
        memWbReg.trace.foreach(_.valid := false.B)
    }

    // Floating-point writeback sideband, aligned with the common MEM/WB
    // register above.  FPR writes and accrued flags become architectural only
    // when memWbReg.valid commits on the following cycle.
    when(reset.asBool || satpCommit || exceptionRedirect || xretRedirect || interruptRedirect ||
            wfiCommit) {
        memWbFpWrite := false.B
        memWbFpData := 0.U
        memWbFpFlagsValid := false.B
        memWbFpFlags := 0.U
    }.elsewhen(!exeMemReg.valid || (!exeMemNeedsDmem && !exeMemIsMul && !exeMemIsDiv && !exeMemIsFp)) {
        memWbFpWrite := exeMemReg.valid && exeFpCtrl.writesFpr &&
            exeFpCtrl.localOp === BreezeFpLocalOp.FMV_F.U && !exeMemReg.illegal_inst
        memWbFpData := exeMemReg.data
        memWbFpFlagsValid := false.B
        memWbFpFlags := 0.U
    }.elsewhen(memRspFire || mulRspFire || divFastCompletion || divRspFire || fpRspFire) {
        memWbFpWrite := (memRspFire && exeFpCtrl.isLoad && !io.dmem.rsp.error) ||
            (fpRspFire && exeFpCtrl.writesFpr)
        memWbFpData := Mux(
            memRspFire && exeFpCtrl.isLoad,
            Mux(exeFpCtrl.isDouble, io.dmem.rsp.data,
                Cat("hffffffff".U(32.W), loadAlignBuf(31, 0))),
            fpUnit.io.result
        )
        memWbFpFlagsValid := fpRspFire && exeFpCtrl.writeFlags
        memWbFpFlags := Mux(fpRspFire, fpUnit.io.status, 0.U)
    }.otherwise {
        memWbFpWrite := false.B
        memWbFpFlagsValid := false.B
    }

    csrFile.io.fp_commit_valid := memWbReg.valid &&
        !memWbReg.instruction_access_fault && !memWbReg.instruction_page_fault &&
        !memWbReg.illegal_inst &&
        !memWbReg.load_addr_misaligned && !memWbReg.load_access_fault &&
        !memWbReg.load_page_fault &&
        (memWbFpWrite || memWbFpFlagsValid)
    csrFile.io.fp_flags := Mux(memWbFpFlagsValid, memWbFpFlags, 0.U)

    // A pending enabled interrupt stops issue while older instructions drain.
    decodeReady := !pipelineHold && !csrHold && !fpRegHazard && !fpCsrHazard &&
        !frontendRedirectNeeded &&
        !csrFile.io.interruptPending && !wfiInFlight && !wfiSleepingReg
    decodeFire := decodeValid && decodeReady
    io.fetchBuffer.ready := decodeReady

    io.dmem.req.valid := memReqIssued
    io.dmem.req.isWrite := exeMemIsStore && !exeMemIsSc
    io.dmem.req.addr := exeMemReg.data
    io.dmem.req.sizeLog2 := memReqSizeLog2
    io.dmem.req.wdata := memReqWData
    io.dmem.req.wmask := memReqWMask
    io.dmem.req.memOp := exeMemMemOp
    io.dmem.req.amoFunc := exeMemAmoFunc
    io.dmem.req.aq := exeMemAq
    io.dmem.req.rl := exeMemRl

    // aq/rl ordering point: this backend is strictly in-order with a single
    // outstanding memory operation and no store buffer, so every older memory
    // operation has completed before an atomic issues (rl) and no younger
    // memory operation can issue before the atomic's response retires (aq).
    // That property is load-bearing for RV64A - keep it checked, not assumed.
    // If a store buffer, MSHR or any second outstanding slot is ever added,
    // aq/rl must gain real barrier logic here.
    assert(!(io.dmem.req.valid && memWaitingRespReg),
        "[BreezeBackend] a memory request was issued while another is outstanding")

    // Any taken trap (exception or interrupt) kills the LR/SC reservation in
    // the D$; mret needs no kill because SC after a trap round-trip already
    // fails through the cleared reservation.
    io.reservationKill := exceptionRedirect || interruptRedirect
    io.mmuContext := csrFile.io.mmu_context
    io.sfence.valid := sfenceExecute
    io.sfence.vaddr := exeRs1Data
    io.sfence.asid := exeRs2Data(15, 0)
    io.sfence.useVaddr := idExeReg.rs1_addr =/= 0.U
    io.sfence.useAsid := idExeReg.rs2_addr =/= 0.U

    csrFile.io.hpmEvents.memStallCycle := memReqIssued ||
        (memWaitingRespReg && !io.dmem.rsp.valid)
    csrFile.io.hpmEvents.loadUseStall := loadUseHazard

    io.frontendRedirect.valid := frontendRedirectNeeded
    io.frontendRedirect.flush := frontendRedirectNeeded
    io.frontendRedirect.cacheFlush := fenceiFlush
    io.frontendRedirect.target := Mux1H(Seq(
        fenceiFlush        -> (exeMemReg.pc + exeMemReg.instLen),
        sfenceExecute      -> (idExeReg.pc + idExeReg.instLen),
        satpCommit         -> memWbReg.nextPc,
        xretRedirect       -> csrFile.io.xret_target,
        interruptRedirect  -> csrFile.io.trap_target,
        exceptionRedirect  -> csrFile.io.trap_target,
        wfiCommit           -> memWbReg.nextPc,
        // Direction mispredicts can be either not-taken -> taken or
        // taken -> not-taken. exeNextPc selects the architecturally correct
        // destination for both cases; actualTarget alone would incorrectly
        // send a predicted-taken loop back to its body on the exit iteration.
        redirectNeeded     -> exeNextPc
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
        debug.memWbException := memWbReg.instruction_access_fault ||
            memWbReg.instruction_page_fault || memWbReg.illegal_inst
        debug.memWbTrapValid := wbTrap || interruptRedirect
        debug.memWbIsEcall := memWbReg.is_ecall
        debug.memWbIsMret := memWbReg.is_mret
        debug.memWbIsWfi := memWbReg.is_wfi
        debug.wfiSleeping := wfiSleepingReg
        debug.csrIllegal := csrFile.io.csr_illegal
    }
}
