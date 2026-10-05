# T01 报告：阶段一

日期：2026-10-05。状态：**阶段一文档已完成，spec 未冻结；阶段二未开始**。

## 1. 分支、提交与交付

分支：`feat/pcie-fase-20260920`。

| 提交 | 内容 / 状态 |
| --- | --- |
| `d5672f51bf0ec67465148c02af970c70464bec68` | 第 0 步：限定文件内的已有改动与已暂存删除，提交信息为 `Freeze v1 microarchitecture design docs, remove BreezePipelinedDCache, add T01 task`；已 push |
| `59e91fca778f62a40219165996bf976a167c144e` | 阶段一 spec 和 testplan，提交信息为 `Document T01 stage-one backend RTL specification and test plan`；已 push |
| 承载本报告的独立提交 | 仅添加本报告；其 SHA 可由 `git log -1 --format=%H -- docs/tasks/T01-report.md` 取得，提交/push 后在最终回报给出，避免在文件中写自身尚不存在的提交号 |

交付文件：

- [backend-rtl-spec.md](../backend-rtl-spec.md)：现状映射、整数记分板、拍级提交/取消/写回、统一接口、仲裁、异常/CSR/栅栏/WFI、MUL/DIV、保留行为、S01–S16 仿真断言、F01–F14 形式化性质、计数器、Q01–Q18 未决问题。
- [backend-testplan.md](../backend-testplan.md)：27 条需求追踪，T01–T23 定向场景、U01/U02 算术等价性、A–D 随机组、形式化/全回归/ACT4/tandem/资源时序/性能计划。
- 本报告：[T01-report.md](T01-report.md)。后续阶段二必须在明确冻结后另行追加报告，不能把本轮静态检查当硬件验收。

## 2. 实际操作、机器、结果与日志

执行机器：本地 `chen`，cwd `/home/chen/leisure/flow`。本轮未连接 Alan 执行构建或验证。下列 Git 输出来自实际工具输出；完整源码再读取与静态检查记录保存在本地 `/tmp/flow-t01-stage1-20261005/`，没有暂存这些日志。

| 实际命令 / 操作 | 机器 | 结果 | 证据 |
| --- | --- | --- | --- |
| `pwd`、`git status --short --branch`、`git diff --cached --name-status` | chen | 完成，确认指定分支与已有暂存删除 | 本轮会话工具输出；最终复核读命令见 `commands.log` |
| `git add -- agent.md docs/dcache-pipeline-design.md docs/backend-pipeline-design.md docs/frontend-prediction-design.md docs/observability-design.md docs/breezefrontend.md docs/tasks/T01-backend-scoreboard-mdu.md` | chen | 完成，仅加七个指定路径，保留原暂存删除 | 原暂存列表与 commit 文件清单；`commands.log` 末尾有第 0 步实际 diff |
| `git diff --cached --stat` | chen | 完成，第 0 步暂存范围复核 | 会话工具输出 |
| `git commit -m 'Freeze v1 microarchitecture design docs, remove BreezePipelinedDCache, add T01 task'` | chen | 成功，`d5672f5`，50 个文件 | Git 提交可重查；会话原文 `[feat/pcie-fase-20260920 d5672f5]` |
| `git push origin feat/pcie-fase-20260920`（第 0 步） | chen | 成功 | 原文 `805612a..d5672f5  feat/pcie-fase-20260920 -> feat/pcie-fase-20260920` |
| `cat agent.md docs/tasks/T01-backend-scoreboard-mdu.md docs/backend-pipeline-design.md` | chen | 完整阅读 | `commands.log` 第一条读取记录 |
| `cat docs/breeze-mmu-rtl-spec.md`；`nl -ba docs/dcache-pipeline-design.md`；`nl -ba docs/observability-design.md` | chen | 完成；MMU 用作写法范例，D-cache 2.3/2.9 用作接口/提交边界，后端计数事件按 2.6 | `commands.log` 各命令均有实际输出与 exit |
| 按需 `cat` 四个允许的技能入口、共用 context、testplan schema/template、formal strategy | chen | 完成，未引入其他硬件技能 | `commands.log` 有完整文件名与逐条命令 |
| `rg`/`nl -ba`/`sed -n` 的源码、测试、bug、HPM、ACT4 入口审查 | chen | 完成，只读；引用源码最终重读记录逐条列出 | `commands.log`：39 条读取/复核命令（含上述必读文件），每条保存 cwd、argv、exit、原文 |
| `rg -n 'rdData\|case class Commit\|class .*Tandem\|compare' sim/flow_lib/src/main/scala -g '*.scala'`（探索参考比对入口） | chen | 路径不存在，后改用 `rg --files sim/flow_lib` 核查；未发现可以据此确认的 checker | 会话工具输出；没有把路径不存在记成验证通过，tandem 入口写未确认（Q18） |
| `apply_patch` 新建/修正文档 | chen | 完成，仅编辑三个交付 Markdown 文件 | 对应 Git 提交差异；未修改 `.scala/.py/.sv` |
| `python3 -`（内联静态检查：源码行号边界与本地文件链接） | chen | 首轮发现三处引用上界超过文件长度，已修正；最终通过 | 首轮会话输出；最终结果 `static-check.log` |
| `python3 -`（内联审计：逐条读取日志、第 0 步白名单、代码 diff、Q/REQ/S/F 编号与性质追踪、文档 SHA256） | chen | 通过，errors=[]；69+24 处源码行号引用，18 个 Q、27 个 REQ | [静态检查日志](/tmp/flow-t01-stage1-20261005/static-check.log)、[读取与命令日志](/tmp/flow-t01-stage1-20261005/commands.log) |
| `git diff --name-only d5672f51bf0ec67465148c02af970c70464bec68 -- '*.scala' '*.py' '*.sv'`；`git diff --check` | chen | 代码差异为空，已跟踪改动无空白错误；新增文档另用 cached check | `commands.log`；会话工具输出 |
| `git add -- docs/backend-rtl-spec.md docs/backend-testplan.md`；`git diff --cached --check`；`git diff --cached --name-status`；`git diff --cached --stat` | chen | 完成，check 通过，仅两份文档，共 517 行 | 会话工具输出 |
| `git commit -m 'Document T01 stage-one backend RTL specification and test plan'` | chen | 成功，`59e91fc` | Git 提交/会话工具原文，两份新增文档 |
| `git rev-parse HEAD`；`git rev-parse origin/feat/pcie-fase-20260920`；`git show --format=fuller --stat HEAD` | chen | 完成，确认文档提交号与文件范围 | 会话工具输出 |
| `git push origin feat/pcie-fase-20260920`（阶段一文档） | chen | 成功 | 原文 `d5672f5..59e91fc  feat/pcie-fase-20260920 -> feat/pcie-fase-20260920` |

本报告提交与最终 push 的结果在该操作后由最终回报和本地 `delivery.log` 记录；不在提交前预填成功。读取日志只是源码快照与操作证据；静态检查只核验行号边界、文件链接、编号、范围与差异，**不证明文字语义、RTL 功能或测试覆盖通过**。

| 硬件验收命令/类别 | 机器 | 本轮结果 |
| --- | --- | --- |
| 改造前/后完整 `sbt test`，模块 `testOnly` | Alan | **未运行**；基线 suite/test 通过数未确认 |
| RTL 生成、ChiselSim/其他仿真、形式化 SBY/Yosys | Alan | **未运行**；BMC 深度/归纳/活性结果未确认 |
| ACT4 RV64IM、tandem、随机程序逐条参考比对 | Alan | **未运行**；完整 tandem checker 入口未确认 |
| Vivado DSP/LUT、100MHz WNS/路径、两个密集程序性能计数 | Alan | **未运行**；没有本轮资源/时序/性能数字 |

## 3. 与规格的偏离

无：只完成 T 第 2 节文档任务，没有自行补设计、修改代码或启动阶段二。spec 中的未知行为以 Q 编号标识，不能作为默认实现规则。

需要审阅时注意：除法特殊结果已经存在于后端预处理路径，证据为 `design/src/main/scala/backend/BreezeBackend.scala:905-955,741-752`；新工作在于 EX 发起/WB 授权/kill/结果保持协议。`CORE-003` 记录仍为 open（`docs/bugs/CORE-003.md:7-11`），当前已修复/通过状态写“未确认”，没有猜测或修改 bug 文件。

## 4. 未决问题

完整来源、影响和需决定内容见 [spec 第 12 节](../backend-rtl-spec.md#12-未决问题冻结前逐项处理)。以下均未决定：

| ID | 审阅项 |
| --- | --- |
| Q01 | 事务身份、标签宽度/复用、逐笔与多笔 kill、ID/EX/WB/FU 匹配 |
| Q02 | 同拍 set/clear/kill 优先级、释放当拍的 ID 放行与旁路 |
| Q03 | resolve 握手与 early done 可见性、commit/kill/done/推进/异常预约清理 |
| Q04 | MUL 反压结构/容量/ready、EX 单次发起、各流水级 enable 与保持 |
| Q05 | DIV 快速路径归属/接口、迭代与端到端延迟、释放同拍再接收 |
| Q06 | WB 不同类别让拍/提交、trap/xRET/WFI 与后台写同拍、中断接受边界 |
| Q07 | 访存/FP 脉冲响应在 WB 反压时的保存、FPR/fflags 对齐 |
| Q08 | 全部真实 GPR 源/目的 decode 名单、FP 跨 bank 依赖、held EX 旁路 |
| Q09 | 公共 `interface/` 文件范围、局部侧带/事件接口、旧字段保留/删除 |
| Q10 | FASE empty/enter/launch/读写/记录与后台 MDU 的交互 |
| Q11 | rd=x0 的执行/身份/释放、是否占写口与冲突计数 |
| Q12 | 来源顺序/公平性、低优先级结果的前进保证 |
| Q13 | 事件 ID、HPM 接入及重叠停顿/CSR/其他 hold 的归因口径 |
| Q14 | reset、WFI 睡眠时钟/写口、ESTOP/结束时后台排空 |
| Q15 | 与新合同冲突的旧断言/延迟/观测/非法 selector 检查等强迁移许可 |
| Q16 | DSP48E2 目标与 KU040/工具支持、综合顶层/config/约束/retiming |
| Q17 | CORE-003 当前修复/验证状态与同机完整回归基线 |
| Q18 | 迟到结果与原退休关联、tandem checker/参考模型入口、可能的范围变更 |

## 5. 已知限制与后续门槛

- 文档是待审阅稿；未决问题未解决、spec 未冻结，尚不能直接实现完整寄存器/FSM/ready 方程。
- 本轮阶段一没有任何硬件运行证据；task 验收尚未开始。完整源码行号为固定基线，未来代码改变要刷新。
- `.agents/`、`docs/figures/`、`docs/cross-project/`、`docs/hardware-skill*`、`docs/plans/2026-10-04-*` 均未暂存。技能/context 为本地只读输入，本报告不把它们当作已提交交付文件。
- 使用的四个技能只辅助分析与文档；没有新增其他硬件技能需求，没有加载其实现/验证执行流程。
- 到此停止。只有用户明确宣布阶段一 spec 冻结后，才能按冻结版本和已批准范围开始阶段二。

## 6. 阶段一审阅修订记录（2026-10-05）

本节是最新状态；前面第1–5节记录初稿交付历史，其中原Q01–Q18列表及旧规格锚点不再表示当前未决项。当前spec为**冻结候选，待用户确认**。阶段二仍未开始；没有修改任何 `.scala/.py/.sv`，没有运行硬件验证。

### 6.1 提交、分支与交付

分支仍为 `feat/pcie-fase-20260920`。

| 提交 | 内容 / 结果 |
| --- | --- |
| `ea040b0ddf0247685769ff386ce09bda443275c5` | 上轮初稿报告提交，已push（历史记录） |
| `d73a546a9f1acd51a4c99b20d3985d451a8d8be0` | 本轮第一步仅提交 `docs/tasks/T01-review.md` 和 `docs/backend-pipeline-design.md` 第5/7节修订；信息 `T01 review decisions and scoreboard set-at-commit revision`；已push，远端输出 `ea040b0..d73a546` |
| 承载本轮修订的文档提交 | 仅 `docs/backend-rtl-spec.md`、`docs/backend-testplan.md`、本报告；实际SHA及push结果在提交后由最终回报和本地delivery日志给出，亦可 `git log -1 --format=%H -- docs/tasks/T01-report.md` 查询 |

修订文件：[RTL spec](../backend-rtl-spec.md)、[测试计划](../backend-testplan.md)、[本报告](T01-report.md)。审阅输入：[T01-review.md](T01-review.md)。源码事实对应 `d73a546`（相对 `d5672f5` 无代码差异）。

### 6.2 修订内容与边界

- 原Q01–Q18已有确定决定，spec第0节增加落实索引。未提交MDU由EX/MEM/WB级间RAW/WAW检查覆盖；busy只在WB真实提交非零MDU置位、实际write清除，kill永不改busy。写回拍使用effectiveBusy掩码和已有RF写穿透；无EX MDU旁路、无事务标签。
- 单元commit最老未提交项、killUncommitted全部未提交项，无ready、同拍先commit后kill。输出只已提交且算完，反压保持。按每种redirect核对发起级，全部附源码行号；FENCE.I/MEM和中断空流水的冲突不擅自修改。
- 按decoder分支推导spec附录A的整数源/目的表，含CSR immediate zimm、原子、SFENCE、FP跨GPR/FPR bank。保留旧CSR/FP hazard、普通旁路与held EX更新。
- MUL四级/末级输出/整体反压、DIV fast附带req及occupied释放隔拍；固定DIV>MUL>普通WB。仅普通非零GPR写WB遇后台grant时整流水让拍；dmem单项捕获，FPU确认有outReady，选择反压不加额外捕获。
- FASE empty含busy空、enter/读写/launch等后台完；中断不使用该empty。rd=x0 MDU不发FU且无rdPending；WFI后台不停时钟；ESTOP在WB退休前等busy空。
- HPM 11/12/13的拍级条件、重叠来源各计、上界13/非法14已确定；现4bit selector更新上界后仍4bit，证据 `design/src/main/scala/core/BreezePerformanceCounters.scala:13,49-64`。S01–S16/F01–F14及spec6.1时序例同步重写；测试计划27条需求、全部定向/随机/形式化/回归期望同步。
- 后续验收按R/Q17在Alan先跑d5672f5完整基线，无新增失败且通过数不少，已有失败只记录。原设计第11节/任务第4节的“kill清记分板”旧措辞，测试期望按本轮明确审阅决定改为“kill不改busy”，不恢复已被否定的机制；本轮没有额外修改设计/任务文件。

批准的**计划删除清单**（本阶段没有删代码）：

| 原字段/逻辑与位置 | 阶段二冻结后的迁移 |
| --- | --- |
| EX/MEM `mul_a/mul_b/mul_op` 与DIV fast/magnitude/sign/word/remainder字段；`design/src/main/scala/interface/interface.scala:408-420` | EX req接受后不再携带到MEM；保留mul_valid/div_valid/rd/valid作为提交元数据 |
| MUL/DIV等待状态及旧MEM发起/完成/全flush；`design/src/main/scala/backend/BreezeBackend.scala:492-493,733-752,1354-1368` | 改EX接收/WB commit与WB killUncommitted；已提交后台不被redirect取消 |
| MEM/WB `mul_data` 的MDU用途；`design/src/main/scala/interface/interface.scala:464` | 后台结果独立仲裁；现FP→GPR共用MUL选择（`design/src/main/scala/backend/BreezeBackend.scala:1297-1300,1526-1530`）改独立FP命名/字段，保留全部功能 |

R/Q15已批准的“旧检查→新检查”：

| 旧检查 / 源位置 | 新检查 / 权限 |
| --- | --- |
| completion PopCount≤1；`design/src/main/scala/backend/BreezeBackend.scala:839-840` | grant独热+每源valid&&!ready保持；允许DIV/MUL同时valid；已批准 |
| MUL wrapper3拍/全flush；`design/src/test/scala/multiplier/RiscvMulUnitSpec.scala:23-64` | 4拍/commit/kill/保持；已批准。旧SignedMul65x65及全部测试原样保留 |
| HPM非法selector=11；`design/src/test/scala/core/BreezeCsrPipelineSpec.scala:130-131` | 新非法上界14；已批准，旧0–10及计数采样语义不变 |
| 后端MDU提交拍最终wbData及DIV wrapper无commit驱动 | **未批准**，列A03；当前不修改旧测试、不伪造旧观测值 |

### 6.3 审阅后问题

完整证据和影响见 [spec第12节](../backend-rtl-spec.md#12-审阅后问题)。以下六项没有自行决定：

| ID | 问题 / 需确认内容 |
| --- | --- |
| A01 | 未提交MUL到P4时outValid=0，Q04 enable仍允许推进，Q03要求的内部保存无法保证。确认未提交P4保持/ready及同拍commit规则；未擅加FIFO或改enable |
| A02 | Q03把FENCE.I列作WB kill，但代码在MEM发redirect；与“MEM只抑EX、不kill”冲突。确认级/kill合同；未搬级 |
| A03 | Q15除三类外旧检查原样，与后端MDU提交拍最终wbData冲突；直接替换原DIV wrapper协议也会使旧无commit驱动冲突。确认驱动/观测迁移许可或保留旧wrapper边界，算术/依赖期望不变；未修改测试 |
| A04 | 指定仓库范围没有找到可复用完整逐条参考比对器。按Q18停止checker实现，请提供入口或指定进一步查找范围；未自建参考模型 |
| A05 | rdPending/晚写事件需要贯穿core/sim，超出Q09允许范围。需批准具体core/trace runner/parser/log文件；未扩大代码范围 |
| A06 | Q03“WB MDU提交同拍接受中断”示例与Q06保留空流水接受条件冲突，当前WB有效则不可达。确认仅是接口级例子或另修中断规则；未放宽中断条件 |

Q18查找证据：`sim/breezecore/README.md:3-26`仅资产/runner说明；`tests/ref/spike_ref.hpp:1-11`空壳；`design/src/main/scala/sim/BreezeCoreTandem.scala:3-24`容器；`design/src/main/scala/sim/BreezeCoreTandemParser.scala:23-58`转换；`design/src/main/scala/sim/BreezeCoreTandemLog.scala:1-44`格式化；`design/src/main/scala/sim/BreezeCoreSimSupport.scala:301-323,453-497`收集/打印，不能证明存在完整参考执行checker。没有可报告的tandem执行命令，写**未确认**。

### 6.4 实际操作、检查和证据

机器：本地chen；cwd `/home/chen/leisure/flow`。日志仅保存在 `/tmp/flow-t01-stage1-review-20261005/`，不暂存。逐条读取/复核argv、cwd、exit和输出见 `commands.log`（49条；34个源码文件）。

| 命令 / 操作 | 结果 / 日志 |
| --- | --- |
| `git status --short --branch`、review完整读取、设计diff与agent规则读取 | 完成，指定分支；输入只读，技能仍限原四个 |
| `git add -- docs/tasks/T01-review.md docs/backend-pipeline-design.md`；`git diff --cached --check`/范围复核；`git commit -m 'T01 review decisions and scoreboard set-at-commit revision'` | 成功，d73a546，仅两个指定文件；提交可重查 |
| `git push origin feat/pcie-fase-20260920`（审阅输入） | 成功，ea040b0..d73a546；实际会话输出 |
| `cat` review/任务/spec/plan/report、四个SKILL/context；`nl -ba`/`sed -n`核对decoder、redirect、FU、trace与旧测试 | 完成；最终源码重读和原文存 `commands.log`，不当运行证据 |
| `rg -n 'Tandem|spike|compare|rdPending' design/src/main/scala/sim sim/breezecore tests/ref`；`rg --files sim/breezecore tests/ref` | 完成，查找范围明确，未找到完整可复用checker；原输出见 `commands.log` |
| 仅Markdown的文档重写/补充 | 完成；无代码变更，新问题只报告 |
| `python3 -` 内联静态检查：文件/引用上界/链接、Q/REQ/T/U/S/F/A编号、追踪与代码范围 | 首次发现spec中六项决定缺显式Q编号索引（并非缺规则）；已补决定索引。首轮 `static-check-initial.log`，最终 `static-check.log` errors=[]；核验spec138处、plan29处源码行号引用 |
| `git diff --name-only d73a546 -- '*.scala' '*.py' '*.sv'`、`git diff --check` | 代码diff为空、空白检查通过；见 `static-check.log`/会话输出 |
| 本轮三文件的精确暂存、cached check/范围复核、提交/push、远端HEAD核验 | 实际结果在完成操作后存 `delivery.log`，最终回报给出提交号；不在提交前预填成功 |

**未运行**：Alan sbt基线/回归、RTL生成/仿真、SBY/Yosys、ACT4、tandem/随机程序、Vivado OOC/整核综合与100MHz WNS、性能程序。无BMC深度、通过数、seed数量、资源或周期测量；这些数值未确认。

静态检查只确认文档引用范围/链接/编号和改动范围，不证明文字语义、RTL功能、测试覆盖或综合时序。源码/测试现状与新合同明确分开。四个已有技能仅用于文档/性质分析，没有新增其他硬件技能需求。

### 6.5 偏离、限制与停止点

无代码实施偏离：本轮仅阶段一修订，确定决定贯穿文档；六项冲突/缺口如实列出、不自行处理。spec尚非冻结版，A01–A06仍需用户审阅；已有测试迁移只有上述三类许可。

所有无关未跟踪内容（包括 `.agents/`、`docs/figures/`、`docs/cross-project/`、`docs/hardware-skill*`、`docs/plans/2026-10-04-*`、`docs/roadmap.md`）不暂存。完成本轮文档提交和push后停止，等待用户确认冻结，不开始阶段二。


## 阶段二：上轮历史（B01/B02 已由用户决定）

日期：2026-10-05。用户已明确冻结 `b180a95fe91ff89bbd597e28419e74c516d2c9a5` 的 spec/testplan。本节为阶段二最新记录；阶段一的“未冻结/未开始”措辞仅为历史。当前第1步 Q17已完成；在第2步接口/源码核查时触发停止条件1，整个任务已停止，未修改任何RTL或测试。分支 `feat/pcie-fase-20260920`。

### A. 提交列表

| 步骤 | 提交 | 内容 |
| --- | --- | --- |
| 1 基线 | `f73a9a2b52f6b0545ec52502d1857740461511b5` | `T01-2/1 Record Alan Q17 suite baseline and 69 ACT4 RV64IM passes`；已push，仅报告 |
| 2 单元 | 无RTL提交 | 源码核查时停止，单元实现/测试未运行 |
| 3–7 | 无提交 | 未运行，未继续后续步骤 |
| 停止报告 | 本节承载的 `T01-2/2` 报告提交；实际SHA在最终回报给出，亦可用 `git log --format=%H --grep='^T01-2/2' -1` 查询 | 仅更新本报告，不代表第2步实现完成 |

验证源码提交为 `d5672f51bf0ec67465148c02af970c70464bec68`，不是报告提交；Alan 实际 HEAD 见证据目录 `source.sha`。

### B. 旧基线表（作废：CVFPU 构建失败）

**作废：CVFPU 构建失败。此表仅保留历史，不能作为第 6 步回归比较依据。**

旧基线：Alan，cwd `/home/chen/FUN/flow/design`，`/home/chen/.local/share/coursier/bin/sbt test`，源码 `d5672f51bf0ec67465148c02af970c70464bec68`，退出码 **1**。56 suites completed、0 aborted；277 tests，211 succeeded、66 failed、0 canceled/ignored/pending。按本次 XML 逐 suite 汇总，与 sbt 原文总数一致。0测试 suite 保留在表内，不算通过测试。

日志根目录：`/home/chen/FUN/flow-runs/20261005-t01-2-q17-d5672f5/`。原文 `sbt-test.log`，逐 suite 原始 XML `test-reports/TEST-<suite>.xml`，汇总 `suite-summary.json` / `suite-table.md`，逐用例错误 `failures.json`。

全部66个失败用例的原始错误包含 CVFPU 的 `%Error-BLKANDNBLK`（blocked/non-blocking assignments to same variable），仿真构建失败。这些是本次指定基线的实际失败；按 Q17 记录，不修复、不修改测试或工具检查。CORE-003 的4个 handler变体均失败于该构建错误，不能据此判定其运行时 RTL 行为。

| Suite | 基线通过 | 基线失败 | 基线忽略 | 改造后通过/失败/忽略 |
| --- | ---: | ---: | ---: | --- |
| `flow.backend.BreezeBackendDivSpec` | 0 | 1 | 0 | 未运行 |
| `flow.backend.BreezeBackendFpMemorySpec` | 0 | 3 | 0 | 未运行 |
| `flow.backend.BreezeBackendFpSpec` | 0 | 2 | 0 | 未运行 |
| `flow.backend.BreezeBackendGShareSpec` | 0 | 10 | 0 | 未运行 |
| `flow.backend.BreezeBackendMulSpec` | 0 | 1 | 0 | 未运行 |
| `flow.backend.BreezeRedirectPrioritySpec` | 0 | 5 | 0 | 未运行 |
| `flow.cache.BreezeAmoAluSpec` | 2 | 0 | 0 | 未运行 |
| `flow.cache.BreezeCacheSpec` | 3 | 0 | 0 | 未运行 |
| `flow.cache.BreezeCoherentDmaSpec` | 6 | 0 | 0 | 未运行 |
| `flow.cache.BreezeDCacheCoherentSpec` | 24 | 0 | 0 | 未运行 |
| `flow.cache.BreezeDCacheSetAssocSpec` | 12 | 0 | 0 | 未运行 |
| `flow.cache.BreezeL2HomeSmallSpec` | 8 | 0 | 0 | 未运行 |
| `flow.cache.BreezeL2HomeSpec` | 14 | 0 | 0 | 未运行 |
| `flow.cache.BreezePLRUSpec` | 0 | 0 | 0 | 未运行 |
| `flow.cache.BreezeParallelLookupSpec` | 2 | 0 | 0 | 未运行 |
| `flow.config.BreezeCoreConfigSpec` | 7 | 0 | 0 | 未运行 |
| `flow.core.BreezeCoreCustomInstrSpec` | 0 | 1 | 0 | 未运行 |
| `flow.core.BreezeCoreNoFASECustomInstrSpec` | 0 | 1 | 0 | 未运行 |
| `flow.core.BreezeCoreNoFASESpec` | 0 | 11 | 0 | 未运行 |
| `flow.core.BreezeCoreSpec` | 0 | 12 | 0 | 未运行 |
| `flow.core.BreezeCsrPipelineSpec` | 2 | 0 | 0 | 未运行 |
| `flow.core.BreezePrivilegeSpec` | 19 | 0 | 0 | 未运行 |
| `flow.core.BreezeRegisterStorageSpec` | 2 | 0 | 0 | 未运行 |
| `flow.core.CSRFileSpec` | 6 | 0 | 0 | 未运行 |
| `flow.core.MulDecodeSpec` | 1 | 0 | 0 | 未运行 |
| `flow.core.RegFileSpec` | 1 | 0 | 0 | 未运行 |
| `flow.divider.RiscvDivUnitSpec` | 1 | 0 | 0 | 未运行 |
| `flow.divider.UnsignedRadix4DividerSpec` | 2 | 0 | 0 | 未运行 |
| `flow.fase.FaseIntegrationSpec` | 0 | 2 | 0 | 未运行 |
| `flow.fase.FlightRecorderSpec` | 1 | 0 | 0 | 未运行 |
| `flow.fpu.BreezeFpDecoderSpec` | 2 | 0 | 0 | 未运行 |
| `flow.fpu.BreezeFpUnitSpec` | 0 | 2 | 0 | 未运行 |
| `flow.frontend.BreezeBTBSpec` | 2 | 0 | 0 | 未运行 |
| `flow.frontend.BreezeCompressedDecoderSpec` | 1 | 0 | 0 | 未运行 |
| `flow.frontend.BreezeFrontendFE001Spec` | 1 | 0 | 0 | 未运行 |
| `flow.frontend.BreezeFrontendFE002Spec` | 1 | 0 | 0 | 未运行 |
| `flow.frontend.BreezeFrontendGShareSpec` | 4 | 0 | 0 | 未运行 |
| `flow.frontend.BreezeFrontendSpec` | 2 | 0 | 0 | 未运行 |
| `flow.frontend.BreezeInstrRealignerSpec` | 3 | 0 | 0 | 未运行 |
| `flow.frontend.BreezePHTSpec` | 3 | 0 | 0 | 未运行 |
| `flow.frontend.MiniDecodeSpec` | 1 | 0 | 0 | 未运行 |
| `flow.mmu.BreezeMmuAdSpec` | 5 | 0 | 0 | 未运行 |
| `flow.mmu.BreezeMmuSpec` | 5 | 0 | 0 | 未运行 |
| `flow.mmu.BreezeParallelTranslatorSpec` | 2 | 0 | 0 | 未运行 |
| `flow.mmu.BreezePmpSharingSpec` | 1 | 0 | 0 | 未运行 |
| `flow.mmu.sv39.Sv39MmuSpec` | 16 | 0 | 0 | 未运行 |
| `flow.mmu.sv39.Sv39StructuresSpec` | 13 | 0 | 0 | 未运行 |
| `flow.multiplier.RiscvMulUnitSpec` | 2 | 0 | 0 | 未运行 |
| `flow.multiplier.SignedMul65x65Spec` | 22 | 0 | 0 | 未运行 |
| `flow.platform.BreezeLinuxPmaSpec` | 4 | 0 | 0 | 未运行 |
| `flow.sim.BreezeCoreGShareSpec` | 0 | 5 | 0 | 未运行 |
| `flow.sim.BreezeCoreSim` | 0 | 2 | 0 | 未运行 |
| `flow.sim.BreezeCoreSimAppSpec` | 5 | 0 | 0 | 未运行 |
| `flow.sim.BreezeCoreSimMemoryLoaderSpec` | 3 | 0 | 0 | 未运行 |
| `flow.sim.BreezePrivilegeFlowSpec` | 0 | 7 | 0 | 未运行 |
| `flow.sim.BreezeWfiFlowSpec` | 0 | 1 | 0 | 未运行 |
| 合计（56 suites） | 211 | 66 | 0 | 未运行 |

ACT4 基线：I/M/Zmmul selected=69、ran=69、PASS=69、FAIL=0、TIMEOUT=0、INFRA_ERROR=0；运行退出码0。改造后 ACT4 **未运行**。上游固定提交 `dfa582359db885ae4c6ed1fa82faef60874e212c`，实际 HEAD 与 `ACT4_REV` 一致。

### C. 每步结果

所有下面的硬件执行均在 Alan（hostname `chen-System-Product-Name`），源码 SHA 均为 `d5672f51bf0ec67465148c02af970c70464bec68`。第1步未修改源码。

| 步骤/命令 | Alan cwd | 退出码 | 实际结果 | Alan 日志路径 |
| --- | --- | ---: | --- | --- |
| 1：`/home/chen/.local/share/coursier/bin/sbt test` | `/home/chen/FUN/flow/design` | 1 | 56 suites，211通过/66失败/0忽略 | `/home/chen/FUN/flow-runs/20261005-t01-2-q17-d5672f5/sbt-test.log`；`sbt-test.meta`；`sbt-test.exit` |
| 1：`make -C verification/act4 build EXTENSIONS=I,M,Zmmul` | `/home/chen/FUN/flow` | 0 | corpus构建完成（upstream报告345 up-to-date；实际选中集合由运行器统计） | 同根目录 `act4-build.log` / `act4-build.meta` / `act4-build.exit` |
| 1：`python3 verification/act4/scripts/run_linux_soc_suite.py --profile single --elf-dir /home/chen/FUN/flow/verification/act4/.work/breeze-rv64gc/elfs --output-dir /home/chen/FUN/flow-runs/20261005-t01-2-q17-d5672f5/act4 --include-extension I --include-extension M --include-extension Zmmul --fresh-build` | `/home/chen/FUN/flow` | 0 | 69/69 PASS，无FAIL/TIMEOUT/INFRA_ERROR | 同根目录 `act4-run.log` / `act4-run.meta` / `act4-run.exit`；`act4/summary.json`；`act4/cases/*.log` |
| 2 单元 | 硬件命令未运行；本地只读核查 | — | 遇B-01后立即停止；RTL/单元测试未运行 | 本地 `/tmp/flow-t01-2-20261005/stop-source-review.log` |
| 3 单元形式化 | 未运行 | — | 未运行 | — |
| 4 后端集成 | 未运行 | — | 未运行 | — |
| 5 trace | 未运行 | — | 未运行 | — |
| 6 整核验证 | 未运行 | — | 未运行 | — |
| 7 综合 | 未运行 | — | 未运行 | — |

工具：Java GraalVM CE17.0.9；sbt项目版本1.9.7（启动脚本1.11.2）；Verilator5.028；ACT4运行器Python3.10.19，现有conda `flow` 环境。工具记录 `tools.log`；已保存 `source-status.log` / `submodules.log`。CVFPU submodule `1b220f3bc89df99e246b72e3574a3a533cf87653`。启动脚本 `/tmp/t01-q17-baseline.sh`；汇总脚本 `/tmp/t01-baseline-summary.py` 只读取XML，不改变验收。

同步：使用用户要求的本地代理反向转发 `Alan 127.0.0.1:17898 -> 本地127.0.0.1:7897`，Alan通过该代理访问GitHub HTTP200、fetch核实冻结提交。未修改全局Git代理配置。

### D. 形式化

F01–F14及相关cover **未运行**；引擎、BMC深度、归纳结果、assume、cover witness均未产生。尚未创建/修改harness、SBY或性质，不把工具探测当证明。已现场找到现有flow环境的SBY0.69、Yosys0.62和系统z3；实际证明尚未开始。

### E. 迁移清单与删除清单

截至停止：**无迁移、无删除**。旧测试、断言、期望值、随机次数、BMC深度、assume均未修改；`SignedMul65x65`及其测试原样保留。

### F. 综合

新旧乘法器OOC（XCKU040）的DSP/LUT/FF：**未运行**。单核整机100MHz综合、WNS、最差路径：**未运行**。性能P02前后周期/新HPM：**未运行**。

### G. B 类问题

**B-01：A08的MEM推进门控与冻结的访存请求当拍保持相矛盾。触发用户停止条件1（spec与源码保留要求冲突/按spec做不到）。**

停止位置：第2步开始，只读核查现有单元接口及其后端接线时发现；第1步完成且已push，第2步未改RTL/测试，步骤3–7全部未运行。没有把一项挂起后继续后续步骤。

冻结输入及源码位置（`b180a95`；对应源码与`d5672f5`一致）：

- `docs/backend-rtl-spec.md:67`：从pipelineHold删除MUL/DIV项，但保留memory/FP/FENCE.I项。
- `docs/backend-rtl-spec.md:196,201`：保留原阻塞行为；访存请求一律使用“该级本拍enable && 原条件”，原pipelineHold期间memEnable=0，并要求只在该级确实推进时发请求。
- `docs/backend-rtl-spec.md:296`：CORE-001请求当拍持有必须保留。
- `design/src/main/scala/backend/BreezeBackend.scala:727`：`memReqIssued := exeMemNeedsDmem && !memWaitingRespReg && !wbKillsYounger`。
- 同文件`:1063-1068`：注释明确请求当拍必须hold，`pipelineHold := memReqIssued || (memWaitingRespReg && !memRspFire) || ...`。
- 同文件`:1346-1352,1617`：req当拍末才置memWaitingRespReg，`io.dmem.req.valid := memReqIssued`；这是首拍真实请求，不是已经处于wait的重复请求。

具体首拍：MEM有一个合法Load/Store，`memWaitingRespReg=0`、没有WB kill、没有其他hold/让拍/ESTOP等待，旧发请求条件为1。令I为memReqIssued、H为pipelineHold、E为memEnable。保留的请求拍hold要求H=I；A08要求E=!H（此场景其余enable条件均允许）；将请求按A08门控后I=E&&1。因此I=!I，没有稳定的组合逻辑解，依赖形成`memReqIssued -> pipelineHold -> memEnable -> memReqIssued`。如果另行固定请求为0，则不能发出这笔请求；如果允许请求为1，则当拍H=1/E=0，又违反A08“只有MEM确实推进才发请求”。

这是冻结条款/源码方程的静态冲突分析，**不是已实现RTL的仿真或形式化失败**。没有为绕过冲突拆分issueEnable/advanceEnable、移除请求hold、改请求时序、改A08或增加assume；这些都需要新的明确规格决定。恢复任务需先明确阻塞访存（以及同类阻塞请求）的发起许可与该级寄存器推进的关系，并给出替换冻结条款；本轮未自行选择方案。

基线66个CVFPU构建失败按Q17原样记录，未将其当作CORE-003运行时结论、未修复。没有遇到“RTL改3次仍失败”，RTL修改次数为0。没有修改测试/断言/期望/次数/深度/assume。已找到的工具不等于全部阶段环境通过；本轮未发现确定的工具缺失，不安装任何工具。

最终交付只有`docs/tasks/T01-report.md`的阶段二记录；冻结spec/testplan及所有源码测试保持原样。Alan最终验证HEAD仍为`d5672f51bf0ec67465148c02af970c70464bec68`，干净detached checkout；基线任务均已结束。停止报告提交/push结果在最终回报给出。

## 阶段二（B01/B02 续行，最新状态）

冻结稿：`de9c303fa5e222f38470ab53e951544c43223b98`。上一轮 B01 已由用户修订 A08；下面记录新增第 0 步；当前新基线与第 2 步单元门槛已完成，第 3 步生成/形式化执行中，尚未触发本轮停止条件。旧阶段二记录作为历史保留。

### A. 提交列表

| 步骤 | 提交 | 内容 |
| --- | --- | --- |
| 0 B02 构建 | `cd9321a335c156d974985a378930d98df6e96b69` | `T01-2/0 Scope ChiselSim BLKANDNBLK waiver to CVFPU sources`，已 push |
| 0 新基线报告 | `788c6a0` | 新基线逐 suite 表与作废旧表，已 push |
| 2 单元实现 | `14249c7e570955e08682ff840bc63df4156525a6` | 新统一接口、四级 MUL/DIV wrapper、获准迁移、协议/数学随机/原 MUL 等价性；Alan 34/34 通过，已 push |
| 2 剩余边界检查 | `fc26153` | 空 commit 断言负测和 DIV 延迟边界；最终 Alan 单元门槛 37/37，已 push |
| 3 形式化准备 | `798c062` | 单元 formal-only observation、独立请求 FIFO 台账、固定 SBY 预算；已 push，生成/证明结果待记录 |
| Alan 新基线临时分支 | `3adca5e283caa7faa3262c713e9ca7624fa5b2dc` | 分支 `t01-b02-baseline-20261005`；父提交 `d5672f51bf0ec67465148c02af970c70464bec68`，只 cherry-pick 上述 B02 提交；相对父提交只改三个构建文件 |

参数入口：Chisel 7.0.0 source JAR 的 `chisel3/simulator/HasSimulator.scala:43-52` 选择 Verilator；`verilator/Backend.scala:71-89,105-223` 定义设置并组装参数，API 没有任意参数/控制文件入口。源码已读取，现有 `disabledWarnings` 只能按规则全局关闭，不能用于本项。仓库现有 `design/src/test/scala/fpu/BreezeFpTestSupport.scala:13-20` 与 `design/src/main/scala/sim/BreezeCoreSimSupport.scala:238-244` 只配置 include 目录；普通 ChiselSim suite 使用默认 backend。

本轮按 B02 用途扩展范围：`design/build.sbt:23-39`（Test 专用 fork/环境）、`design/project/chiselsim-verilator/verilator:1-5`（参数包装）、`design/project/chiselsim-verilator/cvfpu.vlt:1-3`（定向控制文件）。这些构建文件原不在 spec 1.3/9.1 中，用户已为 B02 明确批准。没有修改 RTL、测试文件或 CVFPU submodule。包装脚本在 svsim 原参数之前加 `.vlt`，然后 exec 原 Verilator，保持原参数和退出码；`.vlt` 仅 `lint_off -rule BLKANDNBLK -file "*/third_party/cvfpu/*"`，不匹配 Flow wrapper、生成顶层或其他源文件。Test 之外的原 Verilator 路径不变。不新增 `-Wno-fatal` 或关闭其他规则；原聚合源中已有的 warning metacomment 不修改。

配置语义依据 [Verilator Control Files](https://verilator.org/guide/latest/control.html)：文件 wildcard 限定规则，控制文件须先于对应源码解析；本轮通过包装参数放在最前。具体支持以 Alan Verilator 5.028 执行为准。

### B. 新基线表与改造后对比表（按 suite）

Alan `3adca5e`，完整 `sbt test` 退出码 **1**：56 suites completed、0 aborted；277 tests，276 succeeded、1 failed、0 canceled/ignored/pending。由本轮原始 XML 汇总，与 sbt 原文一致。第 6 步以此表比较；旧表已标作废并保留在上轮 B 节。

日志根目录 `/home/chen/FUN/flow-runs/20261005-t01-2-b02-newbaseline/`，逐 suite XML 为 `test-reports/TEST-<suite>.xml`；汇总为 `suite-summary.json` / `suite-table.md`，失败全文为 `failures.json`。

| Suite | 基线通过 | 基线失败 | 基线忽略 | 改造后通过/失败/忽略 |
| --- | ---: | ---: | ---: | --- |
| `flow.backend.BreezeBackendDivSpec` | 1 | 0 | 0 | 未运行 |
| `flow.backend.BreezeBackendFpMemorySpec` | 3 | 0 | 0 | 未运行 |
| `flow.backend.BreezeBackendFpSpec` | 2 | 0 | 0 | 未运行 |
| `flow.backend.BreezeBackendGShareSpec` | 10 | 0 | 0 | 未运行 |
| `flow.backend.BreezeBackendMulSpec` | 1 | 0 | 0 | 未运行 |
| `flow.backend.BreezeRedirectPrioritySpec` | 5 | 0 | 0 | 未运行 |
| `flow.cache.BreezeAmoAluSpec` | 2 | 0 | 0 | 未运行 |
| `flow.cache.BreezeCacheSpec` | 3 | 0 | 0 | 未运行 |
| `flow.cache.BreezeCoherentDmaSpec` | 6 | 0 | 0 | 未运行 |
| `flow.cache.BreezeDCacheCoherentSpec` | 24 | 0 | 0 | 未运行 |
| `flow.cache.BreezeDCacheSetAssocSpec` | 12 | 0 | 0 | 未运行 |
| `flow.cache.BreezeL2HomeSmallSpec` | 8 | 0 | 0 | 未运行 |
| `flow.cache.BreezeL2HomeSpec` | 14 | 0 | 0 | 未运行 |
| `flow.cache.BreezePLRUSpec` | 0 | 0 | 0 | 未运行 |
| `flow.cache.BreezeParallelLookupSpec` | 2 | 0 | 0 | 未运行 |
| `flow.config.BreezeCoreConfigSpec` | 7 | 0 | 0 | 未运行 |
| `flow.core.BreezeCoreCustomInstrSpec` | 1 | 0 | 0 | 未运行 |
| `flow.core.BreezeCoreNoFASECustomInstrSpec` | 1 | 0 | 0 | 未运行 |
| `flow.core.BreezeCoreNoFASESpec` | 11 | 0 | 0 | 未运行 |
| `flow.core.BreezeCoreSpec` | 12 | 0 | 0 | 未运行 |
| `flow.core.BreezeCsrPipelineSpec` | 2 | 0 | 0 | 未运行 |
| `flow.core.BreezePrivilegeSpec` | 19 | 0 | 0 | 未运行 |
| `flow.core.BreezeRegisterStorageSpec` | 2 | 0 | 0 | 未运行 |
| `flow.core.CSRFileSpec` | 6 | 0 | 0 | 未运行 |
| `flow.core.MulDecodeSpec` | 1 | 0 | 0 | 未运行 |
| `flow.core.RegFileSpec` | 1 | 0 | 0 | 未运行 |
| `flow.divider.RiscvDivUnitSpec` | 1 | 0 | 0 | 未运行 |
| `flow.divider.UnsignedRadix4DividerSpec` | 2 | 0 | 0 | 未运行 |
| `flow.fase.FaseIntegrationSpec` | 1 | 1 | 0 | 未运行 |
| `flow.fase.FlightRecorderSpec` | 1 | 0 | 0 | 未运行 |
| `flow.fpu.BreezeFpDecoderSpec` | 2 | 0 | 0 | 未运行 |
| `flow.fpu.BreezeFpUnitSpec` | 2 | 0 | 0 | 未运行 |
| `flow.frontend.BreezeBTBSpec` | 2 | 0 | 0 | 未运行 |
| `flow.frontend.BreezeCompressedDecoderSpec` | 1 | 0 | 0 | 未运行 |
| `flow.frontend.BreezeFrontendFE001Spec` | 1 | 0 | 0 | 未运行 |
| `flow.frontend.BreezeFrontendFE002Spec` | 1 | 0 | 0 | 未运行 |
| `flow.frontend.BreezeFrontendGShareSpec` | 4 | 0 | 0 | 未运行 |
| `flow.frontend.BreezeFrontendSpec` | 2 | 0 | 0 | 未运行 |
| `flow.frontend.BreezeInstrRealignerSpec` | 3 | 0 | 0 | 未运行 |
| `flow.frontend.BreezePHTSpec` | 3 | 0 | 0 | 未运行 |
| `flow.frontend.MiniDecodeSpec` | 1 | 0 | 0 | 未运行 |
| `flow.mmu.BreezeMmuAdSpec` | 5 | 0 | 0 | 未运行 |
| `flow.mmu.BreezeMmuSpec` | 5 | 0 | 0 | 未运行 |
| `flow.mmu.BreezeParallelTranslatorSpec` | 2 | 0 | 0 | 未运行 |
| `flow.mmu.BreezePmpSharingSpec` | 1 | 0 | 0 | 未运行 |
| `flow.mmu.sv39.Sv39MmuSpec` | 16 | 0 | 0 | 未运行 |
| `flow.mmu.sv39.Sv39StructuresSpec` | 13 | 0 | 0 | 未运行 |
| `flow.multiplier.RiscvMulUnitSpec` | 2 | 0 | 0 | 未运行 |
| `flow.multiplier.SignedMul65x65Spec` | 22 | 0 | 0 | 未运行 |
| `flow.platform.BreezeLinuxPmaSpec` | 4 | 0 | 0 | 未运行 |
| `flow.sim.BreezeCoreGShareSpec` | 5 | 0 | 0 | 未运行 |
| `flow.sim.BreezeCoreSim` | 2 | 0 | 0 | 未运行 |
| `flow.sim.BreezeCoreSimAppSpec` | 5 | 0 | 0 | 未运行 |
| `flow.sim.BreezeCoreSimMemoryLoaderSpec` | 3 | 0 | 0 | 未运行 |
| `flow.sim.BreezePrivilegeFlowSpec` | 7 | 0 | 0 | 未运行 |
| `flow.sim.BreezeWfiFlowSpec` | 1 | 0 | 0 | 未运行 |
| 合计（56 suites） | 276 | 1 | 0 | 未运行 |

唯一失败：`flow.fase.FaseIntegrationSpec` 的 `JTAG mailbox controls a Linux core, diagnoses stalls and launches coherent DDR code`。`FaseIntegrationHarness.sv:175:25` 的 `FlowFaseJtagTransport` 缺少 `core_reset` 引脚（声明在 `FlowFaseJtag.sv:7:54`），Verilator `%Warning-PINMISSING` 后 `%Error: Exiting due to 1 warning(s)`；构建失败。按 B02 只记录，不修、不关闭 PINMISSING；同 suite 另一项通过。没有新增大量 suite 构建阻塞。

CORE-003 四个 handler 变体全部真实执行通过：csrrs-read+csrrw-write、addi 结果经 x6、addi/csrrw 间 NOP、addi rd≠rs1。原文见 `TEST-flow.core.BreezeCoreNoFASESpec.xml`（该 suite 11/11）；这只说明本次测试结果，不改 bug 文档或扩大验证结论。

ACT4 不重跑，沿用源码 `d5672f5` 的 69/69 PASS、0 FAIL/TIMEOUT/INFRA_ERROR，命令/日志见上轮 C 表；不把它记成新 B02 SHA 的 ACT4 运行。

### C. 每步结果

硬件运行在 Alan，cwd `/home/chen/FUN/flow/design`，源码 `3adca5e283caa7faa3262c713e9ca7624fa5b2dc`。

| 步骤 / 实际命令 | cwd | 退出码 | 结果 / 日志 |
| --- | --- | ---: | --- |
| 0：`/home/chen/.local/share/coursier/bin/sbt test` | Alan `/home/chen/FUN/flow/design` | 1 | 276/1/0，56 suites；上述根目录 `sbt-test.log` / `sbt-test.meta` / `sbt-test.exit` / `source.sha` |
| 0：`python3 /tmp/t01-b02-summary.py` | Alan SSH 默认目录；输入为上述根目录 | 0 | 按全部原始 XML 汇总，276/1/0；`suite-summary.json` / `suite-table.md` / `failures.json` |
| 0：Git fetch、`git switch -c t01-b02-baseline-20261005 d5672f51bf0ec67465148c02af970c70464bec68`、`git cherry-pick cd9321a335c156d974985a378930d98df6e96b69` | Alan `/home/chen/FUN/flow` | 0 | 临时提交 `3adca5e`；`checkout.log` / `source-status.log` / `submodules.log`；相对 d5672f5 只改三个构建文件 |
| 2：`/home/chen/.local/share/coursier/bin/sbt 'testOnly flow.multiplier.* flow.divider.*'`，SHA `14249c7e570955e08682ff840bc63df4156525a6` | Alan `/home/chen/FUN/flow/design` | 0 | 7 suites，34/34；`/home/chen/FUN/flow-runs/20261005-t01-2-unit-14249c7/sbt-test.log` / `.meta` / `.exit`，`test-reports/`、`source.sha` |
| 2：`/home/chen/.local/share/coursier/bin/sbt 'testOnly flow.multiplier.* flow.divider.* flow.backend.MduBoundarySpec'`，SHA `fc26153` | Alan `/home/chen/FUN/flow/design` | 0 | 8 suites，37/37，0 ignored/aborted；`/home/chen/FUN/flow-runs/20261005-t01-2-unit-fc26153/sbt-test.log` / `.meta` / `.exit`，`test-reports/`、`source.sha`；第 2 步门槛通过 |
| 3：`sbt 'runMain flow.backend.GenerateIntMduFormal /home/chen/FUN/flow-runs/20261005-t01-2-formal-798c062/generated'`，SHA `798c062` | Alan `/home/chen/FUN/flow/design` | 0 | 生成成功；上述目录 `generate.log` / `.meta` / `.exit`，原始 `generated/{mul,div}/` |
| 3：`sby -f mul.sby`，SHA `798c062` | Alan `/home/chen/FUN/flow-runs/20261005-t01-2-formal-798c062` | 16 | BMC/prove/cover 均读取错误，`MulProtocolFormal.sv:47` 不支持 immediate assume/assert 的 `else $error`；没有引擎结果/witness，`mul-sby.log` / `.exit`。DIV 尚未运行 |
| 4 后端、5 trace、6 整核、7 综合 | Alan | — | 未运行，按顺序继续；本轮单元证据不代表后端/整核/综合通过 |

工具：Java GraalVM CE 17.0.9；sbt 项目 1.9.7（启动脚本 1.11.2）；Verilator 5.028；CVFPU `1b220f3bc89df99e246b72e3574a3a533cf87653`，与旧基线一致，详见 `tools.log` / `submodules.log`。完整 sbt 用时 1913 s。Test fork 只用于把包装器 PATH 限定在测试进程，不改变测试向量/期望/次数。

实际参数证据 `fpunit-verilator-parameters.dat`：来自本轮 FP 单元构建的 `VsvsimTestbench__verFiles.dat`，首个参数为上述 `.vlt`，没有 `-Wno-fatal`；该 FP suite 2/2 通过。BLKANDNBLK 未再出现于本轮 sbt 失败记录；PINMISSING 仍触发失败，确认未放宽其规则。Test 参数入口源码 JAR SHA256 `354de5e110cbe312449047a8d1f732bdc2c994e8ba0673c579b2a7292fb19139`，本地只读副本 `/tmp/flow-t01-b02-20261005/t01-chisel-7.0.0-sources.jar`。本地 shell 语法和 `git diff --check` 通过，只是静态检查。

单元实施：新增 `backend/IntMduProtocol.scala`、`multiplier/CommittedMulUnit.scala`、`divider/CommittedDivUnit.scala`。MUL 四级 product/op/rd/valid/committed，A01 整体 enable，最老未提交 commit、先 commit 后 kill、结果保持。DIV 复用原样 unsigned radix-4，fast 1 拍 done、occupied 保持直到 write/合法 kill、release/accept 隔拍。旧后端暂时使用旧 wrapper，第 4 步再接线删除；没有在第 2 步提前改后端。

B 组预算在运行前已由提交中的测试定义固定：MUL seed `0x701` 500 请求、五 op 各 100，commit 延迟 0–6 拍、result 反压 0–9 拍；DIV seed `0x702` 512 请求、八操作各 64，commit 延迟 0–39 拍、反压 0–9 拍；U01 seed `0x703` 1000 个连续 65-bit 请求，对原样 SignedMul65x65 和独立 BigInt。通过后的实际循环台账分别为 MUL 500 req/commit/write、0 kill；DIV 512 req/commit/write、0 kill；等价性 1000 req/commit/write、0 kill。四级占满定向用例最大占用 4、未提交 P4 停住 9 拍，先 commit 后 kill 丢弃其余项，已提交项再反压/kill 8 拍不丢；DIV 定向用例最大占用 1、早 done 等 commit 9 拍，再反压/kill 7 拍。另覆盖四个 MUL 级的 kill、复位在途、连续 8 个不同 rd MUL 的 II=1/四拍、DIV 迭代中 kill、早 done 未提交 kill/reset。原 SignedMul65x65 的 50k 检查原样运行通过。

延迟原文（最终单元日志）：`a=0,b=1`、`a=1,b=2`、`a=5,b=5` 各 `arithmetic_iterations=0 req_to_done=2 req_to_write=12`；`a=18446744073709551615,b=1` 为 `arithmetic_iterations=32 req_to_done=34 req_to_write=44`；快路径 `req_to_done=1`。req→write 含这些测试明确施加的 commit/8 拍结果反压，不是无反压算术延迟，也不宣称任意环境的写回上界。两个空 commit 负测通过，预期异常来自 RTL S04 断言；不是忽略失败。没有单元测试失败或 RTL 重试。程序指令数、整核覆盖和波形 witness 此时未运行/未产生，有限随机结果不证明无界活性。

### D. 形式化

#### D.1 第 3 步审阅决定（本轮生效）

用户本轮明确批准两个单元**只证控制逻辑**；算术正确性由第 2 步 B 组随机测试、U01/U02 负责（已记录 37/37 单元通过及原始日志）。不再执行带完整算术的 `mul.sby` / `div.sby`，两项记为 **“因求解器资源放弃，算术由仿真覆盖”**，保留全部原始生成 RTL、配置、日志及已有 cover witness。

| 单元 | 当前模型 | 保留的检查 / 边界 |
| --- | --- | --- |
| MUL | `mul_protocol_abc.sby`：唯一组合 `$mul` 的 130-bit 输出换成逐拍任意值；`select -assert-count 1` 检查替换数量 | 全部生产寄存器、控制和断言保留；独立 FIFO 捕获同一个任意乘积，检查所有有效级 rd/product/op 身份、提交前缀和输出身份；不声称数学等价性 |
| DIV | `div_abc.sby`：只以 `UnsignedRadix4DividerAbstract.sv` 替换生成的 unsigned radix-4 核，quotient/remainder 为任意64位值，完成延迟任意0–34拍 | 原 `CommittedDivUnit` 外壳源代码和生成逻辑不变：occupied/commit/kill/done、符号/W恢复、输出保持、快路径全部保留；独立 FIFO 检查身份与计数，内部 done 尚未提交的保持也检查 |

DIV 完成拍数以接受沿为0：0表示接受沿锁存 core 完成、1–34表示后续对应沿完成；core 的 out_valid 是寄存输出（与原核短路径相同），外壳按原逻辑在随后采样拍捕获，不把它改成组合零拍输出。抽象模型仅新增**一条获批准的 assume**：非reset/flush且active、age=33时 `finish_now` 必须为1，保证该请求在接受后的第34拍以内完成；reset/flush 取消本项及其完成义务。模型保留原核“busy时不得接收”断言，并加 age 范围断言、0/34完成端点 cover；没有算术约束。

共同 caller assume 全部保留：初始拍 reset；非复位时 commit 对应独立 accepted-request FIFO 已有未提交项；WB kill 抑制年轻 EX req.valid；req.valid 的 rd 非零。后续 reset 可在途且任意，开始新计数 epoch；没有无WAW、无竞争、公平写口或外部hold上界假设。台账为32位模计数，检查 accepted=written+killed+live，并与真实槽位人口逐拍对应。F11 此步是单元 commit 必有未提交项；记分板 set/clear 及跨单元/CSR 的 integrated 检查在后端集成后进行，不将单元证明冒充这些检查。

| 模式 | 引擎 | 深度 / 超时 |
| --- | --- | --- |
| BMC | `abc bmc3` | 80拍，3600秒/任务 |
| prove | `abc pdr` | **无界，不设归纳深度**，3600秒/任务 |
| cover | `smtbmc z3` | 80拍，3600秒/任务 |

**同一时间只运行一个求解器**，任务逐一串行。出现 unknown/timeout 即照实记录并停止整个任务报告，不再换引擎反复试。F03/F05/F07/F08/F11 单元 assert 全PASS、相关 cover 全可达后才进入第4步。3600秒审阅策略开始前的中断不伪称为该策略下的timeout。没有改变任何生产断言、既有期望、随机次数或 BMC/cover 深度。

#### D.2 历史尝试与证据（保留）

工具预检 Alan SBY v0.69、Yosys0.62、Z3 4.8.12、firtool1.128.0；`yosys-abc` 已安装，没有安装任何工具。harness 为 `design/src/main/scala/backend/GenerateIntMduFormal.scala`。Yosys 不支持 firtool immediate assert/assume 的诊断 `else $error`，只将该动作转换成分号，保留每个表达式、guard、label、cover；原始 SV 保留，`lowering-audit.json` 记录属性数量、诊断与原始/转换 SHA256。

| 源提交 / 目录 | 实际结果 |
| --- | --- |
| `798c062`；`/home/chen/FUN/flow-runs/20261005-t01-2-formal-798c062/` | `sbt runMain flow.backend.GenerateIntMduFormal .../generated` exit0；`sby -f mul.sby` exit16，读取 `MulProtocolFormal.sv:47` 的 `else $error` 语法错误，BMC/prove/cover没有引擎结果 |
| `f21c4b6`；`/home/chen/FUN/flow-runs/20261005-t01-2-formal-f21c4b6/` | 完整算术 MUL cover8/8 PASS；prove526秒后 `Unexpected EOF response from solver` / `Engine terminated without status` / ERROR rc16，无性质反例；其余任务已发终止命令。**因求解器资源放弃，算术由仿真覆盖** |
| `f21c4b6`；`/home/chen/FUN/flow-runs/20261005-t01-2-formal-f21c4b6-div/` | 完整算术 DIV cover5/5 PASS；prove511秒后同类EOF/ERROR rc16，无性质反例；其余任务已发终止命令。**因求解器资源放弃，算术由仿真覆盖** |
| `849a5d2`；`/home/chen/FUN/flow-runs/20261005-t01-2-formal-849a5d2/` | MUL控制抽象Z3 cover8/8 PASS；BMC/prove求解中终止，保留日志，不算PASS |
| `943f9fae2263ca0d9ab71869525c719e284369b4`；`/home/chen/FUN/flow-runs/20261005-t01-2-formal-943f9fa/` | `sby -f mul_protocol_abc.sby bmc` exit0，80frames全部PASS，469秒；prove PDR原配置timeout1800，审阅配置更新时发出终止命令；其余尚未运行。生成exit0；wrapper和所有strengthening assert保留 |

各cover实际 witness 为相应 `*_cover/engine_0/trace*.{vcd,yw,smtc}`，逐项步数映射见 PASS 文件。此前并行求解造成内存压力、swap，已纠正为严格串行；不将EOF原因未经内核日志确认便断言为OOM。原失败/中断不会删除，也不会计入通过。旧 MUL BMC 80 的结果只注明原 source/config；当前配置的执行或复用必须按相同模型哈希单独登记，不冒充新运行。

#### D.3 当前执行状态

上述审阅配置已准备；DIV新的0/34端点cover尚未运行，当前控制模型的最终PASS、无界证明及门槛尚未取得。尚未进入第4步。本机只做文本/静态检查，所有RTL生成、BMC、prove和cover在Alan运行。

### E. 迁移清单与删除清单

已批准迁移（新源码行号对应 `fc26153`）：

| 旧检查（源:行） | 新检查（源:行） | 结果 |
| --- | --- | --- |
| `multiplier/RiscvMulUnitSpec.scala:23-64`：旧 wrapper 三拍数据/flush；完整路径均为 `design/src/test/scala/` | `multiplier/RiscvMulUnitSpec.scala:26-40,56-71`：统一 req，补 commit，四拍 result 和未提交 kill；同五种 op 的原输入/期望值原样，kill 用例 7×9 原样 | 2/2 PASS |
| `divider/RiscvDivUnitSpec.scala:25-65`：原 req/脉冲完成观测 | `divider/RiscvDivUnitSpec.scala:30-70`：统一 req，在接收后补 commit，改 result 观测；四组输入/期望值及 `cycles < 33` 容差原样；原文件实际没有 flush 测例，新增 kill 用例另在协议 suite | 1/1 PASS |

没有删除任何旧 RTL 字段、旧 wrapper、参照单元或测试；只做上述获准驱动/观测迁移并增加检查。旧后端完成断言和旧 wrapper 内断言也保留，第 4 步再按冻结稿迁移/删除。没有改已有期望值、随机次数、深度或 assume。

### F. 综合

新旧 MUL OOC DSP/LUT/FF、单核 100 MHz WNS/最差路径、P02 性能：未运行。

### G. B 类问题

无新增 B 类问题。B01 已按 de9c303 的分类门控决定关闭；B02 定向构建修正及新基线已完成，唯一剩余 FASE 构建失败照实保留，不修。旧基线作废。第 2 步 37/37 门槛已通过；第 3 步控制证明执行中、第 4–7 步未运行；按本轮 D 节审阅策略串行执行，unknown/timeout 时立即停下报告。
