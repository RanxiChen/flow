package flow.top

import chisel3._
import flow.cache.{BreezeCoherenceGrantIO, BreezeCoherenceProbeIO, BreezeCoherenceProbeRespIO, BreezeCoherenceReqIO, BreezeDCache}
import flow.config.{BreezeClusterConfig, BreezeCoreConfigs}
import flow.core.BreezeCore
import flow.interface._
import flow.platform.BreezeMcuPlatform

/** One hart tile: BreezeCore + private L1I (inside the core's frontend) +
  * private coherent L1D.
  *
  * The CPU-facing cache interfaces keep their historical semantics; the tile
  * only fans out the lower-side ports:
  *   - I$ line refill pulses -> instrReq/instrResp (served by the L2/Home,
  *     which answers with GetInstr semantics and never tracks the I$);
  *   - D$ uncached/MMIO scalar pulses -> mmioReq/mmioResp (served by the
  *     cluster MMIO arbiter);
  *   - D$ cached operations -> the MESI coherence sideband (cohReq/cohGrant/
  *     cohProbe/cohProbeResp) served by the L2/Home.
  */
class BreezeHartTile(
    val clusterCfg: BreezeClusterConfig,
    val hartId: Int,
    val enabledebug: Boolean = false,
    val enableTandem: Boolean = false
) extends Module {
  require(hartId >= 0 && hartId < clusterCfg.numHarts, "tile hartId out of range")

  private val coreCfg = BreezeCoreConfigs.fromPreset(
    clusterCfg.corePreset, enableTandem = enableTandem)
  private val plen = BreezeMcuPlatform.AddressWidth
  private val lineBytes = clusterCfg.l1d.lineBytes

  val io = IO(new Bundle {
    val resetAddr = Input(UInt(64.W))
    val machineTimerInterrupt = Input(Bool())
    val externalInterrupts = Input(UInt(BreezeMcuPlatform.ExternalInterruptWidth.W))
    // I$ refill (tile -> Home pulse, Home -> tile response).
    val instrReq = new L1CacheMissReqIO(plen)
    val instrResp = new L1CacheMissRespIO(clusterCfg.l1i.lineWidth)
    // D$ uncached/MMIO (tile -> arbiter pulse, arbiter -> tile response).
    val mmioReq = new DCacheMemReqIO(64, lineBytes)
    val mmioResp = new DCacheMemRespIO(lineBytes)
    // D$ coherence sideband.
    val cohReq = new BreezeCoherenceReqIO(plen, lineBytes, clusterCfg.hartIdWidth)
    val cohGrant = Flipped(new BreezeCoherenceGrantIO(plen, lineBytes, clusterCfg.hartIdWidth))
    val cohProbe = Flipped(new BreezeCoherenceProbeIO(plen, clusterCfg.hartIdWidth))
    val cohProbeResp = new BreezeCoherenceProbeRespIO(plen, lineBytes, clusterCfg.hartIdWidth)
    val fatalError = Output(Bool())
    val estop = Output(Bool())
    // Architectural retirement trace (simulation debug; spec section 21).
    val retire = if (enableTandem) Some(Output(new TracePayload(64))) else None
  })

  val core = Module(new BreezeCore(coreCfg, enabledebug = enabledebug, hartId = hartId))
  val dcache = Module(new BreezeDCache(
    coreCfg.dcacheCfg,
    coherent = true,
    hartId = hartId,
    hartIdWidth = clusterCfg.hartIdWidth
  ))

  core.io.resetAddr := io.resetAddr
  core.io.machineTimerInterrupt := io.machineTimerInterrupt
  core.io.externalInterrupts := io.externalInterrupts

  // I$ refill path. The core emits 64-bit physical addresses; the PMA inside
  // the frontend has already rejected anything wider than the implemented
  // 32-bit physical space, so the slice is safe.
  io.instrReq.req := core.io.nextLevelReq.req
  io.instrReq.paddr := core.io.nextLevelReq.paddr(plen - 1, 0)
  core.io.nextLevelRsp <> io.instrResp

  // D$ CPU-facing and flush wiring (unchanged semantics).
  dcache.io.cpu <> core.io.dmem
  dcache.io.flushReq := core.io.dcacheFlushReq
  core.io.dcacheFlushDone := dcache.io.flushDone
  core.io.dcacheHpm := dcache.io.hpm

  // D$ lower-side fan-out.
  io.mmioReq <> dcache.io.nextLevelReq
  dcache.io.nextLevelRsp <> io.mmioResp
  io.cohReq <> dcache.io.coherence.get.req
  dcache.io.coherence.get.grant <> io.cohGrant
  dcache.io.coherence.get.probe <> io.cohProbe
  io.cohProbeResp <> dcache.io.coherence.get.probeResp

  io.fatalError := dcache.io.fatalError
  io.estop := core.io.estop
  io.retire.zip(core.io.tandem).foreach { case (tileRetire, coreTandem) =>
    tileRetire := coreTandem
  }
}
