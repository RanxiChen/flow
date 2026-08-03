# GShare 当前状态

更新时间：2026-08-03

## 结论

当前 GShare 已经具备预测、修正和训练的基本硬件闭环，并已接入可显式选择的
LiteX MCU 仿真路径。默认配置仍严格使用 baseline；只有传入
`--core-preset gshare` 才会生成、加载 GShare RTL。

代表性 `main.c` 已在 baseline/GShare 两种配置下完成同固件仿真。BTB、PHT、
MiniDecode、GHR/预测选择、后端纠错与单次训练，以及完整核心架构轨迹一致性均已有
定向回归。JALR 动态目标、ICache miss/redirect、trap/mret，以及 Timer/UART 的
Direct/Vectored 中断路径也已覆盖。按“证明 GShare 确实可用、不做微架构调优”的
当前目标，正确性 v1 已完成签收；这些结果不能直接作为正式性能结论。

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
- 新增 JALR 边界回归：同一个 JALR PC 连续切换三个动态目标；后端分别验证 stale
  target 只 redirect/update 一次，以及正确 target 不 redirect、不重复训练；
- 新增 ICache miss/redirect 回归：错误路径 miss 尚未返回时发生 redirect，旧 refill
  可以完成 cache transaction，但不得进入 fetch buffer，随后目标路径能够正常取指；
- 新增完整核心 trap/mret 回归：branch 后依次执行 ECALL、非法指令、两次 handler
  和两次 MRET，baseline/GShare 的逐条退休轨迹与最终架构状态完全一致；
- 新增 `sim/litex/run_gshare_regression.py`，使用同一个固件依次运行 baseline/GShare，
  校验 preset/completion marker、固件 SHA256、PMU/IPC 一致性，并对 deterministic
  generic 程序要求退休指令数一致；cycles/IPC 只报告，不设置性能门槛。
- Timer/UART × Direct/Vectored 四组 LiteX 双 preset 回归全部通过；每组均验证同一
  firmware SHA256、相同退休指令数、中断源触发、正确 vector slot、MRET 返回和最终
  completion PASS。

## 当前范围外事项

- 还没有多 workload、重复运行和已审核的 baseline/GShare 性能报告；
- 尚未进行 GHR/BTB/PHT 容量、索引、初始状态或 replacement 策略调优；
- 尚未把 IPC、prediction miss 等结果转化为性能门槛。当前回归只把架构结果、固件
  一致性和控制流边界作为正确性门槛，cycles/IPC 仅供观察。

## 正确性 v1 验收

当前范围内的验收已经完成：

1. BTB/PHT/GHR 和预测闭环已有独立定向测试；
2. baseline/GShare 已在完整核心中逐条比较架构退休轨迹；
3. LiteX 双 preset 已用同一固件 SHA256 完成 MCU 回归；
4. 同一固件、Cache 参数和存储时序下已报告 cycles、instructions、IPC；
5. PMU 已能报告 control、taken 和 prediction miss 等归因计数；
6. JALR 动态目标、错路径 ICache refill、trap/mret 已有边界定向回归；
7. Timer/UART 的 Direct/Vectored 中断在 baseline/GShare 下均完成端到端验证；
8. chen 全量 `sbt test` 为 21 suites、66 tests，66/66 通过。

因此 GShare 已可作为显式 opt-in 的可用功能进入下一阶段。后续若开始性能工作，应
单独定义 workload、重复次数、对照配置和性能门槛，不把本轮正确性签收外推成性能签收。
