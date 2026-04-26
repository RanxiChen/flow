package flow.sim

import flow.config.{FrontendBranchPredictorKind, GShareBranchPredictorConfig, NoBranchPredictorConfig}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeCoreSimAppSpec extends AnyFreeSpec with Matchers {
    "BreezeCoreSimApp should select baseline core config" in {
        val coreCfg = BreezeCoreSimApp.buildCoreConfig("baseline", enableTandem = false)

        coreCfg.useGShare mustBe false
        coreCfg.enableTandem mustBe false
        coreCfg.frontendCfg.branchPredCfg mustBe NoBranchPredictorConfig
        coreCfg.backendCfg.branchPredKind mustBe FrontendBranchPredictorKind.None
    }

    "BreezeCoreSimApp should select gshare core config" in {
        val coreCfg = BreezeCoreSimApp.buildCoreConfig("gshare", enableTandem = true)

        coreCfg.useGShare mustBe true
        coreCfg.enableTandem mustBe true
        coreCfg.frontendCfg.branchPredCfg mustBe GShareBranchPredictorConfig()
        coreCfg.backendCfg.branchPredKind mustBe FrontendBranchPredictorKind.GShare
    }

    "BreezeCoreSimApp should reject unsupported core config presets" in {
        val ex = intercept[IllegalArgumentException] {
            BreezeCoreSimApp.buildCoreConfig("unknown", enableTandem = false)
        }

        ex.getMessage must include("unsupported core preset")
    }
}
