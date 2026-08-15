package flow.top

import chisel3._
import chisel3.util._
import flow.bus.{LiteXWishboneMasterIO, LiteXWishboneParameters}
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
  * P0 skeleton: the profile-parameterized top elaborates with every output
  * driven to a defined value. The per-hart tiles, shared L2/Home and MMIO
  * arbiter land in later phases without changing this port list.
  */
class BreezeMulticoreClusterWishbone(
    val clusterCfg: BreezeClusterConfig,
    val enabledebug: Boolean = false
) extends Module {
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
        val msip = Input(Vec(clusterCfg.numHarts, Bool()))
        val mtip = Input(Vec(clusterCfg.numHarts, Bool()))
        val externalInterrupts = Input(
            Vec(clusterCfg.numHarts, UInt(BreezeMcuPlatform.ExternalInterruptWidth.W))
        )
        val memoryWishbone = new LiteXWishboneMasterIO(memoryWbParams)
        val mmioWishbone = new LiteXWishboneMasterIO(mmioWbParams)
        val hartFatal = Output(Vec(clusterCfg.numHarts, Bool()))
        val hartEStop = Output(Vec(clusterCfg.numHarts, Bool()))
        val retire = Output(Vec(clusterCfg.numHarts, new TracePayload(64)))
    })

    // P0 skeleton tie-offs. Every output has a defined value so simulation
    // monitors can be built before the coherence datapath is connected.
    io.hartFatal.foreach(_ := false.B)
    io.hartEStop.foreach(_ := false.B)
    io.retire.foreach(_ := 0.U.asTypeOf(new TracePayload(64)))

    io.memoryWishbone.cyc := false.B
    io.memoryWishbone.stb := false.B
    io.memoryWishbone.we := false.B
    io.memoryWishbone.adr := 0.U
    io.memoryWishbone.dat_w := 0.U
    io.memoryWishbone.sel := 0.U
    io.memoryWishbone.cti := 0.U
    io.memoryWishbone.bte := 0.U

    io.mmioWishbone.cyc := false.B
    io.mmioWishbone.stb := false.B
    io.mmioWishbone.we := false.B
    io.mmioWishbone.adr := 0.U
    io.mmioWishbone.dat_w := 0.U
    io.mmioWishbone.sel := 0.U
    io.mmioWishbone.cti := 0.U
    io.mmioWishbone.bte := 0.U
}
