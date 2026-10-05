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
