# Breeze 后端执行流水与记分板设计

本文记录新版 Breeze v1 后端的级划分、提交点、记分板、写口仲裁以及与 L1D、乘除法单元（MDU）、FPU 的接口边界，供微架构审阅。审阅固定后再单独编写 RTL 级 spec；本文不定义 Bundle 字段和逐寄存器状态机，不代表新版已经实现或验证。

修订：2026-10-06 B01；2026-10-08 SOC-3b 用户裁定新增 W2/lateReg，普通物理写与 WB 提交分离。当前拍号/结构门槛以 [`backend-timing-contract.md`](backend-timing-contract.md) 为准，RTL 规则见 [`backend-v1-rtl-spec.md`](backend-v1-rtl-spec.md)；本轮规格更新不代表实现/验证完成。

输入：L1D 与 L2 的设计见 [`dcache-pipeline-design.md`](dcache-pipeline-design.md)，其中 2.3 节的提交点和 2.9 节的判定/迟到数据接口是本文的直接依据；地址翻译沿用 [`breeze-mmu-rtl-spec.md`](breeze-mmu-rtl-spec.md)。MDU、FPU 的现有接口不构成约束，按本文需要修改。

## 1. 现状

现有后端（`design/src/main/scala/backend/BreezeBackend.scala`）是 4 级顺序单发射：ID/RR（译码与读寄存器同拍）、EXE、MEM、WB。分支在 EXE 解析。

瓶颈在长延迟操作的处理方式：

```scala
pipelineHold := memReqIssued || (memWaitingRespReg && !memRspFire) ||
    mulReqIssued || (mulWaitingRespReg && !mulRspFire) ||
    divReqIssued || (divWaitingRespReg && !divRspFire) ||
    fpReqIssued || (fpWaitingRespReg && !fpRspFire) || fenceiPending
```

每一次访存、乘法、除法、浮点运算都会停住整条流水，直到结果返回；与它无关的年轻指令也只能等待。

## 2. 目标与非目标

**目标：**保持顺序单发射、顺序提交和精确异常，让**已提交的长延迟操作在后台完成**，与它无关的年轻指令继续执行。

**非目标（v1 不做）：**双发射、乱序执行、寄存器重命名、分支预测改进（前端另行处理）。这些在记分板方案落地、测出新瓶颈后再评估。

## 3. 级划分

保持 ID/EX/MEM/WB 四级顺序执行/提交，访存仍对齐 L1D S0/S1/S2；仅增加已提交普通写级 W2 和单项迟到结果 lateReg：

| 级 | 职责 | L1D 对应级 |
| --- | --- | --- |
| ID/RR | 译码、读寄存器、记分板检查、旁路选择 | — |
| EX | ALU、分支解析与重定向、地址加法；访存请求以加法结果发给 L1D；MDU/FPU 接收操作 | S0 |
| MEM | 普通指令传递结果；访存等待 L1D | S1 |
| WB | **提交点**：处理异常、中断和 CSR；访存接收 S2 判定；普通 RF 结果捕获到 W2 | S2 |
| W2 | 已提交普通 GPR/FPR 写无条件完成，优先取得 RF 写口并穿透 ID；不置/清 busy，不受 fatal/stop/kill/redirect/hold 屏蔽 | — |

**EX 兼做 AGU**（与 Rocket 相同）：EX 的加法器计算 `rs1 + imm`，结果在同拍送入 L1D 的 S0。L1D 的 set index 和 S0 冲突比较只用地址低 12 位，进位链短；完整 64 bit 地址送入 dTLB，按 MMU spec 下一拍出结果。

时序风险在 S0 冲突比较产生的停顿信号：它要反压 EX 与前端，扇出大。v1 先按同拍停顿实现；若综合不满足，退路是把冲突比较结果寄存一拍，在 S1 kill 该 Load 并于下一拍从 S0 重发，多花 1 拍但切断长路径，不需要增加独立 AGU 级。

**停顿方向规则（B01）：**EX/MEM/WB 与 S0/S1/S2 锁步，L1D 没有来自后端的保持输入。因此：

- 后端自己发起的停顿只能落在 ID（不发射）或 EX（不 fire 请求，MEM 插气泡）；
- MEM、WB 只在 L1D `s2Hold` 时保持（此时 L1D 的 S1/S2 同样保持），或在 WB 中是串行指令且其后各级都是气泡时保持（第 7 节）；
- 后端不得因写口、记分板或任何后端内部条件让 MEM/WB 保持。

Load 命中数据在 E+2 的 WB 得到并提交，E+3 的 W2 写 RF；依赖者在 E+3 经写穿透离开 ID、E+4 执行，load-use 3 拍气泡。不相关指令仍无气泡，S2 组合 load bypass/T02b 作废。

## 4. 提交点

**每条指令都在 WB 按程序顺序提交。**提交的含义是：该指令确定不会再产生精确异常，它对架构状态的效果不可撤销。长延迟指令提交后，结果可以稍后到达。

| 指令类别 | 何时可以提交 | 结果 |
| --- | --- | --- |
| ALU、分支、跳转 | 到达 WB 且无异常 | 有普通寄存器结果时下一拍 W2 写回；分支无普通目的不占口 |
| MUL、DIV | 到达 WB（不会产生异常） | 单元算完后经长延迟写口写回 |
| 浮点运算 | 到达 WB（不产生陷入，只累积 fflags） | 单元算完后写回，同时累积 fflags |
| Load / 浮点 Load | S2 判定命中完成或进入 MSHR | 命中下一拍 W2 写回；Mshr 提交置 busy，迟到数据接收进 lateReg 后获准写回 |
| Store / 浮点 Store | 收到 L1D 的 S2 判定：命中完成或进入 MSHR | 不写寄存器 |
| 不带 aq/rl 的 LR、SC | LR 命中 E/M 与 SC 在 S2 判定；LR miss 等回放完成 | 实际 Done 后 WB 提交、下一拍 W2 写回（SC 写 0/1）；LR miss 不提前提交 |
| AMO、带 aq/rl 的 LR/SC、uncached/MMIO | 收到实际结果 | WB 等实际结果后提交，有寄存器结果则下一拍 W2 写回；不提前提交 |
| CSR、FENCE、FENCE.I、SFENCE.VMA、WFI | 满足第 7 节的等待条件后 | CSR、WFI 按原有语义；FENCE、FENCE.I、SFENCE.VMA 见第 7 节 |

L1D 暂不判定（TLB 等待、与 MSHR 同行、MSHR 已满等）时，访存指令停在 WB，比它年轻的指令随之停住。

MDU 和 FPU 在 EX 接收操作并开始计算，但结果在该指令提交前**不得写回**；若该指令在提交前被 kill（更老指令异常、分支重定向、中断），单元丢弃这次操作。也就是说单元接口需要：EX 拍的发起、WB 拍的提交或 kill，以及结果的 valid/ready 保持（第 8 节）。

## 5. 记分板

整数寄存器（x1–x31）和浮点寄存器（f0–f31）各一组记分板，每个寄存器 1 bit，表示“有一条长延迟写尚未写回”。

- **两种在途写，分开跟踪：**
  - **未提交的长延迟指令**（还在 EX、MEM、WB 中）：由现有的级间冒险检查覆盖。ID 的源寄存器或 rd 与这些级中长延迟指令的 rd 相同时停住，不用记分板，也不需要旁路。
  - **已提交、结果未写回的长延迟操作**：由记分板覆盖。
- **置位：**仅已提交但还需后台结果的 MUL/DIV/FPU、Load/FP Load miss 在 WB 置 busy；普通 ALU、命中 Load、实际 Done 的 LR/SC/AMO/MMIO 和本地 FMV 走 W2，不置 busy。FPU 跨 bank 按真实目的 bank 置位；本地 FMV 不分配 FPU 项。
- **清除：**lateReg/DIV/MUL/FPU 实际获准完成时清对应 busy；错误 lateReg 也清位、不写坏数据。W2 不置、不清 busy；kill 永远不清 busy。
- **ID 级检查：**真实源/目的命中有效 busy 或受保护的 EX/MEM/WB 生产者则停住；WB 有寄存器结果的访存仅看寄存 valid/类别/writes/bank/rd，不看 resp.kind。ID 不单独比较 W2，在 W2 写穿透拍可离开；FPR 普通依赖同样保护。
- **旁路：**后台结果实际写回拍经 RF 写穿透供 ID；普通 MEM/WB→EX 保留并增加 W2→EX，年龄优先级 MEM>WB>W2>捕获值，held EX 继续捕获。WB 只旁路非访存普通结果、只看寄存资格，不用 wbOrdinary/wbCommit/wbDone；不加后台→EX 数据旁路。
- **单元侧的提交与 kill：**长延迟单元中未提交的项至多是 EX、MEM、WB 中的那几条，提交顺序与发射顺序相同。所以后端只需要两个脉冲：WB 提交一条长延迟指令时，向对应单元发“提交最老的未提交项”；WB 发起 kill（异常、xRET、FENCE.I、中断等）时，向所有单元发“作废全部未提交项”；EX 发起的分支重定向不发 kill，只抑制同拍的发射，因为比分支年轻的指令还没发射。不需要事务标签，结果只带 rd。

由于 rd 检查覆盖记分板和各级，同一寄存器任意时刻至多有一条长延迟写在途，同一 rd 的置位与清除不会在同一拍发生。

## 6. 写口仲裁

整数和浮点寄存器堆各一个写口。写口来源为普通 W2 及后台 lateReg/DIV/MUL/FPU；L1D late 仅被单项 lateReg 接收。ready=!lateReg.valid||lateRegGrant，空槽不穿透 RF，同拍出入使用旧项完成、新项捕获。

- **W2 普通写优先且无条件完成**。普通指令 WB 提交、拍末捕获 W2，下一拍 W2 优先写其 bank；同 bank 后台保持，不同 bank 可并行。WB 不因写口保持；W2 每拍消费一次，WB 无新普通提交则下一拍 W2 valid 清零，不随 WB hold 重复写，复位清 valid。
- **饥饿保护在 ID。**每堆有后台 valid 而无后台 grant 时计数 +1、饱和于 3，否则清零；拍初为 3 且无在途保护气泡时 ID 停发一拍，后台 grant 清在途标记。计数只看 lateReg/DIV/MUL/FPU，L1D late 接收/背压不计数。T21 在 c 接收后从 c+1 计冲突，c+4 插 ID 气泡、c+8 到 W2 让口，共 7 次冲突；阈值不改为 2。
- 后台全局一拍至多一个 grant，固定优先级 lateReg>DIV>MUL>FPU；T13 的四路同拍 valid 指写口来源，原始 L1D late 要提前一拍接收。
- fflags 在浮点结果写回时按位或累积，与写回顺序无关。

## 7. 异常、中断、CSR 与栅栏

- **精确异常：**任何指令在 WB 发现异常时，kill EX、MEM 中的年轻指令及其已发起但未提交的 MDU/FPU 操作（单元作废全部未提交项，记分板不变）。已经提交、仍在后台的长延迟操作照常完成并写回。陷入处理程序如果读这些寄存器，会被记分板停住。
- **中断：**在 WB 的指令边界接受，不需要等后台长延迟操作完成。现有“中断挂起时停止发射、排空流水”的做法放宽为只等流水中未提交的指令。
- **refill 错误：**错误 late 在 N 接收，fatal 在 N+1 的 lateReg 可见拍生效并保持，允许 N 拍可能多提交一条普通指令；hartFatal 仅由 fatal 寄存器/lateReg.valid&&error 驱动。错误完成清 busy、不写坏数据、不产生精确陷入；已提交 W2 无条件完成，后台继续。
- **CSR 指令：**v1 保守处理，等两组记分板全部清空后再执行。fflags/fcsr 的读写因此必然看到所有已提交浮点运算的结果；frm 的修改也不会影响已在途的浮点运算（这些运算在 EX 已带走舍入模式）。
- **FENCE：**作为请求发给 L1D，L1D 在 MSHR 与 pending-store 均为空时完成，保证已提交但仍在 MSHR 中的访存先于 FENCE 后的访存生效。
- **FENCE.I、SFENCE.VMA：**v1 改在 WB 串行执行（[`v1-integration-notes.md`](v1-integration-notes.md) 第 3 节，覆盖旧语义）。FENCE.I：等 L1D 的 MSHR 与 pending-store 为空 → 清 L1I → 重定向到下一条；L1D 不写回、不失效，删除 `dcacheFlushReq/Done`。SFENCE.VMA：更老指令按序提交、前端 kill（已提交后台结果可继续） → 等 MSHR 与 pending-store 为空 → 等 MMU `idle` → 一拍 sfence → 等 `idle` → 重定向到下一条（MMU 合同 C4；MMU spec 所称 store buffer 排空即 MSHR 与 pending-store 为空）。两者在 WB 重定向时作废年轻未提交项（第 5 节）。**串行发射（B01）：**FENCE.I、SFENCE.VMA、WFI、ESTOP 这类会在 WB 因后端条件等待的指令，离开 ID 后 ID 不再发射年轻指令，直到它离开 WB；于是它在 WB 等待时 EX/MEM 只有气泡、L1D 中没有其后的 CPU 请求，WB 保持不会与 S1/S2 错位。FENCE.I 与 SFENCE.VMA 本来就重定向到下一条，停发没有代价。T01 的 A02（FENCE.I 在 MEM、不发 kill）只适用于旧阻塞访存路径。
- **AMO、带 aq/rl 的 LR/SC、MMIO：**由 L1D 保证在 MSHR 与 pending-store 为空后执行；后端在 WB 等结果。不带 aq/rl 的 LR/SC 在流水中执行（D-cache 文档 2.7 节）。
- **MMIO 与中断：**MMIO 一旦发出就不能取消，中断等它退休后再接受（D-cache 文档 2.10 节）。
- **WFI：**不需要等后台长延迟操作。

## 8. 接口变更

| 接口 | 变更 |
| --- | --- |
| 后端 ↔ L1D | 请求在 EX 发出；L1D 在 S2 按程序顺序给出一次判定（命中完成、进入 MSHR、异常，或暂不判定）；进入 MSHR 的 Load 稍后给出迟到数据（目的寄存器标签、数据、错误标记，valid/ready）；FENCE 作为请求发给 L1D。见 D-cache 文档 2.9 节 |
| 后端 ↔ MDU | 发起（EX：操作数、操作类型、目的寄存器标签）；提交/kill（WB）；结果（valid/ready，带标签）。单元在结果未被接收前保持结果 |
| 后端 ↔ FPU | 同 MDU，另带舍入模式（EX 拍确定）、结果写整数还是浮点寄存器、fflags |
| 前端 ↔ 后端 | 不变 |

MDU、FPU 只要求在一笔操作未完成时能拒绝新操作（ready 为低）；单元内部是否流水化不影响后端结构。

## 9. 乘除法单元

**现状：**乘法器 `SignedMul65x65`（Booth 编码 + Dadda 压缩树）本身已是 3 拍、每拍可发一个的流水结构；慢的原因是后端每次乘法都停住整条流水，这一点由第 5 节的记分板解决。它的问题是**面积**：单核实测 6.8k LUT，未使用任何 DSP。除法器 `UnsignedRadix4Divider` 每拍算 2 位商，按前导 1 对齐、小操作数提前结束，最坏 32 拍，1.35k LUT。

| 单元 | v1 方案 | 延迟 / 吞吐 | 资源 |
| --- | --- | --- | --- |
| 乘法 | 当前保持 SOC-3 M2 的显式 DSP A/B→M→P 寄存与 fabric 最终结果级；MUL/MULH/MULHSU/MULHU/MULW 语义及 kill/背压协议不变，不依赖自动 retiming | 4 拍，每拍可发一个（无出口背压） | 以匹配源版本的实际综合/routed 报告为准，SOC-3b 不改 MulUnit |
| 除法 | 保留现有 radix-4 除法器；补充除数为 0 和有符号溢出（`-2^63 / -1`）的 1 拍快速结果 | 数据相关，最坏 32 拍；一次一笔 | 不变 |

两者都按第 8 节改为“EX 发起、WB 提交或 kill、结果保持到被接收”的接口；乘法器流水中可以同时有多笔，每笔带目的寄存器标签，kill 时作废对应级。以后若除法成为时序瓶颈，再换成 SRT radix-4。

## 10. 实施顺序

每一步都能独立验证，前一步通过后再做下一步：

1. **MDU 进记分板。**在现有 D-cache 上实现记分板、长延迟写口仲裁和 rd 检查，改造 MDU 接口，同时把乘法器换成 DSP 实现、除法器补快速路径（第 9 节）。访存仍沿用现有阻塞方式。验证：除法/乘法密集程序的结果与性能，kill 与异常时 MDU 操作的丢弃。
2. **FPU 进记分板。**加浮点记分板、fflags 与 CSR 规则、整数/浮点交叉写回，改造 FPU 接口。
3. **访存对齐新 L1D。**随新 L1D 一起实现：EX/MEM/WB 与 S0/S1/S2 对齐，Load miss 进记分板，迟到数据经写口仲裁写回。旧 D-cache 是单拍脉冲接口加阻塞，无法提供 S2 判定，所以这一步不在旧 D-cache 上做。

## 11. 验证重点

- 与现有 tandem/参考模型的逐条退休比对，覆盖长延迟结果乱序写回的情况：比对按提交顺序，寄存器值在结果写回后比对。
- 定向用例：RAW/WAW；kill 不清已提交 busy；后台与 W2 普通写冲突；lateReg 同拍出入；W2 遇 fatal/stop/kill/redirect/hold 完成且仅一次；MEM/WB/W2 旁路与 held EX；CSR raw busy；栅栏条件不新增等待；fatal N+1。
- 随机程序：长延迟指令与 ALU、分支、异常、中断随机交织，并对 L1D/MDU/FPU 的响应延迟随机化。
- 性能计数器：记分板停顿（按来源）、写口冲突停顿、load-use 停顿、L1D 暂不判定停顿。

## 12. 已定边界与后续候选

本表中后续候选不授权 SOC-3b 修改冻结拍数或协议；遇到需要采用候选的路径须停止报告。已定 load-use、写口优先级与 CSR raw busy 规则按当前时序合同执行。

| 项目 | 已确定的边界 | 待拍板内容 |
| --- | --- | --- |
| EX 地址到 L1D S0 的时序 | 4 级，EX 兼做 AGU，与 S0 对齐 | 综合不满足时启用“冲突结果寄存一拍、S1 kill 重发”的退路 |
| load-use 延迟 | SOC-3b：WB 得数据/提交，下一拍 W2 写；依赖 EX=E+4 | 已定 3 拍气泡，S2 组合 bypass/T02b 作废 |
| CSR 串行化粒度 | v1 等两组记分板清空 | 是否只对 fflags/fcsr/frm 等少数 CSR 等待，其余 CSR 不等 |
| 长延迟写回优先级 | W2 普通写优先；后台 lateReg>DIV>MUL>FPU | 已定固定顺序及 ID 饥饿保护阈值 3 |
| MDU/FPU 内部流水 | 后端只依赖 valid/ready 与提交/kill；MUL 为 DSP 4 拍完全流水 | FPU 各操作的延迟与是否改用 DSP |

## 13. SOC-3b 边界

FENCE.I/SFENCE 不额外等待 W2/lateReg，ESTOP 的 busy 已覆盖 lateReg，中断/WFI 不等后台 busy；W2 不被 redirect 取消。结构禁止/允许与 report_timing/RTL fan-in 取证均按时序合同 §4。第 9 节的旧乘法 DSP 自动推断/重定时设想已由 SOC-3 M2 明确 DSP 寄存结构覆盖，本轮不改 MulUnit、不启用 retiming；现有 M1/M2 保持。
