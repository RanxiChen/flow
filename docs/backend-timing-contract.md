# Breeze v1 后端拍数与重叠合同

状态：**冻结**（2026-10-06 用户确认；同日 B01 裁定修订 T12、T13、T15、T19，新增 T21、T22；P06 改为可测量的三条，见 [`tasks/V1-BE-B01-ruling.md`](tasks/V1-BE-B01-ruling.md)）。本表每一行就是一条冻结测试：codex 按规范实现 RTL，并按本表写测试。**期望拍数不得修改**；测不过只能改 RTL。认为某行与微架构文档推导不符时，停下报告（附推导），不得自行改期望值。

依据：[`backend-pipeline-design.md`](backend-pipeline-design.md)、[`backend-rtl-spec.md`](backend-rtl-spec.md)（T01 冻结稿）、[`l1d-rtl-spec.md`](l1d-rtl-spec.md)。

## 0. 记法与测量方法

- 被测指令 I 在 EX 的那一拍记为 **E**；MEM = E+1，WB = E+2（L1D 的 S0/S1/S2 与之对齐）。
- 测量点（测试台从 RTL 观测，不靠推算）：
  - `idLeave(I)`：I 离开 ID 的拍；
  - `ex(I)`：I 在 EX 的拍（= `idLeave(I)` + 1）；
  - `commit(I)`：I 在 WB 提交的拍；
  - `gprWrite(r)` / `fprWrite(r)`：寄存器 r 物理写入的拍；
  - 接口事件：`l1d.req.fire`、`l1d.resp.valid`、`l1d.late.fire`、`fpu.in_valid&&in_ready`、`fpu.out_valid&&out_ready`、`sfence.valid`、`frontendRedirect.valid`。
- 环境：单核，L1D 接行为 L2 模型（RSP↓ 延迟可设），dTLB 预热命中，无中断（除非该行另说）。前端供指不断流（fetch buffer 恒 valid），排除取指气泡。
- "等于"指精确拍数：多一拍、少一拍都失败。"≤ / ≥" 只用于标明的行。
- 除 T12、T13、T21、T22 外，各行默认测试台安排后台结果写回的拍上 WB 没有同寄存器堆的普通写（例如其后接 `nop` 即 `addi x0`），以测量无冲突延迟。
- **推导值**行（标 ※）由 L1D spec 的拍级规则推出；若 codex 按 L1D spec 推出不同值，停下报告，由 Claude 裁定，不得各写各的。

## 1. 拍数（延迟）

| ID | 场景 | 期望 |
| --- | --- | --- |
| T01 | ALU→ALU 依赖：`add x1,..; add x2,x1,..` | `ex(第二条) = ex(第一条) + 1`（0 气泡，EX 旁路） |
| T02 | Load 命中→依赖 ALU：`ld x1,0(x2); add x3,x1,x4` | `l1d.req.fire` 在 E；`l1d.resp.valid`（Done）在 E+2，`commit(ld) = E+2`；`gprWrite(x1) = E+3`（W2 级写，见 §4 `W2`）；`idLeave(add) = E+3`（W2 写穿透）；`ex(add) = E+4`（load-use 3 拍气泡；SOC-3b 2026-10-08 由 2 拍改为 3 拍） |
| T03 | Load 命中后跟不相关 ALU | `ex(ALU) = E+1`（0 气泡） |
| T04 | MUL→依赖：`mul x1,..; add x2,x1,..` | MUL `req.fire` 在 E；`gprWrite(x1) = E+4`；`idLeave(add) = E+4`（写回同拍经 RF 写穿透离开 ID）；`ex(add) = E+5` |
| T05 | DIV 快速路径（除数 0 或 `min/-1`）→依赖 | `gprWrite(rd) = E+3`；`ex(依赖) = E+4` |
| T06 | DIV 常规路径 | `gprWrite(rd) = E + 2 + 迭代拍数`，迭代拍数取 `UnsignedRadix4Divider` 对该操作数的实际拍数（测试台从单元内部取），后端不得再加拍 |
| T07 | 两条相邻独立 DIV | 第二条 `req.fire` = 第一条 `gprWrite` + 1（T01：释放与再接收不同拍）；期间第二条停在 EX，ID 及更年轻指令不越过 |
| T08 | Store 命中→同 8 B 字 Load（Store 在 E，Load 在 E+1 想进 S0） | Load 的 `l1d.req.fire` = E+4（S1、S2、PS 三拍冲突，PS 写入拍仍冲突） |
| T09 | Store 命中→不同 8 B 字 Load | Load `l1d.req.fire` = E+1（无停顿） |
| T10 | Load miss 提交 | `l1d.resp.valid`（Mshr）在 E+2，同拍 `commit(ld)`，x1 记分板周期末置位 |
| T11 ※ | Load miss 迟到数据（RSP↓ DataE 在拍 R fire，`late.ready` 恒可得） | `l1d.late.fire = R+7`（R+1…R+4 安装 4 拍，R+5 回放 S0，R+7 回放 S2），当拍进入后端 `lateReg`；`gprWrite(x1) = R+8`；依赖指令 `idLeave = R+8`（SOC-3b：由 R+7 改为 R+8） |
| T12 ※b | 迟到数据与 WB 普通 GPR 写同拍（拍 N），N+1 的 WB 不写 GPR | WB 普通写在 N 写入并提交；`late.ready=0`，`l1d.late.fire = N+1`；后端任何级都不保持（ID/EX/MEM/WB 照常推进）；`wb_port_conflict` 计 1 |
| T13 | 写口优先级：L1D late、DIV、MUL、FPU→GPR 四路同拍 valid，这 4 拍 WB 无普通 GPR 写 | 连续 4 拍依次写入，顺序 L1D > DIV > MUL > FPU；未选者结果保持不变 |
| T14 | FPU 发射附加拍 | FP 运算在 EX 的拍 E：`fpu.in_valid` 在 E（若 `in_ready=1` 则同拍 fire）。后端不加寄存级 |
| T15 | FPU 写回附加拍 | `fpu.out_valid&&out_ready` 的拍 = `fprWrite`（或 `gprWrite`，FP→整数类）的拍；在途表查 tag 不加拍。仅在被更高优先级来源或 WB 普通写占口时延后 |
| T16 | FP 依赖：`fadd.d f1,..; fadd.d f2,f1,..` | `idLeave(第二条) = fprWrite(f1)`；`fprWrite(f1) − ex(第一条)` 等于 CVFPU FP64 ADDMUL 的实际延迟（测试台从 `in fire`→`out fire` 测得），后端两端都不加拍 |
| T17 | CSR 等空：`fdiv.d f1,..; csrr x5,fflags` | `idLeave(csrr) = fprWrite(f1) + 1`（A07 用 `busy==0`，不用 effectiveBusy）；读到的 fflags 含 fdiv 的标志 |
| T18 | FENCE.I 在 WB，`drained=1` | `frontendRedirect.valid` 在 WB 当拍；I-cache 清空同拍发出 |
| T19 | FENCE.I 在 WB，MSHR 忙，`drained` 在拍 D 变 1 | redirect 在 D 当拍；此前 WB 保持，不提交；FENCE.I 离开 ID 之后到 redirect 之前，没有指令离开 ID，`l1d.req.fire` 不出现 |
| T20 | SFENCE.VMA 在 WB | `sfence.valid` 在第一个 `drained && mmu.idle` 的拍 S（仅 1 拍）；redirect 在 S 之后第一个 `mmu.idle` 的拍；期间无任何 `dtlb.req`/`itlb.req`；SFENCE.VMA 离开 ID 之后到 redirect 之前没有指令离开 ID |
| T21 ※b | 写口饥饿保护：`late.valid` 首拍为 c，c 起 WB 每拍都有普通 GPR 写（独立 ALU 流，不读 late 的 rd） | `l1d.late.fire = c+6`；c+3 拍没有指令离开 ID（唯一 1 拍），其余各拍 ALU 每拍离开 ID 一条；`wb_port_conflict` 计 6 |
| T22 ※b | B01 反例：Load A 已提交 miss；B 为 `add`（写 GPR）、C 为紧随的命中 `ld`（不同字）、其后为 `nop`；A 的 `late.valid` 首拍 = B 的 WB 拍 N | `commit(B) = gprWrite(B) = N`；C 的 `l1d.resp.valid`（Done）= `commit(C)` = `gprWrite(C)` = N+1；`l1d.late.fire = N+2`；后端无保持，`resp` 与 WB 始终同拍；`wb_port_conflict` 计 2 |

**※b（SOC-3b）**：T12/T21/T22 的原期望写于「WB 当拍写 GPR、`late` 直接参与写口仲裁」的结构。SOC-3b 后写口仲裁发生在 W2（WB 普通写）与 `lateReg`（L1D 迟到结果）上，规则本身（WB 普通写优先、L1D > DIV > MUL > FPU、饥饿保护计数与阈值、`wb_port_conflict` 定义）不变。codex 按新结构重新推导这三行的拍号，测试按推导值写，并在报告中逐行给出推导；推导结果只允许是原值整体平移（普通写与 late 相关拍号 +1），出现其它差异就停下报告，由 Claude 裁定。

## 2. 重叠（并行）

每行用拍号断言，做成串行一定失败。L2 模型延迟设为 30 拍（除非另说），保证重叠窗口足够大。“连续提交”允许 ID 饥饿保护插入的气泡（每个饥饿事件至多 1 拍，按 T21 规则可由测试台精确预测）；P02 的 8 条 MUL 之后接 `nop`。

| ID | 场景 | 期望 |
| --- | --- | --- |
| P01 | Load 命中吞吐：16 条独立命中 Load | `l1d.req.fire` 连续 16 拍，每拍一条；`commit` 连续 16 拍 |
| P02 | MUL 吞吐：8 条独立 MUL | `req.fire` 连续 8 拍；`gprWrite` 连续 8 拍（各为对应 E+4） |
| P03 | DIV 后台：一条常规 DIV（≥20 拍迭代）后跟 20 条不相关 ADD | 20 条 ADD 在 DIV 写回之前全部提交，且 `commit` 连续（每拍一条） |
| P04 | hit-under-miss：Load A miss（拍 E），随后 Load B（他行命中，E+1）、Store C（他行命中，E+2）、10 条 ALU | B 的 Done 在 E+3、C 的 Done 在 E+4；B、C 与 10 条 ALU 都在 A 的 `late.fire` 之前提交 |
| P05 ※ | miss 同行停住：P04 之后 Load D 访问 A 的行 | D 在 WB 收 `s2Hold` 直到 A 回放完成；D 的 Done = R+10（A 回放 S2 在 R+7，MSHR 当拍空闲，D 于 R+8 重进 S0） |
| P06 | 后台三路并存：Load miss（x1）、常规 DIV（x2）、FDIV.D（f1）先后提交，随后 20 条不依赖它们的 ALU | ① 重叠：第 1 条 ALU 的 `commit` 早于三路结果中最早的一次写回；② 不停顿：20 条 ALU 的 `commit` 每拍一条，唯一允许的空拍是落在其间的 x1/x2 后台 GPR 写回拍（饥饿保护气泡），即空拍集合 = 落在首末 ALU commit 之间的 `gprWrite(x1)`/`gprWrite(x2)` 拍集合，每个空拍恰 1 拍；FDIV 写 FPR 不产生空拍；③ 三路结果各写一次，记分板最终全 0。不要求 20 条 ALU 全部早于后台写回（FDIV 延迟由 CVFPU 决定） |
| P07 | FPU 乱序返回：`fdiv.d f1,..` 后紧跟 `fadd.d f2,..`（不相关） | `fadd` 在 fdiv 未完成时发射；`fprWrite(f2) < fprWrite(f1)`；两者 fflags 都累积 |
| P08 | FPU 吞吐：8 条独立 `fmadd.d` | `fpu.in fire` 连续 8 拍（在途表容量不得成为瓶颈） |
| P09 | 中断不等后台：常规 DIV 已提交在算，此时置中断 | 中断在未提交流水排空后立即接受，接受拍早于 DIV 的 `gprWrite`；DIV 结果照常写回 |
| P10 | 异常不影响已提交后台项：DIV（x5）已提交；年轻 Load 在 WB 报页异常；其后 MUL x6 在 MEM | 陷入当拍 MUL 被作废，x6 从不写、从不置位；x5 照常写回一次 |

## 3. 结构禁止项（审核用，不是测试）

- 不增加后端流水级；EX/MEM/WB 与 S0/S1/S2 一一对齐。唯一例外（SOC-3b）：WB 之后的 GPR/FPR 写级 W2 与 L1D 迟到结果寄存器 `lateReg`，见 §4。
- 停顿方向（B01）：后端自身发起的停顿只能落在 ID/EX；MEM/WB 只在 L1D `s2Hold` 或 WB 串行指令（其后全为气泡）时保持。用仿真断言检查：MEM 有访存指令时，MEM/WB 保持必然伴随 `s2Hold`。
- 后端与 L1D、MDU、FPU 之间不加额外 FIFO 或结果缓冲（例外仅 SOC-3b 的单项 `lateReg`）；需要保持的结果由各来源自身保持（L1D `late`、MDU `result`、FPU 在途表）。
- FPU 在途表是唯一新增的 FPU 侧结构：按 CVFPU `tag` 索引，存 rd、目的寄存器堆、committed、valid，不存数据。
- 不为任何测试场景写特判（地址、PC、指令序列识别）。

## 4. 已定事项

- **load-use**：v1 默认 3 拍（T02，SOC-3b 2026-10-08 用户裁定，原为 2 拍）。原因：Cluster OOC 最差 52 级路径为 L1D S2 结果 → WB 写口占用/`late.ready` → DIV/MUL/FPU ready → 记分板同拍清除 → ID 发射/EX CE/HPM。`loadUseBypass` 参数保留但不实现、不测试（T02b 作废）。
- **W2（SOC-3b）**：WB 提交不变（commit、陷入、CSR 生效拍都不变）；WB 的普通 GPR/FPR 写数据、rd、写使能在 WB 末寄存，于下一拍 W2 写寄存器堆、清记分板、参与写口仲裁并写穿透到 ID。W2 到 EX 增加一路旁路，保证 T01/T03 及所有 ALU 相关不增加气泡。**W2 是否占写口、写哪个 rd 只能由寄存器决定**，不得组合依赖当拍 `l1d.resp`/`s2Hold`/`late`。
- **lateReg（SOC-3b）**：`l1d.late` fire 当拍把数据与 rd 寄存进单项 `lateReg`；`l1d.late.ready` 只能是寄存器的函数（基线 `!lateReg.valid`）。`lateReg` 取代原 `late` 作为写口最高优先级的长延迟来源；记分板清除与 ID 写穿透在 `lateReg` 写入拍发生。
- **结构门槛（SOC-3b）**：综合网表中不存在从 L1D `s2`/`cpu2`/`internal2` 寄存器出发、同拍到达记分板忙位、GPR/FPR 写口授权（含 `late.ready` 与 DIV/MUL/FPU 结果 ready）或 HPM 事件寄存器的组合路径。`s2Hold` → MEM/WB/EX/ID 保持使能是有序流水固有路径，允许保留，但报告其最差级数与 slack。证据用 Cluster OOC 的 `report_timing -from [l1d 的 s2/cpu2/internal2 寄存器] -to [上述终点]`。
- **写口（B01）**：WB 普通写优先；ID 饥饿保护（每堆计数饱和于 3，计数为 3 且无在途保护气泡时 ID 停发 1 拍）。`wb_port_conflict` 改为：每拍每堆有长延迟结果 valid 但该堆无长延迟写获准时计 1。
- **T11、P05（※）**：按 L1D spec 推导（安装 `wordsPerLine` 拍 + 回放走一次 S0–S2）。codex 若按 L1D spec 推出不同值，停下报告推导，由 Claude 裁定；不得各写各的。
