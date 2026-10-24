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

控制脚本和证据目录：`records/btb-baseline-20261024/`。结果待本轮执行补充。
