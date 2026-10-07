package flow.config

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeCoreConfigSpec extends AnyFreeSpec with Matchers {
    "privilege profiles should keep MCU as default and enable Linux Bare mode explicitly" in {
        PrivilegeProfile.fromName("mcu") mustBe PrivilegeProfile.Mcu
        PrivilegeProfile.fromName("linux") mustBe PrivilegeProfile.Linux
        an[IllegalArgumentException] must be thrownBy PrivilegeProfile.fromName("sv39")
        BreezeCoreConfig().privilegeProfile mustBe PrivilegeProfile.Mcu
        BreezeCoreConfig(privilegeProfile = PrivilegeProfile.Linux)
            .backendCfg.privilegeProfile mustBe PrivilegeProfile.Linux
        BreezeClusterPresets.small.copy(privilegeProfile = PrivilegeProfile.Linux)
            .coreCfg().privilegeProfile mustBe PrivilegeProfile.Linux
    }

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

    "cluster presets carry the memory geometry as the single entry point" in {
        val single = BreezeClusterPresets.single
        single.profileName mustBe "single"
        single.nCores mustBe 1
        single.mem mustBe BreezeMemGeometry.singleCore
        single.corePreset mustBe CorePreset.Gshare
        BreezeClusterPresets.dual.nCores mustBe 2
        BreezeClusterPresets.small.nCores mustBe 4
        BreezeClusterPresets.small.mem mustBe BreezeMemGeometry.default
        BreezeClusterPresets.stress.mem mustBe BreezeMemGeometry.stress
        BreezeClusterPresets.smokeL2w4.mem.l2Ways mustBe 4
        BreezeClusterPresets.smokeL1dw2.mem.l1dWays mustBe 2
        BreezeClusterPresets.smokeL1dw2.mem.l1iWays mustBe 4
        BreezeClusterPresets.smoke1core.nCores mustBe 1
        for (cfg <- BreezeClusterPresets.all)
            BreezeClusterPresets.fromName(cfg.profileName) mustBe cfg
    }

    "default geometry derives the l1d-rtl-spec §0.2 constants" in {
        val l1d = flow.l1d.L1DParams(BreezeMemGeometry.default)
        l1d.ways mustBe 4
        l1d.sets mustBe 128
        l1d.offBits mustBe 5
        l1d.idxBits mustBe 7
        l1d.tagBits mustBe 20
        l1d.wordsPerLine mustBe 4
        l1d.capacityBytes mustBe 16384
        l1d.lineAddrBits mustBe 27
        l1d.nMshrs mustBe 1
        l1d.plruBits mustBe 3
        val coh = flow.coherence.CoherenceParams(BreezeMemGeometry.default)
        coh.nCores mustBe 4
        coh.l2Sets mustBe 1024
        coh.sharerBits mustBe 4
        coh.coreBits mustBe 2
        coh.nReqPorts mustBe 9
        flow.coherence.CoherenceParams(BreezeMemGeometry.l2FourWay).l2Sets mustBe 2048
        flow.coherence.CoherenceParams(BreezeMemGeometry.singleCore).sharerBits mustBe 1
        flow.coherence.CoherenceParams(BreezeMemGeometry.stress).l2Sets mustBe 4
        val l1i = flow.l1i.L1IParams(BreezeMemGeometry.l1dTwoWay)
        l1i.ICACHE_WAY_NUM mustBe 4
        l1i.ICACHE_SET_NUM mustBe 128
    }

    "cluster presets keep explicit baseline without changing geometry" in {
        val dualBaseline = BreezeClusterPresets.dual.copy(corePreset = CorePreset.Baseline)
        dualBaseline.corePreset mustBe CorePreset.Baseline
        dualBaseline.coreCfg().useGShare mustBe false
        dualBaseline.mem mustBe BreezeClusterPresets.dual.mem
    }

    "geometry outside the v1 constraints is rejected at construction" in {
        // l1d-rtl-spec §13.4 negative test: 256 x 32 B > 4096 breaks VIPT.
        an[IllegalArgumentException] must be thrownBy BreezeMemGeometry(l1Sets = 256)
        an[IllegalArgumentException] must be thrownBy BreezeMemGeometry(nCores = 0)
        an[IllegalArgumentException] must be thrownBy BreezeMemGeometry(nCores = 9)
        an[IllegalArgumentException] must be thrownBy BreezeMemGeometry(l1dWays = 3)
        an[IllegalArgumentException] must be thrownBy BreezeMemGeometry(l1dMshrs = 2)
        an[IllegalArgumentException] must be thrownBy BreezeMemGeometry(lineBytes = 64)
        an[IllegalArgumentException] must be thrownBy BreezeMemGeometry(paddrBits = 40)
        an[IllegalArgumentException] must be thrownBy BreezeClusterPresets.fromName("standard")
        an[IllegalArgumentException] must be thrownBy BreezeClusterPresets.fromName("8")
    }
}
