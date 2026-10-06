package flow.backend

import chisel3._
import chisel3.util._
import flow.config.BackendConfig
import flow.interface._
import flow.core._
import flow.divider.CommittedDivUnit
import flow.multiplier.CommittedMulUnit
import flow.fpu._
import flow.platform.BreezeMcuPlatform

/** Metadata for MEM/WB, not a completion queue. Arithmetic stays in its source. */
class V1Stage(val cfg: BackendConfig) extends Bundle {
  val valid = Bool()
  val pc = UInt(64.W)
  val nextPc = UInt(64.W)
  val inst = UInt(32.W)
  val rawInst = UInt(32.W)
  val instLen = UInt(3.W)
  val rd = new L1DDestination
  val writes = Bool()
  val data = UInt(64.W)
  val address = UInt(64.W)
  val storeData = UInt(64.W)
  val mem = Bool()
  val load = Bool()
  val mul = Bool()
  val div = Bool()
  val fp = Bool()
  val serial = Bool()
  val fencei = Bool()
  val sfence = Bool()
  val wfi = Bool()
  val estop = Bool()
  val mret = Bool()
  val sret = Bool()
  val exception = Bool()
  val cause = UInt(64.W)
  val tval = UInt(64.W)
  val csrAddr = UInt(12.W)
  val csrCmd = UInt(CSR_CMD.width.W)
  val csrSource = UInt(64.W)
  val csrWrite = Bool()
  val csrData = UInt(64.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val predictionMiss = Bool()
}

/** Four-stage v1 backend. EX request acceptance, WB authorization, direct late writes. */
class BreezeBackend(
    val cfg: BackendConfig = BackendConfig(), val enabledebug: Boolean = false,
    val hartId: Int = 0, val useFASE: Boolean = false
) extends Module {
  require(cfg.VLEN == 64 && hartId >= 0)
  require(!useFASE, "v1 FASE integration awaits the cluster specification")
  val io = IO(new Bundle {
    val resetAddr = Input(UInt(64.W))
    val fase = if (useFASE) Some(new FaseBackendIO) else None
    val machineTimerInterrupt = Input(Bool())
    val machineSoftwareInterrupt = Input(Bool())
    val time = Input(UInt(64.W))
    val externalInterrupts = Input(UInt(BreezeMcuPlatform.ExternalInterruptWidth.W))
    val supervisorExternalInterrupt = Input(Bool())
    val fetchBuffer = Flipped(Decoupled(new FrontendFetchBundle(64, cfg.ghrLength)))
    val l1d = Flipped(new L1DCoreIO)
    val mmuIdle = Input(Bool())
    val translationBlocked = Output(Bool())
    val frontendBtbUpdate = Output(new BreezeBTBUpdateReq(64))
    val frontendPhtUpdate = Output(new BreezePHTUpdateReq(cfg.ghrLength.max(1)))
    val frontendGhrUpdate = Output(new BreezeGHRUpdateReq)
    val frontendRedirect = Output(new FrontendRedirectIO(64))
    val mmuContext = Output(new BreezeMmuContext(64))
    val sfence = Output(new BreezeSfenceReq(64))
    val reservationKill = Output(Bool())
    val hpmEvents = Input(new BreezeHpmEvents)
    val backendEvents = Output(new BreezeHpmEvents)
    val hartFatal = Output(Bool())
    val estop = Output(Bool())
    val tandem = if (cfg.enableTandem) Some(Output(new TracePayload(64))) else None
    val debug = if (enabledebug) Some(new BackendDebugIO(64)) else None
    val observe = Output(new V1BackendObservation)
  })
  val decoder = Module(new Decoder)
  val fpDecoder = Module(new BreezeFpDecoder)
  val immGen = Module(new ImmGen(64))
  val regFile = Module(new RegFile(64))
  val fpRegFile = Module(new BreezeFpRegFile)
  val csrFile = Module(new CSRFile(64, enabledebug = enabledebug, hartId = hartId,
    privilegeProfile = cfg.privilegeProfile, enableCompressed = cfg.enableCompressed))
  val scoreboard = Module(new V1Scoreboard)
  val writeback = Module(new V1Writeback)
  val mulUnit = Module(new CommittedMulUnit)
  val divUnit = Module(new CommittedDivUnit)
  val fpUnit = Module(new CommittedFpUnit)
  val alu = Module(new ALU(64))
  val bru = Module(new BRU(64))
  val jau = Module(new JAU(64))
  val ex = RegInit(0.U.asTypeOf(new BreezeBackendIDEXE(64, cfg.ghrLength)))
  val exFp = RegInit(0.U.asTypeOf(new BreezeFpCtrl))
  val exFpr = RegInit(VecInit(Seq.fill(3)(0.U(64.W))))
  val mem = RegInit(0.U.asTypeOf(new V1Stage(cfg)))
  val wb = RegInit(0.U.asTypeOf(new V1Stage(cfg)))
  val sleeping = RegInit(false.B)
  val stopped = RegInit(false.B)
  val sfenceSent = RegInit(false.B)
  val deferredLoad = RegInit(false.B)
  val nextPc = RegInit(0.U(64.W))
  val wbKill = Wire(Bool())
  val exAdvance = Wire(Bool())
  val wbCommit = Wire(Bool())
  val branchRedirect = Wire(Bool())
  val idLeave = Wire(Bool())

  val inst = io.fetchBuffer.bits.inst
  val ctrl = decoder.io.exe_ctrl
  val fpCtrl = fpDecoder.io.ctrl
  val rs1 = inst(19,15)
  val rs2 = inst(24,20)
  val rd = inst(11,7)
  decoder.io.inst := inst
  fpDecoder.io.inst := inst
  immGen.io.inst := inst
  immGen.io.type_sel := ctrl.sel_imm
  val idCsr = ctrl.csr_cmd =/= CSR_CMD.NOP.U
  val idEstop = inst === "h7ff00073".U
  val idSerial = ctrl.fencei || ctrl.is_sfence_vma || ctrl.is_wfi || idEstop
  val serialInFlight = (ex.valid && (ex.ctrl.fencei || ex.ctrl.is_sfence_vma || ex.is_wfi || ex.estop)) ||
    (mem.valid && mem.serial) || (wb.valid && wb.serial)
  val gpr1Used = Mux(fpCtrl.valid, fpCtrl.usesGpr1,
    (ctrl.sel_alu1 === SEL_ALU1.RS1.U && !(idCsr && ctrl.csr_cmd(2))) || ctrl.bru_inst ||
      ctrl.sel_jpc_i === SEL_JPC_I.RS1.U || ctrl.is_sfence_vma)
  val gpr2Used = !fpCtrl.valid && (ctrl.sel_alu2 === SEL_ALU2.RS2.U || ctrl.bru_inst ||
    ctrl.mem_op === BreezeMemOp.Store || ctrl.mem_op === BreezeMemOp.Sc ||
    ctrl.mem_op === BreezeMemOp.Amo || ctrl.is_sfence_vma)
  val idWrites = Mux(fpCtrl.valid, fpCtrl.writesFpr || fpCtrl.writesGpr, ctrl.wb_en)
  val idBank = fpCtrl.valid && fpCtrl.writesFpr
  val operands = scoreboard.io.operands
  operands(0).used := gpr1Used || fpCtrl.usesFpr1
  operands(0).rd.idx := rs1
  operands(0).rd.isFp := fpCtrl.usesFpr1
  operands(1).used := gpr2Used || fpCtrl.usesFpr2
  operands(1).rd.idx := rs2
  operands(1).rd.isFp := fpCtrl.usesFpr2
  operands(2).used := fpCtrl.usesFpr3
  operands(2).rd.idx := inst(31,27)
  operands(2).rd.isFp := true.B
  operands(3).used := idWrites
  operands(3).rd.idx := rd
  operands(3).rd.isFp := idBank

  // S2 is consumed directly at WB. A valid response is never captured.
  val wbResponse = wb.valid && wb.mem && io.l1d.resp.valid
  val wbDone = wbResponse && io.l1d.resp.bits.kind === L1DRespKind.Done
  val wbMiss = wbResponse && io.l1d.resp.bits.kind === L1DRespKind.Mshr
  val wbMemExc = wbResponse && io.l1d.resp.bits.kind === L1DRespKind.Exc
  val wbExc = wb.valid && (wb.exception || wbMemExc)
  val serialWait = wb.valid && !wb.exception && ((wb.fencei && !io.l1d.drained) ||
    (wb.sfence && (!sfenceSent || !io.mmuIdle)) ||
    (wb.estop && (scoreboard.io.gprBusy.orR || scoreboard.io.fprBusy.orR || fpUnit.io.committedFlagsOnly)))
  val downHold = io.l1d.s2Hold || serialWait
  val wbCanLeave = wb.valid && !downHold && !writeback.io.hartFatal && !stopped
  wbCommit := wbCanLeave && !wbExc
  val interrupt = csrFile.io.interruptPending && !ex.valid && !mem.valid && !wb.valid &&
    !io.l1d.mmioBusy && !writeback.io.hartFatal && !stopped
  val fenceRedirect = wbCommit && (wb.fencei || wb.sfence)
  val xret = wbCommit && (wb.mret || wb.sret)
  val satp = wbCommit && wb.csrWrite && wb.csrAddr === CSRMAP.satp.U
  wbKill := (wbCanLeave && wbExc) || interrupt || fenceRedirect || xret || satp || (wbCommit && wb.wfi)

  val wbLong = wb.mul || wb.div || wb.fp || (wb.mem && wb.load && wbMiss)
  val wbOrdinary = wbCommit && wb.writes && !wb.mul && !wb.div && !wb.fp && (!wb.mem || wbDone)
  val wbData = Mux(wb.mem, io.l1d.resp.bits.data, wb.data)
  writeback.io.ordinary.valid := wbOrdinary
  writeback.io.ordinary.bits.rd := wb.rd
  writeback.io.ordinary.bits.data := wbData
  writeback.io.ordinary.bits.flags := 0.U
  writeback.io.late <> io.l1d.late
  writeback.io.div <> divUnit.io.result
  writeback.io.mul <> mulUnit.io.result
  writeback.io.fp <> fpUnit.io.result
  io.hartFatal := writeback.io.hartFatal
  regFile.io.rs1_addr := Mux(deferredLoad, ex.rs1_addr, rs1)
  regFile.io.rs2_addr := Mux(deferredLoad, ex.rs2_addr, rs2)
  regFile.io.rd_en := writeback.io.gprWrite.valid
  regFile.io.rd_addr := writeback.io.gprWrite.bits.idx
  regFile.io.rd_data := writeback.io.gprWrite.bits.data
  fpRegFile.io.rs1Addr := rs1
  fpRegFile.io.rs2Addr := rs2
  fpRegFile.io.rs3Addr := inst(31,27)
  fpRegFile.io.rdEn := writeback.io.fprWrite.valid
  fpRegFile.io.rdAddr := writeback.io.fprWrite.bits.idx
  fpRegFile.io.rdData := writeback.io.fprWrite.bits.data
  scoreboard.io.set.valid := wbCommit && wbLong && wb.writes
  scoreboard.io.set.bits.rd := wb.rd
  scoreboard.io.set.bits.source := Mux(wb.mem, V1LongSource.L1D.U,
    Mux(wb.div, V1LongSource.DIV.U, Mux(wb.mul, V1LongSource.MUL.U, V1LongSource.FPU.U)))
  scoreboard.io.clear := writeback.io.clear
  scoreboard.io.csr := idCsr
  scoreboard.io.idValid := io.fetchBuffer.valid
  scoreboard.io.idLeave := idLeave
  scoreboard.io.fpFlagsPending := fpUnit.io.committedFlagsOnly
  val exLegal = ex.valid && !ex.illegal_inst && !ex.instruction_access_fault && !ex.instruction_page_fault
  val exMem = exLegal && (ex.ctrl.mem_cmd =/= MEM_TYPE.NOT_MEM.U || exFp.isLoad || exFp.isStore ||
    ex.inst(6,0) === OPCODE.MISC_MEM && !ex.ctrl.fencei)
  val exLoad = exMem && !(exFp.isStore || ex.ctrl.mem_op === BreezeMemOp.Store || ex.inst(6,0) === OPCODE.MISC_MEM)
  val exMul = exLegal && ex.ctrl.mul_valid && ex.rd_addr =/= 0.U
  val exDiv = exLegal && ex.ctrl.div_valid && ex.rd_addr =/= 0.U
  val exFpLong = exLegal && exFp.fpuValid
  val exBank = exFp.valid && exFp.writesFpr
  val exWrites = Mux(exFp.valid, exFp.writesFpr || exFp.writesGpr, ex.ctrl.wb_en)
  for ((stage, index) <- Seq(mem -> 1, wb -> 2)) {
    scoreboard.io.pipe(index).valid := stage.valid && (stage.mul || stage.div || stage.fp || stage.mem) &&
      (index != 2).B || ((index == 2).B && wb.valid && (wb.mul || wb.div || wb.fp || (wb.mem && !wbDone)))
    scoreboard.io.pipe(index).bits.rd := stage.rd
    scoreboard.io.pipe(index).bits.source := Mux(stage.mem, V1LongSource.L1D.U,
      Mux(stage.div, V1LongSource.DIV.U, Mux(stage.mul, V1LongSource.MUL.U, V1LongSource.FPU.U)))
    // CSR must wait even for a store/Fence or an FP-to-x0 producer.
    when(!stage.writes) { scoreboard.io.pipe(index).bits.rd.idx := 0.U }
  }
  scoreboard.io.pipe(0).valid := exMul || exDiv || exFpLong || exMem
  scoreboard.io.pipe(0).bits.rd.idx := Mux(exWrites, ex.rd_addr, 0.U)
  scoreboard.io.pipe(0).bits.rd.isFp := exBank
  scoreboard.io.pipe(0).bits.source := Mux(exMem, V1LongSource.L1D.U,
    Mux(exDiv, V1LongSource.DIV.U, Mux(exMul, V1LongSource.MUL.U, V1LongSource.FPU.U)))
  // Optional hit bypass relaxes only the MEM Load RAW check. WAW still waits.
  if (cfg.loadUseBypass) {
    val waw = idWrites && idBank === mem.rd.isFp && rd === mem.rd.idx
    when(mem.valid && mem.mem && mem.load && !mem.rd.isFp && !waw && !idCsr) {
      scoreboard.io.pipe(1).valid := false.B
    }
  }

  def exRead(addr: UInt, saved: UInt): UInt = {
    val fromWb = wbOrdinary && !wb.rd.isFp && wb.rd.idx =/= 0.U && addr === wb.rd.idx
    val fromMem = mem.valid && mem.writes && !mem.rd.isFp && mem.rd.idx =/= 0.U &&
      !mem.mem && !mem.mul && !mem.div && !mem.fp && mem.csrCmd === CSR_CMD.NOP.U && addr === mem.rd.idx
    Mux(fromMem, mem.data, Mux(fromWb, wbData, saved))
  }
  val exGpr1Used = Mux(exFp.valid, exFp.usesGpr1,
    ex.ctrl.sel_alu1 === SEL_ALU1.RS1.U || ex.ctrl.bru_inst ||
      ex.ctrl.sel_jpc_i === SEL_JPC_I.RS1.U || ex.ctrl.is_sfence_vma)
  val exGpr2Used = !exFp.valid && (ex.ctrl.sel_alu2 === SEL_ALU2.RS2.U || ex.ctrl.bru_inst ||
    ex.ctrl.mem_op === BreezeMemOp.Store || ex.ctrl.mem_op === BreezeMemOp.Sc || ex.ctrl.mem_op === BreezeMemOp.Amo)
  val bypassMissWait = cfg.loadUseBypass.B && ex.valid && wbMiss && wb.load && !wb.rd.isFp && wb.rd.idx =/= 0.U &&
    ((exGpr1Used && ex.rs1_addr === wb.rd.idx) || (exGpr2Used && ex.rs2_addr === wb.rd.idx))
  val deferredBusy = (exGpr1Used && scoreboard.io.gprBusy(ex.rs1_addr)) ||
    (exGpr2Used && scoreboard.io.gprBusy(ex.rs2_addr))
  val deferredWait = bypassMissWait || (deferredLoad && deferredBusy)
  // A bypass-enabled dependent of a miss stays in EX. Reuse the two RF read
  // ports while ID is closed; capture write-through into existing EX operands.
  // No background-result-to-EX mux or extra result storage is added.
  val exR1 = Mux(deferredLoad, regFile.io.rs1_data, exRead(ex.rs1_addr, ex.rs1_data))
  val exR2 = Mux(deferredLoad, regFile.io.rs2_data, exRead(ex.rs2_addr, ex.rs2_data))
  when(bypassMissWait) { deferredLoad := true.B }
  when(deferredLoad && !deferredBusy || wbKill) { deferredLoad := false.B }
  alu.io.alu_op := ex.ctrl.alu_op
  alu.io.is_w := ex.ctrl.is_w
  alu.io.alu_in1 := MuxLookup(ex.ctrl.sel_alu1, 0.U)(Seq(
    SEL_ALU1.RS1.U -> exR1, SEL_ALU1.PC.U -> ex.pc, SEL_ALU1.ZERO.U -> 0.U))
  alu.io.alu_in2 := MuxLookup(ex.ctrl.sel_alu2, 0.U)(Seq(
    SEL_ALU2.RS2.U -> exR2, SEL_ALU2.IMM.U -> ex.imm,
    SEL_ALU2.CONST4.U -> ex.instLen, SEL_ALU2.CONST0.U -> 0.U))
  bru.io.bru_op := ex.ctrl.bru_op
  bru.io.rs1_data := exR1
  bru.io.rs2_data := exR2
  jau.io.sel_jpc_i := ex.ctrl.sel_jpc_i
  jau.io.sel_jpc_o := ex.ctrl.sel_jpc_o
  jau.io.pc := ex.pc
  jau.io.rs1_data := exR1
  jau.io.imm := ex.imm
  val taken = Mux(ex.ctrl.bru_inst, bru.io.take_branch, ex.ctrl.redir_inst)
  val exNext = Mux(taken, jau.io.jmp_addr, ex.pc + ex.instLen)
  val fpImm = Mux(exFp.isStore, Cat(Fill(52, ex.inst(31)), ex.inst(31,25), ex.inst(11,7)),
    Cat(Fill(52, ex.inst(31)), ex.inst(31,20)))
  val address = exR1 + Mux(exFp.isLoad || exFp.isStore, fpImm, ex.imm)
  val allowEx = !downHold && !wbKill && !writeback.io.hartFatal && !stopped && !deferredWait
  io.l1d.req.valid := exMem && allowEx
  val req = io.l1d.req.bits
  req.op := MuxLookup(ex.ctrl.mem_op.asUInt, L1DOp.Load)(Seq(
    BreezeMemOp.Store.asUInt -> L1DOp.Store, BreezeMemOp.Lr.asUInt -> L1DOp.LR,
    BreezeMemOp.Sc.asUInt -> L1DOp.SC, BreezeMemOp.Amo.asUInt -> L1DOp.AMO))
  when(exFp.isStore) { req.op := L1DOp.Store }
  when(ex.inst(6,0) === OPCODE.MISC_MEM) { req.op := L1DOp.Fence }
  req.vaddr := address
  req.size := Mux(exFp.valid, Mux(exFp.isDouble, 3.U, 2.U), ex.inst(13,12))
  req.signed := !ex.inst(14)
  req.amoFunc := ex.ctrl.amo_func
  req.aq := ex.ctrl.amo_aq
  req.rl := ex.ctrl.amo_rl
  req.wdata := Mux(exFp.isStore, exFpr(1), exR2)
  req.rd.idx := ex.rd_addr
  req.rd.isFp := exBank
  req.isFlw := exFp.isLoad && !exFp.isDouble
  io.l1d.s1Kill := wbKill || io.hartFatal
  io.l1d.s2Kill := wbKill || io.hartFatal
  io.l1d.trapClearRsv := csrFile.io.trap.valid
  io.l1d.csr := csrFile.io.mmu_context

  mulUnit.io.req.valid := exMul && allowEx
  mulUnit.io.req.bits.rd := ex.rd_addr
  mulUnit.io.req.bits.op := ex.ctrl.mul_op
  mulUnit.io.req.bits.a := Cat(exR1(63), exR1).asSInt
  mulUnit.io.req.bits.b := Cat(exR2(63), exR2).asSInt
  when(ex.ctrl.mul_op === MUL_OP.MULHSU.U) { mulUnit.io.req.bits.b := Cat(0.U(1.W), exR2).asSInt }
  when(ex.ctrl.mul_op === MUL_OP.MULHU.U) {
    mulUnit.io.req.bits.a := Cat(0.U(1.W), exR1).asSInt
    mulUnit.io.req.bits.b := Cat(0.U(1.W), exR2).asSInt
  }
  when(ex.ctrl.mul_op === MUL_OP.MULW.U) {
    mulUnit.io.req.bits.a := Cat(Fill(33, exR1(31)), exR1(31,0)).asSInt
    mulUnit.io.req.bits.b := Cat(Fill(33, exR2(31)), exR2(31,0)).asSInt
  }
  val signedDiv = !ex.ctrl.div_op(0)
  val remDiv = ex.ctrl.div_op(1)
  val wordDiv = ex.ctrl.div_op(2)
  val dividend = Mux(wordDiv, Mux(signedDiv, Cat(Fill(32, exR1(31)), exR1(31,0)), Cat(0.U(32.W), exR1(31,0))), exR1)
  val divisor = Mux(wordDiv, Mux(signedDiv, Cat(Fill(32, exR2(31)), exR2(31,0)), Cat(0.U(32.W), exR2(31,0))), exR2)
  val negA = signedDiv && dividend(63)
  val negB = signedDiv && divisor(63)
  val byZero = divisor === 0.U
  val overflow = signedDiv && dividend === Mux(wordDiv, "hffffffff80000000".U, "h8000000000000000".U) && divisor.andR
  val fast = Mux(byZero, Mux(remDiv, dividend, Fill(64, 1.U)), Mux(remDiv, 0.U, dividend))
  divUnit.io.req.valid := exDiv && allowEx
  divUnit.io.req.bits.rd := ex.rd_addr
  divUnit.io.req.bits.dividendMag := Mux(negA, 0.U - dividend, dividend)
  divUnit.io.req.bits.divisorMag := Mux(negB, 0.U - divisor, divisor)
  divUnit.io.req.bits.quotientNeg := negA ^ negB
  divUnit.io.req.bits.remainderNeg := negA
  divUnit.io.req.bits.isRemainder := remDiv
  divUnit.io.req.bits.isWord := wordDiv
  divUnit.io.req.bits.fastValid := byZero || overflow
  divUnit.io.req.bits.fastData := Mux(wordDiv, Cat(Fill(32, fast(31)), fast(31,0)), fast)
  fpUnit.io.req.valid := exFpLong && allowEx
  val fpIn = fpUnit.io.req.bits
  val fp1 = Mux(exFp.usesGpr1, exR1, exFpr(0))
  fpIn.operandA := Mux(exFp.operation === BreezeFpOp.ADD.U, 0.U, fp1)
  fpIn.operandB := Mux(exFp.operation === BreezeFpOp.ADD.U, fp1, exFpr(1))
  fpIn.operandC := Mux(exFp.operation === BreezeFpOp.ADD.U, exFpr(1), exFpr(2))
  fpIn.rm := Mux(exFp.usesArchitecturalRm && exFp.rm === 7.U, csrFile.io.frm, exFp.rm)
  fpIn.operation := exFp.operation
  fpIn.opMod := exFp.opMod
  fpIn.srcFmt := exFp.srcFmt
  fpIn.dstFmt := exFp.dstFmt
  fpIn.intFmt := exFp.intFmt
  fpIn.rd.idx := ex.rd_addr
  fpIn.rd.isFp := exBank
  val resourceWait = (exMem && !io.l1d.req.ready) || (exMul && !mulUnit.io.req.ready) ||
    (exDiv && !divUnit.io.req.ready) || (exFpLong && !fpUnit.io.req.ready)
  exAdvance := allowEx && !resourceWait
  mulUnit.io.commit := wbCommit && wb.mul
  divUnit.io.commit := wbCommit && wb.div
  fpUnit.io.commit := wbCommit && wb.fp
  mulUnit.io.killUncommitted := wbKill || io.hartFatal
  divUnit.io.killUncommitted := wbKill || io.hartFatal
  fpUnit.io.killUncommitted := wbKill || io.hartFatal
  branchRedirect := exLegal && exAdvance &&
    ((taken =/= ex.pred.predTaken) || (taken && ex.pred.predTaken && jau.io.jmp_addr =/= ex.pred.predPc))

  // CSR evaluates in MEM, commits in WB. Existing conservative CSR state hazards remain.
  csrFile.io.csr_addr := mem.csrAddr
  csrFile.io.csr_cmd := Mux(mem.valid && !mem.exception, mem.csrCmd, CSR_CMD.NOP.U)
  csrFile.io.csr_reg_data := mem.csrSource
  csrFile.io.rs1_id := mem.rs1
  csrFile.io.rd_id := mem.rd.idx
  csrFile.io.commit_valid := wbCommit && wb.csrCmd =/= CSR_CMD.NOP.U
  csrFile.io.commit_addr := wb.csrAddr
  csrFile.io.commit_wdata := wb.csrData
  csrFile.io.commit_write_en := wb.csrWrite
  csrFile.io.fp_commit_valid := writeback.io.fpFlags.valid
  csrFile.io.fp_flags := writeback.io.fpFlags.bits
  csrFile.io.retire_valid := wbCommit
  csrFile.io.machineTimerInterrupt := io.machineTimerInterrupt
  csrFile.io.machineSoftwareInterrupt := io.machineSoftwareInterrupt
  csrFile.io.machineExternalInterrupt := io.externalInterrupts.orR
  csrFile.io.supervisorExternalInterrupt := io.supervisorExternalInterrupt
  csrFile.io.time := io.time
  csrFile.io.trap.valid := (wbCanLeave && wbExc) || interrupt
  csrFile.io.trap.is_interrupt := interrupt
  csrFile.io.trap.cause := Mux(interrupt, csrFile.io.interruptCause, Mux(wbMemExc, io.l1d.resp.bits.excCause, wb.cause))
  csrFile.io.trap.pc := Mux(interrupt, nextPc, wb.pc)
  csrFile.io.trap.tval := Mux(interrupt, 0.U, Mux(wbMemExc, io.l1d.resp.bits.tval, wb.tval))
  csrFile.io.mret_commit := wbCommit && wb.mret
  csrFile.io.sret_commit := wbCommit && wb.sret
  io.mmuContext := csrFile.io.mmu_context
  io.reservationKill := csrFile.io.trap.valid
  io.translationBlocked := wb.valid && wb.sfence
  io.sfence.valid := wb.valid && wb.sfence && !sfenceSent && io.l1d.drained && io.mmuIdle && !wb.exception && !io.hartFatal
  io.sfence.vaddr := wb.address
  io.sfence.asid := wb.storeData(15,0)
  io.sfence.useVaddr := wb.rs1 =/= 0.U
  io.sfence.useAsid := wb.rs2 =/= 0.U
  when(io.sfence.valid) { sfenceSent := true.B }
  when(wbCanLeave) { sfenceSent := false.B }
  io.frontendRedirect.valid := wbKill || branchRedirect
  // Flush once when SFENCE reaches WB, without issuing the final redirect early.
  io.frontendRedirect.flush := io.frontendRedirect.valid || (wb.valid && wb.sfence && !RegNext(wb.valid && wb.sfence, false.B))
  io.frontendRedirect.cacheFlush := wbCommit && wb.fencei
  io.frontendRedirect.target := Mux(csrFile.io.trap.valid, csrFile.io.trap_target,
    Mux(xret, csrFile.io.xret_target, Mux(wbKill, wb.nextPc, exNext)))
  io.frontendPhtUpdate := 0.U.asTypeOf(io.frontendPhtUpdate)
  io.frontendGhrUpdate := 0.U.asTypeOf(io.frontendGhrUpdate)
  val btb = RegInit(0.U.asTypeOf(new BreezeBTBUpdateReq(64)))
  when(!downHold || wbKill) { btb := 0.U.asTypeOf(btb) }
  val train = exLegal && exAdvance && !wbKill && (ex.ctrl.bru_inst || ex.ctrl.redir_inst)
  if (cfg.branchPredKind == flow.config.FrontendBranchPredictorKind.GShare) {
    when(train) {
      btb.valid := ex.pred.predType === FrontendPredType.BR || ex.pred.predType === FrontendPredType.JAL ||
        (ex.pred.predType === FrontendPredType.JALR && branchRedirect)
      btb.pc := ex.pc
      btb.target := jau.io.jmp_addr
      btb.predType := ex.pred.predType
      btb.taken := taken
    }
    io.frontendPhtUpdate.valid := train && ex.pred.predType === FrontendPredType.BR
    io.frontendPhtUpdate.idx := ex.pred.phtIdx
    io.frontendPhtUpdate.taken := taken
    io.frontendGhrUpdate.valid := io.frontendPhtUpdate.valid
    io.frontendGhrUpdate.taken := taken
  }
  io.frontendBtbUpdate := btb
  io.frontendBtbUpdate.valid := btb.valid && !wbKill && !downHold && !io.hartFatal

  val csrStateHazard = (ex.valid && ex.ctrl.csr_cmd =/= CSR_CMD.NOP.U) ||
    (mem.valid && mem.csrCmd =/= CSR_CMD.NOP.U) || (wb.valid && wb.csrWrite)
  val oldCsrUsesRs1 = ctrl.sel_alu1 === SEL_ALU1.RS1.U || ctrl.bru_inst || ctrl.is_sfence_vma ||
    ctrl.sel_jpc_i === SEL_JPC_I.RS1.U || ctrl.csr_cmd === CSR_CMD.RW.U ||
    ctrl.csr_cmd === CSR_CMD.RS.U || ctrl.csr_cmd === CSR_CMD.RC.U
  val csrRegHazard = idCsr && Seq(
    (ex.valid && exWrites && !exBank && ex.rd_addr =/= 0.U, ex.rd_addr),
    (mem.valid && mem.writes && !mem.rd.isFp && mem.rd.idx =/= 0.U, mem.rd.idx),
    (wb.valid && wb.writes && !wb.rd.isFp && wb.rd.idx =/= 0.U, wb.rd.idx)
  ).map { case (v,r) => v && ((oldCsrUsesRs1 && rs1 === r) || (gpr2Used && rs2 === r)) }.reduce(_ || _)
  // Ordinary CSR/local-FP destinations are not EX bypassable; wait for WB write-through.
  val ordinaryHazard = Seq((ex.valid, exWrites, exBank, ex.rd_addr, ex.ctrl.sel_wb === SEL_WB.CSR.U || exFp.valid),
    (mem.valid, mem.writes, mem.rd.isFp, mem.rd.idx, mem.csrCmd =/= CSR_CMD.NOP.U || mem.rd.isFp)).map {
      case (v,w,b,r,h) => v && w && h && operands.take(3).map(o => o.used && o.rd.isFp === b && o.rd.idx === r && (b || r =/= 0.U)).reduce(_ || _)
  }.reduce(_ || _)
  io.fetchBuffer.ready := exAdvance && !scoreboard.io.hazard && !csrStateHazard && !csrRegHazard &&
    !ordinaryHazard && !serialInFlight && !deferredLoad && !writeback.io.idStarve && !branchRedirect && !wbKill &&
    !csrFile.io.interruptPending && !sleeping && !stopped && !io.hartFatal
  idLeave := io.fetchBuffer.fire
  when(reset.asBool || wbKill || branchRedirect) { ex.valid := false.B }
    .elsewhen(exAdvance) {
      ex.valid := idLeave
      when(idLeave) {
        ex.pc := io.fetchBuffer.bits.pc
        ex.inst := inst
        ex.rawInst := io.fetchBuffer.bits.rawInst
        ex.instLen := Mux(io.fetchBuffer.bits.instLen === 2.U, 2.U, 4.U)
        ex.instruction_access_fault := io.fetchBuffer.bits.instructionAccessFault
        ex.instruction_page_fault := io.fetchBuffer.bits.instructionPageFault
        ex.instruction_fault_second_parcel := io.fetchBuffer.bits.instructionFaultSecondParcel
        ex.illegal_inst := io.fetchBuffer.bits.illegalCompressed || (decoder.io.illegal_inst && !fpCtrl.valid) ||
          (fpCtrl.valid && !csrFile.io.fp_enabled) || (ctrl.is_mret && csrFile.io.mret_illegal) ||
          (ctrl.is_sret && csrFile.io.sret_illegal) || (ctrl.is_wfi && csrFile.io.wfi_illegal) ||
          (ctrl.is_sfence_vma && csrFile.io.sfence_vma_illegal)
        ex.ctrl := ctrl
        ex.is_ecall := ctrl.is_ecall
        ex.is_ebreak := ctrl.is_ebreak
        ex.is_mret := ctrl.is_mret
        ex.is_sret := ctrl.is_sret
        ex.is_wfi := ctrl.is_wfi
        ex.estop := idEstop
        ex.pred := io.fetchBuffer.bits.pred
        ex.rs1_addr := rs1
        ex.rs2_addr := rs2
        ex.rd_addr := rd
        ex.rs1_data := regFile.io.rs1_data
        ex.rs2_data := regFile.io.rs2_data
        ex.imm := immGen.io.imm
        ex.src1 := regFile.io.rs1_data
        ex.src2 := regFile.io.rs2_data
        exFp := fpCtrl
        exFpr := VecInit(Seq(fpRegFile.io.rs1Data, fpRegFile.io.rs2Data, fpRegFile.io.rs3Data))
      }
    }.otherwise {
      ex.rs1_data := exR1
      ex.rs2_data := exR2
    }
  when(reset.asBool || wbKill) { mem.valid := false.B; wb.valid := false.B }
    .elsewhen(!downHold) {
      // EX wait never holds MEM/WB: insert a bubble behind the older MEM.
      mem := 0.U.asTypeOf(mem)
      mem.valid := ex.valid && exAdvance
      mem.pc := ex.pc
      mem.nextPc := exNext
      mem.inst := ex.inst
      mem.rawInst := ex.rawInst
      mem.instLen := ex.instLen
      mem.rd.idx := ex.rd_addr
      mem.rd.isFp := exBank
      mem.writes := exWrites
      val local = Mux(exFp.localOp === BreezeFpLocalOp.FMV_X.U,
        Mux(exFp.isDouble, exFpr(0), Cat(Fill(32, exFpr(0)(31)), exFpr(0)(31,0))),
        Mux(exFp.isDouble, exR1, Cat("hffffffff".U(32.W), exR1(31,0))))
      mem.data := Mux(exFp.localOp =/= BreezeFpLocalOp.NONE.U, local, alu.io.alu_out)
      mem.address := Mux(ex.ctrl.is_sfence_vma, exR1, address)
      mem.storeData := req.wdata
      mem.mem := exMem
      mem.load := exLoad
      mem.mul := exMul
      mem.div := exDiv
      mem.fp := exFpLong
      mem.serial := ex.ctrl.fencei || ex.ctrl.is_sfence_vma || ex.is_wfi || ex.estop
      mem.fencei := ex.ctrl.fencei
      mem.sfence := ex.ctrl.is_sfence_vma
      mem.wfi := ex.is_wfi
      mem.estop := ex.estop
      mem.mret := ex.is_mret
      mem.sret := ex.is_sret
      mem.exception := ex.instruction_access_fault || ex.instruction_page_fault || ex.illegal_inst || ex.is_ecall || ex.is_ebreak
      mem.cause := MuxCase(2.U, Seq(ex.instruction_access_fault -> 1.U, ex.instruction_page_fault -> 12.U,
        ex.is_ebreak -> 3.U, ex.is_ecall -> MuxLookup(csrFile.io.current_privilege, 11.U)(Seq(0.U -> 8.U, 1.U -> 9.U))))
      mem.tval := Mux(ex.instruction_access_fault || ex.instruction_page_fault,
        ex.pc + Mux(ex.instruction_fault_second_parcel, 2.U, 0.U), Mux(ex.illegal_inst, ex.rawInst, 0.U))
      mem.csrAddr := ex.ctrl.csr_addr
      mem.csrCmd := ex.ctrl.csr_cmd
      mem.csrSource := Mux(ex.ctrl.csr_cmd(2), Cat(0.U(59.W), ex.rs1_addr), exR1)
      mem.rs1 := ex.rs1_addr
      mem.rs2 := ex.rs2_addr
      mem.predictionMiss := branchRedirect
      wb := mem
      when(mem.valid && mem.csrCmd =/= CSR_CMD.NOP.U) {
        wb.data := csrFile.io.csr_old_data
        wb.csrWrite := csrFile.io.csr_write_en
        wb.csrData := csrFile.io.csr_new_data
        when(csrFile.io.csr_illegal && !mem.exception) {
          wb.exception := true.B; wb.cause := 2.U; wb.tval := mem.rawInst
        }
      }
    }
  when(reset.asBool) { nextPc := io.resetAddr }
    .elsewhen(wbKill) { nextPc := io.frontendRedirect.target }
    .elsewhen(wbCommit) { nextPc := wb.nextPc }
  when(csrFile.io.wfiWakeup) { sleeping := false.B }
    .elsewhen(wbCommit && wb.wfi) { sleeping := true.B }
  when(wbCommit && wb.estop) { stopped := true.B }
  io.estop := stopped || (wbCommit && wb.estop)

  io.backendEvents := io.hpmEvents
  io.backendEvents.controlRetired := wbCommit && (wb.inst(6,0) === OPCODE.BRANCH || wb.inst(6,0) === OPCODE.JAL || wb.inst(6,0) === OPCODE.JALR)
  io.backendEvents.controlTaken := io.backendEvents.controlRetired && wb.nextPc =/= wb.pc + wb.instLen
  io.backendEvents.predictionMiss := io.backendEvents.controlRetired && wb.predictionMiss
  io.backendEvents.memStallCycle := io.l1d.s2Hold
  io.backendEvents.loadUseStall := io.fetchBuffer.valid && !idLeave && scoreboard.io.sourceStall(V1LongSource.L1D)
  io.backendEvents.mulSourceStall := scoreboard.io.sourceStall(V1LongSource.MUL)
  io.backendEvents.divSourceStall := scoreboard.io.sourceStall(V1LongSource.DIV)
  io.backendEvents.wbPortConflict := writeback.io.conflict
  csrFile.io.hpmEvents := io.backendEvents

  io.observe := 0.U.asTypeOf(io.observe)
  io.observe.idLeave := idLeave
  io.observe.exFire := ex.valid && exAdvance
  io.observe.exPc := ex.pc
  io.observe.commit := wbCommit
  io.observe.commitPc := wb.pc
  io.observe.gprWrite := writeback.io.gprWrite
  io.observe.fprWrite := writeback.io.fprWrite
  io.observe.gprBusy := scoreboard.io.gprBusy
  io.observe.fprBusy := scoreboard.io.fprBusy
  io.observe.memHold := downHold
  io.observe.exHold := downHold || resourceWait || deferredWait
  io.observe.grant := writeback.io.grant
  io.observe.fpIn := fpUnit.io.req.fire
  io.observe.fpOut := fpUnit.io.result.fire
  io.observe.fpFlags := writeback.io.fpFlags
  io.observe.mulIn := mulUnit.io.req.fire
  io.observe.divIn := divUnit.io.req.fire
  io.observe.divIterating := divUnit.arithmeticActive
  io.observe.translationBlocked := io.translationBlocked
  io.tandem.foreach { t =>
    t := 0.U.asTypeOf(t)
    t.valid := wbCommit
    t.pc := wb.pc; t.inst := wb.rawInst; t.nextPc := wb.nextPc
    t.estop := wb.estop
    t.rdWriteEn := wbOrdinary && (wb.rd.isFp || wb.rd.idx =/= 0.U)
    t.rdAddr := wb.rd.idx; t.rdData := wbData
    t.rdPending := wbCommit && wbLong && wb.writes && (wb.rd.isFp || wb.rd.idx =/= 0.U)
    t.rdIsFp := wb.rd.isFp
    t.lateWriteValid := writeback.io.clear.valid
    t.lateWriteIsFp := writeback.io.clear.bits.isFp
    t.lateWriteRd := writeback.io.clear.bits.idx
    t.lateWriteData := Mux(writeback.io.clear.bits.isFp, writeback.io.fprWrite.bits.data, writeback.io.gprWrite.bits.data)
    t.lateWriteError := io.l1d.late.fire && io.l1d.late.bits.error
    t.memEn := wb.mem && wb.inst(6,0) =/= OPCODE.MISC_MEM; t.memAddr := wb.address
    t.memAlignedAddr := wb.address & "hfffffffffffffff8".U
    t.memIsWrite := wb.mem && (wb.inst(6,0) === OPCODE.STORE || wb.inst(6,0) === OPCODE.STORE_FP ||
      (wb.inst(6,0) === OPCODE.AMO && wb.inst(31,27) =/= 2.U))
    t.memRData := Mux(wbDone, wbData, 0.U)
    t.memWData := (wb.storeData << Cat(wb.address(2,0), 0.U(3.W)))(63,0)
    t.memWMask := (((1.U(9.W) << (1.U(4.W) << wb.inst(13,12))) - 1.U) << wb.address(2,0))(7,0)
  }
  io.debug.foreach { d =>
    d := 0.U.asTypeOf(d)
    d.decodeValid := io.fetchBuffer.valid; d.decodeInst := inst; d.decodePc := io.fetchBuffer.bits.pc
    d.idExeValid := ex.valid; d.idExeInst := ex.inst; d.idExePc := ex.pc
    d.idExeRs1Addr := ex.rs1_addr; d.idExeRs2Addr := ex.rs2_addr
    d.idExeSrc1 := ex.rs1_data; d.idExeSrc2 := ex.rs2_data
    d.exeSrc1 := alu.io.alu_in1; d.exeSrc2 := alu.io.alu_in2
    d.exeAluOut := alu.io.alu_out; d.exeBruTaken := bru.io.take_branch; d.exeJumpAddr := jau.io.jmp_addr
    d.exeMemValid := mem.valid; d.exeMemPc := mem.pc; d.exeMemData := mem.data; d.exeMemRdAddr := mem.rd.idx
    d.memWaitingResp := io.l1d.s2Hold
    d.memWbValid := wbCommit; d.memWbPc := wb.pc; d.memWbInst := wb.inst; d.wbData := wbData
    d.exeBypassRs1 := exR1; d.exeBypassRs2 := exR2
    d.loadUseHazard := io.backendEvents.loadUseStall
    d.redirectValid := io.frontendRedirect.valid
    d.csrMtvec := csrFile.io.mtvec; d.csrMcause := csrFile.io.debug.get.mcause; d.csrMepc := csrFile.io.debug.get.mepc
    d.memWbException := wbExc; d.memWbTrapValid := csrFile.io.trap.valid
    d.memWbIsEcall := wb.inst === "h00000073".U; d.memWbIsMret := wb.mret; d.memWbIsWfi := wb.wfi
    d.wfiSleeping := sleeping; d.csrIllegal := csrFile.io.csr_illegal
  }
  if (enabledebug) {
    // Simulation observability only; it never qualifies a functional signal.
    val cycle = RegInit(0.U(32.W))
    cycle := cycle + 1.U
    when(!reset.asBool) {
      when(idLeave) { printf(cf"[V1-CYCLE] c=${cycle} kind=id pc=0x${io.fetchBuffer.bits.pc}%x inst=0x${inst}%x\n") }
      when(ex.valid && exAdvance) { printf(cf"[V1-CYCLE] c=${cycle} kind=ex pc=0x${ex.pc}%x\n") }
      when(wbCommit) { printf(cf"[V1-CYCLE] c=${cycle} kind=commit pc=0x${wb.pc}%x inst=0x${wb.inst}%x\n") }
      when(fpUnit.io.req.fire) { printf(cf"[V1-CYCLE] c=${cycle} kind=fpIn pc=0x${ex.pc}%x rd=${ex.rd_addr}\n") }
      when(fpUnit.io.result.fire) { printf(cf"[V1-CYCLE] c=${cycle} kind=fpOut fp=${fpUnit.io.result.bits.rd.isFp} rd=${fpUnit.io.result.bits.rd.idx}\n") }
      when(writeback.io.gprWrite.valid) { printf(cf"[V1-CYCLE] c=${cycle} kind=gpr rd=${writeback.io.gprWrite.bits.idx} data=0x${writeback.io.gprWrite.bits.data}%x\n") }
      when(writeback.io.fprWrite.valid) { printf(cf"[V1-CYCLE] c=${cycle} kind=fpr rd=${writeback.io.fprWrite.bits.idx} data=0x${writeback.io.fprWrite.bits.data}%x\n") }
      when(io.l1d.late.fire) { printf(cf"[V1-CYCLE] c=${cycle} kind=late rd=${io.l1d.late.bits.rd.idx} error=${io.l1d.late.bits.error}\n") }
    }
  }
  val pastDownHold = RegNext(downHold, false.B)
  val pastKill = RegNext(wbKill, false.B)
  val pastActive = RegNext(!reset.asBool, false.B)
  val heldMem = RegNext(mem.asUInt)
  val heldWb = RegNext(wb.asUInt)
  when(pastActive && !reset.asBool && pastDownHold && !pastKill) {
    assert(mem.asUInt === heldMem && wb.asUInt === heldWb, "[V1 S09] held MEM/WB metadata changed")
  }
  when(!reset.asBool) {
    assert(!io.l1d.resp.valid || (wb.valid && wb.mem), "[V1 S15] L1D resp is not aligned to WB")
    assert(!(io.l1d.resp.valid && io.l1d.s2Hold), "[V1 S15] simultaneous hold and decision")
    assert(!(mem.valid && mem.mem && downHold) || io.l1d.s2Hold, "[V1 stall direction] MEM memory held without s2Hold")
    assert(!serialWait || (!ex.valid && !mem.valid), "[V1 stall direction] serial wait with younger pipeline entries")
    assert(!wbKill || !(io.l1d.req.fire || mulUnit.io.req.fire || divUnit.io.req.fire || fpUnit.io.req.fire), "[V1 S13] younger request survived WB kill")
    when(downHold && wb.valid) {
      assert(!(io.frontendBtbUpdate.valid || io.frontendPhtUpdate.valid || io.frontendGhrUpdate.valid ||
        io.frontendRedirect.valid || csrFile.io.commit_valid || csrFile.io.trap.valid ||
        csrFile.io.mret_commit || csrFile.io.sret_commit), "[V1 S09] control side effect while WB held")
      // S09 explicitly permits requests with their own one-shot state (A08/B01).
      // SFENCE must request while WB waits, then wait for the subsequent idle.
      assert(!io.sfence.valid || (wb.sfence && !sfenceSent && io.l1d.drained && io.mmuIdle),
        "[V1 S09] SFENCE repeated or issued before drain/idle")
    }
    assert(!downHold || !wbCommit, "[V1 S09] held WB retired")
    assert(!wbCommit || !wb.mem || io.l1d.resp.valid, "[V1 S15] memory retired without S2 decision")
  }
}
