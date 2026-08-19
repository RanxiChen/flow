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

        // Preset resolution: the string form and the sealed form agree, the
        // default is GShare and unknown names fail fast.
        CorePreset.fromName("gshare") mustBe CorePreset.Gshare
        CorePreset.fromName("baseline") mustBe CorePreset.Baseline
        an[IllegalArgumentException] must be thrownBy CorePreset.fromName("max")
        BreezeCoreConfigs.fromPreset("gshare").useGShare mustBe true
        BreezeCoreConfigs.fromPreset("baseline").useGShare mustBe false
        BreezeCoreConfigs.fromPreset(CorePreset.Gshare, enableTandem = false).useGShare mustBe true
        an[IllegalArgumentException] must be thrownBy BreezeCoreConfigs.fromPreset("max")
    }

    "cluster presets derive the frozen geometry from numHarts alone" in {
        val single = BreezeClusterPresets.single
        single.profileName mustBe "single"
        single.numHarts mustBe 1
        single.hartIdWidth mustBe 1
        single.sharerWidth mustBe 1
        single.txnIdWidth mustBe 2
        single.corePreset mustBe CorePreset.Gshare
        // L1D geometry comes from the core configuration (single source).
        single.l1d.capacityBytes mustBe 8192
        single.l1d.lineBytes mustBe 32
        single.l1d.ways mustBe 4
        single.l1d.sets mustBe 64
        single.l1d mustBe single.coreCfg().dcacheCfg
        // L1I matches the L1D geometry.
        single.l1i.ICACHE_SET_NUM mustBe 64
        single.l1i.ICACHE_WAY_NUM mustBe 4
        single.l1i.ICACHE_LINE_BYTES mustBe 32
        single.l2.capacityBytes mustBe 16384
        single.l2.ways mustBe 8
        single.l2.sets mustBe 64
        single.l2.lineBytes mustBe 32

        val dual = BreezeClusterPresets.dual
        dual.numHarts mustBe 2
        dual.hartIdWidth mustBe 1
        dual.sharerWidth mustBe 2
        dual.l2.capacityBytes mustBe 32768
        dual.l2.sets mustBe 128
        dual.corePreset mustBe CorePreset.Gshare

        val small = BreezeClusterPresets.small
        small.numHarts mustBe 4
        small.hartIdWidth mustBe 2
        small.sharerWidth mustBe 4
        small.l2.capacityBytes mustBe 65536
        small.l2.sets mustBe 256
        small.corePreset mustBe CorePreset.Gshare
    }

    "cluster presets keep explicit baseline without changing geometry" in {
        val dualBaseline = BreezeClusterPresets.dual.copy(corePreset = CorePreset.Baseline)
        dualBaseline.corePreset mustBe CorePreset.Baseline
        dualBaseline.coreCfg().useGShare mustBe false
        dualBaseline.numHarts mustBe 2
        dualBaseline.l2.capacityBytes mustBe 32768
        dualBaseline.l1d mustBe BreezeClusterPresets.dual.l1d
    }

    "8/16-hart profiles are rejected" in {
        an[IllegalArgumentException] must be thrownBy
            BreezeClusterConfig(profileName = "standard", numHarts = 8)
        an[IllegalArgumentException] must be thrownBy
            BreezeClusterConfig(profileName = "max", numHarts = 16)
        an[IllegalArgumentException] must be thrownBy BreezeClusterPresets.fromName("standard")
        an[IllegalArgumentException] must be thrownBy BreezeClusterPresets.fromName("max")
        an[IllegalArgumentException] must be thrownBy BreezeClusterPresets.fromName("8")
    }
}
