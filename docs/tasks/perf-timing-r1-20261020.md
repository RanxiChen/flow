# 单核 perf 时序隔离 R1

2026-10-20用户授权：保留perf事件和100MHz，先隔离观察采集及iTLB PLRU更新，再仿真并启动独立Vivado后台构建。前轮678b696+perf overlay：routed WNS -0.881ns，最差carryPc→iTLB PLRU CE，多条观察寄存器终点也失败。恢复快照见records/perf-timing-r1-20261020/before.tgz及before-tracked.patch。

## 行为合同

- 仅生产pipelinedFetchKill的iTLB延后PLRU物理命中写回。请求/响应拍数、同拍kill、PTW、权限和sfence语义不变；dTLB与默认模块模式不变。
- 锁存一次有效命中的set/way/基础页或超页；下一拍写物理PLRU。替换查询从含pending touch的逻辑视图读取；连续同set命中按顺序应用。refill保持原同拍优先级，同set refill覆盖同拍hit touch；下一拍kill不撤销上一拍已成立的touch。sfence只按原规则失效映射，不由延迟touch重新置valid。
- I-cache观察采集整包（demand/install/fence、refill控制、PA/PC/satp/privilege/原时间戳）先无条件寄存，再局部计算；不驱动功能cache/PTW/替换控制，不以晚到kill使能宽计数/地址寄存器。
- 前端其他观察、MMU/L2与后端本地观察同步延迟一拍，来源权限标签同拍随行；legacy HPM及可编程cpu_cycles/retired_instructions保持原语义。统计输出多一拍，延迟总数/原始trace时间戳和事件编号保持。
- 不改100MHz、器件、约束、Vivado默认策略、prefetch或F2功能流水级。

## 验证追踪

| ID | 检查 | 状态 |
|---|---|---|
| R01 | 基础/超页PLRU连续命中、不同set、pending旁路、refill优先、kill与sfence | 通过 |
| R02 | 采集后refill开始/结束延迟、取消/error、trace owner/时间戳、locality/fence同拍 | 通过 |
| R03 | 原前端、MMU、观察、后端统计及PMU模式/溢出回归 | 通过 |
| R04 | 单核scalar/多核与RVV兼容系统ELF；真实OpenSBI采样 | 63次原ELF及真实固件通过 |
| R05 | 同配置新Vivado独立后台构建，完整routed timing门禁 | 通过；bitstream已生成 |

编译/仿真前按共享主机配置重新预检；Vivado在Alan。源码用基线SHA+逐文件overlay摘要绑定，不用旧仿真结果证明新RTL通过。用户要求Vivado启动后后台运行，后续再查询。

## 最终执行结果（2026-10-21核验）

执行主机Alan，主工作区`/home/chen/FUN/flow-perf-timing-r1-20261020-678b696`；日志根`/home/chen/FUN/flow-runs/perf-timing-r1-20261020-678b696`。基线`678b6961437b6e0e7a626f2f30c16f697aec6054`加本次perf/R1工作区快照，360个冻结输入见[inputs-manifest.json](../../records/perf-timing-r1-20261020/inputs-manifest.json)。该清单包含执行前版本的本文；本文验收状态在执行后更新，不能把旧HEAD说成实现提交。

- 模块/前端/PMU/系统：20个suite、124个不同测试通过，含重复复验共157次执行；failure/error/skip均0。63次原系统ELF周期与前轮完全一致。[tests-result.json](../../records/perf-timing-r1-20261020/tests-result.json)按suite+case去重。
- 独立真实OpenSBI1.9固件：1个额外测试通过，cycles/instructions/raw I-cache miss各6次采样与停止/重装，共18次；6,486,240周期、4,823,022退休，与前轮一致。合计125个不同测试通过。原始XML为日志工作区对应源码`design/target/test-reports/TEST-flow.cluster.BreezePmuSbiSpec.xml`，日志`integration.txt`有四个PASS标记，`integration.exit=0`。
- RTL生成：`runMain flow.top.GenerateBreezeCluster single coherent-dma`。Vivado2022.2，KCU105 `xcku040-ffva1156-2-e`，100MHz，单核Linux权限、rvv=false、debug=false、coherentDma=true、native SD；默认实现策略和时序约束保持。
- 2026-10-20 22:03:05 +08:00完成，`fpga.exit=0`。完整routed WNS **+0.002ns**（前轮−0.881ns）、TNS=0、setup违例0；WHS=+0.030ns、WPWS=+0.103ns，hold/pulse违例0。余量仅2ps。完整时序和真实固件复验均通过后才写bitstream。
- 当前最差setup：`frontend/predictor/btb/ram_1_0_ext/_R0_addr_d0_reg_rep[1]/C` → `frontend/predictor/pht/available_1_reg/D`（共同前缀`BreezeClusterAxi/cluster/`），数据延迟9.827ns、23级逻辑。原carryPc→iTLB PLRU CE已不再最差。
- 产物为日志根`single/gateware/xilinx_kcu105.bit`，16,007,026 bytes；SHA256 `0e35af7db0eb1072613914ea22c054a01f055ece70b9fe92c62efaa7b3c49789`。源配置见[fpga-job.json](../../records/perf-timing-r1-20261020/fpga-job.json)，最终摘要见[status.json](../../records/perf-timing-r1-20261020/status.json)；其中提交状态描述构建发生时的快照。

完整报告、XML、输入归档和bitstream保留在上述Alan目录及本地同名records目录；本次源码提交仅附小型证据索引。尚未烧板、写SD或运行Linux-on-Breeze的`perf record`；未实现prefetch、stream buffer或victim cache。
