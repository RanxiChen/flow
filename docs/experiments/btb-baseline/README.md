# 当前 BTB 独立 200 MHz 基线

2026-10-24，用户授权提取并测量当前 BTB。直接实例化生产 `BreezeBankedBtb`，不改逻辑、流水级或存储属性；独立测试壳在 `design/src/main/scala/frontend/experiment/BreezeBtbTimingHarness.scala`。

- 默认 2 bank × 32 sets × 2 ways；完整 64 位查询/target，57 位 tag、2 位 kind，保留 valid、RR、4 项更新队列、repair、更新仲裁。
- 查询与训练/repair 输入均可活动，两个槽位所有结果均寄存并接出，防止常量化裁剪。
- 输入/输出寄存器为同步测试壳。外部查询在沿 N 采样，结果在沿 N+2 后有效；此处包含两端 fixture，BTB 自身的时序/延迟不变，查询 II=1。
- 外部 update/repair 是 raw Valid stimulus，不宣称 Decoupled；输入沿采样后，`updateAccepted` 在实际 BTB 入队沿给出确认。生产 BTB 内部 Decoupled 接口不变。
- 不包含 PHT、GHR、RAS、CFI 最前 taken 选择、next-PC 反馈或取指存储；不能由本实验直接推出完整前端 200 MHz。

先用地址键关联表 oracle 验证槽位/tag/替换，再测试更新端口争用和队列背压；另验证测试壳的精确延迟/II。

Vivado 2022.2，Alan，`xcku040-ffva1156-2-e`，5.000 ns、0.100 ns uncertainty；真实 BUFGCE；虚拟引脚锚定邻近边界寄存器。
测试壳 IO 使用边界寄存器时钟参考，max/min 都为 0.500 ns，仅定义本地同步壳接口，非板级 IO 模型。所有内部路径保持 5 ns、无 false/multicycle path。
分别查看 F0 读地址/使能、F1 结果捕获和更新状态路径，审计 routed setup/hold/脉宽/约束完整性、RAM 映射与资源。

控制脚本和证据目录：`records/btb-baseline-20261024/`。实测结果如下。

## 2026-10-24 实测结论

**现有 BTB 的 setup 满足 5 ns；包含更新路径的整个模块尚未完整闭合，存在内部 hold 违例。未修改 BTB RTL、未加流水级。**

功能检查 3/3 通过、0 skip；直接核心核对 2,858 个槽位结果，寄存测试壳核对 438 个，合计 3,296 个；更新压力下接受 48 笔更新，对应 bank 观察到 96 次受阻预测，另一 bank 仍可用，队列背压和最终数据均正确。测试壳连续查询 II=1，查询输入沿 N → 结果沿 N+2 包含测试壳两端寄存器。

| Routed 检查范围 | 最差 slack | 结果 |
|---|---:|---|
| 查询输入寄存器 → BTB F0 端点（含 valid 选择） | +2.365 ns | setup 通过 |
| BTB SRAM / 查询快照 → 两槽结果寄存器（F1） | +0.251 ns | setup 通过 |
| 更新状态端点（含替换/valid/队列状态） | +0.459 ns | setup 通过 |
| 全部路径 setup | +0.251 ns，TNS 0 | 通过 |
| 全部路径 hold | −0.032 ns，THS −2.875 ns，123 端点 | 未通过 |
| 脉宽 | +1.832 ns，0 失败 | 通过 |

最慢查询路径：`btb/ram_0_0_ext/_R0_addr_d0_reg_rep[3]/C` → `REG_0_target_reg[55]/D`。
数据延迟 4.504 ns，其中逻辑 1.239 ns、布线 3.265 ns（72.49%），9 级；经过 LUTRAM 读出、tag 命中判断与目标选择。读地址寄存器扇出 126、hit 扇出 68、目标选择控制扇出 66。不能仅根据源码框图断定“槽位重排”是实测瓶颈。

最差 hold：更新队列 LUTRAM `btb/writes/ram_ext/Memory_reg_0_3_112_125/RAMA_D1/CLK` → `btb/update_pc_reg[46]/D`。
最短数据路径 0.163 ns（逻辑 0.151、布线 0.012），快角 clock skew 0.039 ns、hold uncertainty 0.100 ns。它是更新队列到更新寄存器的内部短路径，不是测试壳外部 IO 违例，也不是查询 setup 不足。

route-r2 在原 routed DCP 上执行 `phys_opt_design -aggressive_hold_fix` 后重新 route。工具识别到 123 条候选，但优化 0 条，插入 0 个 buffer / delay；最终 setup、hold 与首轮完全相同。不宣称 hold 修复成功，不降低 uncertainty、不增加 false/multicycle path。

## 存储与资源

- 四个逻辑 `32×123 bit` 表阵列被映射为 distributed LUTRAM；没有 BRAM。每表 72 LUTRAM，四表合计 288；4 项更新队列另用 76 LUTRAM，总计 364。
- BTB 本体：1,469 LUT（1,105 logic + 364 LUTRAM）、420 FF、0 DSP。
- 测试壳另有 469 FF；含壳总计 889 FF。LUT 层级统计存在跨层合并，不能把子层总数直接相加。
- 2,542/2,542 可路由网络全部完成，0 路由错误；无未约束内部端点、无缺失时钟/IO delay、无组合环；无有效 timing exceptions。
- DRC 无错误；OOC 未指定板级配置电压，保留 CFGBVS-1 warning。本实验未生成 bitstream。

## 证据与复现

- 实际主机 Alan；首选 cloud_chen 的 SSH 包括升权重试均超时，按共享配置回退。预检记录见 [host-audit.json](../../../records/btb-baseline-20261024/host-audit.json)。
- 硬件/测试版本 `a55bc20a1e0d50e9a1cffc5df4a08e5916496842`；生产 `BreezePipelinePredictor.scala` 与 main HEAD 内容相同，SHA256 `ee07a01200f72fa10d5b62c4170c6e435dcf383b42176ee42655e9fc8d081d90`。
- 功能和生成退出 0：[tests-audit.json](../../../records/btb-baseline-20261024/evidence/attempt-01/tests-audit.json)、`tests.log`、`tests.xml`、`rtl-manifest.json`。
- route-r1 综合/布局/布线及通用报告均完成；脚本在分组报告处因 fixture 结果寄存器命名与筛选不符退出 1。修正脚本不改 design，再打开同一 DCP 的 audit-r1 退出 0，确认 65 个输入查询寄存器和 **135 个实际输出结果寄存器**，不是只检查 valid。
- route-r2 物理 hold 尝试与报告退出 0，但时序检查仍有 hold 失败；流程退出成功不等于完整时序通过。
- 当前结果：[result.json](../../../records/btb-baseline-20261024/evidence/route-r2/result.json)、[query-result-setup.rpt](../../../records/btb-baseline-20261024/evidence/route-r2/query-result-setup.rpt)、[worst-hold.rpt](../../../records/btb-baseline-20261024/evidence/route-r2/worst-hold.rpt)、[timing-route.rpt](../../../records/btb-baseline-20261024/evidence/route-r2/timing-route.rpt)。
- 全部源码/脚本版本、源文件摘要与远端 DCP 摘要见 [evidence-binding.json](../../../records/btb-baseline-20261024/evidence-binding.json)。DCP 留在 Alan `/home/chen/FUN/flow-runs/btb-baseline-20261024/`，本地/分支保留文本证据与生成 RTL。

这份结果支持“现有拍数下 BTB 查询本身能满足 5 ns setup”。完整 BTB 仍须修复内部 hold；是否为更大组合路径增加流水级，需另外测量 PHT/早期选择/PC 反馈，不能由本实验直接推出。
