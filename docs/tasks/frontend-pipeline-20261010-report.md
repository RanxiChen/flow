# Breeze 独立前端重构验收

更新日期：2026-10-11。独立前端功能和 100 MHz routed setup/hold 验收通过；后端未接入。完整核对结果见 [acceptance.json](../../records/frontend-pipeline-20261010/acceptance.json)。

## 实现范围

新增 [BreezeFrontendPipeline](../../design/src/main/scala/frontend/BreezeFrontendPipeline.scala)，并行连接真实 Sv39 iTLB/PTW、新 VIPT ICache 和预测器。旧 `BreezeFrontend`、`BreezeFetchTranslator`、L1ICache、BreezeCluster、CSRFile 和后端未改动；目前没有把新前端接进旧核心。指令消费者、解析/提交反馈、页表内存与 refill 由独立测试驱动，便于后续后端重构。

规格见 [frontend-pipeline-design.md](../frontend-pipeline-design.md)。命中路径为 F0 查询、F1 判定/预测反馈、F2 RVC 重组/预解码；每拍可接收一个 32-bit 窗口，8 项指令队列支持每拍入队最多两条、出队一条。PC 半字对齐，不强制四字节对齐。line/page 末尾返回一个 parcel，跨界 RV32 指令保留头半字，下一页独立翻译、检查权限和报告精确异常。

默认 ICache 128 sets × 4 ways × 32 B，16 KiB；每 way 4 KiB，满足 VIPT 页内 index。BTB 两 bank、每 bank 32 sets × 2 ways，共 128 entries；event PC 使用最后一个 parcel 地址。GShare 1024 × 2-bit，三个逻辑查询 A/B/C，当前四个单预测读口 bank；冲突依次 A/B/C，同索引广播、不可用候选默认 NT。PHT 仅解析训练，F2 不做强化训练。F2 持久推测 GHR 更新、EX 恢复、提交历史和两份完整 8 项 RAS 均有独立接口。

PMP 复用 CSR 已寄存的范围，F0 比较页内低位，F1 在 TLB 候选 PA 上并行比较高位，再选择、组合权限。新增 iTLB 候选旁带不授权访问；仅新前端启用 `pipelinedFetchKill`，在取消沿清 ownership，切断 F2 取消到 F0 SRAM CE/ready 的路径，旧客户端默认行为不变。

## 功能证据

实际执行主机 Alan。每次编译/仿真/RTL 生成前重读共享主机配置；首选 `cloud_chen@47.96.71.231:22` 在有限 SSH 预检中连接超时，备用 Alan 的环境、工具、内存和磁盘通过检查。没有在本机运行仿真或 RTL 生成。

基线 SHA：`9747dfebf61930c1f4a9934b2a8aab6425ae72ff`。验证执行时本任务修改尚未提交，因此以逐文件 SHA256 的准确工作区快照绑定证据；验证源码摘要为 `778553faff6ff7c72e3f7304c6278ffea8f01be383a5aa6e7bedd28bab91a6fc`。验收后文档说明可以更新，所有 design/config 输入必须保持与该快照一致。

编译/测试 cwd：`/home/chen/FUN/flow-frontend-pipeline-20261010/design`。Java 20.0.2、sbt 1.9.7、firtool 1.128.0、Verilator 5.028。完整 argv、UTC 时间、exit 和 source manifest 见 [tests-14/result.json](../../records/frontend-pipeline-20261010/tests-14/result.json)。

```text
testOnly flow.frontend.BreezeFrontendPipelineSpec flow.frontend.BreezePipelinePredictorSpec flow.mmu.sv39.Sv39MmuSpec flow.mmu.sv39.Sv39StructuresSpec flow.mmu.sv39.Sv39PmpCandidateSpec
```

| 套件 | 用例数 | 结果 |
|---|---:|---|
| BreezeFrontendPipelineSpec | 27 | 全通过 |
| BreezePipelinePredictorSpec | 2 | 全通过 |
| Sv39MmuSpec | 17 | 全通过 |
| Sv39StructuresSpec | 15 | 全通过 |
| Sv39PmpCandidateSpec | 1 | 全通过 |
| 合计 | 62 | failure/error/skip 均为 0 |

覆盖连续命中 II=1、RVC/RV32 随机反压、真实 Sv39 双页 walk、跨页第二 parcel 故障、超页和 sfence 改映射、PMP 首匹配和 M/S 权限、refill 错误与取消排空、同地址重取、安装沿 fence.i、双槽分支历史、PHT 竞争/同址旁路/实际训练、RAS 恢复、误预测优先级、输出 stall 稳定、CSR 权限重启和完整 timing harness 顺序流。

新增 BTB 测试专门确认：首条跳转后，未消费的第二槽合法 entry 不得被 F2 当作虚假 CFI 失效。另一个新增用例检查目标未知的压缩 indirect call 后接 return 或第二个 call：两个 RAS hint 依程序顺序计算，第二槽可前递第一槽的 push。功能检查通过后没有调整 golden、跳过用例或缩减测试规模。

长反压下的历史回归另有失败复现证据：[tests-13-red/log.txt](../../records/frontend-pipeline-20261010/tests-13-red/log.txt)。队头/队尾都在途、F1 已空闲时，旧版本后续查询历史从 2 回退到 1。修复保存流尾暂算历史、保留 F1 当拍前递，并以 F2 已接受前缀作为组前历史；查询前快照不符时修复。相同用例期望未经修改，完整回归通过。

RTL 生成入口：`runMain flow.frontend.GenerateBreezeFrontendTimingHarness --target-dir ../rtl-timing`，[rtl-08/result.json](../../records/frontend-pipeline-20261010/rtl-08/result.json) exit=0，与测试为同一源码摘要。

## 物理验证边界

Vivado 2022.2，器件 `xcku040-ffva1156-2-e`，独立顶层 `BreezeFrontendTimingHarness`。完整 synth → opt → place → phys_opt → route → phys_opt，不生成 bitstream。

约束：10 ns、clock uncertainty 0.1 ns、I/O max 2 ns/min 0.2 ns；没有 false path、multicycle 或降低频率。harness 的 Decoupled 边界为显式双项寄存器 FIFO，scalar/context 注册一次，内部实际 BUFGCE；OOC 数据端口按已放置边界逻辑分配 INT 虚拟引脚，保存 CSV/XDC。这样包含真实数据端口和内部全局时钟布线；外部 SoC/板级时钟与 I/O 仍不在该独立上下文内。虚拟引脚的用法依据 [AMD UG905 Context Constraints](https://docs.amd.com/r/2022.1-English/ug905-vivado-hierarchical-design/Context-Constraints)。

关键优化是原始位段 CFI 预解码与完整 RVC 展开并行、F1 预测旁带与晚到权限/完成判定分离、取消沿清查询 ownership，以及 refill 接收和 SRAM 安装分拍。最后一项仅增加 miss 安装拍，正常三拍命中和 II=1 保持。

验证壳的输入以 root clock 为参考，内部 BUFG 分发插入延迟约 3 ns，直接连接输入到边界寄存器产生大 hold 违例。在物理验证环境输入侧加入 1918 个负沿采样寄存器，将外部同步源本周期稳定值交给下一内部正沿；前端本体、三拍命中及正常同一分发时钟下的集成接口不变。没有增加前端流水级或放松 I/O 时序约束。该边界的独立 [phase-interface-02](../../records/frontend-pipeline-20261010/phase-interface-02/result.json) 编译/仿真 exit=0，覆盖 8 种 2–4 ns 分发延迟、0.2–2 ns 随机输入到达、payload 保持和至少 80000 次向量比较；直接采样的负对照出现预期错拍。这是边界采样契约测试，不是整前端门级协同仿真。

route-12 完整 routed WNS +0.085 ns、WHS -0.037 ns，仍未通过。最差路径是 BTB 更新队列 LUTRAM 输出到同 slice 更新寄存器的短路径。最终 route-16 从该完整布线检查点出发，仅将 124 个实测短路径接收寄存器移到空闲 SLICEL，保留功能连接、寄存级数和前端 RTL，重新完成布线。移动清单见 [hold-placement.csv](../../records/frontend-pipeline-20261010/route-16/hold-placement.csv)。

历史候选均保留真实边界：route-05 setup +0.050 ns、hold -0.044 ns，其 OOC 上下文尚不完整；route-03/04/06/07/08 被后续源码替代并精确终止；route-09 同源完整 routed setup +0.029 ns、hold -2.973 ns；route-10 在后续 hold 优化时 Vivado 崩溃，exit=139；route-11 因 ECO 层次连接错误退出；hold-probe-01 只作诊断，没有修复违例。它们均不是通过证据。route-13/14 是物理脚本错误；route-15 的恒等 LUT ECO 发生引脚映射冲突并有部分网络未路由，故排除。最终使用连接正确的 route-12 检查点做纯放置修复，没有沿用 route-15 网表。

## 最终 100 MHz 证据

[route-16/result.json](../../records/frontend-pipeline-20261010/route-16/result.json) exit=0，与 tests-14/rtl-08 为同一源码摘要。实际 cwd `/home/chen/FUN/flow-runs/frontend-pipeline-20261010/route-16`，完整命令是 Vivado 2022.2 batch 执行该目录的 `move-hold.tcl`；UTC 2026-10-10 18:39:00.978650 至 18:46:25.324605。

| 项目 | 最终结果 |
|---|---:|
| 周期 / uncertainty | 10.0 ns / 0.1 ns |
| Routed WNS / TNS | +0.143 ns / 0 |
| Routed WHS / THS | +0.028 ns / 0 |
| WPWS | +4.146 ns |
| 完整路由 / 可路由网络 | 33452 / 33452 |
| 路由错误 | 0 |
| check_timing 各项问题计数 | 全为 0 |
| DRC Error / Critical Warning | 0 / 0 |

依据 [timing-route.rpt](../../records/frontend-pipeline-20261010/route-16/timing-route.rpt)、[route-status.rpt](../../records/frontend-pipeline-20261010/route-16/route-status.rpt) 和 [utilization-route.rpt](../../records/frontend-pipeline-20261010/route-16/utilization-route.rpt)。独立顶层含验证环境共 23530 LUT、13161 FF、16 RAMB36；其中 frontend 层次为 22387 LUT、8278 FF、16 RAMB36。相比 route-12，最终 LUT 占用增加 1，FF 和 RAM 数量不变。面积包含物理优化结果，不等同于综合前的逻辑面积。

最差 setup 是输出边界 `instructions/head_reg` 到 instruction[26]，最差 hold 是前端指令队列到验证壳第二项寄存器。setup/hold 裕量较小，本结论严格限定于当前器件、约束和独立物理上下文；后续整核集成需要重新布线验收。

证据核对命令：`python3 records/frontend-pipeline-20261010/audit.py tests-14 rtl-08 route-16`，exit=0。它核对当前 199 个 design/config 文件、同源测试/生成/物理输入、导出哈希、5 个 JUnit 套件、边界采样测试、完整路由和时序门槛。生成的 30 个 SV 文件还与最终物理输入逐文件匹配，mtime 均落在 rtl-08 的生成时间窗口；记录见 `generated-rtl-audit.json`。检查点 `route.dcp` 保留 Alan，SHA256 `27692055290be501061970db6222a6fc2ec17d8b0ca38ead5bdc0ba5ef0e1c2a`，完整来源链在 route-16 的 upstream.json 和 checkpoints.json 中。

## 后续连接契约

后端应保留每条指令的 `eventPc/historyBefore/phtIndex`，按程序顺序发送 `resolve`，只有 fire 才接收训练和恢复；`incorrect` 携带真实 `nextPc`。RAS 解析反馈须带实际 push/pop 和 returnAddress。`committedBranch` 只来源于已提交条件分支。权限 CSR、fence.i 和全局异常以显式 restart PC 取消旧指令；sfence 依现有 drain/idle 协议。PTW 内存和 cache refill 仍是外部协议。

本验收只覆盖独立前端；后端集成、全 SoC 时序、bitstream、上板和性能对比需在后续接入后重新验证。
