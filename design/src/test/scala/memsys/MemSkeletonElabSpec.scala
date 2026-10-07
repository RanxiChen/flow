package flow.memsys

import chisel3._
import circt.stage.ChiselStage
import flow.config.{BreezeClusterConfig, BreezeClusterPresets, BreezeMemGeometry, PrivilegeProfile}
import flow.bus._
import flow.coherence.CoherenceParams
import flow.top.{BreezeCluster, BreezeClusterWishbone}
import flow.l1d.L1DCache
import flow.l2.L2Home
import flow.l1i.{L1ICache, L1IClient, L1IParams, FetchTlbClient}
import org.scalatest.freespec.AnyFreeSpec

/** Skeleton gate: L1D and L2 elaborate to SystemVerilog in every v1 geometry. */
class MemSkeletonElabSpec extends AnyFreeSpec {
  private val geometries = Seq(
    "default" -> BreezeMemGeometry.default,
    "l2FourWay" -> BreezeMemGeometry.l2FourWay,
    "l1dTwoWay" -> BreezeMemGeometry.l1dTwoWay,
    "singleCore" -> BreezeMemGeometry.singleCore,
    "stress" -> BreezeMemGeometry.stress)

  for ((name, g) <- geometries) {
    // Linux profile: the Sv39 MMU and parallel L1I lookup are elaborated.
    val cluster = BreezeClusterConfig(name, g, privilegeProfile = PrivilegeProfile.Linux)
    s"BreezeCluster elaborates ($name)" in {
      ChiselStage.emitSystemVerilog(new BreezeCluster(cluster), firtoolOpts = Array("-disable-all-randomization"))
    }
    s"BreezeClusterWishbone elaborates with DMA ($name)" in {
      ChiselStage.emitSystemVerilog(new BreezeClusterWishbone(cluster, withDma = true), firtoolOpts = Array("-disable-all-randomization"))
    }
    s"DMA and AXI bridges elaborate ($name)" in {
      val p = CoherenceParams(g)
      ChiselStage.emitSystemVerilog(new DmaWishboneClient(g), firtoolOpts = Array("-disable-all-randomization"))
      ChiselStage.emitSystemVerilog(new Axi4WishboneBridge(Axi4Params(32,64,p.slotBits)), firtoolOpts = Array("-disable-all-randomization"))
      ChiselStage.emitSystemVerilog(new Axi4LiteArbiter(g.nCores), firtoolOpts = Array("-disable-all-randomization"))
    }
    s"L1ICache elaborates ($name)" in {
      ChiselStage.emitSystemVerilog(new L1ICache(L1IParams(g)), firtoolOpts = Array("-disable-all-randomization"))
    }
    s"L1IClient elaborates ($name)" in {
      ChiselStage.emitSystemVerilog(new L1IClient(g), firtoolOpts = Array("-disable-all-randomization"))
    }
    s"L1DCache elaborates ($name)" in {
      ChiselStage.emitSystemVerilog(new L1DCache(g), firtoolOpts = Array("-disable-all-randomization"))
    }
    s"L2Home elaborates ($name)" in {
      ChiselStage.emitSystemVerilog(new L2Home(g), firtoolOpts = Array("-disable-all-randomization"))
    }
  }
  "AXI-Lite bridge and cluster without DMA elaborate" in {
    ChiselStage.emitSystemVerilog(new Axi4LiteWishboneBridge, firtoolOpts = Array("-disable-all-randomization"))
    ChiselStage.emitSystemVerilog(new BreezeClusterWishbone(BreezeClusterPresets.single), firtoolOpts = Array("-disable-all-randomization"))
  }
  "FetchTlbClient elaborates" in {
    ChiselStage.emitSystemVerilog(new FetchTlbClient, firtoolOpts = Array("-disable-all-randomization"))
  }

}
