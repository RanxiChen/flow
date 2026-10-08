# SOC-2 执行报告

起点：`13e795c0d1d7bd73f11a82b50ef584f890b3fab9`，本地 `/home/chen/leisure/flow`，分支 `feat/pcie-fase-20260920`。冻结文件保持不变。

**当前进度（2026-10-08 09:03 后，UTC+8）**：按用户最新要求先形成进度文档。本轮实现源码为 `fbc7e6e505befd02b9107c5f556963268bcdeefa`；D1/D2/D4 与 D5 构建检查已实现，直接门槛 **28/28**、多核/litmus/fault/ClusterIsa 门槛 **63/63** 均 PASS、exit 0。全量 `sbt test`、LiteX pytest、single/small 冒烟、SoC 产品 RTL 生成和三个 Vivado 构建均**尚未启动**。因此 BRAM 映射、资源下降、HPM 全量回归影响、布局布线时序与 bitstream 均未验证；D5 的构建失败停止条件尚未触发。

Alan 根盘接近满，最后检查约 **1.4 GiB 可用**。本任务的归档精简脚本耗时异常且未有效释放空间，已停止；现有证据保留。没有启动新的仿真或 Vivado。继续执行前先解决本任务输出空间，再重读共享主机配置并预检。

## 实现

首版源码：`09a105e1715318933830bd91e72d466809869fb9`。

| 提交 | 内容 | 文件 |
| --- | --- | --- |
| `09a105e` | D1/D2/D4 与 SRAM 单元测试 | `design/src/main/scala/util/SdpSram.scala`、`design/src/main/scala/l2/L2Home.scala`、`design/src/main/scala/top/BreezeCluster.scala`、`design/src/test/scala/util/SdpSramSpec.scala` |
| `feddfb8` | D5 生成物/综合映射检查 | `fpga/kcu105/build_support.py`、`fpga/kcu105/soc2-bram-gate.tcl` |
| `fbc7e6e` | 修正 LiteX 两次格式化下的 Tcl message-id 花括号保留 | `fpga/kcu105/build_support.py` |

以上实现提交已推送；Alan HEAD 与本地实现 HEAD 同为 `fbc7e6e`。后两提交没有修改 `design/` 源码或测试，故两批已执行仿真的设计内容相同。

- D1：`design/src/main/scala/util/SdpSram.scala`，Chisel 模块封装参数化 `SdpSramBlackBox.sv`。同一时钟、1R1W、输出寄存、字节或整字写，不复位 RAM/输出寄存器；整字模式忽略 mask。Chisel 碰撞断言与现有断言采用相同生成/综合处理。同一份 inline SV 用于 Verilator/Vivado。
- D2：`design/src/main/scala/l2/L2Home.scala`，data 保持 S1 读/S2 字节写；meta/plru 保持 S0 读/S2 用，各以 Mux 将初始化/正常写合成一个写口。meta 将 `s2.meta` 更新一路后整行写。几何、流水级、在途检查、协议未改变。
- D4：`design/src/main/scala/top/BreezeCluster.scala`，先组成前端与三个 D-cache 事件，再对整个 Bundle 做零初始化的 `RegNext`，晚一拍送后端。未修改 `BreezePerformanceCounters` 或其测试。
- 测试：`design/src/test/scala/util/SdpSramSpec.scala`，三个几何（32×64 字节掩码、19×23 整字、1×7 整字），各 1000 拍固定 seed 随机读写，初始化/全地址读、连续同地址写、部分/零 mask、使能与读前沿时序；另有同地址碰撞负测试。读禁用后不依赖输出。

`SdpSram.scala` 中供 Verilator/Vivado 共用的 inline Verilog 摘录；正式 SoC 产品生成物与 Vivado 映射尚待执行后核对：

```verilog
(* ram_style = "block" *) reg [WIDTH-1:0] mem [0:DEPTH-1];
integer i;
always @(posedge clk) begin
  if (wen) begin
    if (GRANULE == WIDTH) mem[waddr] <= wdata;
    else
      for (i = 0; i < WIDTH/GRANULE; i = i + 1)
        if (we[i]) mem[waddr][i*GRANULE +: GRANULE] <= wdata[i*GRANULE +: GRANULE];
  end
  if (ren) rdata <= mem[raddr];
end
```

## 主机与证据

按共享配置预检：cloud_chen `47.111.104.2:22`，`BatchMode=yes / ConnectTimeout=8`，exit 255（Connection timed out），随后使用 Alan `ssh -J clawbot -p 2286 chen@localhost`。Alan 激活 `/home/chen/miniforge3/bin/activate flow`。新工作区 `/home/chen/FUN/flow-soc2-20261007`，证据 `/home/chen/FUN/flow-runs/soc2-09a105e`；未覆盖 `soc-010fa84`、未终止其他任务。

Alan Java 11.0.32.1、Verilator 5.028、sbt launcher 1.11.2（项目 1.9.7）、Vivado 2022.2。SBT 绝对入口 `/home/chen/.local/share/coursier/bin/sbt`；Vivado `/home/chen/Tool/FPGA/Vivado/2022.2/bin/vivado`。代理初检端口未监听；建立本任务 SSH 反向转发后 GitHub HTTP 200，源码与固定递归 submodule 经代理从 GitHub 同步。初始 Alan 约 13–14 GiB 磁盘余量、54 GiB available memory，后续监测任务资源。

RISC-V GCC 13.2.0。Alan LiteX `6d8a38cade2092cb1e7db3e5602e81093aead8f9`，pythondata-software-picolibc `6a13ccce7c575b32c102dd9dc52178505b81fe39`。已有 libevent 2.1.12、json-c 0.17；缺 libpcap 开发文件，在 `/home/chen/FUN/flow-soc2-deps-20261007/sysroot` 私有解包 Ubuntu `libpcap0.8-dev / libpcap0.8t64 1.10.4-4.1ubuntu3.1`，`CPATH/LIBRARY_PATH` 仅覆写本任务；编译链接预检通过，包 hash 见 `soc2-fbc7e6e/sim-c-deps.sha256`。没有系统安装或修改共享依赖。

最初对本任务已经完成的 suite 做完整 `.tgz` 压缩、`tar -tzf` 校验后释放可重建的编译目录，保留 XML、仿真源码/二进制/log、门槛日志和退出码。操作记录 `soc2-{SHA}/sim-archive-log.jsonl`。所有 oracle/测试范围不受影响。

本地证据副本：`/home/chen/leisure/flow/records/soc2-bram-archives/{soc2-09a105e,soc2-fbc7e6e}/`，rsync 已完成、exit 0。其中 **7 个完整原始归档、合计 3,585,630,592 bytes，SHA256 校验 PASS**；校验文件为 `records/soc2-bram-archives/archive-sha256-verification.json`，对照各目录的 `transfer-manifest.json`。这些证据文件不纳入源码提交。

随后尝试在 Alan 生成 `.compact.tgz`，去除可重建的 `.o/.a/.d`、仿真执行文件和 core 文件，保留生成 RTL/C++ 源、构建命令与原始仿真日志；每个完成包有 `.omitted.json` 清单。该脚本耗时异常，2026-10-08 09:03 后已向本任务进程 `3228329` 发 SIGTERM 并确认退出。`compact.log` 最后完成记录为直接三个 suite 和 `cluster-sim-before-full`；**其余精简包未确认完成，不能作为已校验归档引用**。远端原始日志、XML、`.result.json`、`.exit` 保持完整；本地完整归档保留上述七包。未执行原定的远端完整归档副本卸载流程，不能声称空间已释放。

## 门槛

| 要求 | 刺激/检查 | 期望与实现映射 | 当前证据范围 |
| --- | --- | --- | --- |
| D1 同步 1R1W / 字节及整字写 | 固定 seed 随机、读前沿、读禁用、连续同址写后读、碰撞负测试 | 独立数组模型；ren&&wen 同址必须报断言；SdpSram/SdpSramBlackBox | 直接门槛 SRAM 4/4；不等同 FPGA BRAM 证明 |
| D2 L2 时序/协议保持 | L2HomeSpec / L1DL2SystemSpec；多核/litmus/fault/ISA | 原 golden 和协议监视器不变；meta/plru 初始化写口合并、data 字节写保持 | 直接门槛 24/24；四 suite 集成门槛 63/63 PASS |
| D3 推导失败存储清单 | 旧/新 Vivado 8-4767 全日志与层级资源 | 位数、端口、源实例前后对应；可保持行为者换 SDP，不可保持者只报告 | 旧 tiny/debug 清单已核对；新综合待执行 |
| D4 事件整体打一拍 | 集群整束 RegNext；全量含原 HpmSpec | 前端和 3 个 D-cache 事件延迟相同；零复位；计数器本体不改 | 静态连接已核对；全量待执行，没有修改计数精确期望 |

所有仿真仍使用 DUT 断言；系统随机/故障/litmus 的参考模型、expected、深度和 watchdog 均未改。正常事务前进仍依赖现有 AXI/链路测试 agent 的有限响应与背压安排；此处不宣称形式化活性证明。

本地 `python3 tools/frozen_check.py`：OK (8 files)，exit 0。Alan 对应日志 `soc2-09a105e/frozen.log`、`frozen.exit`。

直接门槛：**28/28 PASS，exit 0，140.414 s**，命令 `sbt "testOnly flow.util.SdpSramSpec flow.memsys.L2HomeSpec flow.memsys.L1DL2SystemSpec"`，cwd `/home/chen/FUN/flow-soc2-20261007/design`，证据 `soc2-09a105e/direct.*`。每个后台任务保存 `.command.json`（源 SHA/主机/cwd/实际命令/时间）、`.log`、`.result.json`、`.exit`，SBT 另归档 XML。

`fbc7e6e505befd02b9107c5f556963268bcdeefa` 只新增/修正 FPGA 映射检查，design 源码/测试与 `09a105e` 一致；后续 evidence 为 `/home/chen/FUN/flow-runs/soc2-fbc7e6e`。已预建 111 个 ISA ELF（3 个 Zacas 工具链不支持，原合同列表未改变）和 19 个集群 ELF，各 exit 0；`build-{isa,programs}.*`。

四 suite 门槛：**63/63 PASS，exit 0，1721.966 s**，2026-10-07 23:00:00–23:28:42（UTC+8）。命令 `sbt "testOnly flow.memsys.L1DL2MultiCoreSpec flow.memsys.L1DL2LitmusSpec flow.memsys.L1DL2MultiCoreFaultSpec flow.cluster.ClusterIsaSpec"`，Alan 同一 design cwd；`soc2-fbc7e6e/multicore.{command.json,result.json,log,exit}` 与 `multicore-test-reports/`。

各 suite：多核 21/21（332.411 s），litmus 14/14（753.842 s），fault 20/20（397.375 s），ClusterIsa 8/8；XML failures/errors/skipped=0。原禁止结果、必需覆盖、扫描范围和阈值均保留。

2026-10-08 服务重启后核对：源码与 Alan HEAD 仍为 `fbc7e6e`，全量尚未启动；已完成门槛不重跑。cloud_chen 仍 SSH 超时，Alan 可用；恢复私有代理后 GitHub HTTP 200。磁盘余量先为 2.9 GiB，09:03 约 1.5 GiB，停止精简脚本后约 1.4 GiB；这是资源问题，不是 RTL/断言/golden 失败，也不是 Vivado 容量 DRC。

下表实际 `sbt` 入口均为 `/home/chen/.local/share/coursier/bin/sbt`。`E0=/home/chen/FUN/flow-runs/soc2-09a105e`，`E1=/home/chen/FUN/flow-runs/soc2-fbc7e6e`；根 cwd 为 `/home/chen/FUN/flow-soc2-20261007`。

| SHA | 主机 / cwd | 实际命令 | 结果 / exit | 证据 |
| --- | --- | --- | --- | --- |
| `09a105e` | Alan / 根 | `python3 tools/frozen_check.py` | OK (8 files) / 0 | E0 `frozen.log`、`frozen.exit` |
| `09a105e` | Alan / design | `sbt "testOnly flow.util.SdpSramSpec flow.memsys.L2HomeSpec flow.memsys.L1DL2SystemSpec"` | 28/28 / 0，140.414 s | E0 `direct.*`、`direct-test-reports/` |
| `fbc7e6e` | Alan / 根 | `bash tools/build_riscv_tests.sh` | 111 ELF；3 Zacas NOT_BUILT，原合同允许 / 0 | E1 `build-isa.*` |
| `fbc7e6e` | Alan / 根 | `make -C tests/cluster` | 19 ELF / 0 | E1 `build-programs.*` |
| `fbc7e6e` | Alan / design | `sbt "testOnly flow.memsys.L1DL2MultiCoreSpec flow.memsys.L1DL2LitmusSpec flow.memsys.L1DL2MultiCoreFaultSpec flow.cluster.ClusterIsaSpec"` | 63/63 / 0，1721.966 s | E1 `multicore.*`、`multicore-test-reports/` |

HPM：整个事件束打一拍已经实现，计数器本体及其单元测试未修改；当前没有改写任何集群精确计数期望。已执行集群 ISA 门槛通过，但全量尚未运行，不能把 HPM 全量回归影响写成已闭环。

## D3 存储清单与 D5 检查

只读核对旧 `soc-010fa84/{tiny,tiny-debug}/gateware/vivado.log`：每份恰有两条 8-4767。原始日志/源路径/位数归档在 `soc2-fbc7e6e/d3-before.json`。没有其他 ≥2 Kbit 的 8-4767 存储，也没有本轮需改结构的多口/组合读例外。

| 旧存储 | 位数 | 旧证据 | 本轮处理 | 新综合映射 |
| --- | ---: | --- | --- | --- |
| meta_256x192 | 49,152 | tiny vivado.log:939，debug:1193；双写口无法推导 BRAM；旧 tiny 77,323 LUT / 49,208 FF / 0 BRAM | SdpSram 整行写，初始化/正常写合一 | 待综合 |
| plruArr_256x7 | 1,792（<2 Kbit） | tiny:946，debug:1200；双写口；旧 tiny 3,145 LUT / 1,792 FF / 0 BRAM | D2 明确要求，仍替换为 SdpSram | 待综合 |
| data_2048x256 | 524,288 | 旧层级报告 data_ext 155,491 LUT / 524,345 FF / 0 BRAM；旧日志没有独立 8-4767，但确认拆成寄存器 | D2 字节掩码 SdpSram | 待综合 |

`fpga/kcu105/build_support.py` 对本次冻结的 L2Home.sv 检查 data/meta/plruArr 三个实例类型；`fpga/kcu105/soc2-bram-gate.tcl` 在综合报告和 checkpoint 后、opt/place 前检查三阵列各有 RAMB36/RAMB18，且 L2Home BRAM>0、LUT<100,000（作为拒绝原 29 万 LUT 映射的保守上限，实际值仍报告）。不通过直接 Tcl error，不能进入布局。检查数据保存 `soc2-l2-bram-gate.tsv`；Tcl 也复制进本构建 source-snapshot。提高 8-4767 的日志数量限制，避免 D3 清单被日志限额截断。没有放宽容量 DRC、时序约束或断言。

该 D5 检查目前仅完成代码实现和本地 Python 语法检查，尚未在实际 Vivado 构建中执行。100,000 LUT 是本任务为拒绝原约 29 万 LUT 映射设置的保守检查上限，不是测得的优化结果。

## 尚未执行与后续顺序

| 项目 | 当前状态 / 原因 |
| --- | --- |
| `sbt test` | 未启动。先解决 Alan 输出空间；与 410/410 基线及 SoC 新增用例对照，新增 SRAM 用例为 4 个 |
| `python -m pytest sim/litex -q` | 未启动；排在全量之后 |
| single BIOS 冒烟 | 未启动；完整 64 KiB memtest、原 watchdog 与断言保持 |
| single 生产 / single debug / small 生产 SoC RTL 生成 | 未启动；需重读共享主机配置并预检 |
| tiny 生产 Vivado，100 MHz | 未启动；生成物与综合映射检查后按 D5 执行 |
| tiny-debug Vivado，100 MHz | 未启动；前一构建达到 D5 允许继续条件后执行 |
| small BIOS 冒烟 / small 生产 Vivado，100 MHz | 未启动；第三个构建前跑 small 冒烟，前序构建按 D5 决定能否继续 |
| 新综合 D3 清单、L2/全设计 LUT/FF/BRAM | N/A，尚无本轮综合结果 |
| routed WNS/TNS/WHS、最差 10 路径 | N/A，尚无本轮布局布线结果 |
| bitstream / `.ltx` 的 Alan 路径 | N/A，本轮未产出 |

继续时先核对本地完整归档 hash、远端已完成/未完成精简包及空间，再处理**本任务**可重建输出；其他项目、旧 `soc-010fa84` 和共享依赖不在处理范围。每次仿真/编译/生成前重新读取 `docs/cross-project/simulation-host.md`，先预检 cloud_chen，不可用再 Alan；不沿用本报告的历史地址作为新配置。长任务后台运行并保留退出码，不密集轮询。

尚未定位本轮 RTL 功能失败；也尚不能声称解决了 FPGA BRAM 映射或容量问题。没有形式化、OpenSBI/Linux 仿真或烧板。上板第一步目前等待本轮实际产物；以后产出并核对 tiny-debug 的 bit/ltx 后，由用户执行烧录，检查 UART BIOS/DDR/memtest，ILA 以 `dbg_hang == 1` 触发观察原因位、最后退休 PC 与 AXI 握手。这是后续建议，不是已完成验证。
