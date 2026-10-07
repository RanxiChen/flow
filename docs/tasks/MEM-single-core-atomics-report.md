# 单核 LR/SC/AMO RTL 与验证

日期：2026-10-07。代码/测试提交 `2ac677c6ca0a3c2d9bc0f17a7a59a2bd9fd777b4` 已在 Alan 通过模块/结构 101/101、真实单核 L1D + L2 系统 12/12，两个命令均 exit=0。范围为新版 L1D 和单核内存系统；沿用 `l1d-rtl-spec.md` 第 8 节、已有后端请求字段及 B01 停顿方向，不改冻结微架构与接口。

## 实现

- LR.W/D 用 Load 类权限检查，但按 GetM 获得 E/M；miss 不返回 Mshr、不走 late，在内部回放以 resp Done 返回符号扩展数据并建立整行 reservation。
- reservation 记录物理行，80 拍窗口只延迟同行 probe；到期仅结束保护，实际处理 probe、victim 替换、陷入或 SC 尝试才清 reservation。其他访存保留 reservation。待处理的受保护 probe 不关 CPU 入口，SC 可以在窗口内继续执行。
- SC.W/D 先检查权限/PMA，再判定 reservation；成功返回 0 并进 PS，失败返回 1 且不写、不分配 MSHR、不请求 L2。整行 reservation 允许同一行内不同 word 的 SC。
- AMO 的 S0 接受等 CPU/内部老槽、MSHR 和 PS 排空，随后关闭年轻 CPU 入口。命中或 GetM 回放得到独占后，读旧值并经 `BreezeAmoAlu` 计算，交 PS；下一拍实际写入并返回旧值。保护窗口不依赖后端结果接收，也不跨越等待 grant。AMO.W 按地址选择高/低半，mask 只覆盖四字节，返回符号扩展旧值。
- rl 在 S0 等老访存排空，aq 从接受到自身完成关年轻入口；更老普通指令或非 aq LR 的响应不清年轻 acquire 的门控。
- S1/S2 kill 抑制 reservation 与未写入的原子副作用；已经接受的 GetM 仍安装/回放并释放协议资源，失去 CPU owner 的回放不返回、不写。AMO 的 PS 用独立 owner 标记允许实际写边沿的 kill 取消写入，普通已提交 Store 的 PS 语义保持。

例如 `AMOADD.W [0x80001d04],1` 冷 miss：S2 做 Store 类权限检查并分配 GetM，同时保持 CPU S2；等待 grant 时处理允许的 probe；安装后回放读取该 64 位字的高 32 位，经 ALU 产生新高半并进 PS；次拍以 mask `0xf0` 写入、置 M，同时 resp 返回符号扩展旧高半。同行 probe 等回放及 PS 结束后读取脏行；CPU kill 若在此写边沿有效，PS 数据与 E→M 均不写。

新增状态为 reservation 有效位/行地址/7 位 timer、原子 miss owner、acquire 门控、RMW 待写标志/64 位旧值、PS 原子 owner；复用既有 MSHR、PS、内部完成流水与 AMO ALU，没有额外结果队列。S0 的 AMO/rl drain 用请求反压表示，CPU S2 表示 LOOKUP/MISS/WAIT，`ToAmo` 和 `amoRmw` 表示 RMW/DONE，没有重复存储一份请求。

## 需求与覆盖

| ID / 依据 | 检查 | 测试 / 断言 |
| --- | --- | --- |
| A01 §8.1、§5.2 | LR 冷 GetM、S→E 升级、回放 resp、符号扩展 | LR exclusive、LR upgrade；LR completion 必须 hit E/M |
| A02 §8.1 | SC 成功/失败、清 reservation、整行粒度、不 miss | LR exclusive、probe-window、trap/eviction；所有写要求 hit E/M 与 PS 容量 |
| A03 §8.1、§10 | 80 拍内 probe 延迟且 SC 可前进；到期处理清除；timer 单独到期保留 reservation | pending probe、probe timeout、真实 L2 同值 DMA 写后 SC 失败 |
| A04 §8.3 | 九种 .W/.D 运算、两半字节 mask、旧值符号扩展、加法溢出、有符号/无符号比较 | all nine AMOs；独立 BigInt 参照模型与最终整行比较 |
| A05 §8.3、§10.2 | 冷 miss、AckE 升级、升级中 Inv、grant 同拍 probe、RMW 两拍 | AMO cold misses、独立 AMO upgrade Wait 最小回归；`amoRmw` 必须下一拍 PS 完成 |
| A06 §8.2 | aq 禁止年轻进入，老 hit/老非 aq LR 不提前解除；rl/AMO 等旧 Load/Store miss | aq/rl admission，检查 fired/resp/late 拍号 |
| A07 §5.1、§8 | PMA/device/ROM/hole、misalignment/page fault、refill error 的 Load 或 Store/AMO cause；SC 不把异常吞为失败；trap 反馈无组合环 | atomic permissions/refill、L1DPermissionsSpec 的 kill + reservation-clear 反馈、真实 cluster elaboration |
| A08 §3、§8.3 | S1/S2 kill、miss 等待中 kill、AMO 写边沿 kill 无副作用 | atomic kill；逐 Load 与最终黄金内存 |
| A09 §13.2 | probe、REQ/RSPup 反压、TLB 等待、默认/两路/direct-mapped 几何随机混合 AMO/Load/Store | seed 73/74/75，各 300 次；原有普通访存随机用例全部保留 |
| A10 §13.3 | 真 L2、AXI 反压、DMA 读脏原子行/同值写失效、最终一致数据 | 系统 directed 与 seed 72 的 500 次混合 AMO/Load；已有系统 10 项保留 |

SC 的成功/失败预期由定向场景明确指定；AMO 参照模型使用 Scala BigInt 的数学、位运算与有符号解释，不调用 DUT ALU，不根据 DUT 返回值更新黄金结果。最终写回或 DMA 读回继续逐字节与黄金内存比较，没有减少原有深度、规模、watchdog 或断言。

环境假设：L2/AXI 最终应答已接受事务、各接收方最终 ready；行为 L2 的并发 probe/Get 顺序沿用既有协议驱动检查，identity dTLB 只用于地址/时序与注入 fault，不构成真实地址翻译集成证据。A01–A08 的实现位于 `design/src/main/scala/l1d/L1DCache.scala` 的 S0 接受、S2 判定、miss owner、PS 与 probe 仲裁，A04 运算复用 `design/src/main/scala/cache/BreezeAmoAlu.scala`；A09/A10 通过这条相同执行路径。A07 另用真实 Sv39MMU 的权限 harness 与真实 cluster 结构检查，仍不等于全核软件验证。

## Alan 执行

本地仅编辑/提交/push，Alan fetch 精确 SHA 后构建仿真。cwd `/home/chen/FUN/flow/design`，环境 `flow`，sbt `/home/chen/.local/share/coursier/bin/sbt`。运行前后检查 tracked 状态并保留已有证据与无关文件。

首轮 `bb2020e`：三 suite 48 项，46 通过、2 失败，exit=1。两个 AMO 回放用例均触发旧的 `internal result has no reserved completion capacity` 断言：新的 `ToAmo` 是回放结果交给已预留空闲 PS，并非内部槽继续等待。修正为该路径必须有 PS 容量，同时保留其他内部结果不得等待的检查，并新增 AMO 两拍窗口断言。首轮权限、LR/SC、AMO 命中运算和普通访存回归通过。首轮证据根 `/home/chen/FUN/flow-runs/20261007-single-atomics-bb2020e/`；日志 `02-unit.log/.exit`、`unit-reports/` 与 `failed-generated/`。首次环境脚本路径错误发生在 sbt 启动前，不能计为 RTL 编译或仿真失败。

修正及扩展测试版本 `895303ff2e5e4985fda8eb3a38b9c0e28cff3fce`，证据根 `/home/chen/FUN/flow-runs/20261007-single-atomics-895303f/`。

该轮模块结果为 86/100、exit=1：L1D 33/36（3 个等待超时），骨架 26/37（11 个 cluster elaboration 组合环）；驱动 10/10、L2 12/12、权限 5/5。发现两个真实 RTL 问题，均保留失败，系统门槛不运行：

1. **AMO grant 等待仍挡 probe。**定向升级中 Inv 与随机 seed 73/74 触发原 4000 拍 watchdog。定向失败输入在该证据根 `upgrade-replay/` 的生成 SV 副本加入逐拍打印后重放：cycle 219 为 `atomicWait=1, upgrading=1, MSHR=Send`；220 GetM 已接受进入 Wait；221 起同行 `pending=1, owner=0, hold=0`，但 `startOk=0`。CPU S2 保留 AMO 的地址，其 Store 类检查误挡了必须先答的 sharer Inv。修复是在 `startOk` 中允许已经寄存 `atomicWait` 的槽；既有 MSHR、PS、reservation、RMW 的 hold 保留。新增最小回归检查 GetM 接受 < InvAck < DataE < AMO Done，不把 WAIT 场景换成 SEND 场景。
2. **trap-clear 响应组合环。**真实 cluster elaboration 的 firtool 报 `resp.kind/valid/s2Hold → backend trapClearRsv → L1D 判定 → resp`。将 SC 判定与 probe 窗口仅依赖 reservation 寄存状态，trap-clear 只在边沿优先清 reservation；trap 同拍 CPU 副作用继续由原有 `s2Kill` 取消。权限 harness 新增从 fault 响应回接 trapClearRsv，与已有 kill 反馈一起检查；完整 cluster 几何门槛保留。

诊断 replay 的 `replay.exit=0` 仅表示原始输入重放完成，没有 Scala 黄金判据，不能计为功能 PASS。tracked 源码未在 Alan 上诊断修改，打印只在证据副本。两项修复分别位于 `7ecb4f0`、`f2da3e8`，trap 反馈测试位于 `2ac677c`。

最终代码/测试版本 `2ac677c6ca0a3c2d9bc0f17a7a59a2bd9fd777b4`，证据根 `/home/chen/FUN/flow-runs/20261007-single-atomics-2ac677c/`；在无旧 sbt 进程、tracked 工作区干净的精确 checkout 上重新执行下面两道门槛。

```sh
sbt "testOnly flow.memsys.MemAgentsSpec flow.memsys.L1DCacheSpec flow.memsys.L2HomeSpec flow.memsys.MemSkeletonElabSpec flow.memsys.L1DPermissionsSpec"
sbt "testOnly flow.memsys.L1DL2SystemSpec"
```

第二个命令只在模块门槛全部通过后运行。模块门槛通过 101/101、`01-unit.exit=0`；真实单核系统通过 12/12、`02-system.exit=0`。两轮均无 aborted/canceled/ignored/pending。

| 阶段 / spec | 通过 / 总数 | 命令退出码 | 证据 |
| --- | --- | --- | --- |
| MemAgentsSpec | 10 / 10 | 同批 01-unit = 0 | `01-unit.log/.exit`、`01-unit-reports/` |
| L1DCacheSpec | 37 / 37 | 同批 01-unit = 0 | 同上 |
| L2HomeSpec | 12 / 12 | 同批 01-unit = 0 | 同上 |
| MemSkeletonElabSpec | 37 / 37 | 同批 01-unit = 0 | 同上 |
| L1DPermissionsSpec | 5 / 5 | 同批 01-unit = 0 | 同上 |
| L1DL2SystemSpec | 12 / 12 | 02-system = 0 | `02-system.log/.exit`、`02-system-reports/` |

A01–A09 的模块检查均取得上述 SHA 的 PASS，日志可按矩阵测试名称定位，生成 SV 与原始输入位于 `unit-generated/`；A10 的真实系统检查全部 PASS，匹配生成 RTL 与原始输入位于 `system-generated/`。新增 13 项 L1D、2 项系统用例，原有 88 项模块/结构及 10 项系统用例均保留并通过。PMU 新增 `lr_count`（非 killed LR 完成）与 `sc_fail`（非异常、非 killed 的 SC 失败）事件脉冲；完整 PMU 计数/软件可见性不在本轮验证范围。

证据根保留 `run.sh`、`*.command/.log/.exit`、XML 报告、`source-sha*.txt`、`cwd.txt`、`status-*.txt`、Java/Verilator 版本及匹配生成 RTL、仿真输入。归档 XML 仅按各命令实际执行的 suite 统计，sbt 输出目录中的其他既有报告移至 `*-other-reports/` 保留。工具为 sbt 1.9.7、Java 11.0.32.1、Chisel 7.0.0、firtool 1.128.0、Verilator 5.028。

运行前后 Alan 均为精确 `2ac677c`、tracked 工作区干净，运行结束无本轮 sbt 进程。本地 `git diff --check` 通过，`tools/frozen_check.py` 验证 7 份冻结文件无改动；报告后续文档提交不修改生产 RTL 或测试代码。

该轮仅构建、结构检查和模块/内存系统仿真；完整 CPU 指令运行、多 hart 竞争/受约束 LR-SC 前进 litmus、形式化、综合/时序、PPA、FPGA 与 Linux 均未运行。
