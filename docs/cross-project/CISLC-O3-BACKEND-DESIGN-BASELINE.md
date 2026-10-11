# CISLC-O3 后端设计基线与决策记录

更新日期：2026-10-02。目标工程：`/home/chen/work/CISLC-O3`。本文暂存于 Flow，仅记录设计与只读源码核对；不代表 RTL、编译、仿真、时序或 FPGA 验证完成。

最新恢复决定：B30 同步前端 D29 的 BOOM 式 RAS 快速修复及系统首笔取指解耦，替换旧 undo；B29 的其余审查入口继续有效。

最新访存决定：B31 已选普通可缓存标量非对齐访问同 line 硬件支持、跨 line 报异常，并加入跨 line 非对齐异常次数计数。

最新机制共识：B32～B41（第 35 节）记录保守 load 依赖、提前唤醒与完成 FIFO、MULH+MUL 融合、LR/SC reservation、硬件 A/D、committed_next_pc、WFI、fatal 隔离、FP 状态退休、L2 inclusive（组相联、PLRU）回收，以及系统同步由 commit_ctrl 编排；已补入目标工程 RTL 框架（空壳，未编译/验证）。

前端合同见 [前端设计基线](CISLC-O3-FRONTEND-DESIGN-BASELINE.md)，尤其第 16 节及 D09、D22～D28。本文与前端基线互相补充，不覆盖已定前端方案。保留既有前后端代码，整体设计讨论明确后按用户指令进入实现。

**整数算术来源边界（2026-10-01，仍有效）**：仍在微架构设计讨论，不开始 RTL 移植或测试。整数乘除法仅复用 Breeze 对应单元的算法与数据通路，未来再将其 Chisel 实现转写 SV 并接入 CISLC-O3；不复用整个 Breeze 后端或整核。浮点按 B14/B15 的独立决定处理。B16/B17 的 Vivado 路线、B19 的新算法/DSP 优化讨论均为历史方案，不能覆盖 B21 的当前决定。

**最新工作状态（2026-10-02）**：用户已安排其他 agent 根据基线搭建目标工程框架；“本轮不实施 RTL”约束本讨论窗口，不表示整个工程无人实施。当前任务是对已定机制查漏补缺，先讨论并记录共识，再按单独授权补框架。B22～B29 为最新串行执行、屏障、地址预测、trap/xRET 与 Linux 复用决定；历史“暂缓”不再代表当前议程。源码状态均为注明日期的局部快照，新窗口须重新只读检查，不能用空壳或旧注释推翻设计决定。

## 1. 工作方式与当前证据

- 一次讨论一个机制，解释正常流程、冲突和资源代价；字段或容量未定不等于机制不存在。
- 用户确认的新决策记在本文；建议、待定项与实现事实分开。
- 2026-09-30 实时核对目标仓库：`develop`，HEAD `06462b0ebd48cecf0af34b8a87a90a365561894b`。未跟踪文件 `sim/o3/rv64i_instructions.jsonl`、`sim/o3/unified_memory.jsonl`，需保留。相关路径及父目录未发现适用 AGENTS.md；以后操作重新核对。
- 当前已有 Decode Queue、rename、Rename/Dispatch Queue、三类 IQ、执行/写回、ROB 提交；已有组内 RAW/WAW 旁路、映射 checkpoint、分支 allocation mask、LQ/SQ 和分支恢复，不预设全部重写。
- 旧 `doc/CISLC_O3_ARCH_SPEC.md` 中无 MMU、C 不在规划等旧目标不能覆盖新前端基线。
- 2026-10-01 用户明确整核目标为 KCU105 上的 RV64GC、可运行 Linux；SD DMA 是首版系统需要，不只是未来占位。浮点见 B14/B15，CSR/屏障见 B22～B24，异常/中断和返回见 B26/B27，Linux 复用见 B28/B29；设计决定不等于集成与验证完成。
- 2026-10-01 再次只读核对：目标分支、完整 HEAD、两项未跟踪仿真文件与上次一致；父目录及文档路径未发现适用 AGENTS.md/CLAUDE.md。本次仅更新设计文档，不改 RTL、不运行编译/仿真、不提交 git。

## 2. B01：参数化六宽 rename（已定方向，宽度暂定）

用户确认后端 rename 宽度参数化，第一版取 6，根据仿真与 FPGA 数据调整。前端仍按序每拍最多交付 4 条，Decode Queue 吸收积累，六宽用于消化积压；不宣称持续六条整核吞吐。Decode、Dispatch、发射、提交宽度不因此自动改成六，端口与容量分别设计。

## 3. B02：依赖预处理 + 原子 rename 两拍（已定）

用户确认：第一拍检查组内依赖，第二拍检查资源、给出物理编号并完成完整 rename。只拆组合计算，不拆开 RAT、free list、ROB 等状态事务。暂不采用跨拍 RAT 访问、第一拍预分配 preg 或推迟 ROB 实际入队；后续时序不满足时，再评估论文中的进一步拆分。

```text
Decode Queue → R1 依赖预处理 → 级间暂存 → R2 完整 rename → 现有 Rename/Dispatch Queue
```

### 3.1 R1：判断依赖谁

对最多六条有序指令，使用架构寄存器编号查找：rs1、rs2 的最近更老写入者，以及 rd 的最近更老同名写入者。前两项用于 RAW 源旁路，后一项用于 WAW 下正确生成 old_dst_preg。

每项逻辑信息为依赖是否存在及生产者槽位；六宽可用 3 位槽位号，具体编码可调整。排除无效 lane、无目的写入及 rd=x0；源比较按读使能。第一拍不读 RAT、不消耗 preg/ROB/LQ/SQ/checkpoint，不修改推测状态。仅在级间暂存接受时移走 Decode Queue 对应指令，并保存 uop 与依赖关系。

### 3.2 R2：给出编号并统一生效

按现有方式检查 preg、ROB、LQ/SQ、checkpoint、RDQ，选择可接受的最老连续前缀。读取当前 RAT、选择新 preg 和各队列编号；若操作数依赖本组仍有效的更老生产者，选择该生产者本拍新 preg，否则选择 RAT 映射。

在同一接受边界统一更新 RAT、free list、ROB、LQ/SQ、checkpoint、目的 busy，并进入 RDQ。一条指令所需资源要么全部获得，要么不分配。x0 不分配新 preg。相同 rd 的最终 RAT 更新取最后一条实际接受的写入；分支快照包含分支自身和更老指令，不包含更年轻指令。

例：I0 写 x5，I1 读 x5 写 x6。R1 只记录 I1 的源来自 I0；R2 假设分别分配 p32/p33，I1 源选择 p32，而不是 RAT 中 x5 的旧映射。依赖比较与最终编号选择因此不再全部落在第二拍。

### 3.3 暂存、部分接受与恢复

第一版采用固定槽位与有效位保存一组，部分接受后保留原槽位编号，不与下一组立即合并。R2 接受前缀离开后，若剩余指令的已记录生产者已离开，就使用当前 RAT；仍在暂存中的生产者继续由本组新 preg 旁路。资源分配与 RDQ 输出按剩余有效指令的程序顺序形成连续前缀，避免物理槽位空洞影响现有接口。

本组全部离开时，可以同拍末接收 R1 的下一组；全部停顿或部分接受时回压 R1。等待不得重复分配。重定向清除被取消暂存指令，并禁止 R2 同拍年轻副作用；R1 没有预分配资源，无需新增归还事务。存活指令的分支 mask 需随解析清理。

原始 PC、长度、异常和动态 FTQ 身份/槽位始终随指令保存，目标字段按前端合同集成，不将旧接口永久冻结。

### 3.4 实现与验证边界

尚未修改 RTL。拟新增依赖预处理和级间暂存，以保存的比较/优先选择结果替代原 rename 内部的组内比较。保留已有资源规划和恢复机制。

后续验证：RAW/WAW 链、同名多写、x0/无目的指令、0～6 条、资源不足的部分接受、长停顿不重复分配、分支在组中不同位置的快照、恢复同拍取消。综合重点观察 free list、RAT 读、最终 mux 和资源回压；目前没有频率收益证据。

### 3.5 参考与不采用的内容

- Safi/Moshovos/Veneris，Two-Stage, Pipelined Register Renaming，2011：<https://www.eecg.utoronto.ca/~veneris/10tvlsi.pdf>。两级定制电路、依赖比较/跨组旁路/RAT 时序；130nm 电路仿真不等同 FPGA 实测。其半周期 RAT、第一拍取得 preg 不作为本版合同。
- Petric/Sha/Roth，RENO: A Rename-Based Instruction Optimizer，ISCA 2005：<https://acg.cis.upenn.edu/papers/isca05_reno.pdf>。Figure 4(a) 的普通两级基线分开依赖比较与源编号修正；move 消除、公共子表达式消除、推测访存旁路、常量折叠未被本设计选用。
- BOOM v4 本轮本地源码 HEAD `54f11b85c9a670ef3dd18bc675994b9a661b06ee`，不同于前端基线研究版本。`rename-stage.scala` 有 ren1/ren2 寄存边界，但同组比较在第二级 BypassAllocations，不能声称它采用本版纯依赖预处理。
- 香山固定提交 `e7bab53e66dfb3c4a1d11cf9519b0396f8576cae` 中提前查 RAT、Rename 给 robIdx、Dispatch 后实际 ROB 入队的分工只作参考，不采用为当前实现方案。

## 4. 访存现状与设计状态

访存主流程已按 2026-10-01 用户要求归档为下面 B03～B11。两路访存为暂行宽度方向；具体 Load/Store 组合、容量、bank 映射、端口与精确流水级数仍待确定，不默认为双读双写。已确认机制与参数未定可以同时成立。

### 当前源码证据（2026-09-30，只读）

- 校正：`backend.sv` 的 Memory IQ 配置为 `OLDEST_ONLY(1'b1)`（2026-10-01 再核对），不能据通用 IQ 的 ready/年龄选择逻辑声称当前 Memory IQ 已允许年轻访存越过队头发射。整个后端不因此等于顺序核。
- `load_store_unit.sv` 当前同拍组合 AGU、SQ 依赖查询、存储请求仲裁。DTCM 为可写 simple_data_sram，范围外访问仿真外部 memory；不是只有 ROM。仅一个 memory Load pending，另有可保持结果槽。
- `store_queue.sv` 已兼任 committed store buffer：执行填地址/数据/mask，ROB 提交标记 committed，队头在存储请求接受后 drain 并释放。未提交年轻项可取消，已提交项保留。
- Load 查询更老 Store，单个 Store 完整覆盖可转发；未知地址/数据或部分重叠保守阻塞。没有多 Store 字节合并。
- `load_queue.sv` 已有分配、地址、outstanding、代际响应检查和提交释放；未实现访存违例 replay。扩展多个在途请求后，事务身份寿命与代际回绕需重新闭合，不能仅靠现有一位 generation 宣称安全。
- 当前没有 DCache/MSHR/DTLB/PMA/PMP 访问流水、精确访存异常闭环。现有 drain 是 store 队头排出，不等于已经完成 FENCE/FENCE.I 的系统同步。

## 5. B03：首版访存组织与范围收缩（已定方向）

目标是在 KCU105 固定资源内先实现可验证、可测量的非阻塞访存，再按仿真、综合/布局布线与 FPGA 结果调优。以 Dcache 为公共数据访问组件：普通 load/store、PTW 实际页表读取和 L1 原子操作复用它；L2 缓冲 DDR 访问，DMA 通过下级协调入口接入。

```text
访存调度/地址执行 → LQ/SQ、依赖检查与重放 → 多 bank Dcache
                                          ↑ PTW + walk cache
                                          ↑ 原子操作
                                          ↕ MSHR / 回填 / 写回
                                     L2 + DMA 行协调 → DDR
                                          ↑ SD DMA
```

- 组相联、写回 Dcache，支持 hit-under-miss，并保留多个独立 miss 在途与同 line 合并能力。流水线等待与事务等待分开；一次 miss 不把整个访问流水线置忙。
- 多 bank 提供实际并行带宽；同 bank 仲裁，tag/meta 访问能力必须配套。不同 line 不保证不同 bank，不承诺两读一写无冲突。
- PTW 首版共享 Dcache，不作为独立目录共享者，也不采用 PTW 独立接 L2/Home 再探测脏 PTE 的旧建议。共享数据路径仍需满足屏障与旧请求取消合同。
- 首版只处理一个 hart 与 SD DMA 的必要一致性，不引入完整多核 MESI/MOESI 目录。
- 不取消 A 扩展；其执行方案见 B09。值预测不进入 CISLC-O3，本轮只保留为以后 Breeze V2 的研究方向。
- L2 不要求复杂多核目录，但下级不能静默成为全部访存的全局串行瓶颈：独立命中继续服务、多个下级请求的能力是设计目标，具体 L2/AXI 组织尚未冻结。

**尚属建议的具体配置**：16 KiB/4-way/64 B line/4 个数据 bank/8 个 L1 MSHR，以及“一条 load 地址流水线 + 一条 load/store 地址流水线”。这些不是已确认参数。bank 按整行或行内 word 映射也未冻结；最近建议偏向 word 交错，以避免相邻 load 集中到一个 bank。不能将这些例子升级成用户决定。

## 6. B04：Load 生命周期与事件重放（已定主流程）

1. R2 原子分配 ROB、LQ、目的物理寄存器，保存源编号、PC/长度、FTQ 身份以及恢复信息。
2. 地址源就绪后，由访存调度发射并计算虚拟地址；保存可复用的访问地址、大小与身份。
3. DTLB 翻译、页表权限及 PMP/PMA/访问边界检查完成后，才允许产生有效 load 结果。允许部分 cache 查询并行，不能提前交付未检查数据。
4. TLB miss 挂起相关 load，释放执行流水级；PTW 完成后重试。翻译/权限异常记录至原 ROB 项，不写有效结果，到提交边界精确进入异常。
5. 查询更老 SQ 项，选择按程序顺序正确的数据来源；SQ 与 cache 可并行查询，最终结果统一选择。
6. SQ 完整转发或 cache 命中后，提取字节并扩展，写回目的物理寄存器，唤醒依赖者并报告 ROB 完成。执行完成不等于退休。
7. ROB 按序退休后释放 LQ；分支恢复/异常取消的年轻 load，迟到响应不得写入已经复用的物理寄存器或队列项。

### 6.1 SQ 转发与等待

- 只从程序顺序更老的 store 转发；重叠字节必须满足最近有效旧写入的优先关系。不能任取一条同地址 store。
- 首版采用保守的单 store 完整覆盖转发。相关数据未就绪、部分覆盖或无法安全判定依赖时等待，不立即做多 store 与 cache 字节拼接。
- SQ 完整提供数据时，不因并行 cache 查询报告 miss 而无条件申请下级取数。
- 区分 SQ 数据转发、MSHR 同 line 请求合并、提交写缓冲合并、未知地址依赖推测；它们不是一个机制。

### 6.2 MSHR 与重放

- MSHR 跟踪行事务（地址、下级事务身份、回填状态）；LQ/重放记录跟踪 load 身份、目标 preg、等待原因与唤醒关系。
- cache miss 分配或合并 MSHR；资源不足则等待可用事件，不能伪造已发出状态。多个 load 可等待同一行，但各有指令身份。
- 首版先完成回填安装，再唤醒 load 重查 SQ/cache，不要求回填直接写回 preg。MSHR 完成不等于 load 已完成。
- 重放不重新 rename，不重新分配 ROB/LQ，也不要求退回普通 IQ；等待记录是放在 LQ 内还是独立队列，物理组织尚未冻结。
- bank 冲突、DMA 行保护、SQ 数据未就绪、MSHR 满等需区分等待原因，避免每拍盲目重试。
- 取消与响应身份寿命必须闭合；现有一位 generation 不能未经分析直接沿用为任意长多事务的安全保证。

**尚未冻结**：是否允许 load 越过地址未知的旧 store。起步可保守等待；后续可采用未知地址推测加违例冲刷重执行，不要求选择性重放。已知无关的旧访存不应成为目标版的固定队头阻塞原因。

## 7. B05：Store 生命周期、提交与后台 drain（已定主流程）

```text
R2 分配 ROB/SQ → 地址/数据准备 → 翻译与访问检查 → 执行完成
    → ROB 按序提交 → SQ committed → 后台写 Dcache → 完成确认后释放
```

- 普通 store 通常不分配目的 preg；SQ 保存地址、数据、字节 mask、就绪及提交状态。地址和数据是否分开发射尚未确定，不能把香山的优化当作首版前提。
- 执行完成要求地址、数据和提交前必要检查已具备，不表示已经写 cache。提交前不得产生目标 store 的可见写入。
- 普通 store 检查通过后允许 ROB 退休，再由 SQ drain；不采用所有普通 store 均留在 ROB 等 cache 写完的旧建议。
- 已提交 SQ 项不被年轻路径恢复取消；未提交项按恢复范围撤销。SQ 满允许背压。
- 首版 SQ 兼任 committed store buffer，按序 drain；先不新增独立 SBuffer，也不要求多笔 store drain 并发。
- cache 请求接受与写完成分开。命中写完得到确认再释放 SQ；miss/资源冲突/DMA 行保护时保留项，等待事件后重试，不重复发送仍在途的同一请求。
- 等待 drain 的 store，包括已提交项，仍可供年轻 load 转发。store 等 miss 不占住 bank 流水级，不全局阻塞独立 load 命中。
- 普通 store 写入 L1 成功即可释放 SQ；以后脏行排出由 cache 负责，不要求原 SQ 项等 DDR。
- 目标版对 MMIO/不缓存访问采取保守路径：到 ROB 队头、满足先前排序后发出，保留 ROB 等结果，成功退休，同步错误在原指令报告。不可把香山文档中的 NonCacheable 提交后路径等同本方案。

## 8. B06：翻译、权限与精确同步异常边界（已定原则）

- TLB hit 不等于访问许可，PMP 成功也不能替代页表翻译及页表权限检查。目标物理地址和 PMA 属性确定后才能完成访问分类。
- TLB miss 不是异常：等待 PTW，成功后与 hit 路径具有相同资格；不因为曾经 miss 就要求普通 store 等 cache 最终写入才退休。
- page fault、PMP/PMA 拒绝及访问边界等同步错误保留原指令身份，向 ROB 报告。故障 VA 与内部 PA 分开保存，不混作异常地址。
- PTW 自身读取页表的物理权限检查，与最终目标访问权限检查分开。
- 普通可缓存标量非对齐访问按 B31：同 line 硬件支持、跨 line 报非对齐异常，不建立双页拆分完成通路；权限检查覆盖全部访问字节。A 扩展首版自然对齐限制见 B09。
- 将来写回的硬件故障不能静默丢弃，也不能伪装成已退休原 store/AMO 的精确异常；硬件错误报告方式待特权/异常设计闭合。
- 本记录固定访存端异常合同；统一异常入口、CSR、xRET、中断边界随后已按 B22/B26/B27 确认，剩余集成审查见 B29。

## 9. B07：共享 Dcache 的 PTW、walk cache 与预取（已定方向）

- PTW 使用 Dcache 物理访问入口，不递归翻译页表地址；与需求访问仲裁，共享最新数据、miss 处理与下级接口。
- 增加小型 walk cache 减少重复页表遍历。缓存层级、条目组织/容量未定；必须具有足够上下文以满足 D26 的 VA/ASID 定向规则，不擅自改为全清。
- PTW 仲裁与资源分配必须保证前进；等待翻译的请求不能占尽其依赖的 cache/MSHR/回填资源。具体保留份额待定。
- SFENCE.VMA、satp、PMP 更新及旧 PTW 迟到响应隔离继续遵循前端 D26～D28；共享 Dcache 不取消这些要求。
- 页表写入后执行 SFENCE.VMA，首版按 B24 等待 SQ 全量排空，确认更早 store 已写入共享 DCache 且后续 PTW 可从该路径看到新值；不要求为此全量写回 L1D 或 DDR。
- stride 地址预取列入本代计划，与值预测分开。先建立需求访问基线；预取需去重、限流、遵守可访问区域，不能读取有副作用的 MMIO，不因预取失败产生普通指令异常。
- 预取可能占带宽、MSHR 并污染 cache；通过 useful/late/unused 和资源竞争计数评价，不能称为零代价。

## 10. B08：SD DMA 按行 clean+invalidate 协调（已确认首版方案）

SD DMA 是当前系统需求。第一版只允许一笔 DMA 行协调事务在途；所有 DMA 行事务探测唯一 L1D，不要求多核目录过滤或共享者向量。PTW 和 AMO 不作为另外的缓存客户端。

### 10.1 行事务流程

1. L2 入口协调器登记目标物理行；Dcache 接受保护请求后阻止该行新的普通 CPU 访问与 AMO，其他行继续。
2. 目标行已经接受的访问完成到安全边界；已有 miss/refill/writeback 必须继续获得服务，并协调至不会有旧数据迟到重新安装的状态。
3. 对目标行 clean+invalidate：缺失则确认无副本；干净副本失效；脏副本先将最新数据可靠交给 L2，再完成失效确认。
4. Dcache 返回 quiesced 后，L2 执行 DMA 读或写。部分行写用最新数据保留未覆盖字节。DMA 不能绕过仍可能持有最新数据的 L2 直接读旧 DDR。
5. 事务所需的数据/一致性动作完成后解除行保护，唤醒 CPU 等待者，按接口约定返回 DMA 完成。

首版 DMA 读也清理并失效，接受随后 CPU 再次 miss 的代价；以后由探针决定是否增加不失效读取，不把该优化当作已定功能。

### 10.2 行保护与前进

- 逻辑上需一条 busy + physical line address 记录，具体字段和握手编码未定。
- 不能仅靠扣住 L2 响应实现保护：L1 hit 不经过 L2。目标行的新请求在 Dcache 接受/执行边界等待或重放。
- 不能堵住完成旧事务所需的响应、回填、写回和探测应答，否则 DMA 与 cache 会互等。包括跨行替换造成的资源依赖，也要列入验证。
- probe 使用维护队列和 bank 仲裁；不是只找空闲 bank，也不能在持续 CPU 请求下永久饥饿。阵列竞争会造成等待，但不预先给每次普通命中增加一致性流水级。
- DMA 协议不替代软件缓冲区交接和 FENCE：启动前应保证先前写入达到 DMA 可见点，完成后才允许消费结果。SQ 未 drain 的数据不能靠 Dcache probe 自动看见。
- DMA 数据一致性不自动替代 FENCE.I 或 SFENCE.VMA。

## 11. B09：A 扩展执行与精确异常（已定基本路线，前进性细节待闭合）

A 扩展分为 AMO 与 LR/SC；reservation 不是乱序调度的保留站。首版在 Dcache 内增加低吞吐原子执行入口，一次处理一条，不复制完整 LSU，不用普通 store 提交后 drain 来拆开原子读改写。

### 11.1 AMO

- 支持 RV64A 所需 .W/.D 及 swap/add/逻辑/min/max（有符号与无符号）语义；.W 旧值返回按规范符号扩展。
- 位于 ROB 队头，地址/权限/自然对齐/PMA 原子支持检查通过，满足先前访存排序后才实际执行。首版不支持非对齐原子访问；不支持原子访问的区域报适当异常，不模拟成普通读写。
- miss 通过 MSHR 等数据，不长时间占 bank；取得有效数据和执行资源后，在短窗口保护目标行，完成读—运算—写，返回旧值。
- 与 DMA 对目标行互斥；CPU、PTW 等同地址冲突访问也不能插入读改写窗口。其他无冲突行可继续。
- 写入前确保完成结果有保存空间；真正修改后即使写回端口背压，也只等待交付，绝不重执行 AMO。普通中断在此不可撤销窗口内延后处理，统一入口遵循 B26。
- 保留 ROB，结果确定后退休。目标数据修改前发现的同步错误报告至原 ROB；AMO 异常按 store/AMO 类，不能因内部读操作改报普通 load 异常。

### 11.2 LR/SC

- 每 hart 一条独立 reservation 记录（valid、物理地址/保留范围）；范围与具体清除事件表待定。LR 成功读取后建立记录并独立退休，不把 ROB 从 LR 一直锁到 SC。
- SC 第一版先完成地址和访问检查，再不可分割地检查 reservation 并条件写入。成功写入返回 0；保留失效则不写入，返回非零（首版可取 1），这不是异常。SC 执行成功/失败均清除 reservation。
- LR 故障按 load 类；SC 故障按 store 类。新的 LR 替换旧记录；上下文切换/异常清除合同需和未来特权流程闭合。
- reservation 与 cache tag 分开：单纯 clean、替换或 DMA 读导致的失效不必清除。SC 可重新取行后再校验；DMA 写与保留范围冲突必须清除，且与条件写入具有明确顺序。
- DMA probe 必须保留读/写意图，不把所有 invalidation 无区别地转成 reservation 清除。
- 受约束 LR/SC 循环的前进保证是必需项。公平仲裁、替换/重放影响和异常边界尚需验证；不能仅靠 reservation 位和“允许偶发失败”声称合规。

### 11.3 排序与证据边界

- 保留 aq/rl 字段。首版采用较强排序：阻止年轻访存越过原子指令发射，在队头等待先前访存完成至所需顺序点，原子操作完成后放行。
- 较强排序是性能取舍，不等于只等旧 ROB 退休；已提交但尚未 drain 的旧 store 仍需考虑。
- 此路线可以满足 A 扩展，不等于 RTL 已通过合规验证；LR/SC 前进性、DMA 冲突、异常、内存顺序都需单独验证。

## 12. B10：事件计数与性能归因（已确认加入）

首版使用硬件计数器，仿真增加事件轨迹；不立即引入大容量硬件逐事件日志。读取、清零、统一快照接口需保留，ABI/位宽待定。

| 逻辑事件 | 口径 |
| --- | --- |
| dma_line_transactions | DMA 行事务总数 |
| dma_line_lock_cycles | 行保护有效周期 |
| dma_conflict_loads / stores | 请求因行保护首次进入等待的次数，重试去重 |
| dma_conflict_cycles | 至少一项 CPU 请求被行保护阻塞的周期，同拍不按请求数重复累计 |
| dma_wait_dcache_cycles | DMA 等待旧事务收尾、脏数据交接的周期 |
| ROB 队头直接 DMA 等待 | 队头访存直接因 DMA 保护未完成而不能退休的周期 |
| ROB/SQ 等资源满 | 独立统计，不能因 DMA 同时活跃就全归因 DMA |

同一 load 等十拍记一条冲突请求和相应等待周期，不记十条冲突。load 等待周期不等于整核停顿周期；间接依赖链影响需轨迹或对照实验。

同时保留 bank 冲突、MSHR 占用/满、miss 在途时 hit 完成、SQ 转发与等待、PTW/walk cache 及预取效果的观测。事件名是逻辑口径，不是已冻结的 CSR 编码。

仿真轨迹至少能关联：DMA 保护开始、请求首次冲突、Dcache 清理完成、DMA 完成/解锁、请求重发与完成。

## 13. B11：实现边界与后续主题

### 13.1 仍需细化，但不重开已定主流程

- LSU 发射组合与地址/数据拆分；cache 容量、路数、bank 映射、端口、流水级与 BRAM 映射。
- LQ/SQ/MSHR/写回/回填容量、身份编码和生命周期；资源保留、仲裁公平性与死锁审查。
- 未知旧 store 地址的推测策略、访存违例恢复；B31 已定同 line 非对齐支持，内部拆分/拼接与异常事件接口待落实。
- L2 在途请求与 DDR/AXI 接口；DMA 行协调和在途 fill/eviction 的完整竞态表。
- Sv39 大页/ASID/global、A/D 更新和 walk cache 的具体组织；FENCE 完成握手与 D25～D28 集成。
- LR/SC 前进保证与 reservation 清除表；MMIO/不缓存异常、晚到硬件错误报告。
- 以上为实现前细化清单，不将“字段未定”说成机制不存在。

### 13.2 验证分层

先验证 LSU/cache 单元：转发选择、MSHR 合并与资源满、回填唤醒、取消后迟到响应、SQ 交接与重试；再验证 DMA 同行/异行/部分写/脏行/在途回填竞争及 AMO/LRSC。之后集成 PTW、屏障和整核异常/恢复，用架构测试与内存顺序用例检查。编译、仿真、形式性质、布局布线时序、FPGA/Linux 运行和性能测量分别记录。

### 13.3 讨论进度（2026-10-02 校正）

访存归档之后，已继续讨论分支、乘除法、浮点及 B22～B29。下一窗口审查整核机制闭环，每次只处理一个真正未决的选择；不再以“统一中断暂缓”或旧 FU 讨论顺序为议程。参数和字段未定、框架未接通与机制未定分开列出。

## 14. 访存参考资料与版本边界

- [RISC-V A 扩展 2.1（20240411 固定页面）](https://docs.riscv.org/reference/isa/v20240411/unpriv/a-st-ext.html)：原子语义、aq/rl、设备写与 reservation、受约束 LR/SC 前进保证；具体实现可保守，但需验证。
- 香山 [昆明湖 V2 StoreUnit](https://docs.xiangshan.cc/projects/design/en/kunminghu-v2/memblock/LSU/StoreUnit/)、[StoreQueue](https://docs.xiangshan.cc/projects/design/zh-cn/kunminghu-v2/memblock/LSU/LSQ/StoreQueue/)、[Dcache MainPipe](https://docs.xiangshan.cc/projects/design/zh-cn/kunminghu-v2/memblock/DCache/MainPipe/)：地址/数据分离、提交缓冲与维护流水线。参考页的拍数、容量、NonCacheable 路径不是本设计合同。
- 香山 PTW 曾核对提交 `e7bab53e66dfb3c4a1d11cf9519b0396f8576cae` 的 `src/main/scala/xiangshan/cache/mmu/L2TLB.scala`：独立 Get、sfence 与 flush_latch。独立 PTW 路线现已退出本代首版；文档与提交不混为同一版本。
- BOOM v4 `54f11b85c9a670ef3dd18bc675994b9a661b06ee` 的 `src/main/scala/v4/lsu/lsu.scala`：committed/succeeded、转发、访存顺序恢复。不能把后端参考提交与前端另一提交混用。
- [The BlackParrot BedRock Cache Coherence System，2022 v1](https://arxiv.org/html/2211.06390v1)：L1 原子操作、目录事务、不保留副本的一致性请求。源码曾核对 `fc7c4ae5ad6b21143afc4f378a678bafa422681b` 的 `bp_be/src/v/bp_be_dcache/bp_be_dcache.sv` 和 `bp_me/src/v/cce/bp_cce_fsm.sv`；源码与论文不是同一版本，未在本任务编译/仿真。
- [A Primer on Memory Consistency and Cache Coherence，第二版，2020](https://pages.cs.wisc.edu/~markhill/papers/primer2020_2nd_edition.pdf)：一致性、内存顺序、原子读改写及瞬态竞争。
- [HPDcache，DATE 2024](https://past.date-conference.com/proceedings-archive/2024/DATA/10009_pdf_upload.pdf)：流水化非阻塞 cache、MSHR 与写缓冲参考；论文中的写穿策略不是本设计选择，报告的性能/面积不是我们的证据。
- [Evaluating the Cost of Atomic Operations on Modern Architectures](https://arxiv.org/pdf/2010.09852)：PACT 2015 的扩展版，测试旧 Intel/AMD 微架构，不代表当前 Zen。
- [Analyzing the memory ordering models of the Apple M1，2024](https://www.sra.uni-hannover.de/Publications/2024/wrenger_24_jsa.pdf)：测量分析，不是 Apple RTL 或内部一致性协议的官方披露。

后续修改记录日期、原因与证据。早期的独立 PTW/完整 Home 设想、所有普通 store 等写完再退休、以 TLB hit 概率决定安全性等说法，均不得覆盖本文后来明确的合同。

## 15. B12：分支/跳转后端只读审计（2026-10-01，BRU 组织已确认）

承接前端第 16 节、D08/D09/D22/D23/D24；不重开预测器设计。本节区分当前代码、必须满足的既有合同和建议。目标仓库再次核对为 develop、HEAD `06462b0ebd48cecf0af34b8a87a90a365561894b`，仍只有两项既有未跟踪仿真 JSONL；适用父目录及目标源码/文档子目录未发现 AGENTS.md。本次未修改目标 RTL，未运行编译、仿真、时序或 FPGA 测试。

### 15.1 当前已经连接的正常路径

- `decoder.sv` 支持 BEQ/BNE/BLT/BGE/BLTU/BGEU、JAL、JALR，三类控制流都申请 checkpoint；Dispatch 分流到独立 Branch IQ。
- Branch IQ 单发射，选择最老的操作数已就绪项，允许跳过未就绪的老分支。条件分支等待两源，JAL 无寄存器源，JALR 等待 rs1。IQ 写回唤醒在下一拍参与选择，没有同拍 wakeup-select。
- `backend.sv:640` 起的共享 PRF 读仲裁按 ROB 年龄比较 Integer/Memory/Branch 候选。必须原子获得全部所需读口且 RegRead 可接受，才从 IQ 删除；失败则保持 IQ 项。
- 读值锁存到 `branch_regread_q`；独立 `branch_execute_unit.sv` 是组合执行逻辑，比较条件并计算直接目标、JALR 清 bit0 后目标、实际下一 PC 及链接值。结果在后一个边沿进入 `branch_result_q`，不是 BRU 内部多级比较流水。
- 当前误预测条件为实际下一 PC 与 `predicted_next_pc` 不同。结果槽广播解析一次，`branch_resolution_sent_q` 防止链接值背压期间重复解析；解析不等链接值取得 PRF 写口，也不等提交。
- 无目的写入的条件分支及 rd=x0 跳转由解析网络完成 ROB；写 rd 的 JAL/JALR 竞争共享写口，PRF 写入、唤醒、ROB 完成随写回 grant 一起生效。结果等待时保留，反压前级，不能重执行或覆盖。
- 相对时序：C0 选择/仲裁/读 PRF，末端锁存 RegRead；C1 组合执行，末端锁存 Result；C2 结果解析，并在有写口时交付链接值。ROB 之后按序退休，不把 C2 称为提交。

### 15.2 误预测恢复与当前缺口

已有代码按 branch mask 撤销年轻项；RAT 从分支自身之后的快照恢复，free list 回收该分支之后的分配，ROB/LQ/SQ tail 从 checkpoint 恢复，checkpoint 文件释放本分支及年轻后代。分支自身和更老指令保留，JAL/JALR 自身的新目的映射保留。RDQ/IQ、LSU 及写回端有取消路径，Decode Queue/fetch 暂存清除错误路径。

但不能据此称分支后端已完备：

1. **解析造成全局暂停**：当前任何 `branch_resolution_i.valid`（包括预测正确）都阻止 IQ 选择、读口授予、rename/dispatch 和 ROB 退休。这是当前实现的保守控制，不是前端合同要求；不能从单发射 BRU 推出整合后能持续每拍处理一条分支。
2. **停顿 ALU RegRead 的取消风险**：`backend.sv:1750` 附近，当年轻 `alu_regread_q` 所在 ALU 的旧结果未取得写口，`alu_regread_ready=0`。若此拍发生误预测，代码只通过 `resolved_branch_mask` 清位，没有独立清除该年轻槽的 valid。之后旧结果被消费时，这条年轻指令可能已失去取消标记并进入结果槽。写回仲裁对已有 Result 的 kill 不能替代 RegRead 的 kill。此为静态控制路径发现，尚未由定向仿真复现；后续必须验证“较老结果背压 + 年轻 RegRead + 分支误预测”组合。
3. **目标设计恢复接口尚未落实**：当前解析结构携带 `ftq_idx/branch_pc`，未携带完整动态 FTQ 代际、显式槽位及历史/RAS 恢复引用；尚未实现 D24 多来源完整请求仲裁和恢复完成握手。前端历史/RAS 恢复、提交训练及 FTQ 生命周期按既有基线实施，不能因当前 RTL 缺失就重新列为未讨论机制。
4. **目标与异常边界**：BRU 尚无执行异常输出；现有 `PC_WIDTH=39` 会把目标运算截为 39 位，不能据此证明 RV64GC/Linux 所需的完整目标地址检查。运算已用 `inst_len` 计算顺序 PC/链接值，可承接原始 2/4 字节长度，但不等于 RVC 整条集成已验证。统一异常入口承接 B26；本条源码缺口仍须以新窗口实时审计为准。

按目标合同，获胜执行重定向须立即禁止被取消年轻指令的副作用，恢复后端推测状态，并让前端恢复对应 FTQ 入口 E/C 与 RAS 状态、应用真实控制流，再开始正确路径预测。2026-10-02 D29/B30 将 RAS undo 替换为入口索引/占用数/栈顶修复，并给出固定短恢复目标；恢复期间出现有效的更老请求按 D24 替换。预测表仍提交训练，不能把“立即恢复”改为“立即训练”。

### 15.3 本轮只提出一个实现选择

**2026-10-01 用户确认**：沿用独立单发射 BRU，保留“RegRead → 组合执行 → Result 保持”的寄存边界，以及“解析/重定向与链接值写回解耦”的组织。暂不增加第二条分支执行管线，也不增加比较器内部流水级。

资源代价为一套条件比较/目标运算和两个可保持槽；共享 PRF 端口继续可能造成等待，JAL/JALR 写回背压也可能延迟后续分支执行。后续先修正确性及落实既有恢复接口，记录 branch-ready 等待读口、结果槽阻塞、解析暂停周期、误预测到正确路径重新交付的周期；基于综合/时序和性能证据再调整。预测正确时是否消除全局暂停属于之后的局部控制优化，本轮未确认，更不能声称已实现。

## 16. B13：整数乘除法 FU（加入范围已定，具体结构为建议）

2026-10-01 用户要求继续加入整数乘除法及 F/D 执行单元。当前仍处于设计讨论，不修改 RTL。

- 当前 `rtl/backend/mul_execute_unit.sv` / `div_execute_unit.sv` 支持相应运算语义，但未接入当前 backend，decoder 尚未接受 M 扩展编码。
- 两模块在请求接受时组合算完整结果，再用固定计数器延迟返回；乘法使用 `*`，除法/取余使用 `/`、`%`。这不是已切开运算关键路径的真实多拍微结构，也没有结果 ready 或指令身份/取消接口。
- 建议一个可流水乘法 FU（MUL/MULH/MULHSU/MULHU/MULW），以及一个迭代除法/取余 FU（DIV/REM 及 unsigned/word 变体）。除法忙不阻塞乘法或普通 ALU；具体乘法 DSP 映射/切分、除法 radix、拍数、IQ 和端口以后单独落实，不把旧计数器参数冻结为目标延迟。
- 每笔接受请求保存 ROB/动态身份、目的寄存器及恢复关系；输出 valid/ready 保持结果，真正获得整数写口时完成 PRF 写入、唤醒及 ROB 完成。被取消运算可以提前终止或完成后丢弃，但不得覆盖复用后的寄存器；结果和请求槽身份寿命必须闭合。

## 17. B14：复用 Breeze 流水化 CVFPU，拆为后端 FU

### 17.1 用户确定的范围与版本核对

用户指定复用 Breeze 为 100MHz 调整过的 FPU，保留内部运算及流水改动，拆出独立 FMA 和其他 FU，不沿用当前 Breeze 统一顶层包装的接法。

本轮实时核对 Flow HEAD `8ccdfb261dabdc79381cf66f7792bdabcf204695`；CVFPU 子模块 HEAD `1b220f3bc89df99e246b72e3574a3a533cf87653`，子模块工作区干净。其提交包含 `cdb4c70` 的 FMA 加法前切分以及 `1b220f3` 的转换舍入前切分。当前 `FlowFpnewWrapper.sv` 配置：

- 标量 RV64D（FP32/FP64），`DISTRIBUTED`。
- ADDMUL：FP32 3 个流水寄存边界，FP64 4 个。
- DIVSQRT：THMULTI，配置 2 个寄存边界；不代表迭代运算只需两拍。
- NONCOMP：1；CONV：4，保留舍入前弹性流水级。
- ADDMUL/NONCOMP 按格式 PARALLEL；DIVSQRT/CONV 为 MERGED。拆分首版保留这些配置，不暗中改成共享一套 S/D FMA 数据通路。

`docs/linux/act4-single-regression-20260911.md` 保存了相同 CVFPU 提交在 Flow 集成下的 F 82/82、D 114/114 历史测试记录。本轮仅核对记录，未重跑；这些不是 CISLC-O3 的测试证据。Flow 后来的单核 100MHz 板级记录也不能证明拆分后的新乱序核心达到 100MHz；本轮未追溯相同 bitstream 的完整 FPU 构建链。

### 17.2 已确认的分组与数量（2026-10-01 更新）

| 目标 FU | 操作 | 复用层次 |
| --- | --- | --- |
| FP-FMA/ADDMUL ×2 | 每个支持 FADD/FSUB、FMUL、四种 fused multiply-add，S/D，由 uop 选择运算 | 两套 ADDMUL opgroup，保留内部 `fpnew_fma.sv` |
| FP-DIVSQRT ×1 | FDIV、FSQRT，S/D | DIVSQRT opgroup，保留 THMULTI |
| FP-MISC ×1 | FSGNJ/FMIN/FMAX、FEQ/FLT/FLE、FCLASS，S/D | NONCOMP opgroup |
| FP-CONV/MOVE ×1 | FCVT.S.D/D.S、浮点与 W/WU/L/LU 转换；FMV 位搬运由薄包装处理 | CONV opgroup + 原始位搬运逻辑 |

用户确认第一版两个相同的 FMA/ADDMUL 单元；每个按 uop 执行加、乘或融合运算，其他三组各一个。独立 FMA 是从统一 FPU 外壳中拆出算术 FU，不把 fused 指令拆成先 MUL 再 ADD；必须维持一次舍入。不另设专用 FADD 或 FMUL FU。

可以例化五个 `fpnew_opgroup_block`（两套 ADDMUL，加另三组），保留需要的 format slice 与算术叶模块，替换 `fpnew_top` 的统一输入分流/输出仲裁。各 FU 有独立 in/out valid/ready、在途身份和结果保持，分别竞争目的寄存器域的写回。不能继续用 `busy` 把整个 FP 子系统锁成一次只能做一条。两条三源 FMA 同拍发射最多需提供六个源值，结果也可能同拍竞争写口；这些是带宽需求，不是已经冻结六读双写端口。IQ 组织、端口和整体发射宽度仍待落实，不能从 FU 数量直接推出五发射。两套 ADDMUL 首版均保留原有 S/D 格式组织，新增资源需后续综合检查。

移除顶层时必须迁移其语义：输入 NaN-box 检查、输出扩展/boxing、分类结果及整数结果处理。FMV 是位搬运，不能错误套用普通算术的 NaN 替换规则；单精度写 FPR（包括 FLW）保持规定的 NaN-boxing。复用 opgroup 层可减少手工重接 format 和结果扩展的风险。

当前包装 `TagType=logic` 且 tag 固定 0；新包装必须携带唯一请求身份。建议用请求槽号及代际关联侧表中的 ROB、目的域/preg 和当前分支依赖，正确解析时更新侧表，误预测时标记年轻请求 killed。不能把原生 `flush_i` 当作按年龄取消：它会清掉同一 FU 中仍有效的更老操作。首版可让 killed 运算完成并在出口丢弃，槽位保留到返回终结；结果身份不能提前复用。是否加入内部选择性 kill 后续按恢复开销决定。

### 17.3 商业参考的核对边界

本轮核对 Arm Cortex-A76 Software Optimization Guide，Version 8.0，PJDOC-466751330-7215，§2.1（印刷第 7 页）、§3.11（第 18～19 页）、§3.12（第 20 页）：两条 FP/ASIMD 执行流水线存在功能不对称；浮点除法/开方使用 V0，转换、位搬运也有具体路径归属。公开资料支持按资源能力调度、区分长延迟运算和转换通路，但不是其内部 RTL 的全部切分图，更不能说所有商用核心都按本节四组组织。

来源：[Arm 官方指南](https://documentation-service.arm.com/static/5ed4bd67ca06a95ce53f917d?token=)。本节四组建议主要依据本地固定版本 CVFPU 的既有 opgroup 边界及 KCU105 复用成本，不复制 A76 的 SIMD 宽度、FU 数量或延迟。

## 18. B15：独立浮点重命名，64 个物理浮点寄存器

2026-10-01 用户要求浮点寄存器重命名、数量 64。本记录按 **32 个架构 FPR 映射到 64 个 64 位物理 FPR** 理解并记录；不是将 RISC-V 架构 FPR 扩成 64 个。容量参数化，首版取 64。

- 增加 FP speculative/committed RAT、FP free list、FP ready 状态以及分支恢复所需的 FP 映射快照和分配记录；与整数域分开。`f0` 是正常可写寄存器，物理 FP 编号 0 也不照搬整数 p0 的恒零逻辑。
- 原始内容 `64*64=4096 bit`，不是整个多端口 FPR 的 FPGA 资源估计；读口复制、写口仲裁、旁路和 checkpoint 另计。32 个架构映射占用后，初始可用重命名预算为 32 个目的版本，不等于最多只能有 32 条浮点指令在途。
- R1 的同组 RAW/WAW 比较增加整数/浮点域与第三源；R2 原子分配相应目的域的 preg、ROB、LQ/SQ 和 checkpoint，不将 FP RAT 更新另拆一拍，不要求普通整数指令消耗 FP preg。
- 每个源和目的带寄存器域及有效位；FMA 有三个 FP 源；FCVT/FMV、比较/分类可跨整数和浮点域。FMV.X/FCLASS/浮点比较写整数 PRF，FMV.F/I2F 写 FP PRF，不能按所在 FU 推断目的域。
- FLW/FLD 使用整数地址源、写 FP preg；FSW/FSD 使用整数地址源和 FP 数据源，进入既有 SQ，提交与 drain 遵守 B05；不会因为是浮点指令而绕过访存合同。
- 写回只在结果仍有效且获目的域写口时写 PRF、置 ready、唤醒并报告 ROB；按序提交更新对应 committed RAT、归还该域 old_dst。分支恢复保留分支及旧指令，撤销年轻 FP 映射与分配，并隔离 FU 的迟到结果。
- 算术 status 的五个 fflags 先随该指令存 ROB，退休时按程序顺序并入架构 fflags，错误路径不更新架构状态。dynamic rm 必须读取程序顺序正确的 frm 并随请求锁存，不能使用执行时任意当前值；与 frm/fflags/fcsr 的 CSR 读写、FS Off 检查及 Dirty 状态的精确边界随后续 CSR 设计闭合。相关 CSR 首版统一采用 B22 串行化；fflags 退休合并、FS 状态和 trap/xRET 的同拍仲裁仍需落实。

示例：`fmadd.d f1,f2,f3,f4` 将 f1 的新版本分配为 fp32；随后 `fadd.d f1,f1,f5` 的源使用 fp32，目的另分配 fp33，old_dst=fp32。若分支位于两条之间，恢复保留 fp32 映射并回收 fp33；不得取消那条更老的 FMA。

本轮仅更新独立后端设计记录；前端合同继续由第 16 节互相引用，不修改现有前端及目标后端 RTL。FP 分组及数量已经确认；整数乘除法的最终来源与阶段边界见 B21，B16/B17 的 Vivado 选择已被后续决定替代。

## 19. B16：整数算术 IP 的来源约束与候选（2026-10-01）

### 19.1 用户明确的筛选要求

仅考虑独立算术库/IP，不为复用乘除法而 clone 另一套 CPU 或引入其配置/流水线框架。CVA6、Rocket、VexiiRiscv 的 CPU 内部组件退出移植候选；此前只下载少量文件到 /tmp 做只读核对，没有 clone 或引入依赖。用户进一步授权：独立开源实现不合适时，可以采用 Vivado IP。此授权不表示 IP 已生成或 RTL 已接入。

### 19.2 独立库的实际核对

- BaseJump STL 固定提交 `3753148c8f57cbbd878d2b535070c51a5b00c390` 是独立 SystemVerilog 硬件库，符合来源要求。
- `bsg_misc/bsg_idiv_iterative.sv`：参数化 width，signed/unsigned，输出 quotient 与 remainder，支持每次迭代 1/2 bit，使用输入 valid/ready 和输出 valid/yumi；控制器 DONE 保持至消费。可作为 64 位迭代 DIV/REM 候选。依赖同库基础模块，需要显式列出小规模依赖闭包；本轮未编译或验证 width=64。没有独立 kill/tag 端口，需 O3 包装保存身份并管理取消，不能当作整核 reset。
- 同提交 `bsg_misc/bsg_mul_pipelined.sv` 采用 Booth/compressor 结构，实际 generate 只支持 width=16/32，不能仅修改参数就得到 64 位流水乘法；也不把 ASIC 结构自动说成 KCU105 DSP 优化方案。迭代 `bsg_imul_iterative.sv` 可进一步评估，但不是当前高吞吐乘法的首选。
- 独立 `risclite/verilog-divider` 提交 `e73dec16750b45bd9e01d82848251e564a4d5354` 的 `divfunc.v` 可参数化并用 STAGE_LIST 切分，输出无符号商/余数，但没有结果 ready/背压或取消接口；不是即插即用的 RV64M FU，当前不优先。

源代码：[BaseJump divider](https://github.com/bespoke-silicon-group/basejump_stl/blob/3753148c8f57cbbd878d2b535070c51a5b00c390/bsg_misc/bsg_idiv_iterative.sv)、[BaseJump multiplier](https://github.com/bespoke-silicon-group/basejump_stl/blob/3753148c8f57cbbd878d2b535070c51a5b00c390/bsg_misc/bsg_mul_pipelined.sv)、[独立 divider](https://github.com/risclite/verilog-divider/blob/e73dec16750b45bd9e01d82848251e564a4d5354/divfunc.v)。本轮只读审查，不将项目测试或历史性能移作本核心证据。

### 19.3 Vivado 乘除法 IP（来源已确认，配置待定）

用户随后明确“用 vivado 的 ip”：整数 MUL 与 DIV/REM 均采用 Vivado IP。独立开源候选只保留为研究记录，不继续推进 BaseJump 的首版接入。

后续用户已明确改为仅复用 Breeze 的整数乘除法，未来转写 SV，见 B21。以上仅保留此前选择，不表示已生成 IP，不再是当前实施路线。

本轮核对 AMD 官方 Multiplier v12.0 PG108（2015-11-18）及 Divider Generator v5.1 PG151（2021-02-04），实际安装工具的版本以后生成时复核。

- **乘法建议**：优先使用 Multiplier Generator，64×64 unsigned、完整 128 位结果、DSP 实现及真实流水寄存配置。RISC-V MUL/MULW 取低位；MULH/MULHSU 可在包装中根据原操作数做高半部符号修正，避免为每种符号组合复制 IP。PG108 输入上限为 64 位，不能未经核对提出 65×65 配置。流水级数依 OOC/整核时序确定，valid/tag 随运算推进，出口有容量或统一停顿机制保证不会丢结果。
- **除法建议配置**：使用 Divider Generator 的 Radix-2 integer remainder 模式。PG151 的 High Radix 只支持 fractional 输出，不能把它直接等同同时返回 RV64 DIV/REM。Clocks per Division、延迟、Blocking 与 output TREADY 具体配置待定，不默认需要满流水除法吞吐。
- Vivado 除零商/余数不能当作 RV64M 规定值：PG151 明确这些输出 undefined。包装必须独立处理除零、signed overflow 和 word 输入/输出语义；状态、ROB 身份、背压和取消同样由核心侧负责。
- 以上是建议，未生成 IP、未修改 RTL、未运行 OOC 综合/仿真/整核时序。未来为厂商实现保留统一 FU 包装与明确的仿真模型入口；模型通过不等于 FPGA IP 或整核时序通过。

官方来源：[PG108](https://docs.amd.com/api/khub/documents/idOj3Pp9ocZdMoFz3IpkjQ/content)、[PG151](https://docs.amd.com/api/khub/documents/c_0ZEADkbrJCz1MvCirYoQ/content)。

## 20. B17：Vivado IP 的仿真交付与整核验证路线（2026-10-01，建议待落实）

状态：历史参考，随 B21 确认复用 Breeze 整数乘除法而退出当前路线。不要求为本代首版建立本节厂商模型/替代模型双入口。

用户要求先仿真再 FPGA。本轮重新只读核对 `sim/o3/Makefile`：当前整核使用 Verilator + C++ 驱动；没有生成 Vivado MUL/DIV IP。本地 PATH 查到 Verilator，未查到 Vivado/xsim；不据此断言其他目录、Windows 或远程主机未安装。

### 20.1 官方模型的证据

- PG108 v12.0 IP Facts：Multiplier 提供 encrypted VHDL simulation model；可验证选定流水配置及 CE 控制。它不是可直接加入 Verilator 的普通 SV 文件。
- PG151 v5.1 IP Facts 及 Appendix A Simulation Changes：Divider 提供 encrypted VHDL 模型，该模型相对最终 netlist bit/cycle accurate；官方模型可检验 AXI4-Stream 接受、返回、背压和复位。
- PG151 Chapter 5：另有 C shared-library 数值模型，bit accurate，但不 cycle accurate，不模拟延迟、接口信号或 tuser；除零数值也不构成架构语义保证。不能仅把 C 模型接到 DPI 就声称验证了 IP 握手或核心性能。
- XSim 支持混合语言；UG900 2022.2 的 `export_simulation` 可导出 xsim/questa/vcs 等脚本，不包含 Verilator。Verilator 官方 Input Languages 说明其不能使用 encrypted RTL。

### 20.2 建议保留两个相互对照的入口

1. **XSim 真实 IP + 同一 FU 包装**：固定 Vivado/IP 版本和 XCI/生成 Tcl 参数，生成 simulation output products，添加包装及 SV testbench，运行 behavioral simulation。通过脚本记录请求接受、结果返回、复位/取消以及 ROB/目的身份；无需先做整核布局布线。可以进一步将后端 RTL 纳入 XSim，原 Verilator C++ 驱动需另作适配，不能声称原二进制能直接驱动 XSim。
2. **Verilator 快速整核**：包装内算术 IP 例化可切换为项目自有的 SV 行为模型。核心侧真实的调度、身份、写回、恢复控制包装保持相同；模型需要覆盖具体配置的 CE/流水推进、接口缓冲/握手、结果稳定、复位等可观察行为，不能只用 `*`、`/` 算值再任意等几拍。
3. 用同一组带周期的输入激励在 XSim 官方模型和 Verilator 模型下运行，并逐周期对比接受、返回和数值。覆盖连续请求、输入两路不同步、长时间输出背压、复位以及恢复当拍/迟到结果。IP 模型自身的核对与 RISC-V FU 包装的特殊值/取消测试分开归因。官方模型对照通过后，才把行为模型用于性能评估；仍需说明采用的是模型，非 FPGA 测量。

XSim 工程已添加 IP、包装和 testbench 后，可以 `generate_target simulation [get_ips {...}]`，再 `launch_simulation -mode behavioral`；或使用 `export_simulation -simulator xsim -directory ... -force` 导出批处理脚本。此为建议流程，IP 名称、工程入口及运行脚本尚未创建或验证。

以上没有改变“RTL 仿真 → 综合/时序 → FPGA”的证据顺序；官方 IP 模型仿真与快速行为模型仿真必须分别记录。本轮只更新文档，没有创建 IP、修改 RTL、编译或运行仿真。

来源：[PG108](https://docs.amd.com/api/khub/documents/idOj3Pp9ocZdMoFz3IpkjQ/content)、[PG151](https://docs.amd.com/api/khub/documents/c_0ZEADkbrJCz1MvCirYoQ/content)、[UG900 2022.2 export_simulation](https://docs.amd.com/r/2022.2-English/ug900-vivado-logic-simulation/export_simulation)、[Verilator Input Languages](https://verilator.org/guide/latest/languages.html)。

## 21. B18：复用 Breeze 自研整数乘除法（2026-10-01，源码已核对，替换建议待确认）

状态：本节的源码核对继续有效；来源已按 B21 确认。B19 曾讨论另写优化数据通路，但后续用户明确沿用 Breeze 乘除法算法与数据通路，不能再将 B19 当作首版方向。

用户询问是否可以改用 Breeze 当前自研实现。建议可以复用算术数据通路，重新适配 O3 FU 控制；此前 B16/B17 的 Vivado 路线可作为后续资源/时序评估的备选。这里没有声称用户已经确认替换，更没有修改或集成 RTL。

- 当前 `BreezeBackend.scala` 实例化 `RiscvMulUnit` 和 `RiscvDivUnit`。乘法内核 `design/src/main/scala/multiplier/SignedMul65x65.scala` 是 signed 65×65、130 位积，Booth 部分积与 Dadda 压缩树分两级，最后一级进位传播加法；代码有三道寄存边界，延迟 3 拍、启动间隔 1。不是组合乘法后加倒计时。Breeze 在后端做 sign/zero extension，包装选择 MUL/MULH/MULHSU/MULHU/MULW 的结果。
- 除法内核 `design/src/main/scala/divider/UnsignedRadix4Divider.scala` 单请求迭代，每拍串联两步 restoring radix-2 运算，外层每拍处理两位商。按操作数最高有效位对齐，最多 32 次迭代；该数字不是包含输入预处理、接收和输出保持的 FU 总延迟。`RiscvDivUnit.scala` 恢复商/余数符号并做 W 结果符号扩展；Breeze 后端另外处理有效输入、绝对值、除零和有符号溢出。移植必须包含这些语义，不能只复制包装。
- 当前包装没有输出 ready；乘法全局 flush 清空全部有效 token，除法 flush 终止当前请求。O3 必须携带 ROB 身份及复用代际、目的物理寄存器和恢复信息，逐条取消年轻操作，保留老操作。除法只有一个在途请求，可依据其身份决定是否 abort；乘法流水中可能同时有老/年轻操作，不能整体 flush。
- 乘法流水不能停顿，需在发射时为必然返回的结果预留缓冲容量，写回竞争时保持结果；除法的单拍 out_valid 同样需要结果保持。具体缓冲容量、端口和仲裁尚未冻结。
- Chisel 算术 RTL 生成普通 SystemVerilog 后可加入现有 Verilator 工程，同一数据通路也用于 FPGA 综合，不需要厂商加密仿真模型。仍要整理生成入口、文件依赖以及验证断言的仿真/综合处理，不能假定现有 Breeze 测试直接覆盖 O3 包装。

现有源码包含乘法数值/流水/flush 测试和除法定向、随机、flush 测试；本轮只阅读，没有重跑。没有获得本核心接入、编译、仿真或 100 MHz 时序证据。乘法为手写压缩树，不能假定会自动充分利用 FPGA DSP；除法一拍两次串联比较/减法也是需测量的时序路径。资源/时序评估后再决定是否改数据通路或使用 Vivado 备选，维持前端第 16 节接口及 B12 恢复合同。

## 22. B19：新自研整数乘除法的 FPGA 优化方向（2026-10-01）

状态：本节为历史候选研究，已被 B21 的“复用 Breeze 整数乘除法，优化后置”决定替代。DSP 映射、unsigned 64×64 高位修正和 radix-2 改法均未进入当前首版方案。

用户允许采用新的自研实现，以优化旧 Breeze 资源开销。当前仍在设计讨论，不直接修改 RTL。新数据通路为优先方向；B18 保留旧实现的审查结果，B16/B17 保留 Vivado 备选，不继续按旧乘法树直接移植。

### 22.1 资源证据边界

本轮读取 `docs/plans/2026-09-10-kcu105-bram-ila-resource.md`：源提交 `cbcee530960d0aae51902c9b2fe54006bf21a0a9`、四 hart、50 MHz 的历史 routed checkpoint 归因中，整数 multiplier 触及 5,431 个 CLB site，其中 1,513 个仅由该组占用。这是四核汇总的重叠位置集合，不是单个乘法器 LUT 数，也不保证重写会释放等量 CLB。该表没有整数 divider 的独立面积归因；FPU 的 divide/sqrt 不能当作整数除法面积。文档所列本地原始报告目录本轮未找到，不能声称已复核原始 rpt 或最新 100 MHz 关键路径。

### 22.2 本轮重点：DSP 映射的自研乘法 FU（建议，结构细节待定）

旧 `SignedMul65x65` 显式构造 Booth 部分积和 Bool 级 full/half adder 的 Dadda 树，不能期待综合器可靠还原成 DSP 乘法。建议新 FU 用普通可综合 RTL 表达分块乘法和寄存流水，让小乘法映射到 DSP48E2，而非继续扩大 LUT 压缩树。AMD UG579 v1.11 的 DSP48E2 为 signed 27×18 乘法；无符号块必须考虑额外符号零位，不能直接把任意 unsigned 27×18 当作一块 DSP 能容纳。

建议计算一个 unsigned 64×64→128 位积 P。MUL/MULW 取低位；MULHU 取 P[127:64]；MULHSU 的高半部在 a 为负时减去 b；MULH 再在 b 为负时减去 a，运算按 64 位模数。这样不必构造 65×65 的完整有符号树。符号修正是独立可流水的宽减法，不能忽略其面积和时序。

流程：操作数就绪且取得发射/结果容量后，捕获输入、操作及身份；DSP 小乘法并行产生部分积，后续寄存边界逐级合并，最后符号修正及结果选择，进入可保持的完成缓冲。建议保留每拍接收一条乘法的能力，不冻结三拍延迟；块宽、DSP 数、合并方式、流水级数需要 OOC 和整核数据决定。每拍一条需要相应并行硬件，不能同时声称大幅复用 DSP 而不影响吞吐。

正常写回竞争时保持结果；发射前预留返回容量，或另行设计可靠的流水停顿协议。误预测时逐条清除年轻 token，保留老操作，迟到数据不可更新 PRF/ROB；身份与数值随相同寄存边界推进。代价是 DSP、合并/修正逻辑、流水寄存器和完成缓冲，收益目标是减少 LUT/布线压力，不承诺具体百分比或已达到 100 MHz。

仿真使用同一份普通 RTL，包括实际寄存流水中的小乘法表达式；综合引导 DSP 映射。属性只作引导，必须检查实际 DSP/LUT 归因。若后续必须直接例化厂商 primitive，需另行闭合其仿真路线，不能称普通 Verilator 已支持未处理的 primitive。

### 22.3 除法后续单独闭合

建议评估每拍一步 radix-2 迭代，采用固定移位和复用加减通路，减少旧实现每拍两次串联比较/减法及动态对齐网络的开销；最坏迭代数可能由 32 增至 64，早退出及 FU 总延迟另定。此为资源/时序与延迟的交换，不声称 radix-2 必然优于旧实现。没有冻结除法算法或修改其 RTL。本轮未生成、编译、仿真或综合新 FU。

参考：[AMD UG579 v1.11](https://docs.amd.com/api/khub/documents/pTysoma4TYgNH95BrY1Sbw/content)。保持前端第 16 节交付及 B12 恢复合同。

## 23. B20：先落实 SV 流水乘法与迭代除法，优化后置（2026-10-01，方向已定）

最终澄清见 B21：这里的未来 SV 实现是转写 Breeze 的整数乘除法，而非重新选择算法；“待会改为 SV”不是当前开始编码的授权。除法沿用 Breeze 数据通路方向，尚待闭合的是 O3 控制与接入细节。

用户明确：先将当前多拍乘法改为可流水执行的实现，资源优化以后再讨论；当前 Breeze 算术实现是 Chisel，后续乘法、除法都改为 SystemVerilog。本轮仍为设计记录，用户说“待会改为 SV”，不据此提前修改 RTL。

- **乘法机制已定**：实现真实寄存分级的流水运算，支持不同乘法指令重叠执行，以每拍可接受一条为首版设计目标。不能只在接收时组合完成乘法再倒计时，也不能仅在完整组合乘法后加延迟寄存器就称拆分算术关键路径。Breeze `SignedMul65x65` 已有三级实际算术流水，可作为结构参考；移植到 SV 后的具体延迟及寄存边界仍需验证，不冻结 100 MHz 结论。
- **除法方向**：SV 实现真实迭代运算，单元可保持单请求在途；不要求与乘法一样每拍接受一条。算法、迭代位数和总延迟另行闭合，避免将“乘法可流水”自动扩大为“除法全流水”。
- **实现来源**：首版采用项目自有普通 SV 算术 RTL，不例化 Vivado 算术 IP 或 DSP primitive；仿真和综合使用相同数据通路。未来是否优化 DSP 映射、位宽、压缩树或迭代结构依据测量再定，B19 不再作为当前首版要求。
- **O3 控制合同继续有效**：身份/操作/目的物理寄存器随数值推进；完成结果遇写回竞争可保持；发射与结果容量协调；恢复逐条取消年轻操作而保留老操作。符合前端第 16 节和 B12，不能直接照搬 Breeze 全局 flush。

当前 O3 的 `mul_execute_unit.sv` / `div_execute_unit.sv` 是已有 SV 占位实现，Breeze 内核为 Chisel；必须区分这两个来源。本轮没有修改它们，没有编译、仿真、综合或时序证据。后续进入实施时再落实 SV 文件、接口、测试与整核集成。

## 24. B21：最终范围澄清与下一窗口交接（2026-10-01，已定）

### 24.1 本轮最终决定

- **讨论阶段**：继续微架构设计，尚未进入 RTL 实施。用户说明“将 Breeze 乘除法改为 SV 加入 CISLC”是在澄清未来实现来源，不是要求立即转写、集成或运行测试。本轮只更新文档。
- **复用范围**：仅整数乘除法对应的算法和数据通路，不扩大为整个 Breeze 后端/整核复用。CISLC-O3 继续从现有 rename、IQ、PRF、ROB、恢复及访存框架演进，不预设全部重写。FPU 的独立来源与拆分决定仍见 B14/B15，不能从“乘除法复用”推导其他模块复用。
- **乘法**：沿用 `SignedMul65x65` 的 signed 65×65 Booth/Dadda/末级加法三级实际运算流水及启动间隔 1 的方向，保留 RISC-V 乘法变体所需的输入扩展和结果选择。未来转写 SV 后的周期对齐、O3 包装额外延迟、资源及时序须独立验证。
- **除法**：沿用 `UnsignedRadix4Divider` 的单请求、按有效位对齐、每次迭代两步 restoring 运算方向；最多 32 次迭代不是整个 FU 固定延迟。包含后端原有输入/绝对值预处理、除零与有符号溢出、商/余数符号恢复及 W 语义。未来转写 SV，不在本阶段换成 radix-2 或全流水除法。
- **资源优化后置**：先形成可验证、可测量的整合版本并加探针，再依据仿真、时序和 FPGA 数据优化。当前不引入 Vivado 算术 IP 或 DSP primitive，不把历史 Breeze 报告作为新核心达到 100 MHz 的证据。

### 24.2 已定合同、未闭合细节、实现缺口

| 机制 | 已定合同 | 仍需讨论或实现的内容 |
| --- | --- | --- |
| 分支执行/恢复 | 单 BRU，RegRead→EX→Result 保持；解析/重定向与 JAL/JALR 链接值写回解耦；执行纠错、提交训练 | 按 B12 落实 D24 统一恢复接口；背压槽的年轻操作取消；正确解析暂停是否优化；异常/完整目标边界 |
| 整数 M FU | Breeze 数据通路方向，未来 SV；乘法流水、除法单请求迭代 | IQ/发射归属、身份/代际、结果保持、容量预留或流水停顿、选择性取消、特殊值完成路径、写回公平性 |
| F/D FU | B14 的两套 FMA/ADDMUL；DIVSQRT/MISC/CONV-MOVE 各一套；复用已调整 CVFPU 内部流水 | FP IQ 组织、读口/写口及跨域仲裁、请求身份寿命、年轻结果隔离、boxing/fflags/frm 等集成细节 |
| FP rename | 32 架构 FPR→64 个 64 位物理 FPR；独立域；R1 增加域/第三源，R2 原子事务 | 具体表/快照组织、端口与恢复时序；不能因为目标已定就声称 RTL 已有 FP rename |
| 非阻塞访存 | B03～B11 的缓存、LQ/SQ、重查、PTW、DMA、原子与精确检查合同继续有效 | 具体容量/端口/bank/流水、未知地址旧 store 策略、违例恢复及全链路实现；机制未实现不等于未讨论 |
| Linux 特权范围 | 单 hart RV64GC/Linux、SD DMA 必需；前端同步合同继续有效 | 已定主流程见 B22/B26/B27；B28/B29 复用 Breeze Linux 经验，剩余是 O3 集成与实现审查 |

本节是 2026-10-01 交接记录。当前下一窗口议程以 B29 及后续 B30 更新为准；流水乘法完成端背压仍是实现合同检查项，不再指定为唯一下一讨论主题。

### 24.3 交接证据

本轮再次只读核对目标：`develop`，HEAD `06462b0ebd48cecf0af34b8a87a90a365561894b`；仍只有两个既有未跟踪文件 `sim/o3/rv64i_instructions.jsonl`、`sim/o3/unified_memory.jsonl`。本轮未修改目标源码、未编译、未仿真、未综合、未做时序或 FPGA 测试；没有创建乘除法 SV 移植文件。下窗口须重新核对，而非把本快照当永久状态。

## 25. B22：Zicsr 首版串行化与成本计数（2026-10-02，已定范围）

用户决定第一版采用 BOOM 式保守 CSR 串行化，但阻塞点放在本项目的 **Decode→Rename 边界**，不是照搬 BOOM 的 Dispatch 阻塞实现。Decode 按程序顺序识别串行指令；同组只放行它及更老的连续前缀进入 Rename，年轻指令留在前端/Decode 缓冲中，不分配物理寄存器、ROB 项或 IQ 项。CSR 指令本身正常 Rename、分配 ROB；更老指令可继续执行和退休。串行指令等待成为 ROB 队头后，进入容量很小的独立串行执行通路；其提交之前保持年轻指令的 Rename 阻塞。前端缓冲容量不足时正常反压取指。这样不在多路 ROB 分配组合路径增加同组串行截断判断，也不为 CSR 之后的年轻指令引入重命名恢复需求；是否改善时序须待实现后测量。同组若有多条串行指令，只放行至最老的一条，剩余留待下一次放行。

CSR 首版按一条在途的请求/完成握手设计，可先尝试源寄存器读取、CSRFile 读/计算、旧值写回、退休的短流水；若 CSRFile 或写回时序不满足目标，允许将内部读改写拆成多拍或变延迟操作，外部串行边界不变。CSR 到 ROB 队头且更老指令已退休后，先完成地址、权限、只读限制和操作合法性检查。非法访问只在该 ROB 项记录同步异常，不更新 CSRFile。合法操作在 CSRFile 接受并完成该请求时取得应返回的旧值、按 Zicsr 读改写语义更新架构 CSR，并把需要的旧值送往 `rd` 写回；必须按操作码处理访问抑制：CSRRW/CSRRWI 在 `rd=x0` 时不读 CSR；CSRRS/CSRRC 在 `rs1=x0`、立即数变体在 zimm=0 时不写 CSR，但仍读；CSRRW/CSRRWI 的零源仍会写零，不能统一按零源禁止写入。CSRFile 的架构更新发生在执行完成点，可以早于 ROB 退休；一旦更新，CSR 指令不可再被异常、冲刷或重放取消，提交控制须保证它最终完成写回并退休。更新前若遇到同步异常，则不得有 CSR 写入。写回口/缓冲容量需要在允许架构 CSR 写入前得到保证，不能在写入后因为资源背压而丢失该指令。

中断只在指令边界仲裁：若选在 CSR 执行前进入中断，则不得启动该 CSR 写入；若 CSR 已更新，则先让它退休，再根据更新后的 CSR 状态判断待处理中断。对影响中断使能/待决/委托判断的 CSR 写入，须在执行后及时重新评估中断条件。CSR 提交后才能解除 Decode 阻塞；若它改变了年轻指令依赖的取指、翻译或译码状态，前端需丢弃相应缓冲指令并重新取指，但不涉及恢复年轻指令的 Rename 状态。影响前端的 CSR 同步承接 D27/D28；异常入口和 xRET 按 B26/B27，特权语义复用边界见 B29。这里“ROB 队头可执行”不等于当前已存在可工作的 CSR FU/CSRFile。对照来源：[BOOM v4 固定提交的 core.scala](https://github.com/riscv-boom/riscv-boom/blob/54f11b85c9a670ef3dd18bc675994b9a661b06ee/src/main/scala/v4/exu/core.scala) 在执行期驱动 CSRFile，源码明确说明它依靠 CSR 串行化避免提交前架构写入的推测风险；[BOOM CSR 执行文档](https://github.com/riscv-boom/riscv-boom/blob/master/docs/sections/execution-stages.rst)；[RISC-V Zicsr 规范](https://docs.riscv.org/reference/isa/unpriv/zicsr.html)；[RISC-V 特权规范的中断条件](https://docs.riscv.org/reference/isa/priv/machine.html)。[香山 V2R2 CSR 设计文档](https://docs.xiangshan.cc/projects/design/zh-cn/kunminghu-v3/backend/CSR/) 记录部分只读 CSR 可乱序执行，本项目暂不引入这一优化。

首版加入三个逻辑上为 64 位的性能计数器，具体读取地址、清零、快照和溢出规则留待性能计数 ABI 统一确定：

| 计数器 | 加一条件 | 用途 |
| --- | --- | --- |
| `csr_retired` | 一条 Zicsr 指令实际提交握手一次 | 与累计 `instret` 比较，估计动态 CSR 指令频率；等待多拍不能重复计数 |
| `csr_wait_empty_cycles` | CSR 已进入 ROB、等待成为队头，且此前仍有更老 ROB 指令的每一拍 | 量化等待前序指令退休的周期 |
| `csr_block_younger_cycles` | CSR 串行化阻止 Decode→Rename 放行，且前端/Decode 缓冲中确有年轻指令等待的每一拍 | 量化有实际年轻指令需求时的阻塞周期 |

周期计数由相应阻塞状态/握手生成单拍事件，指令数仅在提交握手时生成单拍事件。`csr_retired / instret` 可说明频率，两个周期计数与总周期数比较可说明阻塞占比；这些计数不是 IPC 损失的直接换算，阻塞原因可能重叠，不能相加后当作总停顿。以后在相同配置与工作负载下结合 IPC、CSR 类型分布及计数，判断是否值得研究香山式选择性乱序只读。

本节记录已确认的 Decode 阻塞位置、CSR 执行期架构写入/提交边界及计数口径；独立串行队列的具体端口、CSRFile 内部拍数、与异常/中断入口的详细仲裁电路仍待设计。`csr_file`、`commit_ctrl`、性能事件模块在 2026-10-02 目标工程中仍是框架空壳；本次只改 Flow 设计文档，未修改目标 RTL、未编译或仿真。FENCE/FENCE.I/SFENCE.VMA 的排空与完成条件已经由 B23/B24 确定。

## 26. B23：普通 FENCE 与 FENCE.I 首版完成边界（2026-10-02，已定范围）

普通 FENCE 和 FENCE.I 共用 B22 的 Decode→Rename 串行阻塞：只让串行指令及更老的同组前缀进入 Rename；串行指令分配 ROB，等成为队头后进入独立的一项串行寄存器，年轻指令在其退休前不能进入 Rename/IQ。这里的一项寄存器是逻辑容量目标，具体能否与 CSR 复用同一物理项、源操作数/完成端口如何仲裁，留给 RTL 接口设计。

**普通 FENCE**：首版 Decode 保留 `pred/succ` 编码，按 `pred` 是否含普通内存写 `W` 选择额外等待；无需动态扫描此前执行过的指令。FENCE 到 ROB 队头时，更老的 load、CSR 和不缓存 MMIO 已按 B05/B22 的完成后退休合同结束，年轻指令尚未进入执行。若 `pred.W=1` 且存在需要排序的后继集合，串行项等待 SQ 后台 drain 至空：所有更老普通 store 均得到 DCache 写完成确认，不能以请求接受代替。SQ 的 drain 在 ROB 无老项时仍自主推进；可给出维持到完成的加速请求，但不绕过 DCache 冲突或完成握手。若 `pred.W=0`，不为该 FENCE 强制排空普通 SQ，队头即可完成；例如仅含 I/O 的 FENCE 不因普通缓存 store 未排空而等待。`pred=0` 或 `succ=0` 的无排序提示编码不需要额外等待。由于年轻指令在 Decode 被统一阻塞，首版不依 `succ` 区分年轻指令放行范围；`FENCE.TSO` 可按更强的 `FENCE RW,RW` 处理。此方案依赖已定的 CSR/MMIO 完成握手覆盖其对外效果，并依赖 DCache 写完成与下级内存排序合同；这里不新增全 DCache 脏行写回作为普通 FENCE 动作。规范允许简单实现采用更强排序：[RISC-V FENCE](https://docs.riscv.org/reference/isa/v20260120/unpriv/rv32.html)；CSR 读写在 FENCE 中分别按 `I/O` 归类：[Zicsr CSR ordering](https://docs.riscv.org/reference/isa/v20260120/unpriv/zicsr.html)。

**FENCE.I**：用户选择低频、低硬件复杂度的保守数据侧路径，具体前端顺序见 D25。串行项暂停新取指、预取并丢弃 Decode/前端中比 FENCE.I 年轻的旧指令，同时等待 SQ 中老 store 全部写入 DCache 并收到完成确认。然后遍历 L1D 的全部 tag/meta，跳过非脏行；每个脏行复用 B08 按物理行 clean+invalidate 维护能力，将最新行数据可靠写到 L2，确认后置该行无效。等全部扫描及在途脏行写回完成，再处理旧 ICache miss/预取结果并全量失效本地 ICache。前端返回同步完成后，该 ROB 项才可标记完成并退休；退休后从 FENCE.I 下一 PC 重取，不能只凭 SQ 空、写回请求发出或单拍 flush 脉冲放行年轻指令。若所有有效 DCache 行都脏，最坏要写回并逐出全部这些行；干净 DCache 行不因 FENCE.I 扫描而逐出。写回目标是 ICache 回填可见的 L2，不要求把整个 DCache 或 L2 写到 DDR，也不对普通 ICache miss 增加逐行探测。该维护控制器要在 B08 既有单行 DMA 协调与写回响应之间保证仲裁和前进；具体 FSM、端口、写回并发度及周期数待实现。规范依据：[Zifencei](https://docs.riscv.org/reference/isa/unpriv/zifencei.html)。

首版增加两个逻辑上为 64 位的性能计数器，读取地址、清零、快照和溢出规则留待性能计数 ABI 统一确定：

| 计数器 | 加一条件 | 用途 |
| --- | --- | --- |
| `fencei_retired` | 一条 FENCE.I 实际 ROB 退休握手一次 | 与 `instret` 比较，观察动态调用频率；等待多拍不能重复计数 |
| `fencei_dcache_evict_cycles` | FENCE.I 的 DCache 全行扫描/脏行写回逐出阶段处于 busy 的每一拍，包括扫描干净行及等待 L2 写回确认 | 与 `fencei_retired` 比较得到每次数据侧维护的平均周期；不包含此前 SQ 排空及此后的 ICache 失效/重取 |

这两个计数不能单独换算为 IPC 损失；若 FENCE.I 频率或逐出成本显著，再用相同工作负载下的 IPC、前后 I/D miss 和周期分布评估 I/D 一致性或更细粒度维护方案。本节是设计决定，目标 RTL 仍为空壳/占位，未编译、仿真、综合或测量。

## 27. B24：SFENCE.VMA 首版 SQ 排空与翻译同步（2026-10-02，已定范围）

SFENCE.VMA 沿用 B22 的 Decode→Rename 串行阻塞和一项串行执行通路；到 ROB 队头后，先等已提交 SQ 全量排空，所有更早的普通 store 均收到 DCache 写完成确认，不能只看 SQ 请求已被接受。首版不识别哪些 store 实际写过页表，统一等待；SQ 后台正常 drain 即可，不要求强制逐出 L1D 脏行。该合同以 B07 的 PTW 共享 DCache、页表写入对后续 PTW 可见为前提；若以后 PTW 改为绕过 DCache，必须重新设计可见性握手。

SQ 排空后，按前端 D26 的 rs1 虚拟地址、rs2 ASID、global 与页大小规则，同步失效 ITLB、DTLB、预取翻译记录及 walk cache 中匹配的旧状态；隔离或等待匹配范围内旧 PTW 的迟到结果，防止其在失效后重新安装旧翻译。前端丢弃 SFENCE.VMA 之后携带旧翻译/权限快照的取指工作，完成同步握手后，该 ROB 项才可标记完成并退休，再放行年轻指令。具体请求/应答、PTW 取消与 generation 标记留待接口设计。这里不全清物理标记 ICache，也不执行 FENCE.I 的 L1D 脏行写回逐出；SFENCE.VMA 仅同步本 hart 的地址翻译。当前只记录设计，未修改目标 RTL 或验证。

## 28. B25：stride load 地址预测首版（2026-10-02，已定主流程）

用户决定加入按动态 stride 预测**标量 load 自身有效虚拟地址**的能力，为以后可能加入的 V 扩展积累访存预测基础；V 扩展本身的指令/流水设计本轮不展开。它不同于 B07 已计划的下一缓存行 stride 预取，也不同于旧 store 与 load 的依赖预测；B03 不做值预测的决定保持不变。

首版按下列主流程设计，不要求在 Fetch 阶段无 LQ 身份地提前发 load：

1. 预测表按 load PC 区分，记录上次退休的有效虚拟地址、相邻地址差值 stride 与置信状态。只用成功退休的普通、可缓存标量 load 真实地址训练；看到连续稳定的差值后，预测下一次地址为 `last_va + stride`。表深、tag 宽及置信门槛待资源测量确定。为避免同一静态 load 多个在途实例重复使用同一个预测地址，首版在该 PC 的前一预测实例退休或被取消前抑制下一次预测；取消不能训练。上下文切换至少隔离/失效旧预测状态，不能把前一地址空间的缓存结果误当当前结果。
2. Rename/LQ 分配后，携带 LQ/ROB 身份及足够的 generation，将预测虚拟地址作为提前访存候选。它优先级低于正常需求访问，端口、MSHR 等资源不足时直接跳过预测，不阻塞正常 load。提前请求只在当前 DTLB 命中、翻译及页权限/PMP/PMA 检查允许、目标为可缓存且无副作用的普通内存时发出；首版预测路径不启动 PTW，不向 MMIO 发请求，预测失败或无映射不能产生该 load 的架构异常。推测路径被清除、翻译上下文变化后，迟到结果不得装入已复用的 LQ 项或写回物理寄存器。
3. 在提前请求发出前，检查比该 load 更老的 SQ store/未完成原子操作：未知地址、与预测访问字节重叠、或尚不能确定安全性的情形，首版跳过提前取数。若提前返回，将候选数据暂存在对应 LQ 项，不标记该 load 完成，不写回目的物理寄存器，也不唤醒依赖者。DCache 行若在取数后受到 store、AMO、DMA 等写入/失效，必须使候选数据失效或提供可验证的新鲜度版本；做不到时退回正常 demand 读取，不能使用过期的暂存值。
4. 正常 AGU 仍计算真实地址，照常执行真实地址的翻译、权限与访问检查以及旧 SQ 依赖/转发选择。仅当预测地址与真实地址一致、相关检查成功、旧 store 依赖已安全判定且暂存数据仍新鲜时，才可用提前结果完成 load。若地址不同、数据失效或检查不通过，丢弃暂存结果并按真实地址走既有 LQ 重试/DCache 请求；架构异常只来自真实执行。首版不让依赖指令提前消费预测结果，因此误预测恢复局限于本 load，不增加下游依赖链选择性重放。

上述是提前取得并验证 load 数据，不只是预取一条缓存行。若额外的 SQ 检查、新鲜度跟踪或端口成本太高，允许保留同一 stride 表，把预测请求降级为只暖缓存、真实 load 重新读数据的测量对照模式；不能把降级模式宣称为已实现提前 load 完成。性能事件至少区分符合置信门槛的预测候选、实际提前请求、地址命中、提前数据被真实 load 采用、地址错误/新鲜度失效、资源或安全门槛跳过，以及额外 DCache/MSHR 流量。具体事件名、CSR 编码、表容量和实现拍数待定；用同负载的 IPC/负载等待周期与额外流量评估收益，不能只看地址命中率。目标 RTL 尚未实现该机制，本节不代表已编译、仿真或测量。

资料边界：Shen/Lipasti《Modern Processor Design》第 5 章约印刷页 275～278 将 stride 引导的数据预取与 load 地址预测分开；参考 [Austin/Sohi, Zero-Cycle Loads](https://ftp.cs.wisc.edu/sohi/papers/1995/micro.zcl.pdf)。[RISC-V V 扩展访存规范](https://docs.riscv.org/reference/isa/unpriv/v-st-ext)本身已定义连续、定步长和索引访存的地址生成方式；因此地址预测是可选性能优化，不是实现 V 扩展的正确性前提。以后可复用提前访存接口，不能由此推定 V LSU 已设计完成。

## 29. B26：精确异常与中断的已定提交边界（2026-10-02，已定原则）

同步异常随产生异常的动态指令保留 ROB 身份、PC、cause 与 tval。提交按程序顺序退休该指令之前的所有更老指令；遇到故障指令时停止，**故障指令本身不退休**，它之后的年轻指令全部取消。提交/恢复控制在这个精确边界发出 trap 请求，CSRFile 完成陷阱状态更新，再用处理程序入口 PC 重定向前端。不能把“提交到异常指令”误写成“故障指令正常退休”。架构异常/委托与 trap CSR 语义按 B29 复用 Breeze 经验；O3 多来源事件接入仍须核对。

中断只在指令边界仲裁。若串行 CSR 指令已开始执行并更新了架构 CSR，必须先完成写回及 ROB 退休，再按更新后的 CSR 状态判断是否进入中断；不得在架构 CSR 已更新但该指令未退休的窗口插入中断。其他不可撤销操作也须先到各自已定的安全边界。没有这种在途操作时，完成本次选定的退休前缀后，可在下一条未退休指令之前进入中断。中断边界不能切开一条指令，也不能让年轻指令在 trap 请求后继续退休。待决/使能/委托语义按 B29 复用；空 ROB 时的下一 PC 来源和 O3 接入握手仍需闭合，原子更新时序见本节下文。

此处固定的是提交语义，不限定把控制电路全部放进 ROB 阵列本体；ROB 提供队头顺序与异常元数据，提交控制仲裁并驱动 CSRFile/前端。目标工程当前仍只有框架/占位，未按本节完成 RTL 或验证。

参考实现边界：BOOM 固定提交 `54f11b85c9a670ef3dd18bc675994b9a661b06ee` 的 [v4 ROB](https://github.com/riscv-boom/riscv-boom/blob/54f11b85c9a670ef3dd18bc675994b9a661b06ee/src/main/scala/v4/exu/rob.scala) 在提交逻辑中仅允许提交组队头异常触发 `com_xcpt`，不让故障项正常退休，并产生 flush；[v4 Core](https://github.com/riscv-boom/riscv-boom/blob/54f11b85c9a670ef3dd18bc675994b9a661b06ee/src/main/scala/v4/exu/core.scala) 将 `com_xcpt` 的 PC/cause/tval 延后一拍送 CSRFile，再用 CSR 的 `evec` 重定向前端。BOOM 的 [v4 Decode](https://github.com/riscv-boom/riscv-boom/blob/54f11b85c9a670ef3dd18bc675994b9a661b06ee/src/main/scala/v4/exu/decode.scala) 会把当时的中断标成微操作异常，最终仍到 ROB 队头处理；这并不要求本项目照搬其 Decode 采样点。本项目的中断在提交边界按当时有效 CSR 状态仲裁，尤其要遵守串行 CSR 执行后先退休再复核的约定。

**2026-10-02 补定 trap 请求/重定向主流程**：提交控制在上述边界锁存一次 trap 请求，至少携带异常/中断区分、精确 EPC 候选 PC、cause，以及同步异常所需的 tval；同步异常的 EPC 是故障指令 PC，中断的 EPC 是被中断边界后的下一条尚未退休指令 PC。提交控制从选择该 trap 起阻止继续退休，清除年轻推测状态；CSRFile 作为架构 CSR 唯一状态所有者，接受请求后按当前特权/使能/委托状态更新对应的 epc、cause、tval 与陷阱状态，并给出处理程序入口 PC 及完成确认。前端只在该更新确认后接受系统重定向，从处理程序入口重新取指；迟到旧路径结果不得重新进入有效流水。`trap_ctrl` 可以承担请求整理与重定向控制，但不得另存一份独立的架构 CSR 状态。M/S 委托、`xtvec` 与状态位语义按 B29 复用 Breeze；信号编码、空 ROB 时 EPC 来源仍待落实，这里没有宣称目标 RTL 已实现。

**随后确认的退休互斥与紧凑时序**：正常退休与 trap 触发不在同一拍发生。若提交组中异常前还有更老指令，只退休该正常前缀，停在故障指令之前；下一拍故障指令成为最老有效项后触发 trap，不退休该项或任何年轻项。异常本来已是最老项时直接触发，不再额外插入等待拍。中断也先完成选定退休前缀，在无正常退休的 trap 拍处理，并遵守串行 CSR/其他不可撤销操作的安全边界。因此恢复可直接采用拍初 committed 状态，无需 trap 拍的同拍退休旁路。

时序目标为：周期 N 接受 trap，flush/恢复与 CSRFile 专用硬件 trap 更新、入口 PC 计算并行；N 末恢复推测 RAT、free list 和队列有效性，更新陷阱 CSR 并锁存重定向；N+1 前端开始按处理程序入口 PC 取指。不经普通 Zicsr 读改写执行通路，不额外串行等待一拍恢复完成；这是控制时序设计目标，实际取指返回还受前端流水及 ICache 命中影响，目标频率尚未验证。恢复沿用分支恢复的广播/取消机制，但选择 committed 边界；保留已提交 SQ 项及其后台 drain，取消未提交项，隔离迟到结果。free list 的提交态恢复记录具体采用位图还是等价结构仍待接口设计；不把既有分支 checkpoint 恢复当作已完成全局 trap 恢复。

## 30. B27：MRET/SRET 由队首正常提交触发（2026-10-02，已定主流程）

用户确认 MRET/SRET 到 ROB 队首、通过权限与合法性检查后，由该指令的正常提交直接触发 trap return；不要求先送普通 FU 执行完成。CSRFile 经专用返回控制恢复相应特权/中断使能状态，给出 mepc/sepc 返回 PC，提交控制清除年轻路径并重定向前端。正常返回指令本身退休，不产生新的异常 cause，也不把当前 PC 覆盖进异常 EPC；不合法则走 B26 的同步异常路径且不退休。B26 的“trap 入口与正常退休不同拍”针对异常/中断进入，不禁止合法 xRET 的退休与返回状态更新同拍。状态位和中断重新评估的架构语义按 B29 复用 Breeze；WFI 的 O3 停顿/唤醒接入及返回目标检查的异常归属仍需审查，当前未实施 RTL。

## 31. B28：首版面向 Linux 的自建 SoC（2026-10-02，已定范围与候选参考）

用户明确首版按单 hart RV64GC/Linux 完整平台需求设计；不采用 LiteX 框架，SoC 使用 Vivado IP 与自写逻辑集成。此要求取代任何把 Flow 的 LiteX/LiteDRAM 集成方式直接继承到 CISLC-O3 的假设。借鉴 Breeze 已有的软件可见接口及验证经验，不把其板级或 Linux 历史结果算作 CISLC-O3 验证结果。具体 IP 型号、地址图、启动介质和中断控制器实现尚未全部冻结；既有 SD DMA 需求保持在首版系统范围内。

本轮核对 Breeze：`litex_wrapper/flow/rtl/FlowClint.sv` 和 `FlowPlic.sv` 是独立 SV 模块，当前带 64-bit Wishbone slave；`fpga/kcu105/target.py` 负责地址译码、总线和 msip/mtip/mtime/meip/seip 接线。可参考其寄存器与 gateway/claim/complete 机制，后续改接 AXI/AXI-Lite 等选定总线时必须重查字节地址、访问宽度、写 strobe 和握手，不能把原包装直接当 AXI 外设。`design/src/main/scala/core/RegFile.scala` 包含 M/S pending、enable、delegation 仲裁、软件 STIP 路径及可选 Sstc 路径；核内 CSRFile 应承接这些架构语义。

候选平台分工：DDR4 控制器/PHY、时钟复位、AXI 互连/CDC、UART 可选 Vivado IP；核、cache、CLINT/PLIC 兼容控制、启动 ROM/装载逻辑及 SD DMA 协调由自写或审计后复用模块承担。首版已选择 CLINT/PLIC + OpenSBI 传统定时器转发路径，具体见 B29；首版不依赖 Sstc。UART 更换后应同步 OpenSBI 驱动、Linux 配置、设备树和控制台参数；CPU 时钟与 mtime 时间基准必须分别描述且与硬件一致。启动路径需要 DDR 可用和镜像装载成立，再交给 OpenSBI/DTB/Linux，不能直接沿用旧 LiteX BIOS 装载假设。

## 32. B29：Linux 经验复用与机制审查交接（2026-10-02）

### 32.1 已确认的复用范围

用户已确认传统定时器路线，并要求后续 Linux 特权、中断控制器等常规机制复用 Breeze 经验，不再逐节重新讲解或重新选择。首版链路为 `CLINT mtime/mtimecmp → MTIP → M-mode OpenSBI → 软件 STIP → S-mode Linux`。Linux 通过 SBI set_timer 设置下一事件，OpenSBI 编程比较值并清除旧 STIP、重启机器定时器中断；机器定时器到期后由 OpenSBI 屏蔽 MTIE 并置 STIP，交给 S 态处理。具体固件实现须与所选 OpenSBI 版本核对；首版不依赖 Sstc，未禁止未来添加。

CSRFile 承接 M/S pending、enable、delegation、trap entry/return 及软件 STIP 语义；平台提供 MSIP/MTIP/MEIP/SEIP 等输入，不能把平台反映的只读 pending 位误做普通可写寄存器。PLIC 的 priority、pending、enable、threshold、claim/complete 与设备中断接线沿用 Breeze 的经验。复用意味着以已审查实现为依据迁移语义和验证经验，不是照抄 Wishbone/LiteX 包装、旧核数、源数、地址或时钟参数，也不把 Breeze 的测试结果算作 CISLC-O3 已通过。

源码入口：Flow 的 `design/src/main/scala/core/RegFile.scala`、`litex_wrapper/flow/rtl/FlowClint.sv`、`litex_wrapper/flow/rtl/FlowPlic.sv`；原接线参考 `fpga/kcu105/target.py`。B28 的自建 SoC、不使用 LiteX 和首版 SD DMA 范围保持不变。

### 32.2 本窗口已定机制索引

| 机制 | 当前有效决定 |
| --- | --- |
| 串行阻塞与 CSR | B22：Decode 截断同组年轻前缀；CSR 自身入 ROB，等老指令退休后进入独立小通路；执行完成可更新 CSR，随后必须退休；三个成本计数器 |
| 普通 FENCE | B23：需排序且 pred.W 时等 SQ 正常 drain；否则不额外排空普通 SQ；年轻执行由 Decode 阻塞 |
| FENCE.I | B23：SQ drain、扫描并写回逐出 dirty L1D 到 L2、旧 I 请求隔离和 ICache 失效、完成后重取；次数与数据维护周期计数 |
| SFENCE.VMA | B24：先全量 SQ drain，再按既定 VA/ASID/global 规则同步翻译状态；不要求 dirty DCache sweep |
| stride 地址预测 | B25：预测 load 有效地址并提前取数，真实 AGU/权限/SQ/数据新鲜度验证后才写回；未来 V 设计未展开 |
| 异常与中断 | B26：精确提交边界；fault 本身不退休；串行不可撤销操作先完成并退休；trap 拍无正常退休，专用 CSR 更新与恢复并行 |
| MRET/SRET | B27：合法队首指令自身正常退休直接触发返回、状态恢复和重定向；非法走异常 |
| Linux 平台 | B28/B29：单 hart RV64GC/Linux；Vivado IP + 自写 SoC；复用 Breeze 常规机制，传统 OpenSBI 定时器路线 |

### 32.3 下一窗口怎么查缺口

先完整阅读两份基线，再只读检查目标工程当前状态和相关框架。对每项发现分清：**设计已定但 RTL 未实现**、**已有合同需要接口细化**、**真正缺少行为选择**。不能把这三类混成“机制都没设计”，不能把旧源码 TODO 当作尚未讨论的证据。用户确认一个机制后记录，再按实施授权补框架；本次查漏只修改文档。

优先审查下列接缝，它们是检查入口，不是本轮新决定或已经证明存在的 bug：

- B26 的空 ROB 中断 EPC/下一架构 PC 来源；异常、中断、xRET、分支恢复同时发生时，按 D24 架构边界和年龄规则落实整套请求选择；不能再次打开“异常是否退休”的议题。
- 全局 trap 恢复 committed RAT/free list/队列的实际通路，以及前端 E/C、RAS 恢复完成条件。后续 D29/B30 已选择 BOOM 式 RAS 快速修复，并允许系统入口首笔取指与预测恢复解耦；系统 committed 预测上下文来源及提前取指身份/元数据接入仍待闭合。不能把普通分支的 FTQ 检查点机械当作系统边界，也未选择清空 RAS 或复制 BOOM 系统 flush 清零历史。
- B09 的 reservation 清除与 LR/SC 前进性、B07 的 PTE A/D 更新原子性：先检查已有合同和实现，再判断哪一项确实需要设计选择。普通非对齐/跨页访问的支持边界随后已由 B31 确定，不再作为未决范围。
- B15 的 fflags 退休累积、FS 状态与 CSR/trap 的原子更新，WFI 停顿/唤醒在 O3 中的接入，以及不可撤销 MMIO/AMO 的中断安全边界。标准语义参考 Breeze，重点检查乱序接入带来的差异。

每次只挑一个需要选择的机制，讲正常路径、冲突/恢复、必要状态和资源代价。必要时查 BOOM、香山、规范或论文，给出具体版本和行为证据；不主动开启整套 Linux 基础课程，不重新提出已否决的复杂一致性路线，不将表深、mux 编码和位宽包装成新机制。框架、编译、仿真、时序、FPGA/Linux 和性能测量证据分别记录。

本次查漏修正了文档前部“统一中断暂缓”、B21 的旧下一窗口议程、B22 对已定屏障仍写待讨论等过期表述，补齐已确认的定时器与 Breeze 复用决定，并在前端第 16.4 节标出恢复时序接缝。没有修改目标 RTL、运行测试或取得新性能证据。

## 33. B30：BOOM 式 RAS 快速修复与恢复交接（2026-10-02，已确认）

用户在查阅 BOOM 源码与论文比较后确认采用前端 D29 方案，完整合同见前端第 6.2 节；本节同步后端交接，不重复发明另一套恢复协议。

- RAS 暂定 16 项环形寄存器数组；每个 FTQ 保存区域入口 `{top_idx,count,top_addr}`。取消原逐条 undo，保留 D23 的完整 E/C 快照和 D09 事件资格规则。
- 执行纠错按 D24 选唯一赢家，恢复入口 E/C 和 RAS 索引/占用数/保存栈顶，再应用一次核实后的控制流动作。BRU/预解码须提供准确类型、原始长度及目标，不能继续采用错误的预测类型。
- 普通分支目标时序：R0 接受重定向并读检查点，R1 恢复及修正，R2 从正确 PC 发起正常预测。要求 E/C 宽读取、恢复优先仲裁、RAS 栈顶修复与正确压栈双写选择及旁路；这是从前端接受重定向起算的实现目标，不是整核误预测惩罚或已验证的频率。
- 接受深层 RAS 被错误路径覆盖后仍可能导致后续 return 误预测的取舍；真实 JALR 执行继续纠错。不得继续宣称 RAS 全内容精确恢复，不改变架构正确性要求。
- 恢复请求及快照返回绑定身份；更老请求或同位置更高优先级请求替换时，旧恢复不得写回或错误解除阻塞。预测表仍按原上下文在提交时训练。
- 系统入口已知 PC 的首笔取指允许与恢复解耦，保留 B26 的 N+1 发起取指目标；普通分支不采用停用预测器并后台慢慢撤销的路径。系统 committed 预测上下文来源、空 ROB/已释放 FTQ 边界及提前请求的返回槽/元数据交接仍须闭合，见前端 16.4。

依据为 BOOM v4 `54f11b85c9a670ef3dd18bc675994b9a661b06ee` 的 FTQ `ras_idx/ras_top` 保存修复及前端写回；并非此前前端研究的 58ef2720。BOOM 该版系统 flush 清零全局历史主体的行为没有被选入本设计。D29 的占用数、双写与 R0～R2 是本项目适配决定。论文与固定源码链接见前端 6.2。

本次只更新两份设计基线；目标工程的 `ras.sv` 等仍保留旧框架，未修改 RTL、未编译或仿真，不覆盖其他 agent 的改动。B29 是审查入口，B30 是最新恢复决策补充；余项继续区分已定待实现、接口细化和真正行为选择。

## 34. B31：同 line 非对齐访问支持与跨 line 异常计数（2026-10-02，已确认）

用户选择方案②：普通可缓存内存的标量非对齐 load/store（包括相应浮点访存）在同一条 cache line 内由硬件支持；跨 line 则报告地址非对齐异常，不发出该指令的数据读写。MMIO 不进入这条普通内存拆分路径，A 扩展仍按 B09 要求自然对齐。该决定不涉及取指跨块拼接，也不扩展至 V。

- 判定为 `line_offset + access_size <= line_bytes` 时落在同 line；比较须保留进位，不能因窄位宽溢出误判。跨 line 的 load/store 分别使用相应地址非对齐 cause，保留原指令 PC、有效地址及动态 ROB 身份，按 B26 精确处理。
- 同 line 内可能跨 bank/数据口粒度，需要内部拆分与拼接，但仍是一条架构指令、一次完成/退休。权限检查覆盖全部访问字节；load 在数据及检查全部完成后才交付，store 在检查及数据齐备后才能完成并等待退休/drain，遵守 B04/B05。具体阵列端口和拆分状态待实现。
- 在 64B line、4KiB 最小页的组织下，普通标量访问跨页必然跨 line，因此本路径不新增双页翻译、拼接完成机制。不承诺非对齐访问整体原子性；MMIO、PMA/PMP 拒绝及其他异常的完整优先级按访问检查接口细化。
- 软件对跨 line 非对齐异常的处理/模拟能力须另行核对，不能以未来编译器优化代替运行时合同。编译器可通过已知对齐、循环前导、运行时路径选择或小粒度访问减少此类事件，但不能假定动态地址的 line 位置总能静态判定。

### 34.1 新增一个 64 位逻辑计数器

`misaligned_crossline_traps`：一条普通标量 load/store 因上述跨 line 限制，最终在 ROB 最老位置被正式接受为地址非对齐 trap 时加一。计数对象是实际陷入次数，不是所有推测地址检测次数，也不是所有同 line 非对齐访问。

- LSU 将跨 line 非对齐原因随异常身份保存到提交端；只在 B26 的 trap 接受握手计一次。故障指令不退休，不能用该指令的 retire 脉冲计数。
- 错误路径被取消不计；等待、内部重放、异常 valid 保持多拍不重复计；同一 PC 返回后再次执行且再次正式陷入，属于新一次事件，重新计数。
- 不统计取指、预取/地址预测候选、LR/SC/AMO、普通 MMIO 错误或最终选为其他 cause 的异常。load/store 首版合计，不额外增设两个分类计数器。
- 用同一测量窗口的 `1000 * delta(misaligned_crossline_traps) / delta(instret)` 表示每千条退休指令的此类异常次数；分母为零时不报告比值。这是异常密度，不是“所有访存中有多少比例非对齐”。分母含测量范围内的异常处理/模拟指令，编译器对照实验须保持采集范围一致。
- 若以后要统计占全部架构访存尝试的比例，需另有同范围的访存分母，包含成功退休与相应故障尝试，不能直接将本计数除以成功退休的 load/store 后冒称完整发生比例。本次只确认新增上述一个计数器。
- 读取地址、清零、统一快照及溢出规则随性能计数 ABI 确定。该次数不能直接换算成周期或 IPC 损失。

本次仅记录用户选择与计数合同，并更新旧待定项；未修改 CISLC-O3 RTL、未编译或仿真，计数器尚未实现。

## 35. B32～B41：本轮确认的机制共识（2026-10-02，已确认）

以下十项为 B31 之后用户明确确认的共识，随后同日补入目标工程 RTL 框架（只有类型、接口、模块边界、接线与合同注释，新模块均为空壳；未编译、仿真、综合或测试）。字段、容量、拍数仍未冻结的部分在各条中单独写明，不能把“参数未定、框架未连接”当作机制缺失。

### 35.1 B32：保守 load/store 依赖

- load 遇到地址未知的更老 store 时，等待相关依赖条件解除（该 store 地址写入 SQ，或被取消/提交排出后重新判定）。
- 不能把“等几拍”实现成固定超时后无条件越过；首版不加入未知旧 store 地址下的推测越过与违例恢复。替代 B04 6.2 节末“尚未冻结”的表述。

### 35.2 B33：提前唤醒与完成 FIFO

- 对能确定结果交付时间的流水 FU，支持提前一拍安排依赖指令。memory 不强求提前唤醒；迭代除法不能从启动时假定固定延迟，只能在结束时间已确定时使用。
- FU 完成端使用局部 FIFO 吸收写回仲裁等待。已选的 FIFO 头部结果可参与 bypass，不要求先写 PRF；FIFO 中尚不可交付的条目不能提前唤醒消费者。
- 不可停顿流水线必须有完成空间预留；结果被仲裁推迟时，不能破坏已经发出的唤醒承诺。晚到消费者、PRF 写入和 bypass 退出之间不得有数据可见性空洞。
- 这取代 B21“流水乘法完成端背压与结果容量”待讨论项；FIFO 深度、承诺提前量与 PRF 读写交接拍数待定。

### 35.3 B34：MULH 类 + MUL 执行融合（本项目方案）

- 在 Decode Queue 输出、Rename 之前匹配程序顺序相邻的 MULH/MULHU/MULHSU + MUL：源寄存器顺序一致，前条 rdh 不得覆盖源寄存器。可覆盖 buffer/本拍输入边界，但不任意向前搜索，也不为未来可能的配对强行等待。
- 保留两条指令的 rename 目的映射、两个 ROB 身份和两次架构退休；只形成一次乘法执行请求，携带两个结果归属，交付高低两个结果。第二条是“不独立执行的融合成员”，不是普通 NOP。
- 成对资源接纳必须完整，否则不融合或等待，不能留下半个融合对。结果可通过完成 FIFO 分拍交付；取消和迟到结果按各自身份过滤。单步、触发器、异常等不能安全合并的情况不融合。
- 这是本项目方案，不能写成 BOOM/香山已实现同样的双结果融合。

### 35.4 B35：LR/SC reservation（补全 B09 11.2 清除表与前进性）

- 一条独立 reservation，以物理 cache line 为冲突粒度，并保留 LR 地址/大小用于配对；首版只允许同物理地址、同大小的 SC 成功。不设固定超时；不从 LR 到 SC 一直锁住 cache line。
- LR 在队首执行，读取数据与建立 reservation 处于一致的访问边界。SC 先完成地址/权限/对齐/PMA 检查，miss 可以取行等待；最终在短保护窗口内重新检查 reservation 并条件写入，不能提前锁定成功。新 LR 替换记录；SC 成功或失败都清除。
- 清除：本核 store/AMO、DMA 写、PTW A/D 实际更新与保留行冲突；reset、trap 入口、合法 xRET、进入 debug；首版 SFENCE.VMA/地址空间切换。
- 不清：普通分支恢复；cache 替换、clean、writeback、DMA 读引起的失效（以及 B41 的 L2 容量回收）。
- DMA 写在取得行保护权时与 SC 明确排序，不能仅看到排队请求 valid 就清除。没抢到资源应等待，不能拿 SC 失败替代公平仲裁。SC 回填及最终执行须有前进保障，不能被重复替换或 DMA 读无限抢占。

### 35.5 B36：硬件 A/D 更新，常用路径保持流水线

- TLB 命中、权限和 A/D 都满足时走原 load/store 流水线，不新增流水级。更新状态机放在旁侧，普通 load/store 不经过它。
- A 位可以在推测翻译中原子更新，完成更新后才交付可使用的翻译。
- store 遇到 D=0 时标记 needs_D 并释放 PTW 槽位，到 ROB 队首再做非推测更新；首版慢路径可重新遍历，不要求每个 SQ 项保存完整 PTE 快照。D=0 慢路径阻止年轻访存越过，已执行的年轻访问纳入重放/排序处理。
- DCache 提供内部“完整 64 位 PTE 比较 + 条件置 A/D”入口。比较不匹配时重新遍历检查，不能直接 OR 后覆盖，也不是立即报 page fault。更新错误归属原指令，在退休前处理。
- 取消、SFENCE.VMA、satp 切换不仅要过滤旧响应，还必须阻止失效上下文发起新的 PTE 写入。内部 PTW 更新不能被外部 AMO 的 ROB 队首门控卡死。

### 35.6 B37：committed_next_pc

- 提交端维护“已退休前缀之后的下一架构 PC”：普通指令用原始 PC + 真实指令长度，控制流用真实后继 PC；同拍多条退休取最后一条实际退休指令的后继。
- 正式 trap/xRET 生效后更新为入口/返回目标；普通分支恢复不修改；复位初始化到启动入口。
- 用于中断精确 EPC，尤其 ROB 为空时；同步异常仍用故障指令 PC；不允许用推测取指 PC 代替。闭合 B26/B29 中“空 ROB 中断 EPC 来源”的审查入口。

### 35.7 B38：WFI

- WFI 在 Decode→Rename 串行阻塞年轻指令。合法 WFI 在队首正常退休后进入等待，committed_next_pc 指向其后继。
- 等待期间暂停新指令推进，不关闭整核时钟；SQ drain、cache 回填、DMA 协调、中断检测继续工作。WFI 不额外充当 FENCE。
- 唤醒条件与正式中断条件分开：对应单项使能且 pending 的中断即可唤醒，不额外要求全局 MIE/SIE 打开，也不按委托结果屏蔽唤醒。醒后满足 trap 条件则进入中断，否则从后继继续。
- 入睡同拍已有唤醒条件则不睡，不能只检测后续边沿。权限/TW 等合法性按既有特权规则处理。

### 35.8 B39：晚到不可恢复写回错误

- 首版只做 sticky fatal + 执行隔离，保持到复位：停止新指令推进和后续正常退休，但保留必要的在途总线收尾。
- 失败写回不能按成功释放，维护/DMA 不得虚假完成。普通精确异常不升级成 fatal。synthesizable 的 fatal 状态不能用仿真 $fatal 代替。
- 不做软件恢复、自动重试、RNMI 或完整 Debug Mode。详细错误记录与调试以后接 FASE，本轮不扩展成 BEU/RAS 子系统。闭合 B06 中“晚到硬件错误报告方式”的待定项。

### 35.9 B40：浮点架构状态退休接入

- fflags 随原指令保存，按实际退休项 OR 合并，一次交给 CSRFile。
- 退休写架构 FPR 时置 FS Dirty，不比较新旧数据；退休项带非零 fflags 时也置 Dirty，即使结果写整数寄存器。软件写浮点 CSR 时，在既定串行更新点置 Dirty。
- 错误路径不更新架构 fflags/Dirty。接好 CSR、trap 与退休事件的互斥，不给普通 FP 执行增加状态阶段。闭合 B15 末尾的 fflags/FS 待落实项。

### 35.10 B41：L2 inclusive 与回收

- L2 已纳入首版，不是可选的新增模块。采用 inclusive，覆盖 L1I 和 L1D。**2026-10-02 用户同时确认 L2 组相联、PLRU 替换**；框架按 tree-PLRU 落实（每 set ways-1 位，命中与安装时更新，选 victim 时跳过正在回收、在途或受保护的 way，路数取 2 的幂）。
- L2 淘汰前保护目标行，定向失效两个 L1，协调在途回填，防止旧响应重新安装。L1D 脏副本先交回最新数据再确认失效；inclusive 不代表 L2 数据始终最新。首版每次探测两个 L1，不要求先建精确的 L1 驻留目录。
- 收齐确认、接管最新数据，并为必要写回提供可靠保存位置后，才能复用 way。容量替换不清 LR/SC reservation。
- 启动回收前预留接收/写回所需资源；探测应答不依赖普通 miss 的空闲 MSHR；资源不足时推迟新回收，不堵旧事务完成。
- 同一物理行由统一事务状态确定顺序，兼容读 miss 合并；回填、L1D 写回、淘汰、DMA 不能各自独立修改同行状态，不同行继续并行。
- 完整 L1D 行写回不需要先读 DDR；正常 inclusive 情况下，该写回应命中 L2 项或同行在途事务；正在回收时并入回收事务，不重新分配；既无驻留项又无在途事务时，应暴露包含关系错误，不能用自动重新分配掩盖。
- 总原则（用户同日确认）：简单情况要快，不改常规 load/store 流水线。回收探测与 DMA 探测共用 L1D 维护入口，L1I 回收走 ICache 维护入口，都不在普通访问路径上增加查询口或流水级。

### 35.11 同日确认的实现归属

- FENCE.I/SFENCE.VMA/satp/PMP 的系统同步由 commit_ctrl 统一编排。FENCE.I 顺序为：SQ drain → L1D 脏行扫描写回 L2 并逐出 → 前端同步（停取指/预取、隔离旧请求、ICache 全失效）→ 退休并重取。frontend_sync_ctrl 只做前端部分，不再自行发起 DCache clean。

### 35.12 仍未确认，不得冻结

- “L2 整行收齐、无错误并安装后再交付 L1，暂不 early restart”仍只是建议。
- L2 容量、路数、bank、MSHR/回收槽/写回缓冲数、AXI 宽度/ID、具体流水拍数未冻结。
- 系统 committed 预测上下文来源，以及首笔取指与恢复元数据的交接仍需闭合（前端 16.4）。
