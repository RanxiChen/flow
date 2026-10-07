# MEM 多核测试：RTL 修复与回归证据

日期：2026-10-07。按 [`MEM-multicore-tests.md`](MEM-multicore-tests.md) 的模块 → 单核系统 → 多核顺序在 Alan 执行。首轮多核 15/21，修复 FENCE/probe 等待环并补强默认两核随机的 L2 驱逐刺激。最终代码 `8fcf4869caff358706b024d40aa6962a43c61265` 上，模块/配置 111/111、真实单核系统 12/12、多核 21/21，三条命令均 exit=0，无失败或跳过。本文保留各版本失败和修复证据，不混合不同 SHA 的结果。

## 版本、环境与证据

- 初始提交：`fa690039793dbac9816a19848821a72738cde027`，首轮测试编译失败，exit=1，未进入测试。
- 编译修复/首轮门槛：`b1a4695e49f124de7a04efb5343e86c56460865a`。
- 旧 RTL 最小复现：`a00c6be195ad971ca8800fd581ececf2eb4d4344`，仅新增两个 L1D 定向用例。
- RTL/spec 修复：`caea2f8`；默认两核随机冲突地址补强：`8fcf486`。均本地提交/push，Alan 从 GitHub fetch 后 checkout 对应 SHA。
- Alan cwd：`/home/chen/FUN/flow/design`；激活 `/home/chen/miniforge3` 的 `flow` 环境，执行 `/home/chen/.local/share/coursier/bin/sbt`。
- 工具：sbt 1.9.7、Java 11.0.32.1、Chisel 7.0.0、Verilator 5.028；生成 SV 文件头为 CIRCT firtool 1.128.0。
- 初始编译证据根：`/home/chen/FUN/flow-runs/20261007-mem-multicore-fa69003/`。
- 首轮证据根：`/home/chen/FUN/flow-runs/20261007-mem-multicore-b1a4695/`。
- 最小复现证据根：`/home/chen/FUN/flow-runs/20261007-mem-fence-repro-a00c6be/`。
- 最终证据根：`/home/chen/FUN/flow-runs/20261007-mem-multicore-8fcf486/`。

每条命令的 `*.sha`、`*.cwd`、`*.command`、`*.status-before`、`*.status-after`、`*.log`、`*.exit` 与 `*-reports/` 保存源版本、工作目录、实际命令、工作区状态、退出码与 XML 报告。运行前检查 Alan 工作区及已有任务，保留无关后台服务。首轮与最终多核的生成 RTL、仿真日志和原始逐拍输入分别保存于对应根的 `03-multicore-primary.tar.gz`；失败日志保留全部最近 32 条一致性消息。最终 `summary.json` 从对应 XML 逐项提取通过/失败/跳过数，并记录各命令 SHA/exit；`final-identity.txt` 保存结束时 SHA、工作区及工具身份。报告后的文档提交不改生产 RTL 或测试代码。

## 三条门槛命令

```sh
sbt "testOnly flow.config.BreezeCoreConfigSpec flow.memsys.MemAgentsSpec flow.memsys.L1DCacheSpec flow.memsys.L2HomeSpec flow.memsys.MemSkeletonElabSpec flow.memsys.L1DPermissionsSpec"
sbt "testOnly flow.memsys.L1DL2SystemSpec"
sbt "testOnly flow.memsys.L1DL2MultiCoreSpec"
```

最终执行脚本 `breeze-mem-final.sh` 在任一阶段非零时退出，不继续后续门槛。完整门槛前另跑：

```sh
sbt 'testOnly flow.memsys.L1DCacheSpec -- -z FENCE'
```

## 实际结果

同一个组合命令内的 spec 共用命令退出码，不将通过的 spec 伪报为独立命令 exit=0。

| 阶段 / spec | 通过 / 总数 | 命令退出码 | 证据 |
| --- | --- | --- | --- |
| 首轮 BreezeCoreConfigSpec | 8/8 | 01-unit=0 | b1a4695 根的 `01-unit.*`、`01-unit-reports/` |
| 首轮 MemAgentsSpec | 10/10 | 同上 | 同上 |
| 首轮 L1DCacheSpec | 37/37 | 同上 | 同上 |
| 首轮 L2HomeSpec | 12/12 | 同上 | 同上 |
| 首轮 MemSkeletonElabSpec | 37/37 | 同上 | 同上 |
| 首轮 L1DPermissionsSpec | 5/5 | 同上 | 同上 |
| 首轮 L1DL2SystemSpec | 12/12 | 02-system=0 | b1a4695 根的 `02-system.*`、`02-system-reports/` |
| 首轮 L1DL2MultiCoreSpec | 15/21 | 03-multicore=1 | b1a4695 根的 `03-multicore.*`、`03-multicore-reports/` |
| 旧 RTL FENCE 复现 | 1/3 | 01-repro=1 | a00c6be 根的 `01-repro.*`、`01-repro-reports/` |
| 最终 FENCE 定向 | 3/3 | 00-fence=0 | 8fcf486 根的 `00-fence.*`、`00-fence-reports/` |
| 最终 BreezeCoreConfigSpec | 8/8 | 01-unit=0 | 8fcf486 根的 `01-unit.*`、`01-unit-reports/` |
| 最终 MemAgentsSpec | 10/10 | 同上 | 同上 |
| 最终 L1DCacheSpec | 39/39 | 同上 | 同上 |
| 最终 L2HomeSpec | 12/12 | 同上 | 同上 |
| 最终 MemSkeletonElabSpec | 37/37 | 同上 | 同上 |
| 最终 L1DPermissionsSpec | 5/5 | 同上 | 同上 |
| 最终 L1DL2SystemSpec | 12/12 | 02-system=0 | 8fcf486 根的 `02-system.*`、`02-system-reports/` |
| 最终 L1DL2MultiCoreSpec | 21/21 | 03-multicore=0 | 8fcf486 根的 `03-multicore.*`、`03-multicore-reports/` |

旧 RTL FENCE 复现的 1 个通过项是原有 FENCE drain 测试，新增 Inv/Down 两项均失败；修复后 3/3。新增用例属于最终 L1DCacheSpec，不重复计作额外覆盖项。最终模块/配置合计 111/111，系统 12/12，多核 21/21；各批均 0 aborted/canceled/ignored/pending。

## RTL 根因、修复与最小复现

### R1：FENCE 后的年轻 Store 阻止旧事务所需 probe

首轮 MP+FENCE（seed=11）在 cycle 6647 报连续 4000 拍无进展。round 18 的核 0 先向 `0x80010480` 写 miss，FENCE 在 S2 等待该 MSHR drain，年轻 Store(`0x800144a0`) 保持在 S1。核 1 GetS(`0x800144a0`) 使 L2 在 cycle 2648 向核 0 发 Down，同拍核 0 的旧 GetM(`0x80010480`) 被接受。

原 `probe.startOk` 的 `cpuAlreadyWaiting` 包含重查、原子等待和内部流水占用，漏掉 FENCE。S1 的年轻 Store 虽被 FENCE 保持、没有写入机会，仍阻止同行/同 set probe。Down 占用 L2 的单 probe 引擎；旧 GetM 所需 Inv 因此不能发出，形成旧 MSHR → L2 probe 引擎 → Down → 年轻 Store → FENCE → 旧 MSHR 的等待环。这违反 coherence/L2 §1.4 的 SNP 本地有限延迟依赖与 L1D §10.1 的保持请求处理约定。

`caea2f8` 将 `cpu2.valid && op==Fence` 加入年轻 S1 Store 可安全保持的判断，只放行 probe 的开始条件。第 10.2 节的同行 MSHR、写回槽、PS、reservation 和 AMO RMW 压住条件不变；没有增加流水级、队列或接口。probe 修改 tag 后仍按既有机制使 CPU 快照失效，年轻 Store 在 FENCE 后推进并重查。L1D spec §10.1 同步记录该实现规则。

最小 directed 用例：先写脏 `0x80001240=0x1234`；阻止旧 `Store(0x80003280,0x5678)` 的 GetM 被接受，紧接 FENCE、年轻 `Store(0x80001240,0x9abc)`；再向旧脏行分别发 Inv/Down。必须在旧 GetM 仍未接受、FENCE/年轻 Store 均未响应时完成带脏数据的 Ack，且数据仍为 `0x1234`。释放 GetM 后检查 FENCE/年轻 Store 响应顺序、两个地址的 load 和最终整行黄金内存。`a00c6be` 两项均在 cycle 4155 触发原 watchdog，未通过复现不存在延长超时或降低规模。

首轮另外三个随机停顿也出现 FENCE 后持有年轻 Store 的同类现场，原始 seed、消息历史与输入均保留：

| 用例 | 展示 seed / 实际 RNG seed | 失败拍 | FENCE / 年轻 Store 现场 |
| --- | --- | --- | --- |
| 四核默认 | 82 / 122 | 11804 | 核 1：FENCE fired 7748，Store(`0x8000a008`) fired 7764；旧 Load(`0x8000a010`) 等 late |
| 四核 stress | 81 / 221 | 4400 | 核 2：FENCE fired 327，Store(`0x8000203c`) fired 328 |
| 四核 stress | 82 / 222 | 62930 | 核 3：FENCE fired 58909，Store(`0x80002028`) fired 58910；旧 Load(`0x80002008`) 等 late |

这三组随机生成器的几何、地址集合、操作次数、RNG seed、oracle 和检查不改；最终同 seed 全部通过。MP+FENCE 也完成全部 64 轮，新增两个定向复现通过，原 watchdog 保持 4000 拍。

## 测试改动及依据

- C1：多核 `Env.run` 与 ScalaTest `AnyFreeSpecLike.run` 同名产生 3 条编译错误，改为 `runOps`。没有改变刺激与检查。
- 新增两个 R1 定向回归，依据 L1D §10.1–10.2、§11 和 coherence/L2 §1.4；核实旧值、年轻 Store 无副作用和真实 drain 后的顺序。
- C2：首轮两核默认 seed 81/82 已完成 oracle、监视器、最终 DMA 数据检查，却在 `mem.writes must not be empty` 失败。地址按核奇偶分布到两个 set，每个 set 仅 5 条私有行 + 2 条共享行，小于 8 路 L2；因此既有刺激不能触达任务书随机压力要求的 AXI 写回覆盖。把无法溢出 L2 的组增加到每核 9 条私有冲突行；已溢出的四核默认、两核/四核 stress 地址集合原样保留。默认每核 1000、stress 每核 2000 次操作及全部检查不变。这是补强任务书 §1 的驱逐/写回覆盖刺激，没有删除写回断言或改期望值。
- `L1DCoreIO`、后端、MMU、oracle、监视器、黄金值、RTL 断言、随机操作规模和 watchdog 均未修改。

## 随机统计与 litmus

最终 `03-multicore.log` 的八组随机 `info` 统计如下。Load 包含 LR 的读取；表中未出现的 `scFail` 计为 0。每组均完成 oracle、监视器、全部使用行的最终 DMA 检查及非空 AXI 写回覆盖断言。

| 配置 | 展示 / RNG seed | 每核生成操作数 | AMO | Load | LR | SC 成功 / 失败 | Store | Inv | Down | Put |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 两核默认 | 81 / 101 | 1000 | 413 | 1163 | 164 | 164 / 0 | 375 | 362 | 191 | 676 |
| 两核默认 | 82 / 102 | 1000 | 378 | 1208 | 155 | 155 / 0 | 367 | 339 | 196 | 764 |
| 四核默认 | 81 / 121 | 1000 | 837 | 2248 | 308 | 306 / 2 | 809 | 1546 | 529 | 755 |
| 四核默认 | 82 / 122 | 1000 | 787 | 2325 | 342 | 338 / 4 | 775 | 1490 | 496 | 748 |
| 两核 stress | 81 / 201 | 2000 | 830 | 2254 | 314 | 313 / 1 | 794 | 802 | 294 | 2171 |
| 两核 stress | 82 / 202 | 2000 | 819 | 2258 | 315 | 313 / 2 | 807 | 777 | 310 | 2212 |
| 四核 stress | 81 / 221 | 2000 | 1579 | 4569 | 666 | 657 / 9 | 1589 | 3185 | 705 | 3360 |
| 四核 stress | 82 / 222 | 2000 | 1572 | 4565 | 631 | 628 / 3 | 1630 | 3214 | 694 | 3408 |

SC 由 feeder 在 LR 完成后额外生成，不挤占上述生成操作次数；统计包括这些 SC。首轮两核默认覆盖断言失败前的统计分别为：seed 81，AMO 424、Load 1163、LR 182、SC 成功 179/失败 3、Store 366、Inv 435、Down 234、Put 429；seed 82，AMO 397、Load 1134、LR 143、SC 成功 142/失败 1、Store 410、Inv 430、Down 230、Put 441。AXI 写回均未发生，不能将这两项记作完整通过。

首轮 MP 无 fence `(flag,data)` 分布：00=23、01=27、10=13、11=1；SB+fence 分布：10=17、11=47。MP+fence 因 watchdog 未完成 64 轮，不报告完整分布。

最终每个 litmus 64 轮：MP+fence `(flag,data)` 为 00=20、01=44；MP 无 fence 为 00=23、01=27、10=13、11=1；SB+fence 为 10=17、11=47。MP+fence 本轮没有 flag=新的结果，故该形状的禁止结果检查没有触达 flag=新前提；这轮明确取得的是完整执行与无死锁，不能据此声称已充分探索 MP+fence 的所有交错或完整 RVWMO 允许集。

## 范围与未完成项

本任务所有用例均已执行通过，没有仍失败或跳过的用例。任务书 §3 所列 oracle/litmus 漏检面仍有效；模块/缓存子系统仿真不构成全 CPU 集成、完整 RVWMO 允许集证明、综合/时序、FPGA 或软件运行证据。多核 PTW/MMIO、kill/page fault/refill error 交互仍未覆盖；这些端口/条件仅保留已有单核门槛。
