# T01：后端记分板与 MDU 改造

本任务书交给实现 agent 执行，分两个阶段。**阶段一只写文档，阶段二才写代码**；阶段二必须等用户明确宣布阶段一的 spec 冻结后才能开始。

## 0. 必须遵守的规则

1. **规格权威**：[`docs/backend-pipeline-design.md`](../backend-pipeline-design.md)（下称“设计文档”）是本任务的微架构依据；阶段一产出的 RTL spec 冻结后，是阶段二的唯一实现依据。两者没有覆盖的行为，**停下来列成问题提交，不得自行补设计**。
2. **不得为了通过而修改验收**：不得删除、放宽、跳过已有测试、断言或形式化性质；不得调小 BMC 深度、增加不合理的 assume 或改阈值来让结果通过。确需修改时，作为问题提交并说明理由。
3. **证据规则**（见 [`agent.md`](../../agent.md)）：本地改代码并 push；`sbt`、RTL 生成、仿真、形式化、Vivado 全部在 Alan 上执行。每条“通过”都要附上：提交号、Alan 上实际运行的命令、日志原文或日志文件路径。没有运行过的结果写“未运行”，不得推断。
4. **范围**：只改第 3 节列出的内容。不改前端、L1D/L2、MMU、FPU 内部、LiteX 外壳。
5. **保留现有行为**：除设计文档明确改变的部分外，现有后端的行为必须保持，包括 [`docs/bugs/`](../bugs/) 中 CORE-001 至 CORE-004 的修复、CSR 旁路与冒险规则、FENCE.I/SFENCE.VMA/satp 写/xRET 的重定向、中断与 WFI、FASE、tandem 退休追踪、HPM 计数。
6. **技能**：仓库 `.agents/skills/` 中的技能按需使用，特别是 `breeze-spec-verification`、`spec-to-testplan`、`gf-formal`、`breeze-microarchitecture-review`、`vivado-*`。可以评估并建议引入其他硬件开发技能，但引入前先列出来让用户确认；任何技能都不能改变本任务书和 spec 的要求。

## 1. 目标

按设计文档第 10 节第 1 步实施：

- 整数记分板、长延迟写口仲裁、rd 写后写检查；
- MUL、DIV 改为“EX 发起、WB 提交或 kill、结果保持到被接收”，提交后在后台完成，不再停住整条流水；
- 乘法器改用 DSP48E2 实现，4 拍、每拍可发一个；
- 除法器保留现有 radix-4，补除数为 0、有符号溢出的 1 拍快速结果；
- 访存、FPU 暂时保持现有的阻塞方式（`pipelineHold` 中去掉 MUL/DIV 两项，其余不变）；
- 与本步相关的计数器：`sb_stall_mul`、`sb_stall_div`、`wb_port_conflict`（见 [`observability-design.md`](../observability-design.md) 2.6 节），先接入现有 HPM 事件表。

设计文档第 4–7、9 节中与 MDU 相关的规则全部适用；与 L1D 迟到数据、FPU 相关的规则在本步只需要在接口上预留，不实现。

## 2. 阶段一：RTL spec 与测试计划（只写文档）

### 2.1 产出

1. `docs/backend-rtl-spec.md`：参照 [`breeze-mmu-rtl-spec.md`](../breeze-mmu-rtl-spec.md) 的写法和粒度。至少包括：
   - **现状映射**：现有 `BreezeBackend.scala` 中 ID/EX/MEM/WB 的寄存器、`pipelineHold`、旁路、各类 hazard 信号，逐一说明在新设计中保留、修改还是删除；
   - **记分板**：位宽、置位/清除时机（精确到拍和条件）、kill 时的清除、同拍置位与清除的优先级、ID 的停顿条件（rs1/rs2/rd）、写回旁路；
   - **长延迟单元统一接口**：发起、提交/kill、结果（valid/ready、目的寄存器标签）的信号与时序，要能直接复用于第 2 步 FPU 和第 3 步 L1D 迟到数据；
   - **写口仲裁**：长延迟优先、WB 让拍的条件，多来源同拍时的顺序；
   - **异常、中断、CSR、FENCE、WFI** 与后台 MDU 操作的交互，精确到拍；
   - **MUL 单元**：接口、流水级数、每级寄存器、kill 时如何作废在途级；
   - **DIV 单元**：接口、快速路径条件、忙时的 ready；
   - **必须保留的现有行为清单**（第 0 节第 5 条），每项注明对应的代码位置或 bug 编号；
   - **断言清单**：仿真断言与形式化性质分开列出；
   - **计数器**：事件定义与接入方式；
   - **未决问题**：spec 写作过程中发现设计文档没有覆盖、需要用户决定的点。
2. `docs/backend-testplan.md`：需求到验证的追踪表（可用 `spec-to-testplan`、`breeze-spec-verification`），每条需求对应：定向测试、随机测试、形式化性质、回归中的哪一项。至少覆盖第 4 节的验收内容。

### 2.2 阶段一的约束

- 不改任何 `.scala`、`.py`、`.sv` 文件。
- 文档中所有对现有代码的描述必须附文件与行号；不确定的写“未确认”。
- 完成后提交并 push，回报：提交号、两份文档的路径、未决问题列表。

## 3. 阶段二：实现（spec 冻结后）

### 3.1 允许修改的范围

- `design/src/main/scala/backend/BreezeBackend.scala`（可以拆分出新文件，如记分板、写口仲裁）
- `design/src/main/scala/multiplier/`、`design/src/main/scala/divider/`
- `design/src/main/scala/core/` 中与 HPM 事件表相关的部分
- 对应的 `design/src/test/scala/` 测试，以及新增的形式化验证目录（建议 `verification/formal/backend/`）

其他文件需要修改时，先作为问题提交。

### 3.2 实施要求

- 严格按冻结的 `docs/backend-rtl-spec.md` 实现；实现中发现 spec 有误或遗漏，停下来提交问题，不得边做边改设计。
- spec 中的仿真断言全部写进 RTL；形式化性质按 `gf-formal` 写成 SymbiYosys 配置，在 Alan 上运行。Alan 上若没有 `sby`/`yosys`，先报告并给出安装方案，等用户确认。
- 小步提交，每个提交可以单独编译通过。

## 4. 验收

全部满足才算完成：

| 类别 | 内容 |
| --- | --- |
| 形式化（记分板 + 写口仲裁 + MDU 接口，模块级） | 至少证明：同一寄存器任意时刻至多一条长延迟写在途；记分板置位当且仅当存在对应的在途结果；被 kill 的操作永不写回；每拍至多一个长延迟结果写入；结果在被接收前保持不变；在环境假设（单元有界时间内给出结果、写口有界时间内可用）下每个已提交的长延迟结果最终写回。分别报告 BMC 深度、是否得到归纳证明、未完成项 |
| 定向测试 | rd 写后写停顿；kill 时记分板清除；长延迟结果与 WB 普通写同拍；MUL 与 DIV 结果同拍；连续 MUL 每拍发射；除法快速路径（除数 0、`-2^63/-1`、W 版本）；陷入时后台 MDU 操作继续完成并写回；CSR 指令等待记分板清空；FENCE.I、中断、WFI 与后台 MDU 操作交织 |
| 单元等价性 | 新乘法器与现有 `SignedMul65x65` 在随机与边界操作数上结果一致（MUL/MULH/MULHSU/MULHU/MULW）；除法器快速路径与 RISC-V 规范一致 |
| 回归 | Alan 上完整 `sbt test` 全部通过，且通过的 suite/test 数量不少于改动前（改动前先在同一台机器上跑一次记录基线）；ACT4（`verification/act4`）中 RV64IM 相关测试全部通过；tandem 与参考模型逐条退休比对通过 |
| 随机程序 | MUL/DIV 与 ALU、分支、Load/Store、异常、中断随机交织的程序，与参考模型逐条退休比对，记录种子数与指令数 |
| 资源与时序 | 单核配置在 Alan 上做 Vivado 综合：报告乘法器的 DSP 与 LUT 用量（与改动前对比）、100 MHz 下的 WNS；记分板与写口仲裁不得成为新的最差路径，若是则报告路径 |
| 性能 | 一个除法密集和一个乘法密集的小程序，报告改动前后的周期数与 `sb_stall_*` 计数 |

## 5. 回报格式

每个阶段结束时提交一份 `docs/tasks/T01-report.md`（阶段二追加），内容：

1. 提交号与分支；
2. 实际运行的每条命令、所在机器、结果（通过/失败/未运行），附日志路径；
3. 与 spec 的偏离（应为空；不为空则逐项说明）；
4. 未决问题；
5. 已知限制。
