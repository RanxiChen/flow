# T01 阶段一审阅结论

审阅对象：`docs/backend-rtl-spec.md`、`docs/backend-testplan.md`（提交 `59e91fc`）。spec 的现状映射、保留行为清单和断言/性质框架可用。下面先给出一处设计修订，再逐项决定 Q01–Q18。实现 agent 按本文把决定写入 spec 与 testplan，形成冻结候选稿。

## 1. 设计修订：记分板在 WB 提交时置位（参照 Rocket）

[`backend-pipeline-design.md`](../backend-pipeline-design.md) 第 5 节已同步修改。要点：

- **未提交**的长延迟指令（在 EX/MEM/WB 中）由级间冒险检查覆盖：ID 的 rs1/rs2/rd 与 EX/MEM/WB 中 MUL/DIV 的 rd 相同（且 rd≠0）时停住。
- **已提交、未写回**的操作由记分板覆盖：WB 提交 MUL/DIV 时置位 rd，结果写回时清除。
- **kill 永不清记分板**，被 kill 的指令都未提交。
- 单元侧只需两个脉冲：`commit`（提交该单元最老的未提交项）、`killUncommitted`（作废该单元全部未提交项）。不需要事务标签；结果只带 rd。
- 依赖指令只在结果写回后（或写回同拍经写穿透）离开 ID，EX 不需要 MDU 结果旁路。

这一修订消除了 Q01、Q02、Q03 的大部分问题，也删除了 spec 2.1/2.2 中“ID 离开即预约”“kill 清预约”的全部内容。

## 2. 逐项决定

| ID | 决定 |
| --- | --- |
| Q01 身份 | 不设事务标签。每个单元内部的未提交项按发射顺序排列，至多 2–3 项（EX→MEM→WB 中的那几条）。`commit` 命中最老的未提交项；`killUncommitted` 作废全部未提交项。结果只带 rd。 |
| Q02 同拍优先级 | 置位发生在 WB 提交，清除发生在写回；同一 rd 不会同拍置位和清除（WAW 检查保证），用断言检查。不同 rd 的置位与清除同拍都生效。写回同拍允许依赖指令离开 ID：`sbRaw/sbWaw` 使用 `busy & ~writebackMask`，数据经 `RegFile` 写穿透提供。 |
| Q03 resolve 时序 | `commit`、`killUncommitted` 都是单拍脉冲，单元必须同拍接受，无握手。同拍同时出现时先 commit 后 kill（WB 的 MDU 提交，同拍被接受的中断 kill 更年轻的）。算术先于提交完成时，结果留在单元内，`result.valid` 只对已提交项拉高。带取指异常、非法指令标记的指令不发射到 MDU。**`killUncommitted` 只由 WB 发起的 kill 产生**（异常、xRET、FENCE.I、satp 写、中断等）：此时单元中除同拍提交的项外，所有未提交项都比 WB 指令年轻。EX 发起的重定向（分支、JALR）不发 kill：比它年轻的指令还在 ID，尚未发射；只需抑制同拍 EX 的发射。若某种重定向在 MEM 发起，同样只抑制同拍 EX 的发射，不能作废 MEM/WB 中更老的项。spec 中需逐一核对每种重定向的发起级并写明。复位作废全部项、清记分板。 |
| Q04 MUL 反压 | MUL 为 4 级流水，末级即输出寄存器；末级已提交且未获写口时，整条 MUL 流水停住（所有级 enable 同为 `!(outValid && !outReady)`），`req.ready` 同时为低。停住期间 kill 仍作用于未提交级。EX 中的 MUL 因 `req.ready` 为低时，EX 与 ID 停住，MEM 插入气泡，MEM/WB 继续推进。不设额外结果 FIFO。 |
| Q05 DIV 快路径 | 保留现有 EX 中的除数为 0、溢出检测；快结果作为 `req` 的附带字段（`fastValid`、`fastData`）进入 DIV wrapper，wrapper 接收后即视为算术完成，此后走与迭代结果相同的提交/写回协议。`req.ready = !occupied`，释放与再接收不同拍。报告中分开写“算术迭代拍数”与“req→写回拍数”。 |
| Q06 WB 让拍 | 只有**要写整数寄存器**（`wb_en && rd≠0`，含 CSR、FP→GPR、Load、ALU）的 WB 指令在写口被长延迟结果占用时让拍；让拍时整条流水（WB、MEM、EX、ID）停一拍，WB 项保持、不退休、无副作用。不写整数寄存器的指令（Store、分支、rd=x0、FPR 写、MDU 自身提交、trap、xRET、WFI）照常提交。中断接受条件保持原规则（未提交流水为空），从 `pipelineEmpty` 中删除 MUL/DIV 等待项。 |
| Q07 响应保存 | 阻塞的访存响应和 FPU 完成在让拍时不能丢：各加 1 项捕获寄存器，在 MEM 推进时消费。FPU 若有 out_ready 则改用反压，不加捕获寄存器。具体选择写进 spec。 |
| Q08 依赖名单 | 从现有 decoder 推导整数源/目的使用表，作为 spec 附录逐条列出（含 FP 的整数源与整数目的、CSR、原子、sfence）。规则：读 GPR 的源参与 RAW，写 GPR 的参与 WAW；FPR 不查整数记分板。按第 1 节修订，EX 无需 MDU 旁路，held EX 更新逻辑不变。 |
| Q09 文件范围 | 允许修改 `design/src/main/scala/interface/interface.scala`、`core/common.scala`（HPM 事件）、`core/RegFile.scala`（若写穿透需要）、`core/BreezePerformanceCounters.scala`。旧 MDU 字段：不再使用的删除；FP→GPR 共用的结果选择改为独立命名的选择，不能删除其功能。删除清单写进报告。 |
| Q10 FASE | FASE 的 `empty` 增加“记分板为空”，enter/寄存器读写/launch 都在后台写回完成后进行。中断接受条件不使用 FASE 的 empty。 |
| Q11 rd=x0 | rd=x0 的 MUL/DIV 不发射到单元，按无写回的普通指令在 WB 退休（无架构效果，也不会产生异常）。 |
| Q12 仲裁 | 本步固定优先级 DIV > MUL；长延迟整体优先于普通 WB。DIV 至多 1 项、MUL 流水被写口阻塞时停住且不再接收，所以 MUL 不会无限等待。以后加入 L1D、FPU 时按设计文档第 6 节 L1D > DIV > MUL > FPU 扩展。活性性质的环境假设：外部无永久停顿。 |
| Q13 HPM | 新事件编号顺延：`SB_STALL_MUL = 11`、`SB_STALL_DIV = 12`、`WB_PORT_CONFLICT = 13`，selector 合法上界与位宽随之更新。`sb_stall_*`：ID 有效且因该来源的记分板或级间 MDU 冒险而不能离开的拍数；两个来源同时成立时两个都计。`wb_port_conflict`：WB 因第 Q06 条让拍的拍数。 |
| Q14 复位、WFI、结束 | 复位作废全部项、清记分板。WFI 睡眠不停时钟，后台写回照常进行。ESTOP 在 WB 退休前等记分板为空（与 CSR 相同），保证测试结束时寄存器为最终值。 |
| Q15 旧检查替换 | 批准以下替换，每项在报告中列出“旧检查 → 新检查”：`PopCount(completion) <= 1` → 写口 grant 独热 + 每源保持断言；`RiscvMulUnit` 的 3 拍与全 flush 测试 → 新单元的 4 拍、commit/kill 测试（`SignedMul65x65` 及其测试原样保留，作为参照模型）；HPM 非法 selector=11 的测试 → 新的非法上界 14。其余旧测试和断言一律原样保留。 |
| Q16 DSP | KCU105 的器件 XCKU040 属于 Kintex UltraScale，DSP 即 DSP48E2。只用推断，不直接例化原语；可启用 Vivado retiming。验收：乘法器单独综合（out-of-context）报告 DSP/LUT，与旧乘法器对比；单核整机综合报告 100 MHz 下 WNS。 |
| Q17 基线 | 阶段二第一件事：在 Alan 上以 `d5672f5` 运行完整 `sbt test`，记录每个 suite 的通过/失败。已有失败只记录、不在 T01 修复；验收为“没有新增失败，通过数不少于基线”。CORE-003 的实际状态以这次基线结果为准。 |
| Q18 tandem | WB 提交记录增加 `rdPending` 标志（MDU 提交时置位，`rdData` 无效）；写口每次写回长延迟结果时输出一条写回事件（rd、data）。比对方按 rd 记住参考模型的期望值，在写回事件到达时比较。先查明仓库中现有的逐条比对器（`sim/breezecore`、`tests/ref/spike_ref.hpp` 等）并在报告中写清楚；若不存在可用的比对器，停下来报告，不要自建一套参考模型。 |

## 3. 其他修改

- spec 2.1、2.2、3.1、4.2、10 节按第 1 节修订重写：删除 ID 预约、kill 清位相关内容；F02 改为“`busy[r]` 当且仅当存在已提交、未写回、rd=r 的 MDU 项”；F03 改为“被 kill 的项永不写回”；新增断言“同一 rd 不同拍置位与清除”“`commit` 到达时单元必有未提交项”。
- spec 6.1 的时序例子改为：x5 在 DIV 的 WB 提交拍末置位；依赖 ADD 在 DIV 位于 EX/MEM/WB 时由级间冒险停住，此后由记分板停住；写回拍可同拍离开 ID。
- testplan 按上述决定补齐每条需求的具体期望，删除依赖已解决问题的“待定”标注。

## 4. 第二轮审阅（针对 `53bef68`）

Q01–Q18 的落实、重定向逐级核查（6.3）、附录 A 使用表、S01–S16/F01–F14 均已核对，可用。A01–A06 的决定如下，另增 A07、A08 两条补充规则。全部写入 spec/testplan 后即为冻结稿，不再保留“待确认”标注。

| ID | 决定 |
| --- | --- |
| A01 | 改 MUL enable：`mulEnable = !P4.valid \|\| (P4.committed && outReady)`，即 P4 有效且本拍不能离开（未提交，或已提交但未获写口）时四级整体停住；`req.ready = mulEnable`。`outValid = P4.valid && P4.committed` 不变。commit/kill 在停住时照常更新元数据；kill 清掉未提交的 P4 后下一拍恢复。P4 同拍 commit 时本拍 outValid 仍为 0，下一拍起才可离开。无反压时 MUL 在 P2 前已在 WB 提交，II=1 与 4 拍不受影响。无死锁论证写进 spec：P4 未提交时单元内全部是未提交项（提交按序），没有 MUL 结果争写口；更老的 WB 指令只与 DIV（至多 1 项、有界）竞争，必然推进。S11/F08 按此公式检查，删除“待 A01”标注。 |
| A02 | FENCE.I 保持在 MEM 发起，**不发** `killUncommitted`，只抑制同拍 EX 发射。理由：比它老的 MDU 只可能在 WB（本拍提交或已提交），比它年轻的在 EX/ID 尚未发射。第 2 节 Q03 括号中的 FENCE.I 是笔误，以本条为准。WB kill 组合即 exception/xRET/satp/interrupt/WFI。 |
| A03 | 批准迁移，只改驱动和观测点，不改输入向量、期望值、随机次数和容差：`BreezeBackendMulSpec`/`BreezeBackendDivSpec` 的观测点从“MDU memWbValid 时的 wbData”改为“该 rd 的后台写回事件（或写回后的 RF 值）”；`DivUnitSpec` 驱动在 `req.fire` 后补 `commit` 脉冲，原 flush 用例改为对未提交项发 `killUncommitted`。每项在报告中列“旧检查 → 新检查”。 |
| A04 | 确认 T01 不做参考模型比对，也不自建参考模型。完整的 Spike 比对以后单独立项（见 `docs/plans/2026-10-04-breeze-spike-cycle-model-mmu-ddr.md`）。T01 的取值正确性证据为：完整 `sbt test` 回归、MDU 定向和随机自检程序（程序自己比较最终寄存器/内存值）、仓库已有的 ISA 测试套件。 |
| A05 | 批准以下文件只为承载 trace 而修改：`core/BreezeCore.scala`（仅接线）、`sim/BreezeCoreTandem.scala`、`sim/BreezeCoreTandemParser.scala`、`sim/BreezeCoreSimSupport.scala`。内容：提交记录加 `rdPending`，新增后台写回事件（rd、data），runner 收集并打印。再加一个**协议自洽检查**（不是参考模型）：每条 `rdPending` 提交之后，该 rd 恰好有一次后台写回；没有 pending 的后台写回、同一 rd 重复 pending、pending 期间该 rd 被普通写、结束时仍有 pending，都报错。 |
| A06 | 确认 commit 与 kill 同拍只用于单元接口合同和模块级测试/cover。整核内不可达：WB 的那条指令要么是 MDU 提交，要么是发起 kill 的指令，而中断要求 `!memWbReg.valid`。中断接受条件不改。 |
| A07 | 写死 CSR 等空条件：CSR 离开 ID 需 `busy==0`，**并且** EX/MEM/WB 中没有已发射的 MDU。只看 busy 不够：CSR 离开 ID 后，更老的 MDU 才在 WB 提交并置位。整数 MDU 上这只影响性能，但这条规则是 FPU 步骤中 fflags 正确性的模板，所以现在就写成这样。HPM 11/12 的计数按此条件。ESTOP 在 WB 等 `busy==0` 不变（此时更老的都已提交）。 |
| A08 | 写死副作用门控：EX/MEM 发起的重定向（分支、JALR、SFENCE.VMA、FENCE.I）、BTB/预测训练、FENCE.I 的 flush 请求、访存请求，都只在本级本拍确实推进时发生。在 `wbPortStall`、ESTOP 等空及原 pipelineHold 期间一律不发，以免停住期间重复发出或丢失。6.3 每行的条件写成“该级 enable && 原条件”，S09/S13 按此检查。 |

## 5. 阶段二第 1 次停止（`b6dedbe`）

| ID | 决定 |
| --- | --- |
| B01 | 成立，是 A08 写法错误：把“发起许可”写成了“本级 enable”，而本级 enable 依赖的 pipelineHold 含请求拍。修订后的 A08 见 spec 5.1：自带单次状态的请求（dmem、FENCE.I flush、FPU）保持原条件；无单次状态的控制事件（EX 重定向、SFENCE.VMA、FENCE.I 重定向、BTB/预测训练）为 `原条件 && !downHold`，`downHold = wbPortStall \|\| estopWait`，只取决于 WB 与后台状态，不成环。 |
| B02（审阅发现） | 基线不可用：66 个失败全部是 ChiselSim 下 Verilator 5.028 对 CVFPU 报 `%Error-BLKANDNBLK` 导致构建失败，覆盖了 BreezeBackendMul/Div、BreezeCore、Privilege、WFI、FASE 等正是 T01 要回归的 suite；以此为基线，“无新增失败”没有意义。LiteX 流程用 `-Wno-fatal` 所以 ACT4 不受影响。处理：新增第 0 步，只为 ChiselSim 的 Verilator 构建对 **CVFPU 源文件** 关闭 `BLKANDNBLK`（优先用 Verilator 配置文件 `lint_off -rule BLKANDNBLK -file "<CVFPU 路径>/*"`；不允许全局 `-Wno-fatal` 或关闭其他规则），不改任何 RTL 和测试。在 `d5672f5` + 该提交上重跑完整 sbt test 作为新基线；剩余失败照实记录。若做不到只针对 CVFPU 关闭，按停止条件停下。 |

## 6. 形式化范围裁剪（2026-10-05 晚，工期原因）

- 第 3 步保留：MUL/DIV 单元级 F03、F05、F07、F08、F11，按 agent.md“形式化验证的做法”抽象算术、串行运行、单任务 3600 秒。
- 第 6 步的整后端形式化（F01、F02、F04、F06、F09、F10、F12、F13、F14）**不做**。对应规则由仿真断言 S01–S16 在全部定向/随机测试和完整回归中检查，报告中每条 F 写明“由 Sxx 在仿真中覆盖”。F06（活性）改为仿真看门狗：任何已提交项超过 2000 拍未写回即报错。
- 其余门槛不变：单元测试、完整 sbt 回归不差于新基线、ACT4 RV64IM 全通过、trace 协议检查 0 报错。

## 7. 第 3 步结束（2026-10-06，用户决定）

- 第 3 步到此结束。MUL、DIV 在审阅配置（`51b62b9`）下的 80 拍 BMC 均已 PASS，作为证据保留；已跑完的 cover 照实记录。**PDR 立即终止，不再运行**，也不再修复按模型哈希复用结果的脚本。
- 报告中 F03/F05/F07/F08/F11 写为“BMC-80 PASS + 单元仿真断言覆盖（37/37 单元测试），无界证明按用户决定放弃”。
- 直接进入第 4 步（后端集成）。第 4–7 步不含任何形式化任务；第 6 节中原由仿真覆盖的规则不变。
- 此后各任务默认不做形式化，规则见 `agent.md`“形式化验证的做法”。
