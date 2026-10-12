package flow.top

import chisel3._
import flow.backend.BreezeBackend
import flow.bus._
import flow.coherence._
import flow.config.BreezeClusterConfig
import flow.frontend.{BreezeFrontendPipeline, BreezeFrontendPipelineParams}
import flow.interface.TracePayload
import flow.l1d.{L1DCache, L1DEvents}
import flow.l1i.L1IClient
import flow.l2.{L2Events, L2Home}
import flow.platform.BreezeMcuPlatform
import flow.rvv._

/** BE2 scalar core with the R04 vector backend and single-hart write-through memory system. */
class BreezeRvvSystem(val cfg: BreezeClusterConfig, enableTandem: Boolean = false,
    enableBackendObservation: Boolean = false) extends Module {
  require(cfg.rvv.nonEmpty && cfg.nCores == 1)
  private val vp = cfg.rvv.get
  val mem = cfg.mem
  val coreCfg = cfg.coreCfg(enableTandem)
  require(!coreCfg.useFASE, "FASE backend/cluster contract remains unresolved")
  private val p = CoherenceParams(mem)
  val io = IO(new Bundle {
    val resetAddr = Input(UInt(64.W))
    val msip = Input(Vec(p.nCores, Bool()))
    val mtip = Input(Vec(p.nCores, Bool()))
    val time = Input(UInt(64.W))
    val externalInterrupts = Input(Vec(p.nCores, UInt(BreezeMcuPlatform.ExternalInterruptWidth.W)))
    val supervisorExternalInterrupts = Input(Vec(p.nCores, Bool()))
    val mem = new Axi4MasterIO(Axi4Params(p.paddrBits, vp.memoryBits, 2))
    val mmio = new Axi4LiteMasterIO(p.paddrBits, 64)
    val dma = Flipped(new ReadClientIO(p))
    val hartFatal = Output(Vec(p.nCores, Bool()))
    val hartEStop = Output(Vec(p.nCores, Bool()))
    val retire = Output(Vec(p.nCores, new TracePayload(64)))
    val backendObservation = if(enableBackendObservation)
      Some(Output(Vec(p.nCores, new flow.backend.BackendObservation))) else None
    val l1dEvents = Output(Vec(p.nCores, new L1DEvents))
    val l2Events = Output(new L2Events(p))
  })
  val home = Module(new RvvDatalessHome(mem, vp.memoryBits))
  val fabric = Module(new RvvAxiArbiter(mem.paddrBits, vp.memoryBits))
  io.l2Events := 0.U.asTypeOf(io.l2Events)
  val mmio = Module(new Axi4LiteArbiter(p.nCores))
  io.mem <> fabric.io.mem
  fabric.io.scalar <> home.io.mem
  io.mmio <> mmio.io.out
  home.io.dma <> io.dma

  for (h <- 0 until p.nCores) {
    val backend = Module(new BreezeBackend(coreCfg.backendCfg, hartId = h))
    io.backendObservation.foreach(_(h) := backend.io.observe)
    val frontend = Module(new BreezeFrontendPipeline(mem,
      BreezeFrontendPipelineParams(historyBits = coreCfg.backendCfg.ghrLength.max(2)),
      mmuParams = cfg.mmu, backendConnected = true, predictorEnabled = coreCfg.useGShare))
    val l1d = Module(new L1DCache(mem, withPmpCandidate = true, writeThrough = true, withEarlyCancel = true))
    l1d.earlyCancel.get := backend.io.cacheEarlyCancel
    val l1i = Module(new L1IClient(mem))
    val vectorTranslation = Module(new RvvTranslationClient(vp))
    val dtlbArb = Module(new RvvTlbArbiter)

    backend.io.resetAddr := io.resetAddr
    backend.io.machineSoftwareInterrupt := io.msip(h)
    backend.io.machineTimerInterrupt := io.mtip(h)
    backend.io.time := io.time
    backend.io.externalInterrupts := io.externalInterrupts(h)
    backend.io.supervisorExternalInterrupt := io.supervisorExternalInterrupts(h)
    backend.io.l1d <> l1d.io.core
    backend.io.mmuIdle := frontend.io.mmuIdle && vectorTranslation.io.idle
    BreezePipelineLink(backend, frontend)

    val context = backend.io.mmuContext
    dtlbArb.io.tlb <> frontend.io.dtlb.get
    dtlbArb.io.scalar <> l1d.io.tlb
    dtlbArb.io.scalarWants := l1d.tlbWants.get
    dtlbArb.io.vector <> vectorTranslation.io.tlb
    vectorTranslation.io.context := context
    val vector = backend.io.rvv.get
    vectorTranslation.io.request <> vector.translation
    vector.translated <> vectorTranslation.io.response
    vector.scalarWritesVisible := home.io.scalarWritesVisible && !l1d.writePending.get
    vector.conflictQuery := 0.U.asTypeOf(vector.conflictQuery)
    home.io.invalidate <> vector.invalidate
    home.io.scalarWritePending := l1d.writePending.get
    val va = vector.axi
    val fa = fabric.io.vector
    for ((from, to) <- Seq(va.ar -> fa.ar, va.aw -> fa.aw)) {
      to.valid := from.valid; from.ready := to.ready
      to.bits := 0.U.asTypeOf(to.bits)
      to.bits.addr := from.bits.addr; to.bits.id := from.bits.id
      to.bits.len := from.bits.len; to.bits.size := from.bits.size; to.bits.burst := from.bits.burst
    }
    fa.w.valid := va.w.valid; va.w.ready := fa.w.ready
    fa.w.bits.data := va.w.bits.data; fa.w.bits.strb := va.w.bits.strb; fa.w.bits.last := va.w.bits.last
    va.r.valid := fa.r.valid; fa.r.ready := va.r.ready
    va.r.bits.data := fa.r.bits.data; va.r.bits.id := fa.r.bits.id
    va.r.bits.resp := fa.r.bits.resp; va.r.bits.last := fa.r.bits.last
    va.b.valid := fa.b.valid; fa.b.ready := va.b.ready
    va.b.bits.id := fa.b.bits.id; va.b.bits.resp := fa.b.bits.resp
    l1d.io.ptw <> frontend.io.ptwMem
    l1i.io.demand <> frontend.io.refillReq
    frontend.io.refillRsp <> l1i.io.demandRsp
    // No prefetch trigger or installation contract exists in the frontend.
    l1i.io.prefetch.valid := false.B
    l1i.io.prefetch.bits := 0.U
    home.io.l1i <> l1i.io.coh
    home.io.l1d <> l1d.io.coh
    mmio.io.clients(h) <> l1d.io.mmio
    val hpmEvents = Wire(chiselTypeOf(frontend.io.hpm))
    hpmEvents := frontend.io.hpm
    hpmEvents.dcacheAccess := l1d.io.events.load_access || l1d.io.events.store_access
    hpmEvents.dcacheMiss := l1d.io.events.load_miss || l1d.io.events.store_miss
    hpmEvents.dcacheUncached := l1d.io.events.mmio_read || l1d.io.events.mmio_write
    backend.io.hpmEvents := RegNext(hpmEvents, 0.U.asTypeOf(hpmEvents))
    io.l1dEvents(h) := l1d.io.events
    io.hartFatal(h) := backend.io.hartFatal
    io.hartEStop(h) := backend.io.estop
    io.retire(h) := backend.io.tandem.getOrElse(0.U.asTypeOf(new TracePayload(64)))
  }
}
