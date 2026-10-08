# SOC-3b：WB 写口后移一拍（load-use 2→3 拍），切断 L1D S2 → 写口/记分板/发射的同拍链（交给 codex 实现、跑和修）

起点：`feat/pcie-fase-20260920` 上包含本文件的提交（`ec899c7` 之后）。主工作区 `/home/chen/leisure/flow`。SOC-3 的 M1（C1-B）、M2 保持，不回退。

前情：[`SOC-3-timing-report.md`](SOC-3-timing-report.md)「1b17595 Cluster OOC」。post-synth **WNS=-6.696 ns**，36577/97510 失败端点。worst-20 全部起于 `l1d/internal2_req_idx_reg`，52 级，81% 为估算布线。网表链（从报告 net 名提取）：

`l1d/tags_ext` → `l1d/miss`（wbLineAddr/way/upgrade/allocWay/state）→ `internal2_valid` → `backend/scoreboard`（commitCursor、wb_valid）→ `l1d_late_ready` → `divUnit` → `fpUnit` ready → `scoreboard/pending`、`matches`（rd 匹配）→ `ex_rs1_data`/`ex_valid` → `sourceStall` → HPM `pending_*` / `exFp_*` CE

报告第 1、2 条建议（L1D 控制并行化、记分板读口局部化）只能削减级数，消不掉「S2 结果决定本拍 WB 是否占写口 → 长延迟来源 ready → 同拍清记分板 → 同拍发射」的结构。用户 2026-10-08 裁定：**接受 load-use 多 1 拍**，在 WB 与寄存器堆写之间切一拍。

本任务由 codex 实现、跑和修。下文是冻结裁定，冲突时停止该项并报告。

## 1. 裁定

冻结合同已改：[`backend-timing-contract.md`](../backend-timing-contract.md) 的 T02、T11、T12/T21/T22（※b）、§3 例外与 §4 `load-use`/`W2`/`lateReg`/结构门槛，`tools/frozen.json` 已重新记录。以合同原文为准，下面只是摘要。

- **W2**：WB 的 commit、陷入、CSR 生效、L1D `resp` 与 WB 同拍等全部不变。WB 普通 GPR/FPR 写（数据、rd、堆选择、写使能）在 WB 末寄存，下一拍 W2 写寄存器堆、清记分板、参与写口仲裁，并写穿透到 ID。W2→EX 加一路旁路，ALU 相关（T01/T03 等）不增加气泡。W2 是否占口只由寄存器决定。
- **lateReg**：`l1d.late` fire 当拍寄存进单项 `lateReg`，`l1d.late.ready` 只是寄存器的函数（基线 `!lateReg.valid`）。仲裁顺序仍是普通写优先，长延迟侧 L1D(lateReg) > DIV > MUL > FPU。饥饿保护计数与阈值、`wb_port_conflict` 定义不变。
- **拍数**：T02 `gprWrite(x1)=E+3`、`ex(add)=E+4`；T11 `gprWrite=R+8`；T04/T05/P02/T13/P10 等其余行不变（MUL 仍 E+4 写回，除非与 W2 同拍冲突，冲突规则同前）。T12/T21/T22 由 codex 按新结构推导，只允许整体平移，其它差异停下报告。`loadUseBypass`/T02b 作废，参数可删，在报告中说明。
- **ID 冒险检测**：WB 中已提交、尚未到 W2 的 load/写 GPR 指令，以及 W2 本身，都要被 ID 看到（停顿或旁路），不得读到旧值。实现方式由 codex 定。
- **CSR/WB 串行指令、陷入、FASE 接管、FENCE.I/SFENCE 的 drained 条件**：凡依赖「寄存器堆已写」或「记分板已空」的判定，都要把 W2 与 `lateReg` 计入（例如 `busy==0` 的含义扩成也包括 W2/lateReg 无在途写）。逐条列在报告里。
- **允许一起做**（codex 决定，不必须）：SOC-3 报告建议 1、2（L1D 控制并行化、记分板读口局部 `busy(rd) && !clear(rd)`），前提是不改拍数与协议。
- **不做**：不改 L1D 对外协议与 S0/S1/S2 拍数，不改 MulUnit，不加 false path/multicycle，不改综合策略，不开 retiming，不降频。TLB→S1 若进入最差路径族，停下报告。

## 2. 主机

按 `AGENTS.md`：每次仿真、编译、RTL 生成前重新读取 `docs/cross-project/simulation-host.md`，先校验 cloud_chen，不可用再校验并使用 Alan，两边都不可用就停止并报告具体原因。Vivado 用 Alan。新开 evidence 目录 `soc3b-<SHA短>`，不覆盖 SOC-3 证据。

## 3. 门槛（按顺序）

1. `python3 tools/frozen_check.py` → OK (8 files)。
2. 定向（cloud_chen）：后端时序合同 spec 全部行（含改后的 T02/T11/T12/T21/T22）、MDU 四个 spec、L1D 三个 spec（同 SOC-3 §3.1）、BreezePrivilege、`ClusterIsaSpec`。改了拍数，ISA 级必须跑。除合同 ※b 允许的推导值和 T02/T11 之外，任何测试期望或断言都不得改。
3. tiny RTL 生成：`GenerateBreezeCluster single gshare linux`。
4. **Cluster OOC**（Alan，参数同 SOC-3：100 MHz、AreaOptimized_high、maxThreads4、`timeout 30m`、worst-20），另外加跑：
   - 合同 §4 结构门槛的 `report_timing -from` 查询，结果单独存档；
   - TLB→S1 permission 单独 worst-20（同 SOC-3）。
   - 综合超过 30 分钟就停，记录停在哪个阶段，然后报告，不再自行重试。
5. Cluster post-synth WNS ≥ 0 → 跑整 SoC tiny 布局布线（保留 timeout），WNS ≥ 0 → SOC-3 §3.2 验收段。
   Cluster WNS < 0 → 按 SOC-3 M3 的范围继续修，修完重跑 4；需要再改拍数或协议的，停下报告。

## 4. 报告

写入 `docs/tasks/SOC-3b-wb-split-report.md`：W2/lateReg 的实现位置；T12/T21/T22 的推导；ID 冒险检测与串行/drained 条件改了哪些（逐条）；门槛表（SHA、主机、cwd、命令、通过数/总数、exit、用时）；Cluster OOC 的阶段时间、WNS/TNS/失败端点、worst-20（起点、终点、slack、级数、logic/route）、结构门槛查询结果、与 SOC-3 `1b17595` 的对比；有 routed 结果的话报告 routed 结果。
