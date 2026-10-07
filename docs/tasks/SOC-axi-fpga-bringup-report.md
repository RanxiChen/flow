# SOC AXI / FPGA bring-up 执行报告

起点：`929333b76e6cc29df2ba4ee125ee48cd8fce5bd9`；本地 `/home/chen/flow-soc`，分支 `feat/v1-soc`。冻结合同保持不变。

## 环境与证据

本工作区缺少未跟踪的 AGENTS.md 与 docs/cross-project/simulation-host.md。只读共享源 `/home/chen/leisure/flow/AGENTS.md` 和 `/home/chen/leisure/flow/docs/cross-project/simulation-host.md`，并读取本仓库 agent.md；主机选择按任务书及共享配置优先 cloud_chen，覆盖旧 agent.md 的 Alan-only 描述。未在主工作区切分支。未修改/停止 CLUSTER 工作区或队列。

2026-10-07 cloud_chen 预检：SSH、环境、Java 11、sbt 1.11.2、Verilator 5.028、Migen/LiteX/LiteDRAM、RISC-V gcc 可用；磁盘余量 121 GiB，内存 available 26 GiB；反向代理 GitHub HTTP 200；CLUSTER 队列正在工作，保留原状。SoC 使用独立目录 `/home/cloud_chen/work/flow-soc-20261007`。

## 1. Chisel AXI 顶层与生成器（实现中）

新增 BreezeClusterAxi，直接引出 mem/mmio，不转换协议；DMA req.valid=0 / rspDown.ready=1。生产版不引出 debug Bundle；调试版启用真实退休 trace，包含 hart0 完整 TracePayload（包括晚写字段）、L1D/L2 事件。几何只由 cfg.mem 推导。

生成入口 single|small、gshare|baseline、mcu|linux、tandem/fpga-debug；DMA/FASE/未知参数报“未支持”。输出 design/build/rtl/axi-cluster/<profile>/<preset>/<privilege>/<tandem|production>/<fpga-debug|cpu>；marker 包含 bus/profile/preset/privilege/tandem/debug/platformSha256 和从 cfg 推导的几何/ID 位宽。

删除/改写测试逐项：

- MemoryBridgeSpec “DMA keeps its line request stable and maps word offset, mask, errors and consecutive beats”：删除，DmaWishboneClient 按合同移除。
- MemoryBridgeSpec “AXI bridge accepts W before AW and holds B and each read beat with ID and LAST”：删除，Axi4WishboneBridge 按合同移除；路由顺序/错误传播将在新 LiteX 路由测试覆盖。
- MemoryBridgeSpec “MMIO arbitration retains owner across independent W/AW and a stalled response”：保留原断言。
- MemSkeletonElabSpec 五个 geometry 的 “BreezeClusterWishbone elaborates with DMA”：改为 BreezeClusterAxi elaboration。
- MemSkeletonElabSpec 五个 geometry 的 “DMA and AXI bridges elaborate”：改为 AXI-Lite arbiter elaboration；被移除的桥与 DMA 不再生成。
- MemSkeletonElabSpec “AXI-Lite bridge and cluster without DMA elaborate”：删除，已由新顶层用例覆盖。
- 新增 ClusterAxiElabSpec：single/small × debug/production 四种生成，校验 AXI 端口与 debug 有无。

删除文件：design/src/main/scala/bus/{Axi4WishboneBridge,DmaWishboneClient}.scala；删除 BreezeCluster.scala 的旧 shell，原 BreezeCluster 不改。新增 top/BreezeClusterAxi.scala，修改 GenerateBreezeCluster.scala。

## 待执行

挂死检测、SoC 路由、LiteX/ILA/仿真包装、直接及集群门槛、全量门槛、两个 BIOS+memtest 冒烟、Alan 三个顺序 Vivado 构建、README/进度更新。未完成项不得视为 PASS。

## 已执行门槛

| SHA | 主机 / cwd | 命令 | 结果 / exit | 日志 |
| --- | --- | --- | --- | --- |
| d3f2309 | cloud_chen / /home/cloud_chen/work/flow-soc-20261007/design | sbt "testOnly flow.memsys.ClusterAxiElabSpec flow.memsys.MemoryBridgeSpec flow.memsys.MemSkeletonElabSpec" | 41/41，0 | /home/cloud_chen/evidence/soc-d3f2309/direct.log（direct.exit） |

## 2. 挂死检测

BreezeHangMonitor 只接收观察输入，没有总线控制输出。debug=true 才实例化。noRetireCycles 为 32 bit 饱和计数；最后退休 PC/指令与 seenRetire 在 Chisel 保留。阈值默认 10,000,000 周期（100 MHz 的 0.1 s），生成环境参数 BREEZE_HANG_CYCLES 可设正整数，不扩展冻结的 positional CLI，marker 记录实际值。

hangReasons 粘滞位：bit0 无退休；bit1..10 mem/mmio 的 AR,R,AW,W,B valid&&!ready；bit11..14 mem read/write、mmio read/write 响应超时。mem read 依 cfg.mem.l2Slots 逐项保存请求年龄，完成最老读不会清掉后续请求的年龄；各写从首个 AW 或 W 接受开始计时，适配现有 L2MemEngine/MMIO arbiter 的单写在途合同。

新增 BreezeHangMonitorSpec：无退休阈值边界与粘滞、10 通道分别停顿、4 类响应分别超时、最老读完成保留下一读年龄、正常流量与短停顿无误报。ILA/LED 将在包装步骤连接。
