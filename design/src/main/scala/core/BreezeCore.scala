package flow.core

import chisel3._
import chisel3.util.Decoupled
import chisel3.util._
import flow.backend.BreezeBackend
import flow.buffer.FASEFetchBuffer
import flow.buffer.FetchBuffer
import flow.config._
import flow.frontend.{BreezeFrontend, BreezeFrontendDebugIO}
import flow.interface._
import flow.platform.BreezeMcuPlatform
import flow.mmu.{BreezeDataTranslator, BreezeMmu}

class BreezeCore(val corecfg: BreezeCoreConfig, val enabledebug: Boolean = false,
                 val hartId: Int = 0) extends Module {
    require(hartId >= 0, "core hartId must be non-negative")
    val io = IO(new Bundle {
        val resetAddr = Input(UInt(corecfg.VLEN.W))
        val machineTimerInterrupt = Input(Bool())
        val machineSoftwareInterrupt = Input(Bool())
        val time = Input(UInt(corecfg.VLEN.W))
        val externalInterrupts = Input(UInt(BreezeMcuPlatform.ExternalInterruptWidth.W))
        val supervisorExternalInterrupt = Input(Bool())
        val nextLevelReq = new L1CacheMissReqIO(corecfg.PLEN)
        val nextLevelRsp = new L1CacheMissRespIO(corecfg.frontendCfg.cacheCfg.ICACHE_LINE_WIDTH)
        val dmem = new BackendMemIO(corecfg.VLEN)
        val dcacheArrayReq = if (corecfg.enableMmu) Some(Decoupled(UInt(corecfg.VLEN.W))) else None
        val dcacheFlushReq = Output(Bool())
        val dcacheFlushDone = Input(Bool())
        val dcacheHpm = Input(new BreezeHpmEvents)
        // One-cycle pulse on every taken trap; clears the D$ LR/SC reservation.
        val reservationKill = Output(Bool())
        val estop = Output(Bool())
        val fase = if (corecfg.useFASE) Some(new FASECoreIO()) else None
        val recorder = if (corecfg.useFASE) Some(new flow.fase.FaseCommandIO) else None
        val flight = if (corecfg.useFASE) Some(Output(Vec(8, UInt(64.W)))) else None
        val tandem = if (corecfg.enableTandem) Some(Output(new TracePayload(corecfg.VLEN))) else None
        val frontendDebug = if (enabledebug) {
            Some(new BreezeFrontendDebugIO(
              corecfg.VLEN,
              corecfg.frontendCfg.branchPredCfg.ghrLength
            ))
        } else None
        val debug = if (enabledebug) Some(new BackendDebugIO(corecfg.VLEN)) else None
    })

    val frontend = Module(new BreezeFrontend(corecfg.frontendCfg, enabledebug = enabledebug, useFASE = corecfg.useFASE))
    val buffer = Module(new FetchBuffer(corecfg.VLEN, 6, corecfg.backendCfg.ghrLength))
    val backend = Module(new BreezeBackend(corecfg.backendCfg, enabledebug = enabledebug,
        hartId = hartId, useFASE = corecfg.useFASE))

    val fasePaused = WireDefault(false.B)
    val faseRedirect = WireDefault(false.B)
    val faseTarget = WireDefault(0.U(corecfg.VLEN.W))
    val physicalIdle = WireDefault(true.B)
    val translationEvent = WireDefault(0.U.asTypeOf(new flow.fase.FlightEvent))
    val mmuDiag = WireDefault(VecInit(Seq.fill(8)(0.U(64.W))))
    frontend.io.fasePause.foreach(_ := fasePaused)
    frontend.io.resetAddr := io.resetAddr
    frontend.io.beRedirect := backend.io.frontendRedirect
    when(faseRedirect) {
        frontend.io.beRedirect.valid := true.B
        frontend.io.beRedirect.flush := true.B
        frontend.io.beRedirect.target := faseTarget
    }
    frontend.io.btbUpdate := backend.io.frontendBtbUpdate
    frontend.io.phtUpdate := backend.io.frontendPhtUpdate
    frontend.io.ghrUpdate := backend.io.frontendGhrUpdate
    frontend.io.nextLevelReq <> io.nextLevelReq
    frontend.io.nextLevelRsp <> io.nextLevelRsp

    buffer.io.in <> frontend.io.fetchBuffer
    buffer.io.flush := frontend.io.beRedirect.flush

    backend.io.resetAddr := io.resetAddr
    backend.io.machineTimerInterrupt := io.machineTimerInterrupt
    backend.io.machineSoftwareInterrupt := io.machineSoftwareInterrupt
    backend.io.time := io.time
    backend.io.externalInterrupts := io.externalInterrupts
    backend.io.supervisorExternalInterrupt := io.supervisorExternalInterrupt
    io.reservationKill := backend.io.reservationKill
    io.estop := backend.io.estop
    if (corecfg.enableMmu) {
        val mmu = Module(new BreezeMmu(corecfg.VLEN, entries = 16, useFASE = corecfg.useFASE))
        val dataTranslator = Module(new BreezeDataTranslator(corecfg.VLEN, parallelLookup = true))
        io.dcacheArrayReq.get <> dataTranslator.io.arrayReq.get

        mmu.io.context := backend.io.mmuContext
        mmu.io.sfence := backend.io.sfence
        mmu.io.killI := frontend.io.beRedirect.valid
        mmu.io.i.req <> frontend.io.translateReq
        frontend.io.translateRsp <> mmu.io.i.resp
        mmu.io.d.req <> dataTranslator.io.translateReq
        dataTranslator.io.translateRsp <> mmu.io.d.resp
        dataTranslator.io.cpu <> backend.io.dmem

        // PTW accesses and translated CPU accesses share the coherent L1D.
        // Only one downstream transaction is outstanding at a time.
        val physicalBusy = RegInit(false.B)
        val physicalOwnerPtw = RegInit(false.B)
        physicalIdle := !physicalBusy && !mmu.io.memReq.valid && !dataTranslator.io.memReq.valid
        if (corecfg.useFASE) {
            val req = mmu.io.i.req
            val rsp = mmu.io.i.resp
            val reqPriv = RegEnable(backend.io.mmuContext.privilege, 0.U, req.fire)
            val reqSatp = RegEnable(backend.io.mmuContext.satp, 0.U, req.fire)
            translationEvent.valid := req.fire || rsp.fire || mmu.io.killI
            translationEvent.words := VecInit(Seq(
                (Mux(rsp.fire, reqPriv, backend.io.mmuContext.privilege) << 8) |
                    Cat(mmu.io.killI, rsp.bits.accessFault, rsp.bits.pageFault, rsp.fire, req.fire),
                req.bits.vaddr, rsp.bits.vaddr, rsp.bits.paddr, reqSatp,
                backend.io.mmuContext.satp, mmu.io.faseDiagnostic.get(0)))
            for (i <- 0 until 6) mmuDiag(i) := mmu.io.faseDiagnostic.get(i)
            mmuDiag(6) := physicalBusy.asUInt
            mmuDiag(7) := physicalOwnerPtw.asUInt
        }
        val choosePtw = mmu.io.memReq.valid
        mmu.io.memReq.ready := !physicalBusy
        dataTranslator.io.memReq.ready := !physicalBusy && !choosePtw
        io.dmem.req := 0.U.asTypeOf(new BackendMemReq(corecfg.VLEN))
        when(!physicalBusy && choosePtw) {
            io.dmem.req := mmu.io.memReq.bits
            io.dmem.req.valid := true.B
        }.elsewhen(!physicalBusy && dataTranslator.io.memReq.valid) {
            io.dmem.req := dataTranslator.io.memReq.bits
            io.dmem.req.valid := true.B
        }
        when(!physicalBusy && (mmu.io.memReq.fire || dataTranslator.io.memReq.fire)) {
            physicalBusy := true.B
            physicalOwnerPtw := mmu.io.memReq.fire
        }.elsewhen(physicalBusy && io.dmem.rsp.valid) {
            physicalBusy := false.B
        }
        mmu.io.memRsp.valid := physicalBusy && physicalOwnerPtw && io.dmem.rsp.valid
        mmu.io.memRsp.bits := io.dmem.rsp
        dataTranslator.io.memRsp.valid := physicalBusy && !physicalOwnerPtw && io.dmem.rsp.valid
        dataTranslator.io.memRsp.bits := io.dmem.rsp
        assert(!io.dmem.rsp.valid || physicalBusy,
            "[BreezeCore] unsolicited DCache response")
    } else {
        io.dmem <> backend.io.dmem
        frontend.io.translateReq.ready := false.B
        frontend.io.translateRsp.valid := false.B
        frontend.io.translateRsp.bits := 0.U.asTypeOf(new BreezeTranslationResp(corecfg.VLEN))
    }
    io.dcacheFlushReq := backend.io.dcacheFlushReq
    backend.io.dcacheFlushDone := io.dcacheFlushDone
    backend.io.hpmEvents := frontend.io.hpm
    backend.io.hpmEvents.dcacheAccess := io.dcacheHpm.dcacheAccess
    backend.io.hpmEvents.dcacheMiss := io.dcacheHpm.dcacheMiss
    backend.io.hpmEvents.dcacheUncached := io.dcacheHpm.dcacheUncached
    io.tandem.zip(backend.io.tandem).foreach { case (coreTandem, backendTandem) =>
        coreTandem := backendTandem
    }
    io.frontendDebug.foreach(_ <> frontend.io.debug.get)
    io.debug.foreach(_ <> backend.io.debug.get)

    if (corecfg.useFASE) {
        val recorder = Module(new flow.fase.FlightRecorder)
        recorder.io.host <> io.recorder.get
        recorder.io.privilege := backend.io.fase.get.flightPrivilege
        for (i <- 0 until 4) recorder.io.events(i) := backend.io.fase.get.flightEvents(i)
        recorder.io.events(4) := frontend.io.flightEvent.get
        recorder.io.events(5) := translationEvent
        io.flight.get := recorder.io.probes
        val fasebuffer = Module(new FASEFetchBuffer(corecfg.VLEN, 6, corecfg.backendCfg.ghrLength))
        val f = io.fase.get
        val b = backend.io.fase.get
        val run :: drain :: control :: Nil = Enum(3)
        val state = RegInit(run)
        val saved = RegInit(VecInit(Seq.fill(12)(0.U(64.W))))
        val stopping = state === run && f.halt
        val enter = state === drain && b.empty && physicalIdle
        val launch = state === control && f.launch && f.empty
        fasePaused := state =/= run || f.halt
        faseRedirect := stopping || enter || launch
        faseTarget := Mux(launch, f.launchPc, b.nextPc)
        when(stopping) { state := drain }
        when(enter) {
            state := control
            saved := VecInit((16 until 28).map(b.diagnostic(_)))
        }
        when(launch) { state := run }
        b.active := fasePaused
        b.enter := enter
        b.launch := launch
        b.launchPc := f.launchPc
        b.regIndex := f.regIndex
        b.regWdata := f.regWdata
        b.regWrite := f.regWrite && f.halted && f.empty
        f.regRdata := b.regRdata
        f.halted := state === control
        f.empty := b.empty && !fasebuffer.io.out.valid && physicalIdle
        f.retired := b.retired
        f.fault := b.fault
        f.cause := b.cause
        f.tval := b.tval
        f.nextPc := b.nextPc
        f.diagnostic := VecInit(b.diagnostic.toSeq ++ mmuDiag.toSeq ++ saved.toSeq)
        fasebuffer.io.in.valid := f.inst_valid && f.halted && !f.launch
        fasebuffer.io.in.bits := 0.U.asTypeOf(fasebuffer.io.in.bits)
        fasebuffer.io.in.bits.pc := f.inst_pc
        fasebuffer.io.in.bits.inst := f.instruction
        fasebuffer.io.in.bits.rawInst := f.instruction
        fasebuffer.io.in.bits.instLen := 4.U
        fasebuffer.io.flush := launch
        f.inst_ready := fasebuffer.io.in.ready && f.halted && !f.launch
        val normal = state === run && !f.halt
        val injected = state === control && !launch
        buffer.io.out.ready := normal && backend.io.fetchBuffer.ready
        fasebuffer.io.out.ready := injected && backend.io.fetchBuffer.ready
        backend.io.fetchBuffer.valid := Mux(normal, buffer.io.out.valid,
            injected && fasebuffer.io.out.valid)
        backend.io.fetchBuffer.bits := Mux(normal, buffer.io.out.bits, fasebuffer.io.out.bits)
    } else {
        backend.io.fetchBuffer <> buffer.io.out
    }
}
