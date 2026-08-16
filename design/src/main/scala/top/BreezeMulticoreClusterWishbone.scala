package flow.top

import chisel3._
import flow.bus.{BreezeMmioArbiter, DCacheWishboneBridge, LiteXWishboneMasterIO, LiteXWishboneParameters}
import flow.cache.BreezeL2Home
import flow.config.BreezeClusterConfig
import flow.interface.TracePayload
import flow.platform.BreezeMcuPlatform

/** SoC-facing wrapper of a 1/2/4-hart Breeze cluster.
  *
  * Interface contract (frozen by the multicore specification):
  *   - one shared 64-bit memory Wishbone master (L2/Home -> RAM);
  *   - one shared 64-bit MMIO Wishbone master (per-hart uncached requests
  *     through the blocking round-robin arbiter -> CLINT/UART/GPIO);
  *   - per-hart msip/mtip/external-interrupt inputs;
  *   - per-hart fatal/estop and architectural retire trace for simulation;
  *   - a single shared reset address.
  *
  * P2: the per-hart tiles, the shared L2/Home and the MMIO arbiter are
  * connected. The msip inputs are not consumed yet (the multi-hart CLINT and
  * machine software interrupts land in P8); external interrupts reach hart 0
  * only, per the frozen external-interrupt boundary.
  */
class BreezeMulticoreClusterWishbone(
    val clusterCfg: BreezeClusterConfig,
    val enabledebug: Boolean = false,
    val enableTandem: Boolean = false
) extends Module {
    private val numHarts = clusterCfg.numHarts
    private val memoryWbParams = LiteXWishboneParameters(
        byteAddressWidth = BreezeMcuPlatform.AddressWidth,
        dataWidth = 64
    )
    private val mmioWbParams = LiteXWishboneParameters(
        byteAddressWidth = BreezeMcuPlatform.AddressWidth,
        dataWidth = 64
    )

    val io = IO(new Bundle {
        val resetAddr = Input(UInt(64.W))
        val msip = Input(Vec(numHarts, Bool()))
        val mtip = Input(Vec(numHarts, Bool()))
        val externalInterrupts = Input(
            Vec(numHarts, UInt(BreezeMcuPlatform.ExternalInterruptWidth.W))
        )
        val memoryWishbone = new LiteXWishboneMasterIO(memoryWbParams)
        val mmioWishbone = new LiteXWishboneMasterIO(mmioWbParams)
        val hartFatal = Output(Vec(numHarts, Bool()))
        val hartEStop = Output(Vec(numHarts, Bool()))
        val retire = Output(Vec(numHarts, new TracePayload(64)))
    })

    val tiles = Seq.tabulate(numHarts)(h =>
        Module(new BreezeHartTile(clusterCfg, hartId = h, enabledebug = enabledebug,
            enableTandem = enableTandem)))
    val l2Home = Module(new BreezeL2Home(clusterCfg.l2, numHarts))
    val mmioArbiter = Module(new BreezeMmioArbiter(numHarts, clusterCfg.l1d.lineBytes))
    val mmioBridge = Module(new DCacheWishboneBridge(
        physicalAddressWidth = BreezeMcuPlatform.AddressWidth,
        lineBytes = clusterCfg.l1d.lineBytes,
        busDataWidth = 64
    ))

    for (h <- 0 until numHarts) {
        tiles(h).io.resetAddr := io.resetAddr
        // Machine timer interrupts are per-hart; msip is wired in P8 with the
        // CLINT. External interrupts go to hart 0 only (frozen boundary).
        tiles(h).io.machineTimerInterrupt := io.mtip(h)
        tiles(h).io.externalInterrupts := (if (h == 0) {
            io.externalInterrupts(0)
        } else {
            0.U(BreezeMcuPlatform.ExternalInterruptWidth.W)
        })

        // I$ refill path.
        l2Home.io.instrReq(h) <> tiles(h).io.instrReq
        tiles(h).io.instrResp <> l2Home.io.instrResp(h)

        // D$ coherence sideband.
        l2Home.io.coherenceReq(h) <> tiles(h).io.cohReq
        tiles(h).io.cohGrant <> l2Home.io.coherenceGrant(h)
        tiles(h).io.cohProbe <> l2Home.io.coherenceProbe(h)
        l2Home.io.coherenceProbeResp(h) <> tiles(h).io.cohProbeResp

        // D$ uncached/MMIO path.
        mmioArbiter.io.hartReq(h) <> tiles(h).io.mmioReq
        tiles(h).io.mmioResp <> mmioArbiter.io.hartResp(h)

        io.hartFatal(h) := tiles(h).io.fatalError
        io.hartEStop(h) := tiles(h).io.estop
    }

    // MMIO arbiter -> shared MMIO Wishbone master.
    mmioBridge.io.cacheReq <> mmioArbiter.io.memReq
    mmioArbiter.io.memResp <> mmioBridge.io.cacheResp
    io.mmioWishbone <> mmioBridge.io.wishbone

    // L2/Home -> shared memory Wishbone master.
    io.memoryWishbone <> l2Home.io.memoryWishbone

    // Retire traces exist only with tandem-enabled cores (the cluster RTL
    // generator enables them, mirroring the single-core generator, because the
    // simulation monitors need them - spec section 21). Without tandem the
    // debug outputs stay tied off.
    if (enableTandem) {
        io.retire.zip(tiles).foreach { case (trace, tile) =>
            trace := tile.io.retire.get
        }
    } else {
        io.retire.foreach(_ := 0.U.asTypeOf(new TracePayload(64)))
    }
}
