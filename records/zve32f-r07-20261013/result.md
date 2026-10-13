# Zve32f R07b 验收记录（2026-10-13）

工作树：`/home/chen/leisure/flow/.worktrees/zve32f-single`，分支 `feat/zve32f-single-20261013`。仿真和 RTL 生成前重读 `docs/cross-project/simulation-host.md`；首选 `cloud_chen@47.96.71.231:22` 在 8 秒内连接超时，备用 Alan `ssh -J clawbot -p 2286 chen@localhost` 预检通过。SBT、RTL 生成、测试与 Vivado 实际都在 Alan 运行，隔离目录为 `/home/chen/FUN/flow-runs/zve32f-r07-20261013`；Vivado 输出位于其 `kcu105-r07b-01` 子目录。Java 11.0.32.1、SBT 1.9.7、Vivado 2022.2，目标 `xcku040-ffva1156-2-e`，系统时钟 100 MHz。

## 功能和回归

| 检查 | 命令或内容 | 结果与证据 |
|---|---|---|
| T0′ | `sbt compile`；`runMain flow.top.GenerateBreezeCluster` 依次生成 `single`、`single-rvv`、`single-zve32f`；E1 比较 | 均 exit 0；`single` 85 文件、`single-rvv` 118 文件逐文件一致，仅归一化绝对源路径和 Scala 断言行号。[编译](compile-r07b-final.log)、[生成](rtl-gen-r07b-final.log)、[E1](e1-r07b-final.json) |
| T3′、T10、T11、Zve 回归 | `testOnly flow.rvv.zve.*` | 15 suites、20 tests 全通过。[日志](zve-regression-r07b-final-pass.log) |
| Age matrix / T4 | `ZveWindowSpec` 分配、释放、复用交错；`ZveMountVerdictSpec` kill 后复用 | 4 tests 全通过。[日志](age-mount-r07b.log) |
| T1 / T2 | `testOnly flow.cluster.ZveSystemSmokeSpec` | Zve smoke、5 个 cluster 标量程序和 13 个 riscv-tests 全通过；向量 verdict replay（原因 6）均为 0。[日志](system-regression-r07b-replay.log) |
| §9 | `ZveGemvQ4Spec`，4 个 ELF | 全通过；对照误差为 0；向量 verdict replay 均为 0。[构建](gemv-build-r07b-final.log)、[仿真](gemv-r07b.log)、[完整计数 JSON](gemv-r07b.json) |

T10 独立字节模型覆盖 7 个 MAC 操作、`.vv/.vx`、SEW 8/16、LMUL 1/2/4、随机 vl、vstart、mask、合法高半重叠及 VRF 随机反压，共 336 例。T11 覆盖 10 种操作/形式、SEW 8/16/32、mask 和 vstart，共 120 例。T3′ 的 32 B SoC profile 和 64 B 默认 profile 各运行 32 个起始 offset、3 种 SEW、4 种 LMUL、读写、mask、whole、mask-memory、跨页与乱序返回，逐字节比较。另在最大 LMUL=8 上对每种 SEW 的 `vl=1..VLMAX` 逐值扫完读写两种方向，同时轮换 offset、mask、跨页条件；因此每个 vl 值都有覆盖，但没有对 offset×vl×LMUL 做全笛卡尔积。[逐值扫日志](stream-exhaustive-r07b.log)。

编译修复：Claude 的 R07b RTL 本身在首轮 `sbt compile` 通过。新增性能观测第一次 elaboration 时跨模块直接读取 `stream.busy`、`stream.d`、`translator.isChunk`，Chisel 作用域检查拒绝；改为子模块只读 `perfIdCount`、`perfLoadDone`、`perfPreBusy` 输出后通过。`ZveMemoryHarness` 因新增 `ZveCoprocessorIO` 观测端口未赋值而在单测 elaboration 失败，给未实例化的观测信号接常零。新单测还将 `fpScalar` 错写成 `c.fpScalar`，修为 `c.issue.fpScalar`。旧 `ZveCoprocessorSpec` 的 `vwmaccsu.vv` 期望 `−131070` 与冻结 §8.3 R07-1 的 `s(vs1)×u(vs2)` 冲突；经用户授权改成 `−65534 = −1×65534`，不改 RTL 符号规则。

## KCU105 综合和布线门槛

Alan 上运行 `target.py --cpu-type breeze-zve32f --with-sdcard --sys-clk-freq 100000000 --vivado-threads 4`，随后 Vivado `synth_design`、利用率和时序报告；综合 exit 0。最终重新生成的 `single-zve32f` 与 Vivado source snapshot 的 140 个被综合的 `.sv` 文件逐字节一致；`FlowFpnewSources.sv` 是 Verilator include-list 辅助文件，CVFPU 文件另由 LiteX 加入 Vivado 项目。[快照比对](synth-snapshot-match.json)、[目标生成](target-r07b.log)。

| 综合资源 | 使用 | 容量 | 利用率 |
|---|---:|---:|---:|
| CLB LUT | 180,894 | 242,400 | **74.63%** |
| 其中逻辑 LUT / LUTRAM / SRL | 168,114 / 12,648 / 132 | | |
| FF | 74,695 | 484,800 | 15.41% |
| BRAM36 / BRAM18（tile 合计） | 167 / 3（168.5） | 600 tile | 28.08% |
| DSP | 74 | 1,920 | 3.85% |

主要层次 LUT（父项包含子项，不能横向相加）：SoC `BreezeClusterAxi` 172,554；backend 138,948；`ZveMount` 106,893；其 `unit` 90,185，内含 `ZveWindow` 26,097、`ZveVrfAccess` 24,341、唯一 `ZveElementEngine` 10,306、ALU 4,848、FMA 4,198、conversion 3,903、stream 3,547、slow 1,417、MAC 910（24 DSP）。`ZveElementEngine.resultRows` 为 1,780 LUT，其中 LUTRAM 740。Frontend 17,584，L2 4,769。[完整资源报告](xilinx_kcu105_utilization_synth.rpt)、[完整层次](xilinx_kcu105_utilization_hierarchical_synth.rpt)。相对先前 R07 的 202,230 LUT，减少 21,336 LUT；`ZveWindow` 自身从约 17k 升到 26,097，不能把面积节省归因于 age matrix。

综合时序 WNS **−0.380 ns**、TNS −21.450 ns，130 个 setup failing endpoints。[时序摘要](xilinx_kcu105_timing_synth.rpt)。以下是 `report_timing -max_paths 10 -nworst 1` 的十个不同端点；Vivado 的初始 `-nworst 10` 将同一端点重复十次，因此另从同一综合 checkpoint 导出[十端点完整路径](r07b-worst10-distinct-synth.rpt)。所有路径前缀为 `BreezeClusterAxi/cluster/`。

| # | WNS ns | 逻辑级 | 起点 → 终点（以上述前缀为根） |
|---:|---:|---:|---|
| 1 | −0.380 | 35 | `backend/zveMount/slowFp/fp/i_fpnew/.../i_fma/gen_inside_pipeline[0].mid_pipe_sum_q_reg[1][52]/C` → `backend/zveMount/slowFp/fp/i_fpnew/.../i_fma/gen_output_pipeline[0].out_pipe_status_q_reg[1][UF]/D` |
| 2 | −0.259 | 27 | `l1d/internal3_req_src_reg[1]/C` → `frontend/cache/data_0_ext/Memory_reg_0/WEBWE[1]` |
| 3 | −0.259 | 27 | 同起点 → `frontend/cache/data_0_ext/Memory_reg_1/WEBWE[1]` |
| 4 | −0.259 | 27 | 同起点 → `frontend/cache/data_0_ext/Memory_reg_2/WEBWE[1]` |
| 5 | −0.259 | 27 | 同起点 → `frontend/cache/data_0_ext/Memory_reg_3/WEBWE[1]` |
| 6 | −0.259 | 27 | 同起点 → `frontend/cache/data_1_ext/Memory_reg_0/WEBWE[1]` |
| 7 | −0.259 | 27 | 同起点 → `frontend/cache/data_1_ext/Memory_reg_1/WEBWE[1]` |
| 8 | −0.259 | 27 | 同起点 → `frontend/cache/data_1_ext/Memory_reg_2/WEBWE[1]` |
| 9 | −0.259 | 27 | 同起点 → `frontend/cache/data_1_ext/Memory_reg_3/WEBWE[1]` |
| 10 | −0.259 | 27 | 同起点 → `frontend/cache/data_2_ext/Memory_reg_0/WEBWE[1]` |

最差路径留在向量 FP 内部，不再从向量 window 穿过 WB 到 frontend；其数据路径 10.215 ns、35 逻辑级，属于综合估算（未布局布线）。LUT 74.63% > 70% 且综合 WNS < 0，§10.4 的三项门槛未同时满足，**未执行 place/route；布线 WNS 不存在**，也未生成 bitstream。

## §9.3 微基准

K=896。标量 N=64，向量 N=256 或 N=64；每 block 为 64 行×32 权重、1280 B repack 块。N=256 的权重 143,360 B，超过 64 KiB L2；N=64 为 35,840 B，并在计时前预热权重。四累加器版用 4 个独立 e16 m2 累加器。周期与带宽只统计 kernel marker 内，单元忙周期占比分母是观测 phase cycle。所有结果 `maxErrorBits=0`。

| 版本 | kernel 周期 | block 数 | 周期/block | 权重 B/拍 | 对标量每 block 加速 | 忙周期占比：ALU / MAC / conversion / FMA / stream |
|---|---:|---:|---:|---:|---:|---|
| 标量 N64 | 957,356 | 28 | 34,191.29 | 0.0374 | 1.00× | 不实例化 Zve |
| 向量 N256，1 累加器 | 116,520 | 112 | 1,040.36 | 1.2303 | 32.86× | 53.71 / 46.12 / 3.17 / 5.99 / 41.34% |
| 向量 N64，1 累加器，预热 | 27,811 | 28 | 993.25 | 1.2887 | 34.42× | 56.39 / 48.30 / 3.32 / 6.25 / 33.69% |
| 向量 N256，4 累加器 | 114,176 | 112 | 1,019.43 | 1.2556 | 33.54× | 63.09 / 47.08 / 3.24 / 6.08 / 41.98% |

| §6/§9.3 计数（kernel 观测窗口） | N256 1acc | N64 1acc | N256 4acc |
|---|---:|---:|---:|
| 观测 phase 周期 | 116,569 | 27,828 | 114,188 |
| solo 等待周期 | 2,814 | 702 | 2,817 |
| 预翻译忙周期 / 完成次数 / 延迟合计 | 6,842 / 564 / 7,406 | 1,709 / 141 / 1,850 | 6,842 / 564 / 7,406 |
| 预翻译延迟直方图（13/14/15 拍） | 525/4/35 | 132/1/8 | 525/4/35 |
| WB 等 verdict 周期 | 7,406 | 1,850 | 7,406 |
| 相邻 vle 空闲次数 / 合计 / 平均拍 | 559 / 67,651 / 121.02 | 139 / 17,991 / 129.43 | 559 / 65,524 / 117.22 |
| stream ID 0/1/2/3/4 占用周期 | 72,937/11,412/31,938/80/202 | 20,658/2,364/4,736/26/44 | 70,805/10,852/32,258/72/201 |
| L2 向量端口请求 / 接收间隔数 / 间隔平均拍 | 4,516 / 4,515 / 25.73 | 1,129 / 1,128 / 24.43 | 4,516 / 4,515 / 25.21 |
| L2 向量端口 stall / slotFull 周期 | 31,151 / 13,769 | 5,963 / 2,589 | 30,872 / 13,572 |
| credit 等待周期 | 78,616 | 17,598 | 78,126 |
| fast / Serial 向量访存指令 | 564 / 0 | 141 / 0 | 564 / 0 |
| verdict replay（reason 6） | 0 | 0 | 0 |
| L2 向量 requests / misses | 4,516 / 4,513 | 1,129 / 8 | 4,516 / 4,513 |

[完整 JSON](gemv-r07b.json)保留相邻 vle 空闲和 L2 请求接收间隔的**全部直方图 bin**、各单元忙周期原始值、stream ID 直方图及 whole-run replay 原始向量。没有单独的 L2 miss 往返延迟计数，因此不能从这些数据直接报告该延迟。当前观测口也没有单条向量访存的 ID→consume 身份追踪；solo 等待、预翻译和 WB 等待分别计数，不能相加冒充逐指令端到端延迟。N64 预热后的 8 次 miss 和 993.25 拍/block，以及四累加器的 1,019.43 拍/block 是测量值；瓶颈归因仍留给 §9.4 的下一步判断。
