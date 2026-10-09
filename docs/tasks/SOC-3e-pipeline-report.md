# SOC-3e 流水与局部选择组合优化

2026-10-09 用户授权：保留可用单核基线，完成已讨论优化；本轮仍同时跑单核/四核 LiteX/Vivado 和完整回归，不仅跑单核，并比较匹配单核程序性能。Vivado 无外部时限，记录各任务 wall time，仿真错误使本候选撤回。

基线主仓 `d4ae2fc24df26f597d4811cc2bbb12b9e8ae47da`，CVFPU `b32aeeb6eda9a61b99185e39e78b74d4fb5340be`，保留标签 `soc3d-single-100mhz-d4ae2fc`。生产单核 Linux/gshare、KCU105、100MHz 最终 WNS +0.067ns、WHS +0.030ns，setup/hold 均无失败；bitgen exit0，wall 2056.024936s。cloud_chen 全回归 Scala 469/469、68套件无取消/忽略/待定，LiteX exit0，总 wall 7253.714645s。该基线未作上板/Linux 运行证明，也不代表四核通过。

基线 main/CVFPU 源归档：`records/soc3e-20261009/baseline/`。Alan gateware/DCP/报告/配置完整归档 `/home/chen/FUN/flow-runs/soc3e-20261009/baseline/single-d4ae2fc-gateware.tgz`，SHA256 `fe7f5378951c16dfe30ae884f3d8600d35ce663ea3b9f2ffb1e469b06d6b77cd`；原 bit SHA256 `2f6d5069e1e12668b063d1fc7d912a30ac23b1bff00d7b27b382cae2e57156c2`。完整回归结果另存本地基线目录。

## 改动与冻结合同

本轮不是 TLB 缓存 PMP 决策：保留实际 PMP/PMA 检查，把它与早级 PA 选择分开。VIPT 的 S0 TLB/SRAM 同拍查询不变，S1 捕获 PA/翻译/阵列快照，S2 并行 PMP/PMA/tag/data，S3 响应与副作用。backend 新增 PERM，与 CPU cache 判定对齐；普通提交/写回整体 +1。所有 CPU/internal 在途槽纳入 kill、权限事件、快照失效、冲突、probe、drain。早级普通 store 对齐/mask，末级许可控制写；AMO 算法保持。

I-cache 选好返回字后新增两项寄存队列，预留返回信用、支持背压/flush。FP32 保留内部5拍，FP64 增至7拍，在输入对齐控制和归一化控制后切开宽移位；FpUnit 输出两项寄存队列，committed/tag/flags 保留到实际写回。Scoreboard 来源事件删除可由 hazard 推出的重复 idLeave 门控；BTB 分组一热选择、walk-cache 静态局部 PPN 选择；查找/替换语义及延迟不变。L2 接收时预留 order+dispatch，寄存后发 AR，切断下游 ARready 到 order RAM WE，RAW 与 stalled AR 所有权保持。

规格明确的 SOC-3e 修订优先于历史级数限制。测试拍数逐项按新合同迁移，数值 golden、种子、规模、watchdog、频率不放宽。T13 刺激重新对齐真实四来源，物理完成顺序仍 lateReg>DIV>MUL>FPU。

## 要求与验证映射

| 要求 | 刺激与独立预期 | 覆盖 | 状态 |
|---|---|---|---|
| D-cache PA边界与II1 | 热行16条独立load，恰3拍响应、每拍接收/响应，原golden数据 | L1DCacheSpec新增定向；BackendContractSpec T02/T03/P01 | 已写，待执行 |
| 权限上下文/kill/副作用 | PMP/PMA变化、held请求、TLB miss、probe/refill、年轻kill | 既有L1DPermissions/Context、L1DCache、L1DL2多核/故障/litmus | 待完整回归 |
| I-cache返回所有权 | refill+连续hit、两项背压、地址/数据稳定、flush同拍撤销 | L1ICacheSpec新增；既有frontend/cluster压缩指令测试 | 已写，待执行 |
| FP新增边界 | 精确FP32 E+7/FP64 E+9、连续8/16输入、IEEE flags、混合返回、随机背压 | FpUnitSpec迁移+buffered committed flags-only kill测试 | 已写，待执行 |
| 后端PERM | load-use/WB/W2、ALU旁路、四写口、饥饿、中断、精确trap、CSR drain | BackendContractSpec全合同；cluster全程序 | 待执行 |
| L2本地预留/稳定AR/RAW | 两slot在ARready=0接受、满拒绝、RID数据/错误组装；同拍WB优先、同行等B、异行推进 | L2MemEngineSpec新增；L2Home/多核随机 | 已写，待执行 |
| BTB/PTW局部选择 | 既有容量、替换、ASID、miss清零、walk-cache命中/失效 | 前端BTB及MMU/PTW原测试，不改golden | 待执行 |
| 匹配性能与物理证据 | 同SHA配置/同ELF对比cycles和retired；single/small最终100MHz报告 | ClusterWbSplitSmokeSpec相同三个程序；LiteX target→Vivado | 待启动 |

执行脚本、准确候选SHA/子模块、manifest、实际主机、cwd、命令、版本、exit、wall time、XML和各阶段报告将记录在 `records/soc3e-20261009/`。综合/布局估计/最终布线/bitstream/上板证据分别报告。

## 首次执行与修复

候选 `8dddbafb33fbdb5beaa5b7b3fb1588c0eb224490` 在 cloud_chen 全回归中出现 GShare redirect 后旧 refill 无法完成的 RTL 错误（32拍上限不变）。补齐非压缩 frontend 预测上下文队列及 FetchTranslator 的错PC/取消返回排空、live ready 传播后，`4a8ebbb6e6b47d10f3ca88b31239b9dc6e61c0ed` 同一测试通过。FP32/64精确流、各舍入、flags、随机背压、kill、L2两slot/RAW等定向项目已在日志中通过；全回归未结束，不能宣称全通过。

`4a8ebbb` 的 T11 测试漏迁移了两个写回/依赖期望，仍要求 R+8，而已批准的新合同为 late R+8、物理写与ID R+9。实际为43拍、旧期望42拍，属于测试合同迁移遗漏；新增PERM也要求中断刺激等到真实提交后，以及trap WB与fatal对齐多一拍。修复这些精确拍数，保持数值golden、种子和规模。自动监控按保守规则先取消两项Vivado，wall分别122.670616/122.649837s；保留失败、取消和综合日志，未取得该候选最终时序。首个启动脚本因未带执行权限失败，错误记录另存，chmod后才进入实际综合。

## 单核匹配性能首测

基线 d4ae2fc 与RTL候选4a8ebbb，Linux/gshare单核，默认seed1，AXI无背压，maxCycles2000000、watchdog20000不变。为避免新编译ELF元信息/代码差异混入比较，候选测量使用基线同三个ELF的逐字节副本，SHA256记录在performance-inputs.json。这是程序从启动到tohost的总周期/退休数，不是ROI微核、频率提升收益或板上实测；包含冷启动、取指/总线、trap/MMIO/原子操作。

| 程序 | 基线周期 | 新周期 | 周期增幅 | 两版本退休数 |
|---|---:|---:|---:|---:|
| lrsc_amo_single | 14575 | 15768 | +8.19% | 1127 |
| trap_misc | 25722 | 28079 | +9.16% | 2092 |
| mmio_console | 43402 | 47685 | +9.87% | 4258 |

三项tohost与console oracle均通过，测量进程exit0、wall70.148960s。固定频率下新增边界降低这组三程序的吞吐；cache II1与ALU依赖未新增气泡的定向条件不能代替整个前端的性能结论。后续将结合单核余量和四核最终时序判断这个代价是否值得，不能先宣称新版本性能更好。原始日志在cloud_chen `/home/cloud_chen/evidence/soc3e-4a8ebbb/performance/`；本轮后续仅测试/文档修改时，须证明RTL/config完全相同才能引用这些数据。
