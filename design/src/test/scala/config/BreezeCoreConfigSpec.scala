package flow.config

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeCoreConfigSpec extends AnyFreeSpec with Matchers {
    "BreezeCoreConfig should derive baseline frontend and backend branch predictor settings when requested" in {
        val cfg = BreezeCoreConfig(useFASE = false, useGShare = false)

        cfg.frontendCfg.branchPredCfg mustBe NoBranchPredictorConfig
        cfg.backendCfg.branchPredKind mustBe FrontendBranchPredictorKind.None
        cfg.backendCfg.ghrLength mustBe 0
    }

    "BreezeCoreConfig should derive gshare frontend and backend branch predictor settings" in {
        val cfg = BreezeCoreConfig(
            useFASE = false,
            useGShare = true,
            gshareGhrLength = 10,
            gshareBtbEntryNum = 32
        )

        cfg.frontendCfg.branchPredCfg mustBe GShareBranchPredictorConfig(
            ghrLength = 10,
            btbEntryNum = 32
        )
        cfg.backendCfg.branchPredKind mustBe FrontendBranchPredictorKind.GShare
        cfg.backendCfg.ghrLength mustBe 10
    }

    "parameterless defaults select GShare at every Scala layer" in {
        // Raw case class defaults.
        BreezeCoreConfig().useGShare mustBe true
        BreezeCoreConfig().frontendCfg.branchPredCfg.kind mustBe FrontendBranchPredictorKind.GShare
        BreezeCoreConfig().backendCfg.branchPredKind mustBe FrontendBranchPredictorKind.GShare
        BreezeCoreConfig().backendCfg.ghrLength mustBe 8
        BreezeFrontendConfig().branchPredCfg.kind mustBe FrontendBranchPredictorKind.GShare
        BackendConfig().branchPredKind mustBe FrontendBranchPredictorKind.GShare
        BackendConfig().ghrLength mustBe 8
        BreezeCoreConfigs.gshare().useGShare mustBe true
        BreezeCoreConfigs.gshare().frontendCfg.branchPredCfg.kind mustBe
            FrontendBranchPredictorKind.GShare

        // Generator preset resolution.
        BreezeCoreConfigs.fromPreset("gshare").useGShare mustBe true
        BreezeCoreConfigs.fromPreset("gshare").frontendCfg.branchPredCfg.kind mustBe
            FrontendBranchPredictorKind.GShare
        BreezeCoreConfigs.fromPreset("baseline").useGShare mustBe false
        BreezeCoreConfigs.fromPreset("baseline").frontendCfg.branchPredCfg.kind mustBe
            FrontendBranchPredictorKind.None
        an[IllegalArgumentException] must be thrownBy BreezeCoreConfigs.fromPreset("max")
    }

    "cluster presets derive the frozen geometry from numHarts" in {
        val single = BreezeClusterPresets.single
        single.profileName mustBe "single"
        single.numHarts mustBe 1
        single.hartIdWidth mustBe 1
        single.l1i mustBe single.l1d
        single.l1i.capacityBytes mustBe 8192
        single.l1i.lineBytes mustBe 32
        single.l1i.ways mustBe 4
        single.l1i.sets mustBe 64
        single.l2.capacityBytes mustBe 16384
        single.l2.ways mustBe 8
        single.l2.sets mustBe 64
        single.corePreset mustBe "gshare"

        val dual = BreezeClusterPresets.dual
        dual.profileName mustBe "dual"
        dual.numHarts mustBe 2
        dual.hartIdWidth mustBe 1
        dual.l2.capacityBytes mustBe 32768
        dual.l2.sets mustBe 128
        dual.corePreset mustBe "gshare"

        val small = BreezeClusterPresets.small
        small.profileName mustBe "small"
        small.numHarts mustBe 4
        small.hartIdWidth mustBe 2
        small.l2.capacityBytes mustBe 65536
        small.l2.sets mustBe 256
        small.corePreset mustBe "gshare"
        small.sharerWidth mustBe 4
    }

    "cluster presets keep explicit baseline without changing geometry" in {
        val dualBaseline = BreezeClusterPresets.dual.copy(corePreset = "baseline")
        dualBaseline.corePreset mustBe "baseline"
        dualBaseline.numHarts mustBe 2
        dualBaseline.l2.capacityBytes mustBe 32768
    }

    "8/16-hart profiles are rejected" in {
        an[IllegalArgumentException] must be thrownBy
            BreezeClusterConfig(
                profileName = "standard",
                numHarts = 8,
                l1i = L1CacheGeometry(),
                l1d = L1CacheGeometry(),
                l2 = L2CacheGeometry(capacityBytes = 8 * 2 * 8192)
            )
        an[IllegalArgumentException] must be thrownBy
            BreezeClusterConfig(
                profileName = "max",
                numHarts = 16,
                l1i = L1CacheGeometry(),
                l1d = L1CacheGeometry(),
                l2 = L2CacheGeometry(capacityBytes = 16 * 2 * 8192)
            )
        an[IllegalArgumentException] must be thrownBy BreezeClusterPresets.fromName("standard")
        an[IllegalArgumentException] must be thrownBy BreezeClusterPresets.fromName("max")
    }

    "cluster config validates the frozen capacity relationship" in {
        an[IllegalArgumentException] must be thrownBy
            BreezeClusterConfig(
                profileName = "dual",
                numHarts = 2,
                l1i = L1CacheGeometry(),
                l1d = L1CacheGeometry(),
                l2 = L2CacheGeometry(capacityBytes = 16384) // wrong: must be 2*2*8192
            )
    }
}
