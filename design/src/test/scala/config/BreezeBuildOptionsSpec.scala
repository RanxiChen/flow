package flow.config

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeBuildOptionsSpec extends AnyFreeSpec with Matchers {
    "each deployed profile defaults to the same explicit GShare/Linux hardware configuration" in {
        for (original <- BreezeClusterPresets.deployment) {
            val options = BreezeBuildOptions.parse(Seq(original.profileName))
            options.config mustBe original.copy(
                corePreset = CorePreset.Gshare, privilegeProfile = PrivilegeProfile.Linux)
            options.config.coreCfg().useGShare mustBe true
            options.config.coreCfg().enableMmu mustBe true
            options.config.coherentDma mustBe false
            options.enableTandem mustBe false
            options.debug mustBe false
        }
    }

    "debug enables retirement traces and coherent DMA preserves the selected memory geometry" in {
        val options = BreezeBuildOptions.parse(Seq("single-zve32f", "coherent-dma", "fpga-debug"))
        options.debug mustBe true
        options.enableTandem mustBe true
        options.config.coherentDma mustBe true
        options.config.mem mustBe BreezeClusterPresets.singleZve32f.mem
        options.config.zve mustBe BreezeClusterPresets.singleZve32f.zve
    }

    "tandem alone keeps FPGA debug disabled" in {
        val options = BreezeBuildOptions.parse(Seq("single", "tandem"))
        options.enableTandem mustBe true
        options.debug mustBe false
    }

    "removed profiles, predictor options and privilege options fail before RTL generation" in {
        for (args <- Seq(
            Seq("single-rvv"), Seq("dual"), Seq("stress"),
            Seq("single", "baseline"), Seq("single", "mcu"),
            Seq("single", "gshare", "linux"), Seq("single", "unknown"))) {
            withClue(args.mkString(" ")) {
                an[IllegalArgumentException] must be thrownBy BreezeBuildOptions.parse(args)
            }
        }
    }

    "missing profile and duplicate optional features are rejected" in {
        an[IllegalArgumentException] must be thrownBy BreezeBuildOptions.parse(Seq.empty)
        for (flag <- Seq("tandem", "fpga-debug", "coherent-dma")) {
            an[IllegalArgumentException] must be thrownBy
                BreezeBuildOptions.parse(Seq("single", flag, flag))
        }
    }
}
