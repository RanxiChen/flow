package flow.core

import chisel3._
import chisel3.util._
import flow.backend.BreezeBackend
import flow.buffer.FASEFetchBuffer
import flow.buffer.FetchBuffer
import flow.config._
import flow.frontend.{BreezeFrontend, BreezeFrontendDebugIO}
import flow.interface._

class BreezeCore(val corecfg: BreezeCoreConfig, val enabledebug: Boolean = false) extends Module {
    val io = IO(new Bundle {
        val resetAddr = Input(UInt(corecfg.VLEN.W))
        val nextLevelReq = new L1CacheMissReqIO(corecfg.PLEN)
        val nextLevelRsp = new L1CacheMissRespIO(corecfg.frontendCfg.cacheCfg.ICACHE_LINE_WIDTH)
        val dmem = new BackendMemIO(corecfg.VLEN)
        val fase = if (corecfg.useFASE) Some(new FASECoreIO()) else None
        val frontendDebug = if (enabledebug) Some(new BreezeFrontendDebugIO(corecfg.VLEN)) else None
        val debug = if (enabledebug) Some(new BackendDebugIO(corecfg.VLEN)) else None
    })

    val frontend = Module(new BreezeFrontend(corecfg.frontendCfg, enabledebug = enabledebug))
    val buffer = Module(new FetchBuffer(corecfg.VLEN, 6))
    val backend = Module(new BreezeBackend(corecfg.backendCfg, enabledebug = enabledebug))

    frontend.io.resetAddr := io.resetAddr
    frontend.io.beRedirect := backend.io.frontendRedirect
    frontend.io.nextLevelReq <> io.nextLevelReq
    frontend.io.nextLevelRsp <> io.nextLevelRsp

    buffer.io.in <> frontend.io.fetchBuffer
    buffer.io.flush := backend.io.frontendRedirect.flush

    backend.io.resetAddr := io.resetAddr
    io.dmem <> backend.io.dmem
    io.frontendDebug.foreach(_ <> frontend.io.debug.get)
    io.debug.foreach(_ <> backend.io.debug.get)

    if (corecfg.useFASE) {
        val fasebuffer = Module(new FASEFetchBuffer(corecfg.frontendCfg.cacheCfg.VLEN, 6))
        val useFASEBuffer = Wire(Bool())
        val fase = io.fase.get

        useFASEBuffer := true.B

        fasebuffer.io.in.valid := fase.inst_valid
        fasebuffer.io.in.bits.pc := 0.U
        fasebuffer.io.in.bits.inst := fase.instruction
        fasebuffer.io.in.bits.pred.predType := FrontendPredType.NONE
        fasebuffer.io.in.bits.pred.predTaken := false.B
        fasebuffer.io.in.bits.pred.predPc := 0.U
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
