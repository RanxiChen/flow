# MEM 单核测试：执行、测试修复与失败归因

日期：2026-10-06。首轮按用户“先跑一遍、判断语义/RTL/测试错误”完成编译修复、完整单测和冷 load 波形复现。第二轮按“先修测试”修复驱动与最终检查，详见末节。两轮均未修改生产 RTL、规格、黄金期望、RTL 断言、随机规模或 watchdog；端到端前置门槛仍未满足。

## 版本、环境与命令

- RTL 基线：`0e9e109c928c87f6618345c6e521fc16a8d5a56f`。
- 初始测试提交：`d50b3dd34de2ac8fe36bfa7c431418910c204343`。
- 编译修复及被测提交：`aae9158f19622556d18c3c0776127397d7b294f9`。本地提交/push，Alan 从 GitHub fetch 后 checkout 同一 SHA；运行前后 tracked 工作区干净。
- Alan cwd：`/home/chen/FUN/flow/design`；激活 `flow` 环境，使用 `/home/chen/.local/share/coursier/bin/sbt`。
- 工具：sbt 1.9.7、Java 11.0.32.1、Chisel 7.0.0、CIRCT firtool 1.128.0（生成 SV 的文件头）、Verilator 5.028。
- Alan 证据根：`/home/chen/FUN/flow-runs/20261006-mem-single-d50b3dd/`。运行前检查已有任务，没有占用本 checkout 的 sbt/Verilator 仿真任务。

U1，两个单测加已有门槛：

```sh
sbt "testOnly flow.memsys.L1DCacheSpec flow.memsys.L2HomeSpec flow.memsys.MemSkeletonElabSpec flow.memsys.L1DPermissionsSpec"
```

R1，同 SHA 的最小冷 load 复现，开启波形：

```sh
sbt 'testOnly flow.memsys.L1DCacheSpec -- -z "load miss refills" -DemitVcd=true'
```

## 实际结果

| 阶段 / spec | 通过 / 总数 | 命令退出码 | 证据 |
| --- | --- | --- | --- |
| 初始 U1，d50b3dd | 未进入测试，65 条编译错误 | 1 | `01-unit.log`、`01-unit.exit` |
| U1：L1DCacheSpec，aae9158 | 1 / 22 | 同批 U1 为 1 | `02-unit-aae9158.log`、`unit-test-reports/` |
| U1：L2HomeSpec，aae9158 | 11 / 12 | 同批 U1 为 1 | 同上 |
| U1：MemSkeletonElabSpec，aae9158 | 37 / 37 | 同批 U1 为 1 | 同上 |
| U1：L1DPermissionsSpec，aae9158 | 5 / 5 | 同批 U1 为 1 | 同上 |
| R1：冷 load 波形复现，aae9158 | 0 / 1 | 1 | `03-cold-vcd-aae9158.log`、`03-cold-vcd-aae9158.exit`、`03-cold.vcd` |
| L1DL2SystemSpec | 未运行 | 无 | L1D/L2 尚未都通过，按任务书 §3.5 保留前置门槛 |

U1 总计 76 个测试，54 通过、22 失败，0 aborted/canceled/ignored/pending。各 spec 未单独发命令，因此表中的 U1 退出码是组合命令的退出码，不把通过的 spec 误报为独立命令 exit=0。

## 失败分类与证据

### C1：测试代码编译错误，已修

`L1DCacheSpec.Env.run`、`L1DL2SystemSpec.Env.run` 经 `import e._` 引入后，与 ScalaTest `AnyFreeSpecLike.run` 冲突。65 条错误来自同一命名冲突。辅助方法及调用统一改为 `runOps`；没有改变输入、输出检查或期望值。第二轮全部生产及测试 Scala 编译成功。

### R1：L1D 掩码写入的生成语义错误，已确认，尚未修

最小场景只有初始化后一次 `Load(0x80000100, size=3)`：cycle 128 接受，130 返回 Mshr，141 返回 late。黄金值 `0x80ff7f0181fe0280`，实得 `0x228e5dd690426965`。

波形与生成 SV 相互印证：

1. 行数据 grant 正确；refill beat 0 向 way 0、data 地址 `0x20` 写入黄金值。四个 beat 的地址、数据均正确。
2. Scala `L1DCache.scala:463` 试图用 `UIntToOH(tagWay, ways)` 只更新一个 way；但生成 `L1DCache.sv` 的 `tags_ext.W0_data` 重复连接四份相同 tag/state，`tags_128x88.sv` 的对应写端口没有 mask，直接整项覆写。
3. replay 时 `internal2_tagVec_0..3` 全部为 E、tag=`0x80000`，`hitVec=1111`，`hitWay=3`。数据只填过 way 0，返回的是 way 3 的未初始化数据。
4. 首次 load 的 Scala 对比在下一次 `clock.step()` 前报错，所以这个用例先报数据错误；store-miss 等用例继续走到边沿，会触发 `two ways hit the same tag`。不能把这两个现象算作两个已经独立定位的机制故障。

这违反 L1D spec §2 按 way 掩码写、§6.2 安装到选定 way、§13.1 同 set 同 tag 只能有一路有效。黄金值来源独立，且 grant/data 写入正确，因此不是为了匹配 DUT 而修改期望的问题。

此外，生成 `data_512x64.sv` 的 pending-store 写端口也没有字节 mask，整 64 位写入。源码 `L1DCache.scala:470` 传入了 `ps.mask.asBools`，但生成结果未保持该语义；修复时必须同时检查字节保留，不能只解决 tag 重复。Chisel 7.0.0 官方 [Mem.scala](https://github.com/chipsalliance/chisel/blob/v7.0.0/core/src/main/scala/chisel3/Mem.scala#L110-L152) 的 masked-write API 按 mask 条件连接各元素。当前证据确认了源代码意图与生成硬件的差异，尚未用独立小模块判定具体是哪一步 Chisel/CIRCT lowering 造成，不能直接断言是某个工具版本的通用 bug。

证据：`cold-baseline/primary-sources/` 保留初轮生成代码；`cold-vcd-primary-sources/` 和 `03-cold.vcd` 为同 SHA 的复现。该冷 load 不涉及升级、kill、probe 或 late 反压；能先于这些复杂场景复现。

### R2：L2 单 set 地址拼接的 RTL 位宽错误，已确认，尚未修

仅 `L2HomeSpec` 的单 set、两路 stress 失败，seed=41、cycle=13。请求行的字节地址为 `0x80002020`，期望为该行的完整黄金值，DataE 却返回另一个行的背景值。

`CoherenceParams.setBits` 为寄存器占位做了 `max 1`，但 `tagBits/tagOf` 按真实 `log2Ceil(l2Sets)=0` 推导；`L2Slots.scala:187` 仍把占位 set 位拼接到 tag 后。于是请求行地址 `0x4000101` 被左移一位，再在 27 位行地址口截断，实际 AXI 字节地址变成 `0x00004040`。独立按 GoldenMem 算出的 `0x4040` 行内容与失败实际值逐字节完全一致。

同类拼接还出现在 `L2Slots.scala:161` 的 probe 地址和 `L2Home.scala:449` 的 victim 写回地址。修复必须覆盖三处，区分“字段存储至少 1 位”与“真实地址中有 0 个 set 位”，不能改 stress 几何规避问题。这违反 coherence/L2 spec §0.2、§5.2、§7.1/7.2 的物理行地址规则。

L2 其余 8 个 directed、seed 31/32 随机、四路 smoke 均通过。本轮没有证据把这个位宽问题扩大为默认 L2 的数据一致性失败。

证据：`l2-stress-primary-sources/`、U1 日志及保存的 XML 报告。

## 首轮静态发现的测试语义与覆盖问题

以下是首轮源码检查发现的测试模型问题，不冒充已经触达并归因的功能失败，也未据此改期望。前两项已在第二轮修复；升级 WAIT 场景仍被早期 RTL 错误挡住：

- `BehavioralL2.probeAfterGrant` 在 `sample()` 消费 grant 后才 enqueue probe，实际 SNP 最早下一次 `drive()` 才 valid。名为“grant 同拍 probe”的用例当前制造的是下一拍 probe；需要按 L1D spec §10.2 的同边沿条件加强驱动。
- `CoreDriver.idle` 不含 `core.drained`。Store Mshr 在 resp 后被标为 done；行为 L2 在发送 grant 后可能已 idle，而真实 DUT 仍在 install/replay/PS。`Bench.quiesce()` 只额外走固定 4 拍，不能作为 DUT store-miss 已 drain 的保证。后续结束写回/读回前应按 L1D spec §6.2、§11 等待真实 drain；不能靠扩大固定延时掩盖问题。
- 升级中 Inv 的 directed 测试确实在 GetM 被模型接受后发送 Inv，符合用户指出的 WAIT 前提。U1 在其最初填充 load 就失败，尚未触达升级 race，不能据本轮宣称 SEND/WAIT 争议已被验证或已失败。

## 未通过项与随机失败记录

L1D 唯一通过的是 blocking MMIO 用例。其余 21 项全部失败：15 项先在数据比对失败，6 项先触发 `two ways hit the same tag`（store-miss、FENCE、seed 11/12/13、两路 smoke）。不少用例在初始冷 load 即失败，因此对应后续机制覆盖尚未取得。

| 随机用例 | 首次失败记录 |
| --- | --- |
| L1D seed 11 | RTL assert；仿真日志时间 1520 |
| L1D seed 12 | RTL assert；仿真日志时间 1540 |
| L1D seed 13 | RTL assert；仿真日志时间 1520 |
| L1D two-way seed 21 | RTL assert；仿真日志时间 1550 |
| L1D stress seed 22 | cycle 128，#5 Load(0x80001028, size=2)，实得 `0x6981b796`，期望 `0xfd6e2696` |
| L2 stress seed 41 | cycle 13，DataE 对错行，最小地址原因见 R2 |

随机 RTL assert 的时间是模拟器日志时间单位，不伪称为 Bench 拍号。R1 已缩小到一个无随机反压的冷 load；seed 22 的后续数据错误尚未单独缩小，不能假设解决 tag mask 后全部 21 项都会通过。没有删除、跳过或降低任何失败检查。

下一轮的直接入口是先修 R1 的掩码写入并复测冷 load/字节 store，再修 R2 的三处行地址重建，然后重跑完整 L1D/L2 门槛；两者都通过后才运行系统 spec。本报告仅是首轮模块仿真与归因，不是单核功能闭环、全核/多核、时序、PPA、FPGA 或软件运行证明。

## 第二轮：只修测试

用户范围为“先修测试，把测试修好”。最终测试代码提交为 `c7a3c4b97b292d8d07817da5a93f56ed5763b6fa`，包含 `142ed2d` 的驱动/收尾修复及 `a7f5a03` 的 probe/store 回归。生产源码相对首轮 `aae9158` 无改动。

### 改动、规格依据及验证范围

| 修复 | 依据 | 独立验证 / 真实 DUT 验证 |
| --- | --- | --- |
| 行为 L2 在 `drive` 中同时呈现 grant 与预定 SNP，记录两者握手拍；真实 L1D directed 用例新增拍号相等检查 | L1D §10.1–10.2，同边沿接受 probe 后必须等 grant 安装/回放完成 | wire-only peer 分别验证 Inv/Down 同拍 valid、SNP 反压时保持字段，以及 ready=1 时两条链路同边沿握手；真实 L1D 用例仍可能在 late 黄金比较提前失败，不能报其后续机制已通过 |
| `CoreDriver.idle` 增加 `drained && !mmioBusy`，不再只看 Scala 请求队列 | L1D §1.1、§6.2、§9、§11；store Mshr 的响应早于安装/PS 完成 | 独立 peer 在 store Mshr 响应后延迟 18 拍才 drained，确认 `quiesce` 等到真实信号；另验证 MMIO busy 会阻止 idle；未增加固定延时或 watchdog |
| AXI 内存的 R/B 已拉高 valid 后持续保持到 ready 握手；随机气泡只决定何时首次呈现下一 beat/响应 | coherence/L2 §7 的 AXI4 内存接口及 valid/ready 保持约束；§9 实际 L2 的 R/B ready 恒为 1 | 独立 peer 拉低 R/B ready，首次 valid 后把新 valid 概率设为 0，确认当前 valid、ID、data、resp、last 不撤回；握手后恢复随机气泡并完成四 beat |
| REQ 稳定性检查覆盖 op、addr、id、mask、data 全部字段 | coherence/L2 §1.3、§8、§10 | 独立负向用例分别篡改 stalled mask/data，必须触发原有协议检查；没有放宽字段要求 |
| Scala L1D 在同行 probe 待处理或 Ack 未握手期间不启动新的本地 store/acquire/evict | L1D §10.1 的 probe 行数据稳定要求及 coherence/L2 §1.3 的 Ack 保持要求 | 独立 peer 以 DownAck 反压阻止同行 store，检查原脏数据保持，Ack 后才 GetM/AckE 和写入；L2 随机种子验证综合交互 |
| 三个 spec 的公共 wrapper 在成功完成 body 后必调 `finish`；L2 增加所有 `arch.touched` 行经 DMA 读回的精确比较 | 原任务书 §1 的结束检查契约；L1D §10.3，coherence/L2 §5.2 的 Read/Down 与脏数据合并 | L1D Inv 全部模型持有行后比较 arch/backing 修改行的全部字节；L2/系统 DMA 检查全部写过的行。body 已失败时保留第一现场，不用清理错误掩盖它；系统仅编译，未运行 |

没有修改任何黄金值或放宽测试期望。原有显式 `finish` 仍保留，便于在测试 body 内检查 probe 结果；公共收尾保证遗漏显式调用的成功用例也会检查。

中间版本 `142ed2d` 的额外四路 L2 超时也作为测试错误保留：seed=42、cycle=7951，GetM 尚未被接受，Scala L1D 却把同行 sharer Inv 等待条件改成“必须 getAccepted”，导致 REQ 等 SNP、SNP 等 REQ。按 coherence/L2 §1.4，SNP 不能依赖尚未接受的 REQ；最终 `c7a3c4b` 恢复 Scala 代理原有 sharer Inv 前进规则。没有改真实 L1D SEND/WAIT 语义，也没有把用户指定的 WAIT directed 场景改成 SEND 场景。

证据根：Alan `/home/chen/FUN/flow-runs/20261006-mem-tests-fix-142ed2d/`；cwd `/home/chen/FUN/flow/design`，同首轮工具环境。`01-agents.log/.exit` 为中间版 8/8 独立驱动回归，exit=0；`02-unit.log/.exit` 为中间版 53/76、23 fail，exit=1，包含上述模型超时。二者均绑定 `142ed2ded48db0e98e790b89201571d0d585fdd2`，不混入最终结果。

最终命令（同已推送的 `c7a3c4b`）：

```sh
sbt "testOnly flow.memsys.MemAgentsSpec flow.memsys.L1DCacheSpec flow.memsys.L2HomeSpec flow.memsys.MemSkeletonElabSpec flow.memsys.L1DPermissionsSpec"
```

最终 F1 结果：

| spec | 通过 / 总数 | 命令退出码 | 证据 |
| --- | --- | --- | --- |
| MemAgentsSpec | 9 / 9 | 同批 F1 = 1 | `03-final-c7a3c4b.log`、`final-test-reports/` |
| L1DCacheSpec | 1 / 22 | 同批 F1 = 1 | 同上 |
| L2HomeSpec | 11 / 12 | 同批 F1 = 1 | 同上 |
| MemSkeletonElabSpec | 37 / 37 | 同批 F1 = 1 | 同上 |
| L1DPermissionsSpec | 5 / 5 | 同批 F1 = 1 | 同上 |
| L1DL2SystemSpec | 未运行（源码已编译） | 无 | 仍遵守 §3.5，L1D/L2 尚未全部通过 |

总计 85 个测试，63 通过、22 失败，0 aborted/canceled/ignored/pending；组合命令退出码 1，保存在 `03-final-c7a3c4b.exit`。每个 spec 的数字来自同一 F1，不能把通过的 spec 记成单独命令 exit=0。运行前后 Alan 均为精确 `c7a3c4b`、tracked 工作区干净；运行后无本轮 sbt/仿真进程残留。GitHub 直接 HTTPS 连接故障后使用仅本次 SSH 会话的 SOCKS 转发完成 fetch，未更改仓库代理配置。

四路 L2 seed=42 的中间模型死锁已消失；默认 seed=31/32、四路 smoke 及新增最终 DMA 收尾均通过。L2 仍仅 stress seed=41 在 cycle=13 返回错行，与首轮 R2 相同。L1D 仍仅 MMIO 通过；冷 load 的 cycle=141 实际/期望值、stress seed=22 的 cycle=128 错误及 seed=11/12/13、两路 seed=21 的 tag 断言均与首轮一致，21 个失败用例保留。真实 grant/probe 同拍、升级 WAIT 等后续场景仍会被早期 RTL 故障挡住，独立驱动回归通过不等于这些真实缓存机制已通过。

本轮完成的是测试设施修复与 Alan 模块复测。已知 RTL R1/R2 留待下一步，不能把当前 22 项失败说成已通过，也不能据此证明真实单核端到端功能。
