package flow.backend

import chisel3._
import chisel3.util._
import flow.config.BackendConfig
import flow.interface._
import flow.core._
import flow.divider.DivUnit
import flow.multiplier.MulUnit
import flow.fpu._
import flow.platform.BreezeMcuPlatform

/** Metadata for MEM/WB, not a completion queue. Arithmetic stays in its source. */
class Stage(val cfg: BackendConfig) extends Bundle {
  val valid = Bool()
  val solo = Bool()
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
  val copro = Bool()
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

/** Five-stage backend: ID/EX/MEM/PERM/WB. EX accepts, WB commits, W2/lateReg write the RFs. */
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
    val frontendFastRedirect = Output(new FrontendRedirectIO(64))
    val frontendSlowRedirect = Output(new FrontendRedirectIO(64))
    val mmuContext = Output(new BreezeMmuContext(64))
    val sfence = Output(new BreezeSfenceReq(64))
    val reservationKill = Output(Bool())
    val hpmEvents = Input(new BreezeHpmEvents)
    val backendEvents = Output(new BreezeHpmEvents)
    val hartFatal = Output(Bool())
    val estop = Output(Bool())
    val tandem = if (cfg.enableTandem) Some(Output(new TracePayload(64))) else None
    val debug = if (enabledebug) Some(new BackendDebugIO(64)) else None
    val observe = Output(new BackendObservation)
  })
  val decoder = Module(new Decoder)
  val fpDecoder = Module(new BreezeFpDecoder)
  val immGen = Module(new ImmGen(64))
  val regFile = Module(new RegFile(64))
  val fpRegFile = Module(new BreezeFpRegFile)
  val csrFile = Module(new CSRFile(64, enabledebug = enabledebug, hartId = hartId,
    privilegeProfile = cfg.privilegeProfile, enableCompressed = cfg.enableCompressed))
  val scoreboard = Module(new Scoreboard(pipeStages = 4))
  val writeback = Module(new Writeback(withCoprocessor = cfg.coprocessorStub.nonEmpty))
  val mulUnit = Module(new MulUnit(registeredCommit = true))
  val divUnit = Module(new DivUnit(registeredCommit = true))
  val fpUnit = Module(new FpUnit(issueReservation = true))
  val coprocessor = cfg.coprocessorStub.map(seed => Module(new CoprocessorStub(seed)))
  val coproUsed = RegInit(false.B)
  val alu = Module(new ALU(64))
  val bru = Module(new BRU(64))
  val jau = Module(new JAU(64))
  val ex = RegInit(0.U.asTypeOf(new BreezeBackendIDEXE(64, cfg.ghrLength)))
  val exFp = RegInit(0.U.asTypeOf(new BreezeFpCtrl))
  val exFpr = RegInit(VecInit(Seq.fill(3)(0.U(64.W))))
  val exFpIssued = RegInit(false.B)
  val exRedirectSent = RegInit(false.B)
  val mem = RegInit(0.U.asTypeOf(new Stage(cfg)))
  val perm = RegInit(0.U.asTypeOf(new Stage(cfg)))
  val wb = RegInit(0.U.asTypeOf(new Stage(cfg)))
  val sleeping = RegInit(false.B)
  val stopped = RegInit(false.B)
  val exSolo = RegInit(false.B)
  val soloArm = RegInit(false.B)
  val replayReason = RegInit(0.U(3.W))
  val nextPc = RegInit(0.U(64.W))
  val wbKill = Wire(Bool())
  val exAdvance = Wire(Bool())
  val wbCommit = Wire(Bool())
  val branchRedirect = Wire(Bool())
  val idLeave = Wire(Bool())
  val killD = RegNext(wbKill || io.hartFatal, false.B)
  val mulPortCommitD = RegNext(wbCommit && wb.mul, false.B)

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
  val idCopro = cfg.coprocessorStub.nonEmpty.B && inst(6,0) === "h0b".U
  val idCsr = ctrl.csr_cmd =/= CSR_CMD.NOP.U
  val idEstop = inst === "h7ff00073".U
  val idSerial = ctrl.fencei || ctrl.is_sfence_vma || idEstop || idCsr
  val olderEmpty = !ex.valid && !mem.valid && !perm.valid && !wb.valid
  val serialInFlight = (ex.valid && (ex.ctrl.fencei || ex.ctrl.is_sfence_vma || ex.estop ||
    ex.ctrl.csr_cmd =/= CSR_CMD.NOP.U)) || (mem.valid && mem.serial) ||
    (perm.valid && perm.serial) || (wb.valid && wb.serial)
  val soloInFlight = (ex.valid && exSolo) || (mem.valid && mem.solo) ||
    (perm.valid && perm.solo) || (wb.valid && wb.solo)
  val idAtomic = ctrl.mem_op === BreezeMemOp.Lr || ctrl.mem_op === BreezeMemOp.Sc ||
    ctrl.mem_op === BreezeMemOp.Amo
  val idSolo = soloArm || ctrl.mem_op === BreezeMemOp.Amo || (idAtomic && (ctrl.amo_aq || ctrl.amo_rl))
  // A retiring CPU S3 can create PS/MSHR ownership at this edge. Sampling
  // only after the older pipeline is empty closes that obligation handoff.
  val cpuDrainedD = RegNext(io.l1d.cpuDrained && olderEmpty, false.B)
  // The first SFENCE ID edge blocks new translation. Its idle observation
  // becomes eligible only after that blocked edge and after older CPU work.
  val sfenceAtId = io.fetchBuffer.valid && ctrl.is_sfence_vma
  val mmuIdleD = RegNext(io.mmuIdle && olderEmpty && sfenceAtId, false.B)
  val scalarDrained = !(scoreboard.io.gprBusy.orR || scoreboard.io.fprBusy.orR || fpUnit.io.committedFlagsOnly)
  // Busy and flags ownership are already registers (T17 raw-busy contract).
  val scalarDrainedD = scalarDrained
  val serialReady = (!ctrl.fencei || cpuDrainedD) &&
    (!ctrl.is_sfence_vma || (cpuDrainedD && mmuIdleD)) &&
    (!(idEstop || idCsr) || scalarDrainedD)
  val serialIdWait = idSerial && (!olderEmpty || !serialReady)
  val soloIdWait = idSolo && (!olderEmpty || (soloArm && replayReason =/= 4.U && !io.l1d.replayWake))
  val gpr1Used = Mux(fpCtrl.valid, fpCtrl.usesGpr1,
    (ctrl.sel_alu1 === SEL_ALU1.RS1.U && !(idCsr && ctrl.csr_cmd(2))) || ctrl.bru_inst ||
      ctrl.sel_jpc_i === SEL_JPC_I.RS1.U || ctrl.is_sfence_vma)
  val gpr2Used = !fpCtrl.valid && (ctrl.sel_alu2 === SEL_ALU2.RS2.U || ctrl.bru_inst ||
    ctrl.mem_op === BreezeMemOp.Store || ctrl.mem_op === BreezeMemOp.Sc ||
    ctrl.mem_op === BreezeMemOp.Amo || ctrl.is_sfence_vma)
  val idWrites = !idCopro && Mux(fpCtrl.valid, fpCtrl.writesFpr || fpCtrl.writesGpr, ctrl.wb_en)
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

  // Cache S3 is consumed directly at WB. A valid response is never captured.
  val wbResponse = wb.valid && wb.mem && io.l1d.resp.valid
  val wbDone = wbResponse && io.l1d.resp.bits.kind === L1DRespKind.Done
  val wbMiss = wbResponse && io.l1d.resp.bits.kind === L1DRespKind.Mshr
  val wbMemExc = wbResponse && io.l1d.resp.bits.kind === L1DRespKind.Exc
  val coproDecisionValid = coprocessor.map(_.io.decision.valid).getOrElse(false.B)
  val coproDecision = coprocessor.map(_.io.decision.bits).getOrElse(0.U.asTypeOf(new CoprocessorDecision))
  val wbCoproExc = wb.valid && wb.copro && coproDecisionValid && coproDecision.exception
  val wbExc = wb.valid && (wb.exception || wbMemExc || wbCoproExc)
  val wbReplay = (wbResponse && io.l1d.resp.bits.kind === L1DRespKind.Replay) ||
    (wb.valid && wb.copro && !wb.solo && !coproDecisionValid)
  val downHold = wb.valid && wb.solo && !wb.exception &&
    ((wb.mem && !io.l1d.resp.valid) || (wb.copro && !coproDecisionValid))
  val wbCanLeave = wb.valid && !downHold && !writeback.io.hartFatal && !stopped
  wbCommit := wbCanLeave && !wbExc && !wbReplay
  // Preserve the frozen P09 external-event observation boundary. CSR
  // priority/masking uses its existing state; it is listed separately in
  // the R7 evidence rather than described as a newly registered decision.
  val interrupt = csrFile.io.interruptPending && !ex.valid && !mem.valid && !perm.valid && !wb.valid &&
    !io.l1d.mmioBusy && !writeback.io.hartFatal && !stopped
  val fenceRedirect = wbCommit && (wb.fencei || wb.sfence)
  val xret = wbCommit && (wb.mret || wb.sret)
  val satp = wbCommit && wb.csrWrite && wb.csrAddr === CSRMAP.satp.U
  wbKill := (wbCanLeave && (wbExc || wbReplay)) || interrupt || fenceRedirect || xret || satp || (wbCommit && wb.wfi)

  val wbLong = wb.mul || wb.div || wb.fp || (wb.mem && wb.load && wbMiss)
  val wbOrdinary = wbCommit && wb.writes && !wb.mul && !wb.div && !wb.fp && (!wb.mem || wbDone)
  val wbData = Mux(wb.mem, io.l1d.resp.bits.data, wb.data)
  writeback.io.ordinary.valid := wbOrdinary
  writeback.io.ordinary.bits.rd := wb.rd
  writeback.io.ordinary.bits.data := wbData
  writeback.io.ordinary.bits.flags := 0.U
  writeback.io.late <> io.l1d.late

  io.hartFatal := writeback.io.hartFatal
  regFile.io.rs1_addr := rs1
  regFile.io.rs2_addr := rs2
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
  scoreboard.io.set.bits.source := Mux(wb.mem, LongSource.L1D.U,
    Mux(wb.div, LongSource.DIV.U, Mux(wb.mul, LongSource.MUL.U, LongSource.FPU.U)))
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
  val exWrites = !(cfg.coprocessorStub.nonEmpty.B && ex.inst(6,0) === "h0b".U) && Mux(exFp.valid, exFp.writesFpr || exFp.writesGpr, ex.ctrl.wb_en)
  for ((stage, index) <- Seq(mem -> 1, perm -> 2, wb -> 3)) {
    // A WB memory destination remains protected through its whole S2 cycle,
    // independently of Done/Mshr/Exc. Next cycle W2 writes or busy takes over.
    scoreboard.io.pipe(index).valid := stage.valid && (stage.mul || stage.div || stage.fp || stage.mem)
    scoreboard.io.pipe(index).bits.rd := stage.rd
    scoreboard.io.pipe(index).bits.source := Mux(stage.mem, LongSource.L1D.U,
      Mux(stage.div, LongSource.DIV.U, Mux(stage.mul, LongSource.MUL.U, LongSource.FPU.U)))
    // CSR must wait even for a store/Fence or an FP-to-x0 producer.
    when(!stage.writes) { scoreboard.io.pipe(index).bits.rd.idx := 0.U }
  }
  scoreboard.io.pipe(0).valid := exMul || exDiv || exFpLong || exMem
  scoreboard.io.pipe(0).bits.rd.idx := Mux(exWrites, ex.rd_addr, 0.U)
  scoreboard.io.pipe(0).bits.rd.isFp := exBank
  scoreboard.io.pipe(0).bits.source := Mux(exMem, LongSource.L1D.U,
    Mux(exDiv, LongSource.DIV.U, Mux(exMul, LongSource.MUL.U, LongSource.FPU.U)))
  // loadUseBypass remains a configuration compatibility field, with no effect.
  // WB bypass eligibility uses registered metadata only, never S2/commit/done.
  // A memory result becomes available through W2, not the WB response bus.
  def exRead(addr: UInt, saved: UInt, fp: Boolean = false): UInt = {
    def matches(dest: L1DDestination): Bool =
      dest.isFp === fp.B && (fp.B || dest.idx =/= 0.U) && addr === dest.idx
    val fromW2 = writeback.io.w2.valid && matches(writeback.io.w2.bits.rd)
    val fromWb = wb.valid && wb.writes && !wb.mem && !wb.mul && !wb.div && !wb.fp && matches(wb.rd)
    val fromMem = mem.valid && mem.writes && !mem.mem && !mem.mul && !mem.div && !mem.fp &&
      mem.csrCmd === CSR_CMD.NOP.U && matches(mem.rd)
    val fromPerm = perm.valid && perm.writes && !perm.mem && !perm.mul && !perm.div && !perm.fp &&
      perm.csrCmd === CSR_CMD.NOP.U && matches(perm.rd)
    Mux(fromMem, mem.data, Mux(fromPerm, perm.data,
      Mux(fromWb, wb.data, Mux(fromW2, writeback.io.w2.bits.data, saved))))
  }
  val exR1 = exRead(ex.rs1_addr, ex.rs1_data)
  val exR2 = exRead(ex.rs2_addr, ex.rs2_data)
  val exFprRead = VecInit(Seq(exRead(ex.rs1_addr, exFpr(0), fp = true),
    exRead(ex.rs2_addr, exFpr(1), fp = true), exRead(ex.inst(31,27), exFpr(2), fp = true)))
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
  // RV64A carries no address immediate (Decoder selects CONST0). Preserve
  // rs1 exactly; funct5/rs2 bits must not become an I/S offset at this port.
  val atomicAddress = ex.ctrl.mem_op === BreezeMemOp.Lr || ex.ctrl.mem_op === BreezeMemOp.Sc ||
    ex.ctrl.mem_op === BreezeMemOp.Amo
  val address = exR1 + Mux(atomicAddress, 0.U, Mux(exFp.isLoad || exFp.isStore, fpImm, ex.imm))
  val allowEx = !writeback.io.hartFatal && !stopped
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
  req.wdata := Mux(exFp.isStore, exFprRead(1), exR2)
  req.rd.idx := ex.rd_addr
  req.rd.isFp := exBank
  req.isFlw := exFp.isLoad && !exFp.isDouble
  req.solo := exSolo
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
  fpUnit.io.req.valid := exFpLong && !exFpIssued && !writeback.io.hartFatal && !stopped
  val fpIn = fpUnit.io.req.bits
  val fp1 = Mux(exFp.usesGpr1, exR1, exFprRead(0))
  fpIn.operandA := Mux(exFp.operation === BreezeFpOp.ADD.U, 0.U, fp1)
  fpIn.operandB := Mux(exFp.operation === BreezeFpOp.ADD.U, fp1, exFprRead(1))
  fpIn.operandC := Mux(exFp.operation === BreezeFpOp.ADD.U, exFprRead(1), exFprRead(2))
  fpIn.rm := Mux(exFp.usesArchitecturalRm && exFp.rm === 7.U, csrFile.io.frm, exFp.rm)
  fpIn.operation := exFp.operation
  fpIn.opMod := exFp.opMod
  fpIn.srcFmt := exFp.srcFmt
  fpIn.dstFmt := exFp.dstFmt
  fpIn.intFmt := exFp.intFmt
  fpIn.rd.idx := ex.rd_addr
  fpIn.rd.isFp := exBank
  val resourceWait = exMem && !io.l1d.canAccept
  exAdvance := allowEx && !resourceWait
  mulUnit.io.commit := mulPortCommitD
  divUnit.io.commit := RegNext(wbCommit && wb.div, false.B)
  fpUnit.io.commit := RegNext(wbCommit && wb.fp, false.B)
  mulUnit.io.killUncommitted := killD
  divUnit.io.killUncommitted := killD
  fpUnit.io.killUncommitted := killD
  branchRedirect := exLegal && !exRedirectSent &&
    ((taken =/= ex.pred.predTaken) || (taken && ex.pred.predTaken && jau.io.jmp_addr =/= ex.pred.predPc))
  val redirected = exRedirectSent || branchRedirect
  when(reset.asBool || wbKill || io.hartFatal || exAdvance) {
    exFpIssued := false.B
    exRedirectSent := false.B
  }.otherwise {
    when(fpUnit.io.req.fire) { exFpIssued := true.B }
    when(branchRedirect) { exRedirectSent := true.B }
  }

  // CSR evaluates in PERM, commits in WB. Existing conservative CSR state hazards remain.
  csrFile.io.csr_addr := perm.csrAddr
  csrFile.io.csr_cmd := Mux(perm.valid && !perm.exception, perm.csrCmd, CSR_CMD.NOP.U)
  csrFile.io.csr_reg_data := perm.csrSource
  csrFile.io.rs1_id := perm.rs1
  csrFile.io.rd_id := perm.rd.idx
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
  csrFile.io.trap.cause := Mux(interrupt, csrFile.io.interruptCause, Mux(wbMemExc, io.l1d.resp.bits.excCause, Mux(wbCoproExc, coproDecision.cause, wb.cause)))
  csrFile.io.trap.pc := Mux(interrupt, nextPc, wb.pc)
  csrFile.io.trap.tval := Mux(interrupt, 0.U, Mux(wbMemExc, io.l1d.resp.bits.tval, Mux(wbCoproExc, coproDecision.tval, wb.tval)))
  csrFile.io.mret_commit := wbCommit && wb.mret
  csrFile.io.sret_commit := wbCommit && wb.sret
  io.mmuContext := csrFile.io.mmu_context
  io.reservationKill := csrFile.io.trap.valid
  io.translationBlocked := (io.fetchBuffer.valid && ctrl.is_sfence_vma) ||
    (ex.valid && ex.ctrl.is_sfence_vma) || (mem.valid && mem.sfence) || (perm.valid && perm.sfence) || (wb.valid && wb.sfence)
  io.sfence.valid := wbCommit && wb.sfence
  io.sfence.vaddr := wb.address
  io.sfence.asid := wb.storeData(15,0)
  io.sfence.useVaddr := wb.rs1 =/= 0.U
  io.sfence.useAsid := wb.rs2 =/= 0.U
  when(wbKill || io.hartFatal) { soloArm := false.B }
  when(wbReplay) { soloArm := true.B; replayReason := Mux(wb.copro, 4.U, io.l1d.resp.bits.replayReason) }
  when(idLeave && soloArm) { soloArm := false.B }
  io.frontendRedirect.valid := wbKill || branchRedirect
  // Flush once when SFENCE reaches WB, without issuing the final redirect early.
  io.frontendRedirect.flush := io.frontendRedirect.valid || (wb.valid && wb.sfence && !RegNext(wb.valid && wb.sfence, false.B))
  io.frontendRedirect.cacheFlush := wbCommit && wb.fencei
  io.frontendRedirect.target := Mux(csrFile.io.trap.valid, csrFile.io.trap_target,
    Mux(xret, csrFile.io.xret_target, Mux(wbReplay, wb.pc, Mux(wbKill, wb.nextPc, exNext))))
  // Keep the legacy backend events at WB for the frozen observation contract.
  // The cluster's frontend boundary consumes these two functional routes.
  io.frontendFastRedirect := 0.U.asTypeOf(io.frontendFastRedirect)
  io.frontendFastRedirect.valid := branchRedirect
  io.frontendFastRedirect.flush := branchRedirect
  io.frontendFastRedirect.target := exNext
  io.frontendSlowRedirect := io.frontendRedirect
  io.frontendSlowRedirect.valid := wbKill
  io.frontendSlowRedirect.flush := wbKill ||
    (wb.valid && wb.sfence && !RegNext(wb.valid && wb.sfence, false.B))
  val btb = RegInit(0.U.asTypeOf(new BreezeBTBUpdateReq(64)))
  val pht = RegInit(0.U.asTypeOf(io.frontendPhtUpdate))
  val ghr = RegInit(0.U.asTypeOf(io.frontendGhrUpdate))
  btb := 0.U.asTypeOf(btb)
  pht := 0.U.asTypeOf(pht)
  ghr := 0.U.asTypeOf(ghr)
  val train = exLegal && exAdvance && !wbKill && (ex.ctrl.bru_inst || ex.ctrl.redir_inst)
  // Preserve the public zero-on-no-training payload without making the
  // same-edge WB cancellation reset every PC/target bit. Payload and its
  // visibility qualifier have the same register boundary as before.
  val btbPayloadVisible = RegNext(train, false.B)
  if (cfg.branchPredKind == flow.config.FrontendBranchPredictorKind.GShare) {
    btb.valid := train && (ex.pred.predType === FrontendPredType.BR || ex.pred.predType === FrontendPredType.JAL ||
      (ex.pred.predType === FrontendPredType.JALR && redirected))
    btb.pc := ex.pc
    btb.target := jau.io.jmp_addr
    btb.predType := ex.pred.predType
    btb.taken := taken
    pht.valid := train && ex.pred.predType === FrontendPredType.BR
    pht.idx := ex.pred.phtIdx
    pht.taken := taken
    ghr.valid := train && ex.pred.predType === FrontendPredType.BR
    ghr.taken := taken
  }
  io.frontendBtbUpdate := btb
  io.frontendBtbUpdate.pc := Mux(btbPayloadVisible, btb.pc, 0.U)
  io.frontendBtbUpdate.target := Mux(btbPayloadVisible, btb.target, 0.U)
  io.frontendBtbUpdate.predType := Mux(btbPayloadVisible, btb.predType, FrontendPredType.NONE)
  io.frontendBtbUpdate.taken := btbPayloadVisible && btb.taken
  io.frontendPhtUpdate := pht
  io.frontendGhrUpdate := ghr

  val csrStateHazard = (ex.valid && ex.ctrl.csr_cmd =/= CSR_CMD.NOP.U) ||
    (mem.valid && mem.csrCmd =/= CSR_CMD.NOP.U) ||
    (perm.valid && perm.csrCmd =/= CSR_CMD.NOP.U) || (wb.valid && wb.csrWrite)
  val oldCsrUsesRs1 = ctrl.sel_alu1 === SEL_ALU1.RS1.U || ctrl.bru_inst || ctrl.is_sfence_vma ||
    ctrl.sel_jpc_i === SEL_JPC_I.RS1.U || ctrl.csr_cmd === CSR_CMD.RW.U ||
    ctrl.csr_cmd === CSR_CMD.RS.U || ctrl.csr_cmd === CSR_CMD.RC.U
  val csrRegHazard = idCsr && Seq(
    (ex.valid && exWrites && !exBank && ex.rd_addr =/= 0.U, ex.rd_addr),
    (mem.valid && mem.writes && !mem.rd.isFp && mem.rd.idx =/= 0.U, mem.rd.idx),
    (perm.valid && perm.writes && !perm.rd.isFp && perm.rd.idx =/= 0.U, perm.rd.idx),
    (wb.valid && wb.writes && !wb.rd.isFp && wb.rd.idx =/= 0.U, wb.rd.idx)
  ).map { case (v,r) => v && ((oldCsrUsesRs1 && rs1 === r) || (gpr2Used && rs2 === r)) }.reduce(_ || _)
  // Conservative CSR/local-FP protection continues through WB; W2 RF
  // write-through then lets ID capture the new value without a W2 hazard check.
  val ordinaryHazard = Seq((ex.valid, exWrites, exBank, ex.rd_addr, ex.ctrl.sel_wb === SEL_WB.CSR.U || exFp.valid),
    (mem.valid, mem.writes, mem.rd.isFp, mem.rd.idx, mem.csrCmd =/= CSR_CMD.NOP.U || mem.rd.isFp),
    (perm.valid, perm.writes, perm.rd.isFp, perm.rd.idx, perm.csrCmd =/= CSR_CMD.NOP.U || perm.rd.isFp),
    (wb.valid, wb.writes, wb.rd.isFp, wb.rd.idx, wb.csrCmd =/= CSR_CMD.NOP.U || wb.rd.isFp)).map {
      case (v,w,b,r,h) => v && w && h && operands.take(3).map(o => o.used && o.rd.isFp === b && o.rd.idx === r && (b || r =/= 0.U)).reduce(_ || _)
  }.reduce(_ || _)
  // Only registered occupancy decides ID capacity. Grant may return a
  // credit in the same RF-write-through cone; EX reads no unit ready.
  val idLegal = !decoder.io.illegal_inst && !io.fetchBuffer.bits.illegalCompressed &&
    !io.fetchBuffer.bits.instructionAccessFault && !io.fetchBuffer.bits.instructionPageFault
  val idMul = idLegal && ctrl.mul_valid && rd =/= 0.U
  val idDiv = idLegal && ctrl.div_valid && rd =/= 0.U
  val idFp = fpCtrl.fpuValid && csrFile.io.fp_enabled &&
    !io.fetchBuffer.bits.instructionAccessFault && !io.fetchBuffer.bits.instructionPageFault
  def credit(capacity: Int, reserve: Bool, release: Bool, killed: UInt, orphan: Bool): Bool = {
    val used = RegInit(0.U(log2Ceil(capacity + 1).W))
    val canceled = RegNext((wbKill || io.hartFatal) && orphan, false.B)
    val returns = release.asUInt +& killed +& canceled.asUInt
    used := used + reserve.asUInt - returns
    when(!reset.asBool) {
      assert(used <= capacity.U && returns <= used, "[R3] credit ownership underflow/overflow")
    }
    used < capacity.U || release
  }
  val mulCredit = credit(8, idLeave && idMul, mulUnit.io.result.fire, mulUnit.io.killed,
    exMul && !mulUnit.io.req.fire)
  val divCredit = credit(1, idLeave && idDiv, divUnit.io.result.fire, divUnit.io.killed,
    exDiv && !divUnit.io.req.fire)
  fpUnit.io.reserve := idLeave && idFp
  val creditWait = (idMul && !mulCredit) || (idDiv && !divCredit) || (idFp && !fpUnit.io.reserveReady)
  def longPort(reserve: Bool, available: Bool, commit: Bool, killed: UInt,
      drained: Bool, raw: DecoupledIO[IntMduResult]): LongLatUnit = {
    val port = Wire(new LongLatUnit)
    port.reserve := reserve; port.available := available
    port.commitD := commit; port.killD := killD
    port.returned := killed + raw.fire.asUInt
    port.drained := drained
    port.result.valid := raw.valid
    port.result.bits.rd.idx := raw.bits.rd; port.result.bits.rd.isFp := false.B
    port.result.bits.data := raw.bits.data; port.result.bits.flags := 0.U
    raw.ready := port.result.ready
    port
  }
  val mulPort = longPort(idLeave && idMul, mulCredit, mulPortCommitD,
    mulUnit.io.killed, mulUnit.io.drained, mulUnit.io.result)
  val divPort = longPort(idLeave && idDiv, divCredit, RegNext(wbCommit && wb.div, false.B),
    divUnit.io.killed, divUnit.io.drained, divUnit.io.result)
  val fpPort = Wire(new LongLatUnit)
  fpPort.reserve := idLeave && idFp; fpPort.available := fpUnit.io.reserveReady
  fpPort.commitD := RegNext(wbCommit && wb.fp, false.B); fpPort.killD := killD
  fpPort.returned := fpUnit.io.killed + fpUnit.io.result.fire.asUInt
  fpPort.drained := fpUnit.io.drained
  fpPort.result <> fpUnit.io.result
  writeback.io.div <> divPort.result
  writeback.io.mul <> mulPort.result
  writeback.io.fp <> fpPort.result
  coprocessor.foreach { c =>
    c.io.req.valid := ex.valid && ex.inst(6,0) === "h0b".U && !ex.illegal_inst && allowEx
    c.io.req.bits := ex.inst
    c.io.commitD := RegNext(wbCommit && wb.copro, false.B)
    c.io.killD := killD
    when(idLeave && idCopro) { coproUsed := true.B }
    when(c.io.returned) { coproUsed := false.B }
    writeback.io.copro.get.valid := false.B
    writeback.io.copro.get.bits := 0.U.asTypeOf(new FpResult)
  }
  val slowRedirectGap = RegNext(wbKill, false.B)
  io.fetchBuffer.ready := exAdvance && !slowRedirectGap && !killD && !scoreboard.io.hazard && !csrStateHazard && !csrRegHazard &&
    !ordinaryHazard && !creditWait && !(idCopro && coproUsed) && !serialInFlight && !soloInFlight && !serialIdWait && !soloIdWait && !writeback.io.idStarve && !branchRedirect && !wbKill &&
    !csrFile.io.interruptPending && !sleeping && !stopped && !io.hartFatal
  idLeave := io.fetchBuffer.fire
  when(reset.asBool || wbKill || io.hartFatal) { ex.valid := false.B }
    .elsewhen(exAdvance) { ex.valid := idLeave }
  // Kill invalidates ownership only. The wide EX payload capture must not
  // inherit the valid register's same-edge WB cancellation priority.
  when(exAdvance) {
      exSolo := idSolo
      // Payload is irrelevant when valid=0. Capture on slot advance; only
      // ex.valid needs the late ID authorization/hazard/redirect conditions.
      ex.pc := io.fetchBuffer.bits.pc
      ex.inst := inst
      ex.rawInst := io.fetchBuffer.bits.rawInst
      ex.instLen := Mux(io.fetchBuffer.bits.instLen === 2.U, 2.U, 4.U)
      ex.instruction_access_fault := io.fetchBuffer.bits.instructionAccessFault
      ex.instruction_page_fault := io.fetchBuffer.bits.instructionPageFault
      ex.instruction_fault_second_parcel := io.fetchBuffer.bits.instructionFaultSecondParcel
      ex.illegal_inst := io.fetchBuffer.bits.illegalCompressed || (decoder.io.illegal_inst && !fpCtrl.valid && !idCopro) ||
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
    }.otherwise {
      ex.rs1_data := exR1
      ex.rs2_data := exR2
      exFpr := exFprRead
    }
  locally {
      // EX wait never holds MEM/WB: insert a bubble behind the older MEM.
      mem := 0.U.asTypeOf(mem)
      mem.valid := ex.valid && exAdvance
      mem.solo := exSolo
      mem.pc := ex.pc
      mem.nextPc := exNext
      mem.inst := ex.inst
      mem.rawInst := ex.rawInst
      mem.instLen := ex.instLen
      mem.rd.idx := ex.rd_addr
      mem.rd.isFp := exBank
      mem.writes := exWrites
      val local = Mux(exFp.localOp === BreezeFpLocalOp.FMV_X.U,
        Mux(exFp.isDouble, exFprRead(0), Cat(Fill(32, exFprRead(0)(31)), exFprRead(0)(31,0))),
        Mux(exFp.isDouble, exR1, Cat("hffffffff".U(32.W), exR1(31,0))))
      mem.data := Mux(exFp.localOp =/= BreezeFpLocalOp.NONE.U, local, alu.io.alu_out)
      mem.address := Mux(ex.ctrl.is_sfence_vma, exR1, address)
      mem.storeData := req.wdata
      mem.mem := exMem
      mem.load := exLoad
      mem.mul := exMul
      mem.div := exDiv
      mem.fp := exFpLong
      mem.copro := cfg.coprocessorStub.nonEmpty.B && ex.inst(6,0) === "h0b".U && exLegal
      mem.serial := ex.ctrl.fencei || ex.ctrl.is_sfence_vma || ex.estop || ex.ctrl.csr_cmd =/= CSR_CMD.NOP.U
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
      mem.predictionMiss := redirected
      perm := mem
      when(!downHold) { wb := perm }
      when(!downHold && perm.valid && perm.csrCmd =/= CSR_CMD.NOP.U) {
        wb.data := csrFile.io.csr_old_data
        wb.csrWrite := csrFile.io.csr_write_en
        wb.csrData := csrFile.io.csr_new_data
        when(csrFile.io.csr_illegal && !perm.exception) {
          wb.exception := true.B; wb.cause := 2.U; wb.tval := perm.rawInst
        }
      }
    }
  when(reset.asBool || wbKill || io.hartFatal) { mem.valid := false.B; perm.valid := false.B; wb.valid := false.B }
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
  io.backendEvents.loadUseStall := scoreboard.io.sourceStall(LongSource.L1D)
  io.backendEvents.mulSourceStall := scoreboard.io.sourceStall(LongSource.MUL)
  io.backendEvents.divSourceStall := scoreboard.io.sourceStall(LongSource.DIV)
  io.backendEvents.wbPortConflict := writeback.io.conflict
  for(i <- 0 until 7) io.backendEvents.replay(i) := wbReplay &&
    Mux(wb.copro, 6.U, io.l1d.resp.bits.replayReason) === i.U
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
  io.observe.exHold := resourceWait
  io.observe.grant := writeback.io.grant
  io.observe.fpIn := fpUnit.io.req.fire
  io.observe.fpOut := fpUnit.io.result.fire
  io.observe.fpFlags := writeback.io.fpFlags
  io.observe.mulIn := mulUnit.io.req.fire
  io.observe.divIn := divUnit.io.req.fire
  io.observe.divIterating := divUnit.arithmeticActive
  io.observe.translationBlocked := io.translationBlocked
  io.observe.replay := wbReplay
  io.observe.replayPc := wb.pc
  io.observe.replayReason := Mux(wb.copro, 6.U, io.l1d.resp.bits.replayReason)
  io.observe.solo := wb.valid && wb.solo
  io.observe.memAdvance := true.B
  io.observe.permAdvance := true.B
  io.observe.killD := killD
  io.observe.busySet.valid := scoreboard.io.set.valid && wb.writes && (wb.rd.isFp || wb.rd.idx =/= 0.U)
  io.observe.busySet.bits := wb.rd
  io.observe.busyClear := writeback.io.clear
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
    t.lateWriteError := writeback.io.lateWriteError
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
      when(idLeave) { printf(cf"[CYCLE] c=${cycle} kind=id pc=0x${io.fetchBuffer.bits.pc}%x inst=0x${inst}%x\n") }
      when(ex.valid && exAdvance) { printf(cf"[CYCLE] c=${cycle} kind=ex pc=0x${ex.pc}%x\n") }
      when(wbCommit) { printf(cf"[CYCLE] c=${cycle} kind=commit pc=0x${wb.pc}%x inst=0x${wb.inst}%x\n") }
      when(fpUnit.io.req.fire) { printf(cf"[CYCLE] c=${cycle} kind=fpIn pc=0x${ex.pc}%x rd=${ex.rd_addr}\n") }
      when(fpUnit.io.result.fire) { printf(cf"[CYCLE] c=${cycle} kind=fpOut fp=${fpUnit.io.result.bits.rd.isFp} rd=${fpUnit.io.result.bits.rd.idx}\n") }
      when(writeback.io.gprWrite.valid) { printf(cf"[CYCLE] c=${cycle} kind=gpr rd=${writeback.io.gprWrite.bits.idx} data=0x${writeback.io.gprWrite.bits.data}%x\n") }
      when(writeback.io.fprWrite.valid) { printf(cf"[CYCLE] c=${cycle} kind=fpr rd=${writeback.io.fprWrite.bits.idx} data=0x${writeback.io.fprWrite.bits.data}%x\n") }
      when(io.l1d.late.fire) { printf(cf"[CYCLE] c=${cycle} kind=late rd=${io.l1d.late.bits.rd.idx} error=${io.l1d.late.bits.error}\n") }
    }
  }
  val pastDownHold = RegNext(downHold, false.B)
  val pastKill = RegNext(wbKill || io.hartFatal, false.B)
  val pastActive = RegNext(!reset.asBool, false.B)
  val heldMem = RegNext(mem.asUInt)
  val heldPerm = RegNext(perm.asUInt)
  val heldWb = RegNext(wb.asUInt)
  when(pastActive && !reset.asBool && pastDownHold && !pastKill) {
    assert(wb.asUInt === heldWb, "[N01] held solo WB metadata changed")
    assert(!mem.valid && !perm.valid, "[N01] MEM/PERM did not advance to bubbles")
  }
  when(!reset.asBool) {
    assert(!mulUnit.io.req.valid || mulUnit.io.req.ready, "[R3] reserved MUL rejected")
    assert(!divUnit.io.req.valid || divUnit.io.req.ready, "[R3] reserved DIV rejected")
    assert(!fpUnit.io.req.valid || fpUnit.io.req.ready, "[R3] reserved FP rejected")
    when(io.hartFatal) {
      assert(!(idLeave || wbCommit || io.l1d.req.fire || mulUnit.io.req.fire ||
        divUnit.io.req.fire || fpUnit.io.req.fire), "[fatal] new issue or ordinary retirement")
    }
    assert(!io.l1d.resp.valid || (wb.valid && wb.mem), "[S15] L1D resp is not aligned to WB")
    assert(!(io.l1d.resp.valid && io.l1d.s2Hold), "[S15] simultaneous hold and decision")
    assert(!interrupt || (!ex.valid && !mem.valid && !perm.valid && !wb.valid && !io.l1d.mmioBusy),
      "[R7] interrupt sample no longer has an empty reversible boundary")
    assert(!downHold || (wb.solo && !ex.valid && !mem.valid && !perm.valid),
      "[N01] WB self-hold without empty younger stages")
    assert(!(wb.valid && wb.solo && wb.mem && wbMiss), "[R6] solo memory committed before actual result")
    assert(!wbReplay || !wb.solo, "[N02] solo instruction replayed twice")
    assert(!killD || !(mulUnit.io.req.fire || divUnit.io.req.fire || fpUnit.io.req.fire),
      "[R2] new unit request during delayed kill")
    when(io.fetchBuffer.valid && olderEmpty && cpuDrainedD && (ctrl.fencei || ctrl.is_sfence_vma)) {
      assert(io.l1d.cpuDrained, "[R6] CPU obligation reappeared after eligible drain sample")
    }
    when(sfenceAtId && olderEmpty && mmuIdleD) {
      assert(io.mmuIdle, "[R6] MMU work reappeared after blocked idle sample")
    }
    when(idLeave && idSerial) {
      assert(olderEmpty, "[R6] serial issued before older WB drained")
      assert(!ctrl.fencei || io.l1d.cpuDrained, "[R6] sampled CPU drain fell before FENCE.I issue")
      assert(!ctrl.is_sfence_vma || (io.l1d.cpuDrained && io.mmuIdle),
        "[R6] sampled SFENCE condition fell before ID issue")
      assert(!(idEstop || idCsr) || scalarDrained, "[R6] sampled scalar drain fell")
    }
    assert(!(branchRedirect && exRedirectSent), "[S09] EX redirected twice in one occupancy")
    when(downHold && wb.valid) {
      assert(!(train || io.frontendSlowRedirect.valid || csrFile.io.commit_valid || csrFile.io.trap.valid ||
        csrFile.io.mret_commit || csrFile.io.sret_commit), "[S09] control side effect while WB held")
      // S09 explicitly permits requests with their own one-shot state (A08/B01).
      // SFENCE must request while WB waits, then wait for the subsequent idle.
      assert(!io.sfence.valid, "[R6] serial action during blocking WB wait")
    }
    assert(!downHold || !wbCommit, "[S09] held WB retired")
    assert(!wbCommit || !wb.mem || io.l1d.resp.valid, "[S15] memory retired without S3 decision")
  }
}
