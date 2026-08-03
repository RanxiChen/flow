# GShare 当前状态

更新时间：2026-08-03

## 结论

当前 GShare 已经具备预测、修正和训练的基本硬件闭环，并已接入可显式选择的
LiteX MCU 仿真路径。默认配置仍严格使用 baseline；只有传入
`--core-preset gshare` 才会生成、加载 GShare RTL。

代表性 `main.c` 已在 baseline/GShare 两种配置下完成同固件仿真。BTB、PHT、
MiniDecode、GHR/预测选择、后端纠错与单次训练，以及完整核心架构轨迹一致性均已有
定向回归。当前结果证明第一阶段集成和基础正确性可用，但还不能直接作为正式性能结论。

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
- LiteX elaboration、CPU wrapper、SoC 和 MCU runner 已贯通 `baseline|gshare` preset；
  baseline/GShare RTL 与 Verilator 输出彼此隔离，并由 marker 校验配置一致性；
- 修复 predicted-taken 分支在实际 not-taken 时仍跳回 branch target 的错误，后端现在
  使用 `exeNextPc` 同时覆盖 taken 和 fall-through 两种重定向目标；
- chen LiteX 代表性回归中，同一 `main.c` 在 baseline 与 GShare 下均退休 612 条指令
  并写出 completion PASS。
- 新增 BTB 单元测试，覆盖复位 miss、同 tag 更新、完整 PC tag 和满表 round-robin
  replacement；
- 新增 PHT 单元测试，覆盖 `PC[...:2] xor GHR` 索引、weakly not-taken 初值、
  2-bit counter 状态转移、上下饱和及 alias；
- 新增 MiniDecode 单元测试，覆盖 BR、JAL、JALR 和普通指令；
- 新增 GShare frontend 定向测试，覆盖 GHR 更新/保持、复位预测状态，以及训练后的
  BTB/PHT 命中和 predicted next PC；这些观测口只在 `enabledebug=true` 的测试构造中
  存在，不增加正式 LiteX 顶层端口；
- 新增 GShare backend 定向测试，覆盖 predicted-taken/actual-not-taken、
  predicted-not-taken/actual-taken、正确预测仍训练，以及 older load stall 期间不重复训练；
- 新增完整核心 baseline/GShare 架构一致性回归，逐条比较退休 PC、指令、next PC、
  寄存器写回和 estop，覆盖循环退出、交替分支、JAL 和 load-to-branch；
- 新增 `sim/litex/run_gshare_regression.py`，使用同一个固件依次运行 baseline/GShare，
  校验 preset/completion marker、固件 SHA256、PMU/IPC 一致性，并对 deterministic
  generic 程序要求退休指令数一致；cycles/IPC 只报告，不设置性能门槛。

## 当前缺口

- 当前定向测试已覆盖 taken/not-taken、交替分支、JAL、load stall 和 redirect，
  但 JALR 动态目标变化、ICache miss 与 redirect 竞争、trap/mret 组合仍未覆盖；
- 仓库没有保存一组已审核的 `branch_test` baseline/GShare 运行结果；
- GShare LiteX 路径已经可显式选择，但 Timer/UART、不同分支模式和复杂异常组合的
  GShare 回归尚未完成；
- 当前只有代表性 `main.c` 的探索性对照，还没有多 workload、重复运行和已审核的
  baseline/GShare 性能报告。

## 后续验收条件

第一阶段验收已经完成：

1. BTB/PHT/GHR 和预测闭环已有独立定向测试；
2. baseline/GShare 已在完整核心中逐条比较架构退休轨迹；
3. LiteX 双 preset 已用同一固件 SHA256 完成 MCU 回归；
4. 同一固件、Cache 参数和存储时序下已报告 cycles、instructions、IPC；
5. PMU 已能报告 control、taken 和 prediction miss 等归因计数。

进入正式性能调优前仍需补 JALR、ICache miss/redirect、trap/mret、Timer/UART 和多
workload 重复运行。当前 GShare 可以用于受控探索和下一阶段开发，但尚不能视为已经
签收的性能特性。
