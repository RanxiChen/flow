package flow.top

import chisel3._
import flow.bus.{BreezeMmioArbiter, DCacheWishboneBridge, LiteXWishboneMasterIO, LiteXWishboneParameters}
import flow.cache.{BreezeDCache, BreezeL2Home}
import flow.config.BreezeClusterConfig
import flow.interface.TracePayload
import flow.platform.BreezeMcuPlatform

/** SoC-facing wrapper of a 1/2/4-hart Breeze cluster - the only memory-system
  * top level of the design (the single profile is simply numHarts=1; there is
  * no separate single-core top any more).
  *
  * Interface contract (frozen by the multicore specification):
  *   - one shared 64-bit memory Wishbone master (L2/Home -> RAM);
  *   - one shared 64-bit MMIO Wishbone master (per-hart uncached requests
  *     through the blocking round-robin arbiter -> CLINT/UART/GPIO);
  *   - per-hart msip/mtip/external-interrupt inputs (msip/mtip are driven by
  *     the LiteX-side CLINT; external interrupts reach hart 0 only, per the
  *     frozen external-interrupt boundary);
  *   - per-hart fatal/estop and architectural retire trace for simulation;
  *   - a single shared reset address.
  *
  * Per hart the wrapper instantiates BreezeCore (with its private L1I inside
  * the frontend) and a private coherent L1D; all L1D coherence traffic and
  * every I$ refill go through the shared L2/Home.
  */
class BreezeMulticoreClusterWishbone(
    val clusterCfg: BreezeClusterConfig,
    val enableTandem: Boolean = false
) extends Module {
    private val numHarts = clusterCfg.numHarts
    private val coreCfg = clusterCfg.coreCfg(enableTandem = enableTandem)
    private val plen = BreezeMcuPlatform.AddressWidth
    private val wbParams = LiteXWishboneParameters(
        byteAddressWidth = plen,
        dataWidth = 64
    )

    val io = IO(new Bundle {
        val resetAddr = Input(UInt(64.W))
        val msip = Input(Vec(numHarts, Bool()))
        val mtip = Input(Vec(numHarts, Bool()))
        val time = Input(UInt(64.W))
        val externalInterrupts = Input(
            Vec(numHarts, UInt(BreezeMcuPlatform.ExternalInterruptWidth.W))
        )
        val supervisorExternalInterrupts = Input(Vec(numHarts, Bool()))
        val memoryWishbone = new LiteXWishboneMasterIO(wbParams)
        val mmioWishbone = new LiteXWishboneMasterIO(wbParams)
        val hartFatal = Output(Vec(numHarts, Bool()))
        val hartEStop = Output(Vec(numHarts, Bool()))
        val retire = Output(Vec(numHarts, new TracePayload(64)))
    })

    val l2Home = Module(new BreezeL2Home(clusterCfg.l2, numHarts))
    val mmioArbiter = Module(new BreezeMmioArbiter(numHarts, clusterCfg.l1d.lineBytes))
    val mmioBridge = Module(new DCacheWishboneBridge(
        physicalAddressWidth = plen,
        lineBytes = clusterCfg.l1d.lineBytes,
        busDataWidth = 64
    ))

    val cores = Seq.tabulate(numHarts) { h =>
        Module(new flow.core.BreezeCore(coreCfg, hartId = h))
    }
    val dcaches = Seq.tabulate(numHarts) { h =>
        Module(new BreezeDCache(
            coreCfg.dcacheCfg,
            hartId = h,
            hartIdWidth = clusterCfg.hartIdWidth,
            txnIdWidth = clusterCfg.txnIdWidth
        ))
    }

    for (h <- 0 until numHarts) {
        val core = cores(h)
        val dcache = dcaches(h)

        core.io.resetAddr := io.resetAddr
        core.io.machineTimerInterrupt := io.mtip(h)
        core.io.machineSoftwareInterrupt := io.msip(h)
        core.io.time := io.time
        core.io.externalInterrupts := io.externalInterrupts(h)
        core.io.supervisorExternalInterrupt := io.supervisorExternalInterrupts(h)

        // I$ refill goes to the unified L2 (GetInstr semantics: the I$ never
        // joins the directory). The core emits 64-bit physical addresses; the
        // frontend PMA already rejected anything wider than the implemented
        // 32-bit physical space, so the slice is safe.
        l2Home.io.instrReq(h).req := core.io.nextLevelReq.req
        l2Home.io.instrReq(h).paddr := core.io.nextLevelReq.paddr(plen - 1, 0)
        core.io.nextLevelRsp <> l2Home.io.instrResp(h)

        // D$ CPU-facing and flush wiring (unchanged pulse semantics).
        dcache.io.cpu <> core.io.dmem
        dcache.io.flushReq := core.io.dcacheFlushReq
        core.io.dcacheFlushDone := dcache.io.flushDone
        core.io.dcacheHpm := dcache.io.hpm
        dcache.io.resKill := core.io.reservationKill

        // D$ coherence sideband.
        l2Home.io.coherenceReq(h) <> dcache.io.coherence.req
        dcache.io.coherence.grant <> l2Home.io.coherenceGrant(h)
        dcache.io.coherence.probe <> l2Home.io.coherenceProbe(h)
        l2Home.io.coherenceProbeResp(h) <> dcache.io.coherence.probeResp

        // D$ uncached/MMIO path.
        mmioArbiter.io.hartReq(h) <> dcache.io.mmioReq
        dcache.io.mmioRsp <> mmioArbiter.io.hartResp(h)

        io.hartFatal(h) := dcache.io.fatalError
        io.hartEStop(h) := core.io.estop
    }

    // MMIO arbiter -> shared MMIO Wishbone master.
    mmioBridge.io.cacheReq <> mmioArbiter.io.memReq
    mmioArbiter.io.memResp <> mmioBridge.io.cacheResp
    io.mmioWishbone <> mmioBridge.io.wishbone

    // L2/Home -> shared memory Wishbone master.
    io.memoryWishbone <> l2Home.io.memoryWishbone

    // Retire traces exist only with tandem-enabled cores (the cluster RTL
    // generator enables them because the simulation monitors need them).
    if (enableTandem) {
        io.retire.zip(cores).foreach { case (trace, core) =>
            trace := core.io.tandem.get
        }
    } else {
        io.retire.foreach(_ := 0.U.asTypeOf(new TracePayload(64)))
    }
}
