# Breeze 路线图

本文只记录目标和顺序，设计细节以各设计文档为准：[`dcache-pipeline-design.md`](dcache-pipeline-design.md)、[`backend-pipeline-design.md`](backend-pipeline-design.md)、[`frontend-prediction-design.md`](frontend-prediction-design.md)、[`observability-design.md`](observability-design.md)。

## 1. 当前目标：v1 四核在 KCU105 上跑起来

### 1.1 对外配置

| 配置 | 用途 | 内容 |
| --- | --- | --- |
| 单核 | 开发和深度调试 | RV64GC、Sv39、L2、DDR、SD 卡；FASE 与飞行记录器可选 |
| 四核 | 产品配置 | RV64GC ×4、L2 256 KiB、DDR、SD 卡（镜像经 SD 卡加载）、JTAG 读取性能计数器；FASE 不是必需 |

v1 不保留 MCU 版本，不接入 PCIe。

### 1.2 参数调优

参数调优不是另一套硬件，而是**同一个四核配置的不同参数组合**：

- 设计文档中的开关和容量都做成生成参数，例如 `btbTakenOnly`、`speculativeGhr`、`rasEntries`、`s1ReturnPredict`、`icacheNextLinePrefetch`、BTB/PHT 大小、L2 慢槽数。
- 每组参数生成一个比特流，在板上用同一组基准程序运行，经 JTAG 读出计数器，计算 [`observability-design.md`](observability-design.md) 第 3 节的比例。
- 用结果决定默认参数，并作为设计来源表与消融实验的数据。

### 1.3 实施顺序

1. **T01** 后端记分板与 MDU（进行中）。
2. FPU 进记分板。
3. 新 L1D、新 L2、自有协议、AXI 集群边界与 LiteX 外壳；访存对齐新 L1D。
4. 前端：BTB、全局历史、RAS、S1 返回预测、L1I 预取。
5. 可观测性：计数器、`perf` 从口、JTAG 读取。
6. 单核上板（Linux、SD 卡）→ 四核上板 → 参数调优。

每一步都先写 RTL spec 并冻结，再实现和验证，流程见 [`tasks/`](tasks/)。

## 2. 以后的方向（v1 完成后再评估）

| 方向 | 内容 | 前提与评估方式 |
| --- | --- | --- |
| 除法结果缓存 | 8–16 项，键为操作类型与两个操作数，命中时 1 拍出结果，接在 DIV 快速路径上 | 先用计数器确认除法在实际负载中的占比；比较命中率与除法密集程序的周期数 |
| V 扩展 | 只在单核配置上，作为解耦协处理器挂在长延迟单元接口上，从 Zve64x 子集开始；可与仓库中的 `matrix_accelerator` 一并评估 | 四核配置放不下；需要先有 v1 的资源与性能数据 |
| 其他想法（待补充来源） | 用户提到的“shen”中的想法，来源待确认 | 确认来源后再评估 |
| miss-under-miss | L1D MSHR 增至 2，L2 慢槽相应增加 | 依据 `mshr_full_stall` 等计数器 |
| L2 预取、II=1、更多 bank | 见 D-cache 文档第 6 节 | 依据 v1 压力测试与计数器 |
| 设计来源表 | 每个机制的来源（教科书、Rocket、自有）、改动、理由与数据 | 有第一批计数器数据后编写 |
