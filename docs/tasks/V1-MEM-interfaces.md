# V1-MEM：扫描现有代码，补 L1D/L2 外部接口（交给 codex）

分支 `feat/v1-mem-skeleton`。工作目录用单独的 worktree（例如 `git worktree add ../flow-mem-codex feat/v1-mem-skeleton`），不要在后端分支的工作区里切换分支。先读 [`docs/v1-mem-plan.md`](../v1-mem-plan.md)。

## 目标

骨架的内部结构由 Claude 负责。你的任务是让它和**现有代码**对上：凡是骨架与仓库其他部分相接的地方，按现有实现补齐，或者报告冲突。不实现 L1D/L2 内部功能（MSHR 转移、L2 槽任务等内部 `TODO` 不属于本任务）。

## 范围

1. **MMU 与权限**（`l1d/L1DCache.scala` S0/S1/S2）
   - `tlb`（`Sv39MmuBundles.TlbPortIO`）：`req/resp/kill` 的拍关系对照 `Sv39Tlb.scala` 和 `docs/breeze-mmu-rtl-spec.md`。resp 是否在 req fire 的下一拍；`miss`/`hit` 的含义；`dropS1Next` 在哪里产生。
   - `ptw`（`PtwMemIO`）：paddr 56 位接到 32 位 PA 的截断规则（≥2^32 的处理）。
   - `BreezePmpChecker`：`access`、`privilege`（有效特权，MPRV/MPP）的驱动，目前是 `DontCare`。
   - `PMAChecker`：`amoOk`、`rsrvOk` 两个字段按 l1d-rtl-spec §0.1 新增。
2. **L1I 客户端**：现有 `cache/BreezeCache.scala` 与 `frontend/BreezeFrontend.scala` 怎样改成 `coherence.ReadClientIO`（Read，`id` 区分 demand/预取）。写成单独的适配模块 `l1i/L1IClient.scala`，或者给出改造方案。
3. **集群与外壳**：`top/BreezeMulticoreClusterWishbone.scala` 换成 L1D/L1I/L2 + AXI4 `mem` + AXI4-Lite `mmio` 的连接方式；LiteX 一侧需要的桥（AXI4→Wishbone，或 LiteX 原生 AXI）。新建 `top/BreezeCluster.scala`，能 elaborate 即可，旧文件保留。
4. **DMA 端口**：现有 DMA 从口（若有）怎样映射到 `ReadClientIO`（Read / MaskWrite）。
5. **事件**：`L1DEvents` 按 `docs/l1d-spec-inputs.md` §12 补全字段，对照 `core/BreezePerformanceCounters.scala` 的现有接线。
6. **几何参数**：列出 `config.scala` 里旧几何字段的全部使用点，写出合并到 `BreezeClusterConfig.mem` 的迁移清单。本任务只列清单，不改 `config.scala`。

## 规则

- 不改 `interface/L1DCoreIO.scala` 和冻结文件（`python3 tools/frozen_check.py` 必须 OK）。
- 每次提交前都跑一遍 `testOnly flow.memsys.MemSkeletonElabSpec`，必须全部通过；新增的模块加入该测试。
- 接口位宽、方向、拍关系拿不准的，写进报告，不要猜。
- 报告写到 `docs/tasks/V1-MEM-interfaces-report.md`，内容包括：每个接口点的现有代码位置（文件:行）、改了什么、仍未解决的冲突、命令和日志路径。
