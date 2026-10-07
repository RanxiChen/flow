# SOC AXI / FPGA bring-up 执行报告

起点：`929333b76e6cc29df2ba4ee125ee48cd8fce5bd9`；本地 `/home/chen/flow-soc`，分支 `feat/v1-soc`。冻结合同保持不变。

**结束状态（2026-10-07）**：按用户最新指示结束本任务，不启动 small FPGA 构建。tiny 与 tiny-debug 均因容量 DRC 失败，无 bitstream / .ltx；L2 映射为寄存器的问题尚未修复。single/small BIOS 冒烟均 PASS；直接门槛 65/65、集群 20/20、LiteX 50/50。全量原始 410/416、exit 1，六个失败已通过定向验证闭环，不记为全量 PASS。

## 环境与证据

本工作区缺少未跟踪的 AGENTS.md 与 docs/cross-project/simulation-host.md。只读共享源 `/home/chen/leisure/flow/AGENTS.md` 和 `/home/chen/leisure/flow/docs/cross-project/simulation-host.md`，并读取本仓库 agent.md；主机选择按任务书及共享配置优先 cloud_chen，覆盖旧 agent.md 的 Alan-only 描述。未在主工作区切分支。未修改/停止 CLUSTER 工作区或队列。

2026-10-07 cloud_chen 预检：SSH、环境、Java 11、sbt 1.11.2、Verilator 5.028、Migen/LiteX/LiteDRAM、RISC-V gcc 可用；磁盘余量 121 GiB，内存 available 26 GiB；反向代理 GitHub HTTP 200；CLUSTER 队列正在工作，保留原状。SoC 使用独立目录 `/home/cloud_chen/work/flow-soc-20261007`。

## 1. Chisel AXI 顶层与生成器

新增 BreezeClusterAxi，直接引出 mem/mmio，不转换协议；DMA req.valid=0 / rspDown.ready=1。生产版不引出 debug Bundle；调试版启用真实退休 trace，包含 hart0 完整 TracePayload（包括晚写字段）、L1D/L2 事件。几何只由 cfg.mem 推导。

生成入口 single|small、gshare|baseline、mcu|linux、tandem/fpga-debug；DMA/FASE/未知参数报“未支持”。输出 design/build/rtl/axi-cluster/<profile>/<preset>/<privilege>/<tandem|production>/<fpga-debug|cpu>；marker 包含 bus/profile/preset/privilege/tandem/debug/platformSha256 和从 cfg 推导的几何/ID 位宽。

删除/改写测试逐项：

- MemoryBridgeSpec “DMA keeps its line request stable and maps word offset, mask, errors and consecutive beats”：删除，DmaWishboneClient 按合同移除。
- MemoryBridgeSpec “AXI bridge accepts W before AW and holds B and each read beat with ID and LAST”：删除，Axi4WishboneBridge 按合同移除；路由顺序/错误传播由新 LiteX 路由测试覆盖。
- MemoryBridgeSpec “MMIO arbitration retains owner across independent W/AW and a stalled response”：保留原断言。
- MemSkeletonElabSpec “BreezeClusterWishbone elaborates with DMA (default)”：改为 BreezeClusterAxi elaboration。
- MemSkeletonElabSpec “BreezeClusterWishbone elaborates with DMA (l2FourWay)”：同上。
- MemSkeletonElabSpec “BreezeClusterWishbone elaborates with DMA (l1dTwoWay)”：同上。
- MemSkeletonElabSpec “BreezeClusterWishbone elaborates with DMA (singleCore)”：同上。
- MemSkeletonElabSpec “BreezeClusterWishbone elaborates with DMA (stress)”：同上。
- MemSkeletonElabSpec “DMA and AXI bridges elaborate (default)”：改为 AXI-Lite arbiter elaboration；原 arbiter 仍生成，移除的桥与 DMA 不再生成。
- MemSkeletonElabSpec “DMA and AXI bridges elaborate (l2FourWay)”：同上。
- MemSkeletonElabSpec “DMA and AXI bridges elaborate (l1dTwoWay)”：同上。
- MemSkeletonElabSpec “DMA and AXI bridges elaborate (singleCore)”：同上。
- MemSkeletonElabSpec “DMA and AXI bridges elaborate (stress)”：同上。
- MemSkeletonElabSpec “AXI-Lite bridge and cluster without DMA elaborate”：删除，已由新顶层用例覆盖。
- 新增 ClusterAxiElabSpec：single/small × debug/production 四种生成，校验 AXI 端口与 debug 有无。

删除文件：design/src/main/scala/bus/{Axi4WishboneBridge,DmaWishboneClient}.scala；删除 BreezeCluster.scala 的旧 shell，原 BreezeCluster 不改。新增 top/BreezeClusterAxi.scala，修改 GenerateBreezeCluster.scala。

## 已执行门槛

| SHA | 主机 / cwd | 命令 | 结果 / exit | 日志 |
| --- | --- | --- | --- | --- |
| d3f2309 | cloud_chen / /home/cloud_chen/work/flow-soc-20261007/design | sbt "testOnly flow.memsys.ClusterAxiElabSpec flow.memsys.MemoryBridgeSpec flow.memsys.MemSkeletonElabSpec" | 41/41，0 | /home/cloud_chen/evidence/soc-d3f2309/direct.log（direct.exit） |

## 2. 挂死检测

BreezeHangMonitor 只接收观察输入，没有总线控制输出。debug=true 才实例化。noRetireCycles 为 32 bit 饱和计数；最后退休 PC/指令与 seenRetire 在 Chisel 保留。阈值默认 10,000,000 周期（100 MHz 的 0.1 s），生成环境参数 BREEZE_HANG_CYCLES 可设正整数，不扩展冻结的 positional CLI，marker 记录实际值。

hangReasons 粘滞位：bit0 无退休；bit1..10 mem/mmio 的 AR,R,AW,W,B valid&&!ready；bit11..14 mem read/write、mmio read/write 响应超时。mem read 依 cfg.mem.l2Slots 逐项保存请求年龄，完成最老读不会清掉后续请求的年龄；各写从首个 AW 或 W 接受开始计时，适配现有 L2MemEngine/MMIO arbiter 的单写在途合同。

新增 BreezeHangMonitorSpec：无退休阈值边界与粘滞、10 通道分别停顿、4 类响应分别超时、最老读完成保留下一读年龄、正常流量与短停顿无误报。ILA/LED 在包装步骤连接。

c3370bd：同一 cloud_chen/design，`sbt "testOnly flow.memsys.BreezeHangMonitorSpec flow.memsys.ClusterAxiElabSpec flow.memsys.MemoryBridgeSpec flow.memsys.MemSkeletonElabSpec"`，46/46、exit 0，`/home/cloud_chen/evidence/soc-c3370bd/direct.log`。

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

- test_breeze_cpu_wrapper.py `test_fpga_debug_is_isolated_and_rejects_disabled_trace`：改为 `test_debug_is_isolated_and_exports_every_retire_field_and_hang`，对应新 AXI marker 和 Chisel debug 端口。
- `test_tiny_preserves_linux_buses_and_one_hart_interrupts`：并入 `test_production_products_use_axi_and_only_dram_exit_is_direct`；保留 Linux ISA/ABI/PLIC、单核中断和 reset 检查，更新实际 cache 几何与 AXI 拓扑。
- `test_tiny_rejects_mismatched_hart_count`：并入 `test_each_required_marker_mismatch_is_rejected`，扩展为每个必需 marker 的错误拒绝。
- `test_public_product_is_fixed_four_hart_linux`：改为 `test_fixed_public_profiles_and_memory_map`，同时检查 single/small 固定产品。
- `test_instantiation_requires_and_loads_matching_production_rtl`：并入生产 AXI 产品加载检查；另加 `test_axilite_b_and_axi_r_port_directions_and_ids`。
- `test_debug_marker_is_rejected`：并入逐 marker 拒绝检查。
- `test_legacy_mcu_wrapper_keeps_direct_interrupt_wiring`：删除旧 Wishbone MCU fixture，当前产品由固定 Linux privilege/AXI map 用例覆盖；不把历史 MCU payload CLI 记为已验证。
- test_breeze_ila.py `test_invalid_cpu_is_rejected_before_ip_creation` 保留；`test_last_retire_survives_idle_bus_activity` 改为 `test_retained_chisel_debug_and_axi_probes_are_passive_and_map_matches`，覆盖完整 AXI/事件/挂死/Chisel 留存字段的被动探针连接和 JSON 位宽/深度。
- test_clint_verilog_contract.py `test_linux_selects_verilog_clint_and_keeps_legacy_source`、test_plic_verilog_contract.py `test_linux_profile_selects_verilog_plic_and_keeps_legacy_source` 保留名称，旧 MCU/Linux 条件选择 source-string 检查改为新冒烟对独立 RTL、hart 数、mtime/meip 接线检查；其余 RTL/地址/定向用例检查保留。
- 保留 memory/interrupt/LiteUART 等其他 suite，完整 pytest 门槛继续执行。

3b580a3 完整 LiteX pytest：42/44，exit=1，/home/cloud_chen/evidence/soc-3b580a3/pytest.log。两个问题：DECERR 测试在最后 W 之后才拉低 BREADY，B 已被消费（修驱动提前背压）；router 的动态左移触发仓库 Migen 位宽合同（改用显式 Cat/Array 打包 span，只支持合法 size<=3）。所有原期望、断言、watchdog 保留。

## 后续验证准备

- 97ef8ce：cloud_chen 完整 pytest 44/44，exit 0，/home/cloud_chen/evidence/soc-97ef8ce/pytest.log。
- 3b580a3：直接门槛 46/46、exit 0；集群门槛 12/20、exit 1（L1DL2SystemSpec 12/12，ClusterIsaSpec 8 个失败均为新工作区缺 ISA ELF）。分别见 /home/cloud_chen/evidence/soc-3b580a3/direct.log、cluster.log；原失败保存。
- 97ef8ce：tools/build_riscv_tests.sh 构建 111 个 ELF，3 个 Zacas NOT_BUILT（现有合同支持，未改列表），exit 0；build-isa.log。
- 97ef8ce：三种生产/调试生成成功，exit 0，generate.log；被测 design 源码与 3b580a3 相同。生成包 axi-rtl.tgz SHA256=0d98db37903e5c0f5bb628f1079c11a35e90081eb81e287187632e11135bd7fa，拷贝到 Alan 独立工作区；仅重定位 filelist 的根路径，SV 不改。
- BIOS 预构建首轮 picolibc 发布 wheel 的 newlib/libc 布局不符合 LiteX minimal libc；prepare-single.log exit 1，保留。对齐 Alan 的 pythondata-software-picolibc 源提交 6a13ccce7c575b32c102dd9dc52178505b81fe39 到本任务依赖目录；prepare-single-v2.log exit 0。BIOS ROM 17.86 KiB、SRAM 0.37 KiB，尚非仿真证据。
- 冒烟驱动明确启用 Verilator --assert，用 evidence 私有 shim 引入仓库现有 sim/verilator/cvfpu.vlt，未改任何 RTL assertion；成功必须有真实 UART BIOS 横幅、Memtest OK、litex>，再检查退出尾部无错误。
- 全量开始 SHA 为 3b580a3，期间只更新了非 design 的 Python/文档，设计/测试源不变；实际结果与失败项闭环见下文。

地址连线审查补充：LiteX 默认只实例化当前 reset window 的 ROM，而 router 按冻结 map 接受两段 ROM。新增 add_inactive_boot_rom，在当前 Linux reset=0x10010000 的 BIOS ROM 外，给 0x10000000 的未装载 ROM 显式只读零内容后端；不改变复位或 BIOS 内容。两段 ROM 均按 JSON 精确检查，避免合法 PMA ROM 读卡在未译码 Wishbone 上；本轮没有第二个 firmware payload。

全量输入准备补充：首次全量四组 ClusterProgramSpec 因 tests/cluster/build 缺失失败，失败证据保留。72f23ba 的 make -C tests/cluster 构建 19 个 ELF，exit 0，证据 /home/cloud_chen/evidence/soc-72f23ba/build-programs.log；全量结束后单独重跑四组，不能将其原始失败记为通过。

上游测试合同同步：全量同时暴露 BreezePrivilegeSpec 的两个旧 trigger CSR 期望。GitHub 上 CLUSTER 第二轮报告记录其首次全量 cc60ebf 为 408/410，两个失败均为同一旧测试偏差；62ceea9 的定向 suite 为 19/19，最终全量结果见下文。按 C1 合并该主分支修复，提交 b74fe3cffa7e44485e2ed61b1ff89b797306399d，只修改 BreezePrivilegeSpec，未改 RTL/冻结文件。SoC 全量执行中的远端源码保持原样，结束后再同步、归档与重跑。

## 全量结束与失败项闭环

3b580a3 开始的全量实际完成：59 suites，410/416，6 failures、0 errors/aborted/canceled/ignored/pending，exit 1，5010 s。原始 XML 归档 full-test-reports，结构化 full-results.json。仅四组缺少自写 ELF 与两个旧 trigger CSR 期望失败；不能把后续定向通过写成全量通过。

b74fe3c 在 cloud_chen /home/cloud_chen/work/flow-soc-20261007/design：直接四 suite 加 BreezePrivilegeSpec 为 65/65、exit 0；L1DL2SystemSpec/ClusterIsaSpec 为 20/20、exit 0；ClusterProgramSpec 为 4/4 测试组、25/25 次程序执行、exit 0。证据 /home/cloud_chen/evidence/soc-b74fe3c/{direct,cluster,program}.log/.exit 与对应 reports，gate-summary.json 按实际 suite 筛选累计 XML。

## BIOS 冒烟驱动修复与 single 结果

预检发现 LiteX C 仿真开发依赖缺失。在本任务依赖目录 sysroot 解包 libevent 2.1.12、json-c 0.17、libpcap 1.10.4 的 Ubuntu deb，设置本任务 CPATH/LIBRARY_PATH/LD_LIBRARY_PATH，编译链接预检 exit 0；没有系统安装或修改共享环境。包 hash 保存 soc-b74fe3c/sim-c-deps.sha256。

b74fe3c 的首次 single 406.04 s 提前结束，runner FAIL：生成 soc.h 定义 CONFIG_BIOS_NO_BUILD_TIME，日期行不可能出现，原 oracle 条件错误。73d334d 改为实际 UART 版权横幅。e77f0c0 模型启用 O3 并绑定开始 SHA；其 single 已输出完整 BIOS、64 KiB Memtest OK 和彩色提示符，但原始字符串检查漏掉 litex 与 > 之间的 ANSI 序列，306.55 s 提前结束、runner FAIL，原日志保留。cfd0416 修正 ANSI 解析，原始 UART 保留，增加五个正/负回归用例；不是修改 memtest 期望或关闭断言。

cfd0416 完整 LiteX pytest 49/49、exit 0，soc-cfd0416/pytest.log。single 冒烟 PASS、167.7368 s、runner exit 0，子进程在成功后由驱动终止（child exit -15）；证据 /home/cloud_chen/evidence/soc-cfd0416/smoke-single/{uart-build.log,result.json}。真实 UART 有 BIOS 版权横幅、Initializing SDRAM @0x80000000、Memtest at 0x80000000 (64.0KiB)、Memtest OK、litex>；明确 --assert，尾部无断言错误。small 同 SHA 的实际结果见下一段；每次仍以 1800 s 为上限。

cfd0416 的 small 实际 PASS，1658.1376 s，runner exit 0，Memtest OK、litex> 与无断言错误；soc-cfd0416/smoke-small/{uart-build.log,result.json}。CLUSTER 第二轮最终报告已发布于主分支 d36d60e4b14d58a12bfda6365c654622cb2d2c69：被测 62ceea9、57 suites、410/410、exit 0，5066 s；SoC 原始全量的六个失败已按上面的定向门槛闭环。与基线相比移除 3 个合同授权的 Wishbone 用例、新增 9 个 Chisel 用例，所以总数 410→416；没有未闭环的新增失败，仍不把本分支原始全量标为 PASS。

## Vivado 首轮失败与修复后验证

cfd0416 tiny 生产版首轮 exit 1：Vivado Synth 8-5797，/home/chen/FUN/flow-runs/soc-cfd0416/tiny/gateware/xilinx_kcu105.v:32793 的 8192×64 全零 boot_rom 推导失败；vivado.log:1312 和 tiny.log 保留。未进入布局布线，无 WNS/TNS/WHS、利用率报告、bit/ltx。报错属于本任务包装后端，不是核/L1D/L2/MMU bug。

010fa842ec2004a8b750508da5ceb5c1adcb4ab4 将未装载的 ROM 改为 ZeroBootRom：Wishbone 读返回零并完成，写返回错误；窗口/mode/cacheable 从同一 JSON 推导，路由器对 ROM 写仍在本地返回 DECERR。没有改变 ROM 内容、内存几何或测试深度。新增直接读首/末地址、写拒绝、空闲不响应测试。完整 pytest 50/50、exit 0，证据 /home/cloud_chen/evidence/soc-010fa84/pytest.log。

同 SHA 三套 RTL 生成 exit 0，生成包 SHA256 b79cf701903448ec9f2184c4d39058514f56a936ff07c924ce158177105d143b；soc-010fa84/generate.log 与 axi-rtl.sha256。传到 Alan 后仅重定位 filelist 根路径。single 冒烟再次 PASS、152.6342 s、runner exit 0，soc-010fa84/smoke-single/result.json；small 再次 PASS、1596.6586 s、runner exit 0、child exit -15，soc-010fa84/smoke-small/result.json；最终两个 profile 均未超过 1800 s，assertions_enabled=true、memtest_bytes=65536。Alan tiny 重跑与 tiny-debug 均综合成功、随后在布局容量 DRC 失败，具体结果见下文；small FPGA 按用户指示不启动。

## 最终软件门槛索引

下表 cloud cwd 根为 `/home/cloud_chen/work/flow-soc-20261007`，evidence 根为 `/home/cloud_chen/evidence`。`design` 命令均从该子目录运行，其余从根目录运行。每次批次前重读共享主机配置并预检；`.exit`、SHA/cwd、UART 原始日志保留在独立 evidence。

| SHA | cwd 后缀 | 实际命令 | 结果 / exit | evidence 后缀 |
| --- | --- | --- | --- | --- |
| 3b580a39986b25f6567cafc6761ab060d98f2a69 | design | `sbt test` | 59 suites，410/416，1；失败项下述定向闭环 | `soc-3b580a3/full.log`、`full-results.json`、`full-test-reports/` |
| b74fe3cffa7e44485e2ed61b1ff89b797306399d | design | `sbt "testOnly flow.memsys.BreezeHangMonitorSpec flow.memsys.ClusterAxiElabSpec flow.memsys.MemoryBridgeSpec flow.memsys.MemSkeletonElabSpec flow.core.BreezePrivilegeSpec"` | 65/65，0 | `soc-b74fe3c/direct.log` |
| b74fe3cffa7e44485e2ed61b1ff89b797306399d | design | `sbt "testOnly flow.memsys.L1DL2SystemSpec flow.cluster.ClusterIsaSpec"` | 20/20，0 | `soc-b74fe3c/cluster.log` |
| b74fe3cffa7e44485e2ed61b1ff89b797306399d | design | `sbt "testOnly flow.cluster.ClusterProgramSpec"` | 4/4 组，25/25 次，0 | `soc-b74fe3c/program.log` |
| 010fa842ec2004a8b750508da5ceb5c1adcb4ab4 | 根 | `python -m pytest sim/litex -q` | 50/50，0 | `soc-010fa84/pytest.log` |
| 010fa842ec2004a8b750508da5ceb5c1adcb4ab4 | 根 | `python3 tools/frozen_check.py` | OK (8 files)，0 | `soc-010fa84/frozen.log` |
| 010fa842ec2004a8b750508da5ceb5c1adcb4ab4 | design | `sbt "runMain flow.top.GenerateBreezeCluster single gshare linux" "runMain flow.top.GenerateBreezeCluster small gshare linux" "runMain flow.top.GenerateBreezeCluster single gshare linux tandem fpga-debug"` | 三种生成，0 | `soc-010fa84/generate.log` |
| 010fa842ec2004a8b750508da5ceb5c1adcb4ab4 | 根 | `python -u sim/litex/run_soc_smoke.py --profile single --evidence-dir /home/cloud_chen/evidence/soc-010fa84/smoke-single` | PASS，152.6342 s，0 | `soc-010fa84/smoke-single/{uart-build.log,result.json}` |
| 010fa842ec2004a8b750508da5ceb5c1adcb4ab4 | 根 | `python -u sim/litex/run_soc_smoke.py --profile small --evidence-dir /home/cloud_chen/evidence/soc-010fa84/smoke-small` | PASS，1596.6586 s，0 | `soc-010fa84/smoke-small/{uart-build.log,result.json}` |

b74fe3c 至 010fa84 没有改动任何 `design/` 文件；Python/包装修复的门槛是完整 pytest 和两个真实 BIOS 冒烟。该段 `design/src/main` 的 Git tree 为 `aedc7713292f33b8312e1663c73affb53f7bea52`。f69b4e6 仅改生成器参数数量错误的信息，按规则另跑直接/集群门槛并验证生成内容等价，见后续记录。全量只运行一次，原始 FAIL 如实保留。CLUSTER 主分支公开报告对应提交 `d36d60e4b14d58a12bfda6365c654622cb2d2c69`，其被测源 `62ceea9e00c90edeaf9dc43ba9c7e2f23f5bb103` 的 410/410 基线通过；本任务没有未闭环的新失败。

两个最终 UART 原始日志均包含以下输出（提示符匹配时去除 ANSI，原始文件不改）：

```text
Build your hardware, easily!
(c) Copyright 2012-2026 Enjoy-Digital
(c) Copyright 2007-2015 M-Labs
Initializing SDRAM @0x80000000...
Memtest at 0x80000000 (64.0KiB)...
Write: 0x80000000-0x80010000 64.0KiB
Read:  0x80000000-0x80010000 64.0KiB
Memtest OK
litex>
```

BIOS ELF `_start=0x10010000`、`_fstack=0x11010000` 已检查，使用 JSON 的 64 KiB linux_boot_rom / SRAM；次级 hart 在栈初始化前停驻。Verilator 私有 shim 保证 `--assert`，C++ 编译末项 `-O3`，保留现有文件级 CVFPU 配置。runner 的 1800 秒上限包含编译和运行；PASS 后只终止自有子进程组，`child_exit=-15` 是清理，`runner_exit=0` 才是门槛结果。

实现文件按工作项：

- §1：`design/src/main/scala/top/{BreezeCluster,BreezeClusterAxi,GenerateBreezeCluster}.scala`；移除 `bus/{Axi4WishboneBridge,DmaWishboneClient}.scala`；测试 `design/src/test/scala/memsys/{ClusterAxiElabSpec,MemoryBridgeSpec,MemSkeletonElabSpec}.scala`。
- §2：`design/src/main/scala/top/BreezeHangMonitor.scala`、`design/src/test/scala/memsys/BreezeHangMonitorSpec.scala`。
- §3：`litex_wrapper/flow/axi_router.py`、`sim/litex/test_breeze_axi_router.py`（含 ZeroBootRom 定向测试）。
- §4：`litex_wrapper/flow/{core,cluster,ila}.py`、`fpga/kcu105/{target,build_support}.py`、`sim/litex/{multicore_sim,test_breeze_cpu_wrapper,test_breeze_ila,test_clint_verilog_contract,test_plic_verilog_contract}.py`。旧 `flow/cluster.py` 仅保留 AXI 导入兼容，历史 payload CLI 未验证。
- §5：`sim/litex/{run_soc_smoke,test_soc_smoke}.py`。
- 上游合同同步：`design/src/test/scala/core/BreezePrivilegeSpec.scala`，只 merge 主分支既有修复，无核 RTL 改动。
- §7：本报告已更新；`fpga/kcu105/README.md`、`docs/v1-impl-plan.md` 未更新。用户在 tiny-debug 失败后明确要求只补报告、提交推送并结束，未进入原定三次构建后的文档更新阶段。

工具：cloud Java 11.0.32.1、项目 sbt 1.9.7（launcher 1.11.2）、Verilator 5.028、RISC-V GCC 13.2、g++ 13.3；Alan Vivado 2022.2。cloud 的 LiteX/pythondata/C 库缺口均修在本任务 `/home/cloud_chen/work/flow-soc-deps-20261007`，没有更改共享安装。混合的 cloud LiteX 拷贝不能标为单一完整 Git SHA；补入的 Alan LiteX 根提交为 `6d8a38cade2092cb1e7db3e5602e81093aead8f9`，picolibc 源提交为 `6a13ccce7c575b32c102dd9dc52178505b81fe39`，C 依赖包 hash 见 `soc-b74fe3c/sim-c-deps.sha256`。

### tiny 生产版最终退出与综合诊断

010fa84 tiny：Alan `/home/chen/FUN/flow-soc-20261007`，命令 `python -u fpga/kcu105/target.py --cpu-type breeze-tiny --build --output-dir /home/chen/FUN/flow-runs/soc-010fa84/tiny`；21:18:00–21:54:56，exit 1，日志 `/home/chen/FUN/flow-runs/soc-010fa84/tiny.log`。Synth 成功；`place_design` 前容量 DRC UTLZ-1 拒绝布局。优化后需要 317,504 LUT、623,834 FF，器件仅 242,400 LUT、484,800 FF；未绕过 DRC。没有 routed WNS/TNS/WHS、bitstream 或 .ltx。

综合报告（不是布局布线/PPA 结论）：WNS=-8.767 ns、TNS=-188173.219 ns、WHS=-0.252 ns；LUT 340,831（140.61%）、FF 623,834（128.68%）、BRAM tile 56.5（9.42%，52 RAMB36+9 RAMB18）、DSP 27（1.41%）。原始报告在 `tiny/gateware/xilinx_kcu105_{timing,utilization,utilization_hierarchical}_synth.rpt`。

层级报告 L2Home 为 290,909 LUT / 579,074 FF / 0 BRAM；Vivado 对生成的 `data_2048x256.sv` 等给出 Synth 8-4767，数组溶解为寄存器。原始 L2 使用 SyncReadMem，但实际生成的读写结构未成功映射 BRAM。该资源问题属于已有内存 RTL 的 FPGA 映射边界，本任务不改内存时序/几何去适配。

只读重开 `xilinx_kcu105_synth.dcp` 补出 `tiny/diagnostic-worst-10-synth.rpt`，`diagnostic-synth.exit=0`。最差十条综合 setup 路径全部在既有核内；共同起点 `BreezeClusterAxi/cluster/l1d/internal2_req_core_size_reg[1]/C`：

| 排名 | 终点（前缀 BreezeClusterAxi/cluster/） | slack ns |
| --- | --- | ---: |
| 1 | backend/csrFile/performance/pending_0_reg[0]/D | -8.767 |
| 2 | backend/csrFile/performance/pending_1_reg[0]/D | -8.767 |
| 3 | backend/csrFile/performance/pending_2_reg[0]/D | -8.767 |
| 4 | backend/csrFile/performance/pending_3_reg[0]/D | -8.767 |
| 5 | backend/csrFile/performance/pending_4_reg[0]/D | -8.767 |
| 6 | backend/csrFile/performance/pending_5_reg[0]/D | -8.767 |
| 7 | backend/csrFile/performance/pending_6_reg[0]/D | -8.767 |
| 8 | backend/csrFile/performance/pending_7_reg[0]/D | -8.767 |
| 9 | backend/exFp_dstFmt_reg[0]/CE | -7.900 |
| 10 | backend/exFp_dstFmt_reg[1]/CE | -7.900 |

没有将综合估计写成上板可用时序；路由器/挂死/包装不在该最差十条中，核内时序按 §6 只报告。

### 生成器拒绝信息补齐

最后复查发现传入超过 5 个位置参数（例如三个合法参数 + tandem + fpga-debug + fase）时，最先触发的 usage 错误缺少合同要求的“未支持”。`f69b4e6c5bd1c39b56de6a4337b290c34e082880` 仅补齐该条错误信息，不改参数解析/生成逻辑。cloud_chen 再次预检后同步；直接门槛 65/65、exit 0，`/home/cloud_chen/evidence/soc-f69b4e6/direct.log`，命令与上表五 suite 相同。集群门槛 20/20、exit 0，`soc-f69b4e6/cluster.log`；三种生成 exit 0，`generate.log`。226 个 SV/filelist/marker 文件前后 SHA256 全部相同，`rtl-before.json`、`rtl-after.json`、`rtl-equivalence.json`（changed=[]）。`sbt "runMain flow.top.GenerateBreezeCluster single gshare linux dma"` 和 `sbt "runMain flow.top.GenerateBreezeCluster single gshare linux tandem fpga-debug fase"` 都 exit 1 并含“未支持”，是预期的拒绝 PASS，见 `reject-{dma,fase-oversized}.log/.exit`、`gate-exits.json`。因此 010fa84 的 BIOS/Vivado 输入与 f69b4e6 对应生成内容完全一致；没有再次运行全量或延长冒烟。已运行的 Vivado 使用 010fa84 独立输入快照，未中断或混用生成源。

## tiny-debug 结果与用户指定结束

源码 SHA：`010fa842ec2004a8b750508da5ceb5c1adcb4ab4`；实际主机 Alan，cwd `/home/chen/FUN/flow-soc-20261007`。最后实现源码 `f69b4e6c5bd1c39b56de6a4337b290c34e082880` 仅补齐生成器拒绝信息，生成输入逐文件等价证据见上一节。

```sh
python -u fpga/kcu105/target.py --cpu-type breeze-tiny --debug --build --output-dir /home/chen/FUN/flow-runs/soc-010fa84/tiny-debug
```

开始 `2026-10-07T21:56:56.208595+08:00`，结束 `2026-10-07T22:38:46.024266+08:00`，exit **1**。证据：

- `/home/chen/FUN/flow-runs/soc-010fa84/tiny-debug.log`，容量 DRC 在 5565–5569 行、`place_design` 失败在 5577 行。
- `/home/chen/FUN/flow-runs/soc-010fa84/tiny-debug.{start,end,exit,command.json}`。
- `/home/chen/FUN/flow-runs/soc-010fa84/tiny-debug/gateware/vivado.log`、`xilinx_kcu105_synth.dcp`、`xilinx_kcu105_timing_synth.rpt`、`xilinx_kcu105_utilization_synth.rpt`、`xilinx_kcu105_utilization_hierarchical_synth.rpt`。
- `/home/chen/FUN/flow-runs/soc-010fa84/tiny-debug/ila-probes.json`：实际 124 探针、1253 bit、4096 深度、2 级输入流水、100 MHz。由于 .ltx 未产出，未完成与物理探针文件的匹配验证。

综合成功，随后 `place_design` 前 UTLZ-1 拒绝布局：优化后 **328,957 LUT / 639,617 FF**，超过器件的 242,400 LUT / 484,800 FF，和 tiny 生产版属于同一容量问题。未禁用容量 DRC；未运行布局/布线成功流程；**没有 bitstream 或 .ltx**，因此没有可提供的本次烧录产物路径。

| 产品 | 100 MHz 实际结果 | 综合 WNS / TNS / WHS (ns) | 综合 LUT / FF | 综合 BRAM tile / DSP | 优化后容量 DRC LUT / FF |
| --- | --- | --- | --- | --- | --- |
| tiny | exit 1，容量 DRC | -8.767 / -188173.219 / -0.252 | 340,831 / 623,834 | 56.5 / 27 | 317,504 / 623,834 |
| tiny-debug | exit 1，容量 DRC | -9.000 / -185842.656 / -0.252 | 351,224 / 638,918 | 196.5 / 27 | 328,957 / 639,617 |
| small FPGA | **未运行，用户明确指示不启动** | N/A | N/A | N/A | N/A |

上述时序和综合利用率不是 routed/PPA 结论；两个构建的 routed WNS/TNS/WHS 全部 N/A。debug 综合利用率为 LUT 144.89%、FF 131.79%、BRAM tile 32.75%（192 RAMB36 + 9 RAMB18）、DSP 1.41%。综合与优化后 FF/LUT 数量属于不同阶段，不能混称同一份利用率报告。

debug 层级报告中 `L2Home` 为 292,847 LUT / 579,143 FF / 0 BRAM；L2 数组映射问题仍未解决。默认综合 timing summary 的最差 setup 路径为 `BreezeClusterAxi/cluster/l1d/internal2_req_src_reg[2]/C` → `BreezeClusterAxi/cluster/backend/csrFile/performance/pending_7_reg[0]/D`，slack=-9.000 ns，属于既有核内路径。tiny 的独立十路径诊断已保存；debug 独立十路径报告未追加生成，按用户要求在补报告后结束。

本轮修复的 ZeroBootRom 是 SoC 未装载 ROM 后端的另一处综合错误，**不是 L2 BRAM 映射修复**。没有新定位并修复核/L1D/L2/MMU 的功能错误；两个旧 CSR 测试偏差采用主分支既有修复合并。L2 的 FPGA 映射/容量阻塞仍待后续单独处理，不能由仿真 PASS 推断 FPGA 可用。

按用户最新指示结束：不启动 small FPGA，不继续 L2 修复或 Vivado 重跑；原任务的三构建/bitstream 门槛未达成。small BIOS 冒烟已经 PASS，与 small FPGA 未运行是两件事。README/计划第 3 节未更新，原因如上；未做形式化、OpenSBI/Linux 仿真、烧板，未动主工作区分支、CLUSTER 工作区和后台队列。

上板第一步建议目前受产物缺失阻塞：本次没有可先烧的 bit。以后解决映射问题并重新产出调试 bit/.ltx 后，再由用户烧录同次 tiny-debug 产物，UART 115200 8N1 检查 BIOS 横幅、DDR 初始化和 memtest；ILA 首设 `dbg_hang == 1`，保留触发前窗口，查看原因位、最后退休 PC 和 AXI 五通道握手。这不是本次已完成的上板验证。
