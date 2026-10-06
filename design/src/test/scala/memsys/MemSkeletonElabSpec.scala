package flow.memsys

import chisel3._
import circt.stage.ChiselStage
import flow.config.BreezeMemGeometry
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
  "FetchTlbClient elaborates" in {
    ChiselStage.emitSystemVerilog(new FetchTlbClient, firtoolOpts = Array("-disable-all-randomization"))
  }

}
