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

| c3370bd | cloud_chen / /home/cloud_chen/work/flow-soc-20261007/design | sbt "testOnly flow.memsys.BreezeHangMonitorSpec flow.memsys.ClusterAxiElabSpec flow.memsys.MemoryBridgeSpec flow.memsys.MemSkeletonElabSpec" | 46/46，0 | /home/cloud_chen/evidence/soc-c3370bd/direct.log |

## 3. AXI 路由

BreezeAxiRouter 为 Migen RTL：读/写独立，各全局仅一个 burst 在途。新 AR 必须等待前一 RLAST 被接受，新 AW 必须等待前一 B 被接受；因此包括跨 ID 和跨两个出口的返回严格按接受顺序。W 在 AW 之前背压，AW 锁定目标后所有 W 跟随该目标；R/B 的 id/data/resp/last 透明返回，不能吞错误。四个出口在途探针为 1 bit 计数（0/1）。

地址窗口从 PMA JSON 读取。用 33 bit 末地址检查整个 INCR burst，同一窗口才放行；非法/跨区域/物理地址溢出返回每拍 R DECERR 或收齐 AW.len+1 个 W 后 B DECERR，保留 ID 和背压。ROM 只读属性也参与检查。main_ram burst 保持原 len/size/burst。

aa21693 首轮路由门槛：3/4，exit=1，/home/cloud_chen/evidence/soc-aa21693/router.log。根因为 DECERR 测试驱动 RREADY 保持两拍造成重复消费；修驱动为单拍握手脉冲，原期望与断言保留；失败日志保留。

云端 LiteX 深层导入缺少 litex.build（共享包是无 .git 的拷贝）。不修改共享工具：复制至 /home/cloud_chen/work/flow-soc-deps-20261007/litex，补入 Alan 对应 litex.build；显式覆写本任务 PYTHONPATH。深层 AXI/SoCCore/SDRAMPHYModel 导入通过。

## 4. LiteX 包装

core.py 的 memory_bus 为 AXI4（64 data / 32 addr / marker ID width），仅连接 CPU 内的路由器；memory_buses 只放路由器 DRAM 出口，LiteX add_sdram 自动接自带 AXI 位宽转换与 AXI2Native。periph_buses 是路由器低带宽 AXI 出口和 AXI-Lite MMIO，LiteX 自带 AXI2Wishbone/AXILite2Wishbone。删除 CPU DMA/FASE 产品类与旧 Wishbone 端口，保持 reset 0x10010000、SRAM/CSR/CLINT/PLIC 地址与 Linux 中断连接。L2 bytes 从 marker 读，不再沿用旧 wrapper 的过期几何。

marker 的 bus/profile/preset/privilege/tandem/debug/platformSha256/nCores 逐项严格校验，另检查生成的 ID/slot/几何字段。debug 单核额外连完整退休、L1D/L2 事件、Chisel 留存状态和挂死原因。ILA 深度 4096，输入流水 2，探针映射在构建开始前写入，与本次 .ltx 对应。LED0 接 hang。

KCU105 入口移除 --with-fase/SD/DMA/PCIe/--load，只保留 single/small 产品、debug、100 MHz 和输出/构建开关。不自动在 Alan 运行 sbt，预先使用主机配置选定机器生成的 RTL。

multicore_sim.py 改为与 FPGA 相同的生产 CPU/路由，SDRAMPHYModel DDR4，JSON 精确 ROM/SRAM/main_ram，标准 BIOS 与 64 KiB data/address memtest。保留现有 crt0 次级 hart 停驻。旧 MCU/Linux 的 payload/trace CLI 被新冒烟入口替代，本轮不运行历史 Linux。

包装测试重写逐项：

- test_breeze_cpu_wrapper.py 七个旧 Wishbone/过期几何/legacy fixture 用例替换为五个 AXI product、debug 隔离与完整退休字段、每一冻结 marker 拒绝、端口方向/ID、固定 map 用例。原 reset/ISA/ABI/PLIC/单核中断属性仍检查。
- test_breeze_ila.py 保留非法 hart/tandem 拒绝；旧 Wishbone/DCACHE trace 用例改为完整 AXI/事件/挂死/Chisel 留存字段的被动探针连接和 JSON 位宽/深度检查。
- test_clint_verilog_contract.py / test_plic_verilog_contract.py 的旧 MCU/Linux 条件选择 source-string 检查改为新冒烟对独立 RTL、hart 数、mtime/meip 接线检查；其余 RTL/地址/定向用例检查保留。
- 保留 memory/interrupt/LiteUART 等其他 suite，完整 pytest 门槛继续执行。

3b580a3 完整 LiteX pytest：42/44，exit=1，/home/cloud_chen/evidence/soc-3b580a3/pytest.log。两个问题：DECERR 测试在最后 W 之后才拉低 BREADY，B 已被消费（修驱动提前背压）；router 的动态左移触发仓库 Migen 位宽合同（改用显式 Cat/Array 打包 span，只支持合法 size<=3）。所有原期望、断言、watchdog 保留。

## 后续验证准备

- 97ef8ce：cloud_chen 完整 pytest 44/44，exit 0，/home/cloud_chen/evidence/soc-97ef8ce/pytest.log。
- 3b580a3：直接门槛 46/46、exit 0；集群门槛 12/20、exit 1（L1DL2SystemSpec 12/12，ClusterIsaSpec 8 个失败均为新工作区缺 ISA ELF）。分别见 /home/cloud_chen/evidence/soc-3b580a3/direct.log、cluster.log；原失败保存。
- 97ef8ce：tools/build_riscv_tests.sh 构建 111 个 ELF，3 个 Zacas NOT_BUILT（现有合同支持，未改列表），exit 0；build-isa.log。
- 97ef8ce：三种生产/调试生成成功，exit 0，generate.log；被测 design 源码与 3b580a3 相同。生成包 axi-rtl.tgz SHA256=0d98db37903e5c0f5bb628f1079c11a35e90081eb81e287187632e11135bd7fa，拷贝到 Alan 独立工作区；仅重定位 filelist 的根路径，SV 不改。
- BIOS 预构建首轮 picolibc 发布 wheel 的 newlib/libc 布局不符合 LiteX minimal libc；prepare-single.log exit 1，保留。对齐 Alan 的 pythondata-software-picolibc 源提交 6a13ccce7c575b32c102dd9dc52178505b81fe39 到本任务依赖目录；prepare-single-v2.log exit 0。BIOS ROM 17.86 KiB、SRAM 0.37 KiB，尚非仿真证据。
- 冒烟驱动明确启用 Verilator --assert，用 evidence 私有 shim 引入仓库现有 sim/verilator/cvfpu.vlt，未改任何 RTL assertion；成功必须有真实 UART BIOS 横幅、Memtest OK、litex>，再检查退出尾部无错误。
- 全量 sbt test 已启动；开始 SHA 为 3b580a3，期间只更新了非 design 的 Python/文档，设计/测试源不变。待结束再重跑有 ELF 的集群门槛。
