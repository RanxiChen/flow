# Breeze 后端 RTL spec：T01 阶段一审阅稿

状态：**未冻结，不授权阶段二实现**。微架构依据是 [backend-pipeline-design.md](backend-pipeline-design.md)（下称 D），任务范围与验收依据是 [T01 任务书](tasks/T01-backend-scoreboard-mdu.md)（下称 T）。本文仅展开 D 第 10 节第 1 步；设计未覆盖的细节统一列在第 12 节，不把候选方案当作决定。冻结前不得据此编写 RTL。

## 0. 范围、版本与拍的定义

- 源码审查基线：分支 `feat/pcie-fase-20260920`，提交 `d5672f51bf0ec67465148c02af970c70464bec68`，2026-10-05。下面所有源码位置均对应此提交；以后改动需刷新行号。
- 本步实现目标：整数 MDU 记分板、RAW/WAW 检查、长延迟写口仲裁、EX 发起/WB 提交或 kill、DSP 乘法 4 拍/II=1、radix-4 除法及 1 拍特殊结果、三个 HPM 事件。
- 访存、FPU 保留阻塞路径；不在本步实现浮点记分板、Load miss 提前提交、L1D S2 判定、迟到数据、`hartFatal` 或新的 MMU 接入。统一接口只记录这些后续用途。
- `N` 表示一个时钟周期，周期内观察当前寄存器及组合信号，周期末上升沿采样 fire/更新寄存器，`N+1` 观察更新值。`fire = valid && ready`。`idLeave` 指真实离开 ID 的一次握手；`exAccept` 指单元接收一次操作；`wbCommit` 指该指令在 WB 无异常并实际提交一次；`resultWrite` 指仲裁获准、结果握手并实际写整数寄存器的一次事件。这些是本文的逻辑名称，尚不是既有 Bundle 字段。
- 本文给出有依据的拍级约束；队列深度、身份编码、同拍未定义组合等标为 **未确认/待决定**。它们是冻结阻塞项，不能在阶段二默认填值。
- 本轮只做源码和文档审查；编译、RTL 生成、仿真、形式化、ACT4、tandem、Vivado、性能测量全部 **未运行**。

## 1. 现状映射与改造边界

### 1.1 流水寄存器和状态

源码中的 decode 没有在下列位置另建 ID 寄存器，来自 `fetchBuffer`；ID/RR 接收后进入 `idExeReg`。此对应关系见 `design/src/main/scala/backend/BreezeBackend.scala:64-75,241,337-383`；四级目标来自 D 第 3 节。

| 现有对象、位置 | 现状（源码审查） | 本步处理 |
| --- | --- | --- |
| `idExeReg`；`design/src/main/scala/backend/BreezeBackend.scala:241,285-442`；字段 `design/src/main/scala/interface/interface.scala:358-384` | valid、PC/指令/长度/取指异常、控制、rs/rd、读值与立即数；decodeFire 装载，重定向清空，阻塞时更新可旁路操作数 | 保留四级及指令元数据；增加本条 MDU 预约/发起身份侧带。EX 单元不 ready 时不能丢失、重复发起本条指令，具体级间 enable 见 Q04 |
| `idFpCtrl/idFpOperand1..3`；`design/src/main/scala/backend/BreezeBackend.scala:198-206,444-466` | FP decode/操作数侧带与 ID/EX 条件对齐 | 保留 FPU 阻塞方式、寄存器和语义；整数源参与 MDU 依赖检查，不实现 FPR 记分板 |
| `exeMemReg`；`design/src/main/scala/backend/BreezeBackend.scala:483,1167-1325`；字段 `design/src/main/scala/interface/interface.scala:386-432` | 指令元数据、ALU/地址、CSR、MUL 操作数/类型、DIV 快速结果/幅值/符号、trace | 保留普通指令、访存、CSR/异常字段；MDU 在 EX 发起后只携带身份和提交所需元数据到 MEM/WB，不再在 MEM 等算术结果；旧 MDU 数据字段删除或停止使用的最终清单待 Q09 |
| `exeMemMemOp/exeMemAmoFunc/exeMemAq/exeMemRl`；`design/src/main/scala/backend/BreezeBackend.scala:487-490,1207-1210,1288-1292` | 原子访存侧带随 EX/MEM 清空/推进 | 保留，不改访存接口/aq/rl 边界 |
| `exeFpCtrl/Operand1..3/Rm`；`design/src/main/scala/backend/BreezeBackend.scala:202-206,1327-1344` | EX/MEM FP 侧带；动态 rm 在 EX→MEM 采样 | 保留，不改 FPU 内部 |
| `memWbReg`；`design/src/main/scala/backend/BreezeBackend.scala:61,1378-1569`；字段 `design/src/main/scala/interface/interface.scala:434-470` | WB 元数据、异常、普通/访存/CSR/MDU 数据；等待时清 valid 防重复退休 | 保留 WB 顺序提交、异常元数据；MDU 到 WB 不等结果；增加提交身份，普通 WB 让写口时整条 WB 项保持且不得重复退休（Q04/Q06） |
| `memWbFpWrite/Data/FlagsValid/Flags`；`design/src/main/scala/backend/BreezeBackend.scala:156-169,1571-1608` | FP 写寄存器和 flags 与共同 MEM/WB 对齐 | 保留；普通 FP→GPR 与后台 MDU 同拍占整数写口需保留结果（Q07）；FPR 写口是否随共同 WB 停顿见 Q06 |
| `memWaitingRespReg`；`design/src/main/scala/backend/BreezeBackend.scala:491,727-728,1346-1352` | 访存发出置位、响应清除 | 保留；不让新的 WB 停顿丢失脉冲响应（Q07） |
| `mulWaitingRespReg/divWaitingRespReg`；`design/src/main/scala/backend/BreezeBackend.scala:492-493,1354-1368` | 单笔等待状态，所有 frontendRedirect 清除 | 修改/替换为逐笔 MDU 生命周期；删除它们对全流水等待和“重定向全清”的作用，已提交后台操作必须保留；是否保留诊断同名状态见 Q09/Q10 |
| `fpWaitingRespReg`；`design/src/main/scala/backend/BreezeBackend.scala:494,753-771,1370-1376` | FP ready 接收、等待完成 | 保留阻塞语义；共同级停顿引入后的响应保持见 Q07 |
| `architecturalNextPc/wfiSleepingReg`；`design/src/main/scala/backend/BreezeBackend.scala:531-533,561-601,1144-1154` | 退休/重定向更新架构 PC，WFI 睡眠和唤醒 | 保留 PC/唤醒语义，所有“退休”消费者改看实际一次提交；WFI 不等待后台 MDU |
| `fenceiFlushIssuedReg/memBtbUpdate`；`design/src/main/scala/backend/BreezeBackend.scala:261,606-677,1156-1165` | FENCE.I 单次 flush 请求；BTB 更新寄存并只消费一次 | 保留单次副作用与取消规则，新的流水反压必须统一保护它们（Q04） |

### 1.2 hold、hazard 和旁路逐项映射

| 信号与源码位置 | 现有行为 | 本步要求 |
| --- | --- | --- |
| `pipelineHold`；`design/src/main/scala/backend/BreezeBackend.scala:1063-1068` | memory/MUL/DIV/FP 请求与等待、fenceiPending 合并 | 仅去掉 MUL/DIV 两组等待项，memory/FP/FENCE.I 原有阻塞条件保留；新资源反压与 WB 让拍采用何种 enable/hold 组合待 Q04，不能删除其他项 |
| `decodeReady/decodeFire`；`design/src/main/scala/backend/BreezeBackend.scala:1610-1615` | csr/fp hazard、pipelineHold、redirect、中断、WFI 限制 decode | 加入整数记分板 RAW/WAW 与 CSR 等记分板清空条件；中断只排空未提交流水，不等后台 MDU |
| `decodeUsesRs1/decodeUsesRs2`；`design/src/main/scala/backend/BreezeBackend.scala:225-239` | 分支、跳转、CSR、sfence、Store/SC/AMO 的真实源参与判断 | 保留真实源语义；FP 的整数源与所有整数目的写同样检查（Q08） |
| `loadUseHazard`；`design/src/main/scala/backend/BreezeBackend.scala:974-978` | 等待访存结果时 EX 源依赖 | 保留阻塞访存路径和事件定义，本步不改新 L1D load-use 延迟 |
| `idExePendingCsrRd/exeMemPendingCsrRd/memWbPendingCsrRd/csrUseHazard`；`design/src/main/scala/backend/BreezeBackend.scala:979-1006` | CSR rd 被 decode rs 使用时等待 | 保留，在重新定义提交/让拍后不能把未完成普通 CSR 当作已写回 |
| `idExePendingCsrState/exeMemPendingCsrState/csrStateHazard`；`design/src/main/scala/backend/BreezeBackend.scala:1007-1014` | CSR 状态含别名/隐式用户，阻塞后续 decode 至 WB 更新 | 保留，不把记分板清空当作 CSR 状态冒险的替代 |
| `csrRegHazard/csrHold`；`design/src/main/scala/backend/BreezeBackend.scala:1023-1038` | CSR 读流水中未完成 GPR 产生者时 decode 停，CSR 产生者继续推进 | 保留，并加后台 MDU 清空等待；不得把 CSR 等待加成阻止老 MDU 前进的全流水 hold |
| `fpSourceMatches/fpRegHazard`；`design/src/main/scala/backend/BreezeBackend.scala:1040-1047` | FP 三个 FPR 源依赖 ID/EX 与 EX/MEM FPR 写 | 保留，f0 不是 x0，本步不擅自扩展成 FPR 记分板 |
| `idCsrAffectsFp/exeCsrAffectsFp/memWbCsrAffectsFp/fpCsrHazard`；`design/src/main/scala/backend/BreezeBackend.scala:1048-1061` | mstatus/sstatus/frm/fcsr 的 FP 状态冒险 | 保留，FPU 内部不变 |
| `wbData`、整数写使能；`design/src/main/scala/backend/BreezeBackend.scala:134-155` | 普通 ALU/MEM/CSR/MUL mux，按 WB 有效与异常屏蔽写 | 改为单写口仲裁；MUL/DIV 结果不再由普通 WB mux 写；FP→GPR 当前使用 MUL selector，不能一并删除（Q09） |
| `exeRs1Data/exeRs2Data`；`design/src/main/scala/backend/BreezeBackend.scala:842-885` | WB 后赋值、MEM ALU 后赋值、MEM completion 最后赋值；越年轻匹配者优先 | 保留普通 ALU/MEM/WB 的数据顺序；移除旧 MDU completion 假设，长延迟实际写回同时供 ID 旁路；已在 EX 被反压的读值保持规则待 Q04/Q08 |
| `completionValid/Rd/Data` 与互斥断言；`design/src/main/scala/backend/BreezeBackend.scala:825-840` | completion 用同一个 EX/MEM rd；断言所有长延迟来源同拍最多一项 | MDU 改用自己携带的标签；多个 ready 结果同拍合法，改验“写口 grant 独热”，旧断言修改需明确审阅授权 Q15；访存/FP 本步仍走原阻塞 completion |
| `mulReqIssued/mulRspFire/divReqIssued/divRspFire/divFastCompletion`；`design/src/main/scala/backend/BreezeBackend.scala:733-752` | MEM 发起，返回结果或快速完成驱动 WB | 改为 EX req.fire、WB resolve 与后台 result.fire；快结果也走统一提交/kill/保持协议 |
| `mulUnit.flush/divUnit.flush`；`design/src/main/scala/backend/BreezeBackend.scala:736,745` | 任意 frontendRedirect 全单元 flush | 删除全局冲刷的语义，换逐笔取消；年轻分支不能取消老 MDU，更不能取消已提交后台 MDU |

### 1.3 MDU 现状与源文件范围

- `RiscvMulUnit` 当前无 in_ready/out_ready/rd/commit 字段，仅 flush、in_valid、65 位 a/b、op、out_valid 和 64 位 result，valid/op 三拍对齐：`design/src/main/scala/multiplier/RiscvMulUnit.scala:13-63`。旧 `SignedMul65x65` 是 65×65→130 位 Booth/Dadda/CPA，三个寄存边界：`design/src/main/scala/multiplier/SignedMul65x65.scala:34-49,268-271,307-319`。本步替换乘法数据通路，保留旧单元作为等价性参照及其测试，不能删除参照断言（Q15）。D 第 9 节的 LUT/DSP 数字是设计文档中的历史/估计值，本轮未测量。
- `RiscvDivUnit` 当前接口是 magnitude/sign/word/remainder 加 flush/in_valid/busy/out_valid/result，符号恢复和 W 结果扩展在 wrapper：`design/src/main/scala/divider/RiscvDivUnit.scala:7-49`。radix-4 核寄存器为 `busyReg/outValidReg/quotientReg/remainderReg/divisorReg/shiftReg`，每次两位商，out_valid 是脉冲：`design/src/main/scala/divider/UnsignedRadix4Divider.scala:25-30,38-60,62-100`。保留算术迭代，改 wrapper 生命周期/保持。
- **已有快速路径**：后端 EX 已计算 divisor=0 和 RV64/RV32 有符号溢出、W 符号扩展，MEM 直接 completion：`design/src/main/scala/backend/BreezeBackend.scala:905-955,741-752,1526-1530`。本步“补快速路径”是把其行为接入新的统一单元协议，不能宣称基线完全缺失。放在 wrapper 还是后端、直接单元是否暴露原始操作数，待 Q05。
- 阶段二允许目录以 T 第 3.1 节为准。已有公共 Bundle/HPM 在 `design/src/main/scala/interface/interface.scala:340-351,358-470`，不在允许目录内；若需要修改，先解决 Q09，不能默默扩大范围。本阶段没有修改任何代码。

## 2. 状态与生命周期

### 2.1 整数记分板与所有权

D 第 5 节规定 x1–x31 每寄存器 1 bit，因此逻辑宽度 **31 bit**；x0 恒视为不 busy。物理采用 31 位还是 32 位且 bit0 恒零，属于表示选择，本文以 `busy[r] (r=1..31)` 描述。复位后无在途预约、所有位为 0；复位在途状态的整机边界需冻结 Q14。

本步仅 MUL/DIV 的整数目的预约置位。访存/FPU 依旧阻塞，不在此步置记分板；它们读/写 GPR 时仍必须遵守后台 MDU 依赖。这是 T 第 1 节及 D 第 10 节的分步范围，不能把 D 第 5 节所有 Load/FPU 置位一次全做。

| 逻辑状态 | 必需信息/宽度 | 更新约束 |
| --- | --- | --- |
| `busy[1..31]` | 31×1 | ID 离开预约；实际结果写回/提交前 kill 清除 |
| 预约所有者 | rd 5、来源 MUL/DIV、指令身份（位宽待 Q01）、是否已发起/已提交 | 从 ID 起跟踪，含在 EX 未 ready 的预约；不是仅数 FU 内部条目 |
| 每个 FU 在途项 | live 1、committed 1、身份、rd 5、数据/类型 | req.fire 接收；WB 同身份提交；提交前 kill 作废；结果获准后释放 |
| 来源归属（计数用） | MUL/DIV 区分 | 必须可判断是谁使 ID 停顿；用每寄存器来源位还是 FU/流水标签查找，待 Q13 |
| 浮点与迟到访存预留 | 目的 bank、标签、64 位 data、fflags/error 等逻辑信息 | 本步不建立 FPR 记分板、不连接这些结果源 |

“当且仅当有在途结果”的形式化含义：`busy[r] == (存在一条已离开 ID、尚未 kill/写回、准备写 r 的 MDU 指令)`。不能只与 `req.fire` 后的 FU 项等价，否则 ID 置位到 EX 发起间出现假失败/错误空窗。

### 2.2 置位、清除与同拍事件

| 周期 N 内条件 | 周期末/周期 N+1 可见状态 |
| --- | --- |
| `idLeave && isMDU && writesGpr && rd!=0 && !killed` | 预约 rd，`busy[rd]=1`；即便 EX 单元随后反压，也不能丢预约 |
| ID 被 stall、未真正离开，或被同拍老重定向取消 | 不产生新预约/EX 发起 |
| `resultWrite && rd!=0`，结果属于有效且已提交项 | 实际写回并清本项 rd 的 busy；旁路见 3.2 |
| 提交前被 kill，包括已预约但尚未发起者 | 清被 kill 项的预约与 FU live，不产生写回 |
| 已提交后台项遇 trap、分支、xRET、satp、FENCE.I、WFI 重定向 | 不清其 busy，不取消计算/保持的结果 |
| 周期 N 提交 MDU，但结果未实际写回 | busy 保持 1；提交本身不是清位事件 |
| 对不同 rd 的置位、写回清除、多个年轻项 kill 同拍 | 所有对应更新都必须生效，不能用一个全局 if/else 丢失其中一项 |
| 对同一 rd 的 clear+set，或 kill 与同身份 commit/result 同拍 | 具体优先级未确认，见 Q02/Q03；冻结前不得填写 next-state 公式 |

由于 rd 检查，同一 r 不得有两位所有者。禁止以“flush 全清记分板”实现异常；例如旧 DIV x5 已提交、年轻 MUL x6 未提交，trap 周期只清 x6，x5 必须保持。

## 3. ID 停顿与旁路

### 3.1 RAW/WAW

按照 D 第 5 节，对真实使用的整数源检查：

```text
sbRaw = (usesGprRs1 && rs1!=0 && busy[rs1])
     || (usesGprRs2 && rs2!=0 && busy[rs2])
sbWaw = writesGpr && rd!=0 && busy[rd]
sbBlock = sbRaw || sbWaw
csrSbBlock = isCsr && anyIntegerBusy  // 本步 FPU 尚无后台记分板
```

`sbBlock` 对所有年轻指令生效，包括 ALU、MDU、Load、SC/AMO、CSR 及 FP→GPR；不是只对第二条 MDU 生效。Store 的立即数不是 rs2 使用标志；rs2 写数据也依赖 GPR。FPR rs1/rs2/rs3 不能误查整数 busy。现有普通源判断位置见 1.2；FP GPR 使用/目的完整 decode 映射 **未确认**，Q08 要求冻结明确名单。不能把整数 rd 编码为 0 的“不写”约定用于未来 f0。

普通 ALU 旁路和 CSR/FP hazard 保留。ID 自己 stall 必须让老流水/FU/写口前进；CSR 等清空不能反压老 MDU 的 WB 提交，否则“先置 busy→CSR hold→老 MDU 不能提交→结果不能写→busy 不清”会死锁。

### 3.2 结果写回与读操作数

- N 拍长延迟结果获 grant 且实际握手写回时，同拍将 **被选中的** rd/data 旁路到 ID；未获 grant 的 valid 结果不能旁路、不能清位。
- 按 D 第 5 节，停住的依赖指令在下一拍可前进；不能假定仅看到 FU 计算完成就能放行。是否在 N 拍通过组合解 busy 让依赖者同时离开 ID，属于 Q02，不作为本稿默认行为。
- 普通 ALU 的 EX/MEM/WB 旁路保持。旧实现的 RF 同拍写读已有显式 write-through（`design/src/main/scala/core/RegFile.scala:27-43`），可以作为被仲裁后的 ID 旁路实现路径；不能继续把普通 WB 数据接 RF，同时把另一未写 MDU 数据当作已完成。
- EX 因反压保持时，必须能保存之前出现过的有效旁路值；现有 held EX 更新见 `design/src/main/scala/backend/BreezeBackend.scala:436-442`。新结果写回能否同拍影响 held EX、冲突选择和 enable 冻结于 Q04/Q08。

## 4. 长延迟单元统一接口契约

### 4.1 逻辑信号和字段

下表规定用途、必需宽度和方向（相对 FU），**不是已经存在的接口**。身份编码、resolve 的物理握手形式见 Q01/Q03，不能按这张表擅自定死 Bundle。

| 通道 | 方向/信号 | 必需字段 | 契约 |
| --- | --- | --- | --- |
| 发起 | In `req.valid`，Out `req.ready` | 身份（待定）、rd 5、目的 bank（整数/FP；编码待定）、操作类型；MDU 65 位 MUL a/b 或 DIV 有效操作数；未来 FPU rm 3 | EX 中仅 req.fire 接收一次；ready 为低则本条指令保留；老重定向 kill 同拍禁止发起年轻副作用 |
| 提交/取消 | In `resolve` 的有效、身份、commit/kill 信息（物理形式待定） | 与已预约/接收项匹配的身份；commit 与 kill 不可同时授权 | WB 实际提交时授权；提交前取消可来自老 WB 或更老级重定向，未到 WB 的被取消项也须有取消路径 |
| 结果 | Out `result.valid`，In `result.ready` | 身份、rd 5、目的 bank、data 64；未来 FPU flagsValid 1/fflags 5，迟到访存 error 1 | 被反压时 valid/全部 payload 保持；未提交项不得产生架构写；kill 的未提交项永不 resultWrite |

目的标签 rd 用于 RF/记分板，**不自动等于事务唯一身份**。同 rd 被 kill 后可再次使用，旧数据通路中的晚结果不能命中新预约；x0 还允许没有 busy 保护的多笔，Q01/Q11 冻结这些边界。

### 4.2 必需生命周期与逻辑转移

```text
ID 预约 -> EX 已接收、未提交 -> WB 已提交、未写回 -> 结果实际写回、释放
    \             \__ 提交前 kill -> 作废、释放预约
     \__ 未发起先 kill -> 只释放预约
```

算术完成 `done` 与上述阶段正交：可能在 WB 之前已算完；此时保存结果，等待授权或被 kill。已经提交且未算完时继续计算；已经提交且算完时等待写口。状态可以用 live/committed/done 标志表达，但数量、编码、存储位置待 Q01/Q03/Q05，本文不指定额外队列。

| 周期 N 事件 | 必须行为 |
| --- | --- |
| EX req.fire | 锁存操作数、类型、rd、身份；后续输入变化不影响本项 |
| WB 对匹配 MDU `wbCommit` | 一次性把该项标为已提交；不等算术结果；不通过普通 WB 写口写该 MDU 的未完成值 |
| done 先于 commit | 不架构写回，保存结果；result.valid 是否预先可见、后端是否接受到内部缓冲待 Q03 |
| 提交前 kill | 作废对应项/在途级，清预约；有物理晚结果也不能写回 |
| 已提交 done 且未获 grant | 保持结果；年轻流水允许在依赖/资源满足时继续 |
| 已提交 done 且 grant/ready | 精确一次写回并释放；不得把 result.fire 当第二次退休 |

未来 FPU 使用相同身份与生命周期，EX 采 rm，实际 FP 写回累积 fflags；迟到 Load 使用 L1D S2 判定完成提交后才供结果。两者的物理端口/错误上报尚未实现；L1D 两种事件及标签的设计边界见 [D-cache 2.9](dcache-pipeline-design.md#29-与后端的接口s2-判定与迟到数据)，不能在本步接上旧阻塞 dmem 伪造 S2 判定。

## 5. 单写口仲裁与流水推进

D 第 6 节确定：长延迟结果优先于普通 WB；整数 RF 仍一个写口。多个来源保持 valid/ready。D 第 6 节给出的 `L1D > DIV > MUL > FPU` 是待审阅顺序，D 第 12 节仍列待拍板；本文不擅自固定或改为轮转（Q12）。本步激活 DIV/MUL，其他源只保留逻辑扩展边界。

| N 拍可写项 | 写口与 ready | WB |
| --- | --- | --- |
| 无后台结果，有正常普通整数写 | 普通 WB 写 | 正常一次提交 |
| 一个已提交 MDU 结果，无普通整数写 | 该结果 grant、ready，实际写回/清 busy | 不占用普通整数写口；无写目的的 WB 是否能照常提交见 Q06 |
| 一个已提交 MDU 结果 + 普通整数 WB 写 | MDU 写，普通 WB 不写 | 普通 WB 完整保持，不能 retire/CSR 更新/改架构 PC/重复副作用 |
| MUL 与 DIV 同拍可写 | 按冻结的来源顺序选一个；未选 ready=0 | 有普通整数写则同上；不能断言两个 result.valid 不可共存 |
| 计算完成但未提交 | 不作为架构可写 grant 候选 | 继续按程序顺序处理 WB |
| 被 kill 的未提交结果 + 已提交后台结果 | kill 项不可写；后台项存活 | trap/redirect 与后台写同拍的确切输出条件待 Q03/Q06 |

只有 `grant && result.valid && result.ready && committed && !killed && rd!=0` 对应物理 GPR 写和 busy 清除。x0 结果是否仍握手释放、是否占用写口见 Q11。未 selected 的源不得清 valid 或标签。

WB 让拍会反压 MEM/EX/ID，必须保存所有 sideband，且 EX 同一 MDU 只能发起一次。MDU 结果等待不得经普通 pipelineHold 停住无关指令。具体 enable、响应保存与非整数 WB 的提交资格见 Q04/Q06/Q07；本稿不把一条新增全流水 hold 当作完整实现设计。

固定来源优先级只保证长延迟优先于 ALU，不自动保证低优先级来源不被无限高优先级流饿死。形式化进展的仲裁公平性与来源流量边界必须解 Q12，不能假设环境直接给每个 DUT 仲裁结果 ready 来掩盖内部饥饿。

## 6. 异常、中断、CSR、栅栏与 WFI

| 边界事件（N 拍） | N 拍组合副作用 | 周期末/后续拍 |
| --- | --- | --- |
| WB 同步异常 | 老 trap 优先；禁止年轻 EX 发起/提交、访存/sfence/flush/train 副作用；只 kill 年轻未提交 MDU | 清年轻预约/项；已提交后台 MDU 保持并继续写；handler 读 busy rd 被 ID 停住 |
| EX 分支重定向 | 取消比该分支年轻的 ID 预约/请求；不能以 redirect 全清单元 | 比分支老的 MEM/WB MDU 仍前进，已提交后台项存活；同拍 WB 老 trap 优先 |
| 中断挂起 | 停止新的正常 decode，排空流水未提交指令，后台结果仍参与写口 | 在 WB 指令边界接受；无需等后台 MDU。采用原空流水边界还是同拍退休立即接收见 Q06；旧 pipelineEmpty 中后台等待项必须与 FASE 语义分开（Q10） |
| CSR 位于 ID | any MDU busy 时等待，不发 CSR 执行；已有 csrUse/State/Reg hazard 继续生效 | 最后一项实际写回后按清位可见拍继续；等待只阻塞年轻 decode，不阻止老 WB 授权/结果写；阶段二/三再接入 FPR busy |
| FENCE | 本步维持现有访存语义，不接入未来 MSHR/pending-store 条件 | 不因整组 MDU busy 增加未规定的等待；未来 FENCE 经新 L1D 的合同不属于本步 |
| FENCE.I / SFENCE.VMA / satp / xRET | 保留已有 redirect/flush/CSR 特权语义；不能取消已提交后台 MDU | 清年轻未提交者；未来 L1D 排空/新 MMU idle/sfence 时序仅列接口依赖，不在本步改造 |
| WFI 到 WB | 按原有规则只退休一次、停止年轻发射；不要求后台 busy 全清 | 睡眠时后台 MDU 继续完成；唤醒不等于接受中断，保留原语义；睡眠是否影响时钟/写口见 Q14 |
| 阻塞访存/MMIO/FP 进行中 | 保留请求、结果与全流水等待方式；后台 MDU 不应丢失结果 | 其普通整数 WB 若被后台结果占口必须保留；已发访存不中断取消的现有路径保留；Q07 冻结响应/写口冲突 |

本步不能实现 D 第 7 节中新的 refill `hartFatal`：它是后续访存提交点变更的依赖。也不能因新 MMU 示例规格与旧 sfence 时序不同而在 T01 重做 MMU。

### 6.1 拍级正常例：独立 ALU 与 DIV

假设无访存/FP/CSR/redirect/写口冲突，DIV ready；PC `0x100: div x5,x1,x2`，`0x104: add x6,x3,x4`，`0x108: add x7,x5,x0`。

| 拍 | DIV | 独立 ADD | 依赖 ADD | x5 busy |
| --- | --- | --- | --- | --- |
| N | ID 离开，周期末预约 x5 | — | — | 本拍 0，下一拍 1 |
| N+1 | EX req.fire | ID 离开 | — | 1 |
| N+2 | MEM | EX | ID 因 rs1=x5 停住 | 1 |
| N+3 | WB commit，不等算完 | MEM | ID 等待 | 1 |
| N+4 | 后台运算 | WB 正常提交/写 x6 | ID 等待 | 1 |
| R（算完、获 grant） | 后台结果写 x5，ID 同拍旁路 | 已退休 | ID 仍按清位可见性等待 | 本拍 1，周期末清除 |
| R+1 | 无该在途项 | — | 可离开 ID，随后 EX | 0 |

这是无冲突的约束例，不规定 DIV 总接口延迟。若 R 与普通 WB 写同拍，普通 WB 在 R 让拍，R+1 是否继续遇新结果取决于仲裁；不能保证只停一拍。若依赖者清位同拍放行获批准，表中 R/R+1 需随 Q02 冻结更新。

### 6.2 精确异常例

已提交 DIV x5 仍在后台；更年轻 load 在 N 拍 WB 报错；其后 MUL x6 在 EX、ADD x7 在 ID。N 拍禁止 MUL 发起（若前拍已经发起则 kill 相应项）；周期末清 x6 预约，x5 不清，trap PC/cause/tval 来自 load。handler 访问 x5 要等待真正写回。若 x5 结果也在 N 到达，不允许 trap 取消它；是否 N 同拍写或下一拍写待 Q03/Q06，最终结果与 busy 清除必须一致。

## 7. MUL 单元

### 7.1 算术与流水契约

按 D 第 9 节：Chisel 65×65 有符号乘法，完整积 130 位，后接 **4 个寄存器边界**，允许 Vivado DSP 推断与寄存器重定时；无反压时 4 拍，II=1。DSP48E2 目标名称与 KCU105 实际器件/工具支持匹配 **未确认**，见 Q16；不得先偷偷替换 primitive 或把“约 16 DSP”当验收实测。

符号扩展沿用后端预处理：MUL/MULH 双有符号；MULHSU 仅 b 补零；MULHU 双补零；MULW 双操作数低 32 位符号扩展至 65 位，依据 `design/src/main/scala/backend/BreezeBackend.scala:887-903`。结果选择沿用 wrapper：低 64、高 `127:64`、W 低 32 符号扩展，依据 `design/src/main/scala/multiplier/RiscvMulUnit.scala:46-58`。旧 op 编码见 `design/src/main/scala/core/common.scala:105-113`；新接口是否直接复用编码待冻结接口表，不改变指令算术意义。

| 逻辑级 | 必需保存内容 | 无反压时的拍 |
| --- | --- | --- |
| P1 | 130 位积及 valid、op、rd、身份、提交/取消关联 | EX 在 N 接收，N+1 为 P1 |
| P2 | 上一级积/元数据，valid 与 kill 生效后的状态 | N+2 |
| P3 | 同上 | N+3 |
| P4 | 同上；按 op 选出的 64 位结果可在末级组合选择 | N+4 为算术完成 |

此表是 D“乘法后接 4 级寄存器”的逻辑展开，不规定 DSP 内物理切片、retiming 后位置或新增结果队列。committed 信息必须能够由 WB 身份更新对应 live 项；存于每级还是外部项表、同拍推进如何命中待 Q01/Q03。P4 未获接收必须保持，后续已经算完的项也不能覆盖它。全流水可停的 elastic 方案或信用/结果缓冲方案、容量和 ready 方程待 Q04；**不得额外指定一个结果 FIFO 深度**。

### 7.2 kill 与反压

- 对提交前被取消的每项，作废其当前或同拍推进目标级的 valid/所有权；取消多条年轻 MDU 时必须覆盖全部，不限“一条 kill”。已经提交的级不能作废。
- 标准结果保持：`result.valid && !result.ready` 的每个周期，payload（含身份/rd/op 所决定的 data）保持，直到接收或该未提交项被合法 kill。若只向外呈现已提交结果，则外部保持期间不存在合法 kill；具体可见性待 Q03。
- 当单元有容量、无依赖/资源冲突、写口持续可用时，独立连续 MUL 可每拍接收一项。反压任意长时有限容量不能持续接收，必须降低 req.ready 并保存所有已接收项；4 拍固定算术延迟与 stall 造成的接口可见延迟区分报告。
- 参照模型保留旧 `SignedMul65x65`（3 拍）并按身份/接收序列对齐比较，不能按新旧同周期 out_valid 比较。

## 8. DIV 单元

一次只接收一笔；已有 radix-4 的算术寄存器与迭代保留（位置见 1.3）。wrapper 至少需保存 live/committed/身份/rd、符号/word/remainder 信息、done/64 位 result。忙、已算完但等待提交/写回期间不能覆盖当前项；`req.ready` 只在可保存新操作时有效，释放与重新接收能否同拍待 Q03/Q04。

### 8.1 快速条件与值

使用有效 64 位操作数，W 操作先按符号属性扩展有效低 32 位。以下算术由已有 EX 表达式佐证（`design/src/main/scala/backend/BreezeBackend.scala:908-955`），也属于 T/D 要求，不是本轮新验证结果。

| 条件 | 商类 DIV/DIVU/DIVW/DIVUW | 余数类 REM/REMU/REMW/REMUW |
| --- | --- | --- |
| 有效 divisor==0 | 全 1；W 结果低 32 位全 1 后符号扩展 | 有效 dividend；W 取低 32 位后符号扩展 |
| 有符号有效 min / -1，64 位 min=`0x8000000000000000`，W min=`0x80000000` | min，W 最终 `0xffffffff80000000` | 0 |
| 其他 | unsigned magnitude 迭代、按符号恢复、W 最终符号扩展 | 同左 |

快速路径 EX 在 N 接收后，N+1 算术结果可保存（1 拍），但 N+1 不能绕过 WB 授权写架构寄存器。快速路径与迭代路径都必须支持提交前 kill、提交后存活和 result backpressure；不能保留旧 `divFastCompletion` 绕过仲裁直接普通 WB 写的办法。现有 unsigned 核还有 0 dividend、a<b、a==b 的短路径（`design/src/main/scala/divider/UnsignedRadix4Divider.scala:72-89`），保留其算术；它们不替代有符号 overflow 判断。

### 8.2 延迟边界

D 第 9 节的“最坏 32 拍”与现有核源码注释的“32 iterations”需要区分：源码在接收时初始化，再于 busy 周期两位商迭代（`design/src/main/scala/divider/UnsignedRadix4Divider.scala:6-11,68-98`）。新接口端到端 req.fire→done→写回上界 **未确认**（Q05），不能把迭代次数直接作为含提交/反压的写回时限。必须单独报告算术完成与实际写回延迟。

## 9. 必须保留的现有行为

此表是保留/回归义务，**不是本轮运行通过的结论**。

| 项目 | 源码或 bug 证据 | 保留要求 |
| --- | --- | --- |
| CORE-001 | `docs/bugs/CORE-001.md`；`design/src/main/scala/backend/BreezeBackend.scala:727-728,1063-1068,1346-1352,1561-1569` | memory 请求当拍持有、等待期间上下文保留、一次 WB/退休、正确扩展 |
| CORE-002 | `docs/bugs/CORE-002.md`；回归 `design/src/test/scala/core/breezecoreSpec.scala:1761-1918` | branch 重定向仍正确，目标 miss 恢复不沿用旧请求/行；本步不改 frontend/cache |
| CORE-003 | `docs/bugs/CORE-003.md:7-11` 仍记 open；`design/src/main/scala/backend/BreezeBackend.scala:1015-1038` 有保守 CSR 源冒险；回归 `design/src/test/scala/core/breezecoreSpec.scala:2299-2667` | 保留 CSR 读值/状态规则；当前是否已修复 **未确认**，阶段二先测基线（Q17），不能改 handler 为 workaround |
| CORE-004 | `docs/bugs/CORE-004.md`；`design/src/main/scala/backend/BreezeBackend.scala:544-554,633-663`；回归 `design/src/test/scala/core/breezecoreSpec.scala:1157-1191` | held EX 不用旧 load 值解析/训练；新 hold 也要保护真正 EX advance |
| CSR 旁路/合法性/状态 | `design/src/main/scala/backend/BreezeBackend.scala:979-1038,1070-1079,1253-1259,1449-1450` | rd 与别名/隐式状态冒险保留；合法性与原条指令对齐；CSR 执行等待 busy 清空 |
| trap 分类与优先级 | `design/src/main/scala/backend/BreezeBackend.scala:555-601,1080-1126,1507-1516,1656-1678` | PC/cause/tval、AMO Store 类 fault、取指第二 parcel fault 保留；老重定向赢且禁止同拍年轻副作用 |
| FENCE.I | `design/src/main/scala/backend/BreezeBackend.scala:1156-1165,1431-1434,1653-1668` | 请求/完成、cacheFlush/顺序 PC、一条只退休一次 |
| SFENCE.VMA/satp/xRET | `design/src/main/scala/backend/BreezeBackend.scala:581-601,1642-1647,1659-1668` | 原 sfence 操作数/ASID、satp 次条 PC、xret 特权/target 与优先级；不改 MMU |
| 中断与 WFI | `design/src/main/scala/backend/BreezeBackend.scala:561-601,1120-1126,1610-1615` | 唤醒/陷入分离、WFI 一次退休、interrupt boundary 保留，按设计只放宽后台等待 |
| RV64A / reservation | `design/src/main/scala/backend/BreezeBackend.scala:704-725,815-823,1628-1641` | 阻塞单访存 aq/rl、SC 0/1、trap reservationKill 保留 |
| FPU 本步阻塞/fflags | `design/src/main/scala/backend/BreezeBackend.scala:753-771,1327-1344,1571-1608` | rm、FPR/GPR 输出与 flags 保留；不把 flags 搬成后台方案 |
| FASE | `design/src/main/scala/backend/BreezeBackend.scala:48-50,1679-1732` | flightEvents、empty、注入、寄存器读写、进入断言保留；新后台 empty/共享写口缺口必须解 Q10 |
| tandem | `design/src/main/scala/backend/BreezeBackend.scala:1304-1324,1471-1485,1538-1559,1735-1737`；字段 `design/src/main/scala/interface/interface.scala:246-262` | 顺序提交追踪继续，一项只一次；MDU 值晚到要能关联原提交后比对，现有字段如何满足待 Q18 |
| HPM | `design/src/main/scala/core/BreezePerformanceCounters.scala:29-86`；`design/src/main/scala/core/RegFile.scala:216-227` | 8 个计数器/selector、inhibit、同拍 CSR 写优先与旧 selector/inhibit 采样行为保留；MDU 写回不额外 instret |
| debug/ESTOP | `design/src/main/scala/backend/BreezeBackend.scala:170,1734,1739-1779` | 原调试可观测语义及一次 ESTOP；有后台项时 ESTOP/测试退出是否排空待 Q14/Q18 |

## 10. 仿真断言与形式化性质

### 10.1 RTL 仿真断言（冻结后全部实现）

| ID | 断言 |
| --- | --- |
| S01 | x0 不 busy、不物理写；每个非零 rd 至多一位预约所有者 |
| S02 | busy 等价于 ID 已预约、未 kill/写回的同 rd MDU；不忽略 EX 尚未接收者 |
| S03 | sbRaw/sbWaw 阻塞时无年轻 idLeave；未离开 ID 不置位 |
| S04 | 每项至多一次 req.fire、一次 commit 或 kill；resolve 命中有效同身份项；不能 commit 与 kill 同时授权 |
| S05 | 未提交或已 kill 项永不实际 resultWrite；已提交项不被普通 redirect 清除 |
| S06 | 整数 RF 写口 grant 独热，长延迟每拍最多一笔；不能把多源 valid 独热当要求 |
| S07 | 结果 valid && !ready 时保持全部 payload（取消例外由 Q03 冻结）；未选来源 ready=0 |
| S08 | resultWrite、RF 数据/标签、记分板 clear、ID 旁路一致，写回不触发第二次 retire |
| S09 | 普通 WB 因写口冲突保持元数据且不 retire/CSR commit/改 architecturalNextPc；释放后仅一次提交 |
| S10 | kill 只清对应未提交项，不能误清其他 rd 或复用同 rd 的新身份；多项 kill 覆盖完整 |
| S11 | MUL 每级 valid/身份/op/数据对齐；无 stall 的算术完成间隔/延迟为 II=1/4 拍 |
| S12 | DIV 已占用或结果保持时不得覆盖输入；特殊算术结果 1 拍可用并受 WB 授权 |
| S13 | WB 老重定向抑制所有年轻同拍副作用；EX branch 只在实际推进时解析/训练 |
| S14 | CSR 执行前整数 busy 为空；WFI/中断不要求后台为空；FASE 排空规则按 Q10 冻结 |
| S15 | 访存/FP 单次脉冲响应不能因 WB 让拍丢失/重复；现有断言保留或按 Q15 批准变更 |
| S16 | 三个 HPM 事件与实际发生的 stall/conflict 对齐；一周期不因重复条件累计多次同事件 |

### 10.2 模块级形式化矩阵

对象：记分板、整数写口仲裁、MDU 接口/生命周期；不把本步模块证明扩大为全核精确异常、算术或 FPGA 证明。wrapper 与真实数据通路的绑定另做仿真等价性；若形式化抽象算术，必须报告替换边界。

| ID | 性质 / assert 或 cover | 环境义务 / 证明模式 |
| --- | --- | --- |
| F01 | 对每个 r≠0，所有者数量≤1（assert） | 合法 decode 类型；DUT 自己执行 rd 检查，不能 assume 无 WAW；BMC+归纳 |
| F02 | busy[r] iff 存在对应预约到终结的 MDU（assert） | ghost 台账独立计数 ID leave/kill/write；BMC+归纳 |
| F03 | 被 kill 身份永不物理写回，包括 kill 后复用 rd 的晚结果（assert） | 合法 resolve 年龄/身份；不能 assume kill 时 FU 无结果；BMC+归纳 |
| F04 | 每拍至多一个长延迟 grant/write，普通写与后台写互斥（assert） | 多源同时 valid 合法；BMC+归纳 |
| F05 | 被反压结果的 valid/全部 payload 保持至接收（assert） | 取消边界按 Q03；不用 assume DUT 自己保持；BMC+归纳 |
| F06 | 每个已提交非零 rd 的结果最终恰好一次写回（assert 活性） | 算术在 Ldone 内完成、外部物理写口在 Lport 内可用；内部来源仲裁公平性必须解 Q12；先有界活性，另报无界/归纳结果 |
| F07 | 无 commit 的写禁止、commit 不清 busy，已提交项 redirect 后存活（assert） | 年龄合法，含 trap 与结果同拍；BMC+归纳 |
| F08 | req 接收到终结的计数守恒，无丢失/重复；容量不溢出（assert） | 容量 Q04 冻结后绑定，输入遵守 ready；BMC+归纳 |
| F09 | WB stall 时 payload 不变、普通指令只提交一次（assert） | 接入流水 enable 后；BMC+归纳 |
| F10 | ID RAW/WAW、CSR busy 阻塞正确且不阻止老 MDU 前进（assert） | 无永久外部 hold 的进展假设需显式记录；BMC+归纳/有界活性 |
| F11 | commit/kill/推进/结果同边界遵守冻结的优先级（assert） | Q02/Q03 冻结后写精确公式；BMC+归纳 |
| F12 | reset 后空、x0 行为及睡眠写口符合冻结合同（assert） | 复位/时钟 Q14；BMC+归纳 |
| F13 | 连续 4 拍及以上 MUL 接收、DIV/MUL 同时 ready、WAW stall 后释放、早 done 等 commit、trap 后后台写（cover） | 允许竞争/kill/反压，记录 witness；cover 不是活性证明 |
| F14 | kill 后立刻复用 rd、多个年轻 kill、普通 WB 多拍让口、CSR 等待释放（cover） | 同上；检查 assume/reset 不使场景不可达 |

BMC 深度、引擎、Ldone/Lport 数值在接口/容量冻结后制定并固定，当前 **未确认，未运行**；阶段二分别报告实际深度、SAT/UNSAT、归纳是否完成、cover witness、timeout/unknown/未完成项。不能用一次有界 pass 表述无界证明；不得减深度或添加排除合法竞争的 assume 取得通过。

## 11. 计数事件与接入

依据 [observability-design.md 2.6](observability-design.md#26-后端每核) 与 T 第 1 节，本步先接现有 HPM，不新建 MMIO/JTAG 计数阵列。

| 事件 | 已确定的计数对象 | 未覆盖的细节 |
| --- | --- | --- |
| `sb_stall_mul` | ID 被 MUL 来源 busy 的 rs/rd 依赖停顿的拍数 | 多来源同时阻塞、CSR 等全空、fetch invalid、其他 hold 并存时是否计数 Q13 |
| `sb_stall_div` | 同上，来源为 DIV | 同上；不把 DIV FU ready=0 的纯结构停顿自动算记分板停顿 |
| `wb_port_conflict` | 后台结果实际占整数写口，普通 WB 整数写因此让拍的次数/拍数 | x0、trap/非法指令不构成正常待写；普通 WB 资格 Q06/Q11 冻结后定事件表达式 |

现有事件 ID 0–10 定义在 `design/src/main/scala/core/common.scala:337-349`；`BreezeHpmEvents` 当前只有十个字段（`design/src/main/scala/interface/interface.scala:340-351`），性能模块 selector 位宽/合法上界/表使用 LOAD_USE_STALL（`design/src/main/scala/core/BreezePerformanceCounters.scala:13,49-64`）。三个新 ID 及是否扩公共 Bundle/改用局部事件端口待 Q09/Q13，不擅自编号 11/12/13。

接入后必须同时更新合法 selector 上界、表映射和相关测试驱动；保留 `counters+pending` 可见值、计数器 CSR 写优先、同拍采用旧 selector/inhibit（`design/src/main/scala/core/BreezePerformanceCounters.scala:65-80`）。实际退休信号必须每条一次，后台 resultWrite 不增加 minstret/coreinst（`design/src/main/scala/core/BreezePerformanceCounters.scala:38-44`）。

## 12. 未决问题（冻结前逐项处理）

所有项当前均为 **未决定/未确认**；正文有依据的合同继续有效，但不得据此越过冻结门槛。表中“需决定”不代表推荐某个候选值。

| ID | 来源/缺口与需决定事项 | 对实现/验证的影响 |
| --- | --- | --- |
| Q01 | D 4/5/8/9 只给 rd 标签和 commit/kill。事务身份用 rd、流水位置、slot 还是序号/epoch？宽度、复用条件、多个年轻 kill 的表示、ID 预约→EX→WB→FU 匹配如何规定？ | 晚结果不能命中新指令；MUL 多在途和 x0 不能依赖一个 busy 位定位 |
| Q02 | D 5 写同拍旁路、下一拍前进，未给 clear+set 优先级。释放 rd 当拍是否允许同 rd 新 ID leave？set/clear/kill 多事件的逐位 next-state、旁路与 ready 优先级是什么？ | 位不丢、WAW 不漏，精确到拍的 scoreboard/ID 方程 |
| Q03 | D 4/8 未规定 resolve 是脉冲还是 ready 握手、取消数量/确认，done 早于 commit 时 valid 可见性。commit+done、kill+done、kill+推进、reset 同拍如何排序？携取指/非法异常的指令是否预约/发起，自身 WB trap 的预约如何释放？ | 不能把计算完成当授权；稳定性断言的合法取消例外和 same-edge 测试，异常项不能遗留 busy |
| Q04 | D 9 要求 4 拍/II=1 且结果保持，未给反压容量。MUL elastic/信用/结果缓冲结构、容量/ready、EX 一次发起标志、WB 让拍引起的各级 enable/旁路保持如何规定？ | 连续 MUL 与任意反压不丢结果、不重复发起；不得自行给 FIFO 深度 |
| Q05 | T 要补 DIV 快路径，但基线已有 EX→MEM 路径（1.3）；改造后 fast 检测/寄存器归属、原始输入还是幅值接口？32 拍指迭代还是接口完成？释放与再接收可否同拍？ | DIV wrapper 寄存器/FSM、直接单元特殊值测试及活性边界 |
| Q06 | D 6 仅说“WB 中要写寄存器的普通指令让拍”。无 GPR 写的 store/branch/CSR rd=x0/FPR 写、MDU 自身 WB commit、trap/xRET/WFI 与后台写同拍能否继续？中断接受仍等未提交流水全空还是允许 WB 退休同拍？ | commit/retire/CSR/PC/redirect 统一脉冲；不能重复或错误屏蔽副作用 |
| Q07 | 现有 memory completion/FP completion 与 WB 使用脉冲和共同寄存器（1.1/1.2），新 WB 反压时如何存响应、使 FPU outReady、保持 FPR/fflags sideband？ | 不改 FPU 内部/访存协议也不能丢完成；需要批准保存位置/容量 |
| Q08 | D 5 的真实 GPR 源/目的与 FP 跨 bank 规则需译码名单；本步哪些 FP/local/CSR/原子写检查 rd、FP usesGpr1 如何接？新长延迟旁路是否供 held EX，普通旁路优先级如何合并？ | RAW/WAW 覆盖完整，f0 与 x0 不混淆；未审的 decoder 映射不推测 |
| Q09 | T 3.1 不含 `interface/`，现有 HPM/流水/trace Bundle 在该目录。是否允许修改指定公共文件，或批准后端局部侧带/事件端口？旧 MDU 数据字段/等待诊断字段具体保留/删除名单是什么？ | 模块/文件边界和编码，避免扩大任务范围/误删 FP 的 MUL selector |
| Q10 | D 未覆盖 FASE，基线 `pipelineEmpty` 含 MDU 等待，`f.empty` 与 RF override 共用（`design/src/main/scala/backend/BreezeBackend.scala:596-599,1706-1724`）。FASE enter/empty/launch/寄存器写/flightEvents 如何对待已提交后台项？ | 调试读不能见未完成值，调试写不能覆老结果；中断排空条件不能直接复用调试 empty |
| Q11 | D 5 只管理 x1–31。rd=x0 的 MDU 是否仍执行/提交并释放结果？是否占用 write grant/冲突计数？多笔 x0 如何唯一匹配、取消？ | 无 busy 的操作仍可能占 FU/身份资源；测试/形式化必须覆盖 |
| Q12 | D 6 给默认顺序，D 12 仍待拍板。确认 DIV/MUL 的固定顺序或公平仲裁及扩展来源顺序；如何保证低优先级来源进展？ | 必须满足 T 活性，不能 assume 掉无限合法高优先级流量 |
| Q13 | observability 2.6 未给重叠停顿归因/ID 与编码。两个来源同时依赖、CSR 等空、其他 hold、fetch invalid 的计数口径；新 ID/最大 selector 与来源元数据布局如何定？ | 性能计数可解释、CSR ABI 与现有 HPM 行为兼容 |
| Q14 | 设计未细化复位、睡眠时钟、ESTOP/程序结束时后台排空。全局 reset 是否取消所有项、复位后的架构初值边界；WFI 睡眠仍有写口/时钟保证；退出何时可停止观测？ | reset/活性假设、后台完成与测量末尾不能丢最后结果 |
| Q15 | T 0.2 禁止自改验收；旧 `PopCount(completion)<=1`（1.2）、MUL wrapper 3 拍测试/全 flush、后端 WB 同拍结果观察、HPM 非法 selector=11 检查（testplan 1 节）可能与新合同/新 ID 冲突。需逐条批准改为等强的新检查，哪些旧原样保留？ | 不能删/放宽断言或把已有失败掩盖为新测试通过 |
| Q16 | D 9 指定 DSP48E2，而 D 的目标为 KU040。目标器件对应 DSP 类型及 Vivado 推断/retiming 支持未核验；允许使用哪种器件 DSP、综合顶层/配置/约束/retiming 设置？ | 不擅自改技术目标；4 拍与 DSP/LUT/WNS 需要匹配同配置实测 |
| Q17 | T 要保留 CORE-003 修复，但 bug 文档仍 open（9 节）；新旧硬件完整基线 suite 数量/失败情况未运行。冻结保留行为需以哪条基线证据认定？若基线失败如何处置？ | 不把历史 bug 记载/源码保守逻辑当当前通过；先报告，不能降低全回归验收 |
| Q18 | D 11 要提交顺序比对、迟到后比寄存器；现有 TracePayload 只一拍携 rdData（9 节）。迟到值与原提交的关联/trace 缓冲或额外完成事件、参考模型状态可见点、FASE 退休数据如何定？tandem 完整 checker/可复用执行入口未确认。 | 不把“有退休 log”当逐条参考模型通过；可能需超出 T 3.1 的文件变更 |

不额外引入硬件技能。本阶段已用 `breeze-spec-verification`、`spec-to-testplan`、`breeze-microarchitecture-review`、`gf-formal` 做文档审查/追踪/性质清单，未启动其 RTL 或验证流程。
