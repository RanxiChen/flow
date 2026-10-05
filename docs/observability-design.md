# Breeze 可观测性：性能计数器与调试记录

本文定义新版 Breeze v1 的性能计数器和调试记录。目的有两个：

1. **为结构取舍提供依据**：BTB/PHT 要不要扩、L1/L2 要不要加大、MSHR 要不要加到 2、MMIO 要不要做 posted write，都用实测比例来决定，而不是凭感觉。
2. **调试**：死锁或挂死时能直接看到谁在等谁。

相关设计：[`dcache-pipeline-design.md`](dcache-pipeline-design.md)、[`backend-pipeline-design.md`](backend-pipeline-design.md)、[`frontend-prediction-design.md`](frontend-prediction-design.md)。

## 1. 结构

| 部分 | 做法 |
| --- | --- |
| 计数器 | 每个事件一个**常开**的 48 bit 计数器，不像 HPM 那样只能同时选 8 个。48 bit 在 100 MHz 下约 32 天才溢出 |
| 位置 | 每核一组（前端、L1I、L1D、TLB、后端），L2 一组（按来源细分） |
| 控制 | 一个全局控制寄存器：`freeze`（所有计数器同一拍停止计数）、`clear`（同一拍清零）。读一组比例前先 freeze，读完再解冻，保证分子分母来自同一时刻 |
| 访问 | 集群新增一个 AXI4-Lite 从口 `perf`，计数器映射为只读寄存器。软件（BIOS、Linux 下的用户态工具经 `/dev/mem`）和 JTAG 都通过它读 |
| JTAG | 经 LiteX 外壳中的 JTAG→总线桥访问 `perf` 区域，不需要 CPU 参与，核挂死时也能读。用 LiteX 的 JTAGBone 还是扩展现有 FASE JTAG 见第 6 节 |
| HPM | 现有 8 个 `mhpmcounter` 保留，事件表扩展为本文的事件，供 Linux perf 等软件使用 |
| 离线分析 | 一个脚本读出全部计数器，计算第 3 节的比例并给出结论 |

成本估算：每核约 50 个、L2 约 30 个计数器，4 核合计约 230 个 × 48 bit ≈ 11k FF、约 3k LUT。KU040 的 FF 目前只用了约 8%。

## 2. 计数事件

### 2.1 前端与分支预测（每核）

| 事件 | 说明 |
| --- | --- |
| `br_retired` / `br_mispredict` | 退休的条件分支数；其中方向预测错的数 |
| `jump_retired` / `jump_btb_miss` | 实际跳转的分支与 JAL/JALR 数；其中 S1 时 BTB 未命中的数 |
| `btb_alloc` / `btb_evict_valid` | BTB 新分配次数；分配时替换掉仍有效表项的次数 |
| `jalr_retired` / `jalr_mispredict` | 非返回 JALR 数；其中目标错的数 |
| `ret_retired` / `ret_s1_predict` / `ret_mispredict` | 返回数；在 S1 就预测的数；最终目标错的数 |
| `ras_overflow` / `ras_underflow` | RAS 溢出、下溢次数 |
| `s3_redirect` / `be_redirect` | S3 快速重定向次数；后端重定向次数（按原因分：分支、JALR、异常、xRET、FENCE.I 等） |
| `fetch_buffer_empty_cycles` | 后端 ID 因 fetch buffer 为空而空等的拍数 |

### 2.2 L1I（每核）

| 事件 | 说明 |
| --- | --- |
| `icache_access` / `icache_miss` | 访问与 miss |
| `icache_miss_cycles` | 因 miss 而停顿的总拍数 |
| `prefetch_issued` / `prefetch_useful` / `prefetch_late` / `prefetch_unused` | 发出的预取；被 demand 用上的；demand 到达时预取还在途的；没用上就被替换的 |

### 2.3 L1D（每核）

| 事件 | 说明 |
| --- | --- |
| `load_access` / `load_miss`、`store_access` / `store_miss`、`upgrade` | 分类型的访问与 miss；S → M 升级次数 |
| `ptw_access` / `ptw_miss` | PTW 的 PTE 读与其中 miss |
| `hit_under_miss` | MSHR 等待期间完成的命中数 |
| `mshr_busy_cycles` | MSHR 被占用的总拍数（÷ miss 数 = 平均 miss 延迟） |
| `mshr_full_stall` / `same_line_stall` | 因 MSHR 已满、因与 MSHR 同行而暂不判定的拍数 |
| `s0_conflict_stall` | S0 store→load 冲突停顿拍数 |
| `writeback_dirty` / `writeback_clean` | 脏、干净 victim 的 Put 次数 |
| `probe_received` / `probe_held_cycles` | 收到的 probe 数；被压住的总拍数 |
| `lr_count` / `sc_fail` | LR 次数；SC 失败次数 |
| `mmio_read` / `mmio_write` / `mmio_cycles` | MMIO 读、写次数；等待总拍数 |

### 2.4 TLB（每核）

| 事件 | 说明 |
| --- | --- |
| `itlb_miss` / `dtlb_miss` | ITLB、DTLB miss |
| `ptw_walk` / `ptw_cycles` | PTW 次数；总拍数 |

### 2.5 L2（集群一份，按来源 = 各核 D、各核 I、DMA 细分）

| 事件 | 说明 |
| --- | --- |
| `l2_req` / `l2_hit` / `l2_need_probe` / `l2_miss` | 查询数，以及其中快路径命中、需要 probe、需要访问内存的数 |
| `l2_put` | 收到的 Put 数 |
| `l2_slot_full_stall` / `l2_set_wait` | 因两个慢槽都满、因 set 保护而等待的拍数 |
| `l2_probe_sent` / `l2_probe_cycles` | 发出的 probe 数；从发出到收齐答复的总拍数 |
| `mem_read` / `mem_read_cycles` | 内存读次数；总延迟拍数（÷ 次数 = 平均内存延迟） |
| `mem_two_inflight_cycles` | 两笔读同时在途的拍数 |
| `mem_write` | 写回内存次数 |

### 2.6 后端（每核）

| 事件 | 说明 |
| --- | --- |
| `cycles` / `instret` | 拍数与退休指令数（IPC） |
| `sb_stall_load` / `sb_stall_mul` / `sb_stall_div` / `sb_stall_fp` | 记分板停顿拍数，按造成停顿的来源分 |
| `load_use_stall` | load-use 停顿拍数 |
| `wb_port_conflict` | 长延迟结果占用写口、WB 让拍的次数 |
| `csr_serialize_cycles` | CSR 指令等记分板清空的拍数 |
| `l1d_unresolved_cycles` | WB 等 L1D 判定的拍数 |
| `refill_error` | 已提交访存的 refill 错误次数 |

## 3. 用法：比例与决策

| 比例 | 计算 | 用来决定 |
| --- | --- | --- |
| 方向预测错误率 | `br_mispredict / br_retired` | 是否加长历史、扩大 PHT |
| 跳转 BTB 缺失率 | `jump_btb_miss / jump_retired` | 是否扩大 BTB；配合 `btb_evict_valid` 判断是容量不够还是冲突 |
| 返回预测错误率 | `ret_mispredict / ret_retired`，配合 `ras_overflow` | 是否加深 RAS |
| L1I / L1D miss 率 | `icache_miss / icache_access` 等 | 是否加大 L1 |
| 预取效果 | `prefetch_useful / prefetch_issued`，`prefetch_late` | 是否预取更多行 |
| hit-under-miss 收益与 MSHR 压力 | `hit_under_miss`，`mshr_full_stall / cycles` | 是否把 MSHR 加到 2 |
| L2 miss 率 | `l2_miss / l2_req`（按来源） | 是否加大 L2 |
| L2 慢槽压力 | `l2_slot_full_stall / cycles` | 是否加慢槽 |
| 平均内存延迟 | `mem_read_cycles / mem_read` | 带宽/延迟是否是瓶颈 |
| MMIO 开销 | `mmio_cycles / cycles` | 是否做 posted write（D-cache 文档 2.10 节） |
| 停顿分解 | 2.6 节各项 / `cycles` | 下一步优化哪里 |

## 4. 调试记录

现有 FASE 飞行记录器（`fpga/kcu105/flight_recorder.md`）继续使用，增加以下事件：

| 事件 | 内容 |
| --- | --- |
| 记分板等待 | 停住的指令 PC、等待的寄存器、该寄存器的来源（L1D/MUL/DIV/FPU） |
| MSHR / 写回槽状态变化 | 分配、发出 Get、收到 Data/Ack、回放、释放；写回槽发 Put、收到 PutAck |
| probe 压住 | 被压住的行地址、原因（MSHR 同行 / 写回槽 / LR 窗口 / AMO 窗口）、解除时刻 |
| MMIO watchdog | 等待超过阈值的 MMIO 地址与类型 |

**挂死检测：**每核一个“最老指令未退休拍数”计数器，超过阈值时触发一次飞行记录快照，并在 `perf` 区域留下一个状态寄存器，记录各核最老指令 PC、记分板非零位、MSHR/写回槽状态、正在等待的 probe。L2 侧同样记录两个慢槽的状态和等待事件。这与 D-cache 文档 4.8 节的死锁 watchdog 记录的内容一致，仿真和上板用同一套。

## 5. 实施顺序

计数器随各模块一起实现，不单独成为一步：后端记分板、MDU、FPU、新 L1D、新 L2、前端改动各自带上本文对应的计数器和单元测试。`perf` 从口、freeze/clear 和读取脚本在集群顶层与第一个带计数器的模块一起完成。

## 6. 待拍板

| 项目 | 已确定的边界 | 待拍板内容 |
| --- | --- | --- |
| JTAG 读取路径 | 经 LiteX 外壳访问集群 `perf` 从口，不需要 CPU 参与 | 用 LiteX JTAGBone，还是扩展现有 FASE JTAG（目前 FASE 只支持单 hart，两者可能争用同一 BSCAN 通道，需核实） |
| 地址映射 | `perf` 为只读寄存器区域 | 在 LiteX MMIO 窗口中的具体地址与 PMA 表项 |
| 飞行记录器多核化 | 新事件定义见第 4 节 | FASE 记录器扩展到 4 核的存储预算 |
