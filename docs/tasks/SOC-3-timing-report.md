# SOC-3 执行报告

本轮按用户 2026-10-08 指令：先实现 M1/M2，§3.1 定向测试通过后运行 tiny 生产版 Vivado，并报告 routed 时序。时序迭代阶段不运行全量回归。起点 `ae343c2`，主工作区 `/home/chen/leisure/flow`、分支 `feat/pcie-fase-20260920`。已有用户文档改动和 SOC-2 证据保留。

当前状态：用户已裁定 **C1-B**，事件版 M1 源码为 `1b1759537673f1447e3d6f63c9bb3e241e3810ef`，§3.1 全部定向测试在 cloud_chen **87/87 通过**；tiny RTL 生成通过，Alan 的 Cluster OOC 正常结束，post-synth **WNS=-6.696 ns**。整 SoC 门槛未达，本轮没有 routed 结果。第一版 tiny（1e6e9bf）已于 15:44:54–15:45:06（UTC+8）按用户要求停止，无 routed 结果。冻结检查 OK (8 files)。本轮采用 Cluster OOC；TLB→S1 未进入全局 worst-20，保留用户指定的路径族停止规则。

## 第一版 M1：权限检查（1e6e9bf，后续 C1-B 修复见诊断小节）

`L1S2` 新增 `pmpAllowed/pmaAllowed/pmaDevice/pmaAmoOk/pmaRsrvOk/highAddress/permissionStale`。`L1DCache` 的 CPU 与 internal S1 各自以即将写入的完整物理地址、size、操作类型和有效特权级检查 PMP/PMA，结果随 S1→S2 寄存。`recheckReturns` 复制完整 Bundle，包含新字段。S2 的 permissionFault/atomicDenied/device 路由读取这些寄存位；没有按 `internal2.valid` 在 CPU/internal 两源之间选择后共享最终 S2 地址 checker 的路径；独立内部 lane 的刷新会选择其 S1 或本槽 S2 地址。misaligned 保留原位置，优先级和正常稳定上下文的流水边界保持。

上下文依据：`backend-v1-rtl-spec.md` §2 CSR 等空要求两 bank 原始 busy 清零且 EX/MEM/WB 无未提交长操作或未判定访存；§3 要求 s2Hold 时退休/CSR/PC 更新不重复；§7 串行 WB 期间年轻流水为空。RTL `BreezeBackend.scala:213–226` 中 scoreboard.pipe 对 EX/MEM/WB 访存置资格，`:429–446` 的 `csrStateHazard` 阻止 CSR 在途期间年轻 ID 发射，`:162–170/:371–374` 的 CSR 在 WB 的 wbCommit 更新；`:165–170/:383–389` 中断等空，xret/WB trap 杀年轻 CPU 流水。**这些约束不能证明独立 PTW 通道在 CSR 更新时为空**，因此没有采用无条件无机制的结论。

检测 permission-relevant CSR 向量（pmpcfg/pmpaddr、privilege、MPRV、MPP）变化：未决定 CPU S2 标记 permissionStale/needsRecheck，走现有 Recheck 路径；内部 PTW/Recheck 项保留原 S2 槽位、不响应/不分配 MSHR，在一次刷新边沿复用其所属 lane checker 更新权限位。不重新接受 PTW、不丢弃或复制其 response；internal S1 不覆盖有效保持项。S1 在实际推进时始终按当前上下文检查。Replay 保留提交时已检查语义。

`shadowPmp/shadowPma` 仅被断言读取，按 S2 当拍上下文组合重算。影子断言覆盖使用寄存权限的有效项；失效项不使用旧权限，CPU 等 Recheck，内部项等刷新；Replay/内部维护/FENCE/已承诺 AMO 等不重新判定。FPGA SYNTHESIS 关闭断言后，影子 checker 应作为无消费者逻辑消失；最终需以 routed cell query 证实，当前尚待执行。

新增 `L1DContextSpec` 使用真实 CSRFile 的 CSRW evaluate/commit 路径（含 WARL），覆盖 pmpcfg0/pmpaddr0、MPRV/MPP 双向变化后紧跟 Load/Store/AMO、多拍 S2 Hold 中连续上下文变化、PTW PMP 拒绝及 PTW S1→S2 与 CSR 生效同沿。这里是 CSR+L1D 定向集成，不是完整指令流/全系统证据。

## M2：四拍乘法

64 位无符号核：A 分为 24/24/16 位，B 分为 17/17/17/13 位，共 12 个 24×17 tile。`MulDspTile.sv` 的 SYNTHESIS 分支显式实例化 DSP48E2，AREG/BREG/ACASCREG/BCASCREG/MREG/PREG 均设 1，控制静态选择 A×B；无需 -retiming。仿真分支实现同样三个 CE 边沿，另有 S11 检查隐藏的操作数/MREG/PREG 保持。端口宽度及 pipeline 属性依据 [AMD DSP48E2 官方文档](https://docs.amd.com/r/2020.2-English/ug974-vivado-ultrascale-libraries/DSP48E2)。

| 边沿 | 数据内容 |
| --- | --- |
| E 末 | DSP A/B 输入寄存，操作数符号修正旁带与 op/rd/control 第一级 |
| E+1 末 | DSP MREG 部分积，旁带第二级 |
| E+2 末 | DSP PREG 部分积，旁带第三级 |
| E+3 末 | 130 位 carry-save 压缩与最终 CPA，完整乘积及五种 op 选择后的 64 位结果寄存 |
| E+4 拍 | 现有 committed/valid 结果接口输出 |

保留完整 signed 65×65 数学语义：a=low64(a)-a[64]·2^64，b 同理，按两条负修正行与符号乘积 bit128 修正，支持原完整乘积等价探针。valid/commit/kill/authorize 逻辑保持；数据随 mulEnable 同步推进，S07 出口保持、S11 全数据通路保持断言继续检查。旧 formal 观察探针只适配内部数学视图，断言未删除/放宽，未运行形式化。

原 MUL/MULH/MULHSU/MULHU/MULW 随机/协议测试和 8 条连续四拍合同测试保持。新增 375 组 corner/背压组合（五 op×五 A corner×五 B corner×三注入拍），在 E+1…E+3 拉低 ready，精确检查 E+4 valid 及后续保持；原各级 kill 测试保持，完整乘积参考等价额外跑 1000 次。

## 主机与门槛

实时读取 `docs/cross-project/simulation-host.md`，cloud_chen SSH/Java11/Verilator5.028/sbt 可用，远端反向代理 GitHub HTTP200；独立工作区 `/home/cloud_chen/work/flow-soc3-20261008`。Alan SSH/Vivado2022.2 可用，初检约 101 GiB 磁盘、58 GiB available memory；Vivado 将使用独立 `flow-soc3-vivado-20261008` 与独立 evidence root。

每个 stage 保存 `*.command.json/*.result.json/*.exit/*.log`，包含 SHA、host/cwd/command、submodule、start/end/monotonic wall time；SBT 保存本次 fresh XML。旧失败证据也保留。

| SHA | 阶段 | 实际主机 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| f2c8cf7 | MDU 第一次 | cloud_chen | compile exit1，旧 formal probe 引用已改结构 | `/home/cloud_chen/evidence/soc3-f2c8cf7/mdu.*` |
| 52575ab | MDU 第二次 | cloud_chen | 5/14，9 个 elaboration 失败，符号顶端移位表达式限制 | `/home/cloud_chen/evidence/soc3-52575ab/mdu.*` |
| cb6df29 | frozen | cloud_chen | OK (8 files), exit0 | `/home/cloud_chen/evidence/soc3-cb6df29/frozen.*` |
| cb6df29 | MDU + 完整乘积等价 | cloud_chen | 14/14 PASS, exit0, 46.783 s | `/home/cloud_chen/evidence/soc3-cb6df29/mdu.*` |
| cb6df29 | L1D 单元/权限/上下文 | cloud_chen | 49/52，exit1；Cache39/39、Permissions5/5 通过，新增三例因测试端口 release 为 SV 保留字编译失败 | `/home/cloud_chen/evidence/soc3-cb6df29/l1d.*` |
| 1e6e9bf | frozen | cloud_chen / Alan | OK (8 files), exit0 | 两端独立 `soc3-1e6e9bf/frozen.*` |
| 1e6e9bf | MDU + Context 最终重跑 | cloud_chen | 22/22 PASS, exit0, 99.732 s（MDU14 + Context8） | `/home/cloud_chen/evidence/soc3-1e6e9bf/direct.*` |
| 1e6e9bf | tiny 生产 RTL 生成 | cloud_chen | exit0, 6.961 s | `/home/cloud_chen/evidence/soc3-1e6e9bf/generate.*` |

最终重跑修复了测试端口命名，新增真实 CSR 双向转换均通过。`cb6df29` 与 `1e6e9bf` 的 `design/src/main` tree，以及 L1DCacheSpec/L1DPermissionsSpec blob 完全相同；`records/soc3-20261008/rtl-source-identity.json` 保存 Git 对象 ID。因此沿用 Cache39/39、Permissions5/5 的既有成功结果，未重复未改动 suite；不存在未修复功能失败。Context8 中 5 例继承原权限测试、3 例新增，不能把实例计数当作独立 requirement 数量。没有跳过乘法或 L1D 门槛。

最终 direct 命令：`sbt "testOnly flow.multiplier.MulUnitSpec flow.multiplier.MulProtocolSpec flow.multiplier.MulEquivalenceSpec flow.backend.MduTimingSpec flow.backend.MduBoundarySpec flow.memsys.L1DContextSpec"`。生成命令：`sbt "runMain flow.top.GenerateBreezeCluster single gshare linux"`。

MDU 命令：`sbt "testOnly flow.multiplier.MulUnitSpec flow.multiplier.MulProtocolSpec flow.multiplier.MulEquivalenceSpec flow.backend.MduTimingSpec flow.backend.MduBoundarySpec"`。L1D 命令：`sbt "testOnly flow.memsys.L1DCacheSpec flow.memsys.L1DPermissionsSpec flow.memsys.L1DContextSpec"`；cwd 均为云工作区 `design/`。

## Vivado 与 routed 证据

生成包 `axi-rtl.tgz` SHA256=`2f8a902801023d2cdab948f2568eeda0f55de6c45f571d52c67c91fbabb57b65`；cloud→本地→Alan 一致。Alan 校验 75 个生成文件及全部外部 CVFPU 源 hash，仅重定位一个 filelist 的根目录，保存 `rtl-relocation.json/rtl-manifest-alan.json`。Alan cwd=`/home/chen/FUN/flow-soc3-vivado-20261008`，E3=`/home/chen/FUN/flow-runs/soc3-1e6e9bf`。没有在 Alan 重新跑 Scala 编译或 Chisel 生成。

额外 primitive 定向核对：cloud_chen 无 xsim 入口（实际检查），因此仅该项使用 Alan Vivado Simulator 2022.2。同一生成包的 MulDspTile.sv，以 SYNTHESIS 分支连 Xilinx unisims_ver DSP48E2，2000 随机/边界 CE 边沿，对独立三边沿乘积 FIFO 检查；**PASS、exit0、8.063 s**，E3 `dsp-primitive-retry1.*`。首次 `dsp-primitive.*` 缺少统一 timescale、在 elaboration 阶段 exit1；仅为 xelab 添加 `--timescale 1ns/1ps` 后重试，未改 RTL 或期望。该 gate 弥补 Chisel 仿真走 behavioral 分支的边界。

tiny 已后台启动；命令 `python -u fpga/kcu105/target.py --cpu-type breeze-tiny --sys-clk-freq 100000000 --output-dir /home/chen/FUN/flow-runs/soc3-1e6e9bf/tiny --build`，E3 `vivado-tiny.*`。WNS/TNS/WHS、失败端点、worst10、利用率、DSP pipeline、影子 checker 消失证据、bitstream 均待实际执行。固定 tiny、100 MHz、xcku040-ffva1156-2-e 和原策略/约束；沿用 D5 place 前 BRAM 门槛。只读 route inspection 脚本位于 `records/soc3-20261008/inspect-tiny-route.tcl`。

已对比两轮实际构建文件：本轮 XDC 与 SOC-2 `bd48477` 的 XDC 逐字相同，SHA256=`a9dd70dcfba5908dcb23f186fa863d75278420a70efe00ad36b25ffce07348f5`；六条 synth/opt/place/phys_opt/route/phys_opt 命令策略一致，未开启 `-retiming`。证据 `records/soc3-20261008/alan/constraints-identity.json` 及同目录 `constraints/` 的两轮 Tcl/XDC。原有 CDC false-path 保持，不增加例外或 multicycle。

尚未运行 §3.2 全量/集群/LiteX/BIOS 验收、debug、small、M3；无形式化、OpenSBI/Linux 或烧板。本报告不把模块测试、静态实例参数或综合估计称作 routed 时序结论。

## 2026-10-08：并行 OOC 综合诊断

**结论：两个模块均在 20 分钟内正常完成，未单独复现正式 tiny 的长时间 Timing Optimization。不能据此指定 MulUnit 或 L1DCache 为唯一元凶。** MulUnit 的该阶段约 31 s，L1DCache 约 263 s；后者是较重的模块，但没有出现正式构建超过 70 分钟仍未结束的现象。没有满足“MulUnit 是元凶”的条件，故未执行 `rows.reduce(_ +& _)` 对照。生产 Scala/SV 没有修改，也没有启动新的整 SoC 构建。

### 输入、执行与时间

实际主机 Alan（`chen-System-Product-Name`），Vivado **2022.2 Build 3671981**，器件 **xcku040-ffva1156-2-e**，源码 **1e6e9bfd785f85f2e1440a05ca3a9de7e4f15722**。直接读取正式 tiny 已固定的 `tiny/source-snapshot/`：逐个核对 manifest 的 115 个文件 hash，沿用正式 Tcl 的 read 顺序、SystemVerilog file type 和 include 目录；不用 LiteX 平台 top，不重新生成 RTL。MulUnit.sv SHA256=`4ac20f970d486ed8bf4a06b5deb5ec3aa2c9d850a15f11fa1d2e65a274fbaae4`；L1DCache.sv SHA256=`e16f188952f46b253f1977c913d8e9153c9e3e2a536ace8cccebc1c641f29fe8`。

每个独立 Tcl 设置 `set_param general.maxThreads 2`；独立 XDC 为 `create_clock -name ooc_clk -period 10.000 [get_ports clock]`。综合命令分别是 `synth_design -directive AreaOptimized_high -top MulUnit/L1DCache -mode out_of_context -part xcku040-ffva1156-2-e -include_dirs {…/tiny/source-snapshot/include-0}`，与正式综合相同 directive，无 `-retiming`、无 timing exception。两个作业几乎同时启动，各自有独立进程组，命令包裹为 `setsid timeout --signal=TERM --kill-after=15s 20m nice -n 10 vivado -mode batch -source <独立目录>/synth.tcl -log <独立目录>/vivado.log -journal <独立目录>/vivado.jou`。实际 Vivado 进程 NI 均为 10；日志确认 synth/timing update 最大 2 个进程/CPU。

所有输出位于 `E3/ooc-mul/`、`E3/ooc-l1d/`，各自保存 `ooc.command.json/ooc.result.json/ooc.exit/ooc.log`、Tcl/XDC、完整输入 manifest、Vivado log/journal、`post-synth.dcp`、时序与利用率报告。wrapper cwd 为 `/home/chen/FUN/flow-soc3-vivado-20261008`，Tcl 首行切换到各自 OOC 输出目录，未写 tiny 目录。本地小型证据副本为 `records/soc3-20261008/alan/ooc-{mul,l1d}/`（DCP 留在 Alan）。

| 模块 | 开始 / 结束（UTC+8） | synth_design 实测 | 含启动及报告的总用时 | exit / timeout | post-synth WNS / TNS / WHS | setup 失败 / 总端点 |
| --- | --- | ---: | ---: | --- | --- | --- |
| MulUnit | 15:27:54.302 / 15:29:47.751 | 86.766 s | 113.388 s | 0 / 未超时 | +6.130 / 0.000 / +0.083 ns | 0 / 2250 |
| L1DCache | 15:27:54.312 / 15:35:50.559 | 440.878 s | 476.185 s | 0 / 未超时 | +0.995 / 0.000 / +0.094 ns | 0 / 30987 |

`stage-times.txt` 保存每份日志的 `^Finished .*Time` 全部原文。下面列主要阶段的累计 elapsed；相邻值之差是近似阶段用时，不能将累计值再相加：

| 阶段结束 | MulUnit 累计 elapsed | L1DCache 累计 elapsed |
| --- | ---: | ---: |
| RTL Elaboration | 00:03 | 00:07 |
| RTL Optimization Phase 1 | 00:03 | 00:07 |
| Constraint Validation | 00:06 | 00:13 |
| RTL Optimization Phase 2 | 00:06 | 00:29 |
| Cross Boundary and Area Optimization | 00:46 | 02:34 |
| Applying XDC Timing Constraints | 00:49 | 02:38 |
| Timing Optimization | 01:20（约 31 s） | 07:01（约 263 s） |
| Technology Mapping | 01:21 | 07:07 |
| IO Insertion | 01:23 | 07:10 |
| Writing Synthesis Report | 01:23 | 07:11 |

### post-synth 最差路径与网表

两个模块都只执行综合，没有 place/route。以下 route 数字是 Vivado **unplaced 估计**，不是物理布线路径；约束覆盖内部寄存器路径，按要求仅给模块 clock 10 ns。`check_timing` 均为 no_clock=0、unconstrained_internal_endpoints=0、loops=0。MulUnit 的 143 个 input / 71 个 output、L1DCache 的 1145 个 input / 808 个 output 没有 I/O delay；因此整 SoC 的 CSR 寄存器→L1D、L1D hold→后端→MulUnit ready/CE 等跨模块路径不能用这些 OOC WNS 验收。

**MulUnit**：最差路径从 `tiles_4_1/dsp/DSP_OUTPUT_INST/CLK` 发射，经 DSP PREG 部分积、fabric 压缩/CPA/结果选择，到 `resultData_reg[63]/D`，slack **+6.130 ns**，17 levels，data **3.894 ns = logic 1.632 + estimated route 2.262**。worst10 均为同一起点、同一算术结果路径族：

| # | 终点 | slack ns | levels | data ns | logic ns | estimated route ns |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | resultData_reg[63]/D | 6.130 | 17 | 3.894 | 1.632 | 2.262 |
| 2 | resultData_reg[55]/D | 6.158 | 16 | 3.866 | 1.604 | 2.262 |
| 3 | resultData_reg[47]/D | 6.186 | 15 | 3.838 | 1.576 | 2.262 |
| 4 | resultData_reg[57]/D | 6.207 | 16 | 3.817 | 1.616 | 2.201 |
| 5 | resultData_reg[39]/D | 6.214 | 14 | 3.810 | 1.548 | 2.262 |
| 6 | resultData_reg[59]/D | 6.233 | 17 | 3.791 | 1.591 | 2.200 |
| 7 | resultData_reg[49]/D | 6.235 | 15 | 3.789 | 1.588 | 2.201 |
| 8 | resultData_reg[31]/D | 6.242 | 13 | 3.782 | 1.520 | 2.262 |
| 9 | resultData_reg[56]/D | 6.252 | 16 | 3.772 | 1.566 | 2.206 |
| 10 | resultData_reg[62]/D | 6.252 | 17 | 3.772 | 1.599 | 2.173 |

`ooc-mul/dsp-pipeline.tsv` 逐一列出 `tiles_0_1` 到 `tiles_11_1` 的 12 个 DSP48E2，**全部 AREG=BREG=MREG=PREG=1**。hierarchical utilization：1229 LUT（1096 logic + 133 SRL）、231 FF、12 DSP、0 BRAM。这是独立综合网表证据；正式 tiny 的 routed DSP 门槛仍待主运行。

**L1DCache**：最差路径从 `internal2_req_idx_reg[6]/C`，经过索引/way/分配判定、response-kind/ready、`cpuFire1`、PTW/s0Grant 判定，到 `miss/state_reg[0]/D`；slack **+0.995 ns**，28 LUT levels，data **9.029 ns = logic 1.623 + estimated route 7.406**。完整路径有 `miss/internal2_req_src…` fanout 130、`miss/cpuFire1` fanout 263，表明本地命中/分配/ready 控制锥较深，不能把它直接解释成 PMP 检查路径。worst10 的共同起点为 `internal2_req_idx_reg[6]/C`：

| # | 终点 | slack ns | levels | data ns | logic ns | estimated route ns |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | miss/state_reg[0]/D | 0.995 | 28 | 9.029 | 1.623 | 7.406 |
| 2 | plru_0_reg[1]/D | 1.279 | 28 | 8.745 | 1.741 | 7.004 |
| 3 | plru_0_reg[2]/D | 1.279 | 28 | 8.745 | 1.741 | 7.004 |
| 4 | plru_100_reg[1]/D | 1.279 | 28 | 8.745 | 1.741 | 7.004 |
| 5 | plru_100_reg[2]/D | 1.279 | 28 | 8.745 | 1.741 | 7.004 |
| 6 | plru_101_reg[1]/D | 1.279 | 28 | 8.745 | 1.741 | 7.004 |
| 7 | plru_101_reg[2]/D | 1.279 | 28 | 8.745 | 1.741 | 7.004 |
| 8 | plru_102_reg[1]/D | 1.279 | 28 | 8.745 | 1.741 | 7.004 |
| 9 | plru_102_reg[2]/D | 1.279 | 28 | 8.745 | 1.741 | 7.004 |
| 10 | plru_103_reg[1]/D | 1.279 | 28 | 8.745 | 1.741 | 7.004 |

`ooc-l1d/shadow-checkers.tsv` 对综合后全部层级查询 `NAME =~ *shadowPmp* || NAME =~ *shadowPma*`，**shadow_cell_count=0**。生成 SV 中影子输出仅供 `ifndef SYNTHESIS` 的断言使用；综合网表查询确认没有保留这两个影子实例/所属 cell。hierarchical utilization：12789 logic LUT、16403 FF、4 RAMB36、0 DSP；其中 tags_ext 为 3287 LUT / 11328 FF，miss 为 3011 LUT / 808 FF。该映射按同一源与原 directive 产生，没有为了诊断改变 RAM 或层级属性。

原始 `timing-summary.rpt/worst-10.rpt/utilization-hierarchical.rpt`、pipeline/shadow TSV、完整阶段原文与 `diagnosis-summary.json` 均保留在上述两端 OOC 证据目录。

### 根因判断、建议及主运行状态

已证实的是：**本次当前 CSA+CPA 的 MulUnit 独立综合没有复现长时间 Timing Optimization 停顿；L1DCache 独立综合也正常结束。** OOC 条件与整 SoC 的边界不同，不能排除 MulUnit CE 或 L1D CSR 输入在接回实际驱动寄存器后参与问题。当前最合理的推断是整 SoC 的跨层级时序优化锥及扇出/逻辑重汇合触发了优化器耗时，而不是任一模块独立无法综合；尚未定位到确切一条路径或内部子锥。

建议先保留当前 M2，下一次诊断用小型 wrapper 约束/寄存实际 CSR 与 ready 输入，并保留真实 `L1D s2Hold→后端 hold/ready→MulUnit DSP CE` 连接，区分 CSR/contextChanged、L1D 分配/ready 链和 DSP CE 的贡献。若之后证据指向 L1D，优先在现有级内扁平化命中/way/分配/response-kind/ready 的串行布尔判定，共享前级已知分类并复制局部控制驱动；保持同拍 outcome、MSHR/MMIO/PTW 协议和上下文刷新机制。CSR 边界/掩码预解码只作为候选，须明确 CSR 生效与预解码更新同沿关系，并继续由影子断言覆盖。

如果后续真实路径才证明 M2 需要重排，可评估 E+2/E+3 分摊压缩或 48-bit DSP PCIN 级联；不能在现有三个 DSP 寄存级之外增加累加边沿。必须维持 E 输入 AREG/BREG、E+1 MREG、E+2 PREG、E+3 结果寄存、E+4 输出及 stall/kill/authorize 对齐，T04/P02/T13/P10 和原 MDU 检查保持，不能依赖 retiming。当前 OOC 证据没有支持先改 CSA 的结论，也没有证明上述候选能改善正式构建。**本次仅报告，等待用户确认后才改生产 RTL。**

主运行观察（2026-10-08 **15:38:32 UTC+8**）：Vivado PID **273348** 运行 1:24:19、CPU 100%，仍在 `Start Timing Optimization`；`vivado.log` 最后写入 **14:18:27.435990**，尚无退出码或 routed checkpoint。job.py PID **272626**、after-vivado.sh PID **274664** 均继续运行，未发送任何终止信号。证据 `E3/ooc-main-observation.json` 及本地对应副本。原 after-vivado.sh 将在主构建退出且有 routed checkpoint 时进行既有只读查询；没有新增整 SoC 构建或后续产品构建。当前没有新的 bitstream/routed 时序可报告，原报告中的待完成状态保持。

## 2026-10-08：停止第一版 tiny，事件版 M1 的裁定边界

### 停止记录与保留网表

用户要求停止 `soc3-1e6e9bf` 的 Vivado 273348 进程树、job.py 272626 与 after-vivado.sh 274664。2026-10-08 **15:44:54.454009** 发出停止，**15:45:06.613180 UTC+8** 确认目标树无存活进程。先停 watcher 及其 sleep，再 TERM Vivado 树，让原 job.py 保存 child 的真实退出；随后清理残留目标树。`vivado-tiny.result.json` 记录 exit **1**、总用时 **5446.312 s**、结束 **15:44:54.650081**。这是用户取消导致的构建退出，不能记作功能/DRC 失败。

停止时综合已继续推进：`Finished Timing Optimization` 的累计 elapsed 为 **1:27:59**，此前 `Finished Applying XDC Timing Constraints` 为 **0:04:05**，该优化段约 **1:23:54**。已于 15:43 写出 `xilinx_kcu105_synth.dcp` 与综合报告；停止前最后一个 `Start` 为 **Start Writing Synthesis Report**，最近的执行命令为 `opt_design -directive ExploreArea`。没有 routed checkpoint 或 bitstream。完整 PID、进程身份、信号时间和停止阶段见 `E3/vivado-tiny-cancellation.json`，本地副本 `records/soc3-20261008/alan/`。

保留 synth checkpoint 后启动一次只读查询（不运行 synth/opt/place/route），证据 `E3/stopped-synth-inspection/`，exit0；本地有同名小型副本。实际源码仍为 1e6e9bf，不能将查询结果称作事件版 M1 的结果。

### 当时提出的冲突（现已裁定 C1-B）

**C1：生产 CPU 不加上下文重查机制，与原 Hold 测试的输入/期望不能同时满足。**

后端 `Scoreboard.scala` 的 csrDrainOk 使用原始 busy 与 EX/MEM/WB producer；`BreezeBackend.scala:213–226` 覆盖 Load/Store/Fence 等在途访存，`:429–446` 的 CSR 状态 hazard 阻止年轻 CPU 指令发射，`:162–170/:371–389` 确保 Hold 时不能 CSR commit、trap/xret 发起 kill，`:300–301` 把 kill 接到 cache 的 S1/S2。按这些生产端口对齐与串行规则，CSR 写时没有有效待判定 CPU S1/S2；trap/xret 改上下文同沿会清掉年轻 CPU 项。独立 PTW 不受这一证明覆盖。

但 `L1DContextSpec.scala:107–133` 的 CSR+cache fixture **没有后端**：CPU load 在 S2 Hold 时，`:118/:120/:122` 仍直接 commit 三次 pmpcfg0，随后要求该未被 kill 的 load 返回 accessFault。这是绕过串行/kill 的模块输入。若移除 CPU 上下文重查，它会继续带旧权限；保持原影子检查则断言失败。不能通过修改 expected、删除断言或偷偷加特殊仿真模式让两者同时通过。

待选 **C1-A（建议）**：生产 CPU 无机制；仅调整这一例的环境/场景，使 CSR 写等待 CPU 排空、trap/xret 同沿 kill，保留合法的 Hold/上下文事件覆盖以及 PTW 在途重查覆盖。需明确允许更改这一例刺激与相应期望，其余 directed tests 与影子检查保持。

待选 **C1-B**：保留原非串行 Hold fixture 与 fault 期望，允许生产 CPU 用已寄存的 1 bit CSR 事件触发 needsRecheck→Recheck；仍删除宽向量比较。此项需要放宽最新指令中“能保证的，CPU 不加任何机制”。

### 已有边界，不需要新增拍数裁定

**事件对齐**：CSR 写/trap/xret 的 commit 事件只寄存一次；事件位与新 CSR 状态在同一个边沿之后可见，不能在 CSRFile 注册后再在 L1D 注册第二次，形成漏检窗口。只对受影响的有效项走 Recheck，Replay 保留已检查语义，不用事件去门控整个 S2 outcome。

**内部 Recheck**：当前 cpuRetry 从 cpu2 生成，recheckReturns 把 internal2 返回 cpu2。PTW 没有 CPU owner，不能直接套这条 CPU 返回路径。可按任务原授权，在内部受影响项走局部 Recheck 判定时保留其 S2 槽位、使用该 lane checker 刷新权限，再继续原请求；不新增 PTW 请求/响应，不影响其他来源 outcome。这是内部实现适配，不需要改变四拍乘法或 PTW 对外协议。

**TLB→S1 是组合路径，已有逻辑级数，但尚未发现必须增拍的证据**：`Sv39Tlb.scala:39–66` 在同步阵列/寄存状态之后做 base/super CAM 命中、entry/level 选择、权限/物理地址生成，resp.paddr 并非寄存器直出。本次已保存 checkpoint 的真实跨模块路径：`mmu/dtlb/mem_ext/Memory_reg[3][264]/C` → `l1d/cpu2_pmpAllowed_reg/D`，**29 levels**，data **9.187 ns = logic 2.948 + estimated route 6.239**，100 MHz post-synth slack **+0.648 ns**；查询文件 `stopped-synth-inspection/tlb-to-s1-permission-worst-20.rpt`。它不代表 routed 通过，也不是修复后版本的结果。修复后 Cluster OOC 若显示该路径无法放进一拍，按用户指令停下报告，不自行加级。

同一个旧 checkpoint 的整体 post-synth worst path 为 `l1d/internal2_req_idx_reg[1]/C` → `backend/csrFile/performance/pending_7_reg[0]/D`，slack **-6.433 ns**、51 levels、data **16.268 ns = logic 3.045 + estimated route 13.223**，详见 `stopped-synth-inspection/worst-20.rpt`。这一跨模块控制路径是旧版本当前最差证据，不能用两个独立模块的正 WNS 排除 L1D 或 CSR/ready 连接。

执行顺序已由用户明确：事件版修复 → §3.1 全部重跑 → tiny 参数 BreezeCluster OOC（100 MHz、AreaOptimized_high、maxThreads4、timeout30min、worst20）；只有仍卡 Timing Optimization 才做 1e6e9bf 回退两份 L1D 源的诊断 A/B（不提交）；只有 Cluster post-synth WNS≥0 才启动新的整 SoC tiny 布局布线。后续 C1-B 已批准 CPU 局部重查；TLB→S1 若成为最差路径族，即停止，不能自行加拍。

## 诊断续：C1-B 事件版 M1（1b17595）

用户明确裁定保留原 Hold 用例及其 fault 期望，生产 CPU 也使用局部事件重查。上述后端 CSR 串行/kill 证明仍成立；按 C1-B，cache 同时覆盖没有后端的原 Hold fixture，未改变 WB 串行规则。`CSRFile` 在实际 commit 写 pmpcfg0/pmpcfg2、pmpaddr0…15、mstatus，或 trap/MRET/SRET 时生成 1 bit 事件，**只在 CSRFile RegNext 一次**；新 CSR 状态与事件同沿可见，L1D 直接消费。锁定/同值写保守产生事件，不比较宽上下文。FASE 特权接管同样覆盖，以保留原上下文一致性边界。

删除 L1D 的 permissionContext/previousPermissionContext 宽寄存器与比较，并去掉冗余 permissionStale 字段。事件只使有效 CPU S2 项进入现有 needsRecheck→Recheck：当前有效项立即使用事件，保持项记住 needsRecheck，cpu1 同拍推进项也携带该标记。Recheck 返回仍整体搬运权限字段。PTW/Recheck 的事件只使该内部槽位选择 Recheck 并保留一拍，复用内部 lane checker 更新权限；没有全局 `s2.valid && !internalRefresh` 门控，没有重新接受 PTW 或重复 response，Replay 仍已检查。

原 `L1DContextSpec` Hold 方法刺激、循环次数、响应/异常期望均未改，影子检查保持开启。另增 CPU S1→S2 与 CSR 拒绝写同沿用例，以及 CSR 事件与新状态同拍、单周期、无关 CSR 不触发、trap/MRET/SRET 的定向测试。两个原单元测试初始化仅增加新事件输入的低电平默认值。

证据根：cloud_chen `/home/cloud_chen/evidence/soc3-1b17595`；Alan `/home/chen/FUN/flow-runs/soc3-1b17595`。实际源与子模块、cwd、完整命令、退出码、阶段时间及 XML 按 stage 保存。Cluster OOC 的生产 tiny filelist 将按原顺序连同 CVFPU 源与 include 目录独立快照并逐文件 hash，不触碰旧 tiny 目录。本轮结果见下文。

第一次事件版 cdee99a 在 cloud_chen 重跑为 **86/87，exit1**（wrapper 568.434 s，SBT 566 s）：CPU 同拍推进、原 Hold 与新 CSR 脉冲用例已通过，唯一失败是 PTW 更新同沿触发旧“internal result has no reserved completion capacity”断言。原断言把新的局部权限 Recheck 与资源等待一并归入 undecided；该刷新实际保有内部槽位。1b17595 将容量检查按新机制等价适配，仅允许 **internalRefresh && Recheck && internalHold && !internalAdvance && !allocates && !ptw.resp.valid** 的局部刷新，并另加断言要求刷新必满足这些条件。其余容量等待继续使用原检查，没有改测试刺激、期望或影子断言。失败的完整日志/XML/result 保留于 cloud_chen soc3-cdee99a 及本地 records/soc3-20261008/cdee99a/cloud/soc3-cdee99a；未带该已知失败进入 Vivado。1b17595 随后重跑所有 87 个定向用例，全部通过。


### 1b17595 定向门槛与生成

| 阶段 | 实际主机 | 结果 | 用时 | 证据 |
| --- | --- | --- | ---: | --- |
| frozen | cloud_chen | OK (8 files), exit0 | 0.031 s | soc3-1b17595/frozen.* |
| §3.1 全部定向 + CSR | cloud_chen | **87/87 PASS**, exit0；9 suites，0 failures/errors/skips | **477.980 s** | soc3-1b17595/directed.* + fresh XML |
| tiny production RTL | cloud_chen | `GenerateBreezeCluster single gshare linux`, exit0 | **6.896 s** | soc3-1b17595/generate.* |
| RTL 身份核对 | cloud_chen → Alan | 75 生成文件与全部外部源 hash 一致，L2 wrapper 检查 PASS | — | rtl-manifest-cloud.json / rtl-source-identity.json |

9 个套件计数：MulUnit2、MulProtocol5、MulEquivalence1、MduTiming3、MduBoundary3（合计14）；L1DCache39、L1DPermissions5、L1DContext9（合计53）；BreezePrivilege20。T04/P02/T13/P10 原测试/期望保持，乘法器源码没有改动。Hold 方法与 1e6e9bf 逐字一致，SHA256 `42d5a7ec0bcd6e0a61d5a013b2466ee531c475b1eb39079623287bb8890a8e3e`，本地 hold-identity.json 保存源 SHA 和比对结果。未运行 §3.2 或全量回归。

两端源码均 clean 1b17595，子模块指针与命令存在 result.json。cloud_chen cwd `/home/cloud_chen/work/flow-soc3-20261008/design`，SBT 1.9.7 / Java 11.0.32.1 / Verilator 5.028；本轮每次测试/生成之前重新读取共享主机配置并预检首选主机。tiny 输出配置：single、gshare、linux、debug=false、1 core、32 B line、L1 128 sets × 4 ways、L2 64 KiB × 8 ways / 2 slots，与原 tiny 生成配置相同。

传输包 SHA256 `202ed93a619e532b3f0a7c88a9066c1989e082f4b46ab7b7185bfde8d51dda17`。生成 CSRFile.sv 只有一次 `io_mmu_context_permissionEvent_REG` 注册，直接赋到输出；L1DCache.sv 直接消费该输入，没有 permissionContext/previousPermissionContext。Cluster OOC 以生产 filelist 的 **113 个源文件**和 include 目录独立快照，不包含 LiteX 板级逻辑，top BreezeCluster；完整 source-manifest.json 记录顺序、配置、源 SHA、子模块和每个文件 hash。

### 1b17595 Cluster OOC

Alan evidence `/home/chen/FUN/flow-runs/soc3-1b17595/cluster-ooc`，源码 cwd `/home/chen/FUN/flow-soc3-vivado-20261008`。Vivado 2022.2，xcku040-ffva1156-2-e，模块 clock 10.000 ns；`synth_design -directive AreaOptimized_high -top BreezeCluster -mode out_of_context`，maxThreads4，无 retiming/其他 directive。启动包装为 `setsid timeout --signal=TERM --kill-after=15s 30m nice -n 10 vivado -mode batch ...`，job.py PID299651。启动之前 directed 和 generate 的 exit0/SHA 一致性已检查。阶段原文、timing summary、全局 worst20、单独 TLB→S1 worst20、hierarchical utilization、DSP/影子查询及 checkpoint 会保留。完整结果如下。


**正常完成，exit0，无 timeout/ERROR**：2026-10-08 **16:23:57.594214 → 16:36:14.598781 UTC+8**，wrapper **736.740 s（12:16.740）**；synth_design **695.299 s**。Timing Optimization 从累计 elapsed 3:22 到 10:57，即约 **455 s（7:35）**。未复现第一版 tiny 的 84 分钟优化段停顿；两次的顶层/边界不同，不能据此单独证明宽比较是之前全部耗时的根因。按“仍卡 Timing Optimization 才做 A/B”的条件，本轮**未运行诊断回退**。

阶段原文完整保留在 `cluster-ooc/stage-times.txt`（`grep "^Finished .*Time"`）及 ooc.log。下表累计 elapsed 来自 Vivado 日志；差值为与上一条 Finished 的差，包含阶段间开销且按秒取整，不替代精确 synth/wrapper 时间。

| Finished 阶段 | 累计 elapsed s | 与前一 Finished 差 s |
| --- | ---: | ---: |
| RTL Elaboration | 12 | 12 |
| Handling Custom Attributes | 12 | 0 |
| RTL Optimization Phase 1 | 13 | 1 |
| Constraint Validation | 23 | 10 |
| RTL Optimization Phase 2 | 51 | 28 |
| Cross Boundary and Area Optimization | 197 | 146 |
| Applying XDC Timing Constraints | 202 | 5 |
| Timing Optimization | 657 | 455 |
| Technology Mapping | 669 | 12 |
| IO Insertion | 675 | 6 |
| Renaming Generated Instances | 675 | 0 |
| Rebuilding User Hierarchy | 680 | 5 |
| Renaming Generated Ports | 681 | 1 |
| Handling Custom Attributes | 682 | 1 |
| Renaming Generated Nets | 682 | 0 |
| Writing Synthesis Report | 682 | 0 |

**post-synth timing**：WNS **-6.696 ns**，TNS **-91621.070 ns**，setup failing **36577 / 97510 endpoints**；WHS **+0.083 ns**、THS0、hold failing0。clock=ooc_clk，period10.000 ns，100 MHz。所有下述 route 值都是 **unplaced estimated route**；未运行 opt/place/route，不能报告 routed WNS。Cluster 仍有285 input / 267 output无I/O delay，其外部SoC接口不作为完整时序证明；CSR→L1D、TLB→S1及后端控制这些跨模块内部寄存器路径在本顶层中已被计时。没有 false path/multicycle/降频。

### 全局最差 20 条路径与 TLB→S1 标记

全局20条全部起于 `l1d/internal2_req_idx_reg[3]/C`。前8条到 HPM pending D，后12条到 EX 浮点控制字段的 CE。**TLB→S1 permission 不在全局 worst-20**，下表最后一列没有命中；在随后单独报告/列出该路径，不能因为未进入20条而排除其布线风险。

| # | Source | Destination | Slack ns | Levels | Data ns | Logic ns | Estimated route ns | TLB→S1 |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | `l1d/internal2_req_idx_reg[3]/C` | `backend/csrFile/performance/pending_7_reg[0]/D` | -6.696 | 52 | 16.720 | 3.114 | 13.606 | — |
| 2 | `l1d/internal2_req_idx_reg[3]/C` | `backend/csrFile/performance/pending_0_reg[0]/D` | -6.476 | 52 | 16.500 | 3.144 | 13.356 | — |
| 3 | `l1d/internal2_req_idx_reg[3]/C` | `backend/csrFile/performance/pending_1_reg[0]/D` | -6.476 | 52 | 16.500 | 3.144 | 13.356 | — |
| 4 | `l1d/internal2_req_idx_reg[3]/C` | `backend/csrFile/performance/pending_2_reg[0]/D` | -6.476 | 52 | 16.500 | 3.144 | 13.356 | — |
| 5 | `l1d/internal2_req_idx_reg[3]/C` | `backend/csrFile/performance/pending_3_reg[0]/D` | -6.476 | 52 | 16.500 | 3.144 | 13.356 | — |
| 6 | `l1d/internal2_req_idx_reg[3]/C` | `backend/csrFile/performance/pending_4_reg[0]/D` | -6.476 | 52 | 16.500 | 3.144 | 13.356 | — |
| 7 | `l1d/internal2_req_idx_reg[3]/C` | `backend/csrFile/performance/pending_5_reg[0]/D` | -6.476 | 52 | 16.500 | 3.144 | 13.356 | — |
| 8 | `l1d/internal2_req_idx_reg[3]/C` | `backend/csrFile/performance/pending_6_reg[0]/D` | -6.476 | 52 | 16.500 | 3.144 | 13.356 | — |
| 9 | `l1d/internal2_req_idx_reg[3]/C` | `backend/exFp_dstFmt_reg[0]/CE` | -5.959 | 48 | 15.877 | 2.899 | 12.978 | — |
| 10 | `l1d/internal2_req_idx_reg[3]/C` | `backend/exFp_dstFmt_reg[1]/CE` | -5.959 | 48 | 15.877 | 2.899 | 12.978 | — |
| 11 | `l1d/internal2_req_idx_reg[3]/C` | `backend/exFp_fpuValid_reg/CE` | -5.959 | 48 | 15.877 | 2.899 | 12.978 | — |
| 12 | `l1d/internal2_req_idx_reg[3]/C` | `backend/exFp_intFmt_reg[0]/CE` | -5.959 | 48 | 15.877 | 2.899 | 12.978 | — |
| 13 | `l1d/internal2_req_idx_reg[3]/C` | `backend/exFp_intFmt_reg[1]/CE` | -5.959 | 48 | 15.877 | 2.899 | 12.978 | — |
| 14 | `l1d/internal2_req_idx_reg[3]/C` | `backend/exFp_isDouble_reg/CE` | -5.959 | 48 | 15.877 | 2.899 | 12.978 | — |
| 15 | `l1d/internal2_req_idx_reg[3]/C` | `backend/exFp_isLoad_reg/CE` | -5.959 | 48 | 15.877 | 2.899 | 12.978 | — |
| 16 | `l1d/internal2_req_idx_reg[3]/C` | `backend/exFp_isStore_reg/CE` | -5.959 | 48 | 15.877 | 2.899 | 12.978 | — |
| 17 | `l1d/internal2_req_idx_reg[3]/C` | `backend/exFp_localOp_reg[0]/CE` | -5.959 | 48 | 15.877 | 2.899 | 12.978 | — |
| 18 | `l1d/internal2_req_idx_reg[3]/C` | `backend/exFp_localOp_reg[1]/CE` | -5.959 | 48 | 15.877 | 2.899 | 12.978 | — |
| 19 | `l1d/internal2_req_idx_reg[3]/C` | `backend/exFp_opMod_reg/CE` | -5.959 | 48 | 15.877 | 2.899 | 12.978 | — |
| 20 | `l1d/internal2_req_idx_reg[3]/C` | `backend/exFp_operation_reg[0]/CE` | -5.959 | 48 | 15.877 | 2.899 | 12.978 | — |

**单独标出的 TLB→S1 permission 路径（全局 worst-20 之外）**：

| Source | Destination | Slack ns | Levels | Data ns | Logic ns | Estimated route ns |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `mmu/dtlb/mem_ext/Memory_reg[3][264]/C` | `l1d/cpu2_pmpAllowed_reg/D` | **+0.441** | **31** | **9.583** | **3.018** | **6.565** |

原始 `tlb-to-s1-permission-worst-20.rpt` 使用 `-max_paths 20 -nworst 1`，实际返回10个不同 permission endpoints 的路径；不虚构20个端点。`Sv39Tlb.scala:39–66` 的同步 TLB 数据后仍有 CAM命中、entry/level选择及地址/权限组合生成，因此 paddr 不是寄存器直出。本轮31级、+0.441 ns是新的真实 post-synth 证据；旧整SoC checkpoint 的29级、+0.648 ns留作历史风险标记，边界不同不能直接作等条件A/B。它目前没有成为全局最差路径族，所以没有触发用户针对这一路径族的加拍裁定边界；仍不得把这点正裕量当作 routed 收敛证明。

### 利用率、DSP 与影子查询

Cluster：**47643 LUT**（46882 logic / 626 LUTRAM / 135 SRL）、**41242 FF**、**39 RAMB36 + 9 RAMB18**（43.5 tiles）、**23 DSP**。层级：backend25189 LUT / 7511 FF，l1d10187 LUT / 15901 FF / 4 RAMB36，mmu3875 LUT / 8052 FF；mulUnit1202 LUT / 221 FF / **12 DSP**。完整 utilization-hierarchical.rpt/utilization.rpt 保存。

`dsp-pipeline.tsv` 查询全部23个DSP：**mulUnit所属12个 AREG/BREG/MREG/PREG均为1**；另外11个属于既有FP单元，不纳入 M2 的 MulUnit 属性门槛。此次属性为 post-synth，routed属性验收仍未执行。对综合后全部层级 `NAME =~ *shadowPmp* || NAME =~ *shadowPma*` 查询：**shadow_cell_count=0**。M1的两份生产 checker 保留，仿真影子已随断言综合开关消失。

checkpoint：`/home/chen/FUN/flow-runs/soc3-1b17595/cluster-ooc/post-synth.dcp`；SHA256另存 post-synth-dcp.sha256。本地小型证据副本为 `records/soc3-20261008/1b17595/alan/cluster-ooc/`（DCP留在Alan），含完整reports/阶段原文/diagnosis-summary.json/source-manifest.json/synth.tcl/clock.xdc与命令/退出码。

### 当前根因证据与建议（未追加生产 RTL 改动）

当前最差路径为 **52级LUT/MUX控制串联**，data16.720 ns中 estimated route13.606 ns（81.4%）。网表链从 L1D 内部索引/命中、way/upgrade/分配判定，穿过 miss 与后端控制、late/writeback ready 仲裁、scoreboard实际完成clear/忙位及ID发射判定，最终到 sourceStall/事件选择与 HPM pending；另有共同前缀到 EX CE。路径中没有DSP乘法/CSA/CPA，也没有S2对最终s2地址的PMP组合检查。不能把末端HPM pending误判为64位计数加法慢：这个端点只是事件增量寄存器，报告中没有CARRY8。层级间优化会合并/重命名控制cell，不能凭FP实例前缀将其归为FP算术关键路径。

因此，**当前剩余瓶颈是 L1D完成/分配控制与后端仲裁、scoreboard的同拍组合依赖和扇出**；事件替换消除了宽比较，但没有消除这一条跨模块控制链。独立L1D正WNS不能排除它，独立MulUnit快速综合也不能证明所有ready/CE组合路径已收敛；本轮跨模块报告已给出实际失败证据。当前证据不支持为修最差族先替换乘法器16行CSA或改DSP级联，也未证明第一版综合耗时仅由一个模块导致。

建议下一轮只在现有寄存器边界内处理这条控制族：

1. L1D hit/way/upgrade/victim/alloc/hold条件并行化，减少outcome编码再解码的串行层数；按原优先级派生局部控制，不把kill或上下文事件变成全局S2门控，保留Replay、PTW/Recheck及同拍副作用。
2. 核对Scoreboard每个读端口，将“32 bit忙位整向量先按同拍clear掩码，再动态选rd”重写为读端口局部 `busy(rd) && !sameCycleClear(rd)`，把WB仲裁、bank/rd匹配与资源判定并行算。必须逐拍等价保持同拍clear可发射、WB授权/kill、仲裁和现有S01/S10等断言，不能靠寄存hold/ready或延迟clear缩短路径。结合真实cells再次量化每段减少的级数；若不能保持合同，停下报告。
3. 对仍只有0.441 ns的TLB→S1，候选为CSR写侧预解码PMP上下界/掩码并与CSR同沿更新，再用未改的影子检查覆盖；不在TLB响应后擅加级。后续若该族成为最差，按用户要求立即报告。

上述建议不改变 M2 的 **4拍**、T04/P02/T13/P10，不依赖retiming；本轮只完成已裁定的C1-B M1修复，其余建议尚未实施。**Cluster WNS<0，尚不满足启动整SoC tiny的门槛**：没有新整SoC构建、routed checkpoint或bitstream，没有进入§3.2。旧1e6e9bf tiny及watcher保持已停止状态。
