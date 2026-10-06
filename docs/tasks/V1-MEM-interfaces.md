# V1-MEM：扫描现有代码，补 L1D/L2 外部接口（交给 codex）

**开始条件**：V1-BE 已完成（`docs/tasks/V1-BE-report.md` 记录完整后端合同结果）。先把 `feat/v1-mem-skeleton` 合并进 `feat/pcie-fase-20260920`，本任务在合并后的后端分支上做。合并冲突预计只出现在 `config`：保留两边内容，`BreezeMemGeometry` 暂不挂入 `BreezeClusterConfig`（见第 6 项）。先读 [`docs/v1-mem-plan.md`](../v1-mem-plan.md)。

## 0. 先做：命名清理与删除旧实现

v1 是覆盖式重构。规则：

1. 新模块用功能名，不加 `V1`、`Committed`、`New`、`Legacy` 这类区分新旧的词。
2. 新旧重名时**删旧的**，新的用原名。
3. 旧测试跟着旧模块一起删；不保留让旧测试跑在新 RTL 上的适配层。
4. `Breeze` 前缀是项目名，不动；也不给新模块补加。

| 现在 | 改为 / 动作 |
| --- | --- |
| `backend/V1Scoreboard`、`V1Writeback`、`V1BackendObservation` | `Scoreboard`、`Writeback`、`BackendObservation`；内部 `V1LongSource`、`V1FpEntry`、`V1Request`、`V1Event` 等全部去掉前缀 |
| `multiplier/CommittedMulUnit` | `MulUnit`；删除 `RiscvMulUnit`（无引用） |
| `divider/CommittedDivUnit` | `DivUnit`；删除 `RiscvDivUnit`（无引用） |
| `fpu/CommittedFpUnit` | `FpUnit`；删除旧阻塞包装 `BreezeFpUnit` 及其测试；`FlowFpnewWrapper.sv` 去掉只给旧包装用的 response 缓存路径 |
| `cache/BreezePLRU` | 删除，统一用 `mmu/sv39/TreePlru` |
| 测试 `V1*Spec`、`Committed*Spec` | 同名去前缀（如 `ScoreboardSpec`、`MulUnitSpec`）；合同测试名仍以合同行 ID 开头 |
| `test/.../V1LegacyTestAdapter` 及依赖它的旧测试 | 删除。旧测试覆盖的行为若在新 RTL 上仍有效，就作为新测试直接写在新接口上；否则删除 |
| 打印标记 `[V1-CYCLE]`、断言文本 `"[V1 Sxx] ..."` | 改为 `[CYCLE]`、`"[Sxx] ..."`；测试台匹配这些字符串的地方同步修改 |
| 注释里的 "v1"、"old"、"legacy" | 说明当前行为的保留，只为区分新旧的删除 |
| `cache/BreezeDCache`、`cache/BreezeL2Home`、`cache/Coherence.scala`、`top/BreezeMulticoreClusterWishbone` 及其测试 | 第 3 项新集群 `top/BreezeCluster` 能 elaborate 后删除，同一提交里一起删 |
| `cache/BreezeCache`（现 L1I） | 第 2 项改造成一致性 Read 客户端后，按功能改名为 `l1i/L1ICache`，删除旧文件 |

要求：
- 改名用 `git mv`，保留历史；每一类改名单独提交，提交信息写明对应关系。
- 改名后全量 `sbt test` 的通过数不少于改名前（删掉的旧测试除外，在报告里逐个列出删除理由）；冻结检查必须 OK。
- `docs/backend-v1-rtl-spec.md` 等非冻结文档里的类名同步更新；冻结文件不改（合同只用行 ID，不含类名）。
- 为避免重复：删除前用 `git grep -w` 确认已无引用，并把结果写进报告。

## 目标

骨架的内部结构由 Claude 负责。你的任务是让它和**现有代码**对上：凡是骨架与仓库其他部分相接的地方，按现有实现补齐，或者报告冲突。不实现 L1D/L2 内部功能（MSHR 转移、L2 槽任务等内部 `TODO` 不属于本任务）。

## 范围

1. **MMU 与权限**（`l1d/L1DCache.scala` S0/S1/S2）
   - `tlb`（`Sv39MmuBundles.TlbPortIO`）：`req/resp/kill` 的拍关系对照 `Sv39Tlb.scala` 和 `docs/breeze-mmu-rtl-spec.md`。resp 是否在 req fire 的下一拍；`miss`/`hit` 的含义；`dropS1Next` 在哪里产生。
   - `ptw`（`PtwMemIO`）：paddr 56 位接到 32 位 PA 的截断规则（≥2^32 的处理）。
   - `BreezePmpChecker`：`access`、`privilege`（有效特权，MPRV/MPP）的驱动，目前是 `DontCare`。
   - `PMAChecker`：`amoOk`、`rsrvOk` 两个字段按 l1d-rtl-spec §0.1 新增。
2. **L1I 客户端**：现有 `cache/BreezeCache.scala` 与 `frontend/BreezeFrontend.scala` 怎样改成 `coherence.ReadClientIO`（Read，`id` 区分 demand/预取）。写成单独的适配模块 `l1i/L1IClient.scala`，或者给出改造方案。
3. **集群与外壳**：`top/BreezeMulticoreClusterWishbone.scala` 换成 L1D/L1I/L2 + AXI4 `mem` + AXI4-Lite `mmio` 的连接方式；LiteX 一侧需要的桥（AXI4→Wishbone，或 LiteX 原生 AXI）。新建 `top/BreezeCluster.scala`，能 elaborate 即可；之后按第 0 项删除旧集群与旧 cache。
4. **DMA 端口**：现有 DMA 从口（若有）怎样映射到 `ReadClientIO`（Read / MaskWrite）。
5. **事件**：`L1DEvents` 按 `docs/l1d-spec-inputs.md` §12 补全字段，对照 `core/BreezePerformanceCounters.scala` 的现有接线。
6. **几何参数**：列出 `config.scala` 里旧几何字段的全部使用点，写出合并到 `BreezeClusterConfig.mem` 的迁移清单。本任务只列清单，不改 `config.scala`。

## 规则

- 不改 `interface/L1DCoreIO.scala` 和冻结文件（`python3 tools/frozen_check.py` 必须 OK）。
- 第 0 项完成后，每次提交前都跑一遍 `testOnly flow.memsys.MemSkeletonElabSpec`，必须全部通过；新增的模块加入该测试。
- 接口位宽、方向、拍关系拿不准的，写进报告，不要猜。
- 报告写到 `docs/tasks/V1-MEM-interfaces-report.md`，内容包括：每个接口点的现有代码位置（文件:行）、改了什么、仍未解决的冲突、命令和日志路径。
