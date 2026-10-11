# Flow/Breeze 硬件辅助 skills

这些技能安装在项目的 `.agents/skills/`，供在本仓库工作的 Codex 使用。技能由任务匹配后按需加载，也可以在请求中明确写 `$技能名`；无需每次加载整套资源。添加后从下一轮开始可被发现。

项目共用规则见 [`hardware-skill-context.md`](hardware-skill-context.md)。固定来源、版本及适配记录见 [`hardware-skills-lock.json`](hardware-skills-lock.json) 和各技能的 `UPSTREAM.json`。原始入口及完整支撑资源保留；项目入口已适配 Chisel、Alan 与本仓库的设计权限。

## 设计与验证

| 技能 | 使用场景 |
|---|---|
| [breeze-microarchitecture-review](../.agents/skills/breeze-microarchitecture-review/SKILL.md) | 评审流水线、依赖、资源所有权、恢复、提交/kill 边界与前进保证 |
| [gf-plan](../.agents/skills/gf-plan/SKILL.md) | 把硬件需求整理为接口、阶段、FSM、资源与实现计划 |
| [spec-to-testplan](../.agents/skills/spec-to-testplan/SKILL.md) | 从规范提取行为和验证场景，分析与实现的差异 |
| [breeze-spec-verification](../.agents/skills/breeze-spec-verification/SKILL.md) | 维护需求到测试/断言及实际证据的对应关系 |
| [gf-formal](../.agents/skills/gf-formal/SKILL.md) | 局部安全、活性、可达性性质及 SymbiYosys 流程 |
| [gf-cocotb](../.agents/skills/gf-cocotb/SKILL.md) | Python 驱动、监视器、独立模型、随机反压与可复现失败 |

例如：`用 $breeze-microarchitecture-review 分析 Store 写入与年轻 Load 的冲突，先讨论，不改 RTL。`

## FPGA 工程与时序证据

| 技能 | 使用场景 |
|---|---|
| [breeze-timing-evidence](../.agents/skills/breeze-timing-evidence/SKILL.md) | 将报告绑定源版本和配置，解释关键路径与比较候选 |
| [vivado-analysis](../.agents/skills/vivado-analysis/SKILL.md) | 分析 timing/utilization/congestion/CDC/methodology 报告 |
| [vivado-timing-closure](../.agents/skills/vivado-timing-closure/SKILL.md) | 在固定目标下优化、比较并保留最佳实测候选 |
| [vivado-constraints](../.agents/skills/vivado-constraints/SKILL.md) | 审查或编写时钟、I/O、CDC 及有依据的时序例外 |
| [vivado-synth](../.agents/skills/vivado-synth/SKILL.md) | 分析存储器、字节写使能、DSP、FSM 等资源推断 |
| [vivado-impl](../.agents/skills/vivado-impl/SKILL.md) | 布局布线、物理优化、拥塞及 checkpoint 对比 |
| [vivado-tcl](../.agents/skills/vivado-tcl/SKILL.md) | 生成、审查或执行范围明确的 Tcl 自动化 |
| [vivado-sim](../.agents/skills/vivado-sim/SKILL.md) | 行为/网表/SDF 仿真策略、调试与活动捕获 |
| [vivado-debug](../.agents/skills/vivado-debug/SKILL.md) | 围绕具体故障设计 ILA/VIO 观测与触发条件 |
| [vitis-hls-synthesis](../.agents/skills/vitis-hls-synthesis/SKILL.md) | 独立 C/C++ HLS 内核；不替代 Breeze Chisel 流程 |

例如：`用 $breeze-timing-evidence 和 $vivado-analysis 分析 Alan 的 PMP 路径报告，只分析已有结果。`

## 图形化展示

| 技能 | 使用场景 |
|---|---|
| [drawio](../.agents/skills/drawio/SKILL.md) | 生成可编辑结构图、流水级图和接口图 |
| [drawio-skill](../.agents/skills/drawio-skill/SKILL.md) | 图模型的增量维护、校验、来源与布局保留 |
| [drawio-validated-workflow](../.agents/skills/drawio-validated-workflow/SKILL.md) | 内容/连接契约、结构校验、导出与视觉检查 |
| [excalidraw-validated-workflow](../.agents/skills/excalidraw-validated-workflow/SKILL.md) | 可编辑白板草图、绑定校验与本地预览 |
| [mermaid-diagram](../.agents/skills/mermaid-diagram/SKILL.md) | 文档内 FSM、交互序列和依赖图 |
| [hardware-wavedrom](../.agents/skills/hardware-wavedrom/SKILL.md) | valid/ready、阶段占用、冲突、重放和副作用的逐拍时序 |

例如：`用 $hardware-wavedrom 展示一笔老 Store 与两笔年轻 Load 的交叠，标出 SRAM 读写边沿和恢复条件。`

Draw.io 的项目导出包装器通过 `BREEZE_DRAWIO_BIN` 或 PATH 寻找已有 desktop CLI，保留 `.drawio` 原文件，不再使用 OMP 的固定 Windows staging 路径。没有 CLI 时仍可交付可编辑源文件；不声称导出或视觉检查已完成。各绘图技能也可使用当前会话已有 MCP 工具，不需要为此重复安装插件。

## 安装验证范围

技能入口、资源链接、来源、语法和部分辅助工具可以本地检查。硬件技能仍需在真实任务中结合 Alan 的工具和报告使用；本次安装不启动 RTL 构建、形式证明、Vivado/Vitis、烧板或外部发布。上游示例与第三方脚本的保留不等于逐个执行验证。
