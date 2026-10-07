# MEM：多核 L1D + L2 测试（交给 codex 跑和修）

执行状态（2026-10-07）：已完成。Alan `8fcf486` 模块/配置 111/111、真实单核系统 12/12、多核 21/21，三条门槛命令均 exit=0。已修复 FENCE/probe 等待环并补强默认两核的 L2 写回刺激；完整版本、失败现场、随机统计与覆盖边界见 [`MEM-multicore-tests-report.md`](MEM-multicore-tests-report.md)。以下保留初始任务约定。

起点：单核原子门槛 `2ac677c` 之后、包含本任务测试文件的提交。测试由 Claude 编写，本地未编译、未运行（`agent.md`：sbt 只在 Alan 执行）。先读 [`v1-mem-plan.md`](../v1-mem-plan.md) 第 5–6 步与 [`MEM-single-core-tests.md`](MEM-single-core-tests.md) 的规则。多核一致性 RTL（L2 目录 sharers、多目标 probe、跨核 Down/Inv）此前只经 elaborate，本任务第一次在仿真中运行，预期会出现真实 RTL 问题。

## 1. 文件（`design/src/test/scala/memsys/`）

| 文件 | 内容 |
| --- | --- |
| `MemHarness.scala` | 新增 `CohLinkObs` 与 `L1DL2MultiHarness(g)`：`g.nCores` 个真实 L1DCache + L2Home，每核独立 CPU 口、恒等 dTLB、PTW 入口、MMIO；L1I×n 与 DMA 由测试驱动；`obs` 导出每核四条链路的握手。单核 `L1DL2Harness` 不变 |
| `MemCoherenceMonitor.scala` | 链路级一致性监视器：只依据握手重建每核每行状态（S / E或M），检查 SWMR、grant 与请求及此前状态一致、probe 答复与 probe 及状态一致、带数据答复只来自 owner、每核至多 1 个未答复 probe、1 Get + 1 Put 在途、Put 期间不发同行 Get。违规时打印拍号、核、行地址、该行持有者与最近 32 条消息 |
| `MemMultiCoreOracle.scala` | 多核参照 `MultiCoreOracle`（按字分类判定，见 §3）与 `ProgramFeeder`（按需补充请求；LR 完成后才生成 `SC = LR值 + inc`） |
| `MemAgents.scala` | `CoreDriver` 增加可选 `name/hart/oracle` 与 `onDone`；有 oracle 时非 device、非预期异常的访存由 oracle 判定，原单核路径完全不变 |
| `L1DL2MultiCoreSpec.scala` | directed 7 项、LR/SC 前进 3 项、litmus 3 项、随机 8 项（下表） |

| 用例 | 内容 |
| --- | --- |
| 共享读 | 核 0 DataE，核 1 读触发 Down，两核均 S，AXI 只读一次 |
| 写使失效 | 两核 S，核 0 写：核 1 收 Inv，核 0 经 AckE/DataE 独占；核 1 再读 miss 并得到新值 |
| M 行被读 | 核 0 写脏，核 1 读：Down，DownAck 带数据，两核 S，核 0 继续命中，L2 保留合并后数据 |
| 同拍 GetM | 24 轮，同行不同字分属两核，I 与 S 两种起点，FENCE 填充制造不同到达时差；两核都曾独占，交叉读取最新值 |
| Put 与 Get 竞争 | 核 0 写脏后用同 L1D set 的 `l1dWays` 行逼出 Put，同时核 1 以不同延迟 Get 该行；读值必须为脏数据 |
| DMA 写两 sharer | MaskWrite 使两核 S 副本均收 Inv，随后两核读到 DMA 字节 |
| AMOADD 计数 | 两核各 200 次 AMOADD.D（含 aq/rl），计数链闭合、总和 400 |
| LR/SC 两核 / 四核 | 每核 LR→SC 自增，直到每核成功 60 / 30 次；看门狗兼作前进性判据 |
| LR/SC 对 AMO | 核 0 LR/SC 40 次成功，核 1 同字 400 次 AMOADD；总和精确 |
| MP+fence | 64 轮，禁止 flag=新、data=旧；随机预热 S 副本与延迟 |
| MP 无 fence | 只记录结果分布（RVWMO 全部允许），用于覆盖 |
| SB+fence | 64 轮，禁止两边都读到旧值 |
| 随机 | 2 核 / 4 核默认几何、`stress` 几何 2 核 / 4 核，各种子 81、82；每核 1000 次（stress 2000 次）；共享行含 Counter/Racy/两核 Owned 字（假共享），每核私有行溢出一个 L1D set；Load/Store/AMO（9 种运算）/LR-SC/FENCE、aq/rl、TLB miss/busy、late 反压、AXI 反压，L1I/DMA 无检查读共享行以产生 Down |

每个用例结束：监视器空闲且无违规；DMA 经 L2 读回全部用过的行，Owned/Free 字逐字节精确，Racy/Counter 按 §3 判定。

## 2. 运行

先确认现有门槛未被 `CoreDriver` 改动及 `fbff817`（配置并入 `BreezeClusterConfig`、L2 事件线、集群门槛改用 Linux 配置）影响，再跑多核：

```sh
sbt "testOnly flow.config.BreezeCoreConfigSpec flow.memsys.MemAgentsSpec flow.memsys.L1DCacheSpec flow.memsys.L2HomeSpec flow.memsys.MemSkeletonElabSpec flow.memsys.L1DPermissionsSpec"
sbt "testOnly flow.memsys.L1DL2SystemSpec"
sbt "testOnly flow.memsys.L1DL2MultiCoreSpec"
```

前两条全部通过后才运行第三条。多核失败时先跑 directed（`-z "two cores reading"` 等），再跑随机。

## 3. 检查强度与漏检面

提交点：每核访存在 S2 resp（Load miss 为 `late`）按程序序提交。跨核不假设全局顺序：Store miss 在取得所有权前即 resp（l1d-rtl-spec §6.2），resp 时刻不是一致性顺序。因此 oracle 只用对任何一致性顺序都成立的逐字事实：

| 字类 | 写者 | 检查 |
| --- | --- | --- |
| Owned(c) | 仅核 c（Store/AMO/SC） | 核 c 自身访存与 `arch` 精确相等（同单核）；他核读取必须是该字历史中某值，且不早于本核此前读到的位置（CoRR）；最终值精确 |
| Racy | 任意核，8 B 唯一值 Store | 读值必须是初值或某次写入值；同一写者的写序不回退；本核写后不再读到初值；读到他核更新写后不再读回本核旧写；最终值必须是某核的最后一次写 |
| Counter | AMOADD.D、LR.D→SC.D（+inc）、8 B Load | 每次成功更新的旧值不低于本核已见值；按旧值排序后各更新首尾相接（无丢失、无重复，原子性）；所有读值都是链上某点；最终值 = 初值 + 总增量 |
| Free | directed 顺序使用 | `arch` 在提交点更新，用例自行断言读值，最终值精确 |

漏检面（如实记录，不作为已覆盖）：

- Racy 字不检查跨写者的一致性顺序是否唯一（两核对两写者顺序看法相反的 CoRR 违例检测不到），也不检查读值是否在区间内"过旧"（只检查不回退）。
- Counter 只覆盖 AMOADD；其他 8 种 AMO 运算的跨核原子性只在 Owned 字上（单写者）检查，跨核只由 AMOADD 链证明 RMW 原子性。
- litmus 只有 MP、SB 两种形状和 FENCE（rw,rw 由 `drained` 实现）；没有 aq/rl 版本的 litmus、IRIW、WRC、ISA2，也没有对 RVWMO 允许集的完整枚举。
- 监视器只看链路，不看 L1D 内部 tag 或 L2 目录；若 L1D 在答复后仍错误命中旧副本，由数据检查（CoRR、精确值）间接发现，而非监视器。L2 目录不变式由 RTL 断言覆盖（coherence spec §10）。
- 不注入 refill 错误、page fault、kill（多核用例中均关闭）；这些已在单核用例覆盖，多核交互未覆盖。
- LR/SC 前进性以 watchdog（4000 拍无进展）与固定成功次数判断，不证明任意竞争下的有界前进。
- PTW、MMIO 端口在多核用例中空闲；L1I 只做无检查 Read。

## 4. 规则

同 [`MEM-single-core-tests.md`](MEM-single-core-tests.md) §3：

1. 先修编译错误。测试代码未经编译，语法与 ChiselSim API 用法错误直接修，不需要报告。
2. 失败时先判断 RTL 错还是测试错：对照 [`l1d-rtl-spec.md`](../l1d-rtl-spec.md)、[`coherence-l2-rtl-spec.md`](../coherence-l2-rtl-spec.md)。监视器或 oracle 规则若与 spec 冲突，按 spec 修测试并在报告逐条写出依据；不能为通过而删检查、降规模或改期望。
3. `L1DCoreIO`、后端、MMU 不改；RTL 修复同步更新 spec。
4. 随机失败记录种子、失败拍号、监视器打印的最近消息，并给出最小 directed 复现（保留为新用例）。

## 5. 报告

写 `docs/tasks/MEM-multicore-tests-report.md`：被测 SHA、三条命令、每个 spec 的通过数/总数与退出码；每个 RTL 修复的根因（一两句）与对应用例；每处测试改动及其 spec 依据；随机用例的 `info` 统计（oracle 计数、Inv/Down/Put 数、litmus 结果分布）；仍失败或跳过的用例。
