# Breeze 新版 D-cache 与 L2 微架构设计

本文记录新版 Breeze v1 的结构、流水线、并发边界和典型事务行为，供微架构审阅。审阅固定后再单独编写 RTL 级 spec；本文不定义 Bundle 字段、opcode 编码、逐寄存器状态机，也不冻结尚待容量推导的队列深度，不代表新版已经实现或验证。

唯一已定案的外部设计输入是 [`breeze-mmu-rtl-spec.md`](breeze-mmu-rtl-spec.md)。旧核心、`Coherence.scala`、`BreezeL2Home`、`flowSRAM` 和集群内部的 Wishbone 连接都是待覆盖的历史实现，不构成新版约束。集群对外改为标准 AXI 边界，SoC 外壳继续使用 LiteX，见第 5 节。旧的 `BreezePipelinedDCache` 概念模块及其测试已删除，其设计记录可在 git 历史中查到。

以下区分**已同意机制**、用于解释机制的**拍级示例**和第 7 节的**待拍板参数/细节**。普通 Load/Store 混合流水、S0 保守 store→load 冲突检查、pending-store 延迟写、单 MSHR 的 hit-under-miss、S2 判定即提交、阻塞式 MMIO、LR/SC 进流水、读副本授 E、自有四链路协议、L2 主流水作为阵列唯一入口、按 set 保护及两个慢槽等已同意机制不再列为候选。

## 1. 整体结构图

```text
                         每核（集群支持 1 / 2 / 4 核）
  前端 ── iTLB ── L1I ─────────────────── Read / 数据（不登记）──────┐
             │                                                     │
             ├── 共享 PTW（每核 I/D 两侧共享，不跨核共享）           │
             │       │ 独立物理 PTE 读入口                          │
  LSU ─── dTLB ──┐   │                                             │
                 v   v                                             │
       ┌──────────────────── L1D ─────────────────────┐             │
       │ S0：仲裁、store→load 冲突检查、VIPT 读、dTLB   │             │
       │ S1：翻译结果、各路 8B 字、查询结果保存         │             │
       │ S2：PA tag/PMP/PMA、命中选择、快照有效性       │             │
       │     ├─ Load → 响应                            │             │
       │     └─ Store → pending-store → 次拍 mask 写    │             │
       │ 翻译等待 / 1 个 MSHR / 写回槽 / 侦听 / 原子    │             │
       └─────────────────────┬────────────────────────┘             │
           REQ/RSP↑ 向 L2；SNP/RSP↓ 向 L1D，四链路独立推进           │
                             v                                     v
  SD 卡 DMA：整行读、带 mask 写、独立 source ID ───→┌───────────────┐
                                                    │ L2/Home       │
       完成入口 RSP↑/内存返回 ──────────────────────→│ 完成分流/仲裁 │
                                                    │ 主流水 S0→S2  │
                                                    │ 阵列唯一入口  │
                                                    │ 按 set 保护   │
                                                    ├───────────────┤
                                                    │ 两个慢槽：等待│
                                                    │ 事件、发任务  │
                                                    │  ↙         ↘  │
                                                    │ probe   内存  │
                                                    │ 引擎    引擎  │
                                                    │         2读在途│
                                                    └────────┬──────┘
                                       集群边界：AXI4 mem / AXI4-Lite mmio
                                                           v
                                       LiteX 外壳：DDR 直连 LiteDRAM，其余转 Wishbone
```

L1D 与 L1I 都是 VIPT、16 KiB、4 路、32 B 行，共 128 set。每路 4 KiB，正好等于最小页：行内偏移为地址低 5 位，set index 为位 [11:5]，都在页内偏移中，不会产生虚地址别名；以 PA tag 判断命中，因此虚地址别名不能绕过物理冲突检查。

L2 使用 PA 查询，8 路，容量为 `核数 × 64 KiB`，行大小同为 32 B。这里的容量指数据容量，不含 tag、目录和事务缓冲。4 核时 L2 是全部 L1（每核 L1I + L1D 共 32 KiB）总容量的 2 倍。

| 核数 | 每核 L1I / L1D | 共享 L2 数据容量 | L2 总行数 | L2 set 数（8 路） |
| --- | --- | --- | --- | --- |
| 1 | 16 KiB / 16 KiB | 64 KiB | 2048 | 256 |
| 2 | 16 KiB / 16 KiB | 128 KiB | 4096 | 512 |
| 4 | 16 KiB / 16 KiB | 256 KiB | 8192 | 1024 |

完整参数表见 5.5 节。

L1D 不做 hit-under-miss；某核因本地 miss 阻塞时，其他核仍可向 L2 发请求。L2 优先级固定为：**跨核 hit-under-miss > 命中端到端延迟 > 启动间隔 II**。II=2 可以作为 v1 起点，不以达到 II=1 为代价牺牲前两项。

## 2. L1D 流水线

### 2.1 顺序混合流水与各级职责

普通 Load/Store 允许混合进入同一条流水，不因类型变化排空。核心按程序顺序提供地址和数据已就绪的访存，不跳过被阻塞的老访存，也不让年轻 Load 越过地址未知的老 Store。相关寄存器未就绪、响应容量不足或实际资源冲突仍会停顿。

下表以 T 拍接收请求、各级动作在该拍拍末锁存为约定；无反压、TLB hit 和 cache hit 时适用。

| 级/拍 | 职责 | 结果去向 |
| --- | --- | --- |
| S0 / T | CPU 与 PTW 入口仲裁；Load 用 VA[11:3] 与更老 Store 做保守冲突比较，冲突则停在 S0；无冲突时配对启动 dTLB 和 VIPT Tag/Data 读；保存原请求 | S1；接收前预留 TLB 结果保存容量 |
| S1 / T+1 | 接收翻译/页权限结果；各路按行内位置预选 8 B 字；保存 PA、tag、数据与请求；TLB miss 转等待 | S2，或翻译等待路径 |
| S2 / T+2 | PA tag 比较、命中路选择、Load 格式处理；并行做 PMP/PMA；检查侦听/refill 造成的快照失效和一致性权限；检查与 MSHR 同行 | 向后端给出判定（2.9 节）：命中完成、进入 MSHR、异常，或暂不判定并在 L1D 内停住/重查 |
| pending-store / T+3 | 保存已通过 S2 的 Store，按 byte mask 写 Data，并使 E→M 或保持 M | 实际写入后形成 Store 完成；写口受阻则保持 |

普通 Load 的寄存响应在 T+3 可见；Store 的 Data 写入发生在 T+3 拍末，完成结果最早 T+4 可见。后者是本例的延迟写时序，不承诺所有 Store 在固定拍数完成。响应反压时保持结果，禁止重复完成或重复写入。

Data SRAM 具有**字节写使能**，普通 Store 不读改写。连续两笔部分 Store 按年龄依次写各自 mask，后写字节覆盖前写字节，未选中字节由 SRAM 保留。AMO 需要读取旧值用于运算，与普通 Store 的读改写问题分开。

Tag/状态、Data 的端口组织在 RTL spec 阶段细化。若 Data 采用独立读写端口，可让 S0 读与 pending-store 写重叠；单口或 bank 冲突则按资源停顿，不把资源停顿扩大成类型切换排空，也不依赖特定 SRAM 的同地址读写返回模式。

#### Load / Store 共用的硬件结构

> **已弃用：**下图按旧版“S2 比较 pending-store、快照失效后重查”的冲突检测绘制，与 2.2 节的 S0 保守检查不一致，仅作结构参考，以正文为准。

![L1D Load/Store 流水线硬件结构（已弃用）](figures/dcache-load-store/hardware-pipeline.svg)

图中 R01、R12 表示级间寄存边界，作为教学标记，不冻结 RTL 的寄存器命名。dTLB 和同步 SRAM 已有各自的查询时序，图中的次拍输出不额外增加流水级。可编辑源见 [hardware-pipeline.drawio](figures/dcache-load-store/hardware-pipeline.drawio)。

共用方式是**一条带操作类型的请求流水**：每拍入口至多选择一条请求，op、地址、访问大小和年龄随它寄存到下一阶段。S1 处理哪条请求就使用那条请求的信息，S2 同理；各级不需要同时处理相同类型。Store 的写数据/mask 另路随同一条请求携带，不能与另一条请求的 PA/命中路混用。

| 硬件 | Load 如何使用 | Store 如何使用 |
| --- | --- | --- |
| S0 入口、索引、dTLB | 查地址翻译，以 VA index 启动数组读 | 同样查翻译、查 Tag，以 Store 类检查页权限 |
| S1 查询结果与级间寄存 | 保存 PA、四路 Tag/状态、四路预选字 | 保存 PA、Tag/状态；携带自身写数据/mask。即便统一查询读出了旧 Data，普通 Store 也不使用它合并写入 |
| S2 PA tag 比较、权限/异常判定 | 判断可读命中，得到 hit-way | 判断可写命中，得到待写 way/set/word；若需升级则进入慢路径 |
| S2 后的操作分支 | 命中路 MUX、大小/符号处理，进入结果寄存器 | 进入 pending-store；下一拍按 mask 写同一套 Data SRAM 的写口 |

> **已弃用：**下图中“S2 的 Load 与 pending PA 比较”已改为 S0 检查，以正文为准。

![同一拍不同类型请求占用共同流水（已弃用）](figures/dcache-load-store/shared-hardware-one-beat.svg)

例如依次接收 Store X、Load Y、Store Z、Load V，且它们的物理字互不冲突：T+3 拍里，pending-store 写 X，S2 判断 Y，S1 保存 Z 的翻译/Tag 结果，S0 为 V 发起查询。该拍末 V→S1、Z→S2、Y 形成 Load 结果、X 写入完成；下一拍各级可以继续接续请求。这种同时占据不同级的行为就是混合流水，不依赖一份全局 Load/Store 模式。

共同查询路径负责“找到行并确认访问合法”，Store 数据路径负责“把新数据写回”。store→load 冲突只是 S0 的入口控制：Load 与更老 Store 的页内字地址冲突时停在 S0，不发起数组读；pending-store 的写数据没有接到 Load MUX。具体规则和节拍见 2.2、2.8 节。

### 2.2 pending-store 与 store→load 冲突

pending-store 是 S2 后的一项待写寄存器，不是允许任意年轻 Store 越过老请求的 store buffer。它保存已确定的物理字地址、目标位置、数据和 byte mask；写口不可用时，后续 Store 不能覆盖该项。连续 Store 可以在老项完成写入的边沿接续新项，实际吞吐取决于阵列端口。

**Load 在 S0 用 VA[11:3] 与所有更老、尚未写入 Data 的 Store 比较，命中同一 8 B 字就停在 S0，不发数组读；等这些 Store 全部写入后再正常发起查询。不做 forwarding。**比较对象是位于 S1、S2 和 pending-store 的更老 Store，S1/S2 取随请求寄存的 VA[11:3]，pending-store 取其 PA[11:3]。

- **只误报、不漏报。**位 [11:3] 落在最小 4 KiB 页的页内偏移中，VA[11:3] 与 PA[11:3] 相同；两个虚地址映射到同一物理字时必然比较命中。不同物理页但页内字相同会被误判为冲突，只多停几拍，不影响正确性。
- **整字保守。**即使 byte mask 不重叠也停；同一 32 B 行的其他 8 B 字不因同行而停，但仍受阵列端口和状态变化限制。
- **以“已写入”为解除条件，不以固定拍数。**写入拍本身仍算冲突，不依赖 SRAM 同地址读写的返回行为，Load 最早在写入拍的下一拍发读；pending-store 写口受阻时 Load 继续停。
- **不需要作废和重放。**Load 只有在相关老 Store 全部写入后才读阵列，它的快照不会再被更老的普通 Store 改变，因此 S2 无需再比较 pending-store，也无需“隔拍旧快照”失效标记。比它年轻的请求本来就排在它后面，随它一起停顿。
- PTW 的物理 PTE 读经过同一检查，取自身 PA[11:3]。翻译等待、miss 等待中的更老 Store 按年龄先重查，Load 不会越过它们。

probe、refill 或权限变化仍可能使在途 Tag/Data 快照失效。这类事件发生在阵列读与 S2 判定之间时，随查询保留快照失效标记，被影响的请求在 S2 作废并从 S0 重查，重新取得数组和权限结果；停顿不表示稍后可以继续使用旧快照。该重查请求保存在流水内，按年龄先于更年轻的请求重新进入 S0；比它年轻、已在 S1 的请求一并作废重查。

资源代价是一个 pending-store 项和至多三个 9 bit 页内字地址比较器；不增加 Store-to-Load 数据旁路、字节合并 mux 或违例后的整核回滚机制。

### 2.3 年龄、异常与 kill

访存流水按年龄推进：老请求未通过必要检查或尚未判定时，年轻请求可以保留，但不得完成写入或交付越序成功结果。S1 页权限异常、S2 PMP/PMA 异常一旦确定，就 kill 更年轻的流水请求及其未生效待写项，并按顺序交付原请求异常。

**S2 判定即提交点。**请求在 S2 给出“命中完成”或“进入 MSHR”的判定时，它的地址翻译、页权限和 PMP/PMA 都已检查通过，此后不会再产生精确异常；后端可以据此提交该指令并继续执行年轻指令（见 [`backend-pipeline-design.md`](backend-pipeline-design.md)）。进入 MSHR 的请求此后发生的 refill 错误无法再精确交付，按 2.4 节的非精确错误处理。uncached/MMIO 访问不提前提交，后端等到实际响应返回。

因此不另设“不可撤销写入授权”握手：Store 只有在没有尚未决的更老访存、其自身检查通过且未被 kill 时，才能经 pending-store 写 Data。pending-store 是流水中更老的一项；年轻请求的异常不能撤销已经按序生效的老 Store。新核心还须按其精确异常规则交付可执行请求，不把可能被尚未决的更老指令取消的 Store 提前交给本流水写入；本文不定义核心提交接口。

CPU kill 可取消结果和未生效写入，不能抹掉已经发送的 GetS/GetM/Put、已经接收的 Data/Ack 或必须答复的 probe。协议事务继续安装或答复，CPU 结果按 kill 丢弃。正常 Load/LR 用 Load 类异常，Store/SC/AMO 用 Store/AMO 类异常；未通过权限检查的目标地址不发起 refill、写操作或 MMIO。非对齐数据访问按已定 MMU 输入合同，在进入 dTLB 前由 LSU trap。

### 2.4 MSHR、hit-under-miss 与 refill

L1D 有 **1 个 MSHR**，CPU 和 PTW 共用，TLB miss 本身不占用它。结构按 N 个 MSHR 设计，v1 取 N=1；miss-under-miss（N≥2）推到 v2。Load miss 发 REQ GetS 获取可读副本，Store miss 或 S 状态写升级一律发 REQ GetM 获取独占权限；L1 不区分“只要权限”和“要数据”，由 L2 按目录决定回 Ack 还是 Data。

**MSHR 记录：**行物理地址、GetS/GetM、refill 目标 way、请求类型；Load 另记目的寄存器标签（整数/浮点及编号），Store 另记 8 B 写数据与 byte mask。

**分配（在 S2）：**

1. 普通 Load/Store miss 或写升级，在 MSHR 空闲、写回槽可用时于 S2 分配，同时向后端给出“进入 MSHR”判定，后端提交该指令。LR miss 同样分配 MSHR（按 GetM），但不提前提交，后端等回放结果（2.7 节）。
2. 分配时即选定 victim way。victim 有效时立即经 RSP↑ 发 Put（dirty 带整行），由**写回槽**记住地址直到 PutAck；该 way 置为无效并**锁定**，refill 完成前任何命中都不使用它，替换也不再选它。写回槽尚未收到 PutAck 时不能分配需要发 Put 的 MSHR，请求暂不判定。
3. 写升级（本地 S → GetM）不选 victim，锁定的是原 way；等待期间该行若被 Inv 撤销，L2 回 Data，refill 装回同一 way。

**hit-under-miss 规则：**MSHR 等待期间，L1D 流水继续服务其他请求。

| S2 中的请求 | 处理 |
| --- | --- |
| 与 MSHR 同一 32 B 行（任何类型） | 暂不判定，在 L1D 内停住，refill 并回放完成后从 S0 重查；不做合并 |
| 其他行，命中且权限足够 | 正常完成（Load 返回数据，Store 进入 pending-store） |
| 其他行，miss 或需升级 | v1 只有 1 个 MSHR，暂不判定并停住，MSHR 释放后从 S0 重查 |
| PTW 的 PTE 读 | 与 CPU 请求同样规则；PTE 读 miss 使用 MSHR，但 PTW 等数据回来，不提前提交 |
| 不带 aq/rl 的 LR、SC | 与普通 Load/Store 同样在流水中执行，见 2.7 节 |
| AMO、带 aq/rl 的 LR/SC、uncached/MMIO | 不进入 hit-under-miss：等 MSHR 与 pending-store 均为空后执行，见 2.7 节和 2.10 节 |

停住的请求按年龄保存在流水内，比它年轻的请求随之停在后面，不越过它。

**refill 与回放：**RSP↓ 的 Data/Ack 完整接收后，refill 作为内部请求从 S0 写入 Tag/Data 并解锁 way；随后 MSHR 发起一次**回放**：以物理地址从 S0 进入流水（不查 dTLB，权限已在原 S2 检查），Load 回放读出数据并作为迟到数据送给后端，Store 回放把暂存的写数据按 mask 写入。回放即“原请求至少完成一次”，完成后 MSHR 释放。S0 仲裁顺序为：probe 处理、refill 安装 > MSHR 回放 > PTW > L1D 内停住请求的重查 > CPU 新请求。回放同样经过 S0 store→load 冲突检查；比原请求更老的同字 Store 在原请求的 S0 已被检查过，比它年轻的同行请求都被上表第一行挡住，所以回放不会读到错误的新旧数据。

**非精确的 refill 错误：**RSP↓ 回报错误时不安装、不解锁为有效行；Load 的迟到数据带错误标记送给后端，Store 的暂存数据丢弃。由于原指令已经提交，这类错误无法精确交付，v1 统一上报 `hartFatal`；是否改为非精确总线错误中断见第 7 节。

**MSHR 不阻塞的路径：**RSP↓ 接收与 refill 安装、RSP↑ 发送、与 MSHR/写回槽无关的侦听，以及已接收 PTW 请求的完成，均不因 MSHR 等待而停顿，避免“我等数据，所以无法答侦听”的环形等待。

### 2.5 TLB miss、PTW 与重查

本路径遵守已定 MMU 的下一拍响应、`dropS1Next` 和 `PtwMemIO` 合同：dTLB 的响应不能反压；miss 后 MMU 阻塞该侧，PTW 已接受的 walk 不因 CPU kill 而被丢弃。

1. X 在 S1 得到 TLB miss，移入翻译等待，释放 cache 查询级。两项翻译等待 FIFO 保存 X，以及 miss 同拍在 S0 已接收、被 MMU `dropS1Next` 取消翻译的年轻 Y；显式跟踪 Y，不等待一笔不会到来的 TLB 响应。
2. 下一拍关闭 CPU 新入口，更老 S2/pending-store 继续按序完成，已提交的 MSHR 继续等待和回放。等待的 CPU 虚请求不占 MSHR、不长期锁住 Tag/Data，从而 PTW 可进入。
3. PTW 以独立物理入口在 S0 仲裁，优先于等待请求重查和 CPU 新请求；绕过 dTLB，但仍查询 D-cache，按 S 模式做 PMP/PMA。PTE 读是 8 B 对齐读，也可能使用 MSHR 向 L2 取行。
4. PTW 响应独立路由，不依赖 CPU 响应接收；已接收的 PTE 请求最终恰好返回一笔 data/accessFault。
5. 翻译恢复后，按年龄从 S0 重查等待请求，重新查询 TLB 和 SRAM；pending fault 通过原 VPN 的重查交付。再次 miss 则继续等待，不假定一次重查必定成功。

若更老 cache miss 尚未结束，PTW 可等它完成，但其 RSP↓ 接收和 probe 完成路径仍推进；禁止出现“CPU TLB miss 持有 MSHR/阵列不放，而 PTW 等这个资源”的依赖闭环；MSHR 只会被已提交、与翻译无关的请求占用，它的完成不依赖 PTW。共享 PTW 的 I/D 仲裁、kill、CSR 上下文和 SFENCE.VMA 行为直接沿用 MMU spec，不在此重新设计。

### 2.6 侦听路径与本地写入

下文 probe 泛指 SNP 链路上的 Inv（失效）和 Downgrade（降为 S）。SNP 有独立接收/保持寄存器，按当前 tag/权限查询，完成失效或降级后通过 RSP↑ 发 InvAck/DownAck；本地为 M 时带回整行数据。UNIQUE 副本可能已由 E 静默转 M，回包必须反映最新数据，不能假定 L2 中的行仍最新。

probe 携带 L2 目录对本核的**目标角色**：sharer（L2 认为本核持 S）或 owner（L2 认为本核持 E/M）。Downgrade 总是 owner 角色，Inv 两种都有。

**L1 只在两种情况下压住 probe，其余 probe 必须立即处理：**

| probe 命中的行与本地状态 | 含义 | L1 动作与解除条件 |
| --- | --- | --- |
| MSHR 正在等的行，且 probe 角色高于本地状态（本地 I 收到任意 probe；本地 S 收到 owner 角色 probe） | L2 已把这次请求授出，Data/Ack 还在 RSP↓ 上，probe 属于更晚的事务 | 压住；数据/权限安装且 MSHR 回放完成，再按安装后的状态答复；PTW 请求已被 kill 时安装后即解除 |
| 写回槽中尚未收到 PutAck 的行 | L2 发 probe 时尚未处理这笔 Put | 压住；PutAck 到达后本地已无副本，答复不带数据的 Ack |

本地 S、正在 GetM 升级时收到 sharer 角色的 Inv，说明另一核的独占请求排在本核之前：L1 立即失效 S 副本并答复，继续等待 GetM；L2 之后按目录发现本核已无副本，回 Data 而不是 Ack。若这里也压住，L2 要等本核答复才能完成前一事务、再处理本核 GetM，就会成环。

两种压住的解除事件都在 RSP↓ 上，而 L2 发出这些 RSP↓ 消息不依赖被压住 probe 的答复，所以不会成环。第一种取代了 GrantAck：L2 在 Data 发出后即可对同一行发起新事务，SNP 与 RSP↓ 是两条独立链路，probe 可能先到，L1 用 MSHR 地址比较加角色判断识别；N≥2 时与每个 MSHR 比较。“至少完成一次”保证激烈争用下请求不会在安装后立刻被收走而活锁。此外，AMO 短窗口、LR 前进窗口（2.7 节）和下文 pending-store 同行仲裁只造成有限拍数的本地延迟，不依赖任何消息，不属于上述压住。写回槽压住期间，L2 侧的 Put 走完成路径、不等该 set 的保护，见 3.3 节。写回槽中的行在 PutAck 前不得再次发起 GetS/GetM，否则 L2 可能先看到新请求，误判为重复所有权。

probe 与 pending-store 命中同一行时按年龄/本地仲裁选定一个顺序：若老 Store 已进入本地生效步骤，先完成短写入再让 probe 取得新值；若权限先被 probe 撤销，未生效 Store 不得沿用旧命中权限，需重新获取权限后重查。普通查询快照受 probe 改动影响时标记失效。具体同拍仲裁留给 RTL spec，但不允许既答复“已失效”又让旧 Store 随后写入。

L1 同一时刻至多持有一个未答复的 probe，L2 也保证对每核至多一个 probe 在途；压住期间 SNP 链路对该核反压，不影响其他核。

### 2.7 AMO 与 LR/SC

AMO 独占 CPU 访存通路：停止年轻请求、排空老请求（包括 MSHR 与 pending-store 均为空），完成翻译/权限检查，获得最新数据和独占权限，再执行受保护的“读旧值→运算→写新值”。AMO 不在 S2 提前提交，后端等它的结果返回。**等 RSP↓ Data 期间不持原子锁，继续按 2.6 节处理 probe。**

短原子保护窗口只覆盖已取得权限后的本地读改写。冲突 probe 可在此窗口反压，写入及状态更新后解除保护；CPU 尚未接收 AMO 返回旧值不延长锁。异常/kill 在实际写入前处理，AMO 结果容量在进入窗口前保证可用。AMO.W 的字节写范围与返回扩展在后续 spec 定义。

**不带 aq/rl 的 LR/SC 在流水中执行**，与普通访存并行：

- **LR 按“有写意图”取行。**LR 用 MMU Load 类检查；本地为 E/M 时像普通 Load 一样在 S2 完成，提交时建立 reservation；本地为 S 或 I 时一律发 GetM，分配 MSHR，不提前提交，回放取得数据和独占权限后再完成并建立 reservation。每 hart 一份 reservation，记录物理行地址。
- **SC 永远不 miss。**LR 取得的是独占行；任何对该行的 probe、该行被替换、本 hart 的陷入都会清除 reservation，所以“reservation 有效”蕴含“该行仍为 E/M”。SC 用 Store 类检查，不能把权限异常吞成普通失败；在 S2 判定：reservation 有效且地址匹配时成功，经 pending-store 写入，返回 0；否则失败返回 1，不写、不访问 L2。SC 因此不使用 MSHR，也不需要排空。每次 SC 尝试结束清除 reservation。
- **前进保证。**LR 建立 reservation 后开一个有限长度的窗口（v1 取 80 拍，与 Rocket 相同），期间压住命中该行的 probe；窗口在 SC 执行、陷入或到期时结束。这是有限拍数的本地延迟，不依赖任何消息，与 AMO 短窗口同类，不会成环。它保证 RISC-V 规范要求的“受约束 LR/SC 循环”最终成功。
- reservation 跟踪 coherence 事件，不通过比较 LR/SC 两次数据判定；其他 hart 的同值写入也要使相应 SC 失败。LR 完成后中间指令正常执行，不锁住整个 LR→SC 区间。

**带 aq/rl 的 LR/SC：**rl 置位时，等 MSHR 与 pending-store 为空后再进入流水；aq 置位时，年轻访存等它完成后再进入。AMO 无论 aq/rl 均按上述独占路径。

FENCE 由后端作为请求发给 L1D，在 MSHR 与 pending-store 均为空时完成，从而保证已提交但仍在 MSHR 中的 Load/Store 先于 FENCE 后的访存生效。排空只限制 CPU 普通请求，PTW 和协议完成仍前进。

### 2.8 拍级示例

以下仅展示机制，不包含端口反压、probe 和 TLB miss。W 表示 pending-store 写入拍。

**不同物理字的 Store→Load：无类型切换气泡。**

| 拍 | S0 | S1 | S2 | pending-store / 拍末动作 |
| --- | --- | --- | --- | --- |
| T | Store X | — | — | — |
| T+1 | Load Y：VA[11:3] 与 X 不同 → 发读 | Store X | — | — |
| T+2 | 后续请求 | Load Y | Store X → 待写 | — |
| T+3 | 后续请求 | 后续请求 | Load Y → 响应 | W：写 X |
| T+4 | … | … | … | Y 响应可见；X 写入完成可见 |

**相同物理 8 B 字：Load 停在 S0，等老 Store 写入后再读。**

| 拍 | Store X | Load X | 结果 |
| --- | --- | --- | --- |
| T | S0 | — | 接收 Store |
| T+1 | S1 | S0：VA[11:3] 命中 S1 的 X → 停 | 不发数组读，年轻请求随之停在后面 |
| T+2 | S2 → pending | S0：命中 S2 的 X → 停 | — |
| T+3 | W，拍末写入 | S0：命中 pending 的 X → 停 | 写入拍仍算冲突 |
| T+4 | 已完成 | S0：无冲突 → 发读 | 读到更新后的字 |
| T+5 | — | S1 | 保存新数据 |
| T+6 | — | S2 | 形成成功结果 |
| T+7 | — | 响应可见 | 返回 Store 后的值 |

Load 无论在哪一拍到达 S0，都在 X 写入后的下一拍才发读，所以原先“隔一拍冲突、S2 时 pending 已清空”的旧快照窗口不存在。若 X 的写口受阻，Load 继续停在 S0。

**TLB miss 接收窗口。**T 接收 X；T+1 的 S1 报 X miss，同时 S0 已接收 Y；拍末保存 X/Y，MMU 取消 Y 的翻译；T+2 起关闭 CPU 入口，PTW 物理请求可使用主流水。恢复后先重查 X，再处理 Y。

**hit-under-miss。**T 接收 Load A（miss），T+2 在 S2 分配 MSHR、向后端判定“进入 MSHR”，后端提交 A 并把 A 的 rd 标为忙；T+1、T+2 进入的 Load B、Store C 访问其他行且命中，分别在 T+3、T+4 于 S2 正常完成，不等 A 的 refill。之后 Load D 访问 A 所在行，在 S2 暂不判定并停住；refill 安装、A 回放把数据送给后端后，D 从 S0 重查并命中。

### 2.9 与后端的接口：S2 判定与迟到数据

L1D 对后端的每个请求**按程序顺序恰好给出一次判定**，进入 MSHR 的 Load 之后**再给出一次迟到数据**：

| 事件 | 时机 | 内容 |
| --- | --- | --- |
| 判定：命中完成 | 请求的 S2 | Load 带数据；Store 已进入 pending-store |
| 判定：进入 MSHR | 请求的 S2 | 无异常，已提交；Load 的数据随后以迟到数据返回，Store 由 MSHR 完成 |
| 判定：异常 | 请求的 S2 | 异常类型；年轻请求被 kill |
| 暂不判定 | — | TLB 等待、与 MSHR 同行、MSHR 已满、写回槽未空等；请求留在 L1D 内，后端在对应级等待 |
| 迟到数据 | refill 后的回放 | 目的寄存器标签、数据、错误标记；valid/ready 握手，后端暂不能写回时由 L1D 保持 |
| LR、SC（不带 aq/rl） | LR 命中 E/M 与 SC 在 S2；LR miss 在回放完成时 | LR 带数据，SC 带 0/1；LR miss 不提前提交 |
| AMO、MMIO 结果 | 实际完成时 | 作为该请求的判定交付（带数据或异常），不提前提交 |

v1 只有 1 个 MSHR，所以至多 1 笔迟到数据在途；接口按 N 项设计，标签区分不同 MSHR。后端如何用这些事件提交指令、标记记分板和仲裁写口，见 [`backend-pipeline-design.md`](backend-pipeline-design.md)。

### 2.10 uncached/MMIO 路径

uncached/MMIO 访问不经过 cache，由每核一个**阻塞式 MMIO 状态机**直接访问总线，结果回来后才退休，期间整条流水停住。

| 环节 | 规则 |
| --- | --- |
| 识别 | S2 的 PMA 判为 device/不可缓存；不提前提交，后端在 WB 等结果 |
| 发出条件 | 它已在 WB（即最老的指令），且 MSHR 与 pending-store 均为空。因此 MMIO 与之前所有访存严格保序，驱动遗漏 fence 时“先写内存描述符、再写门铃寄存器”仍然正确 |
| 请求 | AXI4-Lite，64 bit 数据，1/2/4/8 B 由 WSTRB 表示；读数据按地址低 3 位移位并做符号扩展，复用 Load 格式化逻辑 |
| 响应 | OKAY 时完成；SLVERR/DECERR 报 load/store access fault，尚未提交，所以是精确异常 |
| 退休 | Load 拿到数据、Store 收到 B 响应后在 WB 退休；Store 不提前退休（不做 posted write） |
| kill 与中断 | 发出前可被中断抢占；**发出后不能取消**，中断与调试暂停等它退休后再接受 |
| 不支持的访问 | AMO、LR/SC 访问 I/O 区域报 store/AMO access fault；PTW 访问 I/O 区域报 access fault；不对齐访问在进入 dTLB 前已由 LSU 陷入 |
| 集群汇总 | AXI4-Lite 没有 ID，各核响应无法区分，所以集群级 `mmio` 口全局只允许 1 笔在途，各核轮转仲裁；LiteX 的 AXI-Lite→Wishbone 桥本身就是串行的，不损失性能 |
| 超时 | 集群内不做超时，依赖 LiteX 总线超时（超时后 ack 并返回全 1，不报错）；另设调试用 watchdog，等待超过阈值时记一条飞行记录事件，不改变执行结果 |

**取舍：**不采用 posted write（Store 进写缓冲即退休）。v1 的 MMIO 流量很小（UART 每字符约 8700 拍、SD 卡走 DMA、PLIC/CLINT 每次中断几次访问），posted write 省下的等待可以忽略；它却需要额外的规则：AXI 读写通道互不保序，后续 MMIO 读要等未完成的写；FENCE、中断、调试暂停要处理未完成的写；写错误只能非精确上报。若“MMIO 等待拍数”计数器在实际负载中占比明显，再在状态机前加 1 项写缓冲，不影响其他结构。

## 3. L1↔L2 一致性链路与典型事务消息流

### 3.1 链路职责与依赖纪律

L1D、L1I、DMA 与 L2 之间使用 Breeze 自有的四链路协议。拓扑是至多 8 个 client 到单个 L2/Home 的星形点对点连接，L2 是唯一排序点，每个 L1D 至多 N 笔 Get（v1 的 MSHR 数 N=1）、1 笔 Put 在途；协议只针对这个范围设计，不追求多级缓存、任意拓扑或第三方 IP 互通。消息名用于说明事务，本文不冻结编码和字段宽度。

| 链路 | 方向 | 消息 | 接收纪律 |
| --- | --- | --- | --- |
| REQ | client → L2 | GetS、GetM；L1I/DMA 的 Read；DMA 的 MaskWrite（带 mask 与数据） | L2 可以反压 |
| RSP↑ | L1D → L2 | Put（dirty 带整行，clean 不带数据）；InvAck、DownAck（本地 M 时带整行） | **L2 无条件接收**：每核预留 1 项写回行缓冲和 1 项 probe 答复行缓冲 |
| SNP | L2 → L1D | Inv（携带 sharer/owner 目标角色）、Downgrade（owner → S，dirty 时交回数据） | L1D 只按 2.6 节的两种情况压住，其余必须处理 |
| RSP↓ | L2 → client | Data(S/E)、Ack（升级时副本仍在，不带数据）、PutAck；不登记读者的 ReadData、写完成 WriteAck | **client 无条件接收**：请求是自己发的，接收空间在发请求前已预留 |

**依赖纪律只有三条：**

1. RSP↑ 和 RSP↓ 的接收不依赖任何其他消息；ready 只能因本地有限拍数的仲裁暂缓。
2. SNP 的接收只能依赖 RSP↓（MSHR 的 Data/Ack、写回槽的 PutAck），以及有限拍数的本地延迟（AMO 短窗口、LR 前进窗口）。
3. REQ 的接收可以依赖其他全部链路，例如 set 保护、慢槽占满。

依赖只从 REQ 指向 SNP、再指向 RSP，不存在回指，所以链路之间不会形成等待环。这三条规则是新版协议的防死锁依据，RTL spec 中每个 ready 条件都要能归到其中一条。

每条链路在一对端点之间按 FIFO 顺序传递，**不同链路之间不保证顺序**；所有跨链路竞争都由 2.6 节的压住规则和 3.3 节的写回规则处理。

source 直接由核号、client 类别（L1D、L1I、DMA）和请求编号（L1D 为 MSHR 编号，L1I 为 demand/预取 1 bit）构成；v1 的 N=1，MSHR 编号宽度为 0。L1D 的 Get 在途上界为 N、Put 为 1，身份随 MSHR/写回槽固定，不需要分配和回收 ID；L1I 在途上界为 2，DMA 为 1。L2 对每个核至多 1 个 probe 在途。

L2 发出 Data/Ack 后不等待安装确认：**产生 Data/Ack 的事务在 Data/Ack 交给 RSP↓ 输出缓冲时即可释放 set 保护**。L2 之后对同一行发起的新 probe 可能先于 Data 到达，由 L1 的 MSHR 压住规则处理。

带数据的消息一旦开始发送就连续发完，多拍行数据完整接收后才算该消息完成。若链路数据宽度取 256 bit，每条消息都是单拍，这条规则自然满足；宽度见第 7 节。

### 3.2 典型消息流

每条箭头表示一次消息完成。

| 场景 | 消息流与关键动作 |
| --- | --- |
| Load miss，L2 有最新数据且无需 probe | L1 **REQ GetS**；L2 查目录、登记副本并 **RSP↓ Data(S/E)**，Data 入输出缓冲即释放保护；L1 安装后原 Load 重查完成 |
| Store 命中本地 S，需要升级 | L1 **REQ GetM**；L2 对其他 sharer 发 **SNP Inv(sharer)**；各 L1 回 **RSP↑ InvAck**；L2 更新目录，若请求者仍是 sharer 回 **RSP↓ Ack**，否则回 **Data(E)**；L1 安装后 Store 重查写入 |
| 核 B 读核 A 的 UNIQUE 行 | B 发 **REQ GetS**；L2 向 A 发 **SNP Downgrade**；A 回 **RSP↑ DownAck[数据]**；L2 写入最新数据，目录变为 SHARED{A,B}；向 B 发 **RSP↓ Data(S)** |
| 核 B 要写核 A 的 UNIQUE 行 | B 发 **REQ GetM**；L2 向 A 发 **SNP Inv(owner)**；A 回 **RSP↑ InvAck[数据]**；L2 收回权限和最新数据，向 B 发 **RSP↓ Data(E)** |
| L1 自发逐出 | L1 发 **RSP↑ Put**（dirty 带数据）；L2 走完成路径写入数据、更新目录，回 **RSP↓ PutAck**；L1 释放写回槽 |
| I-refill / DMA 读 | client 发 **REQ Read**；行为 UNIQUE 时 L2 先对 owner 发 **SNP Downgrade** 并收 **DownAck**；返回 **RSP↓ ReadData**；不登记该读者 |
| DMA mask 写 | DMA 发 **REQ MaskWrite**；L2 对所有受跟踪副本发 **SNP Inv** 并收齐 **InvAck**；按 mask 更新最新行；回 **RSP↓ WriteAck**；不登记 DMA |

本地 E/M Load、E/M Store 没有 REQ 事务；E→M 在 L1 静默完成，L2 仍把该核记为 UNIQUE，因此后续读不得未经 probe 就相信 L2 旧数据。升级请求回 Ack 还是 Data 由 L2 按当时目录决定；若等待期间本核 S 副本已被 Inv(sharer) 撤销，L2 必然回 Data，L1 不会出现“有权限无数据”。

### 3.3 写回与正在进行的 probe 相遇

L2 正在 probe 行 X 时，被 probe 的核可能已经发出 X 的 Put。两者**各自独立完成，不合并**：

```text
核 B ── REQ：GetM X ─────────────────────────────→ L2（槽 0 保护 set X）
核 A ── RSP↑：Put X（dirty，先于 probe 发出）────→ L2
L2   ── SNP：Inv(owner) X ───────────────────────→ 核 A（写回槽命中，压住）
L2   ：Put 走完成路径写入数据、目录去掉 A
L2   ── RSP↓：PutAck X ──────────────────────────→ 核 A
核 A ── RSP↑：InvAck X（不带数据）───────────────→ L2
L2   ：槽 0 收齐答复，重读目录与数据，得到 A 交回的最新行
L2   ── RSP↓：Data(E) X ─────────────────────────→ 核 B
```

- Put 只会减少该核的权限，所以不申请慢槽，也不等待 X 的 set 保护；它只需等待主流水中同 set 已在途的任务离开 S2，避免目录读写冒险。
- L2 在发 PutAck 前已把 Put 写入阵列，而 A 在收到 PutAck 后才答复 probe，所以槽 0 收齐答复时最新数据一定已经在阵列中。
- 每个 probe 恰好对应一个答复，每个 Put 恰好对应一个 PutAck；不存在取消已发 probe、重复计数或误匹配新事务的记账问题。

代价是这种少见竞争多一次 RSP↓/RSP↑ 往返。

## 4. L2 流水线与并发组织

### 4.1 阵列与目录

L2/Home 共用 Tag、Directory、Data 阵列。目录跟踪 L1D 的副本，不跟踪 L1I 或 DMA；各核 PTW 经本核 L1D 读 PTE，形成的 D-cache 副本按普通 L1D 跟踪。

| 目录状态 | 含义与数据可信条件 |
| --- | --- |
| NONE | 无受跟踪 L1D 副本；有效 L2 行中的数据最新 |
| SHARED | 一个或多个 L1D S 副本，sharer 集合；L2 数据最新 |
| UNIQUE | 一个 L1D E/M owner；该核可静默修改，L2 数据可能过时 |

valid/tag 与上述状态分开；NONE 不是 invalid。dirty-to-memory 表示相对下级尚未写回的修改，也不能证明 UNIQUE owner 与 L2 数据一致。授予时即在目录中记录 sharer/owner；Data 尚在 RSP↓ 上时对同一行的新 probe 由 L1 的 MSHR 压住规则处理（2.6 节）。

本稿以目录覆盖全部有效 L1D 副本、L2 victim 复用前回收相关副本来描述逐出路径。L2 tag/数据的具体包含性组织和替换策略仍在第 7 节确认；采用何种组织都不能丢失仍有效 L1D 副本的目录责任。L1I 不受该包含关系约束。

### 4.2 快流水与 II

| 级 | 职责 |
| --- | --- |
| S0 | 在完成任务、慢槽任务、待重查请求和 eligible 新请求之间仲裁；新请求取得 set 保护并保存；发 Tag/Directory/Data 查询 |
| S1 | PA tag 比较、选数据、检查目录及来源；区分快路径、需要 probe、需要内存或暂缺资源 |
| S2 | 快事务和任务提交 Data/目录更新并形成响应；新的慢事务移交槽；资源阻塞请求转等待重查 |

S0/S1/S2 是本稿的结构切分，数据选择/更新的最终切级和响应缓冲在 RTL spec 明确。以三级加拍末响应寄存示例，T 接收、T+3 响应可见；这只是 L2 本地查找响应时间，L1↔L2 链路和 L1 安装另计。

II 是相邻查询启动的间隔，**不等于**一次命中延迟，更不等于慢事务占用时长。II=2 示例在 T、T+2、T+4 启动不同 set 的请求；某个慢槽等待内存几十拍，也不应把整个查询流水停几十拍。II=1 可以后续优化，先保证不同 set 命中和完成通路持续推进。

**主流水是 Tag/Directory/Data 的唯一访问者。**新请求的查询、Put 写入、慢槽的 victim 选择、probe 交回数据写入、内存 refill 安装和目录更新，全部作为请求或任务从 S0 进入主流水；慢槽和引擎本身不直接读写阵列。这样阵列不需要“快路径口”和“慢路径口”之间的单独仲裁，慢事务的各步自然穿插在快命中之间，重查、保护和错误处理只有一套逻辑。

S0 仲裁顺序为：**完成任务（Put 写入、慢槽各步骤）> 待重查请求 > 新请求**。每个慢槽的任务步数有限，Put 受每核 1 项上界约束，所以完成任务不会无限挤占新请求；新请求之间的公平性在 RTL spec 明确。代价是每个慢事务占用主流水若干次发射机会，慢事务本身少见，在 II=2 下可以接受。

Tag/Directory、Data 分开组织；读写口、bank 和宽数据 mux 的取舍服务于上述优先级，不强制沿用旧 `flowSRAM`，也不在没有端口/时序证据时承诺每拍一笔。

### 4.3 快慢路径分类

“快”指无需等待其他 L1 或内存，可由本地阵列和可用响应资源完成；快路径的 Data/Ack 交给 RSP↓ 输出缓冲时即释放保护。

| 请求与当前条件 | 动作 | 分类 |
| --- | --- | --- |
| L1D 取读副本，命中 NONE | 返回 Data(E)，登记为 UNIQUE | 快 |
| L1D 取读副本，命中 SHARED | 返回 S，加入 sharers | 快 |
| 取独占，命中 NONE，或 SHARED 仅剩请求核 | 更新为 UNIQUE，返回权限/必要数据 | 快 |
| clean Put | 目录移除对应副本并回 PutAck | 完成路径，不申请保护（3.3 节） |
| dirty Put | 写入整行、更新数据及目录并回 PutAck | 完成路径，不申请保护；占主流水一次写任务 |
| I-refill/DMA 读命中 NONE/SHARED | 返回最新数据，不登记读者 | 快 |
| DMA mask 写命中 NONE | mask 写、标脏、回写完成 | 可走快路径，取决于数据端口 |
| 读其他核 UNIQUE 行 | probe owner 降级，取得最新数据 | 慢 |
| 独占请求存在其他 sharer/owner | probe 失效/召回，收齐后授权限 | 慢 |
| DMA 写存在受跟踪副本 | 先 probe 失效/召回再 mask 写 | 慢 |
| L2 miss、victim 需回收或写回 | 逐出、内存访问、refill | 慢 |
| RSP↑ probe 答复、内存返回匹配已有事务 | 路由到对应慢槽，推进现有事务 | 完成路径，不当作新的 demand miss |

分类取决于操作、tag、目录和资源，不能单看目录状态。来源不合法、身份不匹配或重复所有权应报协议错误；不能伪装成普通成功。

### 4.4 按 set 保护与重查

保护从 S0 接受查询时建立，覆盖该 set 的在途查询、慢事务、victim 替换、阵列更新和未完成的权限交付。v1 相同 set 即使不同 line 也等待，不做同行合并或 set 内并行；不同 set 只受各自保护及共享端口/容量限制。

| 事务 | 解除 set 保护的边界 |
| --- | --- |
| 产生 Data/Ack 的事务 | 目录/Data 更新已在 S2 完成，Data/Ack 已交给 RSP↓ 输出缓冲；不等待 L1 安装 |
| I-refill/DMA Read、DMA MaskWrite | 一致性动作/阵列更新完成，ReadData/WriteAck 已交给 RSP↓ 输出缓冲 |
| Put | 不申请保护；只等待主流水中同 set 已在途的任务离开 S2 |
| 慢槽的各步骤任务、与其关联的 probe 答复和内存返回 | 使用槽已持有的保护推进，不独立申请或解除；槽完成最后一步时解除 |

输出缓冲独立持有结果后，响应责任已有归属，不再占用阵列和保护。RSP↓ 是无条件接收链路，client 只会短暂仲裁停顿，输出缓冲不会被长期占住；缓冲深度在 RTL spec 推导。

被保护或暂缺资源的请求解除阻塞后**从 S0 重查**，不使用旧 tag/目录/数据快照，也不照旧 victim 继续执行。已经握手的请求由 L2 保存并内部重查；尚未握手的请求由 client 保持，不要求 client 重发已经接受的请求。

仲裁在 eligible 请求中进行：一个 source 等被保护的 set，不应挡住其他 source 对不同 set 的请求。若 S1 发现需慢槽但两槽已满，应将该请求停放到有界等待记录、撤销其未产生副作用的查询占用，之后从 S0 重查；不能无限冻结共享 S2。等待记录/入口保持容量不足时反压相应入口，完成通路仍可运行。深度和同拍接收边界在 RTL spec 按最坏占用推导，不承诺无限接收。

### 4.5 两个慢槽、probe 引擎与内存引擎

两个慢槽保存有限的慢事务上下文，包括来源、目标/victim、所需权限动作、数据归属、等待事件和错误。它们是 v1 的有限事务并行资源，不表示开放 v2 的多 MSHR、同地址 waiter 合并或无限在途请求。

**慢槽只等待、发任务，不访问阵列。**槽持有本 set 的保护，等待的事件（probe 答复收齐、内存返回）满足后，向主流水 S0 发下一步任务，例如 victim 选择与目录读取、写入 probe 交回的数据、安装 refill、更新目录并形成 Data。每个任务在主流水中重新读取阵列，槽不保存 tag/目录/数据快照，因此 Put 等完成路径对目录的改动不会被旧快照覆盖。慢槽的控制本身不做流水：慢事务的时间几乎都花在等待上，吞吐由在途槽数决定，而不是每步处理速度。

槽数是参数：v1 四核配置为 2；8 核或压力结果表明槽满等待过多时可以增加，结构不变。

**probe 引擎**共享调度需要降级/失效的槽，保存各目标的待发和待响应进度，收齐答复后通知槽发下一步任务。v1 可一次协调一个槽的 probe，并保证每核至多一个 probe 在途；另一槽仍可等待内存。引擎不持有主流水，RSP↑ 的 probe 答复按核号和行地址直接路由到对应槽；答复携带的数据暂存在该核预留的 probe 答复缓冲中，由槽的写入任务取用。

**内存引擎支持两笔在途读**，经集群的 AXI4 `mem` 口发出，每个槽使用固定的 AXI ID。一个读未返回时能发第二个读，也不阻止不同 set 的快命中；每笔读有完整行接收与错误归属。

v1 的下游 LiteDRAM AXI 前端按顺序返回读数据，所以 v1 只要求**按序返回**：内存引擎用一个两项的在途读队列记录发出顺序，按队列头把返回路由到对应槽，同时用 RID 断言匹配。返回乱序是可选能力，不是 v1 的前提；若以后接入会乱序返回的下游，改为按 RID 路由，槽侧结构不变。读 ID 在该读完整返回前不能复用。

下游接入方式见第 5 节。L2 不按地址区域选择下游，所有可缓存区域都从同一个 `mem` 口发出，由 SoC 外壳分流；经外壳转到 Wishbone 的区域（ROM、SRAM）实际只有一笔在途，这只影响启动与 BIOS 阶段，不影响 DDR 上的双在途读。此处不同时承诺两笔在途写；写回深度、同地址读写排序和错误处理在后续 spec 确定。

逐出涉及脏 victim 时，先收回 L1D 最新数据并确保写回责任有安全归属，才复用位置。目标行与 victim 通常属于同 set，受该槽的保护覆盖。两个槽不能在回包到达时争用同一块无归属的数据缓冲；refill 安装作为完成任务在 S0 优先于新请求，持续快请求下也能前进。

两个槽均可服务 demand；不为 v1 预取分配槽。槽数与引擎在途能力是不同维度：两个槽可以一槽等 probe、一槽等内存，也可以两槽各等一笔内存读。

### 4.6 L1I 与 DMA

**L1I 不进目录。**I-refill 与 L1I 的顺序下一行预取（见 [`frontend-prediction-design.md`](frontend-prediction-design.md)）都是不登记的 Read 读者，每个 L1I 至多 2 笔 Read 在途：NONE/SHARED 可以返回 L2 最新数据；UNIQUE 必须照常 probe owner 降级并取得最新数据，随后返回指令行。L1I 不成为 owner/sharer，不接收目录驱动的失效，也不会因 L2 替换自动失效。

FENCE.I 只作废本地 I-cache；它不是跨核 I-cache 广播。刷新后的 I-refill 经上述一致性读取得最新值。已在途的本地 I-refill 不得在 FENCE.I 后重新安装被作废的旧结果；前端的排空/epoch 细节留给其 spec。修改代码的可见性还依赖核心按序处理前序 Store 和软件的跨核同步，不能仅靠这个本地作废动作替代它们。

**DMA v1 的唯一来源是 SD 卡**（LiteSDCard 的 DMA），经集群 `dma` 口进入 L2，带独立 source ID；PCIe 等其他 DMA 源是以后的工作。基本操作为整行 32 B 读和不跨行的带 byte mask 写；不实现预取、复杂 burst、多 MSHR 或 DMA 控制器。DMA 不进 sharer 集合、不持有独占副本。LiteSDCard 每次写 8 B，每次都是一笔 MaskWrite；按每笔约 15 拍的慢路径估算约 50 MB/s，高于 SD 卡本身的带宽。

DMA 读遇 UNIQUE 同样 probe 降级；DMA 写先使对应 L1D 副本失效/召回，以最新数据为基础按 mask 写入，未选择字节保留。整行全 mask 写可省去不必要的旧数据读取，但不能省略回收 L1D 权限。写 ack 表示一致性写入已生效，不要求同步落到 DRAM。DMA 写与 AMO 短窗口、LR/SC reservation 通过同一 probe/行次序协调；修改代码后的 I-cache 同步由软件/核心处理。

### 4.7 跨核 hit-under-miss 示例

以四核、II=2 的候选时序为例：X/Y/Z 分属三个 L2 set，Y 已命中且目录允许本地应答；这里的 hit 指 L2 命中；L1D 自身的 hit-under-miss 见 2.4 节，不经过 L2。

| 拍/区间 | 核 0：X miss | 核 1：Y hit | 核 2：Z miss | 核 3：X 同 set 请求 |
| --- | --- | --- | --- | --- |
| T | L2 S0，保护 set X | — | — | — |
| T+1 | S1 判 miss | — | — | — |
| T+2 | S2 移交槽 0 | L2 S0，保护 set Y | — | — |
| T+3 | 槽 0 请求内存读 | S1 判快路径 | — | — |
| T+4 | 等读 ID 0 | S2 形成 Data，入 RSP↓ 输出缓冲，释放 set Y | L2 S0，保护 set Z | 因 set X 保护而等待 |
| T+5 | 仍等内存 | RSP↓ Data 可见，核 1 安装 | S1 判 miss | 继续等待，不阻塞其他核 |
| T+6 起 | 仍等读 ID 0 | set Y 已可接受新请求 | 槽 1 可发读 ID 1 | 继续等待 |
| 内存返回期 | 按 ID 接收，槽 0 发安装任务，Data 发出后解除 set X | 可继续发不同 set 请求 | v1 按序返回时紧随 X 返回，各自独立完成 | X 解除保护后从 S0 重查 |

因此，X 未返回时 Y 已得到命中响应，Z 的第二笔内存读也可在途，两笔读的内存延迟重叠。若只有两核，只使用 X/Y 两条路径即可证明跨核 hit-under-miss；单核配置保留相同结构；v1 的单个 L1D 只有 1 个 MSHR，不会向 L2 同时发两笔 Get，L2 侧的这类并发只在多核下出现。

### 4.8 前进条件与验证重点

前进依赖：下游最终返回已接收请求、各 client 最终接收应答、AMO 短窗口有限结束、内部端口公平仲裁。在这些条件下，3.1 节的三条依赖纪律保证链路之间无等待环；L2 内部还不能出现 Put 等 set 保护而 probe 等 Put、L1 压住的 probe 等一笔依赖该 probe 的 Data、慢槽任务被新请求无限挤占、PTW 等 D-cache 而翻译等待占住 D-cache 等环形等待。

验证分三层推进，每层通过后再进入上一层；“Linux 能启动”只作最终验收，不作为发现 cache/协议错误的手段。

| 层次 | 对象 | 重点 |
| --- | --- | --- |
| 模块级（Chisel 测试） | L1D、L2 分别配协议/内存模型 | 流水与冲突规则、压住规则、保护与重查、下文三项中可在单模块内覆盖的部分 |
| 集群级 | 新 L1D × 1/2/4 + 新 L2 + AXI 内存模型 | 下文三项全部；AXI 读写延迟随机化与有限反压 |
| SoC 级（LiteX） | 第 5 节外壳 + 现有 `sim/litex` 仿真 | BIOS、多核启动、Linux、中断、DMA；两种主存配置都要跑 |

模块级与集群级以以下三项为主，细化用例再进入独立 testplan/RTL spec：

- **多核随机压力 + golden memory**：覆盖 1/2/4 核、混合 Load/Store、字节 mask、虚拟别名、同字与同 set 冲突、owner 转移、Put/probe 相遇、Data 在途时同一行的新 probe、升级中收到 sharer Inv、hit-under-miss 下其他行的命中与同行停住、victim way 锁定、Store miss 数据暂存与回放、两读在途、四条链路与 AXI 的有限反压和错误。golden memory 在每次合法的一致性生效顺序上检查数据，不能只比较最终 DRAM，因为最新值可能仍在脏 L1/L2。
- **MP / SB / LR-SC litmus**：包含无栅栏与按规定同步的版本，检查内存模型允许结果、aq/rl 顺序、SC 成功/失败与 reservation 失效。判据来自目标内存模型；当前保守顺序实现不必产生所有允许结果，也不能产生禁止结果。增加 AMO 与普通访存/DMA 的同行竞争，以及“年轻命中先于更老 Load miss 完成”的不同地址重排：无栅栏版本允许，FENCE 版本不得出现。
- **死锁 watchdog**：记录各源最老请求、set 保护、慢槽、probe 在途与 L1 压住原因、RSP↑ 缓冲占用和内存 ID；随机施加有限反压后检查持续前进。超时保存 seed 与等待链，区分测试环境永久反压和 DUT 自身闭环。

定向检查同时覆盖 S0 store→load 冲突（含虚别名、误报和写口受阻）、老异常 kill 年轻 Store、TLB miss 同拍 Y 保存、PTW 与 CPU 响应隔离、MSHR 压住 probe 直到回放完成、refill 错误上报 `hartFatal`、写回槽压住 probe 直到 PutAck，以及两个慢槽占用时其他 set 的可服务命中。计数器可记录同 set 等待、槽满、端口竞争、S0 冲突停顿、probe 压住、probe/内存等待和各类完成数，用于解释性能；本稿没有仿真、综合或 FPGA 性能结论。

## 5. 集群边界与 SoC 接入

### 5.1 原则

集群 RTL 的对外边界全部采用标准 AXI 与线信号，不出现 Wishbone 或 LiteX 专用接口；地址分流和协议桥接全部放在 SoC 外壳中。v1 的外壳继续使用 LiteX，沿用其外设、BIOS、LiteDRAM、设备树生成和 `sim/litex` 仿真流程。以后若改用其他 SoC 框架或流片，只重写外壳，集群 RTL 不变。

不同时更换 SoC 框架和内存系统：v1 期间内存系统整体重写，现有 LiteX 仿真与 Linux 启动流程是 SoC 级验证的基线。

### 5.2 集群对外接口

| 接口 | 类型 | 用途 | 在途 |
| --- | --- | --- | --- |
| `mem` | AXI4 主口，带 ID，INCR burst | L2 对全部可缓存区域的读、写回 | 两读在途，按序返回即可；写回在途数在 RTL spec 定 |
| `mmio` | AXI4-Lite 主口 | L1D 的 uncached/device 访问（CLINT、PLIC、LiteX 外设），见 2.10 节 | 全局一笔 |
| `dma` | AXI4 从口 | SD 卡 DMA 进入 L2，转换为 REQ Read / MaskWrite；PCIe 等其他 DMA 源是以后的工作 | 一笔 |
| `perf` | AXI4-Lite 从口 | 性能计数器与调试状态的只读访问，见 [`observability-design.md`](observability-design.md) | 一笔 |
| 中断与时间 | 线信号 | 每核 msip、mtip、meip、seip，以及 time | — |

`mem` 口数据宽度 v1 取 64 bit，一行 32 B 用 4 拍 INCR burst 传送，与现有 ROM/SRAM 的总线宽度一致；LiteDRAM 内部更宽，由 LiteX 自动插入宽度转换。是否改为 256 bit 一拍一行见第 7 节。

PLIC 与 CLINT 继续使用现有的 Breeze 自有实现（`litex_wrapper/flow/rtl/FlowPlic.sv`、`FlowClint.sv`），作为 LiteX 外设挂在 Wishbone 上，经 `mmio` 口访问；它们与本次 cache 重写无关，不做修改。

### 5.3 LiteX 外壳分流

| 集群接口与地址范围 | 外壳中的路径 |
| --- | --- |
| `mem`，`main_ram`（0x8000_0000 起） | 有 LiteDRAM 时经 `cpu.memory_buses` 直接接 LiteDRAM AXI 前端，不经 Wishbone 主总线；这是 DDR 双在途读的唯一路径 |
| `mem`，`boot_rom`、`linux_boot_rom`、`sram` | 经 LiteX AXI→Wishbone 桥进入主总线；复位后的取指和 BIOS 走这里 |
| `mmio` | 经 AXI-Lite→Wishbone 桥进入主总线 |
| `perf` | 作为 LiteX 主总线上的一个从设备（Wishbone→AXI-Lite 桥），CPU 与 JTAG 都可访问 |
| `dma` | LiteSDCard 的 64 bit Wishbone DMA master 在外壳中经 Wishbone→AXI 桥接到集群的 AXI4 从口；以后接入 PCIe 时同样接在这里 |

外壳必须同时支持现有两种主存配置：

- **LiteDRAM（含仿真 DDR 模型）**：LiteX 只在加入 SDRAM 时调用 CPU 的 `add_memory_buses`，外壳在这一钩子中创建 DDR 直连路径，其余地址走主总线。
- **LiteX 片上集成 RAM**（如 `sim/litex/breeze_sim.py` 的 `integrated_main_ram_size`）：不调用该钩子，`mem` 口的全部地址经 AXI→Wishbone 桥进入主总线，此时只有一笔在途，仅用于功能仿真。

Linux 级仿真使用 LiteDRAM 模型，双在途读在 SoC 级仿真中可见。

### 5.4 过渡

旧的 `BreezeMulticoreClusterWishbone` 与对应外壳在新集群跑通 Linux 之前保留，作为对照参考；新集群使用独立的顶层与 CPU 类型名，两者不共享 RTL 目录。

### 5.5 KCU105 v1 参数

目标是在 KCU105（KU040）上放下 4 核、L2 与 SD 卡，系统时钟 100 MHz（带 SD 卡的构建要求 100 MHz）。参数参考 Rocket 的默认配置。

| 项目 | Rocket 默认 | Breeze v1 | 说明 |
| --- | --- | --- | --- |
| 核数 | — | 4（支持 1/2/4） | — |
| L1I / L1D | 16 KiB、4 路、64 B 行 | 16 KiB、4 路、32 B 行 | 每路 4 KiB = 页大小，VIPT 无别名 |
| L1D MSHR | 1 | 1 | hit-under-miss，2.4 节 |
| ITLB / DTLB | 32 / 32 | 按 MMU spec | MMU 已冻结 |
| L2 | 512 KiB、8 路 | 4 核 256 KiB、8 路、2 个慢槽 | 1/2 核为 64/128 KiB |
| 内存在途读 | — | 2，按序返回 | 4.5 节 |
| `mem` 数据宽度 | — | 64 bit，4 拍一行 | 第 7 节保留 256 bit 选项 |
| 主频 | — | 100 MHz | — |

前端与后端的参数见 [`frontend-prediction-design.md`](frontend-prediction-design.md) 与 [`backend-pipeline-design.md`](backend-pipeline-design.md)。

**资源估算**（以 `fpga/kcu105/reports` 中单核 breeze-tiny 报告为基线：核心 34.0k LUT、L1D 6.4k、L2 7.4k，整机 57.4k，含 tandem 与 ILA）：4 核加 SoC 与 SD 卡约 182k / 242k LUT（75%）；本轮新增功能每核约 +5k LUT，合计到约 83%；乘法器改用 DSP 后每核约省 6k LUT，回到约 73%。块 RAM 主要由 L1、L2 与 ILA 占用，余量充足。以上为估算，以 Vivado 实现结果为准。

## 6. 推到 v2 的项目

- **L2 预取器**：v1 没有 tracker、候选队列、预取请求或占槽策略；v2 再依据 demand 基线决定训练、去重和节流。
- **miss-under-miss 与更深并发**：L1D MSHR 数 N≥2、同行合并与多 waiter、更多 L2 慢槽。v1 已有单 MSHR 的 hit-under-miss，结构按 N 设计；v1 的两个慢事务槽和两读在途仍按本文保留。
- **进一步吞吐优化**：II=1、更多 bank、set 内细粒度保护和更宽入口，依据 v1 命中延迟及压力结果决定；不作为 v1 结构闭合的前提。

## 7. 待拍板的点

下列项目需要后续逐项审阅；它们不重新打开前文已同意机制。

| 项目 | 已确定的边界 | 待拍板内容 |
| --- | --- | --- |
| L2 II 与级划分 | hit-under-miss、端到端命中延迟优先；II=2 可接受 | 是否以 II=2 固定 v1；读/更新端口与 S0/S1/S2 最终切分 |
| 下游总线 | 集群边界为 AXI4 `mem` + AXI4-Lite `mmio` + 可选 AXI4 `dma`；LiteX 外壳分流，DDR 经 `memory_buses` 直连；两读在途、按序返回 | `mem` 数据宽度保持 64 bit 还是改为 256 bit；写回在途数与同地址读写排序；AXI 错误响应到 L1 的映射 |
| 几何与替换 | 1/2/4 核；L1I/L1D 16 KiB/4 路/32 B；L2 为核数×64 KiB、8 路、32 B 行 | L2 bank 数、替换策略、L1D 目录驻留与 L2 数据包含性组织 |
| LR/SC | 不带 aq/rl 的 LR/SC 进流水；LR 按 GetM 取独占；SC 不 miss；80 拍前进窗口 | reservation 精确粒度与同拍失效仲裁 |
| 容量与完成资源 | 一个 L1 MSHR、一个 L1 写回槽、两个 L2 慢槽（参数化）、两读在途；L2 每核预留 1 项写回与 1 项 probe 答复行缓冲；完成任务优先于新请求 | RSP↓ 输出缓冲、等待重查记录深度，L1I/DMA 在途上界与 source 范围；后续 spec 按拍级容量推导 |
| MSHR 扩展 | v1 为 1 个 MSHR 的 hit-under-miss，结构按 N 设计 | N=2 的时机；N=2 时 L2 慢槽是否增至 4、内存在途读是否随之增加 |
| refill 错误上报 | 已提交请求的 refill 错误不可精确交付；v1 上报 `hartFatal` | 是否改为非精确总线错误中断，以及记录出错地址的 CSR |
| 链路宽度与物理复用 | 四条逻辑链路与 3.1 节依赖纪律不变 | 数据宽度是否取 256 bit 使每条消息单拍；是否把上下行各合并为一条按类型预留缓冲、永不反压的物理链路 |
| 系统排序 | MMU 合同不变；FENCE.I 本地作废；MMIO 阻塞且等 MSHR/pending-store 清空；FENCE 等 MSHR/pending-store 清空 | MMU 所称 store buffer 排空如何映射到 pending-store/MSHR 的细节 |

下一份 RTL spec 再固定接口字段、握手保持、寄存器/状态机、同拍优先级、数组端口、队列深度、错误恢复和断言。本稿止于上述微架构与待决边界。
