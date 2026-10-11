# SOC-3d d4ae2fc 四核关键路径分析

主仓 `d4ae2fc24df26f597d4811cc2bbb12b9e8ae47da`，CVFPU `b32aeeb6eda9a61b99185e39e78b74d4fb5340be`；small 四核生产 Linux/gshare，KCU105 `xcku040-ffva1156-2-e`，Vivado 2022.2 build 3671981，sys_clk 10 ns。输入及启动记录在 `records/soc3d-local-control-20261009/`。

本次只在 Alan 查询既有布局及物理优化 checkpoint 的独立副本，不修改 RTL、约束或运行中的实现，不重启构建。以下连线延时主要是估计值，不是最终布线证据。

## 证据绑定

- 原 checkpoint：`/home/chen/FUN/flow-runs/soc3d-local-d4ae2fc/small/gateware/xilinx_kcu105_place.dcp`。
- 副本 SHA-256：`bb4d21fe0ba6a1ed160aa53e564188dd5c8efa9e95e7e2a0eb1e6e43369a1694`，175,574,316 bytes。
- 主机 Alan，cwd `/home/chen/FUN/flow-runs/soc3d-local-d4ae2fc/path-inspection-1720`。
- 命令：任务 `alan-env.sh` 启动 Vivado `-mode batch -notrace -source /home/chen/FUN/flow-runs/soc3d-local-d4ae2fc/path-inspection-1720/query.tcl`。
- UTC 09:32:57–09:36:35，exit 0，wall 218.107 s。
- 本地原始报告：`records/soc3d-local-control-20261009/path-inspection-1720/`，含输入、执行结果、Tcl、`setup-60.rpt`、各 family 报告、timing-summary、利用率及 fanout 报告。

## 布局及物理优化后的违约

Setup WNS **-1.469 ns**、TNS **-15,223.935 ns**，31,963 个失败端点；全局 hold WHS -0.190 ns、86 个失败端点。最差 setup 是性能事件采样，多个独立机制几乎同样差。表中连线占比属于数据路径，逻辑级数是 Vivado Logic Levels。

| 机制 | slack ns | 逻辑级 | 估计连线占比 |
|---|---:|---:|---:|
| 指令读出/记分板到性能事件 | -1.469 | 20 | 81.652% |
| dTLB 到数据访问 PMP | -1.465 | 24 | 81.941% |
| PTW walk-cache 查询 | -1.465 | 8 | 92.737% |
| I-cache 命中到 realigner 字缓冲 | -1.464 | 9 | 79.545% |
| FP64 FMA 归一化 | -1.457 | 23 | 86.621% |
| I-cache 返回控制到首字捕获 | -1.456 | 9 | 90.384% |
| BTB 查找及预测目标选择 | -1.456 | 12 | 88.321% |
| iTLB 到取指权限结果 | -1.455 | 18 | 81.443% |
| 浮点队列完成/占用控制 | -1.428 | 17 | 85.943% |
| D-cache 后级控制到下一请求捕获 | -1.426 | 29 | 78.896% |
| FP64 输入对齐及 sticky | -1.422 | 23 | 76.194% |
| pending-store / AMO 数据捕获 | -1.413 | 22 | 81.253% |
| 浮点完成到后端发射 | -1.297 | 24 | 84.619% |
| MMIO 请求到 L2 排队 RAM 写使能 | -1.261 | 18 | 87.234% |

## 机制与优化判断

1. **性能事件仍跨完整译码和依赖链。** FIFO 读指针 → 指令读出/操作数分类 → 记分板 busy/source 及离开条件 → rawEvents MUL/DIV source-stall。终点不是计数器加法器。`Scoreboard.scala:64–91` 已将功能 hazard 与来源归因分开，但事件仍依赖当拍来源查询与 `!idLeave`；`BreezeBackend.scala:566–567` 接事件，`BreezePerformanceCounters.scala:36` 才首次寄存。isFp 分类有一条 fanout 12、1.773 ns 的估计连线，跨 SLICE_X13Y162 到 X50Y168。应将操作数预译码及来源归因放到近端，评估分段事件采样；若增加流水，必须同步 selector/inhibit/CSR 覆写顺序，保持计数语义。

2. **TLB/PMP 两端都还有违约。** dTLB read-address → PA 读取/命中选择 → fresh/held/refresh PA → PMP 比较/permission 合并仍有长链，iTLB → accessFault 同类也失败。应减少 PA 选择后的串行工作，局部保存候选 PA 与访问末地址。新增权限边界必须同时处理 replay/kill、异常返回及 store 副作用。不要简单复制整套每-way PMP 比较阵列进一步增加面积。

3. **PTW 本轮差的是 walk-cache 查询。** `Sv39WalkCache.scala:19–23` 的 ASID/tag 比较、命中归约、PPN 一热选择接 `Sv39Ptw.scala:39–42` 的 middle/upper/root 基址选择。不是前轮 PTE 解码到 refill 链。仅 8 级但连线占 92.737%；hits_0 fanout 48、1.547 ns，lookup-hit 到 PTW 1.609 ns，另一个 wBase 相关网 2.113 ns。优先每-way 命中/PPN 局部收束，必要时寄存查询结果再进入内存请求阶段。

4. **I-cache 命中选择到 realigner 没有完全切开。** 新 L1IClient 返回寄存处理一类一致性返回，但 tag RAM/等待控制 → way/data 选择 → wordBuf/firstWord 仍长。s2_wt_en_OH[0] fanout 87 的估计连线达 **3.809 ns**；到 realigner 的另一数据网 1.607 ns。建议在 cache 附近先选好返回字和元信息，再交给对齐器；局部候选捕获与最终 valid 分开时，须保持 backpressure、跨字压缩指令及 redirect 丢弃语义。

5. **浮点实际是 FP64，且两段都偏长。** `fpnew_pkg.sv` 中 FP32=0、FP64=1，报告 gen_parallel_slices[1] 对应 FP64。FMA mid_pipe_sum → norm_q.sticky 仍串接 leading-zero count、移位量生成、宽移位、尾数修正及 sticky 归约（`fpnew_fma.sv:568–691`）。已有归一化/舍入边界没有切断此链；输入对齐到 pre_q.sticky 也有 23 级、-1.422 ns。应平衡 FP64 输入对齐和归一化内部工作量，valid/tag/flags、stall/flush 随新边界移动。浮点队列 entry valid 广播及 completion → WB → scoreboard → EX 另有 -1.428 / -1.297 ns，FMA 数据流水优化不会自动解决这些控制路径。

6. **BTB 匹配和目标选择有远距离广播。** 路径经过 BTB tag match、命中归约及 target mux，BTB 内一条 fanout 264 的估计网延时 **2.747 ns**，BTB 输出到前端目标寄存又有 **2.875 ns**。对应 `BreezeFrontend.scala:220–252,402`。优先局部匹配/选目标后末级收束。新增预测级会影响取指请求归属、恢复和吞吐，须成套设计。

7. **D-cache 晚控制链仍长。** internal2.valid 经 miss/probe、后端响应/redirect、TLB ready/kill、下一请求准入及 payload mux、末地址计算到下一 S1，仍有 29 级。对应 `L1DCache.scala:129–199,331–337`。终点叫 pmpEnd，这条主要是上游控制与 mux。应提前形成各来源 payload/末地址候选，晚控制只决定局部接收/valid，减少后级状态对宽数据网络的广播。pending-store 另有 `L1DCache.scala:658–674` 的 store/AMO 授权、结果选择及字节偏移移位；须合理分离数据生成与最终写许可，保持失败 store 和 AMO 的副作用规则。

8. **L2 入口也是独立候选。** 已确认 MMIO 地址寄存到 L2 排队 RAM WE 为 -1.261 ns；具体地址/准入归属链仍需进一步核对，不只依据层次名称指定 RTL 修改。

## 面积和证据边界

布局后 LUT **201,114 / 242,400 = 82.97%**，FF 107,417 / 484,800 = 22.16%，BRAM 199 / 600 = 33.17%。上述路径多数连线占比约 79–93%。应同时减少广播、宽 mux 级联和物理距离；单凭占用率不能确定拥塞位置，低逻辑级数也不等于安全。

### 单核通过而四核仍违约的证据

追加读取 Alan 同任务 `tiny/gateware/xilinx_kcu105_utilization_place.rpt`：单核 LUT 58,179 / 242,400 = **24.00%**，FF 34,295 = 7.07%，BRAM 76 = 12.67%。四核不只增加仲裁器，还复制前端、后端、MMU、FPU 和私有 L1；`BreezeCluster.scala:48–55` 明确各核独立实例。`BreezeMemGeometry` 的 L2 容量按每核 65,536 bytes 扩展，因此单核/四核共享 L2 分别为 64/256 KiB，四核也增加目录/入口逻辑；不能把两种配置差异只归于核数或一个仲裁器。

此外，四核原始 `small/gateware/vivado.log` 第 6189 行明确记录：`[Route 35-448] Estimated Global/Short routing congestion is level 5 (32x32)`，工具指出 level 5 及以上可能影响可布线性和时序收敛。这比仅凭 LUT 利用率推断拥塞更直接，但没有证明所有路径分别受哪一处拥塞影响。

四核 FP64 归一化路径的整个数据链位于 `backend_2/fpUnit/.../i_fma`，不经过共享 L2/四核仲裁；逻辑延时 1.511 ns，估计连线 9.783 ns。核内逻辑在单核有空间靠近摆放，四核时与其他核及共享资源竞争位置和连线，即使 RTL 功能链不变也可能变慢。静态时序分析检查寄存器之间所有合法传播路径，与运行时四核是否同时访存不是同一个问题。

单核内部最差 PMP 余量只有 +0.076 ns，说明通过余量很薄。四核目前详细路径来自布局+物理优化估计，而单核是最终布线；它们能说明配置规模及薄余量问题，不能把延时差额全部定量归为拥塞，四核最终值仍待 routed 报告。

下一轮优先成组处理 D-cache 控制/数据候选、TLB/PMP 收束、I-cache 返回边界和 FP64 内部分段，同时评估性能事件、walk-cache、BTB 局部化。多条路径相差不到 0.02 ns，不能只修当前排名第一的端点。

同 SHA 单核最终 WNS +0.067 ns（整体最差是 IDELAYCTRL reset recovery）；核心 PMP +0.076、CPU permission +0.099、pending-store +0.284、fetch accessFault +0.296 ns。这是单核证据，不能替代四核证明。

四核日志 post-place -2.623 ns、post-phys-opt -1.469 ns，早期布线曾到 -1.266 ns；最终布线尚未完成。本轮没有修改 RTL、测试或时序例外，原 LiteX/Vivado 任务继续运行。

注意：family-icache_return.rpt 匹配的是 reset recovery，不能据此声明 I-cache 数据通过；PLRU family 过滤未匹配有效路径，也不能声明 PLRU 已通过。

## 下一轮组合优化方案（建议采用，尚未实现）

基线为 d4ae2fc，同一候选同时覆盖长运算链与局部选择/广播。保持生产单核/四核、器件与 100 MHz 约束。保留当前任务及单核通过结果，不为讨论重启构建。

### A. D-cache / 权限：增加一个真正的判定级

| 阶段 | 内容 |
|---|---|
| S0 | 各来源独立形成 payload、访问末地址和索引候选；保持原优先级的一热 grant；晚控制只决定接收/有效性 |
| S1 | 接收 TLB、tag/data 查询结果；捕获选定 PA、翻译状态、原始查询快照及请求所有权，不在此级完成全套 PMP |
| S2 | 对已寄存 PA 做 PMP/PMA；并行 tag 比较与命中数据选择；把权限、命中、异常和数据结果登记到 S3 |
| S3 | 根据有效且未失效的判定完成响应、MSHR 分配或 pending-store 接收；副作用仅在合法完成时发生 |

CPU 命中判定相对现行流水增加一拍，目标保持现有可接受请求间隔；额外级不是让请求逐笔串行化。新增 CPU/internal 槽必须纳入 olderDrained、acquire、whole-line/RMW 所有权及 probe 前进规则。TLB miss 接收窗口的年轻请求数和容量需要按新增级重新计算；kill、permissionEvent、probe/refill 导致的快照失效必须覆盖所有在途级，旧快照只能重查，不能授权写入。

末地址在各来源候选中计算再选择，不能先选完整请求再做末地址算术；普通 store 的字节对齐候选提前生成，AMO 结果遵守原本地 RMW 时序，数据候选不等于写许可。权限 checker 不按 TLB way 复制，也不同时保留旧、新两套功能路径。生产验证所需独立旧算法断言仍保留。

### B. I-cache：局部数据选择后增加命中返回边界

cache tag 命中、way 数据选择及 fault/PC 元信息在 cache 出口形成统一返回 payload，寄存后进入 realigner，切断 tag RAM/way 广播到 wordBuf/firstWord 的长链。命中返回增加一拍；demand/prefetch 的来源、请求 epoch、在途归属与新边界一致，已有 L1IClient 一致性返回寄存保留。返回反压容量依据输入接口的实际 ready 能力和同拍接收上界计算，保证连续返回不丢失；redirect/flush 丢弃旧响应不能撤销已接受协议事务。

### C. FP64 FMA：五级改为七个实际算术级

当前 wrapper FP32/FP64 ADDMUL 都为五级。FP64 建议用下面七级，FP32 本轮保持五级：

| 算术级 | 捕获内容 |
|---|---|
| 1 | 输入、操作及特殊值元信息 |
| 2 | 对齐移位量/指数控制，保留对应未移位操作数 |
| 3 | 完成宽对齐移位、sticky，捕获加法操作数 |
| 4 | 全精度加法及符号结果 |
| 5 | 前导零计数、归一化移位量及指数，保留对应未移位和 |
| 6 | 完成宽归一化移位、尾数修正及 sticky/tininess |
| 7 | 舍入、状态和最终输出 |

FMA 无反压算术延迟比当前 FP64 增加两拍；各级可同时持有不同操作，目标保持无反压 II=1。不是仅增加 NumPipeRegs：需要实际修改共享 FMA fork，在 FP64 配置条件下启用两处边界，并保证参数总数匹配实际寄存级。每级 valid、tag、rm、flags、special、mask、aux 及 backpressure/flush 配套传播；FP32、CONV、NONCOMP 也需检查未受共享代码修改影响。

### D. 组合选择及性能事件：保持现有查询拍数

- BTB：保持全相联条目数、原更新和替换语义；将 PriorityEncoder 后动态读 target 改为保持原优先级的一热局部 payload 选择，然后平衡归约。分组仅改变组合结构，不改成组相联。
- Walk-cache：每-way 的 tag/ASID 命中与 PPN 局部形成候选；整理 upper/middle/root 收束，避免 hit 广播再参与重复的大范围选择。先保持现有查询状态拍数，不把已有 Mux1H 改写本身当作新优化。
- 性能事件：来源归因成立蕴含 hazard，integrated backend ready 要求 !hazard；验证后删掉 source-stall 中冗余 !idLeave 门控。L1D loadUseStall 外层相同门控一并检查，维持现有计数拍数及 CSR/inhibit 语义。

### E. FPU 完成出口：增加本地结果缓冲并明确所有权

在 CVFPU 的真实返回握手上捕获 tag/data/flags，后端 WB 只读取缓冲中已登记的结果，切开 operation arbiter 到 WB/scoreboard/EX 的同拍控制链。容量依据每拍最多一个返回、输入 ready 与输出反压传播边界推导；保留持续输出能力，不能仅给 ready 加寄存器。已提交结果从 CVFPU 转移到本地缓冲后继续受 busy/flags/drain 所有权追踪；架构 busy 仍只在真实完成时清除。未提交、被 kill 和提前返回等情况保持原规则，允许仲裁在未握手时变动 tag，不能只因 valid 就固定错误 payload。该边界为已接受返回增加一拍到达 WB 的基本延迟，排队等待另计。

### F. L2 入口与布局：缩小跨模块控制收束

核对 MMIO→order RAM WE 的实际路径，提前形成各入口的入队 payload/资格候选，以局部 grant 控制 RAM 写入；保持仲裁、公平性、地址映射、MMIO 发出后不能取消及资源所有权。完成源映射后实施局部结构变换，不按端点名称猜接口。

减少数据寄存器不必要的保持 mux、重复全局译码及大范围 enable；只对证实关键的小控制网做局部复制。新增 payload 不无条件逐位复位，valid/所有权状态按合同复位。完成 RTL 后根据同候选报告给 BTB/walk-cache/cache 返回等模块局部放置引导，不先硬切四核区域。面积目标是控制甚至降低组合 LUT，而不是用大量每-way checker 换路径；不存在预先承诺的面积降幅。

### 验证与完成标准

1. 先同步新增级的接口拍数、请求接收窗口、恢复和不可撤销边界到权威规格；golden 只迁移已明确的流水延迟，不降低数值或异常要求。
2. 必要子集覆盖：老异常 kill 年轻 store、新增级反压和 TLB miss 年轻请求保存、权限变化重查、PMP 边界/首匹配、AMO/probe、连续 I-cache 返回与跨字压缩指令、FP64 各舍入模式/subnormal/NaN/flags、完成缓冲所有权、source-stall 事件等价性。
3. 同一 SHA push 后，两端 pull 核对主仓及 CVFPU；cloud_chen 必要子集和完整回归、Alan 单核及四核 LiteX/Vivado 按既有并行流程，记录 monotonic wall time；不设构建超时，不打断当前基线。
4. 比较匹配版本和阶段的 LUT、setup/hold/pulse width、失败端点、TNS、拥塞和关键路径，最终单核/四核均以 routed 100 MHz 报告判定；目标同时增加单核余量。Vivado 成功、bitstream 或布局估计不能替代功能回归与最终时序。

该节是待实现的具体方案。本次只整理计划，没有启动 RTL 修改、编译、仿真或新增 Vivado 构建。

## 代表路径完整端点

以下对应上表顺序，端点直接来自原始报告。

- 指令读出/记分板到性能事件：`BreezeClusterAxi/cluster/buffer_1/fifo/deq_ptr_value_reg[0]/C` → `BreezeClusterAxi/cluster/backend_1/csrFile/performance/rawEvents_divSourceStall_reg/D`。

- dTLB 到数据访问 PMP：`BreezeClusterAxi/cluster/mmu_1/dtlb/mem_0_0_ext/_R0_addr_d0_reg_rep__4_replica_1/C` → `BreezeClusterAxi/cluster/l1d_1/internal2_pmpAllowed_reg/D`。

- PTW walk-cache 查询：`BreezeClusterAxi/cluster/mmu/ptw/wAsid_reg[6]/C` → `BreezeClusterAxi/cluster/mmu/ptw/wBase_reg[43]/D`。

- I-cache 命中到 realigner 字缓冲：`BreezeClusterAxi/cluster/frontend_2/tag_array_1/mem_ext/Memory_reg_0/CLKARDCLK` → `BreezeClusterAxi/cluster/frontend_2/realigner/wordBufData_reg[19]/D`。

- FP64 FMA 归一化：`BreezeClusterAxi/cluster/backend_2/fpUnit/impl/i_fpnew/gen_operation_groups[0].i_opgroup_block/gen_parallel_slices[1].active_format.i_fmt_slice/gen_num_lanes[0].active_lane.lane_instance.i_fma/gen_inside_pipeline[0].mid_pipe_sum_q_reg[1][108]/C` → `BreezeClusterAxi/cluster/backend_2/fpUnit/impl/i_fpnew/gen_operation_groups[0].i_opgroup_block/gen_parallel_slices[1].active_format.i_fmt_slice/gen_num_lanes[0].active_lane.lane_instance.i_fma/gen_normalization_pipeline.norm_q_reg[sticky]/D`。

- I-cache 返回控制到首字捕获：`BreezeClusterAxi/cluster/frontend_2/icache/wait_rsp_reg/C` → `BreezeClusterAxi/cluster/frontend_2/realigner/firstWordReg_reg[1]/D`。

- BTB 查找及预测目标选择：`BreezeClusterAxi/cluster/frontend_3/s1_pcReg_reg[25]/C` → `BreezeClusterAxi/cluster/frontend_3/s2_predPcReg_reg[22]/D`。

- iTLB 到取指权限结果：`BreezeClusterAxi/cluster/mmu_3/itlb/mem_0_0_ext/_R0_addr_d0_reg_replica_1/C` → `BreezeClusterAxi/cluster/fetchTlb_3/result_accessFault_reg/D`。

- 浮点队列完成/占用控制：`BreezeClusterAxi/cluster/backend_1/fpUnit/entries_9_valid_reg/C` → `BreezeClusterAxi/cluster/backend_1/fpUnit/entries_12_valid_reg/D`。

- D-cache 后级控制到下一请求捕获：`BreezeClusterAxi/cluster/l1d_2/internal2_valid_reg_replica_3/C` → `BreezeClusterAxi/cluster/l1d_2/cpu1_pmpEnd_low_reg[6]/D`。

- FP64 输入对齐及 sticky：`BreezeClusterAxi/cluster/backend_3/fpUnit/impl/i_fpnew/gen_operation_groups[0].i_opgroup_block/gen_parallel_slices[1].active_format.i_fmt_slice/gen_num_lanes[0].active_lane.lane_instance.i_fma/gen_input_pipeline[0].inp_pipe_operands_q_reg[1][1][60]/C` → `BreezeClusterAxi/cluster/backend_3/fpUnit/impl/i_fpnew/gen_operation_groups[0].i_opgroup_block/gen_parallel_slices[1].active_format.i_fmt_slice/gen_num_lanes[0].active_lane.lane_instance.i_fma/gen_pre_adder_pipeline.pre_q_reg[sticky]/D`。

- pending-store / AMO 数据捕获：`BreezeClusterAxi/cluster/l1d_1/internal2_valid_reg_rep__2/C` → `BreezeClusterAxi/cluster/l1d_1/ps_data_reg[61]/D`。

- 浮点完成到后端发射：`BreezeClusterAxi/cluster/backend_3/fpUnit/impl/i_fpnew/gen_operation_groups[2].i_opgroup_block/gen_parallel_slices[1].active_format.i_fmt_slice/gen_num_lanes[0].active_lane.lane_instance.i_noncomp/gen_input_pipeline[0].inp_pipe_valid_q_reg[1]/C` → `BreezeClusterAxi/cluster/backend_3/ex_rs2_data_reg[55]/D`。

- MMIO 请求到 L2 排队 RAM 写使能：`BreezeClusterAxi/cluster/l1d_2/mmio/r_paddr_reg[27]/C` → `BreezeClusterAxi/cluster/l2/memEng/order/ram_ext/Memory_reg_0_1_0_0/DP/WE`。
