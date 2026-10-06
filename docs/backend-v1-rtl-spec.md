# Breeze 后端 v1 RTL 规范

本稿按 V1-BE 任务书扩展 T01；冻结输入不改。B01 已按用户批准的裁定修订，ND01/ND02 已接受；实际实现、接入和 Alan 证据见 `tasks/V1-BE-report.md`。[自定]

## 0. 权威、范围与拍定义

- 保持 ID/RR、EX、MEM、WB 四级；请求在 EX 发起，WB 顺序提交；EX/MEM/WB 分别对齐 L1D S0/S1/S2。[backend-pipeline-design.md§3–4]
- 本条指令 EX 拍为 E，无保持时 MEM=E+1、WB=E+2；所有事件按当前组合信号、周期末上升沿更新测量。[backend-timing-contract.md§0]
- T01 的 A01–A08、S01–S16、整数真实源表、MDU 提交/kill/保持全部沿用；T01 仅适用于旧阻塞访存/FPU 的条款由下述 v1 条款替代。[tasks/V1-BE-backend-spec-and-rtl.md§1–2]
- 不改前端、L1D/L2/MMU 实现和 CVFPU 内部；核心新接线由集群规范承接。本稿不新增队列、结果缓存、流水级或特定地址/PC/指令序列行为。[tasks/V1-BE-backend-spec-and-rtl.md§3.6][backend-timing-contract.md§3]

## 1. 寄存器与单元

| 状态 | 归属、更新规则和依据 |
| --- | --- |
| 整数 busy | x1–x31；实现用 32 bit 且 bit0 恒 0；仅 WB 已提交长延迟写置位，实际完成清位；kill 不写 busy。[backend-rtl-spec.md§2][自定] |
| 浮点 busy | f0–f31 全部有效；同一置位/清位生命周期。[backend-pipeline-design.md§5] |
| 来源 | busy 对应项记录 L1D/DIV/MUL/FPU 的 2 bit 来源，仅 busy 有效时读取。[backend-rtl-spec.md§2.1][自定] |
| EX/MEM/WB | valid、真正发射资格、类别、rd bank/index，保留普通数据、PC、异常、CSR、预测和 trace 侧带；未提交长延迟目的由级间检查覆盖。[backend-rtl-spec.md§1–3] |
| MUL | 使用 `CommittedMulUnit`，P1–P4 各 valid/committed/rd/op/product；P4 不离开则四级全部停止；不加输出级。[backend-rtl-spec.md§7] |
| DIV | 使用 `CommittedDivUnit`，保留 unsigned radix-4；occupied/committed/done/rd/result；释放后下一拍才 ready。[backend-rtl-spec.md§8] |
| FPU 表 | 32 项，tag 宽度 5；每项仅 valid、committed、rd、isFp，无 data/flags；循环 allocate/commitCursor 指针决定发射/提交顺序。[tasks/V1-BE-backend-spec-and-rtl.md§2.1][自定] |
| FPU killDrain | 一个控制位；WB kill 后暂停新 FP 接收直到 CVFPU busy=0，防止被作废但尚未返回的 tag 被复用。[V1-BE-B01-ruling§3/ND01] |
| fatal | 一个复位清零、`late.error` 后保持的 hartFatal 状态；停止该 hart 新发射/普通退休，不触发精确陷入。[tasks/V1-BE-backend-spec-and-rtl.md§2.1][自定] |

## 2. 真实源、记分板与旁路

- 整数 RAW 只检查真实使用的 rs1/rs2，WAW 只检查真实整数目的且 rd≠0；CSR immediate 不把 zimm 当 GPR。旧 CSR 状态/寄存器保守冒险同时保留。[backend-rtl-spec.md§3及附录A]
- 浮点 RAW 检查译码真正使用的三个 FPR 源，WAW 检查真实 FPR 目的，f0 不特判；FP→整数置整数 busy，整数→FP 置浮点 busy。[backend-pipeline-design.md§5]
- 每 bank：`busyNext=(busy & ~clearMask) | setMask`；复位清零；同目的 set/clear 同拍禁止，不同目的同时更新全部生效。[backend-rtl-spec.md§2.2]
- set 只来自真实无异常 WB 长延迟提交：MDU/FPU 和 Load/FP Load 的 Mshr；Load Done/SC/AMO/LR 等实际结果同 WB 写入，不留下 busy。Store Mshr 不置目的。[backend-pipeline-design.md§4–5]
- `effectiveBusy=busy & ~clearMask`，clearMask 只来自实际被 grant 的来源；级间比较仍包括本拍即将提交但尚未置位的生产者。[backend-rtl-spec.md§3.1]
- ID 命中任一 bank 的有效 busy 或 EX/MEM/WB 未提交生产者时不离开；只停 ID，老流水与后台结果继续前进。[backend-rtl-spec.md§3.1][backend-pipeline-design.md§5]
- 最终值写回拍，经各 RF 的单写口写穿透提供给 ID；若无其他保持，依赖者同拍离开。后台 MDU/FPU/late 不添加 EX 数据旁路。[backend-rtl-spec.md§3.2][backend-pipeline-design.md§5]
- 普通 ALU EX/MEM/WB 旁路和 held EX 操作数更新保留；ALU→ALU 依赖 EX 间隔为 1 拍。[backend-rtl-spec.md§1.2][backend-timing-contract.md§1/T01]
- `loadUseBypass` 是 `BreezeClusterConfig` 生成参数，默认 false；false 时 Load EX/MEM 冒险阻止 ID，Done 的 WB 拍 RF 穿透释放，依赖 EX=E+3；true 时允许依赖者 E+1 离开 ID，并在 E+2 用 S2→EX 组合数据，依赖 EX=E+2；不影响其他操作数和 WAW 资格。[backend-timing-contract.md§4]
- 可选旁路下若依赖者已在 EX 而前一 Load 判为 Mshr，依赖者保持 EX；ID 关闭期间复用原两 RF 读口，将真实写穿透值捕获进已有 EX 操作数，原始 busy 清零后推进，不加后台结果→EX 旁路或结果缓存。[自定]
- CSR 等空使用原始两组 busy==0，而非 effectiveBusy；同时 EX/MEM/WB 不得有已发射未提交 MDU/FPU 或尚未判定的访存，防止下一拍才提交置位的项越过 CSR。[backend-rtl-spec.md§3.1/A07][tasks/V1-BE-backend-spec-and-rtl.md§2.1]
- FP→x0 不能置整数 busy，但转换/比较可能产生 flags；将表中 `valid&&committed&&!isFp&&rd==0` 的组合归约接到 CSR 等空和 FPU 来源等待事件，仍用当前状态，最后 fire 后下一拍才放行 CSR；不新增计数器/表字段。[V1-BE-B01-ruling§3/ND02]
- ESTOP 在 WB 等两组 busy 空，后台继续；FASE empty 包含两组 busy 空，v1 不支持 useFASE=true；中断/WFI 不以 busy 空为条件。[backend-rtl-spec.md§6及9][v1-integration-notes.md§3]

## 3. 写口、保持与事件

- 后台全局一拍至多一个 grant，顺序 L1D late > DIV > MUL > FPU；来源不被选时由来源保留，仲裁器不寄存数据。[backend-rtl-spec.md§10.1/S06][backend-timing-contract.md§1/T13及§3]
- GPR/FPR 各一个写口；WB 普通写当拍优先写入并提交，同 bank 的后台来源 ready=0；不同 bank 可并行。rd=x0 不需要整数口，v1 没有 wbPortStall。[V1-BE-B01-ruling§1]
- 后端自身停顿只在 ID 或 EX；EX 不 fire 时 MEM 插气泡，老 MEM/WB 继续。MEM/WB 只因 L1D s2Hold 或 WB 串行指令且其后全是气泡而保持；保持时退休、CSR/PC 更新、训练及年轻发射不得重复，后台完成继续。[V1-BE-B01-ruling§1]
- 每 bank 一个 2-bit 饱和计数和在途保护标记：有后台 valid 而该 bank 无 grant 时计数 +1（饱和 3），否则清零；计数为 3 且标记为 0 时 ID 停发一拍并置标记，该 bank 获 grant 清标记。ID 只读这两个寄存器，不依赖当拍仲裁。最高优先级来源在连续普通写下首次落败 c、气泡 c+3、grant c+6。[V1-BE-B01-ruling§1]
- EX 解析的 BTB 请求沿用一个寄存边界；MEM/WB 保持时该请求保持、前端不消费，解除后只发一次；更老 WB kill 丢弃待发训练。PHT/GHR 仅在 EX 真推进时更新。[backend-rtl-spec.md§5/A08及§6][自定]
- 无 WB 保持时，EX 单元 not-ready 只保持 EX/ID；MEM 消费后插气泡，MEM/WB 前进；ID RAW/WAW 不全局保持。[backend-rtl-spec.md§5.1]
- 后台写不再次 retire；独立普通提交与不同 rd 后台写同拍要分别输出事件。HPM11/12 可同拍记 MUL/DIV 来源停顿；13 每拍计数有后台 valid 而无后台 grant 的 bank 数（0/1/2），不代表 WB 停顿。[backend-timing-contract.md§4][V1-BE-B01-ruling§1]
- hartFatal 后取消未提交单元项与 CPU 访存，保留已提交后台项，不产生陷入/前端重定向；防止未提交 FP 输出阻塞更老已提交返回。[自定]
- late.error 作为完成事件清对应 busy，不写坏数据；hartFatal 当拍可见并保持，停止该 hart 且不发 trap；其他已提交来源可继续被消费，不能因 fatal 执行年轻普通退休。[backend-pipeline-design.md§7][tasks/V1-BE-backend-spec-and-rtl.md§2.1][自定]
- 对 `Valid` 的 L1D resp，没有 ready；按一次判定消费，不制造 extra capture。WB 写口冲突不保持，resp 与 WB 锁步消费，不加响应缓存或隐藏保持信号。[V1-BE-B01-ruling§1]

## 4. MDU 发射、提交和取消

- 非零整数目的、合法、无取指异常的 MUL/DIV 在 EX 直接 fire；WB/更老重定向禁止年轻同拍请求。MUL/DIV 的 result 只在 committed && done 时有效。[backend-rtl-spec.md§4.1]
- WB 向对应单元发一个无 tag 的 commit，授权最老未提交项；WB kill 向各单元发 killUncommitted，只取消未提交项；单元接口同拍先 commit 再 kill。[backend-rtl-spec.md§4.2]
- EX 分支/JAL/JALR 取消年轻 ID 和同拍年轻发射，不发全单元 kill，不影响更老 MEM/WB。[backend-rtl-spec.md§6.3]
- WB 异常/xRET/satp/中断/WFI，以及 v1 WB FENCE.I/SFENCE 的最终重定向作废年轻未提交项；已提交后台项和 busy 不由 kill 取消。[backend-rtl-spec.md§6.3][backend-pipeline-design.md§7][v1-integration-notes.md§3]
- MUL req 在 E，首次无反压结果 E+4；DIV fast 计算 E+1 完成但等 E+2 WB 授权，首次结果 E+3；常规 DIV result=E+2+实际迭代拍数；不得再加后端结果级。[backend-timing-contract.md§1/T04–T07]
- DSP 推断沿用 65×65 有符号乘和四级寄存器，旧 SignedMul65x65 及其测试保留；未综合时不报告 DSP 数或 timing 达标。[backend-rtl-spec.md§7及9.2]

## 5. FPU

- `FlowFpnewWrapper` 参数 TAG_WIDTH=log2(tableDepth)，TagType 使用相同 packed logic vector，tag_i/tag_o 直接接线；CVFPU 算术、PipeRegs、UnitTypes 和 PipeConfig 不改。[tasks/V1-BE-backend-spec-and-rtl.md§2.1及3.6]
- EX 将三个操作数、EX 确定的 rm/op/格式、目的送到原始 CVFPU input；接收同拍写 metadata 表，allocate 前进，无输入寄存级；表满/killDrain 或 CVFPU 不 ready 时 EX 等待。[backend-timing-contract.md§1/T14][自定]
- commitCursor 从最老方向组合找 valid&&!committed 项；WB commit 只授权这一项并前进；WB kill 清所有未提交 valid，刚 commit 的项保留，committed 项不动。[tasks/V1-BE-backend-spec-and-rtl.md§2.1][backend-rtl-spec.md§4.2][自定]
- `out_tag` 组合读表，valid&&committed 的输出直接参加后台仲裁；result fire 与 CVFPU out fire 同拍、无数据寄存级。[backend-timing-contract.md§1/T15]
- valid&&!committed 的早完成输出 ready=0，等 WB；valid=0 的作废返回直接 ready=1 丢弃，不写 RF/flags；不得用 flush_i 清 CVFPU，因为那会取消已提交项。[backend-pipeline-design.md§4及7][v1-integration-notes.md§3]
- kill 后设置 killDrain，待 CVFPU busy=0 才重新允许分配 tag；已提交输出照常参与仲裁，作废输出照常丢弃，防止无世代 tag 的迟到 ABA 误认。[V1-BE-B01-ruling§3/ND01]
- fflags 只在真实已提交 FP 完成的 fire 拍按位或累积；CSR 等空保证读取最终值，FP flags 不随退休提前更新。[backend-pipeline-design.md§6–7][backend-timing-contract.md§1/T17及§2/P07]
- CVFPU 各单元保存未消费结果，但跨单元仲裁的 tag/data/status 在反压时可以变化；仅在 fire 拍取样。S07 对 MDU/late 的字段稳定断言保留，FPU 的检查使用 tag 台账验证不丢/不重复/不写 killed 项，不要求原始组合 mux 不变。[v1-integration-notes.md§3/CVFPU]
- FMV.X/FMV.F 的本地数据搬运不分配 CVFPU tag，按普通目的 bank 在 WB 写入，保留 NaN-boxing/word 扩展；FP→x0 如需 flags 则仍执行 FP 运算，只抑制 x0 物理写和 busy。[backend-rtl-spec.md§附录A][自定]

## 6. L1D 合同

- `interface/L1DCoreIO.scala` 使用缓存侧方向，后端 `Flipped`；字段完全保持 l1d spec §1.1，无全局 hold、resp.ready、请求编号或完成缓存；op/响应 kind 枚举顺序按表，cause/tval 用64位。[l1d-rtl-spec.md§1.1][自定]
- EX 地址=`rs1+imm`；Store wdata 是原始64位，不移位，size/signed/amoFunc/aq/rl/rd/isFlw 直接给 L1D；格式化/NaN-boxing由 L1D 负责，后端不二次格式化。[backend-pipeline-design.md§3][l1d-rtl-spec.md§1.1及5.4]
- 更老 WB kill 产生 s2Kill，清未判定 S2 和年轻 S1；MEM 级请求因更老取消而作废时产生 s1Kill，禁止同拍年轻 S0；EX 分支不杀更老访存；陷入给 trapClearRsv。[l1d-rtl-spec.md§3及8.1][backend-rtl-spec.md§6.3]
- WB 的 resp Done：无异常、真实写回并提交；Mshr：Load/FP Load 提交置 busy，Store 提交不置 busy；Exc：用 excCause/tval 精确陷入，抑制普通写/提交并 kill 年轻项。[l1d-rtl-spec.md§5.1–5.2][backend-pipeline-design.md§4]
- L1D 暂不判定以 s2Hold 保持 WB/年轻流水；已提交的 late 不受此保持阻止。AMO、aq/rl LR/SC、MMIO 和 LR miss 等实际 Done，不能把 Mshr 当其完成。[backend-pipeline-design.md§4][l1d-rtl-spec.md§5.2及8–9]
- mmioBusy=1 后不可取消；中断/调试等它退休再接受；一般中断只等未提交流水排空，不等后台 busy。[l1d-rtl-spec.md§9][backend-pipeline-design.md§7]
- RSP↓ DataE 在 R fire，R+1…R+4 安装，R+5 回放 S0、R+6 S1、R+7 S2 late；同行等待请求 R+8 重查 S0、R+10 Done。此推导假定无额外仲裁/写口阻塞，沿用 T11/P05 的环境条件。[l1d-rtl-spec.md§6.1–6.2][backend-timing-contract.md§1/T11及§2/P05]

## 7. 栅栏、异常与 MMU

- FENCE 是 L1D req.op=Fence；到 S2 后等 drained 再 Done，不以旧 NOP 或 dcacheFlush 替代。[l1d-rtl-spec.md§11][v1-integration-notes.md§3]
- FENCE.I、SFENCE.VMA、WFI、ESTOP 离开 ID 后，ID 禁止年轻发射直到它离开 WB；串行 WB 等待时 EX/MEM 必为气泡。[V1-BE-B01-ruling§1]
- FENCE.I 到 WB 等 drained；首次 drained 当拍清 I-cache 并 redirect nextPc，同时提交；此前 WB 保持，不发清空/重定向；删除旧 dcacheFlushReq/Done。[backend-timing-contract.md§1/T18–T19][v1-integration-notes.md§3]
- SFENCE 到 WB 冲刷前端、kill iTLB、禁止新 iTLB/dTLB 请求；等 drained&&mmu.idle 后只发一拍 sfence，保持 WB；S 之后第一个 mmu.idle 拍 redirect nextPc、提交，再解除请求关闭。[backend-timing-contract.md§1/T20][breeze-mmu-rtl-spec.md§4.4][v1-integration-notes.md§3]
- SFENCE 的 vaddr/asid 与 rs1Nz/rs2Nz 随指令保持到 WB，不在 EX 发 sfence；asid取真实rs2低16位。[breeze-mmu-rtl-spec.md§4.3][v1-integration-notes.md§3]
- 访存异常时，更老已提交 DIV/FPU/Load miss 照常完成；未提交年轻项不置 busy、不物理写。trap handler 若读 pending 目的仍受记分板约束。[backend-pipeline-design.md§7][backend-timing-contract.md§2/P09–P10]

## 8. 断言和测试

- S01–S03/S08/S10/S14：两 bank 目的唯一、busy 生命周期、只屏蔽实际 grant、CSR 用原始 busy；覆盖四来源与 f0/跨 bank。S04/S05：最老 commit、commit-before-kill、killed 永不写、committed 存活。[backend-rtl-spec.md§10.1][tasks/V1-BE-backend-spec-and-rtl.md§2.3]
- S06/S07：grant独热、bank单写、DIV/MUL/late反压字段保持；FPU 使用上文 tag/fire 合同。S09/S13：保持时控制与普通副作用禁止、WB 年龄取消、EX redirect不清老FU。[backend-rtl-spec.md§10.1][v1-integration-notes.md§3]
- S11/S12：MUL四级对齐/全级保持，DIV占用/快路径/释放后一拍接受。S15：新 L1D 不用旧 dmem capture；按 accepted/resp/late 台账检查一次完成、kill例外、flags不丢。S16：MUL/DIV来源事件与每 bank 写口冲突精确一致。[backend-rtl-spec.md§10.1][l1d-rtl-spec.md§1.1][tasks/V1-BE-backend-spec-and-rtl.md§2.3]
- T01–T22、T02b、P01–P10 最终必须实例化真实后端，逐行以 ID 开头，并按冻结测量点精确断言；独立单元的 `*_component` 证据不可替代对应合同验收。[backend-timing-contract.md§0–2][自定]
- 后端接行为 L1D 模型，遵守原始无 ready 的 resp，不增加模型专用 hold；随机延迟、s2Hold/late消费背压、异常/kill、固定种子；回放 S2 直接驱动 late，反压时进入 MSHR LATE 保存数据，S2 照常前进；resp 不依赖 late.ready；LATE 占用 MSHR 但不阻止 probe。[V1-BE-B01-ruling§1][tasks/V1-BE-backend-spec-and-rtl.md§2.3及3.1]
- 只在 Alan 运行 sbt/仿真；不做形式化；每次提交前 frozen_check OK；失败不改冻结期望/删除/跳过/放宽测试。[tasks/V1-BE-backend-spec-and-rtl.md§3]
