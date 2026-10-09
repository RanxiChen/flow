# Breeze v1 后端拍数与重叠合同

状态：**冻结**（2026-10-06 用户确认 B01；2026-10-08 用户完成 SOC-3b 多轮裁定，现稿已同步 W2/lateReg、T12/T13/T21/T22、P06 与结构门槛）。本表每一行就是一条冻结测试：codex 按规范实现 RTL，并按本表写测试。**期望拍数不得自行修改**；测不过只能改 RTL。认为某行与微架构构造不符时，停下报告（附推导），不得自行改期望值。本轮规格修订不代表 RTL 或验证已完成。

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
- 除 T12、T13、T21、T22 外，各行默认测试台安排后台结果写回的拍上 **W2** 没有同寄存器堆的普通写（例如调整 `nop` 即 `addi x0` 的位置），以测量无冲突延迟。WB 当拍无普通写不足以满足此条件，前一拍提交的普通写仍会占用本拍 W2。
- SOC-3b 后 `l1d.late.fire` 只表示 L1D 结果被 lateReg 接收；它不表示物理写回。迟到结果的物理完成另测 `gprWrite`/`fprWrite`；lateReg 的实际 grant 才释放对应 busy。T12/T21/T22 的 lateReg 初始为空；T12/T22 的 N 拍无此前普通 W2 GPR 写，且无其它后台来源。
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
| T12 ※b | late 首次 valid 与普通 GPR 指令 WB 提交同拍 N，N+1 的 WB 不写 GPR | 普通指令 `commit=N`、`gprWrite=N+1`；`l1d.late.fire=N`、迟到目的 `gprWrite=N+2`；ID/EX/MEM/WB 不因写口保持；`wb_port_conflict` 仅 N+1 计 1 |
| T13 | 写口优先级：lateReg、DIV、MUL、FPU→GPR 四个写口来源在 N 同拍 valid，N…N+3 的 W2 无普通 GPR 写 | 连续 4 拍 N…N+3 依次写入，顺序 lateReg > DIV > MUL > FPU；未选者保留未完成结果。测试 L1D late 在 N−1 接收：现有刺激 `returnDelay=26` 改为 25，原物理写回期望 N…N+3 不变；FPU 反压遵循 v1 RTL spec 的 tag/fire 合同 |
| T14 | FPU 请求接受与计算发射 | SOC-3d 合并控制链候选（2026-10-09 用户授权）：EX 拍 E 在 FpUnit 入口有容量时接受请求并分配 tag；两项无 flow/pipe 请求缓冲使原始 CVFPU 最早 E+1 fire。入口 ready 只依赖寄存容量/tag 可分配，不能组合读取 CVFPU ready。无停顿 II=1；满时保持 EX，不重复接受。 |
| T15 | FPU 写回附加拍 | `fpu.out_valid&&out_ready` 的拍 = `fprWrite`（或 `gprWrite`，FP→整数类）的拍；在途表查 tag 不加拍。仅在被更高优先级来源或 W2 普通写占口时延后 |
| T16 | FP 依赖：`fadd.d f1,..; fadd.d f2,f1,..` | `idLeave(第二条) = fprWrite(f1)`；`fprWrite(f1) − ex(第一条)` 等于 1 + CVFPU FP64 ADDMUL 的实际延迟（测试台从原始 `in fire`→`out fire` 测得），对应本批一拍输入边界；输出不加拍 |
| T17 | CSR 等空：`fdiv.d f1,..; csrr x5,fflags` | `idLeave(csrr) = fprWrite(f1) + 1`（A07 用 `busy==0`，不用 effectiveBusy）；读到的 fflags 含 fdiv 的标志 |
| T18 | FENCE.I 在 WB，`drained=1` | `frontendRedirect.valid` 在 WB 当拍；I-cache 清空同拍发出 |
| T19 | FENCE.I 在 WB，MSHR 忙，`drained` 在拍 D 变 1 | redirect 在 D 当拍；此前 WB 保持，不提交；FENCE.I 离开 ID 之后到 redirect 之前，没有指令离开 ID，`l1d.req.fire` 不出现 |
| T20 | SFENCE.VMA 在 WB | `sfence.valid` 在第一个 `drained && mmu.idle` 的拍 S（仅 1 拍）；redirect 在 S 之后第一个 `mmu.idle` 的拍；期间无任何 `dtlb.req`/`itlb.req`；SFENCE.VMA 离开 ID 之后到 redirect 之前没有指令离开 ID |
| T21 ※b | 写口饥饿保护：`late.valid` 首拍为 c，普通独立 ALU 流已稳定，除保护气泡外持续提交，不读 late 的 rd | `l1d.late.fire=c`；迟到目的 `gprWrite=c+8`；只有 c+4 一拍没有指令离开 ID，其余各拍照常离开；`wb_port_conflict` 在 c+1…c+7 各计 1，共 7 |
| T22 ※b | B01 反例：Load A 已提交 miss；B 为 `add`（写 GPR）、C 为紧随的命中 `ld`（不同字）、其后为 `nop`；A 的 `late.valid` 首拍 = B 的 WB 拍 N | `commit(B)=N`、`gprWrite(B)=N+1`；C 的 `l1d.resp.valid`（Done）=`commit(C)=N+1`、`gprWrite(C)=N+2`；`l1d.late.fire=N`、`gprWrite(A)=N+3`；后端无保持，resp 与 WB 始终同拍；冲突 N+1、N+2，共 2 |

**※b（SOC-3b，2026-10-08 用户裁定）**：旧结构中 late.fire 与 RF 写回同拍，新结构中两者分离；旧「只允许整体 +1」限制撤销，以本表明确值为准。普通指令 WB 提交拍不变，物理写回后移到 W2。T21 在 c 接收后，c+1 才开始写口冲突计数，c+4 拍初计数为 3 并在 ID 插气泡；气泡经过 EX/MEM/WB/W2，在 c+8 让出写口，故有 7 次冲突。阈值仍为 3，不提前组合读取 L1D late.valid。报告逐行给出上述推导，不自行再改变拍号或规则。

## 2. 重叠（并行）

每行用拍号断言，做成串行一定失败。L2 模型延迟设为 30 拍（除非另说），保证重叠窗口足够大。“连续提交”允许 ID 饥饿保护插入的气泡（每个饥饿事件至多 1 拍，按 T21 规则可由测试台精确预测）；P02 的 8 条 MUL 之后接 `nop`。

| ID | 场景 | 期望 |
| --- | --- | --- |
| P01 | Load 命中吞吐：16 条独立命中 Load | `l1d.req.fire` 连续 16 拍，每拍一条；`commit` 连续 16 拍 |
| P02 | MUL 吞吐：8 条独立 MUL | `req.fire` 连续 8 拍；`gprWrite` 连续 8 拍（各为对应 E+4） |
| P03 | DIV 后台：一条常规 DIV（≥20 拍迭代）后跟 20 条不相关 ADD | 20 条 ADD 在 DIV 写回之前全部提交，且 `commit` 连续（每拍一条） |
| P04 | hit-under-miss：Load A miss（拍 E），随后 Load B（他行命中，E+1）、Store C（他行命中，E+2）、10 条 ALU | B 的 Done 在 E+3、C 的 Done 在 E+4；B、C 与 10 条 ALU 都在 A 的 `late.fire` 之前提交 |
| P05 ※ | miss 同行停住：P04 之后 Load D 访问 A 的行 | D 在 WB 收 `s2Hold` 直到 A 回放完成；D 的 Done = R+10（A 回放 S2 在 R+7，MSHR 当拍空闲，D 于 R+8 重进 S0） |
| P06 | 后台三路并存：Load miss（x1）、常规 DIV（x2）、FDIV.D（f1）先后提交，随后 20 条不依赖它们的 ALU | ① 重叠：第 1 条 ALU 的 `commit` 早于三路结果中最早的一次写回；② 不停顿：提交空拍集合 = `{g−1}`，g 取 x1/x2 后台 `gprWrite` 拍，并且 g−1 严格落在首末 ALU commit 之间；每个空拍恰 1 拍，FDIV 写 FPR 不产生空拍。ID 在 b 插气泡时 WB 空拍为 b+3、W2 让口/后台写回为 b+4；③ 三路结果各写一次，记分板最终全 0。不要求 20 条 ALU 全部早于后台写回（FDIV 延迟由 CVFPU 决定） |
| P07 | FPU 乱序返回：`fdiv.d f1,..` 后紧跟 `fadd.d f2,..`（不相关） | `fadd` 在 fdiv 未完成时发射；`fprWrite(f2) < fprWrite(f1)`；两者 fflags 都累积 |
| P08 | FPU 吞吐：8 条独立 `fmadd.d` | `fpu.in fire` 连续 8 拍（在途表容量不得成为瓶颈） |
| P09 | 中断不等后台：常规 DIV 已提交在算，此时置中断 | 中断在未提交流水排空后立即接受，接受拍早于 DIV 的 `gprWrite`；DIV 结果照常写回 |
| P10 | 异常不影响已提交后台项：DIV（x5）已提交；年轻 Load 在 WB 报页异常；其后 MUL x6 在 MEM | 陷入当拍 MUL 被作废，x6 从不写、从不置位；x5 照常写回一次 |

## 3. 结构禁止项（审核用，不是测试）

- 不增加后端流水级；EX/MEM/WB 与 S0/S1/S2 一一对齐。唯一例外（SOC-3b）：WB 之后的 GPR/FPR 写级 W2 与 L1D 迟到结果寄存器 `lateReg`，见 §4。
- 停顿方向（B01）：后端自身发起的停顿只能落在 ID/EX；MEM/WB 只在 L1D `s2Hold` 或 WB 串行指令（其后全为气泡）时保持。用仿真断言检查：MEM 有访存指令时，MEM/WB 保持必然伴随 `s2Hold`。
- 后端与 L1D、MDU、FPU 之间不加额外 FIFO 或结果缓冲（例外为 SOC-3b 的单项 `lateReg` 和 SOC-3d 合并控制链候选 FpUnit 内部两项请求缓冲；不增加输出结果缓存）；需要保持的结果由各来源自身保持（L1D `late`、MDU `result`、FPU 在途表）。
- FPU 在途表按 CVFPU `tag` 索引，存 rd、目的寄存器堆、committed、valid，不存结果数据。本批新增两项请求缓冲（操作数/操作/rm/格式/tag）及 flags-only 所有权计数；不是结果缓存。
- 不为任何测试场景写特判（地址、PC、指令序列识别）。

## 4. 已定事项

- **load-use**：v1 默认 3 拍（T02，原为 2 拍）；`loadUseBypass`/T02b 作废，不实现、不测试；参数允许删除，或保留为无此旁路功能的兼容参数，在报告中说明。
- **W2 无条件完成**：普通 GPR/FPR 写的数据、bank、rd、valid/写使能在无异常的 WB 提交拍末寄存，下一拍 W2 优先取得对应写口、物理写入并写穿透到 ID。W2 已提交，不受 fatal、stop、kill、redirect、hold 屏蔽，也不重复 retire。W2 是否占口、写哪个 rd 只由寄存器决定；每拍消费一次，WB 无新普通提交时下一拍 W2 valid 清零，不能随被保持的 WB 重复写。复位按原规则清 valid；x0 不物理写，f0 可写。W2 不置、不清 busy。WB 提交、精确陷入与 CSR 生效拍保持原规则；迟到 fatal 的变化见下条。
- **lateReg 接收与完成**：单项 lateReg 存 valid、bank、rd、data、error；`l1d.late.ready = !lateReg.valid || lateRegGrant`，只由寄存状态决定。空槽只接收，不组合穿透写 RF；同拍 grant 旧项并 fire 新项时，clear/数据/error/完成事件来自旧项，拍末捕获新项。lateReg 取代原 L1D late 作为写口来源；busy 清除和 RF 写穿透发生在 lateReg 获准完成拍，不是接收拍。
- **fatal 行为变化**：错误 late 在 N 接收后，hartFatal 在 lateReg 可见的 N+1 拍生效，并由 fatal 寄存器或 `lateReg.valid && lateReg.error` 驱动；不组合读取原始 late.error。接受 N 拍可能多提交一条普通指令，因为此错误非精确、无 trap。fatal 停止新发射/普通退休并作废未提交项；已提交 W2 无条件完成，已提交后台来源继续完成。错误 lateReg 完成清对应 busy、输出 error 事件，不写坏数据。
- **ID 与 EX 数据规则**：WB 中会产生寄存器结果的访存（load、FLW/FLD、LR/SC/AMO、MMIO）只按寄存的 valid、类别、writes、bank、rd 判断 RAW/WAW，不按 Done/Mshr/Exc 解除；禁止继续用 `!wbDone` 派生 WB 冒险资格。WB miss 到已提交 busy 的保护连续。ID 不单独比较 W2 在途，依赖者可在 W2 RF 写穿透拍离开 ID。FPR 普通结果也须避免 ID 锁存旧值。
- **普通旁路**：保留 WB→EX，新增 W2→EX；WB 资格仅看寄存的 valid、类别、writes、bank、rd，只向非访存普通结果开放，不使用含 wbCommit/wbDone 的 `wbOrdinary`。按年龄 `MEM > WB > W2 > 已捕获操作数` 选择；EX 被保持时继续捕获有效旁路值。保证 ALU 依赖在生产者 MEM/WB/W2 各拍均无新增气泡。不加后台 MDU/FPU/lateReg→EX 数据旁路。
- **busy、串行与 trace**：busy 只由已提交长延迟项置位，由 lateReg/DIV/MUL/FPU 实际获准的完成事件清除；错误 lateReg 也清位，W2 不碰 busy，kill 不清位。T17 仍用 raw busy，ESTOP 的 busy 已覆盖 lateReg；FENCE.I/SFENCE 不增加 W2/lateReg 等待条件，T18/T19 的 drained 和 T20 的 drained 仍为 `l1d.drained`（T20 另有原 mmu.idle）。中断/WFI 不等后台 busy，redirect 不取消 W2/lateReg。FASE 仍不支持 useFASE=true。trace 在 WB 记录架构提交及普通结果，物理写拍由 observe.gprWrite/fprWrite 测量；lateWriteError 对齐 lateReg 的完成事件，普通 W2 不伪装成后台 pending completion。
- **结构门槛：禁止项**：L1D s2/cpu2/internal2 寄存器的当拍结果不得决定写口授权、各来源的结果出口 ready（l1d.late.ready、DIV/MUL result.ready、FPU 输出 ready）、记分板实际 clear、操作数 RAW/WAW 判定或 EX 旁路选择。结果出口 ready 不包括受精确取消控制的请求入口 ready。允许的保持/异常取消不能被当成操作数可用性判定的例外。
- **结构门槛：允许项**：s2Hold→各级保持/最终推进门控；S2 精确异常→取消年轻指令；Mshr→提交拍末 busy 置位（T10 不挪）；S2→W2 寄存器输入；s2Hold/S2 事件→HPM 事件寄存器输入。各类单独报告最差级数和 slack；Mshr→busy 置位若进入全局 worst-20 且 slack<0，停下报告，不自行挪到 W2。
- **结构门槛：取证**：可定位的寄存器终点用 `report_timing -from … -to …`，可定位的中间点可用 `-through`。网名优化后无法查询时，允许 RTL 级 fan-in 分析替代该中间链查询，写明范围、源位置与无法定位的对象；可定位终点仍提供网表 timing。不得为查询加 keep 属性或修改综合策略。此证据与 routed timing 分开。
- **写口与饥饿保护**：W2 普通写优先；后台全局一拍至多一个 grant，lateReg > DIV > MUL > FPU。每堆计数饱和于 3，计数为 3 且无在途保护气泡时 ID 停发 1 拍；该堆获后台 grant 后解除在途保护。`wb_port_conflict` 每拍每堆有 lateReg/DIV/MUL/FPU 结果 valid 但该堆无后台 grant 时计 1。L1D late 的接收或因 lateReg 满而等待都不直接计数，也不提前用于饥饿计数。
- **T11、P05（※）**：按 L1D spec 推导（安装 `wordsPerLine` 拍 + 回放走一次 S0–S2）。codex 若按 L1D spec 推出不同值，停下报告推导，由 Claude 裁定；不得各写各的。


## 2026-10-09 SOC-3d 合并控制链授权修订

用户明确要求四项同时实现以尽快时序收敛。T14/T16 的输入边界与上述结构例外按本次授权更新；其余整数、L1D、WB/W2/lateReg 提交/写回拍数不变。FpUnit 请求接受仍在 EX，commit 仍在 E+2；无停顿原始 CVFPU input 延后一拍，FP32/FP64 ADDMUL 均为原始输入到输出五拍，后端接受到输出六拍。T15 保持直接输出。同拍 enqueue/kill 新 tag 作废；刚 commit 与既有 committed 项保留，包括尚在缓冲中的请求。队头作废请求本地丢弃，已进入 CVFPU 的作废结果按原规则丢弃。killDrain 等请求缓冲和 CVFPU 同时排空，期间禁止 tag 复用。flags-only 计数只由实际 commit/completion 更新，与原 valid&&committed&&x0 归约同沿等价，CSR 仍按 raw ownership 等待。

EX payload 在槽位推进时可捕获不被接受的 ID 值，但 ex.valid 仍只由原 idLeave 授权；无效 payload 不得产生发射、提交或副作用。CSR time/stimecmp 分段比较保持同拍 pending，不增加中断延迟。L1D tag 存储分组保持 S0 同拍读发起、S1 返回，写优先级及初始化/快照失效/kill/所有权规则不变。功能与物理结果见 `tasks/SOC-3d-combined-control-report.md`；不得沿用旧候选通过结论。
