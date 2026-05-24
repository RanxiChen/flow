package flow.config

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeCoreConfigSpec extends AnyFreeSpec with Matchers {
    "BreezeCoreConfig should derive baseline frontend and backend branch predictor settings" in {
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
}
