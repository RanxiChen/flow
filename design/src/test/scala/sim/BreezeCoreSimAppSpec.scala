package flow.sim

import flow.config.{FrontendBranchPredictorKind, GShareBranchPredictorConfig, NoBranchPredictorConfig}
import flow.config.PrivilegeProfile
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

    "BreezeCoreSimApp should select the Linux privilege profile for architectural tests" in {
        val coreCfg = BreezeCoreSimApp.buildCoreConfig(
          "baseline",
          enableTandem = false,
          privilegeProfileName = "linux"
        )

        coreCfg.privilegeProfile mustBe PrivilegeProfile.Linux
        coreCfg.enableCompressed mustBe true
        coreCfg.enableMmu mustBe true
    }

    "BreezeCoreSimApp should reject a failing architectural-test exit code" in {
        val ex = intercept[IllegalArgumentException] {
            BreezeCoreSimApp.validateResult(
              BreezeCoreSimResult(cycleCount = 10, timedOut = false, exitCode = Some(3)),
              exitAddress = Some(BigInt(0x100))
            )
        }

        ex.getMessage must include("exit code 0x3")
    }
}
