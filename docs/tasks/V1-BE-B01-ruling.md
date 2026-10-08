# V1-BE B01 裁定及 ND01/ND02

> 2026-10-08 SOC-3b 适用说明：本文保留旧 T01/B01/集成基线。普通 WB 当拍写口、late.fire 等同 RF 写回、6 次冲突、S2 load bypass、fatal 接收当拍生效等旧后端条款，已由用户最新裁定覆盖。当前后端实现以 [`backend-timing-contract.md`](../backend-timing-contract.md) §4、[`backend-v1-rtl-spec.md`](../backend-v1-rtl-spec.md) 和 [`SOC-3b 任务`](../tasks/SOC-3b-wb-split.md) 为准；未覆盖的协议/安全规则沿用。W2 无条件完成、不碰 busy，后台来源为 lateReg/DIV/MUL/FPU；FENCE.I/SFENCE 不额外等待 W2/lateReg。硬件执行主机以每次重读的共享 simulation-host.md 为准，不能沿用本文历史 Alan-only 规则。

状态：Claude 裁定，用户 2026-10-06 批准。冻结文件已按本裁定修订并重新登记哈希（`tools/frozen.json`）。codex 从本提交起继续 [`V1-BE-backend-spec-and-rtl.md`](V1-BE-backend-spec-and-rtl.md)，任务书其余规则不变。

## 1. B01 结论

codex 报告的矛盾成立，根因是设计文档的写口规则：长延迟结果优先、WB 普通写让拍、后端四级保持。后端因此会在 WB 自行停顿，而 `L1DCoreIO` 没有来自后端的保持输入，后端停住时 L1D 的 S1/S2 继续前进，`resp` 与 WB 错位。FENCE.I/SFENCE.VMA 在 WB 等待时，其后若跟着访存指令，也会出同样的问题。

不增加接口信号，改规则：

1. **停顿方向规则**：后端自身发起的停顿只能落在 ID（不发射）或 EX（不 fire 请求，MEM 插气泡）。MEM/WB 只在两种情况下保持：L1D `s2Hold`（此时 L1D 的 S1/S2 同样保持）；或 WB 是串行指令且其后各级都是气泡。
2. **写口 WB 优先**：WB 普通写当拍写入并提交，同一寄存器堆的长延迟结果保持（ready=0）。四个来源之间的优先级不变（L1D > DIV > MUL > FPU）。
3. **ID 饥饿保护**：每个寄存器堆一个饥饿计数。本拍有长延迟 valid 而该堆没有任何长延迟写获准时 +1（饱和于 3），否则清 0。计数为 3 且该堆没有在途保护气泡时，ID 本拍不发射，并置在途标记；该堆有长延迟写获准时清除标记。计数是寄存器，ID 的判断不依赖当拍仲裁结果，不引入 WB→ID 组合路径。最高优先级来源自首次落败起至多 6 拍获写口（T21）。
4. **串行发射**：FENCE.I、SFENCE.VMA、WFI、ESTOP 等会在 WB 因后端条件等待的指令，离开 ID 后，ID 不再发射年轻指令，直到它离开 WB。
5. **L1D 回放不在 S2 等写口**：回放 Load 在 S2 直接驱动 `late`。`late.ready=0` 时数据存入 MSHR 的 `lateData`，转入新状态 LATE，S2 照常前进；新 miss 在 LATE 期间按"MSHR 满"处理（s2Hold）；LATE 不压住 probe。这样 L1D S2 的保持从不取决于后端，`s2Hold` 与 `late.ready` 之间没有组合环，probe 活性也不依赖后端。

codex 的两个反例都已覆盖：B/C 场景见新合同行 T22；命中 Load 在 WB 时与 late 同拍，Load 当拍写入，late 次拍写入。

## 2. 冻结文件修订

| 文件 | 修订 |
| --- | --- |
| `backend-pipeline-design.md` | §3 增加停顿方向规则；§6 改为 WB 优先 + ID 饥饿保护；§7 增加串行发射 |
| `backend-timing-contract.md` | T12 改期望（late 次拍写入，后端不保持）；T13、T15 补前提；T19、T20 补"不发射年轻指令"的断言；新增 T21（饥饿保护）、T22（B01 反例）；§0 默认无冲突；§2 允许饥饿气泡，P02 后接 nop；§3 增加停顿方向断言；§4 补写口规则和 `wb_port_conflict` 的新定义 |
| `l1d-rtl-spec.md` | §1.1 `late` 由 MSHR 保持（字段不变）；§6.2 增加 LATE 状态和 `lateData`；§10.2 probe 在离开 REPLAY 时解除；§12 活性论证；§13.1 增加断言 |
| `dcache-pipeline-design.md` | §2.9 迟到数据由 MSHR 保持、不占 S2 |
| `backend-rtl-spec.md` | §5 加 v1 覆盖说明：v1 不存在 `wbPortStall`，其余规则沿用 |

## 3. ND01、ND02

| ID | 裁定 |
| --- | --- |
| ND01 | **接受**。FP kill 后关闭新 FP 分配，直到 CVFPU busy=0；已提交项的返回照常写回，作废项的返回丢弃。这是 EX 停顿，不违反停顿方向规则。代价只出现在陷入后紧接 FP 的情况，可以接受 |
| ND02 | **接受**。CSR 还要等已提交的 FP→x0 项返回，避免漏累积 fflags |

## 4. codex 继续事项

1. 按本裁定更新 `backend-v1-rtl-spec.md`：B01 处改写为上述规则，规则末尾标注 `[V1-BE-B01-ruling§1]`。
2. `V1Writeback` 改为 WB 优先，并增加饥饿计数和在途标记；HPM 13 按合同 §4 计数。
3. L1D 的 LATE 状态随 L1D RTL 一起实现。后端测试使用的行为 L1D 模型必须遵守：`late.ready=0` 时 S1/S2 照常前进，`resp` 不受 `late.ready` 影响。
4. 切换 `BreezeBackend`，实现并运行合同 T01–T22、T02b、P01–P10，以及停顿方向断言（MEM 有访存指令时，MEM/WB 保持必伴随 `s2Hold`）。
5. 停止条件不变：发现冻结文件之间仍有矛盾时，只报告该项，其余照做。
