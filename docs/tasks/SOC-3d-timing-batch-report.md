# SOC-3d RTL 实现与验证记录

初始实现记录日期：2026-10-09。当时用户已批准第一批实现，并要求暂不测试；下方“修改内容”和“源码与静态检查”记录该时点。随后第一批定向功能子集通过，并在 Alan 启动单核/四核 LiteX/Vivado。CSR/PMP 与 L1D S2 控制链新候选完成后，用户授权补必要测试；新增 4 项及所选 39 项全部通过，含三个短集群程序。新候选与第一批执行证据分开记录，见文末。

## 修改内容

| 项目 | 本批实现 |
| --- | --- |
| TLB/PMP | CSRFile 同沿保存合法 PMP 新值与区间预解码；S0 提前计算末字节低位/进位，S1 直接比较区间并一热选权限。生产集群启用候选 PA 旁带，使页权限与物理权限并行计算；候选随反压保存，异常与事务地址仍由原响应决定。旧 checker 保留为独立 shadow 算法。 |
| Cache | 各来源独立计算 S0 资格，维持 Probe、Refill、WbRead、Replay、PTW、Recheck、CPU 优先级及现有互锁；维护授权直接驱动 grant，tag 写掩码直接使用一热 way。 |
| 记分板 | 操作数 busy 位与实际完成目的比较并行计算，同拍完成仍可解除依赖；来源统计单独计算，CSR drain 和 busy 更新规则保持。 |
| FPU | ADDMUL FP32 从 3 级改为 4 级，启用已有加法前边界；FP64 从 4 级改为 5 级，在归一化与舍入/状态之间保存未舍入值。新边界同步保存特殊结果、状态、舍入模式、UF 所需位、tag/mask/aux，并接入 ready、flush 和 busy。 |

整数后端级数、L1D S0/S1/S2、提交点保持。默认未启用候选 PA 的独立模块保留原 TLB 端口形状。已同步 backend-v1、MMU、L1D 规格；`tools/frozen.json` 仅更新本批获准修改的 MMU 规格摘要，未修改测试、golden 或既有断言条件。

## 源码与静态检查

- 工作目录：`/home/chen/leisure/flow`，分支 `feat/pcie-fase-20260920`。
- 起点主仓 SHA：`d364bacb959da13ea58178f919547c56aafda0e0`；本批尚未提交。
- CVFPU 子模块起点：`1b220f3bc89df99e246b72e3574a3a533cf87653`；修改了 `third_party/cvfpu/src/fpnew_fma.sv`，尚未形成新的子模块提交。仅用主仓起点 SHA 无法复现本批；本批文件摘要见 [source-manifest.json](../../records/soc3d-rtl-20261009/source-manifest.json)。后续验证必须使用包含这些改动的完整快照，并在最终验收前固定主仓与子模块版本。
- 本地静态检查：`git diff --check`、`git -C third_party/cvfpu diff --check`、`python3 tools/frozen_check.py` 均 exit 0，冻结清单检查为 `OK (8 files)`。这些检查不验证 Scala/Chisel/SV 编译或硬件行为。

既有工作区报告和记录未纳入本批修改。起点单核 WNS -1.798 ns、四核超时均是历史结果，本批没有新的时序结果。

## 后续

用户安排验证时，按 [分批计划](SOC-3d-timing-batch-plan.md) 将四项作为一个候选：先完成必要编译与相关功能门槛，再做一次单核完整布线；剩余失败路径决定第二批。仿真、编译、RTL 生成前重新读取共享主机配置；Vivado 使用 Alan。

## 2026-10-09 定向功能验证

结果：**选定 42 个测试用例全部通过**，其中一个集群测试运行三个自检程序。另对两个新增浮点用例补充最小正常数边界向量后重跑，2/2 通过。新增 7 个用例，既有用例通过过滤选择；没有运行 `sbt test` 完整回归。Scala 主源码/测试源码已编译，测试所需 Chisel RTL 与 CVFPU SystemVerilog 已生成并经 Verilator 编译、执行。此次没有修订 RTL，第一批实现记录中的所有主 RTL/CVFPU 文件摘要仍相同；没有修改既有 golden 或 RTL 断言。

### 来源、主机与命令

- 本地主仓分支 `feat/pcie-fase-20260920`，起点 SHA `d364bacb959da13ea58178f919547c56aafda0e0`；CVFPU 起点 `1b220f3bc89df99e246b72e3574a3a533cf87653`。两者仍含未提交改动，不能把起点 SHA 单独当作已测版本。
- 实际执行主机 `cloud_chen@47.96.71.231`（hostname `iZbp16rhtg91v96m32vggjZ`）。重读共享主机配置并检查免密连接、环境、资源、代理；环境检查 exit 0。独立 cwd `/home/cloud_chen/work/soc3d-tests-20261009/design`，远端证据 `/home/cloud_chen/evidence/soc3d-tests-20261009/`。
- 工具：Chisel 7.0.0、Scala 2.13.16、项目 sbt 1.9.7（启动器位于 `sbt-1.11.2/bin/sbt`）、Verilator 5.028、OpenJDK 11.0.32.1、GCC 13.3.0。完整检查输出见本地 `records/soc3d-tests-20261009/cloud/preflight-corrected.log`。
- 最终完整输入快照 [source-complete.tgz](../../records/soc3d-tests-20261009/source-complete.tgz)，含修改过的 CVFPU、平台 JSON 和三个程序的 ELF；[source-manifest-complete.json](../../records/soc3d-tests-20261009/source-manifest-complete.json) 的 SHA256 为 `1f3b6aee7a25aae02d0d5f410b59933278168a0b8aa6614cab15e86d75809f30`。结束后远端逐文件检查 **664/664**，见 [source-verify-final.json](../../records/soc3d-tests-20261009/cloud/source-verify-final.json)。报告/计划的本次状态更新是执行后的文档改动，不属于该输入快照。
- 全部选定命令保存在 [gates-selected.json](../../records/soc3d-tests-20261009/gates-selected.json)，执行形式为 `sbt -batch '<testOnly 命令>'`。成功作业的实际开始/结束 UTC、monotonic 用时、cwd、完整 argv、exit 与日志路径分别在 `cloud/validated/results.json`、`cloud/rest/results.json`、`cloud/fp-boundary/results.json`。所有下表作业 exit 0。
- 汇总 [acceptance.json](../../records/soc3d-tests-20261009/acceptance.json) 从原始 JUnit 核对 42 个唯一用例，并核对加验 2/2、9 个成功作业 exit 0、快照和归档摘要。成功作业累计实际用时 574.5 秒（约 9 分 34 秒）；不含准备、首次编译、主动停止与入口诊断时间。
- 预解码模型修正、补齐平台输入及浮点测试加验分段留档；各段输入清单与第一批 RTL 摘要对齐见 [source-bindings.json](../../records/soc3d-tests-20261009/source-bindings.json)。三段主 RTL/CVFPU 完全相同。生成 RTL、编译和仿真交互记录及最终 JUnit 见 `cloud/generated-test-evidence.tgz`；42 项的原始 JUnit 汇集于 `cloud/rest/test-reports/`，浮点加验日志另存 `cloud/fp-boundary/`。

### 需求与执行结果

| 要求与规格来源 | 激励、独立期望与测试 | 执行结果 / 本地日志 |
| --- | --- | --- |
| PMP 预解码与 S0 末地址（L1D spec SOC-3d 修订） | 新增 `BreezePmpDecodedSpec`：整数区间模型比较旧 checker、即时解码、寄存区间+预计算末地址；TOR/NA4/NAPOT、空区间、最低项优先、跨 128 B、权限/锁定与高地址；40 组配置，seed `0x3d504d50`。新增 CSR 用例检查同沿更新、TOR 前驱变化、locked TOR 锁住自身及前驱、NA4/NAPOT 新边界。 | PMP 1/1 + CSR 1/1；`cloud/validated/control.log`、`csr.log` |
| 候选 PA 不授权 fault/miss/kill（MMU spec candidatePaddr SOC-3d 修订） | 新增 `Sv39PmpCandidateSpec`：真实 MMU 的 4 KiB/2 MiB/1 GiB hit、页权限 fault 的候选非零/公共 PA=0、Bare 高地址、kill，PTW latency=3、随机反压 seed=61。新增 `FetchPmpCandidateSpec`：2 B 取指在 PMP 末端允许、4 B 跨界拒绝、pageFault/miss 不授予成功响应、重试、TLB/前端反压与 kill。 | 2/2；`cloud/validated/control.log` |
| 同拍实际完成解除依赖、CSR 用原始 busy（backend spec §2、§5） | `ScoreboardSpec` 既有四来源、f0/x0、RAW/WAW、跨 bank、CSR drain；新增完成一个目的不释放另一 bank/在途 producer。真实 `BackendContractSpec` 只选 T16、T17、P07、P08：完成同拍 ID 放行，CSR 晚一拍，乱序 flags OR=17，8 个 FMA II=1。 | 记分板 4/4 + 后端 4/4；`cloud/validated/control.log`、`cloud/rest/backend.log` |
| Cache 来源授权、一热 way 写掩码、所有权与 kill（L1D spec §4–8） | 只选 `L1DCacheSpec` 的 12 个场景：字节 store/probe、store miss replay、逐路替换/dirty WB、hit-under-miss、等待 MSHR 时 probe 前进、kill、probe+grant 同拍、refill error、TLB wait、PTW、AMO 升级/写沿 kill。保持独立 golden memory 和结束脏行比较。测试 harness 启用生产 `withPmpCandidate=true`，seed=1。 | 12/12；`cloud/rest/cache.log` |
| PMP/PMA/上下文变化不能留下过期授权（L1D spec SOC-3d 修订、§5） | `L1DPermissionsSpec` 5 项：>4 GiB 不别名、fault 同拍取消、MPRV/MPP、ROM atomics、PTW faults，无非法下游流量。`L1DContextSpec` 只选 SOC3 的 4 项：实际 CSR 立即生效、S1 同沿更新、S2 多拍 Hold 两方向更新、PTW 上下文变化仍恰好一个响应。真实 CSR/MMU/Cache 使用生产候选旁带。 | 5/5 + 4/4；`cloud/rest/permissions.log`、`context.log` |
| FP32 4 / FP64 5 内部级数，单次舍入与 sideband（backend spec §5） | `FpUnitSpec` 8 项，新增两项覆盖 FP32/64 五种 rm、正负 tie、signed zero、OF/NX、精确/不精确 subnormal、FMA 精确抵消、交替特殊/普通 S/D 结果、rd/flags、固定 seed `0x3df00d` 的输出反压；既有连续 FMA、跨组返回、commit-before-kill、fire/kill 与 tag 回绕保持。加验两种格式 min-normal tie 输出 UF/NX=3，以及更接近正常范围的积输出 NX=1。按规格仅在实际 fire 拍记账，不要求跨单元组合 mux 在反压中恒定。 | 8/8；加验 2/2；`cloud/validated/fp.log`、`cloud/fp-boundary/fp-boundary.log` |
| 真实集群连接与访存/陷入/MMIO | 复用 `ClusterWbSplitSmokeSpec`：single core、Linux profile、seed=1、无 AXI 随机反压；独立程序 `tohost` 与 console 判据不变。 | 一个用例 1/1；三个程序全通过；`cloud/rest/cluster.log` |

三个集群程序分别为 `lrsc_amo_single`（14410 cycles，1127 retired）、`trap_misc`（25587 cycles，2092 retired）、`mmio_console`（43389 cycles，4258 retired）；console 精确匹配原期望。

### 途中问题与证据边界

1. 最初 PMP 扫描量过大，按快速子集目标主动停止并收紧为代表性边界+固定 seed；记录在 `cloud/cancel-initial.json`。停止作业不计作通过。
2. 新增整数模型把全 1 的 54-bit `pmpaddr` 解释为 57-bit 区间。诊断明确显示旧 checker 在 `addr=2^56−1,sizeLog2=2` 拒绝而模型允许；继承的 checker 使用 56-bit 地址范围。修正新增模型的编码范围后，三条 checker 均通过，跨 `2^56` 仍须拒绝；日志 `cloud/pmp-diagnostic.log` / exit 1 与首次失败 JUnit 均保留。没有修改 RTL 或既有 golden/断言。
3. 首次 Cache 入口缺少快照中的 `config/breeze_mcu_platform.json`，suite abort、实际执行 0 项；补齐同一工作区的原配置后运行 12/12，通过。入口失败保留于 `cloud/validated/cache.log`，不算 DUT 失败，也未换主机。

本次覆盖四处修改的定向功能风险及有限系统联动，不是完整回归、数值穷举或形式证明。测试中生成/编译 RTL 不等于 FPGA 综合、BRAM/资源验收、routed setup/hold 或上板证据。本次没有运行 Vivado，尚无新的 WNS/TNS、bitstream 或上板结论。相关功能门槛已通过；进入原计划的单核完整布线前，仍须固定包含主仓与 CVFPU 改动的版本，并使用对应源码、100 MHz 约束和 Alan 作业证据。


## 2026-10-09 单核与四核 LiteX / Vivado 启动

用户在定向功能子集通过后授权单核和四核并行启动，随后暂停准备、确认使用 LiteX，并授权继续。固定主仓 `d276f8bbd03bc86873063331920e8489f6e658f2`、CVFPU `3cbca77e2bab5546ac75edfbe6f056c0a14d7fc9`；两者已推送，硬件源码与已通过功能子集的输入逐文件哈希相同。此前“不运行 Vivado”描述仅适用于定向功能验证阶段。

- RTL 生成在 cloud_chen 执行：`single gshare linux` / `small gshare linux`，production、tandem=false、debug=false，分别 1 / 4 核。生成 exit 0，实际 UTC 02:55:31.672146–02:56:02.913127，wall time **31.240966 秒**。命令与日志见 [generation-result.json](../../records/soc3d-vivado-20261009/generation-result.json)、[generation.log](../../records/soc3d-vivado-20261009/generation.log)。归档 SHA256：single `54bcbe6bcb701b9ed2b0778d951851bd29b63bec009c9118dca4e923216fca61`，small `66dfd55c92cd634b25c8a87fd5d2b8ad7c28666cb9965fd5c788076981b31da6`；Alan 安装前再次核对归档与各源码摘要。
- Alan hostname `chen-System-Product-Name`，环境与 LiteX FPGA imports 检查通过；Vivado **2022.2**，RISC-V GCC **13.2.0**。启动前没有其他 Vivado 作业，磁盘约 111 GiB、可用内存约 56 GiB。
- 入口仍为仓库 `fpga/kcu105/target.py` 的 LiteX `SnapshotBuilder.build`，由 LiteX 生成 SoC、BIOS、约束并启动 Vivado。外层 `build-soc.py` 只添加 `general.maxThreads=4`，外层 `alan-stage.py` 记录 SHA/cwd/argv/进程身份与 wall time，并设置每项一小时 timeout。器件 `xcku040-ffva1156-2-e`、100 MHz、现有综合/布局/布线 directives 保持。没有启动额外 OOC 作业。
- 单核 cwd `/home/chen/FUN/flow-soc3d-d276f8b-tiny`，CPU `breeze-tiny`；四核 cwd `/home/chen/FUN/flow-soc3d-d276f8b-small`，CPU `breeze`；独立输出 `/home/chen/FUN/flow-runs/soc3d-d276f8b/{tiny,small}`。
- 两项 LiteX 构建实际开始时间分别为 UTC **03:02:34.517874** / **03:02:34.521270**（北京时间 **11:02:34**）。已确认两个实际 Vivado 进程运行。启动观察不构成综合、routed timing、bitstream 或上板通过结论。
- 完整 argv、driver PID 与外层脚本摘要见 [launched.json](../../records/soc3d-vivado-20261009/launched.json)，启动时进程身份/日志/状态见 [startup-observation.json](../../records/soc3d-vivado-20261009/startup-observation.json)。远端各 `soc-{tiny,small}-job/command.json` / `run.log` 保存精确起点与日志，完成时自动写 `result.json` / `exit`，包含真实结束 UTC、monotonic wall seconds 与 exit code；wall time 从各 LiteX 构建入口计起，包括 BIOS 和 Vivado，不按轮询次数估算。

准备期间补齐缺失的 CVFPU 子模块与外层脚本执行权限；这些问题均发生在构建计时前。暂停状态保存在 `records/soc3d-vivado-20261009/paused.json`，后续启动以 `launched.json` 为准。当前仍在运行，最终 routed setup/hold、资源和 bitstream 结果待取证。


### 2026-10-09 11:37 解除运行中作业的时限

用户明确要求取消时限且不重跑。北京时间 **11:37:22** 已解除两项现有作业的一小时限制；这项最新指令覆盖上文启动时的一小时策略。只向各自的 `timeout` PID 400138 / 400140 发送 `SIGSTOP`，没有向进程组、LiteX 或 Vivado 发送暂停/终止信号。单核 Vivado PID **401463**、四核 PID **401501** 及 `/proc` start_ticks 均保持不变，原始起点 11:02:34 与输出目录保持。

Alan 的 ptrace 策略不允许直接修改既有 `timeout` 进程内部定时器，因此停用外层监控，启动独立退出记录器 PID 423935；它没有时间上限。记录器以 pidfd 监听原有 LiteX 进程 400139 / 400141 的实际退出，读取保留的 kernel wait status。该机制先以独立的 exit 0 / exit 37 小进程验明，未编译或重跑硬件。机制参考 [GNU timeout 9.4 源码](https://raw.githubusercontent.com/coreutils/coreutils/v9.4/src/timeout.c)、[pidfd_open](https://man7.org/linux/man-pages/man2/pidfd_open.2.html)、[proc_pid_stat](https://man7.org/linux/man-pages/man5/proc_pid_stat.5.html)。

今后每项的真实构建结果读取 `soc-{tiny,small}-job/build-result.json` / `build-exit`，包括实际退出码、原起点、结束时刻和累计 wall time；[poll-vivado.py](../../records/soc3d-vivado-20261009/poll-vivado.py) 已优先读取这些结果及 `build-process.json`。累计时间使用原起点到接管时的实际 UTC 差加接管后的 monotonic 时长，原始外层记录器的 monotonic 总时长仍留存。原 LiteX 退出之后才清理停止的 timeout；旧 `result.json` 的监控器退出 -9 是清理动作，不能当作 Vivado/DUT 失败，不能替代 `build-result.json` 的实际退出状态。原始命令与启动版本文件继续保留，当前有效超时策略以 `timeout-removal.json` 为准。

接管证据见 [timeout-removal.json](../../records/soc3d-vivado-20261009/timeout-removal.json)、[timeout-removal-observation.json](../../records/soc3d-vivado-20261009/timeout-removal-observation.json)，脚本与 SHA 在同一 evidence root。停用的 timeout 不应被单独 `SIGCONT` 恢复；作业结束后记录器会自动清理它们。此次没有重启、重新生成或重新构建。


### 2026-10-09 12:15 进展（非最终报告）

两项累计 wall time 约 **1 小时 12 分 44 秒**，原 Vivado PID 401463 / 401501 继续运行，时限解除生效，未重跑，日志无 `ERROR`。单核已完成首轮 `route_design` 并进入布线后 `phys_opt_design -directive default`：该次 route 日志摘要 WNS **-0.847 ns**、TNS **-2743.030 ns**、WHS **+0.030 ns**、THS **0.000 ns**；后续优化日志 sys_clk WNS 已出现 **-0.714 ns**，仍是过程中数值，不能替代最终 routed 报告。当前 setup 尚未闭合。四核进入 `route_design` Phase 4.2 Global Iteration 1（Rip-up And Reroute），无最终时序报告。两项尚无 bitstream。观察见 `records/soc3d-vivado-20261009/progress-121518.json`。


### 2026-10-09 12:20 现有路径证据分析（综合与布线分开）

当前 `tiny/gateware` 仅有综合详细时序报告与 synth/place 检查点；尚无 route 检查点、routed 详细路径报告。LiteX Tcl 先执行 `route_design`，再 `phys_opt_design -directive default`，之后才写 `xilinx_kcu105_route.dcp` 和详细时序报告。因此现在无法还原首轮 route 的完整最差路径；日志的“Processed net”也不等于该网就是整条最差路径的起点/终点。

已取回同版本综合报告 [single-timing-synth.rpt](../../records/soc3d-vivado-20261009/single-timing-synth.rpt)。sys_clk 的综合最差 setup 路径为 `l1d/internal2_paddr_reg[14]/C` → `backend/csrFile/pmpRanges_7_nonempty_reg/D`，10.000 ns 要求、slack -1.534 ns、data path 11.369 ns，其中逻辑 3.383 ns、**综合估计**线延迟 7.986 ns（70.244%）、36 级（7 个 CARRY8）。这些数值不能替代首轮 routed WNS -0.847 ns 的路径分析。

该综合网表路径依次经过 L1D miss 的 lineAddr/way/upgrade/wAf/recheck、core response valid、WB trap/CSR 控制、pmpaddr next-state 选择，最后经过 PMP lower/upper/nonempty 解码并进入预解码寄存器。可直接核对报告行 395–525。RTL 对应 `core/RegFile.scala` 中 nextPmpCfg/nextPmpAddr 受 commit/trap/锁定/WARL 条件选择，而 `pmpRanges` 同拍对 next state 解码更新（823–831）；这提供“PMP 预解码写入端的组合依赖”这一具体候选瓶颈，尚不能宣布它是首轮 routed 最差路径，也未授权新增 RTL 或时序例外。

布线后物理优化日志的热点包括 dTLB 存储读地址/candidate PA、L1D PMP/PMA、CSR pmpRanges、L1D tag 与 miss 逻辑。12:20 附近工具输出 Current Timing Summary：WNS -0.674 ns、TNS -2584.971 ns、WHS +0.030 ns、THS 0.000 ns，属于当前优化过程摘要。最终路径报告写出后，需核对实际起终点、logic/routing 占比、扇出与最差十条，而不能用综合估计或历史 dTLB→PMP 路径替代。


### D-cache / WB / CSR 阶段边界审阅建议（讨论，未改变冻结合同）

结合当前综合路径与 RTL，建议优先审查跨模块控制边界：L1D CPU/internal S2 共用 `s2` 与 outcome 决策，`resp/s2Hold` 同拍送入 WB；`BreezeBackend.scala:157–167` 的通用 WB commit 又进入 CSR next-state 选择；`RegFile.scala:491–724` 的 nextPmpCfg/nextPmpAddr 已包含 commit/trap/锁定/WARL 门控，再于 823–831 对 next state 做完整 PMP 区间解码。因此，PMP 访问端前移之后，预解码更新端仍可能承接很晚的缓存/WB 控制。综合最差路径来自 internal2，不是普通 Load hit 的独立路径证据。

建议顺序：① 让 CSR 地址/写数据/现有锁定与 WARL 状态独立生成候选 PMP 区间，将最终提交许可留在寄存器末端；必须保持 pmpaddr/pmpcfg/range 同沿、TOR 前驱联动及原锁定语义，目标是不增加架构延迟。② 审查普通命中判定、内部回放/重查与 miss 资源控制的组合共享，压缩共用 outcome/hold 的逻辑锥；已有容量 Boolean 与 victim 选择拆分应继续保持，并由综合网表验证。③ 最终 routed 路径若仍证明 S2 到提交端过重，再评审寄存完成边界；优先评估慢路径局部边界与统一增加提交级的不同成本，不预先宣布全局加级。

任何新增完成级都必须随请求身份一起对齐 kind/data/exception/age，重新安排 kill、MSHR/PS/way 所有权与背压容量；不能只打一拍 resp.valid。内部流水必须继续在 CPU S2 等待时推进，否则会堵住释放 MSHR 所需的 refill/probe/PTW。统一加级还会改变 E+2 判定即 WB 提交、load-use、精确异常与 store 提交时刻，需先重新裁定任务合同；目前仅为建议，没有修改 RTL、规格、约束或运行中作业。访存与 CSR 写属于不同指令类型，跨到 CSR 的综合链还可能含共享 commit 门控造成的互斥依赖，应优先改善 RTL 结构，不据此自行加 false_path。

本轮预解码实现对更新端组合链隔离考虑不足；这比仅按 D-cache 级数决定加流水更明确。最终判断仍需当前 routed 最差十条。FPGA 原则参考 [UG949 2022.2：Check Inferred Logic](https://docs.amd.com/r/2022.2-English/ug949-vivado-design-methodology/Check-Inferred-Logic)：按实际 fan-in、运算与布线逻辑锥评估流水寄存边界，而非按模块名字计级数。


## 2026-10-09 控制链候选：RTL 已修改，测试延后

用户授权先落实上述前两项，测试下一次加。本次工作位于 `/home/chen/leisure/flow`、分支 `feat/pcie-fase-20260920`，基于主仓 `d276f8bbd03bc86873063331920e8489f6e658f2` 的未提交修改；CVFPU 保持 `3cbca77e2bab5546ac75edfbe6f056c0a14d7fc9`。只修改两个主 RTL 文件，未修改后端提交边界、流水寄存器、浮点或约束。同步 L1D spec 的实现说明，不改冻结拍数合同。

| 文件 | 修改及保留的边界 |
| --- | --- |
| `design/src/main/scala/core/RegFile.scala` | `candidatePmpCfg/Addr/Ranges` 只依赖 CSR 地址、写数据与已有 PMP 状态；不包含 `commit_valid/commit_write_en/trap.valid`。原 `pmpStateWrite` 在寄存器末端同沿更新配置、地址和区间。保留 R=0 时 W 清零、保留位清零、自身 lock、locked TOR 对前驱的 lock、TOR 候选前驱联动、无效/陷入写保持和原 permissionEvent。 |
| `design/src/main/scala/l1d/L1DCache.scala` | 将共享 outcome 优先 Mux 与后续枚举比较改为局部 Boolean 条件。按原顺序限定 refresh/internal/replay 与 CPU/原子/FENCE 等路径，分别生成完成、异常、重查、MMIO、AMO、PTW、MSHR 和等待条件；普通 hit、PS 容量与 miss 容量独立计算，victim/data 选择不进入分配容量。所有原 outcome 消费点改用对应条件，副作用的 kill/error、PTW/回放目的、快照失效、LR/SC reservation 与原断言保持。 |

静态审阅逐项对照原决策分支：回放错误优先于 AMO；成功 Store 回放等待 PS、错误回放不写 PS；CPU 被内部占用时保持而内部仍推进；RMW 完成、atomicWait、FENCE 均先于重新查询；过期快照先重查再用权限；权限异常先于 MMIO/SC；SC reservation 失败可完成且不分配 MSHR；same-line 资源等待先于普通 hit/miss；PTW 与 CPU 使用原各自容量条件，LR/AMO miss 仍保持到回放。这是人工源码审阅，不是功能等价证明。

本地检查只执行 `git diff --check` 与 `python3 tools/frozen_check.py`，均 exit 0；冻结检查 `OK (8 files)`。精确 RTL 补丁、两个文件摘要、base/CVFPU SHA、命令/cwd/exit 与检查日志记录于 [control-boundary/source-manifest.json](../../records/soc3d-rtl-20261009/control-boundary/source-manifest.json) 和同目录的 `rtl.patch`、`static-checks.log`。未编译、未生成 RTL、未新增或运行测试，也未新启 Vivado；功能与时序改善均待验证。第一批 42 项通过结果不能移用于此新候选。

第一批单核/四核作业的固定输入、原 wall time 起点及无限时限策略均保持；本次未操作远端进程或输入，因此它们的结果只评价第一批 `d276f8b`。下一次验证需先覆盖 PMP 被禁止提交/同拍 trap 的状态保持、锁定与 TOR 联动，以及 S2 回放错误、PS 满、PTW refresh、SC fault/失败、MMIO、probe/refill 与 CPU 资源等待交叠，再决定新候选的物理构建；本次不执行这些测试。


## 2026-10-09 控制链候选：必要测试与定向验证完成

用户随后授权补必要测试。本轮新增 **4 项**，在 cloud_chen 运行所选 **39 个唯一用例，39/39 通过**；其中一个集群用例运行三个短自检程序，均通过。没有执行完整回归，没有修改既有 golden、断言或冻结拍数，没有因失败修订 RTL。验证的两个主 RTL 文件与上一节控制链实现摘要完全相同。

### 输入与执行证据

- 本地 cwd `/home/chen/leisure/flow`、分支 `feat/pcie-fase-20260920`；基于 `d276f8bbd03bc86873063331920e8489f6e658f2` 的未提交 RTL/测试改动，CVFPU `3cbca77e2bab5546ac75edfbe6f056c0a14d7fc9` 保持且工作区干净。仅用 base SHA 无法复现本候选，须使用本轮输入快照。
- [source.tgz](../../records/soc3d-control-tests-20261009/source.tgz) 与 [source-manifest.json](../../records/soc3d-control-tests-20261009/source-manifest.json) 保存 665 个实际输入文件；manifest SHA256 为 `d4434a85198908dbc4d560489607dd049484baa14064fab673501d5f96aeca75`。每个门槛执行前逐文件校验，结束时再次 **665/665**；[source-verify-final.json](../../records/soc3d-control-tests-20261009/cloud/source-verify-final.json)。两个 RTL 与此前修改的绑定见 [source-bindings.json](../../records/soc3d-control-tests-20261009/source-bindings.json)，精确主 RTL/测试补丁另存 `rtl-tests.patch`。
- 实际执行主机 `cloud_chen@47.96.71.231`，hostname `iZbp16rhtg91v96m32vggjZ`；独立 cwd `/home/cloud_chen/work/soc3d-control-tests-20261009/design`，远端证据 `/home/cloud_chen/evidence/soc3d-control-tests-20261009/`。每个门槛前本地脚本重读共享主机配置并验证 SSH；启动前检查环境、磁盘、内存与代理。旧代理断开，恢复反向转发后预检通过，见 `preflight-corrected.log`。已有构建缓存复制到独立工作区使用，未写入旧测试工作区或 Alan Vivado 输入。
- 实际工具：sbt 1.9.7、Verilator 5.028、OpenJDK 11.0.32.1、GCC 13.3.0；固定项目配置为 Chisel 7.0.0、Scala 2.13.16。首次日志确认两个 RTL 和两个测试 Scala 文件均重新编译；所选测试的 RTL 生成、Verilator 编译与仿真成功。
- 完整命令见 [gates.json](../../records/soc3d-control-tests-20261009/gates.json)，均为 `sbt -batch 'testOnly ...'`。七项作业各自的 cwd、argv、实际 UTC 起止、monotonic wall seconds、exit 和日志路径在 `cloud/<gate>-result.json`，全部 exit 0。实际执行 UTC **04:43:53.529098–04:52:19.616784**，北京时间 **12:43:53–12:52:19**；七项作业累计 wall time **498.360 秒（8 分 18.36 秒）**，首项开始到末项结束 **506.088 秒**，不含准备与归档时间。
- [acceptance.json](../../records/soc3d-control-tests-20261009/acceptance.json) 从每项独立保存的原始 JUnit 核对 39 个唯一测试、无失败/跳过及三个成功程序。日志、JUnit、生成 HDL 与模拟器构建记录归档到 `cloud-evidence.tgz`（SHA256 `c00e4bceeea98b2eefbc1a705c96f2f8304fd4aaf4ea2a948615a7c6385adaa6`），取回后已核对摘要；生成记录另在 `cloud/generated-test-evidence.tgz`。本节、计划和 L1D spec 的验证状态更新发生在执行之后，不属于已执行的输入快照；主 RTL/测试未改变。

### 风险与用例对应

| 风险 / 规格 | 激励与独立期望 | 实际结果 |
| --- | --- | --- |
| PMP 最终提交门控（CSR 写/trap 优先级；L1D spec 控制链修订） | **新增**无效 commit、write_en=0、同拍 trap 等四种许可组合，分别驱动 cfg/地址/TOR 前驱候选并连续保持 3 拍；所有 raw PMP 与区间均应保持，permissionEvent 只按实际 trap 更新。之后合法写应生效，禁止的锁定位候选不能暗中锁住 CSR。整数区间模型独立检查所有 8 项。 | `csr` 3/3，含新增 2 项与既有 TOR 同沿/锁定用例。 |
| PMP 连续写、WARL、lock 和 TOR 联动 | **新增**连续无空拍写，seed `0x3d504d51`；32 次固定随机写加显式 TOR 正/空区间、锁住自身和 TOR 前驱、非活动 CSR 写。逐沿比较独立软件 WARL/锁定模型的 raw state、上下界、nonempty 与块前驱。 | 同上；`cloud/csr.log`。 |
| SC reservation 失败与 MSHR 等待的优先级（L1D §5、§8） | **新增**seed=61：下游 Get 被反压，MSHR 持有与 SC 相同的行；未建立 reservation 的 SC 应独立返回 Done/1，不新增 GetM，年轻已有 hit 能完成，原 miss 尚未返回。释放后检查原 GetS 恰一次及 golden 数据。 | `cache-new` 2/2，含新增 2 项。 |
| CPU 重查与内部 PTW/probe/refill 的推进与响应归属（L1D §5–7） | **新增**seed=62：store miss 被反压，年轻同一行 load 保持；PTW 等候其预留容量，另一个驻留行 Inv 能先完成；释放后 CPU 重查、PTW、PS 与 FENCE 排空均完成，PTW 恰一个响应，CPU 无重复/无主响应，最终脏行对照独立 golden。 | 同上；`cloud/cache-new.log`。 |
| 其余 S2 优先区及副作用（L1D §4–8） | 复用并过滤 18 项：各种 Load 格式、Store hit/miss/升级、MSHR/同一行等待、probe 前进、FENCE 持有年轻 Store 时 Inv/Down、kill、grant+probe、refill error、MMIO 数据/strobes/error、PTW hit/miss、LR/SC、AMO 冷 miss/升级、原子权限与错误回放、RMW 写沿 kill。保留 PS 容量/所有权等原断言和最终脏行 golden 比较。 | `cache-boundaries` 18/18。 |
| 上下文事件与权限异常（SOC3 permission snapshot；L1D §5） | 复用真实 CSR/MMU/cache 的 4 项 SOC3 上下文更新测试，覆盖 CSR 紧邻访存、S1 同沿更新、S2 多拍保持、PTW 刷新；另跑 5 项地址/权限/原子/PTW faults 和同拍故障取消用例。 | `context` 4/4，`permissions` 5/5。 |
| 响应/提交拍数与年龄对齐（冻结 backend timing contract） | 只选 T02、T03、T08、T09、T10、T22，保持精确拍数与 load-use、MSHR busy、普通 hit/late/ADD 对齐判据。该测试台用行为缓存环境，真实后端/cache 连接由下一行补验。 | `backend` 6/6。 |
| 单核真实集群：原子、异常、MMIO | 复用 `ClusterWbSplitSmokeSpec`：Linux single、seed=1、无 AXI 随机反压，三个 ELF 与既有 tohost/console 期望不变。 | `cluster` 1/1；`lrsc_amo_single` 14410 cycles/1127 retired，`trap_misc` 25587/2092，`mmio_console` 43389/4258，console 精确匹配。 |

这些结果覆盖控制链重构的选定功能风险和有限集群联动，不是完整回归、形式等价证明或多核随机压力；也未单独统计所有 S2 内部 Boolean 分支的覆盖率。PS 安全性由所选并发场景中的原容量/所有权断言与 golden 检查约束，不声称穷举 PS-full 组合。此次没有新启、停止或重启 Vivado，没有综合/布局布线/bitstream/上板证据；第一批物理构建结果仍只对应 `d276f8b`。
