# V1-BE：后端 v1 规范、RTL 与测试（交给 codex）

一次完成，不分阶段，不等中间审阅。分支 `feat/pcie-fase-20260920`。先读 `agent.md`、`docs/v1-impl-plan.md`。

## 1. 冻结输入（权威，不得修改）

`tools/frozen.json` 列出的文件全部冻结。每次提交前运行 `python3 tools/frozen_check.py`，不是 OK 就不得提交。

| 文件 | 作用 |
| --- | --- |
| `docs/backend-timing-contract.md` | **拍数与重叠合同**：每行一条测试，期望值不得改 |
| `docs/backend-rtl-spec.md` | T01 规则：记分板、MDU、写口、A01–A08、S01–S16，全部沿用 |
| `docs/backend-pipeline-design.md` | 后端微架构 |
| `docs/l1d-rtl-spec.md` | **§1.1 `L1DCoreIO` 是后端↔L1D 接口，逐字段照用，不增删改** |
| `docs/v1-integration-notes.md` | FENCE.I / SFENCE.VMA / CVFPU 事实 |
| `docs/breeze-mmu-rtl-spec.md`、`docs/dcache-pipeline-design.md`、`docs/coherence-l2-rtl-spec.md` | 上下游合同 |

## 2. 产出

1. `docs/backend-v1-rtl-spec.md`：在 T01 规则上扩展：
   - 写口四来源：L1D late > DIV > MUL > FPU；
   - FPR 记分板（f0–f31，f0 有效）、整数/浮点交叉置位（FP→GPR 类置整数位，GPR→FP 类置 FP 位）；
   - FPU：CVFPU `TagType` 改为在途表索引宽度，在途表（按 tag 索引：valid、committed、rd、目的寄存器堆）；commit 最老未提交项、WB kill 作废全部未提交项（未提交项返回时丢弃）；fflags 写回时按位或累积；CSR 等两组记分板清空 + EX/MEM/WB 无已发射长延迟项（A07 推广到 FPU 与 L1D）；
   - 访存：EX/MEM/WB 对齐 S0/S1/S2；`s1Kill`、`s2Kill` 的产生条件；`resp` 三种判定与 `s2Hold` 下 WB 保持；Load miss 提交置位；`late` 写回；`late.error` → 该 hart 停止、输出 `hartFatal`、不陷入；
   - FENCE 作为 L1D 请求；FENCE.I、SFENCE.VMA 在 WB 串行（integration-notes §3）；删除 `dcacheFlushReq/Done`；
   - AMO、aq/rl LR/SC、MMIO 在 WB 等结果；MMIO 发出后中断等它（`mmioBusy`）；
   - `loadUseBypass` 参数（合同第 4 节）。
   规范每条规则末尾标来源：`[文件§节]`，或 `[自定]`（实现细节，自己定），或 `[新决定]`（冻结输入没有覆盖、且影响拍数/结构/接口）。
2. RTL：实现上述规范。拍数与结构严格按合同；合同第 3 节禁止项全部遵守。
3. 测试：
   - 合同 T01–T20、T02b、P01–P10 每行一个测试，测试名以行 ID 开头（如 `T11_loadMissLate`），按拍号精确断言；
   - T01 的 S01–S16 断言扩展到 FPU 与 L1D 来源；
   - 后端模块测试用行为 L1D 模型（按 `L1DCoreIO` 合同随机延迟、随机 `s2Hold`/`late.ready` 背压）。
4. 报告 `docs/tasks/V1-BE-report.md`：提交号；全部 `[新决定]` 列表；`[自定]` 摘要；每条合同测试的结果与 Alan 命令、日志路径；未运行的写"未运行"。

## 3. 规则

1. **测试不过只改 RTL**。不得改冻结文件、改期望拍数、删除/跳过/放宽测试或断言、加 `assume`、对测试场景写特判（识别地址、PC、指令序列）。
2. **自己定细节**：编码、信号命名、文件拆分、测试脚本组织、在途表深度（以 P08 吞吐不受限为下限）等，标 `[自定]` 写入报告，不停下来问。
3. **只在以下情况停下**：冻结文件之间互相矛盾；或按冻结规则推出的拍数与合同某行不符（附逐拍推导）；或必须新增合同第 3 节禁止的结构。停下时只报这一项，其余工作照做。
4. 合同中标 ※ 的行（T11、P05）是 Claude 按 L1D spec 推导的值；你推导不同则按规则 3 报告。
5. 不做形式化（`agent.md`）。sbt、仿真都在 Alan 上跑；每个"通过"附命令与日志。
6. 范围：`backend/`、`multiplier/`、`divider/`、`fpu/` 中后端侧接口包装（`BreezeFp.scala` 的 Chisel 部分与 `FlowFpnewWrapper.sv` 的 tag 接线）、`core/` 中 HPM/RegFile/接线、`interface/`、`sim/` trace、对应测试。**不改 CVFPU 内部**、不改 L1D/L2/MMU/前端。L1D 尚未实现时，用行为模型测后端；核心顶层接线留到集群 spec。
7. 提交信息前缀 `V1-BE/<n>`。

## 4. 验收（Claude 审核）

- `frozen_check.py` OK；
- 合同每行测试存在且通过；
- 报告中 `[新决定]` 逐条由 Claude 裁定；
- 抽查 diff：无测试特判、无禁止结构。
