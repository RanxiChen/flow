# SOC-2：L2 存储映射 BRAM、HPM 事件打拍，重新上 Vivado（交给 codex 实现、跑和修）

起点：`feat/pcie-fase-20260920` 上包含本文件的提交（`6e0b689` 之后，已合并 `feat/v1-soc`）。在主工作区 `/home/chen/leisure/flow` 的这个分支上工作；`/home/chen/flow-soc` 不再使用。

前情：[`SOC-axi-fpga-bringup-report.md`](SOC-axi-fpga-bringup-report.md)。tiny 生产版与 tiny-debug 都在 `place_design` 前被容量 DRC UTLZ-1 拒绝（约 32 万 LUT、62–64 万 FF，KCU105 为 24.2 万 / 48.5 万）。根因：L2Home 的 `data_2048x256`、`meta_256x192`、`plruArr_256x7` 全部被 Vivado 拆成寄存器（Synth 8-4767），L2Home 合计 290,909 LUT / 579,074 FF / 0 BRAM。L1D 的同类数据阵列已正确映射 BRAM。

本任务由 codex 实现、跑和修。下文「裁定」是冻结的设计决定，不得为通过测试改变；冲突时停止该项并报告。

## 1. 裁定

### D1：通用一读一写 SRAM `SdpSram`

新增 `SdpSram(depth, width, maskGranule)`（位置自定，如 `design/src/main/scala/util/SdpSram.scala`）：

- 一个读口、一个写口、同一时钟。读：本拍给 `ren/raddr`，下一拍 `rdata` 有效（与 `SyncReadMem.read(addr, en)` 时序相同）；`ren=0` 时 `rdata` 保持上次值或任意值均可，调用方不得依赖。
- 写：`wen/waddr/wdata`，可选写掩码；掩码粒度只允许 8 bit（字节）或整字（无掩码）。
- **读写同拍同地址的返回值未定义**。仿真模型中加断言：`ren && wen && raddr === waddr` 时报错（不能在 FPGA 综合版本中留下额外逻辑；用 Chisel `assert`，生成时随 layer/断言开关处理，与现有断言一致）。依据：coherence-l2-rtl-spec §3/§4.3 已保证同一 set 的读写不同拍。
- 实现：用 Chisel `BlackBox` + `HasBlackBoxInline`（或资源文件）输出一个**按 Vivado 文档 BRAM 模板写的 Verilog**：单个 `always @(posedge clk)` 内写（字节写用 for 循环 + `if (we[i])`）、读地址寄存或输出寄存的标准 simple-dual-port 写法，加 `(* ram_style = "block" *)`。同一份 Verilog 给 Verilator 与 Vivado 用，不分两套。
- 不用 `xpm_memory_*`（Verilator 跑不了）。
- 写一个小的 Chisel 单元测试：随机读写对照参考模型（含字节掩码、读使能、连续同地址写后读），以及同拍同地址触发断言的负测试。

### D2：L2 三个阵列换成 `SdpSram`，时序和行为不变

- `data`：深度 `l2Sets*l2Ways`，宽 `lineBits`，字节掩码。读仍在 S1 发、S2 用；写仍在 S2。
- `meta`：**去掉按路掩码**。S2 已经构造了整行 `v`（`s2.meta` 改写一路），直接整行写入，无掩码。初始化清零与正常写**合并为同一个写口**（`Mux` 选地址和数据），不得出现两个写调用。
- `plruArr`：同样把初始化和 touch 合并成一个写口，无掩码。
- 初始化期间不接受读或保证不与写同址（现有 `initDone` 门控）；若 D1 的碰撞断言在初始化阶段触发，按根因修 L2 门控，不改断言。
- 不改 L2 几何、流水级数、在途检查、任何协议行为。所有现有 L2/系统/litmus/fault/集群测试的期望不变。

### D3：其他被拆成寄存器的存储

Vivado 综合日志中，所有出现 Synth 8-4767（RAM dissolved into registers）且大小 ≥ 2 Kbit 的存储都要处理：能换成 `SdpSram` 的换掉（同样要求时序与行为不变），不能换的（例如多读口、组合读）在报告中列出名字、大小、原因和建议，不在本任务中改结构。小于 2 Kbit 的只列出。

### D4：HPM 事件打一拍

综合最差路径（-8.767 ns）从 `l1d/internal2_req_core_size_reg` 组合穿过 L1D 事件到 `backend/csrFile/performance/pending_*`。在 `BreezeCluster` 中把接到 `backend.io.hpmEvents` 的**整个事件束**（含前端 `hpm` 与三个 dcache 事件）经一级寄存器后再接入。事件计数晚 1 拍，架构上允许。不改 `BreezePerformanceCounters` 本体与其单元测试；如有集群级测试对 HPM 计数做精确拍数比较而因此失败，在报告中列出并改为容忍 1 拍，写明理由。

`backend/exFp_dstFmt_reg/CE`（-7.900 ns）等其余核内路径本任务**不修**，等布局布线后的真实时序报告再裁定。

### D5：Vivado 构建的停止规则（替换 SOC 任务书 §4 中对构建的「继续能做的项」）

- 构建在 `place_design` 之前失败（综合错误、容量 DRC、IP 错误等）：**立即停止后续所有构建**，报告根因与证据，等待裁定。不得因为「下一个构建是不同产品」继续跑——同一根因对后续构建同样成立。
- 只有布局布线完成且仅时序不满足（WNS<0）时，才继续下一个构建。
- 每次 Vivado 之前先检查本次 RTL 生成物：L2 三个阵列在生成的 SV 中是 `SdpSram` 实例；Vivado 综合后确认它们落在 RAMB36/RAMB18 上（`report_utilization -hierarchical` 中 L2Home 的 BRAM 数 > 0，且 L2Home LUT 降到合理量级）。综合后不满足就停，不进布局。

## 2. 主机

**用户说明（2026-10-07）：cloud_chen 暂时停机，仿真改用 Alan。** 仍按 AGENTS.md：每次仿真前重新读取 `docs/cross-project/simulation-host.md`，预检 cloud_chen（预期不可用，记录失败原因），然后预检并使用 Alan；Alan 也不可用就停止并报告。若用户期间更新了配置、cloud_chen 恢复，按配置执行。Vivado 在 Alan。使用独立工作区与 evidence 目录（例如 `/home/chen/FUN/flow-soc2-<日期>`、`/home/chen/FUN/flow-runs/soc2-<SHA短>`），不覆盖 `soc-010fa84`。

## 3. 门槛（按顺序；每步记录 SHA、主机、cwd、命令、通过数/总数、exit、日志）

```sh
python3 tools/frozen_check.py                     # OK (8 files)
cd design
sbt "testOnly <SdpSram 单元测试> flow.memsys.L2HomeSpec flow.memsys.L1DL2SystemSpec"   # 直接门槛
sbt "testOnly flow.memsys.L1DL2MultiCoreSpec flow.memsys.L1DL2LitmusSpec flow.memsys.L1DL2MultiCoreFaultSpec flow.cluster.ClusterIsaSpec"
sbt test                                          # 全量；基线为 CLUSTER 第二轮 410/410（62ceea9）与 SOC 分支新增用例
cd .. && python -m pytest sim/litex -q
python -u sim/litex/run_soc_smoke.py --profile single --evidence-dir <evidence>/smoke-single
```

然后 Vivado（Alan，100 MHz），按 D5：

1. `breeze-tiny` 生产版；
2. 布局布线完成后 `breeze-tiny --debug`；
3. 再 `breeze`（small，4 hart）生产版。small 冒烟仿真（约 27 分钟）在第 3 步之前跑。

全量 `sbt test` 若出现失败：先判断是否由本任务改动引入（对比 `6e0b689` 之前的基线），引入的修根因；不是本任务引入的照实报告，不扩大修复范围。

## 4. 规则

同 [`SOC-axi-fpga-bringup.md`](SOC-axi-fpga-bringup.md) §3（冻结文件不改、不改测试期望/断言/看门狗凑通过、修根因、报告每个改动、不做形式化、不跑 OpenSBI/Linux、不烧板），停止条件以本文 D5 为准。不要密集轮询长任务：Vivado 和全量测试用后台运行 + 退出码文件，等完成后再核对。

## 5. 报告

写入 `docs/tasks/SOC-2-bram-report.md`：

- D1–D4 的实现与文件列表，SdpSram 生成的 Verilog 摘录；
- D3 中所有 ≥ 2 Kbit 被拆存储的清单（前后对比）；
- 各门槛结果表；HPM 打拍对测试的影响；
- 每个 Vivado 构建：综合后 L2Home 的 LUT/FF/BRAM，全设计利用率；布局布线后的 WNS/TNS/WHS 与最差 10 条路径（起点、终点、slack、所属模块）；bitstream 与 `.ltx` 的 Alan 绝对路径；
- 未完成项与原因；上板第一步建议（用户执行）。
