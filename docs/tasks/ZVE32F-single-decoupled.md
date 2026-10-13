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

**2026-10-13 R07（用户批准）**：为装进 KCU105 并让 llama 热点真正走全行流水，`single-zve32f` 改为 DLEN 256、FMA/转换各 4 lane，新增全行加宽乘加单元，ALU 增加 vor/vxor/vsll/vsra。见 §8（改动）和 §9（性能微基准）。R07 RTL 尚未编译或仿真。

**2026-10-13 R07c（用户批准）**：R07b（`4cbc473`）的最差路径是 slowFp 里从不使用的 FP64 FMA slice。slowFp 改为只支持 FP32（KIND=3），之后不论 LUT 门槛如何都去布线。见 §11。R07c RTL 尚未编译。

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
- 不能改冻结配置：缓存容量、L2 slots、TLB、AXI64、PA32、VLEN/DLEN、window 深度、lane 数。R07 之后冻结值为 VLEN 512、DLEN 256、fmaLanes 4、conversionLanes 4、8 个执行单元（§8）。
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

## 8. R07：KCU105 缩配 + 全行加宽乘加（2026-10-13，用户批准）

### 8.1 为什么

1. **面积。** R06 综合 280,628 / 242,400 LUT，ZveMount 占 205,660，DSP 只用 62 / 1920。主要开销与 DLEN 成正比：7 个客户端各有 4 项 × 3 × 512 bit 的读返回队列（52.7k），16 条转换 lane（22.7k），8 条 FMA lane（11.6k），慢路径 3 × 8 × 512 bit 的 scratch（约 14k）。
2. **带宽上限。** decode 的权重流受 L2 的 2 个 miss 槽限制（32 B line，DRAM 往返约 30–40 拍时约 1.6–2 B/拍）。Q4_0 在 2 B/拍时约 3.5 个权重/拍，DLEN 256 的整数行（32 个 e8/拍）远大于需要。
3. **热点原先不在快路径上。** R06 只有 vadd/vsub/vand/vsrl.vx/vmv 走全行 ALU；vwmacc、vwmul、vsll、vsra 都由 `ZveElementEngine` 逐元素执行，每个元素要好几拍。Q4_0×Q8_0 的整数内积因此比标量还慢。缩配解决不了这个问题，必须补全行乘加。

### 8.2 改动

| 文件 | 内容 |
|---|---|
| `rvv/zve/ZveTypes.scala` | `ZveParams.soc`：`vlen=512, dlen=8*lineBytes`（=256），`fmaLanes=4, conversionLanes=4`；`units=8`，新增 `ZveUnit.mac=7`。默认 `ZveParams()` 不变（独立 RTL 和旧单元测试仍是 512） |
| `rvv/zve/ZveStreamEngine.scala` | 去掉 `rowBytes==64` 的硬编码，改为 `rowBytes >= lineBytes`（2 的幂）。SoC 中一行正好一条 line；未对齐时一条 line 仍然最多跨两行，结构不变，只把 64/32/16 常数改成由 R 推出 |
| `rvv/zve/ZveMacVectorEngine.scala`（新） | vwmul/vwmulu/vwmulsu、vwmacc/vwmaccu/vwmaccsu/vwmaccus，.vv/.vx，SEW 8→16、16→32。目标第 t 行取源第 t>>1 行的 t(0) 半行；一次三元组读（vs2 行、vs1 行、旧 vd 行）产出一行。流水：返回 → S1 操作数寄存 → S2 乘积寄存（每 lane 一个 17×17，DSP）→ 加旧 vd → 4 项写队列。只在队列能容纳全部在途项时接收返回，流水本身不停顿 |
| `rvv/zve/ZveAluVectorEngine.scala` | 新增 vor、vxor（所有形式）和 vsll/vsra（.vx/.vi，标量移位量）。.vv 移位仍在 element engine |
| `rvv/zve/ZveDecode.scala` | `fast` 增加上述 ALU 操作；新增 `mac` 路由到 `ZveUnit.mac` |
| `rvv/zve/ZveCoprocessor.scala` | 接入第 8 个单元（VRF 客户端 8 个） |

`single`、`single-rvv` 不实例化任何 zve 代码，E1 应保持逐文件一致。

### 8.3 新增不变量

- R07-1 MAC 结果按 2×SEW 取模，符号按规范：vwmulsu = s(vs2)×u(vs1)，vwmaccsu = s(vs1)×u(vs2)，vwmaccus = u(rs1)×s(vs2)。被屏蔽、尾部、vstart 以前的元素不改。
- R07-2 加宽的合法重叠（源在 vd 组的高半部分，源 EMUL≥1）结果正确：行按升序读，第 t 行的写不早于它自己的读，被覆盖的源行只被 ≤t 的目标行使用。
- R07-3 `MAC write queue exceeded its reservation` 永不触发；MAC 在途期间 `drained` 为 0。
- R07-4 stream 引擎在 R=L 时，对 offset 0..31、vl 1..VLMAX、e8/e16/e32、LMUL 1..8、mask/whole/mask-memory 的字节结果与 R06（R=64）参考模型一致。

### 8.4 codex 要做的检查（主机按 `docs/cross-project/simulation-host.md`）

| # | 内容 | 判定 |
|---|---|---|
| T0' | `sbt compile`；E1；生成 `single-zve32f` | 编译/生成 exit 0；E1 逐文件一致 |
| T3' | T3 全部用例在 `ZveParams.soc(32,32)` 下重跑（R=L=32），并保留默认参数一组 | 逐字节对照参考模型 |
| T10 | MAC 单元：7 条指令 × .vv/.vx × SEW 8/16 × LMUL 1/2..4（加宽后 ≤8）× vl 随机 × vstart 0/非 0 × mask 开关 × 合法高半重叠；VRF 返回随机反压 | 与软件参考逐元素一致 |
| T11 | ALU 新操作：vor/vxor（.vv/.vx/.vi）、vsll/vsra（.vx/.vi），三种 SEW，移位量取低 log2(SEW) 位 | 同上 |
| T12 | 回归：T1 冒烟、T2 标量回归、Zve 单元测试全部在新 profile 上重跑 | 全部通过 |
| T13 | KCU105 综合 + 布局布线（Vivado 在 Alan） | 先报综合 LUT/FF/DSP/BRAM 和层次分布；LUT ≤ 70% 再继续布线；布线后 WNS ≥ 0 @100 MHz 才生成 bitstream。不满足就停下报告，**不要**自行再缩 lane 或 DLEN |

### 8.5 codex 的修改权限（在 §5 之外补充）

- 可以自行修：MAC/ALU/stream 新代码中的位宽、切片、半行选择、符号扩展、enable 计算、计数器错误；DSP 推断属性（例如为乘法加 `use_dsp`），只要不改流水级数和行为。
- 必须上报：把 MAC 退回 element engine；减少 MAC lane 或让它一次只处理半行；把 R07-2 的重叠改成 Serial；改动 DLEN、lane 数、写队列深度以外的结构参数。
- 时序不收敛时先报告关键路径（前 10 条，带层次），不要擅自插入全局停顿或降频。

## 9. 性能微基准（T9 的具体化，只观测、不改控制）

目的是在上板前回答一个问题：快路径能不能接近 L2 的带宽上限，还是被 solo、预翻译、stream 不重叠这些固定开销拖住。

### 9.1 程序

裸机 `tests/cluster/zve_gemv_q4.c`（或 .S，codex 自己定），在 `single`（标量）和 `single-zve32f`（向量）上各跑一次。工作集必须远大于 L2（64 KB）。

- 形状：K = 896（Qwen2.5-0.5B hidden），N = 256（向量版；工作集约 143 KB）。标量版太慢的话可以用 N=64，按每 block 周期数比较。
- 权重布局（离线 repack，向量版）：64 行一组交错。每个 block（32 个权重）存 16 × 64 B 的 qs，第 j 字节、第 r 行在 `j*64 + r`；低 nibble 是元素 j，高 nibble 是元素 j+16，**已经转成有符号 4 bit（原值 xor 8）**。之后是 64 个 f32 scale。每组每 block 1280 B。
- 激活：Q8_0，32 个 int8 + f32 scale（运行时量化由我们的代码完成，所以直接存 f32）。
- 标量版用 ggml 参考写法（`ggml_vec_dot_q4_0_q8_0` 的 generic 实现，-O2 rv64gc）。两版结果按 f32 容差（相对 1e-5）对比。

### 9.2 向量内循环（VLEN 512，每组 64 行、每个 block）

```
vsetvli e8,m4    ; vle8 v8, (qs)        256 B，j=0..3（共 4 次覆盖 j=0..15）
对每个 j：
  vsetvli e8,m1,vl=64
  vsll.vi  v1, v(8+j), 4
  vsra.vi  v1, v1, 4            ; 低 nibble：w[.][j]-8
  vsra.vi  v2, v(8+j), 4        ; 高 nibble：w[.][j+16]-8
  vwmacc.vx v12, a[j],    v1     ; v12-v13：64 × i16 累加（|和| ≤ 32×8×127 < 2^15）
  vwmacc.vx v12, a[j+16], v2
每个 block 末尾：
  vsetvli e16,m2 ; vfwcvt.f.x.v v16, v12        ; i16 → f32（m4）
  vsetvli e32,m4 ; vle32 v28, (dw)              ; 64 个行 scale
                   vmv.v.i v20, 0 ; vfmacc.vf v20, da, v28   ; da*dw（不用 vfmul，它在慢路径）
                   vfmacc.vv v24, v20, v16      ; 64 行 f32 累加
  vsetvli e16,m2 ; vmv.v.i v12, 0
每组末尾：vse32 v24 → y
```

`vfwcvt.f.x.v` 在 SEW=16 时输出 FP32，在 Zve32f 中合法；如果 decode 判为非法或走了 Serial，按 bug 上报，不要改 kernel 绕开。a[j] 用标量 `lb` 取（Q8_0 的激活每 token 只有 K 字节，常驻 L1D）。不要在内循环用 vredsum、vmv.x.s、vfmv.f.s：前者走 element engine，后两者要等向量排空再写回标量。

### 9.3 要报告的量

- 每组每 block 的周期数，以及权重 B/拍（向量版、标量版），相对标量的加速比。
- §6 的全部计数：向量访存 solo 等待周期、预翻译周期、相邻 vle 之间的空闲周期、stream ID 占用直方图、L2 向量端口停顿和 slotFull 次数、verdict replay（应为 0）、credit 等待、fast/Serial 比例（应接近 100% fast）。
- 每个执行单元的忙周期占比（alu、mac、conversion、fma、stream），用来判断瓶颈在算还是在访存。
- 如果有 L2 miss 往返延迟的计数，就一并报告（用来校准 1.6–2 B/拍的估算）。

### 9.4 判读（由用户/Claude 决定下一步，codex 只报告）

- 权重带宽接近 1.5 B/拍以上：瓶颈在 L2 槽数和 AXI，再提升需要改冻结的 L2（加槽或预取）。
- 远低于这个值，且 solo/预翻译/vle 间隔占主导：下一刀是放松向量访存的 solo、让 stream 指令重叠（这会改动 §5.2，需要批准）。
- mac 或 alu 忙周期接近 100%：看是单元自身每条指令的固定开销（读 → 写 → ack 才完成），还是 lane 不够。

## 10. R07b：时序断点 + 去掉重复的慢路径（2026-10-13，用户批准）

### 10.1 R07 的证据（Alan `flow-runs/zve32f-r07-20261013`）

- 综合：202,230 / 242,400 LUT（83.4%），DSP 69，综合 WNS −0.948 ns。最差路径 29 级 LUT：`window/commands_4_age` → `oldestPending`/`judgement` 的年龄比较树 → `verdicts`（flow-through 队列）→ `decision_valid` → WB → `exRedirect` → `frontend/cache/data_0_ext` 的 WEBWE。这是向量状态组合驱动标量控制，违反 BE2 的原则，与利用率无关。
- 参照：冻结基线 SoC 布线时只用了 35.6% LUT，WNS 在默认策略下为 −0.481，用 Explore 策略为 +0.021，余量几乎为零。
- 层次分布：两份完整的 `ZveElementEngine`（cross_0 20.4k，slow.integer_0 19.9k），其中 `resultRows` 16×256 一项 11.7k（3 个写口，无法映射成 LUTRAM）；`ZveWindow` 17.0k；`ZveVrfAccess` 27.9k；MAC 只占 970 LUT 和 24 DSP。
- gemv 微基准（第二次运行通过，maxErrorBits=0）：

| | 标量（N=64） | 向量（N=256） |
|---|---|---|
| kernel 周期 | 957,511 | 116,358 |
| 64 行 block 数 | 28 | 112 |
| 每个 block 的周期数 | 34,197 | 1,039 |
| 权重 B/拍 | 0.037 | 1.23 |
| solo 周期 | — | 7,540（每 block 67，占 6.5%） |
| vector verdict replay（下标 6） | 0 | 0 |

  每个 block 加速 33×。1.23 B/拍落在 §9.4 两种判读之间，而 solo 只占 6.5%，所以瓶颈不在 solo。**我的推断（未验证）**：内核把 32 条 vwmacc 都累加到同一个 v12 上，形成 RAW 链；每条 vwmacc 要经过 读 VRF → S1 → S2 → 写 → ack → 完成 → window 释放依赖 → mailbox 才能发下一条，估计每条 12–15 拍，一个 block 约 400–500 拍。也就是说，瓶颈可能在依赖链的延迟，而不在 L2。

### 10.2 改动（行为不变，只改结构）

| 文件 | 内容 |
|---|---|
| `ZveWindow.scala` | 年龄比较树换成 16×16 的 age matrix：`before(i)(j)` 在分配时设置（新项比所有 live 项年轻）。window 的年龄按分配顺序发放，所以在 live 项之间它与 `RvvAge.older` 完全等价。oldest、judgement、每个单元的 oldest、`olderLive`/`conflicts` 都改用它。`serialAge` 那一处比较保留：Serial 项的 slot 可能已经释放 |
| `ZveMount.scala` | `verdicts` 队列 `flow=false`。时间线：EX 进 ingress，下一拍进 window，再下一拍 judgement 入队，入队后一拍 WB 看到。非 solo 向量指令在 WB 仍能看到 verdict；solo 向量访存最多多 1 拍 |
| `ZveSlowUnit.scala` | 只保留单 lane FP（vfmul/vfdiv/vfrdiv/vfsqrt），删除第二份 `ZveElementEngine` |
| `ZveDecode.scala` | 原来进入 integer 队列的非热点整数逐元素指令改去 cross 单元；integer 队列只接收 `slowFloat` |
| `ZveElementEngine.scala` | `resultRows` 的三处写（capture、update、finalize，三个状态互斥）合并成一个写口，使它能映射成 LUTRAM |

预计 LUT 在 160–175k（66–72%）。R07 我估错了 40k 以上，这次也只以综合结果为准。

### 10.3 新增或改变的不变量

- R07b-1 age matrix：对任意两个 live 项，`before(i)(j) == RvvAge.older(age_i, age_j)`。可以加一条只在仿真中检查的断言来验证（codex 可自行添加）。
- R07b-2 正常程序中 vector verdict replay 仍为 0（gemv、smoke、所有 Zve 单元测试）。出现非 0 说明 §10.2 的时间线不成立，应上报，不要改回 flow-through。
- R07b-3 cross 单元现在串行执行所有逐元素整数和置换指令；不能为提速再复制一份。

### 10.4 codex 要做的事

1. 合并：codex 目前也在修改这个 worktree（perf 计数）。我改的文件是 `ZveWindow`、`ZveMount`（只动了 verdict 队列那一段）、`ZveSlowUnit`、`ZveDecode`、`ZveElementEngine`。
2. 编译；E1；T3'/T10/T11/T12 全部重跑；ZveWindowSpec 和 T4 需要覆盖 slot 复用以后的年龄顺序（分配、释放、再分配交错）。
3. 只跑综合，报告：LUT、DSP、层次分布、最差 10 条路径（带层次）。门槛：LUT ≤ 70%，最差路径不再从 window 穿到 WB 或 frontend，且综合 WNS ≥ 0。全部满足才布线（先默认策略，再 Explore）。
4. gemv 补充两组实验，用来区分是访存瓶颈还是依赖链瓶颈（只改软件，不改 RTL）：
   - a. 向量版 N=64（权重 35 KB，能放进 L2），比较每个 block 的周期数。如果仍然约 1,000，说明瓶颈不在 DRAM/L2 槽。
   - b. 多累加器版：4 个独立的 e16 m2 累加器（例如 v4、v6、v12、v14），每个 j 轮换使用；临时寄存器用两组（v1/v2、v3/v5）交替；block 末尾先把 4 个累加器加起来再做 vfwcvt。
   - 两组都报告每个 block 的周期数、B/拍，以及 perfBusy 里每个单元的忙周期占比。
5. 注意：现在的内核对所有 block 都用同一个 fa0 作为 Q8 scale，作为基准可以接受；移植到 llama 时要改成逐 block 载入 da。

## 11. R07c：slowFp 改为只支持 FP32，然后布线（2026-10-13，用户批准）

### 11.1 R07b 的证据（SHA `4cbc473`，`records/zve32f-r07-20261013/`）

- 综合：LUT 180,894（74.63%），综合 WNS −0.380 ns，130 个端点违例。
- 最差路径（−0.380 ns，35 级）：`zveMount/slowFp/fp/i_fpnew/gen_operation_groups[0]/gen_parallel_slices[1]/.../i_fma`，`mid_pipe_sum_q` → `out_pipe_status_q[UF]`，信号 `mantissa[51]`。slice 1 是 **FP64** 的 FMA。slowFp 用的是 `ZveFpnewService(2)`，wrapper 里 KIND=2 是 `Features RV64D`。Zve32f 的 ELEN=32，这个 slice 从来不会被用到，但仍然被综合进来。
- 第 2–10 条（−0.259 ns，27 级）：`l1d/internal3_req_src` → `frontend/cache/data_*_ext/Memory_reg_*/WEBWE`。这是标量侧本来就有的弱端点：冻结基线 `records/f2-owner-ce-20261012/route-default/soc-worst-30.rpt` 里，同一个 WEBWE 端点在默认布线后就已经是 −0.460 ns（起点 `fpUnit`），基线最差是 frontend cache 内部的 −0.481 ns，用 Explore 收到 +0.021。

### 11.2 改动（Claude 已写，未编译）

| 文件 | 内容 |
|---|---|
| `ZveFpnewWrapper.sv` | 新增 `KIND=3`：WIDTH 32，`ZveFeatures`（只开 FP32），ADDMUL 用 PARALLEL，DIVSQRT 用 MERGED（THMULTI），NONCOMP 用 PARALLEL，CONV 关闭。KIND 0/1/2 的展开不变 |
| `ZveMount.scala` | `slowFp` 由 `ZveFpnewService(2)` 改为 `(3)` |
| `ZveCoprocessor.scala` | `ZveStandaloneHarness` 的共享 FP 服务同样改为 `(3)`，单测和产品保持一致 |
| `ZveFpnewService.scala` | 默认 kind 由 2 改为 3，并更新注释 |

**为什么可以这样改**（codex 要逐条复核，任何一条不成立就上报）：

1. slowFp 只有两个客户端，经由 `ZveSharedFpMux` 接入。
   - `ZveSlowUnit`：断言只放行 vfmul、vfdiv、vfrdiv、vfsqrt。格式在 `ZveFpVectorEngine.scala:74` 固定为 src/dst=0（FP32）。
   - cross 单元的 `ZveElementEngine`：`:134-135` 格式固定为 FP32，操作只有 ADD（vfred[ou]sum）、MINMAX（vfredmin/max）、CMP（vmf*）。
   - 两个客户端都不发 I2F、F2I、F2F，也不发 FP64。
2. 在 RV64D 配置下，FP32 的 ADDMUL 和 NONCOMP 本来就走各自独立的 FP32 slice（PARALLEL，PipeRegs 3/1）。所以去掉 FP64 slice 以后，FP32 的运算和延迟应当不变。只有 DIVSQRT 从 FP32+FP64 的 MERGED 变成只有 FP32 的 MERGED。THMULTI 的 `fpnew_divsqrt_th_64_multi` 支持任意 WIDTH，内部补零到 64 位，所以算法本身不变，但延迟可能有变化。
3. 输出：KIND=3 走 wrapper 里已有的 `{32'hffffffff, result[31:0]}` 分支，和 RV64D 对 FP32 结果做的 NaN-box 一致。CMP 的结果在 RV64D 下高 32 位是 0，现在变成全 1。两个消费方都只用 bit 0（`ZveElementEngine.scala:230` 的 `value(0)`）或低 32 位（`:247` 按 mask 截取），因此不受影响。
4. 输入：`ZveFeatures` 的 `EnableNanBox=0`，只取 operand 的 `[31:0]`。客户端本来就送 NaN-boxed 的 FP32，低 32 位就是原值。
5. 标量 FPU（`backend/fpUnit`，`FlowFpnewBlackBox`）完全不动，标量 D 扩展保持不变。`single`、`single-rvv` 不实例化 Zve，E1 必须继续逐文件一致。

### 11.3 新增不变量

- R07c-1：slowFp 的每个请求都满足 `sourceFormat==0 && destinationFormat==0`，并且 operation ∈ {ADD, MUL, DIV, SQRT, MINMAX, CMP}。codex 可以在 `ZveFpnewService` 加一条仅在仿真中检查的断言（kind=3 时生效）。
- R07c-2：对相同的 FP32 输入，KIND=3 与 KIND=2 的 `result[31:0]` 和 `status` 逐位相同（CMP 只比较 bit 0）。

### 11.4 codex 要做的事（主机按 `docs/cross-project/simulation-host.md`）

1. 编译；三个 profile 的 RTL 生成；E1（`single` 85 个文件、`single-rvv` 118 个文件逐文件一致）。
2. 新增差分单测 `ZveSlowFpKindSpec`：同时实例化 `ZveFpnewService(2)` 和 `(3)`，送同一串请求，比较 R07c-2。覆盖要求：
   - 操作：ADD、MUL、DIV、SQRT、MINMAX（rm 0/1）、CMP（rm 0/1/2）。
   - 5 种舍入模式。
   - 特殊值：±0、±inf、qNaN、sNaN、最小和最大 subnormal、最大 normal、除以 0、对负数开方。
   - 随机值至少 10k 个。
   - 每个请求带不同 tag，下游随机反压，并检查乱序返回时 tag 是否对应。
   - 另外报告两种 kind 的 DIV/SQRT 延迟。延迟可以不同，但必须如实记录。
3. 重跑：`flow.rvv.zve.*` 全部、`ZveWindowSpec`/T4、`ZveSystemSmokeSpec`（含 riscv-tests）、`ZveGemvQ4Spec`（4 个 ELF）。gemv 的周期数应与 R07b 相同或只差几拍，误差为 0，verdict replay 为 0。
4. 综合，报告：LUT、DSP、`ZveMount`/`slowFp` 的层次 LUT、最差 10 条不同端点的路径（带层次）。
5. **不再用 §10.4 的 LUT 70% 门槛挡住布线**。只要综合成功，就依次做：
   - a. `place_design` + `route_design`（默认策略），之后 `phys_opt_design`。报告布线 WNS/TNS、违例端点数、最差 30 条路径、拥塞报告（`report_design_analysis -congestion`）。
   - b. 默认策略 WNS < 0 时，用 Explore（`place_design -directive Explore`、`phys_opt -directive AggressiveExplore`、`route_design -directive Explore`）再跑一次。基线就是这样收到 +0.021 的，脚本沿用 f2-owner-ce 的 route 流程。
   - c. 任一策略布线 WNS ≥ 0：生成 bitstream，记录 SHA256，并说明用的是哪个策略。
6. 记录：`records/zve32f-r07c-20261013/result.md`，格式同 R07b，写明实际执行主机。

### 11.5 codex 的修改权限（在 §5、§8.5 之外补充）

- 可以自行修改：差分单测和断言；Vivado 脚本、策略和 directive；R07c-1 断言触发后对 wrapper 的修正（必须保持 FP32-only）。
- 必须上报，不得自行处理：
  - R07c-1 或 R07c-2 失败，说明 §11.2 的前提不成立。**不要改回 KIND=2。**
  - 布线 WNS < 0：按路径分类（向量内部、向量到标量、标量既有路径），附上最差 30 条和拥塞数据，由用户决定降频（例如 90 MHz）还是改微架构。
  - 尤其不要自行修改 frontend cache 的 WEBWE 路径（例如给 refill 写打一拍）。那是标量共享代码，会破坏 E1，需要用户批准。
  - 不要通过改 `ZveParams` 缩配、删单元或改流水来换取时序。
