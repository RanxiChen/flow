# SOC-3b：WB 写口后移审查报告

状态：**最小功能验证 16/16 通过，RTL 生成成功；Cluster OOC 完成但 100 MHz 时序未收敛**（2026-10-08，见 §9）；§8 是上一轮未编译的 RTL 初稿记录。最新规则见 backend-timing-contract.md、backend-v1-rtl-spec.md 和 SOC-3b 任务。用户本轮明确要求从现有测试选最小子集，只补关键缺口，广泛程序及组合场景留到 FPGA；不运行原任务的完整定向回归。以下 §1–6 保留首次审查的历史快照（对应源 5e8f105），其中「阻塞」「需要裁定」「未修改冻结文件」描述当时状态，已由 §7 的用户裁定及当前规格覆盖；静态推导不等于动态验证。

审查源：`feat/pcie-fase-20260920`，HEAD **`5e8f10506f388b7640c1807db8b34ca40e7b4fd5`**，cwd `/home/chen/leisure/flow`。已有无关工作区改动保留。独立证据目录：[`../../records/soc3b-5e8f105/static/`](../../records/soc3b-5e8f105/static/)。

## 1. 阻塞：late 接收与物理写回不能一起整体平移

依据冻结 [`backend-timing-contract.md`](../backend-timing-contract.md) §4：

- WB 提交拍不变，普通物理写在下一拍 W2。
- `l1d.late.fire` 当拍进入单项 lateReg；基线 `late.ready = !lateReg.valid`。
- 仲裁从 lateReg 取结果，普通 W2 写优先；不从当拍 L1D late 直接写 RF。
- ※b 又要求 T12/T21/T22 仅允许原普通写与 late 相关拍号整体 +1。

因此必须区分 **L1D→lateReg 接收** 与 **lateReg→RF 写回**。对于首次迟到结果，lateReg 起初为空，首次 `late.valid` 当拍已经 `ready=1`，无论 WB/W2 是否占 GPR 口。当拍 fire 不能为了满足旧冲突测试推迟到 RF 写回拍。

以下保持原刺激的 WB 提交拍、late 首个 valid 拍及指令间隔，不人为移动刺激。T12/T22 中此前是 nop，故 N 拍无先前普通 W2 GPR 写。无错误、无其它后台结果。

| 行 | 新结构必然推导 | 与原值整体 +1 的差异 |
| --- | --- | --- |
| T12 | 普通指令 N 提交、N+1 写 RF；late N fire，N+1 在 lateReg 等 W2，N+2 写 RF；冲突仅 N+1，计 1 | 原 `late.fire=N+1` 若 +1 应为 N+2，实际接收为 N；只有 late 的物理写回变为 N+2 |
| T21 | 首个 late.valid 为 c，late c fire；lateReg 从 c+1 参与仲裁；ID 保护气泡 c+4；late 物理写回 c+8；冲突 c+1…c+7，计 **7** | 原 fire c+6 若 +1 应为 c+7，实际接收为 c；即使把事件改成物理写回，也为 c+8；计数 6→7 不是平移 |
| T22 | B N 提交、N+1 写 RF；C N+1 响应并提交、N+2 写 RF；A 的 late N fire、N+3 写 RF；冲突 N+1、N+2，计 2；流水不保持 | 原 `late.fire=N+2` 若 +1 应为 N+3，实际接收为 N；N+3 是物理写回而非 fire |

### T21 逐拍推导

保持现有每堆饱和计数阈值 3、计数寄存、仅从 ID 注入一拍保护气泡、在途标记阻止重复保护。这里普通独立 ALU 流已稳定，c 以前也有普通写；c 起持续供给，除唯一保护气泡外没有其它停顿。

| 拍 | late/lateReg | W2 与保护气泡位置 | 拍初饥饿计数 | 本拍冲突 |
| --- | --- | --- | ---: | ---: |
| c | lateReg 空，L1D late fire，拍末捕获 | 普通流 | 0 | 0 |
| c+1 | lateReg valid | 普通 W2 写，lateReg 落败 | 0 | 1 |
| c+2 | lateReg valid | 普通 W2 写，lateReg 落败 | 1 | 1 |
| c+3 | lateReg valid | 普通 W2 写，lateReg 落败 | 2 | 1 |
| c+4 | lateReg valid | 普通 W2 写；ID 注入唯一气泡 | 3 | 1 |
| c+5 | lateReg valid | 普通 W2 写；气泡到 EX | 3 | 1 |
| c+6 | lateReg valid | 普通 W2 写；气泡到 MEM | 3 | 1 |
| c+7 | lateReg valid | 普通 W2 写；气泡到 WB，故本拍普通提交空缺 | 3 | 1 |
| c+8 | lateReg valid，获准写 RF、清记分板 | 气泡到 W2，普通写口空闲 | 3 | 0 |

计数/在途保护实现依据：[`Writeback.scala`](../../design/src/main/scala/backend/Writeback.scala) 第 51–68 行。ID→EX→MEM→WB 每拍推进依据：[`BreezeBackend.scala`](../../design/src/main/scala/backend/BreezeBackend.scala) 第 447–450、486–490、528 行；冻结 W2 在 WB 后一拍。因此相比旧结构，等待的起点因 lateReg 多一拍，ID 气泡释放写口又因 W2 多一拍。不能只用「结果整体后移一拍」代替这个反馈链的推导。

此处没有通过修改阈值、提前读取当拍 L1D valid、改变刺激或额外停流水来凑计数 6；这些都需要额外裁定。

## 2. 阻塞：P06 提交空拍与后台物理写回不再同拍

冻结 P06 要求 20 条 ALU 的 **commit 空拍集合**等于窗口内 x1/x2 后台 **gprWrite 拍集合**。现有测试 [`BackendContractSpec.scala`](../../design/src/test/scala/backend/BackendContractSpec.scala) 第 370–376 行直接断言这个等式。

W2 后，ID 在拍 b 插入保护气泡：EX 空拍 b+1，MEM 空拍 b+2，WB/commit 空拍 b+3，W2 普通写空拍 b+4。后台 GPR 在 b+4 获写口，而 ALU commit 空拍是 b+3。例如上面的 T21 中分别是 c+7 与 c+8。P06 只要保护事件位于 ALU 窗口内部，就存在相同的一拍错位；保持 WB 提交拍、单个 ID 保护气泡和 W2 普通写优先不能保留原等式。

任务 §3.2 只允许修改 T02/T11 和 ※b 三行的许可推导值，**P06 不在许可范围内**。建议裁定 P06 的空拍比较是否应改为 `gprWrite - 1`；未改测试或合同。

## 3. 阻塞：结构门槛字面范围包括必须保留的事件路径

冻结合同 §4 禁止从 L1D S2/cpu2/internal2 到任何「记分板忙位」或「HPM 事件寄存器」的组合路径，只明确允许到流水保持使能的 s2Hold 路径。但当前语义还包括：

1. **T10 的同拍 miss 提交置忙**：L1D S2 的 `resp.kind=Mshr` → `wbMiss` → `wbLong` → `scoreboard.set` → gprBusy/fprBusy 的 D。依据：BreezeBackend 第 154–156、172、195 行；[`Scoreboard.scala`](../../design/src/main/scala/backend/Scoreboard.scala) 第 48–55 行。T10 仍明确要求 E+2 提交并于周期末置忙；W2/lateReg 只切写回仲裁，不能直接消除此置位依赖。
2. **原有内存停顿计数**：S2 判定 → `s2Hold` → `backendEvents.memStallCycle` → HPM `pending` 的 D。依据：BreezeBackend 第 550、555 行；[`BreezePerformanceCounters.scala`](../../design/src/main/scala/core/BreezePerformanceCounters.scala) 第 62、70–72、82 行。HPM 选择寄存器可选 MEM_STALL_CYCLE，因此不能因为 s2Hold→流水 CE 被豁免，就假定 s2Hold→HPM D 也被豁免。

这是源码/语义审查，尚无 SOC-3b 综合网表查询结果。按门槛字面全排除会影响 T10 或计数器逐拍语义，超出本轮冻结许可。建议把门槛限定为待切断的**写回授权/clear/发射反馈链**，明确 WB 提交置忙、原有 S2 事件采样及 hold 路径的处理；是否列为例外需裁定，未擅自放宽查询。

## 4. 实现与串行条件状态

尚未写 W2/lateReg 或 ID 冒险逻辑，`loadUseBypass` 参数及 T02b 测试也未删除。下列是静态定位的后续核查点，**不是已完成修改**：

| 条件 | 当前位置 | 后续需核对 |
| --- | --- | --- |
| ID 普通 RAW/CSR 源寄存器冒险 | BreezeBackend 第 213–225、434–445 行 | WB 等待 W2 的普通目的寄存器、W2 写穿透与 EX 旁路 |
| CSR 等空 | Scoreboard 第 71–78 行 | W2/lateReg 在途写及原 FP flags-only 项；保持 T17 的 raw busy 规则 |
| ESTOP | BreezeBackend 第 159–161、543–544 行 | 在停止前完成已提交 W2/lateReg 写 |
| FENCE.I | BreezeBackend 第 159、167 行 | L1D drained 与后端待写状态同时满足 |
| SFENCE.VMA | BreezeBackend 第 159–160、393 行 | 发 sfence、后续 redirect 两阶段对 W2/lateReg 的要求 |
| 陷入/中断 | BreezeBackend 第 165–170、383–391 行 | 已提交 W2/lateReg 不被年轻指令 kill；保持 P09 不等已提交 DIV 的规则 |
| FASE 接管 | BreezeBackend 第 57 行；BreezeCluster 第 23 行 | 当前 v1 顶层 `require(!useFASE)`，FASE 集成尚被源代码禁止，不能声称其 drained 修改已验证 |

## 5. 门槛与证据

| 门槛 | 源 SHA | 主机 / cwd | 命令 | 通过 / 总数 | exit | 用时 | 证据 / 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 冻结文件散列 | 5e8f10506f388b7640c1807db8b34ca40e7b4fd5 | 本地 chen / `/home/chen/leisure/flow` | `python3 tools/frozen_check.py` | 8/8 文件 | 0 | 0.037 s | `records/soc3b-5e8f105/static/frozen-check.log`、`.exit`、`manifest.json` |
| 后端全部合同、MDU 四 spec、L1D 三 spec、BreezePrivilege、ClusterIsaSpec | 同上 | 未执行 | 未执行 | — | — | — | 合同冲突，未开始硬件执行 |
| tiny RTL 生成 | 同上 | 未执行 | `sbt "runMain flow.top.GenerateBreezeCluster single gshare linux"`（计划命令） | — | — | — | 未执行 |
| Cluster OOC、结构查询、TLB→S1 permission worst-20 | 同上 | Alan 未执行 | 未执行 | — | — | — | 未执行；无阶段时间、WNS/TNS、失败端点或 worst-20 |
| tiny 布局布线与 SOC-3 §3.2 验收 | 同上 | 未执行 | 未执行 | — | — | — | 上游门槛未完成 |

冻结检查只是本地静态散列检查，不是硬件测试。已读取当前 `docs/cross-project/simulation-host.md`；本轮未发起仿真、编译、RTL 生成或 Vivado，故没有选择执行主机或进行 SSH 工具/资源预检，也没有主机不可用结论。后续开始硬件执行前仍须重新读取该文件并按 cloud_chen→Alan 顺序校验。

SOC-3 `1b17595` 的 Cluster post-synth WNS=-6.696 ns、失败端点 36577/97510、worst path 52 级是任务提供的历史基线；本轮没有新综合结果，不能报告相对改善或 routed 收敛。

## 6. 需要的裁定

建议保留已确定的 W2/单项 lateReg 结构，明确区分 `late.fire` 与物理写回，允许本报告 T12/T21/T22 的真实推导（特别是 T21 7 次冲突），修订 P06 的空拍关系，并明确结构查询对提交置忙与原有 S2 事件路径的范围。冻结合同与 `tools/frozen.json` 由有权限的裁定方更新后再继续实现。当前没有把这些建议写回冻结规格，也未以新期望运行测试。

## 7. 用户裁定落稿（2026-10-08）

- T12/T21/T22 的 late.fire 分别 N/c/N，迟到 RF 写回 N+2/c+8/N+3；冲突 1/7/2。T21 唯一 ID 气泡 c+4，阈值仍 3，整体 +1 限制撤销。
- T13 改为 lateReg/DIV/MUL/FPU 同拍 valid，returnDelay 26→25，物理期望不变；P06 ② 提交空拍={g−1}。所有无冲突前提按 W2 判断。
- W2 无条件完成、不置/清 busy、每拍消费一次；busy 由 lateReg/DIV/MUL/FPU 完成清除。保留 WB→EX，增加 W2→EX；WB 资格与 WB 访存冒险不看 wbCommit/wbDone/resp.kind。
- fatal 接受 N+1 生效，只由寄存器驱动；FENCE.I/SFENCE 不新增 drained 条件，T17/ESTOP/P09 保留；late.ready=!valid||grant，同拍旧项完成/新项捕获。
- 结构门槛修正为写口/结果 ready/实际 clear/RAW/WAW/EX 旁路反馈链；保持、精确取消、Mshr 提交置忙、W2/HPM 输入允许并报告。优化掉的中间网名可用 RTL fan-in 取证，不加 keep。Mshr→busy 进入 worst-20 且负 slack 时停止。
- 最新 spec 已同步；旧 T01/B01/集成备忘增加适用说明。冻结清单重登记已有修改项，不新增冻结文件数量。此次仅文档修订，原 §5 的 SHA/散列证据属于修订前快照，不用于证明当前内容；动态门槛全部未执行。

## 8. RTL 实现（2026-10-08，未编译/未仿真）

基线 HEAD：`5e8f10506f388b7640c1807db8b34ca40e7b4fd5`，分支 `feat/pcie-fase-20260920`，cwd `/home/chen/leisure/flow`。实现处于未提交工作区，**不能把基线 SHA 当作实现后的验证版本**；两份修改源码及当前冻结文档的 SHA256、RTL diff、静态检查命令/exit/用时保存在 [`../../records/soc3b-5e8f105/rtl-implementation-20261008/`](../../records/soc3b-5e8f105/rtl-implementation-20261008/)。原有文档、冻结清单和其它无关修改保留；本轮没有重新登记冻结散列。

### 8.1 实现映射

| 规则 | 实现位置与行为 |
| --- | --- |
| W2 | `design/src/main/scala/backend/Writeback.scala` 的 `w2`：仅保存 valid、bank/rd、data。输入仍是后端无异常 WB 普通提交；valid 每拍覆盖，WB 无新普通提交即清零，不跟随 WB hold。写口和导出的 `io.w2` 只取寄存值。x0 不捕获有效写，f0 可写；物理写不受 hartFatal/stop/kill/redirect/hold 门控，不重复 retire，不输出后台 clear。 |
| lateReg | 同文件 `lateReg` 保存单项 valid、bank/rd、data、error；`late.ready=!lateReg.valid||lateRegGrant`。grant/clear/写数据/错误事件始终取旧项；新 late.fire 只更新拍末寄存器，不穿透 RF。grant 与 fire 同拍可替换槽项。 |
| 后台仲裁 | `valids` 第一项改为 lateReg，W2 同 bank 普通写优先；全局至多一个后台 grant，lateReg>DIV>MUL>FPU。错误 lateReg 不占物理写口，但完成清 busy、输出 lateWriteError。 |
| 冲突与饥饿 | 两 bank 饱和计数、阈值 3、在途保护标记和 ID 单拍气泡机制保持。waiting/granted 只统计 lateReg/DIV/MUL/FPU；原始 late 接收/反压不直接计数。 |
| WB 访存 RAW/WAW | `BreezeBackend.scala` 的 `scoreboard.io.pipe(2)` 改为仅由 WB 寄存 valid/类别/目的产生资格，取消 `!wbDone`；Done/Mshr/Exc 均不能当拍解除目的保护。下一拍由 W2 写穿透或已提交 busy 接续。Store/Fence 等无目的项仍保留 CSR drain 资格，目的按旧规则设 x0。 |
| 普通 EX 旁路 | `exRead` 使用 MEM>WB>W2>捕获值；WB 仅开放非访存、非 MDU/FPU 普通结果，不再使用 wbOrdinary/wbCommit/wbDone 或响应数据。GPR 与 FPR 操作数均接入；EX 保持时捕获两个 GPR 和三个 FPR 的有效旁路值。未增加后台结果到 EX 的数据旁路。 |
| 普通 FPR/CSR 源依赖 | `ordinaryHazard` 增加寄存 WB 元数据检查，保护尚未物理写入的普通 FPR/CSR 结果；ID 不单独比较 W2，W2 拍从现有 RF 写穿透捕获新值。 |
| loadUseBypass | 保留配置参数兼容，但删除 MEM Load RAW 豁免、S2 命中旁路及 miss-dependent deferred EX 执行状态；参数 true/false 都采用 T02 的三拍 load-use。T02b 测试源本轮未删除，下一轮按裁定处理。 |
| fatal | Writeback 的 hartFatal 仅取 sticky fatal 或 `lateReg.valid&&error`；错误 N 接收、N+1 生效。后端阻止新发射/普通提交，清未提交流水 valid，并向 L1D/各单元发原有取消信号。已提交 W2/后台结果继续完成。 |
| trace / observe | 普通架构结果仍在 WB trace，物理写仍由 observe.gprWrite/fprWrite 测量；普通 W2 不产生 pending completion。trace.lateWriteError 改接 lateReg 旧项实际完成事件。 |
| 断言 | 保留原 S01–S16 安全含义，增加 lateReg 保持、W2 无条件完成和 fatal 禁止新发射/普通退休检查；MEM/WB 保持断言的取消条件加入 fatal，以对应明确的未提交项取消规则。断言尚未运行。 |

没有修改 L1D、MulUnit、DIV、CVFPU、Scoreboard 或 RF 本体；没有一起做可选 L1D 控制并行化/记分板局部读口优化，没有改综合策略、约束或频率。

### 8.2 拍数静态推导

| 行 | 按当前实现的推导（非测试结果） |
| --- | --- |
| T02 / T11 | T02：Done/commit=E+2，W2 写与 ID 离开=E+3，依赖 EX=E+4。T11：late.fire=R+7，lateReg 从 R+8 仲裁，空口写 RF/清 busy/ID 离开=R+8。 |
| T12 | N 普通提交并接收 late；N+1 W2 普通写占口、lateReg 冲突一次；N+2 lateReg 写回。commit/resp 不后移。 |
| T21 | c 接收；c+1…c+3 冲突并把计数升至 3；c+4 ID 插唯一保护气泡；气泡经过 EX/MEM/WB/W2，于 c+8 让口。c+1…c+7 共七次冲突，后台写 c+8。 |
| T22 | B 在 N 提交、N+1 W2 写；C 在 N+1 Done/提交、N+2 W2 写；A 在 N 接收、N+3 后台写。仅 N+1/N+2 两次冲突，不保持流水。 |
| T13 | L1D 在 N−1 接收，N 拍 lateReg 已 valid；若 N…N+3 W2 无同 bank 普通写，则依次 lateReg/DIV/MUL/FPU 写回。下一轮需按裁定把模型 returnDelay 从 26 调到 25，本轮不改刺激。 |
| P06 | ID 在 b 插气泡，则 WB 提交空拍 b+3、W2 让口及后台 GPR 写 g=b+4；窗口内提交空拍集合应为 `{g−1}`。FPR 后台写不占 ALU 的 GPR 口，原重叠及一次完成规则保留。 |

### 8.3 串行条件与结构范围

- FENCE.I 仍仅等 `l1d.drained`，满足当拍提交、清 I-cache/redirect；不增加 W2/lateReg 等待。
- SFENCE 仍在 `l1d.drained&&mmu.idle` 首拍发请求，并在其后的首个 mmu.idle 拍提交/redirect；不增加 W2/lateReg 等待。
- T17 CSR 等空仍使用 Scoreboard 原始 busy，加原有未提交生产者/flags-only 条件，不能用 effectiveBusy 提前放行。
- ESTOP 仍等两 bank busy 和 committedFlagsOnly；lateReg 的有效目的尚在 busy 中，W2 已提交且无条件写。
- 中断与 WFI 不新增后台 busy 等待；kill/redirect 不取消 W2/lateReg。`require(!useFASE)` 保留。

源码 fan-in 复核：写口/grant/结果 ready/实际 clear 的仲裁输入为 W2、lateReg 和原 MDU/FPU 结果，原始 S2 响应只进入 WB 提交、busy set、W2 D 和原 HPM 事件路径。ID 的访存保护及 EX 旁路选择使用寄存元数据，未再使用 wbDone/resp.kind/wbCommit；最终推进仍受 s2Hold 与精确取消门控。这只描述直接源码依赖，**尚未完成综合网表结构门槛取证**；允许路径的级数/slack、Mshr→busy 是否进入 worst-20、TLB→S1 permission 均未测量。

### 8.4 本轮检查与未执行门槛

| 项目 | 版本 / 执行位置 | 命令 / 结果 |
| --- | --- | --- |
| 冻结文档散列 | 当前工作区；本地主机，cwd 如上 | `python3 tools/frozen_check.py`：OK (8 files)，exit 0；具体用时见 manifest。仅散列检查。 |
| RTL diff 格式 | 当前工作区；本地主机 | `git diff --check -- design/src/main/scala/backend/BreezeBackend.scala design/src/main/scala/backend/Writeback.scala`：exit 0；具体用时见 manifest。不是 Scala 编译。 |
| 编译 / 定向仿真 / 合同及回归 | 未执行，未选择仿真主机 | 用户指定下一轮；现有测试未更新，不能报告通过数。 |
| tiny RTL 生成 / Cluster OOC / SoC 布局布线 | 未执行 | 无生成 RTL、WNS/TNS、失败端点、worst-20 或 routed 结果，不能报告相对 SOC-3 的时序改善。 |

没有启动硬件执行，故没有 SSH 主机预检或主机不可用结论；下一轮开始编译/仿真/RTL 生成前必须重新读取共享 simulation-host.md 并按 cloud_chen→Alan 校验，Vivado 使用 Alan。当前仅能标记「按规格实现的未验证 RTL 初稿」，不能关闭 SOC-3b 动态/时序门槛。

## 9. 最小上板前验证（2026-10-08，本轮）

用户本轮明确收窄原任务 §3 第 2 项：优先推进 FPGA，只选现有测试的最小关键子集，必要时补缺口；广泛程序、组合场景、长时间运行留到 FPGA。冻结架构、逐拍拍号、断言和 golden 不变；完整后端/MDU/L1D/Privilege/ISA 回归未执行，不以本轮子集冒充完整门槛通过。上板前的综合/实现时序及资源门槛仍须满足。

### 9.1 版本与测试修改

实际测试源为独立 Git 快照 **`21e3b187f0eea5d4739929a92b2f71e46761f719`**，tree `8d15ab15ee1ef6cf746c5945628c41a77b7c378b`；基线仍是 `5e8f10506f388b7640c1807db8b34ca40e7b4fd5`。本地工作分支/索引/未提交改动保留，快照经 Git bundle 传至独立远端工作区；不能把本地主分支 HEAD 当作本次实现的验证 SHA。执行 cwd=`/home/cloud_chen/work/flow-soc3b-21e3b18/design`，evidence=`/home/cloud_chen/evidence/soc3b-21e3b18`；本地镜像 [`../../records/soc3b-21e3b18/minimal-20261008/`](../../records/soc3b-21e3b18/minimal-20261008/)。CVFPU=1b220f3；common_cells=6aeee85；fpu_div_sqrt_mvp=86e1f55。工具：SBT 实际启动版本 1.9.7、Java 11.0.32.1、Verilator 5.028。

本轮没有修改生产 RTL。`BackendContractSpec` 只按已裁定值更新 T02/T11/T12/T13/T21/T22/P06；移除已作废 T02b，将原 optional-miss 场景改名为无功能兼容参数测试并保留数据检查。除所选子集以外，其它合同场景本轮未运行。

最小补充：更新 `WritebackSpec` 的两个原有用例以区分捕获和物理写，增加一个 lateReg 满槽反压/旧项出新项入用例；加强原 fatal 场景，精确检查 N 接收、N+1 fatal、N 普通提交/N+1 W2 写、年轻项取消、已提交 DIV 保留。增加一个 held EX 用例，在同一次 elaboration 内复位运行三个指令间隔，检查等待 DIV 时 MEM/WB/W2 旁路捕获及较年轻普通生产者覆盖较老值。集群 smoke 只调用原有三个自检程序，保留原 runner 的通过判据、超时和 watchdog，并检查 MMIO 控制台完整文本。

### 9.2 最小集合及已取得结果

| 层次 / 覆盖目的 | 所选场景 | 实际结果 |
| --- | --- | --- |
| 写回单元：W2 延后一拍/每拍消费、不清 busy，仲裁、双 bank、x0/f0、错误 N+1、lateReg 无穿透/反压/同拍替换 | `WritebackSpec` 三项 | 3/3 PASS；编译加用例 wrapper 41.423 s，exit 0 |
| 普通及访存依赖 | T01、T02、T11 | 已包含于后端 12/12 PASS |
| 并发来源/普通占口/饥饿保护 | T13、T21、T22、P06 | 已包含于后端 12/12 PASS |
| 跨堆与恢复边界 | S01_S08_f0_and_cross_bank_RAW_WAW、S05_S08_fatal、P10、T20 | 已包含于后端 12/12 PASS |
| held EX 捕获与最新值 | SOC3b_held_EX_captures_MEM_WB_W2_and_youngest_value（3 子场景） | 已包含于后端 12/12 PASS |
| 真集群短程序 | lrsc_amo_single、trap_misc、mmio_console（1 ScalaTest 用例，3 程序） | 1/1 用例、3/3 程序 PASS；wrapper 45.427 s，exit 0 |
| tiny production RTL | `GenerateBreezeCluster single gshare linux` | wrapper 6.575 s，exit 0；113 源文件 / 1 include 目录已封存 |
| Cluster OOC / 整 SoC 实现 | 100 MHz、原策略/约束；本次实际 timeout 30m，后续 1h | OOC exit 0、697.756 s；WNS −4.244 ns，未进入整 SoC 实现 |

后端命令的完整 12 个精确 `-t` 选择保存在 `cloud/backend/command.txt` 和 `result.json`；wrapper 254.209 s，exit 0，XML 12 tests、0 failures/errors/skips。组件阶段执行 `sbt build "testOnly flow.backend.WritebackSpec"`，生产 98 个 Scala 源和测试 76 个 Scala 源均编译成功；只是编译了其余测试源，没有运行其余测试。

每次硬件执行前重新读取共享主机配置，cloud_chen 的 SSH/环境/工具/资源预检通过。起初云盘空余 7.9 GB，后续复核已为 59 GB；本轮没有清理其它任务文件。Alan 已读检查剩余约 100 GB、Vivado 2022.2，可用作后续物理门槛。各阶段保留 SHA/cwd/command/exit/用时/log/XML；只声明实际完成层次。

### 9.3 RTL 生成与 OOC 输入

最小功能门槛共 16/16 ScalaTest 用例通过（3+12+1，集群用例包含三个程序）；独立读取 XML 确认 0 failures/errors/skips，各阶段 SHA 相同，汇总见 `minimal-gate-summary.json`。三个程序分别 cycles/retired：`lrsc_amo_single` 14389/1127、`trap_misc` 25511/2092、`mmio_console` 43372/4258；不是完整 ISA/程序回归。RTL 生成参数 single/gshare/linux/production/cpu、100 MHz OOC、无 debug。生成源包 SHA256 **`fc41cd4638c6c70db0af11308df425daf7dec1ffee9f2e9e76b29c6a47df8608`**，经本地核验再传 Alan；全部源/include 文件逐个校验，不用旧版已生成 RTL。

OOC cwd/evidence=`/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc`，Vivado 2022.2，xcku040-ffva1156-2-e，`AreaOptimized_high`、maxThreads4、clock 10.000 ns、`timeout --signal=TERM --kill-after=15s 30m`，无 retiming/false path/multicycle/keep/策略改动。Tcl 从本轮 filelist 生成确定顺序，保留原 TLB→S1 permission worst-20；增加写口、结果 ready、实际 clear、RAW/WAW 中间查询与允许终点查询。对象优化掉时记录为未映射并用 RTL fan-in 补充，不将零对象查询视作通过。综合网表结果与结构查询限制见 §9.4；本轮不能关闭时序门槛。

### 9.4 Cluster OOC 实测结果与当前边界

同源 `21e3b187f0eea5d4739929a92b2f71e46761f719`；Alan 正常完成，exit 0，无 timeout/ERROR，UTC 2026-10-08 10:18:07.236395 → 10:29:44.993229，wrapper **697.756 s（11 分 37.756 秒）**，`synth_design` 649.738 s。时序优化阶段约 **409 s**（累计 03:15 → 10:04）；所有阶段原文见 `alan/stage-times.txt`。OOC 覆盖 **BreezeCluster 集群顶层**的单核 tiny 参数 CPU/MMU/L1/L2 与内部接口；不是完整 KCU105 SoC，不含 LiteX/DDR/板级外设，且没有 opt/place/route。

| post-synth 指标 | SOC-3 历史 1b17595 | 本轮 21e3b18 |
| --- | ---: | ---: |
| WNS | −6.696 ns | **−4.244 ns**（改善 2.452 ns） |
| TNS | −91621.070 ns | −60742.875 ns |
| setup 失败 / 总端点 | 36577 / 97510 | 30021 / 97533 |
| WHS / hold 失败数 | +0.083 ns / 0 | +0.083 ns / 0 |
| 全局最差路径级数 | 52 | **42** |

两个结果均是同器件、100 MHz、AreaOptimized_high、maxThreads4 的 Cluster OOC post-synth 估算；不是 routed 对比。当前 worst-20 同属 `l1d/internal2_req_idx_reg[2]/C` → `frontend/realigner/faultVaddrReg_reg[*]/CE`，delay **14.162 ns = logic 2.367 ns + estimated route 11.795 ns（83.286%）**，slack −4.244 ns。路径经过 tags/victim/upgrade/allocWay → S2 决定/保持与精确取消 → 后端推进门控 → CVFPU 输入 valid/ready 链 → EX 推进/预测纠正 → 前端 redirect/fault 捕获使能。CVFPU **输入** ready 与写回结果 **出口** ready 属于不同路径；不能把这条剩余路径描述成 W2 仍直接受 S2 组合授权。具体实例/逐级 net/delay、全部 worst-20 见 `alan/worst-20.rpt`、`worst-20-paths.tsv`、`worst-20.md`。这是基于网名与源连接的诊断，尚未实施后续级内控制优化。

| 允许路径查询 / 复核 | 最差 slack | 逻辑级数 |
| --- | ---: | ---: |
| S2 → W2 寄存输入 | +2.085 ns | 23 |
| S2 → HPM pending 输入 | −3.644 ns | 43 |
| S2 → EX/MEM/WB valid 保持/取消 | −2.330 ns | 38 |
| S2 → Mshr 提交置 busy 端点 | +1.544 ns | 26 |
| S2 → EX 操作数寄存端点（包含合法 hold/advance/cancel 选择） | −2.821 ns | 38 |
| dTLB → S1 permission worst-20 | **+0.809 ns** | 30 |

Mshr→busy 不在全局 worst-20 且查询最差为正；TLB→S1 不是全局最差路径族。本轮未触发这两条专项停止条件。写口/结果出口 ready/actual clear/RAW/WAW 的层次中间 pin 在本综合网表均优化或改名，查询记录 **0 匹配对象**，不能把此结果当作「零路径证明」；采用本轮 same-SHA RTL 的 `rtl-fanin.md`、`generated-fanin/` 补充 W2/lateReg/来源结果寄存边界及 WB/EX 资格映射。EX 旁路资格仅用 MEM/WB/W2 寄存元数据；EX 数据端点的负 slack 还包含允许的最终推进/保持门控，不能按终点名字判作禁止的数据选择路径。所有允许类保留 report_timing 的真实报告。总体时序门槛仍未通过。

资源：LUT 48348、FF 41421、RAMB36E2 39、RAMB18E2 9、DSP48E2 23；shadow checker cell count=0。仅为 Cluster 综合利用率，不是整 SoC D5 gate 或布局容量/布线结果。OOC 外部仍有 285 input / 267 output 未设 IO delay，其内部寄存器时序已计时，不宣称板级接口验收。checkpoint 留在 Alan：`/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc/post-synth.dcp`，SHA256 **`33edf14231b7afb2cc44d1fbdc530f24016a752b7fbe425bfff91119760dded2`**；小型报告/日志/命令/退出码在本地 `alan/`，汇总 `diagnosis-summary.json`。

**用户超时更新**：用户要求在不打断现有 Vivado 的情况下把上限从 30 分钟延至 1 小时。检查时本次 Vivado 已自然完成，因此没有向进程发信号、没有暂停/重启。任务文件和后续独立 runner 改为 `timeout 1h`，本次实际 `command.json/result.json` 保留 `30m`，不改写历史。后续 runner 要求新目录并拒绝覆盖已有执行证据。

本轮已完成最小功能子集与生成验证；实际发现的下一项工作是上述 S2/推进/预测纠正控制链的时序优化。**尚未通过 Cluster WNS≥0，未启动整 SoC 布局布线，没有 bitstream、routed 或板上程序结果。** 没有为了快速上板放宽冻结拍数、golden、断言、约束或频率。
