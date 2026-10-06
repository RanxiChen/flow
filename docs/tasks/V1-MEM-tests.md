# V1-MEM-tests：在 Alan 上运行访存测试并修 RTL（交给 codex）

**开始条件**：`V1-MEM-interfaces` 已完成。把 `feat/v1-mem-skeleton` 合并进 `feat/pcie-fase-20260920`（新增文件都在 `design/src/test/scala/memsys/` 和 `docs/`，预计无冲突）。

## 测试（Claude 编写）

| 文件 | 内容 |
| --- | --- |
| `memsys/Checkers.scala` | 黄金内存（含版本历史）、SWMR + 数据值监视器、看门狗、可复现随机数 |
| `memsys/CheckersSpec.scala` | 校验设施自身的测试（纯 Scala，不启动仿真器） |
| `memsys/Protocol.scala` | 四条链路消息的 Scala 镜像与 poke/peek |
| `memsys/Agents.scala` | 行为级 L1D 代理（严格遵守 L1D 客户端规则）、L1I/DMA 读者、AXI4 内存模型 |
| `memsys/L2Bench.scala`、`L2Spec.scala` | L2Home + 每核代理 + AXI 内存；定向 7 项，随机 3 种几何 × 4 seed |
| `memsys/L1DBench.scala`、`L1DSpec.scala` | L1DCache + 后端锁步模型 + 行为级 Home（含虚拟第二核偷行）+ 恒等 dTLB + MMIO；定向 10 项，随机 3 种几何 × 4 seed |

测试检查的内容：
- **锁步**（B01）：后端模型里 WB 中的请求每拍必须看到 `resp.valid` 或 `s2Hold`；
- **数据值**：每个 load 与黄金内存比对；每次带数据的 Put/probe 答复与黄金比对；不带数据时内存必须已是最新；
- **SWMR**：每拍检查，并且每个有效副本都等于黄金值；
- **看门狗**：单笔事务超过上限，或全局无进展，即失败，并打印现场；
- **精确拍数**：只取冻结合同已定的值：resp E+2（T02/T10）、late R+7（T11）、store→同字 load E+4（T08）、不同字不停（T09）、命中每拍一条（P01）。其余只查协议和数据。

## 你要做的

1. 跑 `testOnly flow.memsys.*`。`CheckersSpec` 必须先通过；不通过说明测试设施有问题，报告即可，不要改断言。
2. 骨架有大量 `TODO`，L1DSpec 和 L2Spec 一开始大面积失败是预期的。**按失败修 RTL**，补 `l1d/`、`l2/` 里的 `TODO`，依据是 `docs/l1d-rtl-spec.md` 和 `docs/coherence-l2-rtl-spec.md`。
3. 顺序：先 `L1DSpec` 定向 → `L2Spec` 定向 → 两边的随机项。
4. **不得改测试的期望或放宽检查**。认为测试本身错了（模型违反协议规则、期望与 spec 矛盾）时，在报告里写明：失败的 seed、拍号、现场 dump，以及你的推导，然后跳过这一项，继续做其他项。
5. spec 与仿真有冲突、需要改 spec 的，写进报告的"spec 问题"一节，附逐拍推导，由 Claude 裁定。L1D/L2 的 spec 不在冻结清单里，但仍不要自己改。

## 编写测试时已经预见的问题

写测试时读骨架发现的，作为第一批要修的对象：

- **PTW 在 S2 停住不发 `s2Hold`**：PTW 读在 S2 因 MSHR 满而保持时，S1 里的 CPU 请求也跟着停，但后端看不到 `s2Hold`。这会触发锁步失败（B01 同类）。修法写进报告让 Claude 裁定，例如让 PTW 不在 S2 保持、改为回 S0 重查。
- **S1 保持期间 dTLB 结果丢失**：`tlb.resp` 只在 req 之后一拍有效。骨架在 S1 前进的那一拍才用它，S1 保持多拍时 paddr 就丢了。S1 需要寄存 TLB 结果。
- **PMP 的 `privilege` 和 `access` 是 `DontCare`**：接口任务里补；没补之前所有访问都会被判异常。
- **probe 与回放的先后**：l1d-rtl-spec §10.2 第 1 行只在"role > 本地状态"时压住 probe。安装成 E 之后，owner probe 不满足这个条件，可能抢在 store 回放之前把行收走，回放就会不命中（断言），或者 store 丢失（数据检查会报）。如果仿真复现，报告推导，由 Claude 裁定。

## 验收

- `testOnly flow.memsys.*` 全部通过，或者剩下的每一项都有"测试问题 / spec 问题"的书面说明；
- 全量 `sbt test` 无新增失败；冻结检查 OK；
- 报告写到 `docs/tasks/V1-MEM-tests-report.md`：每个 suite 的结果、Alan 命令与日志路径、修了什么（文件:行）、spec 问题列表、随机项的 seed 与周期数。
