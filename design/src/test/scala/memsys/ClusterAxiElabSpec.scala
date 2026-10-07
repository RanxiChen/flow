package flow.memsys

import circt.stage.ChiselStage
import flow.config.{BreezeClusterPresets, PrivilegeProfile}
import flow.top.BreezeClusterAxi
import org.scalatest.freespec.AnyFreeSpec

class ClusterAxiElabSpec extends AnyFreeSpec {
  for (cfg <- Seq(BreezeClusterPresets.single, BreezeClusterPresets.small); debug <- Seq(false, true)) {
    s"${cfg.profileName} AXI shell debug=$debug has exactly the selected ports" in {
      val sv = ChiselStage.emitSystemVerilog(new BreezeClusterAxi(
        cfg.copy(privilegeProfile = PrivilegeProfile.Linux), debug = debug),
        firtoolOpts = Array("-disable-all-randomization"))
      val top = sv.substring(sv.indexOf("module BreezeClusterAxi("))
      val ports = top.substring(0, top.indexOf(";"))
      assert(ports.contains("io_mem_ar_bits_id"))
      assert(ports.contains("io_mmio_aw_bits_addr"))
      assert(ports.contains("io_debug_retire_lateWriteData") == debug)
      assert(ports.contains("io_debug_l1dEvents_load_access") == debug)
      assert(!ports.contains("Wishbone") && !ports.contains("dma"))
    }
  }
}
