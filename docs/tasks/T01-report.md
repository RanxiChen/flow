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
