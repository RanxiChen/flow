# Flow Agent Notes

## 开发与验证位置

本地编辑代码、提交并 push 到 GitHub；Alan 从 GitHub pull 对应提交后运行构建和验证。`sbt` 编译、RTL 生成、仿真和测试都在 Alan 执行，本地不运行。验证前确认 Alan 的分支、HEAD、工作区和已有任务，保留无关改动与正在运行的任务；报告验证所用提交和实际结果。

## Breeze 重做方向

当前仓库中的 Breeze 是待覆盖的旧实现。后续新版 Breeze 的架构讨论和实现应从新的目标与已达成的设计决定出发，不以旧 Breeze 的 RTL、接口、流水级、系统约束、测试入口或阶段性优先级作为必须沿用的基线。

旧代码和旧文档可以用来了解历史、定位可复用的局部机制，不能仅因它们已经存在就要求新版兼容。如果新设计需要不同接口或模块边界，直接按新设计确定；不要为了迁就旧 Breeze 而削弱方案。只有用户明确要求迁移、兼容或复用某一部分时，才把那一部分作为约束。

当前的 `BreezePipelinedDCache` 是概念/模块框架，不代表最终 VIPT D-cache。它的现有实现也不应限制新 MMU 的结构。新版访存方向是先确定 MMU、TLB、PTW 与 D-cache 的接口，再改造 D-cache，之后逐步更新 Breeze 其他部分。

## 新版 MMU

新版 MMU 的设计文档有三份，均已定案。独立实现位于 `design/src/main/scala/mmu/sv39/`（包 `flow.mmu.sv39`）；Alan 模块测试 29/29 通过，版本、覆盖和证据见 [`docs/breeze-mmu-validation.md`](docs/breeze-mmu-validation.md)。系统集成、综合时序和板上运行仍待验证。

- [`docs/breeze-mmu-vipt-design.md`](docs/breeze-mmu-vipt-design.md)：架构与取舍。独立 iTLB/dTLB（组相联 + 全相联超页阵列，16 位 ASID tag），共享 PTW，两级非叶 walk-cache，Svade，PTW 经 D-cache 专用物理通道读 PTE，miss 阻塞不重放，sfence 串行执行。
- [`docs/breeze-mmu-rtl-spec.md`](docs/breeze-mmu-rtl-spec.md)：RTL 实现规格。写 MMU RTL 时以它为准：寄存器、流水级、状态机、接口、断言、测试按它实现，不增删流水级，不自行补设计；它没覆盖的行为先问用户。
- [`docs/breeze-mmu-closure-checklist.md`](docs/breeze-mmu-closure-checklist.md)：MMU 依赖其他模块的假设（跨页取指、非对齐 trap、sfence 串行化、D-cache 前进保证等），系统闭环时逐项检查。

讨论新机制时，先解释正常路径、冲突/停顿、恢复与资源代价，再确定接口和实现细节。明确区分架构决定、RTL 实现、模块仿真、系统集成、综合时序和板上运行证据。不要把现有框架或历史测试结果写成新版 Breeze 的验证结论。
