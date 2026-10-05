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
