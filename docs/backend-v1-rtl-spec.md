# Breeze 后端 v1 RTL 规范

状态：2026-10-08 用户已裁定 SOC-3b，现稿与 [`backend-timing-contract.md`](backend-timing-contract.md) §4、[`tasks/SOC-3b-wb-split.md`](tasks/SOC-3b-wb-split.md) 同步。B01/ND01/ND02 的未覆盖部分沿用；冲突处以本轮明确裁定为准。旧 V1-BE 实现/Alan 证据仅证明对应历史版本；本轮 W2/lateReg RTL 和验证尚未执行。

## 0. 权威、范围与拍定义

- 保持 ID/RR、EX、MEM、WB 四级顺序执行/提交；EX/MEM/WB 对齐 L1D S0/S1/S2。仅普通 GPR/FPR 物理写增加 WB 后的 W2，L1D 迟到结果增加单项 lateReg，不移动提交点。[SOC-3b§1]
- 本条指令 EX 拍为 E，无保持时 MEM=E+1、WB=E+2；所有事件按当前组合信号、周期末上升沿更新测量。[backend-timing-contract.md§0]
- T01 的 A01–A08、S01–S16、整数真实源表、MDU 提交/kill/保持全部沿用；T01 仅适用于旧阻塞访存/FPU 的条款由下述 v1 条款替代。[tasks/V1-BE-backend-spec-and-rtl.md§1–2]
- 不改前端、L1D/L2/MMU 对外事务协议；后端新增寄存边界仅 W2/单项 lateReg，不新增其它队列、结果缓存或特定地址/PC/指令序列行为。SOC-3d 第一批允许 §5 指定的 CVFPU 内部计算边界和可选 TLB 候选地址旁带，不改变后端级数或提交点。[SOC-3b§1][backend-timing-contract.md§3–4][tasks/SOC-3d-timing-batch-plan.md]

## 1. 寄存器与单元

| 状态 | 归属、更新规则和依据 |
| --- | --- |
| 整数 busy | x1–x31；实现用 32 bit 且 bit0 恒 0；仅 WB 已提交长延迟写置位，实际完成清位；kill 不写 busy。[backend-rtl-spec.md§2][自定] |
| 浮点 busy | f0–f31 全部有效；同一置位/清位生命周期。[backend-pipeline-design.md§5] |
| 来源 | busy 对应项记录 L1D/DIV/MUL/FPU 的 2 bit 来源，仅 busy 有效时读取。[backend-rtl-spec.md§2.1][自定] |
| EX/MEM/WB | valid、真正发射资格、类别、rd bank/index，保留普通数据、PC、异常、CSR、预测和 trace 侧带；未提交长延迟目的由级间检查覆盖。[backend-rtl-spec.md§1–3] |
| MUL | 使用 `MulUnit`，P1–P4 各 valid/committed/rd/op/product；P4 不离开则四级全部停止；不加输出级。[backend-rtl-spec.md§7] |
| DIV | 使用 `DivUnit`，保留 unsigned radix-4；occupied/committed/done/rd/result；释放后下一拍才 ready。[backend-rtl-spec.md§8] |
| FPU 表 | 32 项，tag 宽度 5；每项仅 valid、committed、rd、isFp，无 data/flags；循环 allocate/commitCursor 指针决定发射/提交顺序。[tasks/V1-BE-backend-spec-and-rtl.md§2.1][自定] |
| FPU killDrain | 一个控制位；WB kill 后暂停新 FP 接收直到 CVFPU busy=0，防止被作废但尚未返回的 tag 被复用。[V1-BE-B01-ruling§3/ND01] |
| fatal | 复位清零、错误 lateReg 可见后保持；hartFatal 仅由 fatal 寄存器或 lateReg.valid&&lateReg.error 驱动，停止新发射/普通退休、不发精确陷入。[SOC-3b§1] |
| W2 | 普通写在无异常 WB 提交拍末捕获 valid/写使能、bank、rd、data；下一拍优先取得对应 RF 写口，无条件完成，不受 fatal/stop/kill/redirect/hold 屏蔽；每拍消费一次，不随 WB 保持，不置/清 busy，复位清 valid。[backend-timing-contract.md§4] |
| lateReg | 单项 valid、bank、rd、data、error；ready 为空槽或旧项本拍 grant，空槽不穿透 RF。同拍消费旧项并接收新项时，完成/clear/数据/error 取旧项，拍末捕获新项。[backend-timing-contract.md§4] |

## 2. 真实源、记分板与旁路

- 整数 RAW 只检查真实使用的 rs1/rs2，WAW 只检查真实整数目的且 rd≠0；CSR immediate 不把 zimm 当 GPR。旧 CSR 状态/寄存器保守冒险同时保留。[backend-rtl-spec.md§3及附录A]
- 浮点 RAW 检查译码真正使用的三个 FPR 源，WAW 检查真实 FPR 目的，f0 不特判；FP→整数置整数 busy，整数→FP 置浮点 busy。[backend-pipeline-design.md§5]
- 每 bank：`busyNext=(busy & ~clearMask) | setMask`；复位清零；同目的 set/clear 同拍禁止，不同目的同时更新全部生效。[backend-rtl-spec.md§2.2]
- set 只来自真实无异常 WB 长延迟提交：MDU/FPU 和 Load/FP Load 的 Mshr；普通 ALU、Load Done、SC/AMO/LR 等有寄存器结果的普通项在 WB 提交、下一拍 W2 写入，不留下 busy。Store Mshr 不置目的。clearMask 只来自 lateReg/DIV/MUL/FPU 实际获准完成的有效目的，错误 lateReg 也清位但不写坏数据；W2 不置、不清 busy。[backend-timing-contract.md§4]
- `effectiveBusy=busy & ~clearMask`，clearMask 只来自实际被 grant 的来源；级间比较仍包括本拍即将提交但尚未置位的生产者。[backend-rtl-spec.md§3.1]
- ID 命中有效 busy 或 EX/MEM/WB 中受保护的生产者时不离开，只停 ID。WB 中会产生寄存器结果的访存（load、FLW/FLD、LR/SC/AMO、MMIO）只按寄存的 valid、类别、writes、bank、rd 判断 RAW/WAW，不能用 resp.kind/!wbDone 解除；WB miss 到 busy 的保护连续。ID 不另比较 W2 在途：W2 无条件写穿透，依赖者可同拍离开；FPR 普通结果也不得锁存旧值。[backend-timing-contract.md§4]
- 最终值写回拍，经各 RF 单写口写穿透提供给 ID；无其它保持时依赖者同拍离开。后台 MDU/FPU/lateReg 不添加 EX 数据旁路；原始 L1D late.fire 仅表示接收，不能用于解除 busy。[backend-timing-contract.md§4]
- 保留普通 MEM/WB→EX 旁路，新增 W2→EX；WB 资格仅依赖寄存的 valid、类别、writes、bank、rd，仅对非访存普通结果开放，exRead 不用 wbOrdinary/wbCommit/wbDone。年龄优先级 MEM>WB>W2>已捕获操作数；EX 保持时继续捕获有效旁路值。ALU 依赖在生产者 MEM/WB/W2 各拍无新增气泡。[backend-timing-contract.md§1/T01及§4]
- load-use 采用 T02：req=E，resp/commit=E+2，普通写 W2=E+3，依赖 ID 离开 E+3、EX=E+4。loadUseBypass/T02b 作废，不实现、不测试；参数可删除或保留为无此功能的兼容参数，报告说明。[backend-timing-contract.md§4]
- 禁止 S2→EX 的可选 load bypass 及其 miss-dependent EX 延迟执行机制；依赖者由 ID 的寄存元数据/busy 保护，直到实际 RF 写穿透可用。[backend-timing-contract.md§4]
- CSR 等空使用原始两组 busy==0，而非 effectiveBusy；同时 EX/MEM/WB 不得有已发射未提交 MDU/FPU 或尚未判定的访存，防止下一拍才提交置位的项越过 CSR。[backend-rtl-spec.md§3.1/A07][tasks/V1-BE-backend-spec-and-rtl.md§2.1]
- FP→x0 不能置整数 busy，但转换/比较可能产生 flags；将表中 `valid&&committed&&!isFp&&rd==0` 的组合归约接到 CSR 等空和 FPU 来源等待事件，仍用当前状态，最后 fire 后下一拍才放行 CSR；不新增计数器/表字段。[V1-BE-B01-ruling§3/ND02]
- ESTOP 在 WB 等两组 busy 空，后台继续；FASE empty 包含两组 busy 空，v1 不支持 useFASE=true；中断/WFI 不以 busy 空为条件。[backend-rtl-spec.md§6及9][v1-integration-notes.md§3]

## 3. 写口、保持与事件

- 后台全局一拍至多一个 grant，顺序 lateReg>DIV>MUL>FPU，未获准来源保留结果；L1D late 只进入 lateReg，不直接参与写口仲裁。[backend-timing-contract.md§4]
- GPR/FPR 各一个写口；W2 普通写无条件优先，同 bank 的后台来源未获写口则保持，不同 bank 可并行；WB 普通指令只提交并捕获 W2，不因写口保持。x0 不物理写，f0 可写。[backend-timing-contract.md§4]
- 后端自身停顿只在 ID 或 EX；EX 不 fire 时 MEM 插气泡，老 MEM/WB 继续。MEM/WB 只因 L1D s2Hold 或 WB 串行指令且其后全是气泡而保持；保持时退休、CSR/PC 更新、训练及年轻发射不得重复，后台完成继续。[V1-BE-B01-ruling§1]
- 每 bank 一个 2-bit 饱和计数和在途保护标记：lateReg/DIV/MUL/FPU 有 valid 而该 bank 无后台 grant 时 +1（饱和 3），否则清零；计数为 3 且标记为 0 时 ID 停发一拍并置标记，该 bank 获 grant 清标记，ID 只读寄存器。T21 的 L1D late 首次 valid/fire 为 c，冲突 c+1…c+7，ID 气泡 c+4，lateReg grant/物理写回 c+8，共 7 次冲突。原始 late 的接收/背压不直接计数。[backend-timing-contract.md§1/T21及§4]
- SOC-3c §6 D3：BTB/PHT/GHR 统一在 EX 真推进且 !wbKill 的决策拍产生并寄存，下一拍无条件送前端，消费拍不与 wbKill/downHold/exAdvance 门控、不再取消。决策拍之后更老 WB 陷入可能留下年轻分支训练，接受其预测质量影响；架构提交/取消不变。
- SOC-3c §6 D1：EX 快路只依赖 EX 寄存信息、寄存旁路值及 exRedirectSent，held EX 可纠错一次且保留本条；只取消 ID/skid 错路项。mem.predictionMiss/JALR BTB valid 使用本条已发 redirect。WB 慢路在前端边界寄存一拍后改 PC/清 translator/realigner/I-cache，后端事件仍在 WB；WB kill 后一拍禁止 ID 离开。两项 skid 空时直通，向前端的 ready 只取寄存占用。
- 无 WB 保持时，EX 单元 not-ready 只保持 EX/ID；MEM 消费后插气泡，MEM/WB 前进；ID RAW/WAW 不全局保持。[backend-rtl-spec.md§5.1]
- 后台写不再次 retire；独立普通提交与不同 rd 后台写同拍要分别输出事件。HPM11/12 可同拍记 MUL/DIV 来源停顿；13 每拍计数有后台 valid 而无后台 grant 的 bank 数（0/1/2），不代表 WB 停顿。[backend-timing-contract.md§4][V1-BE-B01-ruling§1]
- hartFatal 后取消未提交单元项与 CPU 访存，不发 trap/redirect；已提交 W2 无条件完成，已提交后台项继续消费，保留原防止未提交 FP 返回阻塞已提交项的规则。[backend-timing-contract.md§4]
- 错误 late 在 N 接收，hartFatal 从 lateReg 可见的 N+1 拍生效并保持；接受 N 拍可能多提交一条普通指令，这是 SOC-3b 明确行为变化。错误 lateReg 在获准完成拍清对应 busy、不写坏数据，lateWriteError 与该完成事件对齐；fatal 停止新普通退休，不能屏蔽已提交 W2。[backend-timing-contract.md§4]
- 对 `Valid` 的 L1D resp，没有 ready；按一次判定消费，不制造 extra capture。WB 写口冲突不保持，resp 与 WB 锁步消费，不加响应缓存或隐藏保持信号。[V1-BE-B01-ruling§1]

## 4. MDU 发射、提交和取消

- 非零整数目的、合法、无取指异常的 MUL/DIV 在 EX 直接 fire；WB/更老重定向禁止年轻同拍请求。MUL/DIV 的 result 只在 committed && done 时有效。[backend-rtl-spec.md§4.1]
- WB 向对应单元发一个无 tag 的 commit，授权最老未提交项；WB kill 向各单元发 killUncommitted，只取消未提交项；单元接口同拍先 commit 再 kill。[backend-rtl-spec.md§4.2]
- EX 分支/JAL/JALR 取消年轻 ID 和同拍年轻发射，不发全单元 kill，不影响更老 MEM/WB。[backend-rtl-spec.md§6.3]
- WB 异常/xRET/satp/中断/WFI，以及 v1 WB FENCE.I/SFENCE 的最终重定向作废年轻未提交项；已提交后台项和 busy 不由 kill 取消。[backend-rtl-spec.md§6.3][backend-pipeline-design.md§7][v1-integration-notes.md§3]
- MUL req 在 E，首次无反压结果 E+4；DIV fast 计算 E+1 完成但等 E+2 WB 授权，首次结果 E+3；常规 DIV result=E+2+实际迭代拍数；不得再加后端结果级。[backend-timing-contract.md§1/T04–T07]
- MUL 保持 SOC-3 M2 的显式 DSP A/B、M、P 寄存与 fabric 最终结果级，四拍延迟及原 kill/背压协议不变；本轮不改 MulUnit、不启用 retiming。旧 SignedMul65x65 参照及其测试保留；资源/时序结论须绑定实际综合或 routed 证据。[tasks/SOC-3-timing.md§1/M2][tasks/SOC-3b-wb-split.md§1]

## 5. FPU

- `FlowFpnewWrapper` 参数 TAG_WIDTH=log2(tableDepth)，TagType 使用相同 packed logic vector，tag_i/tag_o 直接接线。SOC-3d 第一批：ADDMUL 的 FP32/FP64 PipeRegs 从 3/4 改为 4/5；FP32 启用全精度乘积/对齐加数到宽加法之间的已有边界，FP64 增加归一化到舍入/状态之间的边界，各增加一拍内部延迟。舍入仍仅发生一次；数据、舍入模式、特殊值、UF 所需位、tag/mask/aux 同步保持和 flush，busy 覆盖新增有效项。UnitTypes、DISTRIBUTED、其余格式与操作组不变，T14/T15 的后端两端直连和 T16 按实际 CVFPU 延迟测量的规则保持。[tasks/SOC-3d-timing-batch-plan.md]
- EX 将三个操作数、EX 确定的 rm/op/格式、目的送到原始 CVFPU input；接收同拍写 metadata 表，allocate 前进，无输入寄存级。SOC-3c C1：exFpIssued 保证 held EX 只接收一次；req.valid 不含 downHold/wbKill/allowEx，仅受本条已发及寄存 fatal/stopped 限制，resourceWait 的 FP 项仅在尚未发射时等待 ready；表满/killDrain 或 CVFPU 不 ready 时 EX 等待。[backend-timing-contract.md§1/T14]
- commitCursor 从最老方向组合找 valid&&!committed 项；WB commit 只授权这一项并前进；WB kill 清所有未提交 valid，刚 commit 的项保留，committed 项不动。[tasks/V1-BE-backend-spec-and-rtl.md§2.1][backend-rtl-spec.md§4.2][自定]
- `out_tag` 组合读表，valid&&committed 的输出直接参加后台仲裁；result fire 与 CVFPU out fire 同拍、无数据寄存级。[backend-timing-contract.md§1/T15]
- valid&&!committed 的早完成输出 ready=0，等 WB；valid=0 的作废返回直接 ready=1 丢弃，不写 RF/flags；不得用 flush_i 清 CVFPU，因为那会取消已提交项。[backend-pipeline-design.md§4及7][v1-integration-notes.md§3]
- kill 后设置 killDrain，待 CVFPU busy=0 才重新允许分配 tag；已提交输出照常参与仲裁，作废输出照常丢弃，防止无世代 tag 的迟到 ABA 误认。[V1-BE-B01-ruling§3/ND01]
- SOC-3c §6 D2/S13/S05：canAllocate 不含 killUncommitted；FP fire/kill 同拍时新项显式 valid=0，allocate 前进且无 commit 时 commitCursor 跳过该 tag。作废返回 ready=1 丢弃，不写 RF/flags、不获提交；killDrain 期间禁止 tag 复用。L1D/MUL/DIV 请求仍在 WB kill 拍禁止，其余 speculative-write 等安全检查保留。
- fflags 只在真实已提交 FP 完成的 fire 拍按位或累积；CSR 等空保证读取最终值，FP flags 不随退休提前更新。[backend-pipeline-design.md§6–7][backend-timing-contract.md§1/T17及§2/P07]
- CVFPU 各单元保存未消费结果，但跨单元仲裁的 tag/data/status 在反压时可以变化；仅在 fire 拍取样。S07 对 MDU/late 的字段稳定断言保留，FPU 的检查使用 tag 台账验证不丢/不重复/不写 killed 项，不要求原始组合 mux 不变。[v1-integration-notes.md§3/CVFPU]
- FMV.X/FMV.F 本地搬运不分配 CVFPU tag；在 WB 提交、下一拍按普通目的 bank 走 W2，保留 NaN-boxing/word 扩展。FPR 依赖须由 WB 冒险或完整旁路保证，不能让 EX 使用旧锁存值；FP→x0 如需 flags 则仍执行 FP 运算，只抑制 x0 物理写和 busy。[backend-timing-contract.md§4]

## 6. L1D 合同

- `interface/L1DCoreIO.scala` 使用缓存侧方向，后端 `Flipped`；字段完全保持 l1d spec §1.1，无全局 hold、resp.ready、请求编号或完成缓存；op/响应 kind 枚举顺序按表，cause/tval 用64位。[l1d-rtl-spec.md§1.1][自定]
- EX 地址=`rs1+imm`；Store wdata 是原始64位，不移位，size/signed/amoFunc/aq/rl/rd/isFlw 直接给 L1D；格式化/NaN-boxing由 L1D 负责，后端不二次格式化。[backend-pipeline-design.md§3][l1d-rtl-spec.md§1.1及5.4]
- 更老 WB kill 产生 s2Kill，清未判定 S2 和年轻 S1；MEM 级请求因更老取消而作废时产生 s1Kill，禁止同拍年轻 S0；EX 分支不杀更老访存；陷入给 trapClearRsv。[l1d-rtl-spec.md§3及8.1][backend-rtl-spec.md§6.3]
- WB 的 resp Done：无异常当拍提交，有寄存器结果时捕获 W2、下一拍物理写；Mshr：Load/FP Load 当拍提交置 busy，Store 不置目的；Exc：当拍精确陷入，不捕获该项 W2，kill 年轻项但不取消更老已提交 W2/lateReg。[backend-timing-contract.md§4]
- L1D 暂不判定以 s2Hold 保持 WB/年轻流水；已提交的 late 不受此保持阻止。AMO、aq/rl LR/SC、MMIO 和 LR miss 等实际 Done，不能把 Mshr 当其完成。[backend-pipeline-design.md§4][l1d-rtl-spec.md§5.2及8–9]
- mmioBusy=1 后不可取消；中断/调试等它退休再接受；一般中断只等未提交流水排空，不等后台 busy。[l1d-rtl-spec.md§9][backend-pipeline-design.md§7]
- RSP↓ DataE 在 R fire，R+1…R+4 安装，R+5 回放 S0、R+6 S1、R+7 S2 late.fire 并进入空 lateReg，R+8 迟到结果实际写 RF；同行等待请求仍 R+8 重查 S0、R+10 Done。无额外仲裁阻塞假设沿用 T11/P05。[backend-timing-contract.md§1/T11及§2/P05]

## 7. 栅栏、异常与 MMU

- FENCE 是 L1D req.op=Fence；到 S2 后等 drained 再 Done，不以旧 NOP 或 dcacheFlush 替代。[l1d-rtl-spec.md§11][v1-integration-notes.md§3]
- FENCE.I、SFENCE.VMA、WFI、ESTOP 离开 ID 后，ID 禁止年轻发射直到它离开 WB；串行 WB 等待时 EX/MEM 必为气泡。[V1-BE-B01-ruling§1]
- FENCE.I 到 WB 仍只等 l1d.drained；首次满足当拍清 I-cache、redirect、提交。不得增加 W2/lateReg 等待条件，redirect 不取消其已提交写；此前 WB 保持规则不变。[backend-timing-contract.md§1/T18–T19及§4]
- SFENCE 到 WB 冲刷前端、kill iTLB、禁止新 iTLB/dTLB 请求；第一个 l1d.drained&&mmu.idle 的拍 S 只发一拍 sfence，S 之后第一个 mmu.idle 拍 redirect/提交；不增加 W2/lateReg 等待条件。[backend-timing-contract.md§1/T20及§4]
- SFENCE 的 vaddr/asid 与 rs1Nz/rs2Nz 随指令保持到 WB，不在 EX 发 sfence；asid取真实rs2低16位。[breeze-mmu-rtl-spec.md§4.3][v1-integration-notes.md§3]
- 访存异常时，更老已提交 DIV/FPU/Load miss 照常完成；未提交年轻项不置 busy、不物理写。trap handler 若读 pending 目的仍受记分板约束。[backend-pipeline-design.md§7][backend-timing-contract.md§2/P09–P10]

## 8. 断言和测试

- S01–S03/S08/S10/S14：两 bank 目的唯一、busy 生命周期、只屏蔽实际 grant、CSR 用原始 busy；覆盖四来源与 f0/跨 bank。S04/S05：最老 commit、commit-before-kill、killed 永不写、committed 存活。[backend-rtl-spec.md§10.1][tasks/V1-BE-backend-spec-and-rtl.md§2.3]
- S06/S07：grant独热、bank单写、DIV/MUL/late反压字段保持；FPU 使用上文 tag/fire 合同。SOC-3c §6 S09：WB 保持时禁止慢路改向、退休、CSR 及产生训练，允许 exRedirectSent 保证的一次 EX 快路纠错；前端消费已寄存训练不受限。S13/S05：FP 可同拍 fire/kill 入表即作废，其他年轻单元请求禁止；EX redirect 不清老 FU。其余 S09 安全检查不变。[backend-rtl-spec.md§10.1]
- S11/S12：MUL四级对齐/全级保持，DIV占用/快路径/释放后一拍接受。S15：新 L1D 不用旧 dmem capture；按 accepted/resp/late 台账检查一次完成、kill例外、flags不丢。S16：MUL/DIV来源事件与每 bank 写口冲突精确一致。[backend-rtl-spec.md§10.1][l1d-rtl-spec.md§1.1][tasks/V1-BE-backend-spec-and-rtl.md§2.3]
- T01–T22、P01–P10 实例化真实后端，按更新合同精确测量；T02b 作废。T12/T21/T22 分别测 late.fire 和物理写回；T13 对齐 lateReg 等四个写口来源，现有 returnDelay 26→25，N…N+3 写回期望不变；P06 ② 空拍={g−1}；其余无冲突刺激改为 W2 无同 bank 普通写，保留期望值。[backend-timing-contract.md§0–2]
- 后端接行为 L1D 模型，遵守原始无 ready 的 resp，不增加模型专用 hold；随机延迟、s2Hold/late消费背压、异常/kill、固定种子；回放 S2 直接驱动 late，反压时进入 MSHR LATE 保存数据，S2 照常前进；resp 不依赖 late.ready；LATE 占用 MSHR 但不阻止 probe。[V1-BE-B01-ruling§1][tasks/V1-BE-backend-spec-and-rtl.md§2.3及3.1]
- 本轮先只修订规格，不改 RTL/测试、不执行硬件验证。后续每次编译/仿真/RTL 生成前重读 docs/cross-project/simulation-host.md，先校验 cloud_chen，不可用才校验并使用 Alan；Vivado 仍用 Alan。不做形式化，失败不放宽冻结测试。[tasks/SOC-3b-wb-split.md§2–3]

## 9. SOC-3b 结构证据与新增检查

- 结构禁止/允许范围及取证方法完整沿用时序合同 §4：禁止 S2 决定写口授权、结果 ready、实际 clear、RAW/WAW 可用性与 EX 旁路选择；允许保持/精确取消、Mshr 提交置忙、W2 输入和 HPM 事件采样，分别报告级数/slack。Mshr→busy 若进入全局 worst-20 且 slack<0，停下，不挪 T10。中间网名优化掉可用 RTL fan-in 分析，不能加 keep 或改综合策略。
- 检查 W2 在 fatal/stop/kill/redirect/hold 重叠时完成且仅一次、WB 无新提交后 W2 valid 清零；lateReg 同拍出入取旧完成/新捕获；GPR/FPR 写穿透；ALU 间隔 0/1/2 条及 held EX 捕获；WB 访存 RAW/WAW 无 resp.kind 依赖。保持原 S01–S16 的安全含义，不以放宽断言凑通过。
- trace 在 WB 记录提交和普通架构结果，物理写拍由 observe.gprWrite/fprWrite 测量；普通 W2 不输出后台 pending 完成，lateWriteError 取 lateReg 完成旧项。
