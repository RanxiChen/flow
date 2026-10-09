# SOC-3d 第一批 RTL 实现记录

初始实现记录日期：2026-10-09。当时用户已批准第一批实现，并要求暂不测试；下方“修改内容”和“源码与静态检查”记录该时点。随后用户授权增加必要测试并只运行子集，定向功能验证已完成，结果见本页末尾。尚未运行综合或布局布线，没有新的 100 MHz 时序结论。

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
