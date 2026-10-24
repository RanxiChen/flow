# 独立四级 PMP 模块实验

2026-10-24。用户授权实现四级完整 PMP 检查和 CSR 侧预解码，并检验 200 MHz 模块时序。
不接入生产前端，不加入权限缓存，不更改原 PMP/TLB/CSR 实现。

源码：`design/src/main/scala/mmu/experiment/BreezePmpFourStage.scala`、`BreezePmpFourStageHarness.scala`。

## 模块契约

- 普通 PMP，8 条有效规则，原始 pmpaddr 为 54 位、物理请求地址为完整 64 位。
- 一笔查询覆盖 1..128 字节，可非对齐，检查完整范围；有效特权由调用方提供，支持 Fetch/Load/Store。
- CSR 端接收已授权提交的 RV64 `pmpcfg0/pmpcfg2/pmpaddr*` 写入；处理 R/W 合法化、L 和后继锁定 TOR 对地址写入的约束。
- 复用 `BreezePmpDecode.range` 生成上下界、非空标记与边界高位减一。原始与预解码状态同沿更新；保留现有 56 位 PMP 编码覆盖语义。
- 原始 CSR、预解码是配置准备，不算查询级数，但包含在时序测量中。
- P0：7 位低地址/末字节比较，保存末端跨 128 B 块进位及权限；P1：高位 8 位分块比较，包括前驱边界；P2：平衡合并、相交与完整覆盖；P3：最低编号匹配项与最终许可。
- 四个计算级均有末端寄存；若请求在沿 N 接受，响应在沿 N+3 后有效。命中/故障都保持相同延迟；无阻塞、配置不变时 II=1。
- request 使用 ready/valid；response 为 Valid，无 ready，接收方必须每拍接收。
- cancel 或合法 PMP CSR 提交事件清除所有在途 valid，并抑制当沿接受。被取消请求不产生响应。即使写锁定项未改变数值，也保守取消。
- 不同配置不混算：配置变化必须伴随 configChanged。该实验组合已直接连接实际 CSR 提交事件。
- 无 PMP 缓存、页翻译、PMA、Smepmp、超管扩展或生产前端集成。

## 功能与物理验证

功能参考为独立整数区间模型，覆盖优先级、完整覆盖、边界进位、随机重叠、锁定和取消；不复制硬件分块比较树。
Vivado 目标 `xcku040-ffva1156-2-e`、Vivado 2022.2、5.000 ns、0.100 ns uncertainty。
测试壳有真实 BUFGCE、输入寄存器；OOC 虚拟引脚邻近已放置边界单元，IO max/min=0.5/0.2 ns 相对边界寄存器时钟。
内部路径均保持单周期 5 ns，无 false/multicycle path。CSR 预解码与检查流水分组报告；完整 routed setup/hold/脉宽和路由状态须一起审计。
该上下文是独立模块证据，不是整核/板级时序证明。

## 2026-10-24 实测结果

执行主机 Alan；主选 cloud_chen SSH 超时后按共享配置回退。功能与 RTL 源码 SHA `751af023aeea747a90ecd40a472962bd464d49dd`。
功能测试 3/3 通过、0 skip，核对 4,991 笔流水响应，并检查 CSR 预解码、锁定和取消。

选用 `route-r2/route.dcp`，报告在 [records](../../../records/pmp-fourstage-20261024/evidence/route-r2/)，分组复核在 [audit-r2](../../../records/pmp-fourstage-20261024/evidence/audit-r2/result.json)。

| 5 ns routed 检查范围 | 最差 setup slack | 最差 hold slack |
|---|---:|---:|
| 所有内部寄存器路径，含 CSR 与测试壳寄存器 | +0.096 ns | +0.016 ns |
| 终点为四级查询流水寄存器的路径 | +0.431 ns | 见内部 hold 报告 |
| CSR 状态到 CSR 预解码寄存器 | +0.126 ns | 见内部 hold 报告 |
| 整个 OOC 测试壳，含外部 IO | +0.096 ns | −0.170 ns |

内部最慢 setup：测试壳 CSR address[11] 寄存器 → CSR ranges_3_nonempty，数据延迟 4.690 ns（逻辑 2.201、布线 2.489），14 级。
查询流水最慢：p0High[18] → p1Chunks_7_0_2_eq，4.376 ns（逻辑 0.361、布线 4.015），2 级，主要受高位地址扇出/布线影响。
负 hold：输入 address[31] → 测试壳输入寄存器，属于外部 IO 路径。不能用内部通过掩盖整体失败，也不能把它计为四级比较逻辑失败。

路由全部完成（6,949/6,949 可路由网络，0 错误）；脉宽 slack +1.942 ns，0 失败；无未约束内部端点、无无时钟寄存器、无组合环；无 false/multicycle path。
DRC 无错误，保留未设置板级配置电压的 CFGBVS-1 warning；本实验不生成 bitstream。
资源含测试壳：5,725 LUT、3,079 FF、17 SRL（SRL 已包含在 LUT 中）；层级 LUT 有跨层合并，不可直接相加。

结论：**四级查询及 CSR 预解码的内部单周期路径满足 200 MHz，裕量偏小；整个测试壳仍未实现完整时序闭合。**
后续集成必须重新验证真实上游 CSR/地址寄存器、下游接收路径、配置取消与前端时序；当前证据不证明整核或板级 200 MHz。

## 尝试与证据对应

- attempt-01：Scala 编译失败（private Bundle 类型通过 public val 暴露），修正可见性后 attempt-02 全部通过。
- route-r1：综合前直接查询未展开端口导致脚本失败；改为 read_xdc 加载时钟。
- route-r2：综合/布局/布线及主报告完成；分组报告因综合将 checker 重命名为 checker_0 而退出 1。修正报告筛选后，audit-r2 **只重新打开同一 DCP 报告**，退出 0，不改网表或布线。
- route-r3：对 route-r2 执行 aggressive_hold_fix，再布线。工具未找到可优化 hold 网络；内部 setup 降至 +0.005 ns、hold +0.016 ns，整体 IO hold 仍 −0.170 ns。保留较好的 route-r2，不宣称修复成功。
- 后续脚本提交不改变 design 源码；源文件摘要、各次源码及 DCP 对应见 [evidence-binding.json](../../../records/pmp-fourstage-20261024/evidence-binding.json)。DCP 留在 Alan，仓库保存报告、日志和摘要。

控制脚本在 `records/pmp-fourstage-20261024/`。
