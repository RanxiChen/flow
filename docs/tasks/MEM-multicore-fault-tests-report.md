# MEM 多核 fault 测试报告

日期：2026-10-07。起点为 `52cd8f0acb0da58f5957732996f396a830a9cc8f`，按任务书的模块 → 单核系统 → 旧多核 → fault 顺序在 Alan 执行。fault 阶段代码 `b2393c97ec8c1ea1eb21ab45df47ea99b80ff5cd` 上四条命令分别通过 111/111、12/12、21/21、20/20，exit 均为 0，无失败或跳过。首轮随机失败为测试生成器的 LR/SC 回调归属错误；修复后原 18 项及新增两项最小复现全部通过。没有生产 RTL 修复。

## 环境与版本

- 独立 Alan cwd：`/home/chen/FUN/flow-mem-fault-litmus-20261007/design`；保留 Alan 主 checkout 和其他正在运行的任务。
- 证据根：`/home/chen/FUN/flow-runs/20261007-mem-fault-litmus/`。每次运行保存 `source.sha`、`cwd`、`command`、`status-before/after`、`start/end`、`exit`、`run.log`、`reports/`；失败另存生成源码与逐拍模拟输入/日志的归档。
- 环境：`source /home/chen/miniforge3/bin/activate flow`；sbt `/home/chen/.local/share/coursier/bin/sbt` 1.9.7，Java 11.0.32.1，Chisel 7.0.0，Verilator 5.028。
- 仓库依赖保持锁定 SHA：CVFPU `1b220f3bc89df99e246b72e3574a3a533cf87653`；common_cells `6aeee85d0a34fedc06c14f04fd6363c9f7b4eeea`；fpu_div_sqrt_mvp `86e1f558b3c95e91577c41b2fc452c86b04e85ac`；flexfloat `28be2d4fbf41b38fc37763bb6e90a1c88f6aaa61`。
- `70348bd`：仅增加失败诊断及两项最小复现，保留旧生成器。
- `b2393c9`：修复测试程序生成器的 LR/SC 回调归属。未修改生产 RTL、`L1DCoreIO`、后端或 MMU。

起点的生产及测试 Scala 编译均通过。首次模块运行因独立 checkout 未初始化 CVFPU 嵌套依赖而中止 skeleton suite（其他 74 项实际测试通过，exit=1）；GitHub 子模块拉取发生 TLS 断连后，从 Alan 主 checkout 建立相同锁定 SHA 的独立副本，核对全部依赖并完整重跑，111/111、exit=0。原始环境失败保存在 `01-baseline-modules/`，不作为 RTL 或功能测试失败计数。

## 命令与门槛

四条任务命令如下，均在上述 Alan cwd 执行：

```sh
sbt "testOnly flow.config.BreezeCoreConfigSpec flow.memsys.MemAgentsSpec flow.memsys.L1DCacheSpec flow.memsys.L2HomeSpec flow.memsys.MemSkeletonElabSpec flow.memsys.L1DPermissionsSpec"
sbt "testOnly flow.memsys.L1DL2SystemSpec"
sbt "testOnly flow.memsys.L1DL2MultiCoreSpec"
sbt "testOnly flow.memsys.L1DL2MultiCoreFaultSpec"
```

`52cd8f0` 的前三条门槛为 111/111、12/12、21/21，exit 均为 0，证据分别为 `01b-baseline-modules/`、`02-baseline-system/`、`03-baseline-multicore/`。首轮 fault 为 12/18，6 个随机失败、无跳过，exit=1（`04-fault-initial/`）。

以下为 fault 阶段完整门槛，绑定同一代码 SHA `b2393c97ec8c1ea1eb21ab45df47ea99b80ff5cd`，计数来自对应 XML；各命令 elapsed 为 `start/end` 墙钟差（含 sbt 启动，不含归档），完整汇总保存在证据根 `summary.json`。

| 命令 / spec | 通过 / 总数 | exit | 证据子目录 / 命令耗时 |
| --- | --- | --- | --- |
| 第 1 条：BreezeCoreConfigSpec | 8/8 | 0 | `08-feeder-fixed-modules/`，整条 79 s |
| MemAgentsSpec | 10/10 | 0 | 同上 |
| L1DCacheSpec | 39/39 | 0 | 同上 |
| L2HomeSpec | 12/12 | 0 | 同上 |
| MemSkeletonElabSpec | 37/37 | 0 | 同上 |
| L1DPermissionsSpec | 5/5 | 0 | 同上 |
| 第 2 条：L1DL2SystemSpec | 12/12 | 0 | `09-feeder-fixed-system/`，64 s |
| 第 3 条：L1DL2MultiCoreSpec | 21/21 | 0 | `10-feeder-fixed-multicore/`，380 s |
| 第 4 条：L1DL2MultiCoreFaultSpec | 20/20 | 0 | `11-fault-feeder-fixed/`，355 s |

四条 fault 阶段命令合计 878 s（14 分 38 秒）。fault suite 自报测试耗时 5 分 51 秒。报告提交只补文档，不改变上述被测生产 RTL 或测试代码。

## 失败归因与测试修复

六个随机失败均为测试生成器错误。`ProgramFeeder.waitLr` 原来只记录增量，没有记录对应的 LR 请求；前方队列中不参与 LR→SC 自增对的 faulting LR 若先完成，`onDone` 会误用后方已生成自增对的增量，以 fault 地址和异常请求的未赋值返回数据生成 SC。RTL 对这个 SC 返回合法的 page fault；测试却将它当成普通成功/失败 SC，触发 `unexpected exception`。

依据是 [L1D spec §5.1](../l1d-rtl-spec.md)：LR page fault cause=13、SC page fault cause=15，翻译异常优先于缓存访问或 SC 失败状态；§7.1 的 trap 模型还会取消年轻 S1 请求。生成器必须将 SC 与它实际等待的 LR 配对，不能把另一条异常 LR 的响应当成成功 LR 数据。

修复将 `waitLr` 记录为 `(CoreOp, inc)`，`waitSc` 记录为实际生成的 `CoreOp`，完成与 kill 回调只在请求对象身份匹配时消费该对。正常/被杀 LR 的 SC 数据和期望状态、被杀 SC 的下一拍 trap 模型保持原规则。原有 cause、tval、golden memory、Counter 链、监视器、最终 DMA 读回、随机数量与 watchdog 均未放宽。

| 初始随机用例 | 有效 RNG seed | 失败核 / 拍 | 错误生成的 SC 地址 |
| --- | --- | --- | --- |
| 两核默认，91 | 111 | core0 / 4254 | `0x8f000300` |
| 两核默认，92 | 112 | core1 / 4964 | `0x8f0000e0` |
| 四核默认，91 | 131 | core2 / 10566 | `0x8f000000` |
| 两核 stress，91 | 211 | core1 / 2232 | `0x8f0006e0` |
| 两核 stress，92 | 212 | core0 / 60112 | `0x8f000120` |
| 四核 stress，91 | 231 | core3 / 78304 | `0x8f000280` |

最小 directed 复现保留在 `L1DL2MultiCoreFaultSpec`：先入队 faulting LR，再入队 Counter LR（增量 3）。无 trap 时必须只对 Counter 生成一个成功 SC、总增量=3；有 trap 时年轻 Counter LR 在 S1 被杀，只能生成失败 SC、总增量=0；两种模式都禁止向 fault 地址生成额外 SC。

- `70348bd`：`testOnly flow.memsys.L1DL2MultiCoreFaultSpec -- -z "a faulting LR"`，1/2、exit=1，最小复现于第 538 拍失败（`05-feeder-repro-old/`）。
- 同 SHA 复跑 `-- -z "two cores, seed 91"`，0/1、exit=1，同第 4254 拍失败（`06-random-diagnostics-old/`）。最近 32 条消息和请求历史完整见日志；关键历史为 fault LR `#161` 在 4236 拍报 Exc，真正的 Counter LR `#162` 在 4253 拍 Done，而错误 SC `#163` 的地址仍为 `0x8f000300`。
- `b2393c9`：同最小复现命令 2/2、exit=0（`07-feeder-repro-fixed/`）。

新增失败诊断只打印有效 seed、失败拍、监视器最近 32 条消息与每核最近 12 条请求，再原样抛出失败；不改变判据。没有发现需要修复生产 RTL 的失败，因此未修改 RTL spec。

## 定向覆盖

起点原有 12 个 directed 全通过。AMO 的 48 轮扫描观察到等待 GetM 时被杀 18 次、RMW 写边沿被杀 12 次，两个非零覆盖断言均通过。trap 的 28 轮扫描分布为 before=7、same=1、after=20，同拍覆盖断言及答复时限均通过。

TLB 等待的 X/Y 三变体均通过；fault 变体的 info 为 Y fired=1410、X fault response=1712、Y killed=true。PTE 改写定向用例的 48 次 PTW 读观察到 48 个不同整字值，Down/Inv 与单调不回退检查通过。refill error 的重复 miss、清除后命中、Store 丢弃、AMO/LR cause、PTW accessFault、脏 victim 保留，以及 fault 区无 REQ/AXI 读均由原用例检查。

## 最终随机统计

来自 `11-fault-feeder-fixed/run.log`，保持原配置、种子和规模。四核默认仅种子 91，四核 stress 也仅种子 91；没有把它们扩大表述为两个种子。

| 几何 / 标称 seed | 每核操作 | amo | load | lr | scOk / scFail | store | kill 总数 / LR / SC |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 两核默认 / 91 | 1000 | 357 | 1418 | 135 | 132 / 18 | 323 | 163 / 16 / 1 |
| 两核默认 / 92 | 1000 | 344 | 1398 | 131 | 121 / 15 | 357 | 170 / 11 / 6 |
| 四核默认 / 91 | 1000 | 717 | 3494 | 287 | 260 / 45 | 650 | 356 / 33 / 15 |
| 两核 stress / 91 | 2000 | 696 | 3606 | 307 | 250 / 79 | 711 | 363 / 36 / 14 |
| 两核 stress / 92 | 2000 | 730 | 3533 | 249 | 201 / 66 | 702 | 366 / 32 / 14 |
| 四核 stress / 91 | 2000 | 1484 | 10597 | 529 | 398 / 158 | 1327 | 700 / 51 / 24 |

| 几何 / seed | 异常 | refill error 操作 / error grant | PTW 总数 / fault | Inv / Down / Put |
| --- | --- | --- | --- | --- |
| 两核默认 / 91 | 88 | 16 / 89 | 460 / 43 | 388 / 261 / 816 |
| 两核默认 / 92 | 85 | 14 / 85 | 445 / 46 | 380 / 277 / 862 |
| 四核默认 / 91 | 152 | 32 / 217 | 1636 / 155 | 1848 / 878 / 1263 |
| 两核 stress / 91 | 157 | 42 / 296 | 1882 / 218 | 871 / 530 / 3133 |
| 两核 stress / 92 | 149 | 38 / 232 | 1772 / 166 | 847 / 525 / 3055 |
| 四核 stress / 91 | 310 | 89 / 921 | 7419 / 750 | 5580 / 1785 / 6680 |

`load` 含 oracle 判定的 LR 和 PTW 额外读者，不能与 CPU 请求数直接等同；`lr` 为完成的正常 LR，kill LR 另计。refill error 操作数为 `refillError` 标记的完成 Load/Store，error grant 还含 AMO/LR 的异常 refill 与 PTW 错误读，两者不是同一统计口径。每项都完成监视器 idle、error grant 仅落在 poisoned 行、fault 区无 REQ/AXI 读，以及 Owned/Free 精确、Racy/Counter oracle 的全量 DMA 读回检查。

## 与 litmus 修复合并后的最终回归

完成后续 litmus 任务后，在最终代码 `e232d2f9d8cccdff6098ca0a8a159eedbf7e82ee` 上依次完整重跑以下四条门槛，均无失败、跳过或 suite 中止；四条命令合计 1036 s。fault 的定向和六组随机统计与上表相同，原 18 项和新增 2 项均保留，watchdog、oracle、异常检查、种子及规模不变。

| 命令 / spec | 通过 / 总数 | exit | 证据子目录 / 命令耗时 |
| --- | --- | --- | --- |
| 第 1 条：BreezeCoreConfigSpec | 8/8 | 0 | `16-final-modules/`，整条 103 s |
| L1DCacheSpec | 39/39 | 0 | 同上 |
| L1DPermissionsSpec | 5/5 | 0 | 同上 |
| L2HomeSpec | 12/12 | 0 | 同上 |
| MemAgentsSpec | 10/10 | 0 | 同上 |
| MemSkeletonElabSpec | 37/37 | 0 | 同上 |
| 第 2 条：L1DL2SystemSpec | 12/12 | 0 | `17-final-system/`，整条 76 s |
| 第 3 条：L1DL2MultiCoreSpec | 21/21 | 0 | `18-final-multicore/`，整条 468 s |
| 第 4 条：L1DL2MultiCoreFaultSpec | 20/20 | 0 | `19-final-fault/`，整条 389 s |

同 SHA 的完整 litmus 随后为 14/14、exit=0，详见 [litmus 报告](MEM-litmus-tests-report.md)。最终提交只补两份报告，被测生产与测试源码保持一致，`design` Git tree 均为 `b052df3986e179650a5869c587810f5d20e0018a`；证据根 `final-doc-commit`、`final-doc-design-tree` 记录最终 push 后的核对。证据仍在本报告同一 evidence root，最终结果不依赖旧阶段的通过计数。

## 验证边界

范围为真实多核 L1D + L2 在 `L1DCoreIO` 边界的仿真；PTW、dTLB、AXI 下游及后端 trap 为行为驱动。未验证真实后端、Sv39 walk、MMIO 多核交互、AXI B 写回错误、综合时序、PPA、FPGA 或 Linux 运行。注入 AXI R 错误仍限第 2 拍，错误行保持独立于 oracle 的正常读回集合。
