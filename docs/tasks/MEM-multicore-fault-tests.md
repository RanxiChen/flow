# MEM：多核 kill / trap / page fault / refill 错误 / PTW 交互测试（交给 codex 跑和修）

起点：多核门槛 `8fcf486`（报告 [`MEM-multicore-tests-report.md`](MEM-multicore-tests-report.md)）之后、包含本任务测试文件的提交。测试由 Claude 编写，本地未编译、未运行（`agent.md`：sbt 只在 Alan 执行）。先读 [`MEM-multicore-tests.md`](MEM-multicore-tests.md) 的检查方式与规则。这些交互此前只在单核用例覆盖，多核下 kill 与 probe/GetM、trap 与 LR 窗口、refill 错误与 L2 驱逐、PTW 与他核写的组合第一次运行。

## 1. 文件（`design/src/test/scala/memsys/`）

| 文件 | 改动 |
| --- | --- |
| `L1DL2MultiCoreFaultSpec.scala` | 新增：`FaultScript`（按拍执行的动作：trap 脉冲、PTW 读）与 12 个 directed + 6 个随机用例 |
| `MemHarness.scala` | `IdentityTlb(faultRegion)`、`L1DL2MultiHarness(g, faultRegion)`：可选地址区内每次 dTLB 查询 page fault（TLB miss 重查后仍 fault）；默认 `None`，硬件与原来相同 |
| `MemAgents.scala` | `CoreTxn.killedAt`；`CoreDriver.onKilled`（kill 回调）、`killOnExc`（后端 trap 模型：Exc 响应同拍 `s2Kill` + `trapClearRsv`，异常本身照常检查，年轻请求被杀）、`trapNextCycle()`；多核路径下带 `refillError` 的 Store 不提交（l1d §6.2：回放时丢弃）；`PtwDriver.judge`（可替换精确黄金比对） |
| `MemMultiCoreOracle.scala` | `ProgramFeeder`：LR 被杀 → 发 `scExpected=1` 的 SC（必须失败）；SC 被杀 → 下一拍 trap 脉冲清 reservation；`scMutate`、`killedLr/killedSc` 计数。oracle：Counter 字上 `scExpected=1` 且失败的 SC 允许没有配对 LR |
| `MemCoherenceMonitor.scala` | `allowErrors`（默认 false，原多核用例保持严格）：带 error 的 DataS/DataE 不建立持有状态（L1D tag 置 I）并记入 `errorGrants`；新增 `reqs` 记录每次 REQ 握手 |

所有共享文件改动都只在新开关或 kill/错误发生时生效；原 `L1DCacheSpec`、`L1DL2SystemSpec`、`L1DL2MultiCoreSpec` 不注入多核 kill/错误、不设 `killOnExc`/`judge`/`allowErrors`/`faultRegion`，刺激与检查不变。

| 用例 | 内容 | 依据 |
| --- | --- | --- |
| S2 保持的 Store 被杀 | 核 0 load miss 占 MSHR（内存延迟 150），同为 S 的行 b 上 Store 在 S2 等升级；核 1 写 b 另一字。Inv 必须在 kill 之前答复；Store 不响应、不发 GetM、不写 | l1d §3、§5.2、§10.1 |
| S2 响应拍杀 Store | 8 个变体（S 起点 / I 起点 × FENCE 填充）：不发 GetM，他核取得行，原值保留 | l1d §3、§5.2 |
| AMO 在 GetM 等待 / RMW 写边沿被杀 | 48 轮，kill 延迟 3–26 拍扫描 + 每 4 轮一次响应拍 kill；核 1 同时 AMOADD/读同一 Counter。要求至少一轮"GetM 已在 kill 前握手且 grant 在 kill 后"、至少一轮写边沿 kill；Counter 总和 = 未被杀 AMO 的增量和 | l1d §3、§8.3 |
| 被杀 LR 无 reservation | s1Kill、响应拍 kill、等 GetM 时 kill（核 1 持 M）；之后 SC 必须失败，有/无他核写两组；等待中被杀的 GetM 仍安装；对照组同核 LR→SC 成功 | l1d §3、§6.2、§8.1 |
| trap 与 probe 同拍 | 28 轮，trap 脉冲相对核 1 写的延迟 0–27 扫描；InvAck 必须在 max(trap, probe 到达) + 20 拍内（否则会等 80 拍窗口）；要求至少一轮 trap 恰在 probe 握手拍；之后 SC 失败 | l1d §8.1、§10.2 |
| TLB miss 等待中答 probe | X 在 S2 翻译等待、Y 在 S1：Y 为同行 Store / X 本身为同行 Store / X page fault（trap 模型杀 Y）三种；核 1 的 GetM 必须在 300 拍 TLB miss 结束前完成 | l1d §7.1、§10.1 |
| page fault 不发请求 | 四种 fault（13/15）与他核 Inv 并发；fault 本身不清 reservation（SC 成功），trap 模型下清除（SC 失败）；fault 区无 REQ、无 AXI 读 | l1d §5.1、§8.1 |
| refill 错误不安装 | 单核、两核先后与同时、L1I/DMA 读：每次都重新读内存；清除错误后正常安装，之后命中不再读 | coherence §5.2、§7.1；l1d §6.2 |
| Store/AMO/LR/PTW 错误 | Store 丢弃、AMO 7、LR 5、PTW accessFault；无 reservation 的 SC 不 miss；他核随后读到原数据 | l1d §5.2、§6.2、§7.2 |
| 驱逐后 refill 错误 | stress 两核：核 1 的两条脏行占满 L2 set，核 0 错误行 miss 驱逐其一并写回；核 1 数据不丢 | coherence §5.2 |
| PTW 读他核反复改写的 PTE | 核 1 字节/半字/全字写与 AMOOR 位设置交替，PTW 每 17 拍读一次：值必须是该字真实出现过的整字值且不回退（无撕裂）；要求 Down 与 Inv 都出现 | l1d §7.2、coherence §1 |
| PTW 与 M 行 | 他核持 M → Down 取脏数据；本核 M → 命中、无新 REQ；他核改写后再读经 Down | l1d §7.2 |
| 随机 | 两核默认（种子 91、92）、四核默认（91）、两核 stress（91、92）、四核 stress（91）；在多核随机流量上加入 s1/s2 kill（7%，含 SC 5%）、随机 trap 脉冲、page fault（3%，trap 模型）、refill 错误（2%，独立错误行，与共享行同 set、不入 oracle）、L1I 错误读、PTW 读共享行与错误行 | 同上 |

每个用例结束：监视器空闲无违规；所有 error grant 只落在测试设为错误的行上；fault 区没有任何 REQ 和 AXI 读；清除注入后 DMA 经 L2 读回全部用过的行，Owned/Free 精确、Racy/Counter 按 oracle。错误行从未被写入，不在读回集合中。

## 2. 运行

先确认共享文件改动没有影响已有门槛，再跑本任务：

```sh
sbt "testOnly flow.config.BreezeCoreConfigSpec flow.memsys.MemAgentsSpec flow.memsys.L1DCacheSpec flow.memsys.L2HomeSpec flow.memsys.MemSkeletonElabSpec flow.memsys.L1DPermissionsSpec"
sbt "testOnly flow.memsys.L1DL2SystemSpec"
sbt "testOnly flow.memsys.L1DL2MultiCoreSpec"
sbt "testOnly flow.memsys.L1DL2MultiCoreFaultSpec"
```

前三条全部通过后才运行第四条。第四条失败时先跑 directed（`-z "killed LR"` 等），再跑随机。

## 3. 检查强度与漏检面

- 被杀访存不进 oracle：若 RTL 仍写入，Owned 字精确比对、Racy 字"从未写过的值"、Counter 链断裂或总和不符会发现。
- 普通 Store 在分配 MSHR 时即响应（l1d §5.2、§6.2），发出 GetM 后已提交、不能再被杀；"GetM 已发出后被杀"只由 LR/AMO 覆盖。
- AMO/trap 两项用延迟扫描取得精确时序，并以"至少命中一次"作为覆盖断言；没命中时属于刺激不足，可调扫描范围，不能删断言。
- 随机中 SC 被杀后由测试模拟 trap 的 `trapClearRsv`（后端只有 trap 会在 WB 杀 SC）。spec 没有规定"被杀 SC 是否清 reservation"，因此不检查不带 trap 的被杀 SC 后的 reservation。
- 带 page fault 的 SC 是否清 reservation，l1d §8.1 未规定；只在 trap 已清除后检查其 cause。
- PTW 读不在核的程序序内，对已分类字只检查"真实出现过的值、单调不回退"（作为额外读者），不检查 PTW 与本核 CPU 访存的先后。
- refill 错误只注入在 AXI R 的第 2 拍；没有 B 通道写回错误；DMA MaskWrite 不打到错误行（L2 对 MaskWrite 错误只计数、不回报，参照模型无法判断）。
- 随机中 page fault 只来自固定地址区，不与真实 Sv39 walk 相连；PTW 驱动是行为模型，不是真实 MMU。
- MMIO 在多核用例中仍空闲。

## 4. 规则

同 [`MEM-multicore-tests.md`](MEM-multicore-tests.md) §4：先修编译错误（语法与 ChiselSim 用法直接修，不需要报告）；失败先判断是 RTL 错还是测试错，对照 [`l1d-rtl-spec.md`](../l1d-rtl-spec.md)、[`coherence-l2-rtl-spec.md`](../coherence-l2-rtl-spec.md)；RTL 错修 RTL 并同步 spec；测试或模型与 spec 冲突时按 spec 改，并在报告逐条写出依据；不得为了通过而放宽检查、删用例、降规模或延长 watchdog。`L1DCoreIO`、后端、MMU 不改。随机失败记录种子、失败拍号、监视器最近消息，并给出最小 directed 复现（保留为新用例）。

## 5. 报告

写 `docs/tasks/MEM-multicore-fault-tests-report.md`：被测 SHA、四条命令、每个 spec 的通过数/总数与退出码；每个 RTL 修复的根因（一两句）与对应用例；每处测试改动及其 spec 依据；directed 扫描的 `info`（AMO 在等待中/写边沿被杀次数、trap 相对 probe 的分布、X/Y 现场）；随机用例的 `info` 统计（oracle 计数、kill/LR/SC kill、异常、refill 错误、PTW 与 PTW fault、Inv/Down/Put）；仍失败或跳过的用例。
