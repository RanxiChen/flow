# MEM：多核 litmus 加强（交给 codex 跑和修）

起点：多核门槛 `8fcf486` 通过之后、包含本任务测试文件的提交。测试由 Claude 编写，本地未编译、未运行（`agent.md`：sbt 只在 Alan 执行）。先读 [`MEM-multicore-tests.md`](MEM-multicore-tests.md) 与 [`MEM-multicore-tests-report.md`](MEM-multicore-tests-report.md) 的“随机统计与 litmus”一节。

背景：`L1DL2MultiCoreSpec` 的 MP+fence 64 轮从未出现 flag=新，禁止结果检查的前提一次也没有满足；SB+fence 从未出现 01。原因是两线程只用 0–5 条 FENCE 错开，时差远小于一次 store miss 的所有权往返。本任务改为系统扫描时差、交叉预热缓存状态，并把关键前提的命中次数作为失败条件。原多核 spec 不变。

## 1. 文件（`design/src/test/scala/memsys/`）

| 文件 | 内容 |
| --- | --- |
| `L1DL2LitmusSpec.scala` | 新文件。`OpScheduler`（按指定拍把操作交给某核，bench 中排在核之前）；`Env`（同多核 spec，加 scheduler）；`Inst`（每轮新行、唯一值、值→标签）；`Shape`（变量、参与核、扫描轴、程序、禁止结果、必需结果）；`calibrate`、`applyWarm`、`runShape`；14 个形状 |

只复用 `L1DL2MultiHarness`、`CoreDriver`、`MultiCoreOracle`、`CoherenceMonitor` 等已有设施，不修改它们。

## 2. 扫描设计

- **T 的测量（自适应）**：每个用例开始时，核 0 对三种行各做 3 次 store miss：未缓存、核 1 共享（需 Inv）、核 1 持 M（需 Inv 取数据）。T = 从 fire 到监视器看到 grant 的最大拍数，限制在 [8, 300]。
- **时差轴**：两核形状按 `d`（核 1 相对核 0 的起始拍）扫描。非对称形状（MP、CoRR 等）取 −T..2T；对称形状（SB、LB、2+2W）取 −2T..2T。每轴 96 点均匀分布，去重。同址形状另加线程内间隔 `g`（0..T，4 点；用本核私有行的命中 load 填充），`d` 取 48 点。三核、四核形状用 16×16 的二维网格：WRC/ISA2 为 `d1`（P1−P0）× `d2`（P2−P1），IRIW 为写者间 `w` × 读者偏移 `s`。
- **预热**：每个变量取 I（未缓存，新行）、S（所有参与核共享）、W（其写者持 M）、O（另一参与核持 M）之一。按 `(轮号×5) mod 组合数` 轮换，与扫描点交叉。W/O 写入一个唯一的 pre 值，作为该轮的“旧值”。
- **地址**：每 3 轮有一轮把各变量放进同一 L1D set 和同一 L2 set（`stride`），其余轮次放在相邻行。
- **轮数**：两核单轴 96×4 = 384（MP 无 fence 为 192），同址 48×4×2 = 384，三核/四核 256；共约 5000 轮。

每轮：分类 → 预热并静止 → scheduler 释放各线程 → 静止 → 按程序位置取各次读的返回值并映射为标签（"0" = 旧值，其余为写入标签；读到既非旧值也非本轮写入的值立即失败）。需要最终值的形状再经 DMA 读回。每个用例结束时监视器空闲且无违规，并经 DMA 读回全部用过的行：Free 字精确，Racy 字由 oracle 判定。纯 load/store 形状的变量归为 Racy，因此 oracle 的逐字检查（不回退、只读写入过的值）也同时生效；含 AMO/LR 的形状归为 Free。

## 3. 用例

阈值 `Threshold = 8`：每个必需结果至少观察 8 次，否则失败。FENCE = `L1DOp.Fence`（等 `drained`）= fence rw,rw。普通 load/store 不带 aq/rl（L1D 只对 LR/SC/AMO 生效，与 ISA 一致），因此 release/acquire 形状用 AMOSWAP.rl、AMOOR.aq（加 0，读值并写回原值）和 LR.aq 承载。

| 形状 | 核 | 程序 | 禁止（RVWMO） | 必需结果 |
| --- | --- | --- | --- | --- |
| MP+fence.rw.rw | 2 | P0: Wx; F; Wy ‖ P1: Ry r1; F; Rx r2 | r1=1 ∧ r2=0 | r1=1；r1=0 |
| MP+amoswap.rl+amoor.aq | 2 | P0: Wx; AMOSWAP.rl y ‖ P1: AMOOR.aq y r1; Rx r2 | r1=1 ∧ r2=0（PPO 规则 5/6） | r1=1；r1=0 |
| MP+amoswap.rl+lr.aq | 2 | P1 以 LR.aq 读 y | 同上 | 同上 |
| MP（无 fence） | 2 | 两写、两读无序 | 无（全部允许） | 无，只打印分布 |
| SB+fence.rw.rw | 2 | P0: Wx; F; Ry ‖ P1: Wy; F; Rx | r0=0 ∧ r1=0 | (0,1)；(1,0) |
| LB+fence.rw.rw | 2 | P0: Rx; F; Wy ‖ P1: Ry; F; Wx | r0=1 ∧ r1=1 | (0,1)；(1,0) |
| CoRR | 2 | P0: Wx ‖ P1: Rx; g; Rx | r1=1 ∧ r2=0 | r1=1；(r1=0, r2=1) |
| CoWR | 2 | P0: Wx=a; g; Rx ‖ P1: Wx=b | r0=旧；r0=b ∧ 最终 a | r0=b；最终 a；最终 b |
| CoWW | 2 | P0: Wx=1; g; Wx=2 ‖ P1: Rx; g; Rx | 最终 ≠ 2；观察者读序回退 | 观察到中间值 1 |
| CoRW | 2 | P0: Rx; g; Wx=a ‖ P1: Wx=b | r0=a；r0=b ∧ 最终 b | r0=b；最终 a；最终 b |
| 2+2W+fence.rw.rw | 2 | P0: Wx=a1; F; Wy=a2 ‖ P1: Wy=b1; F; Wx=b2 | 最终 x=a1 ∧ y=b1 | 交错 (b2,a2)；(a1,a2)；(b2,b1) |
| WRC+fence | 3（four 几何） | P0: Wx ‖ P1: Rx; F; Wy ‖ P2: Ry; F; Rx | r1=1 ∧ r2=1 ∧ r3=0（多副本原子） | r1=1 ∧ r2=1 |
| ISA2+fence | 3（four 几何） | P0: Wx; F; Wy ‖ P1: Ry; F; Wz ‖ P2: Rz; F; Rx | r1=1 ∧ r2=1 ∧ r3=0 | r1=1 ∧ r2=1 |
| IRIW+fence | 4 | 两写者各写一个变量；P2 先读 x 后读 y，P3 先读 y 后读 x | a=1, b=0, c=1, d=0 | (a=1,b=0)；(c=1,d=0) |

`nCores` 必须使 `l2Sets` 为 2 的幂，3 核不可构造，所以三核形状用 4 核几何，核 3 空闲。禁止结果依据 RISC-V 非特权规范第 17 章（RVWMO）的 preserved program order 与附录 A 的同名 litmus；每个形状旁的注释写明了理由。

运行时间估计：每轮约 300–600 拍，全部约 2–3 M 拍。按现有多核 spec 的仿真速度，约 15–40 分钟。请在报告中记录实际耗时。

## 4. 运行

```sh
sbt "testOnly flow.config.BreezeCoreConfigSpec flow.memsys.MemAgentsSpec flow.memsys.L1DCacheSpec flow.memsys.L2HomeSpec flow.memsys.MemSkeletonElabSpec flow.memsys.L1DPermissionsSpec"
sbt "testOnly flow.memsys.L1DL2SystemSpec"
sbt "testOnly flow.memsys.L1DL2MultiCoreSpec"
sbt "testOnly flow.memsys.L1DL2LitmusSpec"
```

前三条全部通过后才运行第四条。失败时用 `-z "<形状名>"` 单独复跑。

## 5. 规则

1. 先修编译错误：语法与 ChiselSim API 用法错误直接修，不需要报告。
2. **出现禁止结果视为 RTL bug**。按失败信息中的轮号、扫描点、预热状态与 T，写出最小 directed 复现并保留为新用例；修 RTL 并同步 [`l1d-rtl-spec.md`](../l1d-rtl-spec.md) / [`coherence-l2-rtl-spec.md`](../coherence-l2-rtl-spec.md)。若认为禁止结果判断本身有误，必须在报告中引用 RVWMO 规则逐条论证，再修改测试。
3. **必需结果命中不足**时，先判断是扫描没有覆盖到，还是 RTL 确实无法产生该时序。前者扩大扫描（加宽范围、加密点数、增加轮数或预热组合），并在报告中写明改动；后者必须给出依据（波形、监视器消息、拍数推算）。不允许降低阈值、删除形状或删除必需结果。
4. 其他测试规则同 [`MEM-single-core-tests.md`](MEM-single-core-tests.md) §3：`L1DCoreIO`、后端、MMU 不改；不能为通过而删检查、降规模或改期望。

## 6. 报告

写 `docs/tasks/MEM-litmus-tests-report.md`：

- 被测 SHA、四条命令、每个 spec 的通过数/总数与退出码、实际耗时。
- 每个形状的 T、轮数、结果分布，以及每个必需结果的命中数与命中的扫描范围（来自 `info` 输出）。
- 每个 RTL 修复的根因（一两句）及对应的最小复现。
- 每处测试改动及其依据。
- 仍失败或跳过的用例。

## 7. 漏检面（如实记录）

- 只有两核和四核默认几何，未在 `stress` 几何上运行 litmus。
- 驱动边界是 `L1DCoreIO`，不含后端与真实指令流；L1D 之外的后端重排不在范围内。
- 没有 SC 参与的形状，没有混合大小（非 8 B）访问，没有 fence 的细分类型（fence r,r、fence w,w 等；L1D 只实现 rw,rw）。
- 预热组合在扫描点之间轮换，不是全交叉；三核/四核形状每个网格点只覆盖一个预热组合。
- 不注入 kill、TLB miss、AXI 背压之外的扰动（AXI 延迟为 `AxiMemory` 默认随机）。
