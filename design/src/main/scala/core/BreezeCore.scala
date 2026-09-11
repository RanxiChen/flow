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
        val tandem = if (corecfg.enableTandem) Some(Output(new TracePayload(corecfg.VLEN))) else None
        val frontendDebug = if (enabledebug) {
            Some(new BreezeFrontendDebugIO(
              corecfg.VLEN,
              corecfg.frontendCfg.branchPredCfg.ghrLength
            ))
        } else None
        val debug = if (enabledebug) Some(new BackendDebugIO(corecfg.VLEN)) else None
    })

    val frontend = Module(new BreezeFrontend(corecfg.frontendCfg, enabledebug = enabledebug))
    val buffer = Module(new FetchBuffer(corecfg.VLEN, 6, corecfg.backendCfg.ghrLength))
    val backend = Module(new BreezeBackend(corecfg.backendCfg, enabledebug = enabledebug,
        hartId = hartId))

    frontend.io.resetAddr := io.resetAddr
    frontend.io.beRedirect := backend.io.frontendRedirect
    frontend.io.btbUpdate := backend.io.frontendBtbUpdate
    frontend.io.phtUpdate := backend.io.frontendPhtUpdate
    frontend.io.ghrUpdate := backend.io.frontendGhrUpdate
    frontend.io.nextLevelReq <> io.nextLevelReq
    frontend.io.nextLevelRsp <> io.nextLevelRsp

    buffer.io.in <> frontend.io.fetchBuffer
    buffer.io.flush := backend.io.frontendRedirect.flush

    backend.io.resetAddr := io.resetAddr
    backend.io.machineTimerInterrupt := io.machineTimerInterrupt
    backend.io.machineSoftwareInterrupt := io.machineSoftwareInterrupt
    backend.io.time := io.time
    backend.io.externalInterrupts := io.externalInterrupts
    backend.io.supervisorExternalInterrupt := io.supervisorExternalInterrupt
    io.reservationKill := backend.io.reservationKill
    io.estop := backend.io.estop
    if (corecfg.enableMmu) {
        val mmu = Module(new BreezeMmu(corecfg.VLEN, entries = 16))
        val dataTranslator = Module(new BreezeDataTranslator(corecfg.VLEN, parallelLookup = true))
        io.dcacheArrayReq.get <> dataTranslator.io.arrayReq.get

        mmu.io.context := backend.io.mmuContext
        mmu.io.sfence := backend.io.sfence
        mmu.io.killI := backend.io.frontendRedirect.valid
        mmu.io.i.req <> frontend.io.translateReq
        frontend.io.translateRsp <> mmu.io.i.resp
        mmu.io.d.req <> dataTranslator.io.translateReq
        dataTranslator.io.translateRsp <> mmu.io.d.resp
        dataTranslator.io.cpu <> backend.io.dmem

        // PTW accesses and translated CPU accesses share the coherent L1D.
        // Only one downstream transaction is outstanding at a time.
        val physicalBusy = RegInit(false.B)
        val physicalOwnerPtw = RegInit(false.B)
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
        val fasebuffer = Module(new FASEFetchBuffer(corecfg.frontendCfg.cacheCfg.VLEN, 6, corecfg.backendCfg.ghrLength))
        val useFASEBuffer = Wire(Bool())
        val fase = io.fase.get

        useFASEBuffer := true.B

        fasebuffer.io.in.valid := fase.inst_valid
        fasebuffer.io.in.bits.pc := 0.U
        fasebuffer.io.in.bits.inst := fase.instruction
        fasebuffer.io.in.bits.rawInst := fase.instruction
        fasebuffer.io.in.bits.instLen := 4.U
        fasebuffer.io.in.bits.isCompressed := false.B
        fasebuffer.io.in.bits.illegalCompressed := false.B
        fasebuffer.io.in.bits.instructionAccessFault := false.B
        fasebuffer.io.in.bits.instructionPageFault := false.B
        fasebuffer.io.in.bits.pred.predType := FrontendPredType.NONE
        fasebuffer.io.in.bits.pred.predTaken := false.B
        fasebuffer.io.in.bits.pred.predPc := 0.U
        fasebuffer.io.in.bits.pred.phtIdx := 0.U
        fasebuffer.io.flush := false.B
        fase.inst_ready := fasebuffer.io.in.ready

        fasebuffer.io.out.ready := useFASEBuffer && backend.io.fetchBuffer.ready
        buffer.io.out.ready := !useFASEBuffer && backend.io.fetchBuffer.ready

        backend.io.fetchBuffer.valid := Mux(useFASEBuffer, fasebuffer.io.out.valid, buffer.io.out.valid)
        backend.io.fetchBuffer.bits := Mux(useFASEBuffer, fasebuffer.io.out.bits, buffer.io.out.bits)
    } else {
        backend.io.fetchBuffer <> buffer.io.out
    }
}
