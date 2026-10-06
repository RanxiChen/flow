# V1-MEM 外部接口执行报告

起点：`ae3d7ac`，分支 `feat/pcie-fase-20260920`。本地编辑、提交/push；Alan 按 SHA 验证。现有未跟踪文件保留；冻结文件及 `interface/L1DCoreIO.scala` 不改；L1D/L2 内部 TODO 不实现。

## 0. 命名与旧实现清理

| 类别 | 提交 | 动作 |
| --- | --- | --- |
| 后端、观测、合同及环境 | `9fa1263` | V1Scoreboard→Scoreboard、V1Writeback→Writeback、V1BackendObservation→BackendObservation；内部类型、测试、trace 辅助类去 V1；[V1-CYCLE]→[CYCLE]、断言去版本标记 |
| MUL | `3ec9230` | CommittedMulUnit→MulUnit；删除 RiscvMulUnit。其算术 suite 已直接接提交接口，改为 MulUnitSpec，保留全部场景 |
| DIV | `e232725` | CommittedDivUnit→DivUnit；删除 RiscvDivUnit。其算术 suite 已直接接提交接口，改为 DivUnitSpec，保留全部场景 |
| FPU | `b21a139` | CommittedFpUnit→FpUnit；删除 BreezeFpUnit 及旧包装测试；保留 FpUnitSpec 的 tag/commit/kill/乱序/flags 合同 |
| PLRU | `874a62f` | 删除 BreezePLRU，统一 TreePlru；保留缓存行为测试。BreezePLRUSpec 两个场景原本在块注释内（0 个实际测试），删空 suite |

删除引用检查：各删除提交前执行 `git grep -n -w RiscvMulUnit -- design`、`RiscvDivUnit`、`BreezeFpUnit`、`BreezePLRU`，在移除定义/测试后均无输出、退出 1。旧文件自身与其旧测试是唯一直接引用（PLRU 的两处缓存使用先改为 TreePlru）。`FlowFpnewWrapper.sv` 原本就是直连 tag/valid/ready，未发现旧 response 缓存；缓存实际位于 Scala BreezeFpUnit，随类删除。SV 只更新过时注释，没有改 CVFPU 算术配置。

### 删除的旧测试（逐项）

- `fpu/BreezeFpUnitSpec.scala`：execute exact FP32/FP64 arithmetic and report IEEE exception flags。删除旧包装，算术有效行为另加直接 FpUnit 接口测试；不是删掉算术要求。
- `fpu/BreezeFpUnitSpec.scala`：flush an accepted long-latency operation without a stale response。旧全 flush 包装已删除；新 commit/kill/drain 语义由 FpUnitSpec 的 S05_S15 覆盖，已提交 DIV 不得被全 flush 丢掉。

以下旧 suite 依赖 V1LegacyTestAdapter。删除适配层及其全部依赖，未保留旧脉冲 dmem 到新 RTL 的适配。有效行为使用 BackendContractSpec、MduTimingSpec、Mul/DivProtocolSpec、FpUnitSpec 与新增 BackendBehaviorSpec 的原生接口验证；旧完成旁路/停顿拍数不再构成当前合同。

- `design/src/test/scala/backend/V1LegacyTestAdapter.scala`：无独立测试，适配层或辅助函数随依赖删除。
- `design/src/test/scala/backend/BreezeBackendMulSpec.scala`：multiply completion bypass feeds the immediately dependent EXE instruction。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeRedirectPrioritySpec.scala`：WB store fault beats younger branch, memory, sfence and fence side effects。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeRedirectPrioritySpec.scala`：WB mret beats a simultaneous EX branch and satp blocks younger issue。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeRedirectPrioritySpec.scala`：overlapping fetch fault flags select one cause rather than OR causes。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeRedirectPrioritySpec.scala`：keep EPC at the instruction start but report a fault on its second parcel。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeRedirectPrioritySpec.scala`：forward a CSR result to the following SC store operand。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendFpMemorySpec.scala`：load/store FP values with correct width, boxing and byte mask。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendFpMemorySpec.scala`：honor a back-to-back sstatus FS enable before FLD。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendFpMemorySpec.scala`：trap a misaligned FP64 load without issuing a memory request。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendFpTestUtils.scala`：无独立测试，适配层或辅助函数随依赖删除。
- `design/src/test/scala/backend/BreezeBackendFpSpec.scala`：trap FP instructions while mstatus.FS is Off。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendFpSpec.scala`：execute dependent FP64 arithmetic/FMA and commit sticky fflags。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should redirect a predicted-taken branch to fall-through when actually not-taken。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should redirect a predicted-not-taken branch to its taken target。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should train a correctly predicted branch without redirecting。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should not retrain a branch while an older load holds the pipeline。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should drain $name and train its younger branch before taking a timer interrupt。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should repair a stale JALR target exactly once。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should preserve a high Sv39 JALR target from a load。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendGShareSpec.scala`：GShare backend should keep a correct JALR target without retraining。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。
- `design/src/test/scala/backend/BreezeBackendDivSpec.scala`：RV64 divider executes all eight operations, fast paths, and completion bypass。删除理由：旧适配依赖和旧脉冲时序；有效架构语义移到直接 L1D 接口测试，具体覆盖及缺项见最终验证节。

## 验证记录（执行中）

证据根目录：Alan `/home/chen/FUN/flow-runs/20261006-mem-interfaces/`。`00-baseline.log` 为起点实际全量 sbt test；01–05 分别绑定各类命名提交，日志和 exit/sha/start/end 分开保存。此前 V1-BE 的 65/65 是指定 11 suites，不是仓库全量，不能当本次基线。

命令：`source /home/chen/miniforge3/bin/activate flow; cd /home/chen/FUN/flow/design; /home/chen/.local/share/coursier/bin/sbt test`。冻结：`python3 tools/frozen_check.py`，截至当前每次提交前均 OK（9 files）。
