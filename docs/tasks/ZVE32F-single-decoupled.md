# single-zve32f：解耦式 Zve32f 后端（RTL 交接说明）

2026-10-13。RTL 由 Claude 编写，codex 负责测试、审核和修 bug。本文说明三件事：为什么这样设计，必须检查和监测哪些内容，以及 codex 可以自行修改的范围与必须上报的情况。

- 分支：`feat/zve32f-single-20261013`，worktree `.worktrees/zve32f-single`，基线为 main `94636c6`（已定型单核）。
- 本文取代 `docs/tasks/RVV-main-single-zve32f-pipeline-plan.md` 中“统一 ROB + VRF 版本重命名 + 推测执行向量”的方案。那个方案要把定型后端改写成乱序核，周期长、风险高，而 llama 的性能上限实际在访存，见 §1。

## 0. 当前状态（如实记录）

| 项目 | 状态 |
|---|---|
| `sbt compile` | 通过（Alan；cloud_chen SSH 超时） |
| `GenerateBreezeCluster single-zve32f gshare linux coherent-dma` | 生成通过，141 个 SV 文件 |
| `single`、`single-rvv` 与 main 等价 | 去掉绝对路径和断言行号后**逐文件一致**（85 / 117 个文件） |
| 功能仿真 | 2026-10-13 Alan：Zve 冒烟、5 个单核程序、13 个 riscv-tests 代表程序通过；Zve 定向单元测试 14/14 通过。范围仅限所列程序，见 `records/zve32f-20261013/result.md` |
| KCU105 实现 | Vivado 2022.2 综合成功；`place_design` 前 DRC 报 LUT 超量，退出码 1。没有布局布线、bitstream 或上板 Linux 证据，见同一结果记录 |

当前冒烟和定向单元测试不能证明完整 Zve32f 兼容性；容量障碍解决前不能生成此配置的 KCU105 bitstream。

## 1. 为什么这样设计

目标是让单核 llama.cpp（Q4_0/Q8_0）通过向量化获得加速。Qwen2.5-0.5B Q4_0 每生成一个 token 要读约 0.4 GB 权重。向量化之后，decode 的上限由 L2 的 2 个 miss 槽、32 B line 和 AXI64 决定，计算侧再怎么流水化也越不过这个上限。因此这次的取舍是：

1. **标量后端少改，向量在提交后解耦执行（业界常见做法，参考 Saturn/Ara 一类设计）。** 向量指令在 EX 送出，WB 根据 verdict 提交。提交以后不可能再产生精确异常，所以向量单元不需要 ROB、不需要撤销，标量也不用等向量执行完。
2. **在提交前完成访存翻译。** unit-stride 访存（普通、whole-register、mask，vstart=0）在提交前由 `ZveTranslator` 查 dTLB（最多两页），并对每个 32 B line 做 PMP/PMA 检查。全部通过才走快路径 `ZveStreamEngine`，否则交给 Serial 慢路径，由慢路径给出精确异常、vstart 和 FOF 结果。
3. **快慢分离。** 热点指令走整行流水；其余指令走 R06 的逐元素引擎（`ZveElementEngine`、`ZveMemoryEngine`），只要求正确。完整的 Zve32f 由慢路径兜底。
4. **向量访存经过真实 L2，并保持一致。** 向量旁路标量 L1D，从 L2 的一个新的一致性客户端口发 Read/MaskWrite：读会 probe L1D 拿到 dirty 数据，写会先让 L1D 的副本失效。标量 L1D 继续写回，不像旧的 single-rvv 那样改成写穿而拖慢未向量化的代码。
5. **冻结配置不变。** L1 容量、L2 的 2 个慢槽、TLB 容量、AXI64 内存口、PA32 都不改。只是新增 `single-zve32f` 这个 profile，其余 profile 的电路逐文件不变。

## 2. 指令的生命周期

```
ID: 向量指令按 credit 预留；向量访存为 solo（等较早指令排空且 cpuDrained）
EX: 捕获 rs1/rs2/frs1，以及 vl/vtype/vstart/vxrm/frm 快照 → ZveMount.ingress
     ├─ 快路径访存：ZveTranslator 预翻译（dTLB + 逐 line PMP/PMA）→ pre.valid
     └─ 其他指令：直接进入 window
window 按序给出 verdict：Ok / Exc（非法）/ Serial
WB:  Ok  → consume → 下一拍 commit → window 在之后执行，与标量解耦
     Exc → 精确 trap（cause 2，tval=inst）
     Serial（solo，WB 保持）→ serialGo → 慢路径执行完 → decision（fault/vstart/FOF newVl）→ 提交或 trap
完成：fflags/vxsat 累加到 CSR；vmv.x.s / vfmv.f.s / vcpop / vfirst 的结果经 writeback.copro 写回，scoreboard 负责保护目标寄存器
```

### 2.1 修改清单

| 文件 | 内容 |
|---|---|
| `config/config.scala`, `BreezeMemConfig.scala` | 新增 `zve` 参数、`vectorL2Ids`、`singleZve32f` profile |
| `rvv/RvvTypes.scala`, `RvvCsr.scala` | `elen=32` 时按 Zve32 规则判定 vill；`fullV=false` 时 misa.V 清零 |
| `core/RegFile.scala` | misa.V 改由 `fullV` 决定 |
| `coherence/*`, `l2/L2Home.scala` | 可选的向量端口（端口号 `vecPort`），请求/响应 ID 位宽由 `reqIdBits` 决定，FastMaskWrite 回送 id，向量端口的 refill 错误上报 |
| `l1i/L1IClient.scala` | ID 变宽以后只取第 0 位，并加了越界断言 |
| `backend/BreezeBackend.scala` | 全部改动包在 `if (isZve)` 中：ZveMount 接线、vset 不再 Serial、向量访存 solo、标量访存只按向量访存的待完成状态排序、FPR 标量操作数和结果、fflags 合并 |
| `top/BreezeCluster.scala` | `ZveTlbShare`（dTLB 共享）、`ZveL2Client` 接到 L2 向量端口 |
| `top/GenerateBreezeCluster.scala` | 新 profile；marker 中 `memoryBits=64`、`zve=zve32f` |
| `rvv/zve/*`（从 R06 `9b15d76` 移入） | 新增 `ZveStreamEngine`、`ZveMount`（含 Translator、L2Client、TlbShare）；ZveCoprocessor 增加第 7 个单元；Window 新增 `memoryLive/storeLive` 输出；Decode 中 `pre.valid` 改为走 stream，并且不再标为 Serial |

注意：R06 的 `ZveMemoryEngine` 用的是**旧的逐元素状态机版本**（`9b15d76`），没有采用 `ff41869` 的双 lane TLB 改动，因为那个改动替换了公共 Sv39Tlb，属于冻结的 MMU。这个引擎现在只是 Serial 慢路径，所以保持串行可以接受。

## 3. 必须遵守的不变量（审核和测试都以此为准）

### 3.1 精确性
- I1 向量指令提交（WB consume）以后，不会再产生架构可见的异常。快路径必须在提交前完成全部翻译和 PMP/PMA 检查。快路径的总线错误只能报 `hartFatal`，不能悄悄忽略。
- I2 Serial 指令的 decision 在慢路径完成以后才发出。fault 必须是按元素顺序最早的那个，vstart 等于该元素序号，FOF 在第 0 个元素之后出错时只缩短 vl、不 trap。
- I3 被 kill 的向量指令（ingress、staged、window 中未提交的项）不产生任何副作用：不写 VRF、不访存、不改 CSR。已经提交的项不会被 kill。

### 3.2 内存顺序（同一 hart 内）
- M1 较年轻的标量 load 只在没有较早向量 store 尚未完成时发射（`storesPending`）；标量 store、AMO、LR/SC、FENCE、MMIO 等待所有较早向量访存完成（`memoryPending`）。
- M2 向量访存发射前，较早的标量 store 已经离开 L1D 的 PS/MSHR（solo + `cpuDrainedD`）；L1D 中的 dirty 数据由 L2 probe 取得。
- M3 向量 store 的 WriteAck 表示数据已在 L2 可见，stream 指令只在收到全部 ack 后才 complete。向量访存之间按 window 顺序执行：stream 单元一次执行一条，Serial 是屏障。
- M4 SD DMA、L1D、向量端口三方对同一 line 的并发访问必须保持一致（L2 原有协议）。

### 3.3 资源与活性
- R1 credit：ingress、staged、window 和 EX 中的项总数不超过 window−1。因此 window 不会满，非 solo 向量指令在 WB 不会因为 verdict 未到而 replay。**正常程序中 replay 计数应为 0**，出现非 0 视为 bug。
- R2 stream 的 ID：每个 ID 从请求到响应都被占用；`busy` 清零之前不允许 complete。L2 向量端口 RSP↓ FIFO 深度为 8，在途请求最多 4（stream）+ 1（element）。
- R3 dTLB 共享：只有在标量当拍没有发起查询时，向量才拿锁；向量持锁期间标量的 `canAccept` 为 0。不允许出现标量和向量互相等待的死锁（向量访存期间标量流水线本来就是空的）。
- R4 SATP、SFENCE、PMP 等 CSR 写走 Serial，要等 `vectorDrainedD`，其中包括 translator 空闲。翻译上下文在向量访存执行期间保持稳定。

### 3.4 等价性门槛（硬性要求）
- E1 `single`（含 coherent-dma）和 `single-rvv` 重新生成后，与 `94636c6` 的输出逐文件一致（只允许绝对路径和断言行号不同）。每次提交都要重新检查；结果不一致时一律视为错误，不能接受。

## 4. codex 要做的检查

主机按 `docs/cross-project/simulation-host.md` 选择，先 cloud_chen，不可用再用 Alan。记录 SHA、命令、exit 和日志。远端工作区要包含 `.gitmodules`，否则 `ClusterSim.repoRoot` 找不到仓库根。

| # | 内容 | 判定标准 |
|---|---|---|
| T0 | E1 等价性，以及 single-zve32f 的生成 | 逐文件一致；生成 exit 0 |
| T1 | 修正并运行 `zve_smoke.S`（10 项定向检查）。编译用 `-march=rv64gc_zve32f -mabi=lp64d`，在 Makefile 里加对应规则 | tohost=1；失败码对应到具体机制 |
| T2 | 用 single-zve32f 跑标量回归：tests/cluster 的单核程序、riscv-tests p 环境（ui/um/ua/uf/ud/si/mi） | 与 single 结果相同 |
| T3 | ZveStreamEngine 单元测试：起始偏移 0..31、vl 取 1..VLMAX、e8/e16/e32、LMUL 1..8、mask、whole、mask load/store、跨页、L2 响应乱序加随机反压 | 逐字节对照参考模型；尾部和被屏蔽的字节保持不变 |
| T4 | ZveMount：同一拍 kill + preDone、kill 时有 staged 项、verdict 队列、Serial 匹配、commit 和 kill 的先后 | 满足 I3、R1；断言不触发 |
| T5 | 一致性 litmus：标量 dirty line 后跟向量 load，向量 store 后跟标量 load 和 L1D 中的旧副本；再加 SD DMA；随机 AXI 延迟 | 满足 M1–M4 |
| T6 | 精确异常：快路径被拒（PMP 洞、MMIO、缺页、跨页第二页缺页）后走 Serial；Sv39 下 vle/vse；FOF | 满足 I2；cause/tval/vstart/epc 正确 |
| T7 | 状态：vset 之后紧跟向量指令、x0/x0 形式、vill、VS/FS Off；读 fflags/vxsat/vstart 时等向量排空 | 结果正确，或按规范 trap |
| T8 | 指令覆盖：riscv-vector-tests 的 Zve32f 子集（VLEN=512），用 Spike `--isa=rv64gcv_zve32f --varch=vlen:512,elen:32` 作参考 | 逐项记录通过/失败，不改期望值 |
| T9 | 性能观测（只观测，不改控制，见 §6） | 输出报告 |

## 5. codex 的修改权限

### 5.1 可以自行修改（修完后在提交说明里写清楚原因和证据）
- 位宽、off-by-one、字节 enable 和掩码计算错误、tag 或 ID 拼接错误、漏接的线、错误的复位值。
- 计数器和 credit 记账的错误，前提是修正以后仍然满足 R1、R2。
- 断言写错（误报）：先证明断言本身错了，再改断言，并在说明里写出反例。
- 测试、harness、Makefile、smoke 程序的错误。
- 添加只用于观测的性能计数器或 debug 输出，不能影响任何控制信号。
- 在 `if (isZve)` 范围内补上遗漏的保护条件（例如某种标量访存类别漏判），而且只能更保守，不能放宽。

### 5.2 不得修改，必须先上报
- **不能把流水改回状态机或串行执行。** 不能让 ZveStreamEngine 一次只允许一笔在途；不能把 unit-stride 快路径改成 Serial；不能把整行流水单元退化成逐元素执行；不能为绕开 bug 而降低 `streamCredits`。
- 不能引入全局 hold 或全局停顿，不能让 WB 等向量执行完成。solo 只用于向量访存的 verdict。不能把 vset 改回 Serial。
- 不能改标量流水结构（ID/EX/MEM/PERM/WB/W2）和 BE2 规则，不能改 L1D、L2 的协议语义和槽位数。
- 不能改冻结配置：缓存容量、L2 slots、TLB、AXI64、PA32、VLEN/DLEN、window 深度、lane 数。
- 不能让 E1 不一致，也就是不能改动 `single`/`single-rvv` 的电路。
- 不能用非一致性的 AXI 直连替换 L2 向量端口，也不能取消提交前翻译。
- 不能删除或放宽断言，不能修改 golden 或期望值来让测试通过。
- 如果发现设计层面的缺陷（例如不变量本身不成立、存在死锁），停下来写问题报告：反例波形或日志、所在位置、建议改法。由用户或 Claude 决定怎么改。

## 6. 已知限制和需要监测的性能点

下面这些是第一版有意接受的代价，**codex 不要修**，只需要测量并报告：

| 点 | 原因 | 报告的量 |
|---|---|---|
| 每条向量访存都是 solo：要等标量流水排空和 cpuDrained，然后等预翻译 | 用最简单的方式保证 WB 前 verdict 就绪 | 每条向量访存从 ID 到 consume 的周期数 |
| 预翻译对每个 32 B line 单独做一次 PMP/PMA 检查（每周期 1 个 line） | 保守且简单 | 预翻译延迟随字节数的分布 |
| stream 一次只执行一条指令，指令之间不重叠 | 保证向量访存之间的顺序 | 相邻 vle 之间的空闲周期 |
| L2 每个端口同一时刻只能有一个请求在流水线里（`inPipe`），约每 3 拍接收 1 个；只有 2 个 slow slot | 冻结的 L2 设计 | 向量端口的请求接收间隔、slotFull 停顿 |
| Serial 路径逐元素、一次一个 AXI 请求 | 只要求正确 | 快路径和 Serial 路径的指令比例（llama kernel 中应接近 100% 走快路径） |
| 快路径遇到 MMIO/device 一律拒绝，走 Serial 后以 access fault 结束 | 平台不支持对设备做向量访存 | 只需记录 |
| tandem 无法比较向量的写回 | 向量在提交后才执行 | 只需记录 |

要观测的计数项：向量访存 solo 等待周期、预翻译周期、stream ID 占用的直方图、L2 向量端口停顿、verdict replay 次数（应为 0）、vector credit 等待周期、fast/Serial 次数。

## 7. 软件侧（不属于这次 RTL 的范围）
- 编译选项用 `-march=rv64gc_zve32f`。misa.V 为 0，Linux 需要通过 DT 的 `riscv,isa-extensions` 声明 zve32f。要核实内核 6.18 能否在只有 Zve32x/f 的情况下开启用户态向量上下文；不能就需要补丁。
- llama.cpp 的 RVV kernel 依赖 `__riscv_v`，需要重写 Q4_0/Q8_0 的 kernel（VLEN=512）。fp16 的 scale 在 repack 时转成 fp32，因为 Zve32f 没有 Zvfh。
