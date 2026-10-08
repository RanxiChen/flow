# SOC-3c：S2 停顿/取消不得穿过其它模块握手（交给 codex 实现、跑和修）

起点：`feat/pcie-fase-20260920`，SOC-3b 工作区（验证快照 `21e3b18`）。主工作区 `/home/chen/leisure/flow`。SOC-3 M1/M2、SOC-3b 的 W2/lateReg 保持，不回退。

前情：[`SOC-3b-wb-split-report.md`](SOC-3b-wb-split-report.md) §9.4。SOC-3b 已切断写口反馈链（S2→W2 输入 +2.085 ns，S2→Mshr 置 busy +1.544 ns），Cluster OOC post-synth WNS −6.696 → **−4.244 ns**，最差 52 → 42 级。新最差路径（`internal2_req_idx_reg` → `frontend/realigner/faultVaddrReg/CE`）逐级拆分：

| 段 | 累计 | 级数 | 内容 |
|---|---|---|---|
| ① L1D 内部 | 0 → 6.46 ns | ~20 | tags → wbLineAddr → victim/way → state → upgrade → allocWay → wbHasData → amoOld/r_paddr → internal2_valid → needsRecheck，才得出 S2 结局 |
| ② 后端 | → 8.16 | 4 | downHold / wbCanLeave → allowEx |
| ③ CVFPU | → 11.30 | 8 | `fpUnit.io.req.valid`（含 allowEx）→ fpnew opgroup 仲裁 → `in_ready` → resourceWait |
| ④ 前端 | → 14.16 | 7 | exAdvance → branchRedirect / fetchBuffer.ready → fetchTranslator → realigner CE |

① 单独占 65% 周期；③④ 是结构问题：只要 S2 停顿/取消还要穿过 CVFPU 的 valid/ready 和前端状态机，① 再压也过不了（3 + 1.6 + 3.1 + 2.6 ≈ 10.3 ns）。

本任务由 codex 实现、跑和修。下文是 Claude 裁定，冲突时停止该项并报告。

## 0. 先提交 SOC-3b

开工前把 SOC-3b 的实现作为一次提交落盘：`design/` 下 SOC-3b 改动的 RTL 与测试、`ClusterWbSplitSmokeSpec`、SOC-3b 已同步的规格文档、`tools/frozen.json`、`docs/tasks/SOC-3b-wb-split*.md`、`records/soc3b-*`。**不要带上**与 SOC-3b 无关的工作区改动（`docs/tasks/SOC-2-bram-report.md`、`docs/tasks/V1-MEM-interfaces-report.md`、`.agents/`、`AGENTS.md`、`docs/cross-project/`、`docs/figures/`、`docs/hardware-skill*`、`docs/plans/` 等）。提交后的 SHA 记为 SOC-3b 实现版本，补到 SOC-3b 报告 §9 末尾；本任务的证据目录用 SOC-3c 自己的 SHA。

## 1. 总原则

**L1D S2 结局（含 `s2Hold`、`resp.valid/kind`）以及由它派生的 `downHold`、`wbCanLeave`、`wbKill`，只允许驱动：后端各级的 valid/CE，以及寄存器的 D 端（1～2 级逻辑）。不得穿过 CVFPU、前端、HPM 的 valid/ready/CE 逻辑。** 下列 C1–C4 是具体落法。拍数合同（`backend-timing-contract.md` 全部行）与 L1D 对外协议不变。

## 2. 裁定

### C1 FPU 请求与停顿解耦

- EX 增加 `exFpIssued`（EX 装入新指令或被 kill 时清零，`fpUnit.io.req.fire` 时置位）。
- `fpUnit.io.req.valid = exFpLong && !exFpIssued && !hartFatal && !stopped`，**不含** `downHold`/`wbKill`/`allowEx`。`hartFatal`、`stopped` 必须是纯寄存器。
- `resourceWait` 的 FP 项改为 `exFpLong && !exFpIssued && !fpUnit.io.req.ready`；`exAdvance` 仍为 `allowEx && !resourceWait`，两路并联而非串联。
- fire 当拍若有 `wbKill`：在途表把该项登记为作废（valid=0 或等效标记），其 CVFPU 输出按现有「作废返回 ready=1 丢弃」规则处理，不写 RF/flags、不占 commitCursor；`killDrain` 照常置位。`wbKill` 只进在途表寄存器的 D 端。
- 未保持时 T14（`fpu.in_valid` 在 E）不变；EX 被保持时可以提前 fire，且只 fire 一次。P07/P08/T15–T17 不变。
- **S13 修订**（授权 codex 同步改 `backend-rtl-spec.md` S13 行与 `backend-v1-rtl-spec.md` 对应条目，并重登记 `tools/frozen.json`，不新增冻结文件）：FPU 请求可在 WB kill 同拍 fire，前提是该项入表即作废、无任何架构副作用；L1D、MUL、DIV 请求仍按原 S13 在 WB kill 拍禁止。对应断言按此改写，其余断言不动。
- MUL/DIV 不改（MulUnit、DIV 本体不动）。若 MUL/DIV `req.valid → ready → exAdvance` 进入最差路径族，停下报告。

### C2 前端与 S2 解耦

- **(a) EX 分支纠错快路**：`branchRedirect` 只由 EX 寄存器、EX 操作数（旁路源 MEM/WB/W2 均为寄存值）和新增 `exRedirectSent` 决定，**不含** `exAdvance`/`downHold`/`wbKill`/`resourceWait`。EX 被保持时只发一次；`mem.predictionMiss` 取「本条已发 redirect」。BTB/PHT/GHR 训练仍按原规则只在 EX 真推进时进行。未保持时分支纠错拍数不变。
- **(b) WB 慢路**：所有 `wbKill` 来源的改向（陷入、中断、FENCE.I、SFENCE、xRET、satp、WFI）由前端寄存一拍后再执行（PC、translator/realigner 清空、I-cache flush 都在下一拍生效）。后端 `io.frontendRedirect` 是否拆成快/慢两组由 codex 定；`observe` 的 redirect / icacheFlush 事件仍在 WB 当拍，T18–T20 期望不变。后端在 WB kill 的**下一拍**用寄存条件禁止 ID 离开，防止间隙中的错误路径指令进入 EX。同拍快慢都有时，慢路在下一拍覆盖。codex 必须核实：间隙中已发出的取指/I-cache 回填不会在 flush 之后装入旧数据，SFENCE 后间隙取指的结果被丢弃。
- **(c) ID 入口 skid**：ID 入口加 2 项 skid buffer，前端看到的 `fetchBuffer.ready` 只取寄存器（如「skid 未满」）；skid 空时直通不加拍。WB kill 当拍清 skid（D 端），快路 redirect 同理。
- (b) 让陷入类改向多 1 拍，属于前端内部行为，无合同行受影响；ClusterIsa 程序的周期数变化不算失败。

### C3 L1D：S2 结局与分配逻辑分开（SOC-3 建议 1）

- S2 结局只依赖：tag 命中向量、命中 way 的行状态、MSHR/PS/写回状态寄存器、S1 预先比较并寄存的同行匹配位（加上与 S2 本拍正在分配项的转发比较）、已寄存的 TLB/PMP/PMA 结果。
- victim/allocWay/upgrade 的选择、`wbHasData`、`amoOld`、`r_paddr` 等分配与数据选择，只进 MSHR/写回寄存器的 D 端，移出结局锥。victim 可放到 S1 按替换状态预先算好并寄存。
- 不改 L1D 对外协议、S0/S1/S2 拍数、`L1S2` 字段语义、一致性协议。目标（指导值，不是门槛）：L1D 边界上 `s2Hold` ≤ 10 级。

### C4 HPM 事件先寄存

HPM3+ 的原始事件向量先寄存一拍再进选择与计数；`mcycle`/`minstret` 不变。若已有测试因计数晚一拍而差 1，允许按此调整该测试期望并在报告逐条列出。

### 顺序与不做

- C1–C4 可以一次做完上 Vivado，也可以分两轮（先 C1/C2/C4，再 C3），codex 定。
- **不做**：不改拍数合同、L1D 对外协议，不加 false path/multicycle、keep，不改综合/实现策略，不开 retiming，不降频。TLB→S1 若进入最差路径族，停下报告。需要改合同拍号才能修的，停下报告。

## 3. 主机

按 `AGENTS.md`：每次仿真、编译、RTL 生成前重新读取 `docs/cross-project/simulation-host.md`，先校验 cloud_chen，不可用再校验并使用 Alan，两边都不可用就停止并报告具体原因。Vivado 用 Alan。证据目录 `soc3c-<SHA短>`，不覆盖 SOC-3/3b 证据。

## 4. 门槛（按顺序）

1. `python3 tools/frozen_check.py` → OK (8 files)。
2. 定向（cloud_chen，可与 Vivado 并行）：
   - `BackendContractSpec` **全部**、`WritebackSpec`；
   - 前端已有 spec（`breezefrontend*`、`BreezeInstrRealignerSpec`、`BreezeFrontendGShareSpec` 等，codex 选，列在报告）；
   - 动了 L1D：SOC-3 §3.1 的 L1D 定向测试 + `L1DL2MultiCoreSpec`、`L1DL2LitmusSpec`；
   - `ClusterIsaSpec`、`ClusterWbSplitSmokeSpec`、`BreezePrivilegeSpec`；HPM 相关已有测试。
   - 除 C1 的 S13 断言改写、C4 允许的 HPM 差 1 外，任何测试期望或断言不得改。失败先修再上 Vivado。
3. tiny RTL 生成：`GenerateBreezeCluster single gshare linux`。
4. **Cluster OOC**（Alan，100 MHz、AreaOptimized_high、maxThreads4、`timeout 1h`）：
   - worst-20 **按终点寄存器分组去重**（去掉位下标），每组取最差一条，列 20 组；
   - 重跑 SOC-3b 的允许路径查询与 TLB→S1 worst-20；
   - 新增结构查询：S2（`s2`/`cpu2`/`internal2` 寄存器）→ CVFPU 内部寄存器、→ 前端寄存器、→ HPM 计数寄存器。预期仅剩慢路 redirect 寄存器 D 端与 skid 清空 D 端；其余存在即报告。
   - 超过 1 小时停，记录阶段后报告，不自行重试。
5. Cluster WNS ≥ 0 → 整 SoC tiny 布局布线（保留 timeout），WNS ≥ 0 → SOC-3 §3.2 验收段。Cluster WNS < 0 → 按 SOC-3 M3 范围继续修、重跑 4；需要改拍数或协议的，停下报告。

## 5. 报告

写入 `docs/tasks/SOC-3c-s2-fanout-report.md`：§0 的 SOC-3b 提交 SHA；C1–C4 各自的实现位置与改动；C2(b) 间隙取指/flush/SFENCE 的核实结论；S13 与 HPM 测试的改动逐条；门槛表（SHA、主机、cwd、命令、通过数/总数、exit、用时）；Cluster OOC 阶段时间、WNS/TNS/失败端点、分组 worst-20（起点、终点、slack、级数、logic/route、逐段拆分）、结构查询结果、与 SOC-3b `21e3b18` 的对比；有 routed 结果就报告 routed。

## 6. 补充裁定（2026-10-08，回应报告 §1 D1–D4）

四项均批准，其中 D2、D3 有修正。以下文字优先于报告 §1 的建议文本。

### D1 S09：批准

- S09 改为：WB 保持期间，`io.frontendRedirect` 中**来自 WB 的改向**（慢路）、退休、CSR commit/trap/xRET、预测训练的**产生**仍禁止；**允许** EX 分支快路纠错，每个 EX 占用期最多一次。授权同步 `backend-rtl-spec.md` S09/A08、`backend-v1-rtl-spec.md` 对应条目与 `frozen.json`。
- 断言改写为：(1) 慢路 redirect 不在 `downHold && wb.valid` 拍发出；(2) 新增 `!(branchRedirect && exRedirectSent)`；(3) 训练按 D3 的边界检查。其余 S09 断言（SFENCE 一次性、held WB 不退休）不动。
- 实现注意（静态已发现，必须处理并加定向用例）：
  - `BreezeBackend.scala:435` 现在 `branchRedirect` 时清 `ex.valid`——旧语义里 redirect 蕴含 exAdvance，清的是将进 EX 的气泡；快路下 EX 被保持时这会**杀掉分支本身**。改为：快路 redirect 只清 ID/skid 中的错路项，EX 保持该分支；EX 之后推进时装入的是纠错后取到的指令，**不能再被清一次**。
  - `:399` JALR 的 BTB valid 和 `:515` `mem.predictionMiss` 改用「本条已发 redirect」（`exRedirectSent || branchRedirect`）。
  - 定向用例至少一条：MEM 中 load miss 保持 EX，EX 中分支误预测 → 快路发出 → 保持期间正确路径指令进 skid → 释放后正确路径指令按序执行、错路指令从未进 EX、只发一次 redirect。再加一条：快路已发后 WB 陷入，慢路覆盖。

### D2 FpUnit S05：批准，属于 C1 范围

- `canAllocate` 去掉 `!io.killUncommitted`；S05 第一条改写为：fire 与 kill 同拍时，新项入表即无效，无 RF/flags/commit 副作用，`killDrain` 期间该 tag 不得复用。`[S05/S08] speculative FP write` 及其余断言不动。
- 实现注意：现有 kill 循环按 `entries(i).valid` 的**当前寄存值**作废，同拍 fire 的那项此时 valid 还是 0，不会被作废，`entries(allocate).valid := true` 会存活；`commitCursor := allocate` 也会指向这个死项。必须显式处理（fire&&kill 时不置 valid，指针跳过或等效），并核实 CVFPU 0 级流水 opgroup（若配置中存在）同拍返回时不会留下永不归还的表项。加定向用例：FP 请求与 WB kill 同拍 fire，之后连续发新 FP 请求，验证无死锁、无错误写回、无 tag 混淆。

### D3 训练：批准「决策寄存、下一拍消费」，**不批准消费拍再做年龄/取消检查**

- 训练决策仍在 EX 真推进拍：`train = exLegal && exAdvance && !wbKill && …`，结果只进训练包寄存器的 D 端。
- 下一拍把 BTB/PHT/GHR 训练包**无条件**送前端，valid 只允许再与寄存的 `hartFatal` 相与。不得再与当拍 `wbKill`/`downHold`/`exAdvance` 相与——那就是把 S2 路径接回前端，等于没切。
- 理由：旧 PHT/GHR 在决策拍当拍消费，决策拍之后本来就不会被取消；「年轻项取消规则」= 决策拍的 `!wbKill`，已在 D 端保留。BTB 现有的消费拍 `!wbKill && !downHold` 门控和 `!downHold || wbKill` 清除随之删除，三者统一为同一个寄存训练包。BTB 因此在「推进到 MEM 后被更老的 WB 陷入杀掉」的极少情形下仍会训练——与 PHT/GHR 现状一致，只影响预测质量，不影响正确性，接受。
- GHR/PHT 晚一拍可能让紧跟的预测用旧历史，属性能变化，不要求前端旁路。
- S09 训练检查的边界改为：保持期间不**产生**训练决策；前端消费已寄存的训练包不受限。若有已有测试依赖消费拍取消语义而失败，停下报告该测试，不要自行改。

### D4 HPM：批准

- 语义按报告：事件按发生拍的 selector/inhibit 归属（需寄存一份延迟一拍的 selector/inhibit，或等效），CSR 可见增量晚一拍；软件写 counter 当拍优先，丢弃写入前尚未可见及写入同拍的事件，其后事件照常计入；`mcycle`/`minstret` 不变。注：这与原未流水计数器的覆盖结果一致（写前一拍的事件本就会被覆盖掉），所以覆盖语义实际不变，只有可见时间晚一拍。
- 测试许可由「差 1」改为「期望值按一拍延迟平移，数值差等于被延迟那一拍的真实增量」（HPM13 单拍可为 2）。授权修改 `HpmSpec`、`BreezeCsrPipelineSpec` 及实际受影响的 HPM 用例和其独立参考模型；限制：参考模型不得从 RTL 输出导出；不删 expect、不减检查点；非法 selector、inhibit、软件覆盖、复位、随机刺激检查全部保留。报告逐条列出每个改动的 expect（文件:行、旧值→新值、对应的延迟推导）。
- 授权同步 `backend-rtl-spec.md` HPM 语义描述并重登记 `frozen.json`，不新增冻结文件。

### 其余

报告 §2 所列细节由 codex 自定，同意。C2(b) 的间隙取指/FENCE.I/SFENCE 必须有动态定向证据，静态审查不算通过。D1–D4 外仍按 §2/§4 的停止规则执行。

## 7. 流程压缩（2026-10-08，用户要求尽快出 100 MHz 可上板镜像；100 MHz 锁死，不降频）

覆盖 §2「顺序」与 §4 第 4–5 步、SOC-3 §3.2 的对应部分；其余门槛与停止规则不变。

1. **C1–C4 一轮做完再上 Vivado**，不再分两轮。若已按两轮在做，C3 接着并入本轮，不必为第一轮单独跑 OOC。
2. **Alan 上 Cluster OOC 与整 SoC tiny 生产版并行启动**（各自独立工作区与证据目录，各自 `timeout 1h`）。整 SoC routed WNS ≥ 0 即视为时序门槛通过，Cluster OOC 结果仍照 §4 报告；OOC 正值而 SoC 负值，按 M3 继续。
3. **上板前必须通过**：§4 第 2 步全部定向测试（含 D1/D2 新增用例、C2(b) 间隙取指/FENCE.I/SFENCE 动态用例）、`L1DL2MultiCoreSpec`、`L1DL2LitmusSpec`、`L1DL2MultiCoreFaultSpec`、`ClusterIsaSpec`、tiny routed WNS ≥ 0 与 bitstream。
4. **不挡上板、在 cloud_chen 上与上板并行**：`sbt test` 全量、`pytest sim/litex`、single SoC smoke。它们发现失败需改 RTL 的，修完重跑 tiny Vivado 确认 WNS ≥ 0。
5. **推迟到上板之后**：`breeze-tiny --debug`、small 冒烟、small 生产版。
6. 时序达标后，worst-20 分组和结构查询只需保存原始报告，不必在出镜像前写完分析。

bitstream 出来后在报告里写明路径、SHA、routed WNS/WHS，立即告知用户。

## 8. 补充裁定（2026-10-08，回应报告 §1 训练测试停止）

两条失败是 D3 已批准语义的**预期结果**，不是 RTL 逻辑错误：S09 原期望 BTB=8 编码的正是 D3 删除的消费拍 `!downHold` 门控；S13 原期望 BTB 为空编码的正是 D3 明文接受的「推进到 MEM 后被更老 WB 陷入杀掉仍训练」。授权同步，之后同类训练消费拍差异不再停止。

- `S09_BTB_training_waits_for_held_WB_once`（可改名为 `S09_training_decided_outside_held_WB_once`）：
  - BTB、PHT、GHR 各恰好一次，且三者消费拍相同（同一寄存训练包，期望拍 3）；训练内容（BTB target、PHT idx/taken、GHR taken）照常检查。
  - `m.all("btb").intersect(m.all("hold")) mustBe empty` 改为**决策拍**检查：`m.all("btb").map(_-1).intersect(m.all("hold")) mustBe empty`（PHT/GHR 同理）。RTL 中 `train` 与 held WB 互斥的断言保留不动。
- `S13_WB_fault_discards_younger_pending_BTB_training`（改名为 `S13_training_decided_before_older_WB_fault_still_consumed`）：
  - BTB/PHT/GHR 各恰好一次于拍 3；`redirect.last mustBe 8`、`gprBusy=0` 保留；补充：分支不退休、x0 以外无该分支的架构写回。
  - 另加一条反向用例：WB kill **与 EX 推进同拍**时不产生训练（覆盖决策拍 `!wbKill`）。
- 两条在报告中按 §4 HPM 表格式逐条列出旧→新及推导。

继续执行：补跑 BackendSoc3cSpec（D1）、FpUnitSpec（D2）——上次是依赖缺失 abort；`RegFileCsrFileSpec` 改为实际 suite `flow.core.CSRFileSpec`；然后按 §4/§7 剩余门槛推进到 Alan 并行 OOC + SoC tiny。

## 9. 用户执行范围更新（2026-10-08）

目标扩展为单核与四核在 KCU105 上达到 100 MHz、生成 bitstream 并推进上板。允许根据实际板载资源缩减容量/深度等设计参数；需要分别记录配置、功能证据和 routed setup/hold 时序，不能用单核结果代替四核结果。

已定微架构不变。因规格或测试没有同步既定裁定而产生的冲突，codex 可自行同步并记录独立推导与旧→新检查；不再因同类文档滞后重复请求授权。真实功能失败仍须定位根因，不能削弱 golden、断言或覆盖。涉及改变已定微架构的冲突仍需报告具体决策。100 MHz 不降频，不添加时序例外以掩盖失败。

本节覆盖早期仅 tiny、推迟四核以及“不烧板”的范围限制；其它冻结行为与验证证据要求保持。

## 10. 用户批准测试与物理构建并行（2026-10-08）

用户明确批准：“一边跑综合一边跑测试，测试挂了就 kill 掉，没挂还能省时间”。覆盖此前功能门槛必须全部结束才允许生成 RTL/启动 Vivado 的流程顺序。以同一准确 SHA 和配置试探性并行运行，已有失败需先修；运行中发现新的测试失败信号或非零退出时，终止本轮相应 Vivado 进程，保留证据并定位修复，不能把未验证结果作为镜像验收通过。

Vivado 综合/实现进度每 20 分钟轮询一次；测试失败守卫可以更快检查，以及时取消本任务独立进程。保持 100 MHz、既定微架构、测试/golden/assertion 和原物理策略；单核与四核分别记录实际结果。推进至符合门槛的 bitstream 后通知用户并等待用户处理上板，不在测试结束时停下来。用户当前未连接 FPGA，镜像构建不受此条件阻止。
