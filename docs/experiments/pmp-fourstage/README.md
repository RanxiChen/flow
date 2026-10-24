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

执行结果待本轮运行后补充；控制脚本在 `records/pmp-fourstage-20261024/`。
