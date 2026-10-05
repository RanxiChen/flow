# Flow Agent Notes

## 开发与验证位置

本地编辑代码、提交并 push 到 GitHub；Alan 从 GitHub pull 对应提交后运行构建和验证。`sbt` 编译、RTL 生成、仿真和测试都在 Alan 执行，本地不运行。验证前确认 Alan 的分支、HEAD、工作区和已有任务，保留无关改动与正在运行的任务；报告验证所用提交和实际结果。

## Breeze 重做方向

当前仓库中的 Breeze 是待覆盖的旧实现。后续新版 Breeze 的架构讨论和实现应从新的目标与已达成的设计决定出发，不以旧 Breeze 的 RTL、接口、流水级、系统约束、测试入口或阶段性优先级作为必须沿用的基线。

旧代码和旧文档可以用来了解历史、定位可复用的局部机制，不能仅因它们已经存在就要求新版兼容。如果新设计需要不同接口或模块边界，直接按新设计确定；不要为了迁就旧 Breeze 而削弱方案。只有用户明确要求迁移、兼容或复用某一部分时，才把那一部分作为约束。

旧的 `BreezePipelinedDCache` 概念框架已删除。新版 L1D 按 [`docs/dcache-pipeline-design.md`](docs/dcache-pipeline-design.md) 重新实现，接口先与 MMU、TLB、PTW 对齐。

## 新版 MMU

新版 MMU 的设计文档有三份，均已定案。独立实现位于 `design/src/main/scala/mmu/sv39/`（包 `flow.mmu.sv39`）；Alan 模块测试 29/29 通过，版本、覆盖和证据见 [`docs/breeze-mmu-validation.md`](docs/breeze-mmu-validation.md)。系统集成、综合时序和板上运行仍待验证。

- [`docs/breeze-mmu-vipt-design.md`](docs/breeze-mmu-vipt-design.md)：架构与取舍。独立 iTLB/dTLB（组相联 + 全相联超页阵列，16 位 ASID tag），共享 PTW，两级非叶 walk-cache，Svade，PTW 经 D-cache 专用物理通道读 PTE，miss 阻塞不重放，sfence 串行执行。
- [`docs/breeze-mmu-rtl-spec.md`](docs/breeze-mmu-rtl-spec.md)：RTL 实现规格。写 MMU RTL 时以它为准：寄存器、流水级、状态机、接口、断言、测试按它实现，不增删流水级，不自行补设计；它没覆盖的行为先问用户。
- [`docs/breeze-mmu-closure-checklist.md`](docs/breeze-mmu-closure-checklist.md)：MMU 依赖其他模块的假设（跨页取指、非对齐 trap、sfence 串行化、D-cache 前进保证等），系统闭环时逐项检查。

讨论新机制时，先解释正常路径、冲突/停顿、恢复与资源代价，再确定接口和实现细节。明确区分架构决定、RTL 实现、模块仿真、系统集成、综合时序和板上运行证据。不要把现有框架或历史测试结果写成新版 Breeze 的验证结论。

## 新版内存系统、后端与前端

以下四份是新版 Breeze v1 的微架构设计文档，审阅冻结后再分别编写 RTL 级 spec；在冻结前不写对应 RTL。

- [`docs/dcache-pipeline-design.md`](docs/dcache-pipeline-design.md)：L1D（单 MSHR hit-under-miss、S2 判定即提交、阻塞式 MMIO）、L2/Home、自有四链路一致性协议、AXI 集群边界与 LiteX 外壳、KCU105 参数。
- [`docs/backend-pipeline-design.md`](docs/backend-pipeline-design.md)：4 级顺序单发射后端、记分板、长延迟写口仲裁、MDU 改造。
- [`docs/frontend-prediction-design.md`](docs/frontend-prediction-design.md)：BTB、全局历史、RAS、S1 返回预测、L1I 下一行预取。
- [`docs/observability-design.md`](docs/observability-design.md)：性能计数器、JTAG 读取与调试记录。

## 硬件辅助 skills

项目技能位于 `.agents/skills/`，按当前任务选择，不整套加载。使用索引见 [`docs/hardware-skills.md`](docs/hardware-skills.md)，共用适配约定见 [`docs/hardware-skill-context.md`](docs/hardware-skill-context.md)。微架构讨论优先考虑 `breeze-microarchitecture-review`；需求到验证追踪使用 `breeze-spec-verification`；报告与源版本对齐使用 `breeze-timing-evidence`。其他技能补充规划、验证、FPGA 工程与绘图能力，不改变本文件及用户指定的任务权限和规格权威。

## 形式化验证的做法

用 SymbiYosys 做形式化时，按以下规则（来自 T01 第 3 步：带完整 65×65 乘法和 64 位除法的证明在 Z3 上跑了 500 多秒后求解器崩溃，多路并行把 Alan 内存占满）：

- **形式化只证控制和协议**：握手、提交/kill、结果保持、计数守恒、互斥、无丢失/无重复。宽位算术（乘法、除法、浮点运算等）用抽象替换：结果换成逐拍任意值，多拍单元的完成时间换成有界任意值并加“有界内必然完成”的 assume；外壳控制逻辑保持原样。这不是机器强弱的问题，宽位乘除法原样放进求解器在任何机器上都基本证不出来。
- **算术正确性由仿真负责**：随机向量对参照模型（如 `SignedMul65x65`、BigInt）逐条比对，在报告中写明这部分由哪项测试覆盖。
- **一次只跑一个求解任务**，不要并行启动多个 SBY/求解器进程。
- 无界证明优先用 `abc pdr`；BMC 和 cover 用 `abc bmc3` 或 `smtbmc`，深度按任务登记值执行。
- 单个任务设 timeout（默认 3600 秒）。超时或 unknown 照实记录并停下报告，不要反复更换引擎或私自降低深度、增加 assume 来求得结果。
- 抽象本身要写进报告：替换了哪个节点、用了哪些 assume、为什么不会漏掉真实行为（任意值集合包含全部真实结果）。
