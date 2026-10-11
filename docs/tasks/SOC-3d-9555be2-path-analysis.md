# SOC-3d 9555be2 中间关键路径分析

2026-10-09 16:06，主仓 `9555be20a183c43728944b39fe4131493b127c28`，CVFPU `b32aeeb6eda9a61b99185e39e78b74d4fb5340be`。Alan，生产 LiteX KCU105，`breeze-tiny` 单核 / `breeze` 四核，100 MHz，Vivado 2022.2，器件 xcku040-ffva1156-2-e。两路原构建继续运行，不修改 RTL、约束或原构建进程。

## 证据阶段

独立报告进程只读取已完成的 `xilinx_kcu105_place.dcp` 副本。这些检查点位于 `place_design` 和 `phys_opt_design -directive AggressiveExplore` 之后、`route_design` 之前；报告仍包含 estimated net delays，不能代替最终 routed timing。

报告进程 UTC 08:02:46.663442–08:06:32.925404，exit 0，monotonic wall 226.261672 秒，maxThreads 1。实际命令、检查点 SHA256、报告和生成 RTL 保存在 [path-inspection-1603](../../records/soc3d-combined-20261009/retry-9555be2/path-inspection-1603/)。远端根 `/home/chen/FUN/flow-runs/soc3d-combined-9555be2/path-inspection-1603`。报告前检查源 HEAD，RTL reference 解包自同 SHA small 归档。

先前日志的四核 -1.738 ns 是物理优化中间值；已完成的布局/物理优化检查点 WNS 为 **-1.457 ns**。单核该检查点 setup WNS **+0.014 ns**；随后主构建布线中间日志曾给出 +0.320 ns、WHS -0.112 ns，没有对应的最终 route DCP，因此不能把布局 hold 路径指认为那个 -0.112 ns 路径。

## 四核 setup 路径

下表均为 sys_clk → sys_clk，周期要求 10.000 ns、慢角 setup。逻辑/连线延迟来自详细路径，不含时钟网络贡献。

| 起点 → 终点（层级缩写） | slack ns | 数据延迟 ns | 逻辑 / 连线 ns | 逻辑级数 |
| --- | ---: | ---: | ---: | ---: |
| mmu_3/dtlb/Memory_reg[6][0]/C → l1d_3/cpu2_pmpAllowed_reg/D | -1.457 | 11.349 | 2.417 / 8.932 | 21 |
| l1d_1/internal2_valid_reg_rep__2/C → l1d_1/plru_87_reg[1]/D | -1.457 | 11.270 | 1.975 / 9.295 | 27 |
| l2/outQ_6/deq_ptr_value_reg/C → frontend_2/realigner/rawInstReg_reg[16]/D | -1.449 | 11.510 | 1.231 / 10.279 | 15 |
| mmu_1/ptw/wPte_reg[54]/C → mmu_1/itlb/Memory_reg[7][34]/R | -1.447 | 11.392 | 0.908 / 10.484 | 10 |
| backend_1/writeback/w2_valid_reg/C → frontend_1/realigner/rawInstReg_reg[19]/CE | -1.438 | 10.952 | 1.613 / 9.339 | 18 |

1. **TLB 命中到 PMP 仍串行。**详细网表链是 TLB 阵列读出 → baseHit → level/PA 选择 → candidatePaddr → PMP 边界比较 → 首项权限判断 → cpu2_pmpAllowed。当前提前末地址和 CSR range 预解码已经存在，残留路径仍跨 TLB 命中/物理地址选择与宽边界比较。报告中候选地址进入 PMP 后有 fanout 102 的网，估算 1.430 ns；连线占 78.703%。映射 `Sv39Tlb.scala:40-60`、`BreezePmpChecker.scala:24-59`、`L1DCache.scala:258-294`。建议先审查 TLB priority page selection 的独热并行化及 PMP 高位比较分块均衡归约，保持单次命中、PMP first-overlap、权限/故障拍数。

2. **L1D 存在跨模块的同拍反馈长链。**详细链为 internal2.valid → probe/hitWay → miss/S2Hold → backend scoreboard/writeback → s2Kill → miss/PLRU 新值与写入。s2Kill fanout 439，估算连线 1.399 ns；最终还经过共享 PLRU 地址/数据选择，合计 27 级，连线占 82.476%。映射 `L1DCache.scala:393` 起的 S2 判断、`allocates` 与 `plru` 两处写入（约 740、770 行）。建议把 PLRU 每个 set/way 的候选值、地址译码与最终 kill-qualified 更新许可分开，局部独热更新，保留同拍 kill 安全；晚到授权不能继续驱动全表共享数据/地址选择。仅 tag 分 bank 没有切掉这条 PLRU 路径。

3. **L2 响应一路组合贯穿前端。**实际起点是 L2 response queue 读地址，路径经队列 RAM 读出的 error、L1I/icache 判断、wordBufData 选择、fetchTranslator、realigner 终止于 rawInstReg。该路径连线占 89.305%，其中两条估算网分别为 2.118 和 1.921 ns。另有 WB/重定向控制到 realigner payload CE 的长链。建议审查响应缓冲边界、前端 payload 捕获和 valid/kill 的关系，以及 error/tag-hit 控制与数据候选的并行计算；增加寄存边界会改变响应拍数，实施前应明确同步合同和恢复/反压测试。

4. **PTW 完成合法性控制扇出到 TLB 阵列。**源 PTE bit 54 参与 PTE 判错，经过 PTW done/refill 控制、TLB 写入译码到映射后的阵列寄存器 R pin。此处 R 是实现网表引脚，不能仅凭名字判为体系结构全局 reset。连线占 92.029%，只有 10 级逻辑。建议把 PTW 判定结果、TLB 写入选择/数据与最终许可分离，并让 refill 的 bank/way 许可局部化；若注册 done/refill，应验证 miss 保持、kill、sfence 和读写冲突合同。

四核最差 20 条中没有原先 FPU→EX payload CE 长链；这是排名变化，不能据此宣称全部 FPU 路径通过。另有 L1D internal2.paddr → ps.data 及 iTLB → fetchTlb.accessFault 接近 -1.43 ns，需在最终路由报告中继续复核。优先处理多个公共控制/连线瓶颈，不能只优化一条后就预期所有违约消失。

## 单核 hold 的可定位范围

布局检查点最差 hold **-0.089 ns**，在 CVFPU DIV/SQRT 输入流水寄存器 `inp_pipe_operands_q[1][0][9]` → `srcf0_q[9]`，sys_clk 慢角 hold，逻辑级数 0，数据延迟 0.292 ns，时钟 skew +0.273 ns。这是一条直连短路径，适合先等待路由 hold 修复/后续物理优化，不能把它解释为浮点计算逻辑太深或用增加计算级数解决。

该路径仅说明布局阶段的 hold 来源；最终单核 WHS 与对应路径需要 route DCP/report。当前不改动正在运行的候选，待最终报告确认上述家族是否继续违约，再确定 RTL/物理优化的具体改动。
