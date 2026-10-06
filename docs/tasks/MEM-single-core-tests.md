# MEM：单核 directed 与随机测试（交给 codex 跑和修）

起点：第一批 RTL `0e9e109`。测试由 Claude 编写，本地未编译、未运行（`agent.md`：sbt 只在 Alan 执行）。先读 [`v1-mem-plan.md`](../v1-mem-plan.md) 第 4–6 步。

## 1. 文件（`design/src/test/scala/memsys/`）

| 文件 | 内容 |
| --- | --- |
| `MemTestKit.scala` | 黄金内存（确定性背景值 + 按行写历史）、`CycleAgent`、`Bench`（drive → decide → sample → step，无进展看门狗 4000 拍） |
| `MemHarness.scala` | `IdentityTlb`（恒等映射，可注入 busy/miss/pageFault）、`L1DTestHarness`（L1D 单独）、`L1DL2Harness`（L1D + L2Home，单核） |
| `MemAgents.scala` | `CoreDriver`（按后端语义驱动 `L1DCoreIO`，含 s1Kill/s2Kill）、`PtwDriver`、`BehavioralL2`（按协议第 1 节检查 L1D 侧全部规则）、`AxiLiteDevice`、`AxiMemory`、`ReadClientAgent`（L1I/DMA）、`L1DProxy`（Scala L1D，供 L2 单测） |
| `L1DCacheSpec.scala` | L1D + 行为 L2：hit/miss 全尺寸、store hit/miss、S→M 升级、victim 与写回、hit-under-miss、单 MSHR 等待、kill、probe（Inv/Down/未持有）、grant 同拍 probe 压住、升级中 sharer Inv、refill 错误、MMIO、异常、TLB miss、PTW、FENCE、随机 3 种子 + 两组非默认几何 |
| `L2HomeSpec.scala` | L2Home + Scala L1D/L1I/DMA + AXI 内存：冷 miss、Put、Down 合并、MaskWrite（持有/未缓存）、L2 驱逐 probe 与写回、AXI 读错误、两槽并发、随机 2 种子 + stress 单 set 与 4 路 |
| `L1DL2SystemSpec.scala` | 真实 L1D + L2 端到端：读写、两级驱逐、L1I/DMA 拉脏数据、DMA 写失效、PTW/MMIO、随机 2 种子 + 三组非默认几何 |

检查方式：每个 load 在 resp 或 late 时与黄金内存逐字节比对；L1D 测试结束时 Inv 全部行，比较写回内存与黄金内存；L2/系统测试结束时经 DMA 读回全部写过的行并精确比对。RTL 断言同样作为失败条件。LR/SC、AMO、aq/rl 未实现，测试不涉及。

## 2. 运行

```
sbt "testOnly flow.memsys.L1DCacheSpec flow.memsys.L2HomeSpec flow.memsys.L1DL2SystemSpec flow.memsys.MemSkeletonElabSpec flow.memsys.L1DPermissionsSpec"
```

## 3. 规则

1. 先修编译错误。测试代码未经编译，语法与 ChiselSim API 用法错误直接修，不需要报告。
2. 失败时先判断是 RTL 错还是测试错：对照 [`l1d-rtl-spec.md`](../l1d-rtl-spec.md)、[`coherence-l2-rtl-spec.md`](../coherence-l2-rtl-spec.md)。RTL 错就修 RTL。
3. 改测试的期望值、放宽检查或删用例，必须在报告里逐条写出 spec 依据。不允许为了通过而降低检查强度；行为模型确实违反 spec 的，修模型。
4. `L1DCoreIO`、后端、MMU 不改。spec 与仿真冲突时按 `v1-mem-plan.md` 规则处理，并同步 spec。
5. 先跑 L1D 单测和 L2 单测，都通过后再跑系统测试。随机用例失败时记录种子、失败拍号和最小复现。

## 4. 报告

写 `docs/tasks/MEM-single-core-tests-report.md`：被测 SHA、命令、每个 spec 的通过数/总数与退出码；每个 RTL 修复的根因（一两句）与对应用例；每处测试改动及其 spec 依据；仍失败或跳过的用例。
