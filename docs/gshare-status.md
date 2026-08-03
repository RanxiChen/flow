# GShare 当前状态

更新时间：2026-08-03

## 结论

当前 GShare 已经具备预测、修正和训练的基本硬件闭环，但仍属于“实现骨架完成、
系统性验证未完成”的状态。它尚未接入默认 LiteX MCU elaboration，也不能用于发布
baseline/GShare 性能结论。

本次只记录状态，不修改 GShare RTL、配置或测试。

## 已实现

- 默认参数为 8-bit GHR、16-entry BTB；
- PHT 使用 `PC index xor GHR`，共有 256 个 2-bit 饱和计数器，复位为 weakly
  not-taken；
- BTB 使用完整 PC tag，支持 BR、JAL 和 JALR，空 entry 优先、满后 round-robin
  replacement；
- S1 完成 BTB/PHT lookup 并选择预测 next PC；
- 预测类型、方向、目标和 PHT index 沿 S2、S3、fetch buffer 和后端流水传递；
- S3 MiniDecode 能识别 BR/JAL/JALR，并修正 JAL 与 taken branch 的直接目标；
- 后端比较预测方向和目标，错误时向前端 redirect；
- 后端实际执行结果能够更新 BTB、PHT 和 GHR；
- branch 被 memory stall 保持时，不再重复 redirect 或重复训练预测器；
- core-only `BreezeCoreSimApp` 支持选择 `baseline` 或 `gshare`，并已有
  `sim/breezecore/tests/branch_test/` workload 入口。

## 当前缺口

- 没有 BTB、PHT、GHR 的独立单元测试；
- 没有 cold miss、训练、再次命中并正确预测的完整定向回归；
- 没有系统覆盖 taken/not-taken、交替分支、JAL/JALR 目标变化、cache miss、stall、
  redirect、trap/mret 等组合场景；
- 现有核心 branch redirect 回归运行在 baseline 配置，不是 GShare 回归；
- 仓库没有保存一组已审核的 `branch_test` baseline/GShare 运行结果；
- `sbt elaborate` 仍固定生成 `BreezeCoreConfigs.baseline(enableTandem = true)`；
- LiteX MCU runner 尚无 `--core-preset baseline|gshare` 选择；
- 当前新增的 IPC monitor 只服务默认 baseline MCU，本次不用于验证 GShare。

## 后续验收条件

GShare 进入性能调优前，至少需要：

1. 补齐 BTB/PHT/GHR 单元测试和预测闭环定向测试；
2. 在 GShare 配置下跑完整核心正确性回归；
3. 使用相同二进制证明 baseline 与 GShare 的架构结果一致；
4. 为 LiteX elaboration 和 runner 增加明确的 core preset 选择，并隔离不同 RTL
   产物，避免误用旧配置；
5. 在同一固件、Cache 参数和存储时序下比较 cycles、instructions、IPC；
6. 再增加 branch、mispredict 等归因计数，解释 IPC 变化来源。

在完成这些验收项以前，GShare 可以用于探索性实验，但不能视为已经签收的性能特性。
