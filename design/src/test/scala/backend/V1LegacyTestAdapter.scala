package flow.backend
import chisel3._
import chisel3.util._
import flow.config.BackendConfig
import flow.interface._
import flow.platform.BreezeMcuPlatform
/** Test-only old pulse-memory environment. No compatibility ports on v1 RTL.
  * Old expectations remain intact; these suites are separate from v1 contracts.
  */
class V1LegacyTestAdapter(val cfg: BackendConfig = BackendConfig(), val enabledebug: Boolean = false,
    val hartId: Int = 0, val useFASE: Boolean = false) extends Module {
  val backend = Module(new BreezeBackend(cfg, enabledebug, hartId, useFASE))
  val io = IO(new Bundle {
    val resetAddr = Input(UInt(64.W))
    val machineTimerInterrupt = Input(Bool())
    val machineSoftwareInterrupt = Input(Bool())
    val time = Input(UInt(64.W))
    val externalInterrupts = Input(UInt(BreezeMcuPlatform.ExternalInterruptWidth.W))
    val supervisorExternalInterrupt = Input(Bool())
    val fetchBuffer = Flipped(Decoupled(new FrontendFetchBundle(64, cfg.ghrLength)))
    val dmem = new BackendMemIO(64)
    val dcacheFlushDone = Input(Bool())
    val dcacheFlushReq = Output(Bool())
    val hpmEvents = Input(new BreezeHpmEvents)
    val frontendBtbUpdate = Output(new BreezeBTBUpdateReq(64))
    val frontendPhtUpdate = Output(new BreezePHTUpdateReq(cfg.ghrLength.max(1)))
    val frontendGhrUpdate = Output(new BreezeGHRUpdateReq)
    val frontendRedirect = Output(new FrontendRedirectIO(64))
    val mmuContext = Output(new BreezeMmuContext(64))
    val sfence = Output(new BreezeSfenceReq(64))
    val reservationKill = Output(Bool())
    val estop = Output(Bool())
    val tandem = if (cfg.enableTandem) Some(Output(new TracePayload(64))) else None
    val debug = if (enabledebug) Some(new BackendDebugIO(64)) else None
  })
  backend.io.resetAddr := io.resetAddr
  backend.io.machineTimerInterrupt := io.machineTimerInterrupt
  backend.io.machineSoftwareInterrupt := io.machineSoftwareInterrupt
  backend.io.time := io.time
  backend.io.externalInterrupts := io.externalInterrupts
  backend.io.supervisorExternalInterrupt := io.supervisorExternalInterrupt
  backend.io.fetchBuffer <> io.fetchBuffer
  backend.io.hpmEvents := io.hpmEvents
  backend.io.mmuIdle := true.B
  io.frontendBtbUpdate := backend.io.frontendBtbUpdate
  io.frontendPhtUpdate := backend.io.frontendPhtUpdate
  io.frontendGhrUpdate := backend.io.frontendGhrUpdate
  io.frontendRedirect := backend.io.frontendRedirect
  io.mmuContext := backend.io.mmuContext
  io.sfence := backend.io.sfence
  io.reservationKill := backend.io.reservationKill
  io.estop := backend.io.estop
  io.debug.zip(backend.io.debug).foreach { case (a,b) => a := b }
  io.tandem.zip(backend.io.tandem).foreach { case (a,b) => a := b }
  val s1 = RegNext(backend.io.l1d.req.fire, false.B)
  val s2 = RegInit(false.B)
  val hold = s2 && !io.dmem.rsp.valid
  when(backend.io.l1d.s2Kill) { s2 := false.B }
    .elsewhen(!hold) { s2 := s1 }
  backend.io.l1d.req.ready := !hold
  backend.io.l1d.s2Hold := hold
  backend.io.l1d.resp.valid := s2 && io.dmem.rsp.valid
  backend.io.l1d.resp.bits.kind := Mux(io.dmem.rsp.error || io.dmem.rsp.pageFault, L1DRespKind.Exc, L1DRespKind.Done)
  backend.io.l1d.resp.bits.data := io.dmem.rsp.data
  backend.io.l1d.resp.bits.excCause := Mux(io.dmem.rsp.pageFault, 13.U, 5.U)
  backend.io.l1d.resp.bits.tval := io.dmem.rsp.faultAddr
  backend.io.l1d.late.valid := false.B
  backend.io.l1d.late.bits := 0.U.asTypeOf(backend.io.l1d.late.bits)
  backend.io.l1d.drained := !s1 && !s2
  backend.io.l1d.mmioBusy := false.B
  io.dmem.req := 0.U.asTypeOf(io.dmem.req)
  io.dmem.req.valid := backend.io.l1d.req.valid
  io.dmem.req.addr := backend.io.l1d.req.bits.vaddr
  io.dmem.req.sizeLog2 := backend.io.l1d.req.bits.size
  io.dmem.req.isWrite := backend.io.l1d.req.bits.op === L1DOp.Store
  io.dmem.req.wdata := backend.io.l1d.req.bits.wdata
  io.dcacheFlushReq := false.B
}
