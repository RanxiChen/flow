# SOC-2 执行报告

起点：`13e795c0d1d7bd73f11a82b50ef584f890b3fab9`，本地 `/home/chen/leisure/flow`，分支 `feat/pcie-fase-20260920`。冻结文件保持不变。

**当前进度（2026-10-08 恢复执行，UTC+8）**：用户授权恢复 SOC-2 执行。源码锁定 `bd48477cce9e7747a37b5c9dfe6708ea35d5db94`，其 `design/fpga/sim/tests/config` 与已验收定向门槛的 `fbc7e6e` 无差异。直接门槛 **28/28**、多核/litmus/fault/ClusterIsa 门槛 **63/63** 的既有证据保留，不重复运行。新云机上的全量 `sbt test` 已完成 **60 suites、420/420 PASS、exit 0**，LiteX pytest **50/50 PASS、exit 0**；三个产品 RTL 生成均 exit 0，single BIOS 冒烟 **PASS、159.052 s**。Alan 上 tiny 生产版完成布局布线并生成 bitstream，exit 0、完整 wall time 3631.693 s；100 MHz routed WNS=-6.112 ns，时序未达标。L2 三阵列 BRAM 映射与容量门槛通过；用户于 13:28 要求停止后台 Vivado，tiny-debug 已中止（exit -15，2582.804 s），后续 small 冒烟和构建取消。

恢复时重新读取共享配置：首选云机已更新为 `cloud_chen@47.96.71.231:22`，SSH/Flow 环境可用，约 76 GiB 磁盘、29 GiB available memory。建立本任务 loopback 反向代理后 GitHub HTTP 200。独立工作区 `/home/cloud_chen/work/flow-soc2-20261008`，证据 `/home/cloud_chen/evidence/soc2-bd48477-resume-20261008`，递归子模块锁定仓库 SHA。冻结检查 OK (8 files)、ISA 构建 111 ELF（原合同允许的 3 个 Zacas NOT_BUILT）、自写程序构建 19 ELF，均 exit 0；全量于 10:05:20 启动。私有 LiteX/picolibc/C 依赖复用已有 `flow-soc-deps-20261007`，仅本任务覆写环境；没有修改共享依赖。Alan 已重新预检，磁盘恢复至约 70 GiB、58 GiB available memory，Vivado 2022.2 可用，未启动构建。

历史暂停原因（2026-10-08 09:03 后）：Alan 根盘接近满，当时约 **1.4 GiB 可用**。本任务的归档精简脚本耗时异常且未有效释放空间，已停止；现有证据保留。本轮空间阻塞已解除，未继续旧精简流程。

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

`SdpSram.scala` 中供 Verilator/Vivado 共用的 inline Verilog 摘录；三个正式 SoC 产品生成物已核对，tiny Vivado 映射已通过：

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
| D3 推导失败存储清单 | 旧/新 Vivado 8-4767 全日志与层级资源 | 位数、端口、源实例前后对应；可保持行为者换 SDP，不可保持者只报告 | 旧 tiny/debug 清单已核对；新 tiny/debug 已产生的综合日志均无 8-4767；small 未执行 |
| D4 事件整体打一拍 | 集群整束 RegNext；全量含原 HpmSpec | 前端和 3 个 D-cache 事件延迟相同；零复位；计数器本体不改 | 静态连接已核对；全量 420/420 PASS，没有修改计数精确期望 |

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

恢复执行证据：`E2=/home/cloud_chen/evidence/soc2-bd48477-resume-20261008`，云机根 cwd `/home/cloud_chen/work/flow-soc2-20261008`；所有命令绑定 `bd48477`，tracked 状态为空。`dependencies.json` 保存实际 Python 包路径/源码哈希与 Java/Verilator/GCC 版本，`simulation-host.md` 保存恢复时共享配置快照。LiteX、LiteDRAM、litex-boards Python 源码树哈希与 Alan 一致；两端 Python/Migen/picolibc 包环境有差异，分别记录，不声明完全相同环境。

| 主机 / cwd | 实际命令 | 结果 / exit | 证据 |
| --- | --- | --- | --- |
| cloud_chen / 根 | `python tools/frozen_check.py` | OK (8 files) / 0 | E2 `frozen.*` |
| cloud_chen / 根 | `bash tools/build_riscv_tests.sh` | 111 ELF，3 Zacas NOT_BUILT / 0 | E2 `build-isa.*` |
| cloud_chen / 根 | `make -C tests/cluster` | 19 ELF / 0 | E2 `build-programs.*` |
| cloud_chen / design | `/home/cloud_chen/tools/sbt-1.11.2/bin/sbt test` | 60 suites，420/420 / 0，5477.501 s（wrapper） | E2 `full.*`、`full-test-reports/`、`full-summary.json` |
| cloud_chen / 根 | `python tools/frozen_check.py` | 全量后 OK (8 files) / 0 | E2 `frozen-after-full.*` |
| cloud_chen / 根 | `python -m pytest sim/litex -q` | 50/50 / 0，pytest 3.47 s | E2 `pytest.*` |
| cloud_chen / design | `sbt "runMain flow.top.GenerateBreezeCluster single gshare linux" "runMain flow.top.GenerateBreezeCluster small gshare linux" "runMain flow.top.GenerateBreezeCluster single gshare linux tandem fpga-debug"` | 三种生成 / 0，13.816 s | E2 `generate.*`、`rtl-manifest-cloud.json` |
| cloud_chen / 根 | `python -u sim/litex/run_soc_smoke.py --profile single --evidence-dir E2/smoke-single-retry1` | PASS / runner 0，159.052 s；成功后 child -15 | E2 `smoke-single-retry1.*`、`smoke-single-retry1/{result.json,uart-build.log}` |

本地旧完整归档 7 包已重新核对 bytes/SHA256，均 PASS，记录 `records/soc2-resume-20261008/old-archives-reverified.json`；没有移除旧证据或重启精简脚本。

HPM：整个事件束打一拍已经实现，计数器本体及其单元测试未修改；没有改写任何集群精确计数期望。`bd48477` 的 420/420 全量包含原 HpmSpec 与集群 ISA/自写程序，均通过，未发现需要容忍一拍的测试修改。计数器模块单测与集群回归的范围分别记录，不据此宣称额外的架构精确计数全覆盖。

全量于 2026-10-08 10:05:20–11:36:38（UTC+8）执行，sbt 总时间 5473 s，wrapper 5477.501 s；420/420、60 suites，failed/aborted/canceled/ignored/pending 均为 0。仅归档本次开始后更新的 60 个 XML，failures/errors/skipped 均为 0。源 SHA/空 tracked 状态前后保持一致，冻结前后 OK (8 files)。总数对应 CLUSTER 410 + SoC 净新增 6 + SRAM 新增 4 = 420，本轮没有删除或修改任何测试。

## D3 存储清单与 D5 检查

只读核对旧 `soc-010fa84/{tiny,tiny-debug}/gateware/vivado.log`：每份恰有两条 8-4767。原始日志/源路径/位数归档在 `soc2-fbc7e6e/d3-before.json`。没有其他 ≥2 Kbit 的 8-4767 存储，也没有本轮需改结构的多口/组合读例外。

| 旧存储 | 位数 | 旧证据 | 本轮处理 | 新综合映射 |
| --- | ---: | --- | --- | --- |
| meta_256x192 | 49,152 | tiny vivado.log:939，debug:1193；双写口无法推导 BRAM；旧 tiny 77,323 LUT / 49,208 FF / 0 BRAM | SdpSram 整行写，初始化/正常写合一 | 新 tiny：215 LUT / 0 FF / 3 RAMB36 |
| plruArr_256x7 | 1,792（<2 Kbit） | tiny:946，debug:1200；双写口；旧 tiny 3,145 LUT / 1,792 FF / 0 BRAM | D2 明确要求，仍替换为 SdpSram | 新 tiny：75 LUT / 0 FF / 1 RAMB18 |
| data_2048x256 | 524,288 | 旧层级报告 data_ext 155,491 LUT / 524,345 FF / 0 BRAM；旧日志没有独立 8-4767，但确认拆成寄存器 | D2 字节掩码 SdpSram | 新 tiny：657 LUT / 0 FF / 16 RAMB36 |

`fpga/kcu105/build_support.py` 对本次冻结的 L2Home.sv 检查 data/meta/plruArr 三个实例类型；`fpga/kcu105/soc2-bram-gate.tcl` 在综合报告和 checkpoint 后、opt/place 前检查三阵列各有 RAMB36/RAMB18，且 L2Home BRAM>0、LUT<100,000（作为拒绝原 29 万 LUT 映射的保守上限，实际值仍报告）。不通过直接 Tcl error，不能进入布局。检查数据保存 `soc2-l2-bram-gate.tsv`；Tcl 也复制进本构建 source-snapshot。提高 8-4767 的日志数量限制，避免 D3 清单被日志限额截断。没有放宽容量 DRC、时序约束或断言。

2026-10-08 tiny 生产版实际综合通过 D5 门槛。`soc2-l2-bram-gate.tsv` 中 L2 LUT 原语计数 **3,766**、FF **3,741**、RAMB36 **19**、RAMB18 **1**，其中 data=16 RAMB36、meta=3 RAMB36、plruArr=1 RAMB18。三个阵列均已映射 BRAM。正式层级综合报告 L2 为 **3,393 Total LUTs / 3,741 FF / 19 RAMB36 / 1 RAMB18**；门槛直接计数 LUT 原语，层级报告则考虑 LUT 合并，两种 LUT 数口径分别保留。100,000 LUT 仍是拒绝原约 29 万 LUT 映射的保守检查上限。

## Vivado 实际结果与用户停止边界

全部使用 Alan、Vivado 2022.2、xcku040-ffva1156-2-e、100 MHz，源码 `bd48477cce9e7747a37b5c9dfe6708ea35d5db94`；生成输入、冻结 source-snapshot 和 E3 命令/结果前后 SHA、tracked 状态已记录。用户要求停止后，不启动 small 冒烟、small 构建或时序优化候选。

| 构建 | start / end（UTC+8） | monotonic wall seconds | exit / 状态 |
| --- | --- | ---: | --- |
| tiny | 2026-10-08 11:43:51.315 / 12:44:23.283 | 3631.692869 | 0；完整布局布线与 bitgen 完成，100 MHz 时序失败 |
| tiny-debug | 2026-10-08 12:45:08.211 / 13:28:11.248 | 2582.804406 | -15；用户要求 SIGTERM，中止于布线，不是完整编译耗时 |
| small | N/A | N/A | 未启动 |

上述 wall time 由运行包装脚本实测；start 在记录仓库元数据之前、monotonic 计时在实际命令启动处开始，故时间戳之差可能有不足一秒的记录开销。tiny-debug 的停止信号发给本任务 Vivado PID 148357 和 target.py PID 147707，未触及其他项目；随后 wrapper 147638 写完退出记录，三个进程均已消失。

| 构建 / 报告阶段 | L2 LUT / FF / RAMB36 / RAMB18 | 全设计 LUT / FF / RAMB36 / RAMB18 / DSP |
| --- | --- | --- |
| tiny 综合层级 | 3393 / 3741 / 19 / 1 | 53212 / 48506 / 71 / 10 / 27 |
| tiny 最终 postRoute | 未额外生成最终层级报告；综合及布局层级原始报告保留 | 54197 / 48529 / 71 / 10 / 27 |
| tiny-debug 综合层级 | 3395 / 3743 / 19 / 1 | 61888 / 63516 / 211 / 10 / 27 |
| tiny-debug postPlace | 3315 / 3743 / 19 / 1 | 62444 / 64241 / 211 / 10 / 27 |

tiny 最终 LUT 22.36%、FF 10.01%、BRAM 76 tiles/600=12.67%；所有 97909 条可布线 net 完整路由、routing errors=0。两份实际综合日志均无 Synth 8-4767，新 D3 清单为空。debug 的后布局数不作为最终布线利用率；中止前 log 中的 WNS=-6.411 ns 仅是中间布线估计，不是 routed 验收报告。

tiny routed：**WNS=-6.112 ns / TNS=-201377.219 ns / WHS=+0.030 ns / THS=0.000 ns**，49130 个 setup failing endpoints；`soc-timing-summary.rpt` 和 `soc-worst-10.rpt` 对应 Physopt postRoute。不存在 100 MHz 时序达标结论。

最差 10 路径均从 `BreezeClusterAxi/cluster/l1d/internal2_valid_reg_replica_17/C` 出发，跨 L1D PMP/PMA、后端控制/旁路、乘法 DSP，分别到下表端点；slack 都是 -6.112 ns。10 ns requirement、15.621 ns 数据延迟，其中 logic=7.555 ns、route=8.066 ns，45 级逻辑；这是同一路径族的不同结果位，不能当作 10 个独立瓶颈。

| 序号 | 终点（前缀 `BreezeClusterAxi/cluster/backend/mulUnit/`） | 所属模块 | slack ns |
| --- | --- | --- | ---: |
| 1 | `product_0_reg/DSP_OUTPUT_INST/ALU_OUT[0]` | L1D→Backend/MulUnit | -6.112 |
| 2 | `product_0_reg/DSP_OUTPUT_INST/ALU_OUT[10]` | L1D→Backend/MulUnit | -6.112 |
| 3 | `product_0_reg/DSP_OUTPUT_INST/ALU_OUT[11]` | L1D→Backend/MulUnit | -6.112 |
| 4 | `product_0_reg/DSP_OUTPUT_INST/ALU_OUT[12]` | L1D→Backend/MulUnit | -6.112 |
| 5 | `product_0_reg/DSP_OUTPUT_INST/ALU_OUT[13]` | L1D→Backend/MulUnit | -6.112 |
| 6 | `product_0_reg/DSP_OUTPUT_INST/ALU_OUT[14]` | L1D→Backend/MulUnit | -6.112 |
| 7 | `product_0_reg/DSP_OUTPUT_INST/ALU_OUT[15]` | L1D→Backend/MulUnit | -6.112 |
| 8 | `product_0_reg/DSP_OUTPUT_INST/ALU_OUT[16]` | L1D→Backend/MulUnit | -6.112 |
| 9 | `product_0_reg/DSP_OUTPUT_INST/ALU_OUT[17]` | L1D→Backend/MulUnit | -6.112 |
| 10 | `product_0_reg/DSP_OUTPUT_INST/ALU_OUT[18]` | L1D→Backend/MulUnit | -6.112 |

tiny bitstream：`/home/chen/FUN/flow-runs/soc2-bd48477-resume-20261008/tiny/gateware/xilinx_kcu105.bit`，16,007,026 bytes，SHA256 `7418a58e8e013cbba8cff721e4f27355210f640f6a8e355311af46a8accd8c81`。生产版没有 ILA，`.ltx` 不适用；debug 中止时没有 bit/ltx/route.dcp。

时序分析只读取已完成 tiny route checkpoint，没有重新综合或改约束。E3 `inspect-tiny-route.tcl/log/jou`、`tiny-dsp-pipeline.tsv` 核对 16 个乘法 DSP：AREG/BREG/MREG 全部为 0，12 个 PREG=0、4 个最终输出 PREG=1。当前 Chisel 先计算完整 65×65 product，再串四级结果寄存器；生成构建的 `synth_design` 没有 `-retiming`，实际没有形成乘法内部流水。最差路径不是 D4 原 HPM 事件路径。

后续时序修复建议（仅分析，未实施）：优先评估四拍接口不变的 DSP 内部流水/重定时，并缩短 L1D 权限判定经后端旁路控制进入乘法输入的组合链；逐项验证 commit/kill、反压与精确四拍合同。若需要改变冻结寄存器边界，先提出具体裁定，不自行更改 backend-timing-contract 或 L1D 协议。其他核内路径按修复后的真实报告排序；不降频或放宽时序例外来宣称 100 MHz 达标。

## 未完成项与停止状态

| 项目 | 当前状态 / 原因 |
| --- | --- |
| `sbt test` | 新云机 420/420 PASS、exit 0 |
| `python -m pytest sim/litex -q` | 新云机 50/50 PASS、exit 0 |
| single BIOS 冒烟 | 新云机 PASS，159.052 s，runner exit 0；完整 64 KiB memtest、原 watchdog 与断言保持 |
| single 生产 / single debug / small 生产 SoC RTL 生成 | 新云机三种生成均 exit 0；229 个生成文件有 SHA256 清单，三个产品的 L2 data/meta/plru SdpSram 实例检查 PASS |
| tiny 生产 Vivado，100 MHz | Alan 完成，exit 0；BRAM gate PASS，routed WNS=-6.112 ns；bitstream 已生成，3631.693 s |
| tiny-debug Vivado，100 MHz | 用户要求中止，exit -15，2582.804 s；综合/BRAM gate/布局已完成，布线未完成，无 bit/ltx |
| small BIOS 冒烟 / small 生产 Vivado，100 MHz | 用户要求停止后续工作，未启动 |
| 新综合 D3 清单、L2/全设计 LUT/FF/BRAM | tiny/debug 均有综合结果，见下表；small N/A |
| routed WNS/TNS/WHS、最差 10 路径 | tiny 已完成并保留原始报告；debug 中止、small 未执行，均 N/A |
| bitstream / `.ltx` 的 Alan 路径 | tiny 有 bit，无 ILA 故无 ltx；debug 中止，无 bit/ltx；small 未执行 |

继续时先核对本地完整归档 hash、远端已完成/未完成精简包及空间，再处理**本任务**可重建输出；其他项目、旧 `soc-010fa84` 和共享依赖不在处理范围。每次仿真/编译/生成前重新读取 `docs/cross-project/simulation-host.md`，先预检 cloud_chen，不可用再 Alan；不沿用本报告的历史地址作为新配置。长任务后台运行并保留退出码，不密集轮询。

恢复后产品生成：新云机 `bd48477`，命令 `sbt "runMain flow.top.GenerateBreezeCluster single gshare linux" "runMain flow.top.GenerateBreezeCluster small gshare linux" "runMain flow.top.GenerateBreezeCluster single gshare linux tandem fpga-debug"`，cwd 为云工作区 `design/`，wrapper 13.816 s、exit 0，E2 `generate.*`。`rtl-manifest-cloud.json` 记录三个 marker、L2 实例类型、229 个生成文件和外部 CVFPU 输入的 SHA256；`axi-rtl.tgz` SHA256 为 `fc71b89b136d374801d66796e59397354f34ff725dd7fd9dcb65d4c8255a7c69`，本地传输副本已核对一致。

single 首次冒烟在 0.416 s 内因找不到 Meson 可执行入口失败，尚未进入 RTL 仿真。包已存在于私有 `flow-soc-deps-20261007/python`，修正本任务环境 PATH 加入其 `bin/`（Meson 1.12.1、Ninja 1.13.2），保留初次 `smoke-single.*` 与 UART 构建日志，使用独立 `smoke-single-retry1` 目录重试。没有修改仓库 RTL、测试、watchdog 或共享工具，不将环境失败计作 RTL 功能失败；仍在新云机执行。私有启动环境分别保留为 E2 `cloud-env.sh` 与 `cloud-env-v2.sh`。

single 重试 PASS，159.052 s，runner exit 0；明确启用断言、memtest_bytes=65536、wall_limit_seconds=1800。成功后 runner 按原流程停止子进程，child exit -15；真实 UART 包含 BIOS 横幅、DDR 初始化、64 KiB Memtest OK 与 `litex>`。

Vivado 使用 Alan 独立工作区 `/home/chen/FUN/flow-soc2-vivado-20261008`，同一 `bd48477` 与递归子模块；`E3=/home/chen/FUN/flow-runs/soc2-bd48477-resume-20261008`。从云机传入 `axi-rtl.tgz`，包 hash 与 229 个生成文件及全部外部 CVFPU 源 hash 均校验通过；只重定位三个 `filelist.f` 的绝对根目录，记录 E3 `rtl-relocation.json`，重定位后 `rtl-manifest-alan.json` 再确认三个产品 L2 阵列实例。Alan 冻结 OK (8 files)，没有在 Alan 重跑仿真、Scala 编译或 RTL 生成。tiny 生产版命令 `python -u fpga/kcu105/target.py --cpu-type breeze-tiny --sys-clk-freq 100000000 --output-dir E3/tiny --build`，E3 `vivado-tiny.*`。

耗时记录规则（用户 2026-10-08 补充）：长综合可按一小时检查点等待，进程退出则提前返回。每个 `*.command.json` 在命令启动前记录 start，`*.result.json` 在进程实际退出后记录 end 与 monotonic wall seconds，独立于检查频率。Vivado 的完整构建 wall time 边界为 `target.py` 命令启动到退出，包含 BIOS 编译、SoC/gateware 生成、Vivado 综合/优化/布局/布线/bitstream；云机上的 Chisel 产品 RTL 生成另记 `generate.result.json`，不混入 Vivado 耗时。每个构建最终报告 start/end/wall seconds、退出码与产物状态，不以轮询间隔估算时长。

尚未定位本轮 RTL 功能失败；tiny 的 BRAM 映射及容量问题已解决，但 100 MHz 时序未收敛；debug 用户中止、small 未执行。没有形式化、OpenSBI/Linux 仿真或烧板。上板第一步目前等待本轮实际产物；以后产出并核对 tiny-debug 的 bit/ltx 后，由用户执行烧录，检查 UART BIOS/DDR/memtest，ILA 以 `dbg_hang == 1` 触发观察原因位、最后退休 PC 与 AXI 握手。这是后续建议，不是已完成验证。
