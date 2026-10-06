# MEM 单核测试：首轮执行与失败归因

日期：2026-10-06。按用户“先跑一遍、判断语义/RTL/测试错误”的范围，完成编译修复、完整单测首轮和一次冷 load 波形复现。功能失败仍保留；本轮未修改 RTL、规格、测试期望、断言、随机规模或 watchdog。端到端按任务书的前置门槛未运行。

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

## 尚未推进到的测试语义与覆盖问题

以下是源码检查发现的测试模型问题，不冒充已经触达并归因的功能失败，也未据此改期望：

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
