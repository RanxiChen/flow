# SOC-3b：WB 写口后移一拍（load-use 2→3 拍），切断 L1D S2 → 写口/记分板/发射的同拍链（交给 codex 实现、跑和修）

起点：`feat/pcie-fase-20260920` 上包含本文件的提交（`ec899c7` 之后）。主工作区 `/home/chen/leisure/flow`。SOC-3 的 M1（C1-B）、M2 保持，不回退。

前情：[`SOC-3-timing-report.md`](SOC-3-timing-report.md)「1b17595 Cluster OOC」。post-synth **WNS=-6.696 ns**，36577/97510 失败端点。worst-20 全部起于 `l1d/internal2_req_idx_reg`，52 级，81% 为估算布线。网表链（从报告 net 名提取）：

`l1d/tags_ext` → `l1d/miss`（wbLineAddr/way/upgrade/allocWay/state）→ `internal2_valid` → `backend/scoreboard`（commitCursor、wb_valid）→ `l1d_late_ready` → `divUnit` → `fpUnit` ready → `scoreboard/pending`、`matches`（rd 匹配）→ `ex_rs1_data`/`ex_valid` → `sourceStall` → HPM `pending_*` / `exFp_*` CE

报告第 1、2 条建议（L1D 控制并行化、记分板读口局部化）只能削减级数，消不掉「S2 结果决定本拍 WB 是否占写口 → 长延迟来源 ready → 同拍清记分板 → 同拍发射」的结构。用户 2026-10-08 裁定：**接受 load-use 多 1 拍**，在 WB 与寄存器堆写之间切一拍。

本任务由 codex 实现、跑和修。2026-10-08 用户已完成后续多轮裁定，现稿同步最新规则；本轮先更新规格，不启动 RTL 或硬件验证。冻结规则冲突时停止该项并报告。

## 1. 裁定

冻结合同及 backend-v1-rtl-spec.md 已按 2026-10-08 最新裁定同步；以 [`backend-timing-contract.md`](../backend-timing-contract.md) 的明确拍号和 §4 为准，tools/frozen.json 同步已有冻结项。以下为摘要，不再使用旧「整体 +1」限制。

- **W2 无条件完成**：普通 GPR/FPR 在无异常 WB 提交拍末寄存 bank/rd/data/valid，下一拍优先写 RF 并穿透 ID。W2 已提交，不受 fatal/stop/kill/redirect/hold 屏蔽，不置/清 busy、不再次 retire；每拍消费一次，WB 无新普通提交则下一拍 valid 清零，复位清 valid。写口资格仅由寄存器决定。
- **lateReg**：单项接收 late 的 bank/rd/data/error，ready=!lateReg.valid||lateRegGrant；空槽不直接穿透 RF，同拍出入时写回/clear/error 来自旧项，拍末捕获新项。W2 普通写优先，后台 lateReg>DIV>MUL>FPU，全局至多一个后台 grant。冲突/饥饿计数只看这四个结果来源，原始 L1D late 接收或背压不计数；busy 由这四个实际完成清除，W2 不碰 busy。
- **拍数**：T02 普通写 E+3、依赖 EX E+4；T11 late.fire=R+7、物理写 R+8。T12 late.fire=N、迟到写 N+2、普通写 N+1、冲突 1；T21 late.fire=c、迟到写 c+8、唯一 ID 气泡 c+4、冲突 7；T22 late.fire=N、迟到写 N+3、B/C 写 N+1/N+2、冲突 2。提交/resp 拍不变。T13 对齐 lateReg/DIV/MUL/FPU 在 N valid（returnDelay 26→25），N…N+3 写回期望不变；P06 ② 提交空拍={g−1}，g 取 x1/x2 实际后台写回且 g−1 在首末 ALU commit 之间，①③不变。T04/T05/P02/T15–T17/P09/P10 等其余期望不变，默认无冲突前提改为 W2 无同 bank 普通写。loadUseBypass/T02b 作废，参数可删或无功能兼容保留，报告说明。
- **ID/EX**：WB 中有寄存器结果的访存（load、FLW/FLD、LR/SC/AMO、MMIO）仅按寄存 valid/类别/writes/bank/rd 判断 RAW/WAW，不用 resp.kind/!wbDone 解除。ID 不单独比较 W2；W2 写穿透拍可离开。保留 MEM/WB→EX，增加 W2→EX，MEM>WB>W2>捕获值；WB 只对非访存普通结果开放，不用 wbOrdinary/wbCommit/wbDone 判断；held EX 持续捕获旁路值，FPR 普通依赖也须保护。
- **串行与 fatal**：FENCE.I/SFENCE 不增加 W2/lateReg 条件，T18–T20 仍按 l1d.drained/原 mmu.idle；ESTOP 的 busy 已覆盖 lateReg，T17 用 raw busy，P09 中断/WFI 不等后台 busy，FASE 仍不支持 useFASE=true。错误 late 在 N 接收，fatal 在 N+1 lateReg 可见拍生效，可在 N 多提交一条；hartFatal 仅取 fatal 寄存器或 lateReg.valid&&error，不屏蔽已提交 W2。trace 在 WB 记架构提交，observe 测物理写；lateWriteError 对齐 lateReg 完成。
- **允许一起做**（codex 决定，不必须）：SOC-3 报告建议 1、2（L1D 控制并行化、记分板读口局部 `busy(rd) && !clear(rd)`），前提是不改拍数与协议。
- **不做**：不改 L1D 对外协议与 S0/S1/S2 拍数，不改 MulUnit，不加 false path/multicycle，不改综合策略，不开 retiming，不降频。TLB→S1 若进入最差路径族，停下报告。

## 2. 主机

按 `AGENTS.md`：每次仿真、编译、RTL 生成前重新读取 `docs/cross-project/simulation-host.md`，先校验 cloud_chen，不可用再校验并使用 Alan，两边都不可用就停止并报告具体原因。Vivado 用 Alan。新开 evidence 目录 `soc3b-<SHA短>`，不覆盖 SOC-3 证据。

## 3. 门槛（按顺序）

**2026-10-08 后续执行范围更新**：用户明确要求尽快推进 FPGA，从现有测试选最小关键子集，仅补本次改动的关键缺口，广泛程序与组合场景留到 FPGA。下列第 2 项的完整定向集合本轮不执行，改用报告 [§9](SOC-3b-wb-split-report.md#9-最小上板前验证2026-10-08本轮) 记录的最小集合；未运行的完整集合继续标记未验证。冻结行为、拍号、golden/断言以及第 3–5 项的结构、时序和停止条件保持。本轮最小功能集合通过后直接推进 RTL 生成及 Cluster OOC，不以完整回归作为这一阶段的前置条件。

1. `python3 tools/frozen_check.py` → OK (8 files)。
2. 定向（按当前主机配置）：后端时序合同 spec 全部行（含改后 T02/T11/T12/T13/T21/T22、P06）、MDU 四个 spec、L1D 三个 spec（同 SOC-3 §3.1）、BreezePrivilege、ClusterIsaSpec。只按本轮明确裁定更新期望/刺激：T13 和其它无冲突前提允许调整回填/nop 对齐，原写回期望不变；fatal 的行为变化及 W2/lateReg 安全检查按合同添加。其余期望/断言不得修改或放宽，不删除其它测试。
3. tiny RTL 生成：`GenerateBreezeCluster single gshare linux`。
4. **Cluster OOC**（Alan，参数同 SOC-3：100 MHz、AreaOptimized_high、maxThreads4、`timeout 1h`、worst-20；2026-10-08 用户将后续上限从 30 分钟改为 1 小时），另外加跑：
   - 合同 §4 结构门槛取证：禁止 S2 决定写口授权、结果出口 ready、实际 clear、操作数 RAW/WAW 或 EX 旁路选择。可定位终点用 report_timing，中间点可用 -through；网名优化掉允许 RTL fan-in 分析替代该中间查询，记录映射，不加 keep、不改策略。允许的保持/精确取消、Mshr→busy、S2→W2/HPM 输入分别报告最差级数/slack；Mshr→busy 进入全局 worst-20 且 slack<0 时停下，不挪 T10。
   - TLB→S1 permission 单独 worst-20（同 SOC-3）。
   - 综合超过 1 小时就停，记录停在哪个阶段，然后报告，不再自行重试。本轮首次 OOC 在用户调整时已正常结束（11 分 38 秒），其实际命令仍记录原 `timeout 30m`，不改写历史执行证据。
5. Cluster post-synth WNS ≥ 0 → 跑整 SoC tiny 布局布线（保留 timeout），WNS ≥ 0 → SOC-3 §3.2 验收段。
   Cluster WNS < 0 → 按 SOC-3 M3 的范围继续修，修完重跑 4；需要再改拍数或协议的，停下报告。

## 4. 报告

写入 docs/tasks/SOC-3b-wb-split-report.md：W2/lateReg 实现位置；T12/T21/T22 与 P06/T13 推导；ID/EX 寄存资格、普通写不碰 busy、fatal N+1、串行/drained 条件保留哪些（逐条）；门槛表（SHA、主机、cwd、命令、通过数/总数、exit、用时）；Cluster OOC 阶段时间、WNS/TNS/失败端点、worst-20、结构查询/RTL fan-in 证据、允许路径报告，与 SOC-3 1b17595 的对比；有 routed 结果才报告 routed。
