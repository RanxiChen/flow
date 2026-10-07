package flow.top

import chisel3._
import flow.backend.BreezeBackend
import flow.buffer.FetchBuffer
import flow.bus._
import flow.coherence._
import flow.config.BreezeClusterConfig
import flow.frontend.BreezeFrontend
import flow.interface.TracePayload
import flow.l1d.{L1DCache, L1DEvents}
import flow.l1i.{FetchTlbClient, L1IClient}
import flow.l2.{L2Events, L2Home}
import flow.mmu.sv39.Sv39Mmu
import flow.platform.BreezeMcuPlatform

/** Native memory-system connection boundary. Cache miss/replay execution is
  * still the responsibility of the L1D/L2 implementations, not this shell.
  */
class BreezeCluster(val cfg: BreezeClusterConfig, enableTandem: Boolean = false) extends Module {
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
    val mem = new Axi4MasterIO(Axi4Params(p.paddrBits, p.memDataBits, p.slotBits))
    val mmio = new Axi4LiteMasterIO(p.paddrBits, 64)
    val dma = Flipped(new ReadClientIO(p))
    val hartFatal = Output(Vec(p.nCores, Bool()))
    val hartEStop = Output(Vec(p.nCores, Bool()))
    val retire = Output(Vec(p.nCores, new TracePayload(64)))
    val l1dEvents = Output(Vec(p.nCores, new L1DEvents))
    val l2Events = Output(new L2Events(p))
  })
  val l2 = Module(new L2Home(mem))
  io.l2Events := l2.io.events
  val mmio = Module(new Axi4LiteArbiter(p.nCores))
  io.mem <> l2.io.mem
  io.mmio <> mmio.io.out
  l2.io.dma <> io.dma

  for (h <- 0 until p.nCores) {
    val backend = Module(new BreezeBackend(coreCfg.backendCfg, hartId = h))
    val frontend = Module(new BreezeFrontend(coreCfg.frontendCfg, memGeometry = mem))
    val buffer = Module(new FetchBuffer(64, 6, coreCfg.backendCfg.ghrLength))
    val l1d = Module(new L1DCache(mem))
    val l1i = Module(new L1IClient(mem))
    val mmu = Module(new Sv39Mmu)
    val fetchTlb = Module(new FetchTlbClient)

    backend.io.resetAddr := io.resetAddr
    backend.io.machineSoftwareInterrupt := io.msip(h)
    backend.io.machineTimerInterrupt := io.mtip(h)
    backend.io.time := io.time
    backend.io.externalInterrupts := io.externalInterrupts(h)
    backend.io.supervisorExternalInterrupt := io.supervisorExternalInterrupts(h)
    backend.io.l1d <> l1d.io.core
    backend.io.mmuIdle := mmu.io.idle
    frontend.io.resetAddr := io.resetAddr
    frontend.io.beRedirect := backend.io.frontendRedirect
    frontend.io.btbUpdate := backend.io.frontendBtbUpdate
    frontend.io.phtUpdate := backend.io.frontendPhtUpdate
    frontend.io.ghrUpdate := backend.io.frontendGhrUpdate
    buffer.io.in <> frontend.io.fetchBuffer
    buffer.io.flush := backend.io.frontendRedirect.flush
    backend.io.fetchBuffer <> buffer.io.out

    val context = backend.io.mmuContext
    mmu.io.csr.sv39 := (if (coreCfg.enableMmu) context.satp(63, 60) === 8.U else false.B)
    mmu.io.csr.asid := context.satp(59, 44)
    mmu.io.csr.rootPpn := context.satp(43, 0)
    mmu.io.csr.priv := context.privilege
    mmu.io.csr.mprv := context.mprv; mmu.io.csr.mpp := context.mpp
    mmu.io.csr.sum := context.sum; mmu.io.csr.mxr := context.mxr
    mmu.io.sfence.valid := backend.io.sfence.valid
    mmu.io.sfence.rs1Nz := backend.io.sfence.useVaddr
    mmu.io.sfence.rs2Nz := backend.io.sfence.useAsid
    mmu.io.sfence.vaddr := backend.io.sfence.vaddr
    mmu.io.sfence.asid := backend.io.sfence.asid
    mmu.io.dtlb <> l1d.io.tlb
    l1d.io.ptw <> mmu.io.ptwMem
    fetchTlb.io.tlb <> mmu.io.itlb
    fetchTlb.io.context := context
    fetchTlb.io.kill := backend.io.frontendRedirect.flush
    fetchTlb.io.block := backend.io.translationBlocked
    fetchTlb.io.request <> frontend.io.translateReq
    frontend.io.translateRsp <> fetchTlb.io.response

    l1i.io.demand <> frontend.io.nextLevelReq
    frontend.io.nextLevelRsp <> l1i.io.demandRsp
    // No prefetch trigger or installation contract exists in the frontend.
    l1i.io.prefetch.valid := false.B
    l1i.io.prefetch.bits := 0.U
    l2.io.l1i(h) <> l1i.io.coh
    l2.io.l1d(h) <> l1d.io.coh
    mmio.io.clients(h) <> l1d.io.mmio
    backend.io.hpmEvents := frontend.io.hpm
    backend.io.hpmEvents.dcacheAccess := l1d.io.events.load_access || l1d.io.events.store_access
    backend.io.hpmEvents.dcacheMiss := l1d.io.events.load_miss || l1d.io.events.store_miss
    backend.io.hpmEvents.dcacheUncached := l1d.io.events.mmio_read || l1d.io.events.mmio_write
    io.l1dEvents(h) := l1d.io.events
    io.hartFatal(h) := backend.io.hartFatal
    io.hartEStop(h) := backend.io.estop
    io.retire(h) := backend.io.tandem.getOrElse(0.U.asTypeOf(new TracePayload(64)))
  }
}

