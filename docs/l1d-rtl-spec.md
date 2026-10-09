# Breeze v1 L1D RTL spec

状态：Claude 编写，供实现使用（2026-10-06）。设计依据为 [`dcache-pipeline-design.md`](dcache-pipeline-design.md) 第 2 节，输入摘录与已定决定见 [`l1d-spec-inputs.md`](l1d-spec-inputs.md)。协议编码以 [`coherence-l2-rtl-spec.md`](coherence-l2-rtl-spec.md) 第 1 节为准；MMU 合同以 [`breeze-mmu-rtl-spec.md`](breeze-mmu-rtl-spec.md) 为准。

修订：2026-10-06 B01 裁定（[`tasks/V1-BE-B01-ruling.md`](tasks/V1-BE-B01-ruling.md)）——回放的迟到数据不在 S2 等 `late.ready`，改由 MSHR 的 LATE 状态保持（6.2 节）；第 12 节活性论证随之更新。`L1DCoreIO` 字段不变。

实现修订（2026-10-06，普通 Load/Store 第一批）：CPU S1/S2 在后端 `s2Hold` 期间保持；另设两级内部完成流水共享 S0 与阵列端口，服务 probe、install、写回读、MSHR replay、PTW 和 CPU 重查。否则年轻 S2 等 MSHR 时会阻止同一 MSHR 经 S0 安装/回放。内部 S2 优先使用判定与写端口，CPU 本地冲突时保持；普通 CPU hit 的 S0/S1/S2 拍关系不变。资源、翻译和快照等待均由保持的 CPU 请求拥有，内部结果不占用后端 WB 请求槽。

实现修订（SOC-3d 第一批，2026-10-09）：PMP/PMA 按 SOC-3 M1 保留在 CPU/internal 各自 S1，结果寄存进 S2。CSRFile 将 PMP 区间及边界高位前驱与合法 CSR 新值同沿寄存；S0 将末字节低 7 位及跨 128 B 块进位保存进 S1。生产集群使用可选 TLB 候选 PA，页权限与物理权限并行计算；候选随 fresh/held 快照保存，S2 的 permission snapshot 保存对应完整候选地址，事务 paddr/异常仍取原 TLB resp。fault/miss 候选不授权副作用。上下文事件、PTW 刷新、Recheck 与独立旧算法 shadow 检查保持。S0 各来源独立算资格后按原优先级一热仲裁，refill/probe/WbRead 授权不经 CPU kill/TLB 资格选择；端口、AMO、所有权与快照互锁不变。tag 写掩码直接使用一热 way。

实现修订（SOC-3d 控制链，2026-10-09）：CSRFile 按 WB 的 CSR 地址、写数据和现有锁定/WARL 状态计算候选 PMP 配置、地址及区间，commit/write/trap 许可只控制原始 CSR 与区间寄存器的同沿更新，TOR 使用候选前驱地址。L1D S2 按内部刷新、内部服务、回放、CPU 占用、原子等待、FENCE、快照重查、权限异常、MMIO、SC 和普通 cache lookup 的原优先级限定局部条件，直接生成 Done/Mshr/Exc、等待和副作用资格，替代共享 outcome 编码后再解码。普通命中、PS 容量与 miss 分配容量分别判断；CPU/internal 流水级数、响应/提交拍数、kill 与资源所有权规则保持。新增 4 项测试及所选 39 项定向功能用例通过，执行版本、覆盖与证据见 [`tasks/SOC-3d-timing-batch-report.md`](tasks/SOC-3d-timing-batch-report.md) 文末；这不是完整回归或功能等价证明。第一批 Vivado 不覆盖此次修改，尚无此候选的物理时序证据。

实现修订（2026-10-07，单核原子访存）：第 8 节 LR/SC、AMO、aq/rl 已补入 L1D。LR/AMO miss 保持 CPU S2 到内部回放，LR 以 `resp` 完成；AMO 在回放或命中拍把 RMW 数据交给 PS，次拍写入时返回旧值。AMO/rl 在 S0 以 `ready` 等待老请求排空，AMO/aq 在接受后关年轻入口；未增加 CPU 接口或流水级。实际仿真版本、覆盖与边界见 [`tasks/MEM-single-core-atomics-report.md`](tasks/MEM-single-core-atomics-report.md)，规格目标不自动等于验证通过。

实现修订（SOC-3d 合并控制链，2026-10-09 用户授权）：tag 存储按最多 8 个 set 分组、逐 way 使用独立 SyncReadMem；S0 同拍选择分组并发起读，S1 用同沿寄存的 bank 选择返回，不增加流水拍。各写来源提前生成 idx/way/data，按 install > probe > allocation > PS 优先级生成独热许可，并与各分组/way 译码直接汇合。每个局部存储仅一个无掩码写口，初始化和运行更新共用；初始化仍逐 set 写全 way，data 不初始化。容量、查询/更新顺序、快照失效、同拍 kill、PS/MSHR/原子与 PTW 所有权保持。验证状态见 `tasks/SOC-3d-combined-control-report.md`。

## 0. 范围与参数

### 0.1 文件

| 文件 | 内容 |
| --- | --- |
| `design/src/main/scala/config/config.scala` | `BreezeClusterConfig`：全部几何参数的唯一入口（0.2 节） |
| `design/src/main/scala/l1d/L1DParams.scala` | 由 `BreezeClusterConfig` 推导的 L1D 常量 |
| `design/src/main/scala/l1d/L1DBundles.scala` | 后端接口、PTW 入口、计数器事件 Bundle |
| `design/src/main/scala/l1d/L1DCache.scala` | 顶层：S0–S2、pending-store、阵列、PMP/PMA |
| `design/src/main/scala/l1d/L1DMiss.scala` | MSHR、写回槽、REQ/RSP↑ 发送、RSP↓ 接收 |
| `design/src/main/scala/l1d/L1DProbe.scala` | SNP 接收与答复 |
| `design/src/main/scala/l1d/L1DMmio.scala` | 阻塞 MMIO 状态机（AXI4-Lite） |

复用：`mmu/sv39/TreePlru.scala`、`platform/PMAChecker.scala`（增加 `amoOk`、`rsrvOk`）、`mmu/BreezePmpChecker.scala`、`cache/BreezeAmoAlu.scala`。旧 `cache/BreezeDCache.scala` 删除。

### 0.2 参数：唯一入口 `BreezeClusterConfig`

全部几何与宽度参数只在 `BreezeClusterConfig` 中声明，`L1DParams`、`CoherenceParams`、L1I、L2 只做推导，模块内不得出现几何常量。v1 改造 `BreezeClusterConfig`：

| 字段 | 默认 | 约束（`require`） |
| --- | --- | --- |
| `nCores` | 4（预设 single=1、dual=2、small=4） | 1 ≤ nCores ≤ 8 |
| `lineBytes` | 32 | v1 锁定 32（链路单拍 256 bit） |
| `l1Sets` | 128 | 2 的幂；`l1Sets × lineBytes ≤ 4096`（VIPT 无别名） |
| `l1dWays` | 4 | 2 的幂 |
| `l1iWays` | 4 | 2 的幂；可与 `l1dWays` 不同 |
| `l1dMshrs` | 1 | v1 锁定 1；结构按 N 写 |
| `l2Ways` | 8 | 2 的幂 |
| `l2BytesPerCore` | 65536 | 2 的幂；`l2Sets` 为 2 的幂 |
| `l2Slots` | 2 | ≥ 1 |
| `paddrBits` | 32 | v1 锁定 32 |

删除旧字段与类：`DefaultDCacheConfig`、`DefaultICacheConfig` 的几何部分、`BreezeCoreConfig.dcache*`、`L2CacheGeometry` 的 `numHarts × 2 × L1D` 公式、`txnIdWidth`；`numHarts` 改名 `nCores`。L1I/L1D 同路数的 `require` 删除。

L1D 推导（`L1DParams(cfg)`）：

| 名称 | 公式 | 默认值 |
| --- | --- | --- |
| `ways` | `l1dWays` | 4 |
| `sets` | `l1Sets` | 128 |
| `offBits` | `log2(lineBytes)` | 5 |
| `idxBits` | `log2(sets)` | 7 |
| `tagBits` | `paddrBits − idxBits − offBits` | 20 |
| `wordsPerLine` | `lineBytes / 8` | 4 |
| `capacityBytes` | `sets × ways × lineBytes` | 16 KiB |
| `lineAddrBits` | `paddrBits − offBits` | 27 |
| `nMshrs` | `l1dMshrs` | 1 |
| `plruBits` | `ways − 1` | 3 |

S0 冲突比较位 `[11:3]` 固定取页内 8 B 字地址，与几何无关。

### 0.3 非默认配置冒烟

为防参数化失效，测试计划包含以下三组非默认配置的冒烟测试（详见第 13 节）：L2 4 路（`l2Ways = 4`）、L1D 2 路（`l1dWays = 2`，L1I 保持 4 路）、单核（`nCores = 1`）。

## 1. 接口

### 1.1 后端（`L1DCoreIO`）

| 信号 | 方向 | 内容 |
| --- | --- | --- |
| `req` | 入，Decoupled | EX 拍：`op`（Load/Store/LR/SC/AMO/Fence）、`vaddr` 64、`size` 2、`signed`、`amoFunc`、`aq`、`rl`、`wdata` 64、`rd`{isFp, idx 5}、`isFlw`（NaN-boxing） |
| `req.ready` | 出 | 0 = S0 冲突、入口关闭或级保持；同拍反压 EX |
| `s1Kill` | 入 | 后端 MEM 级作废 S1 请求；L1D 当拍作废 S1，不依赖当拍 `tlb.resp.valid`（K6） |
| `s2Kill` | 入 | WB 发起的 kill（更老异常、中断、xRET）；作废 S2 与 S1 |
| `resp` | 出，Valid | S2 判定：`kind`（Done / Mshr / Exc）、`data` 64、`excCause`、`tval`；每请求恰一次 |
| `s2Hold` | 出 | S2 暂不判定，后端停在 WB |
| `late` | 出，Decoupled | 迟到数据：`rd`、`data` 64、`error`；后端 ready=0 时由 MSHR 保持（不占 S2，6.2 节） |
| `drained` | 出 | MSHR 与 pending-store 均空（FENCE.I、SFENCE.VMA 用） |
| `mmioBusy` | 出 | MMIO 已发出未退休（中断/调试等待） |
| `trapClearRsv` | 入 | 本 hart 陷入，清 reservation |
| `csr` | 入 | `BreezeMmuContext`（PMP、有效特权计算） |

### 1.2 MMU

- `tlb: TlbPortIO`（发出方）：`req.valid` = S0 有 CPU 请求；`cmd` = Load（Load、LR）/ Store（Store、SC、AMO）；`kill` = `s1Kill || s2Kill`。
- `ptw: Flipped(PtwMemIO)`：PTW 入口，见第 7 节。
- `idle` 不经 L1D。

### 1.3 一致性链路

`req: Decoupled(CoherenceReq)`、`rspUp: Decoupled(CoherenceRspUp)`、`snp: Flipped(Decoupled(CoherenceSnp))`、`rspDown: Flipped(Decoupled(CoherenceRspDown))`，Bundle 来自 `CoherenceBundles`。`rspDown.ready` 恒 1；`snp.ready` 归类见第 12 节。

### 1.4 MMIO 与事件

- `mmio: AXI4-Lite 主口`，64 bit 数据。
- `events: Output(L1DEvents)`：l1d-spec-inputs 第 12 节全部事件，每拍脉冲或电平。

## 2. 阵列

| 阵列 | 组织 | 端口 | 内容 |
| --- | --- | --- | --- |
| `tag` | `SyncReadMem(sets, Vec(ways, TagEntry))`，按 way 掩码写 | 1R1W（U1） | `state` 2（I/S/E/M）、`tag` `tagBits` |
| `data` | 每路一块：`ways` 个 `SyncReadMem(sets × wordsPerLine, Vec(8, UInt(8)))`，按字节掩码写 | 1R1W | 地址 `{set, word}`；S0 用同一地址读各路同一 8 B 字（S1 预选） |
| `plru` | 寄存器 `Vec(sets, UInt(plruBits))` | — | `TreePlru`；选 victim 时跳过锁定 way |

- **整行操作**（refill 安装、写回读、probe 读）每拍访问一个 8 B 字，连续占用 S0 `wordsPerLine` 拍（字计数器 0…`wordsPerLine−1`），期间 S0 不接收其他来源。
- 取舍：不按字分 bank。默认几何下每路深 512 × 64 bit，正好一块 BRAM36，每核 4 块；按字分 4 个 bank 时每块只用 128 深，每核约 16 块，4 核多出约 48 块。refill 安装多 3 拍，只在 miss 时发生，代价可忽略；旧 4 核正是因资源装不进 XCKU040。
- 复位：初始化状态机逐 set 写 `tag` 全 I，共 `sets` 拍；期间 `req.ready=0`、`snp.ready=0`。`data` 不初始化。
- 同拍同地址读写的返回值不被依赖：S0 冲突检查（第 4 节）与快照失效（5.3 节）覆盖全部读写重叠情况。

## 3. 流水级

| 级 | 寄存器 | 动作 |
| --- | --- | --- |
| S0 | 无（组合仲裁，第 6 节） | 选中一个来源；读 `tag(idx)`、各路 `data({idx, word})`；CPU 请求同拍发 `tlb.req`（`req.ready` 与 `tlb.req.ready` 同为 1 才 fire） |
| S1 | `s1`：valid、src、req、idx、word、age | 收 `tlb.resp`（CPU）或携带 PA（内部来源）；寄存 tag 各路、data 各路字、PA、翻译异常 |
| S2 | `s2`：同上 + PA、`tagVec`、`dataVec`、`snapInvalid` | tag 比较、命中路、格式化、PMP/PMA、MSHR 同行、判定（第 5 节） |
| PS | `ps`：valid、idx、word、way、mask、data | pending-store：写 `data`；E→M 写 `tag` |

- S1/S2 保持：S2 暂不判定时 S2 保持，S1 若有效则保持，S0 不 fire。
- 上述 S0 不 fire 指 CPU 新入口；内部完成流水可以访问共享 S0。非整行内部请求从发射到内部 S2 完成期间独占该完成流水；整行操作只允许同一操作的各 beat 在其中重叠，直到最后一拍离开内部 S2 才解除 S0 所有权。内部来源的接纳保证其结果容量，不允许因等待资源而堵住内部 S2。
- S1 的 TLB miss：X 进入 CPU S2 翻译等待，可能已 fire 的年轻 Y 保留在 CPU S1（第 7 节），次拍起关闭 CPU S0。
- `s1Kill`：当拍清 S1；若同拍 S0 fire 的请求更年轻，S0 请求同样丢弃（`req.ready` 置 0 当拍不接收）。
- `s2Kill`：当拍清 CPU S2（若尚未判定）与 CPU S1，翻译等待及 CPU 重查一并清除；不撤销 PS（已判定为更老）、MSHR、写回槽、probe、已接受 PTW。

## 4. S0 冲突检查

- 比较对象：S1、S2 中有效的 Store 类请求（Store、SC、AMO 写阶段）用其 `vaddr[11:3]`；PS 有效时用其 `pa[11:3]`。
- 被检查者：CPU Load/LR、PTW 读（用 `paddr[11:3]`）、MSHR 回放、重查请求。命中任一 → 该来源本拍不进 S0，不发 `tlb.req`、不读阵列。
- 写入拍仍算冲突：PS 写入当拍比较有效，下一拍解除。PS 写口受阻时持续冲突。
- `s0ConflictStall` 同拍反压 `req.ready`。时序退路（不在 v1 默认实现）：比较结果寄存一拍，S1 kill 该 Load 并从 S0 重发。

## 5. S2 判定

### 5.1 检查顺序

1. 快照失效（`snapInvalid`）→ 作废，置为重查（S2 请求回到 S0 候选，年轻 S1 同样回 S0）。
2. 翻译异常（S1 带来的 pageFault/accessFault）→ Exc。
3. PA ≥ 2^`paddrBits` 或 PMA 不允许、PMP 不允许 → Exc（access fault）。
4. 原子性 PMA：LR 且 `!rsrvOk` → load access fault (5)；SC 且 `!rsrvOk`、AMO 且 `!amoOk` → store/AMO access fault (7)。
5. PMA `device`（不可缓存）：LR → 5；SC/AMO → 7；Load/Store → 转 MMIO（第 9 节）。
6. 按下表处理可缓存请求。

异常 cause：Load/LR 用 5（access）/13（page）；Store/SC/AMO 用 7/15。`tval` = 请求 vaddr。

### 5.2 可缓存请求

记 `hit` = 某路 tag 匹配且 state ≠ I 且该 way 未锁定；`wOk` = state ∈ {E, M}。

| 请求 | 条件 | 判定 |
| --- | --- | --- |
| 任意 | 与 MSHR 同行，或与写回槽同行 | s2Hold；MSHR/写回槽释放后重查 |
| Load | hit | Done，带格式化数据 |
| Load | miss | MSHR 空且（victim 无效或写回槽空）→ 分配，GetS，Mshr；否则 s2Hold |
| Store | hit 且 wOk | Done；入 PS（PS 被占且写口受阻时 s2Hold） |
| Store | hit S 或 miss | 分配 MSHR，GetM，Mshr（条件同 Load） |
| LR（非 aq/rl） | hit 且 wOk | Done，建 reservation |
| LR | 其他 | 分配 MSHR（GetM），不判定，回放完成时 Done |
| SC（非 aq/rl） | reservation 有效且 `pa` 行地址匹配 | Done，data=0，入 PS；清 reservation |
| SC | 否则 | Done，data=1，不写；清 reservation |
| AMO / aq-rl LR/SC | — | 第 8 节独占路径 |
| PTW 读 | hit | 返回 `ptw.resp`（不经后端） |
| PTW 读 | miss | 分配 MSHR（GetS），等回放 |

- “分配”同拍：记 MSHR、选 victim（PLRU 跳过锁定 way；优先无效 way）；victim 有效 → 写回槽记地址、RSP↑ 发 Put（M 带整行，从 `data` 读出，见 6.3 节），victim 的 tag 置 I 并锁定。写升级不选 victim，锁原 way，原 S 状态保持（可被 Inv 撤销）。
- 判定为 Done/Mshr 时：PLRU 更新为命中路（miss 时不更新，安装时更新）。SOC-3d 后续授权（2026-10-09）：每 set 使用本地 PLRU 状态并行算各 way 的 touch 候选，命中独热/安装 way 选择与最终更新许可分开；安装使用自身 idx/way，CPU 更新保留同拍 s2Kill 与 installLast 优先级，不增加更新延迟。

### 5.3 快照失效

S1/S2 请求寄存 `idx`。下列事件发生在其阵列读之后、S2 判定之前，且**同 set** → 置 `snapInvalid`：

- refill 安装写 tag/data；
- probe 修改 tag（I 或 S）；
- 写回 victim 置 I。

PS 写 data 不触发（由第 4 节保证）。PS 的 E→M tag 写触发同 set 快照失效，防止后续 victim 查询把刚写脏的 E 行误当成 clean。发生在 S2 判定同拍的 tag/refill 修改同样阻止使用旧快照。

实现修订（2026-10-07）：CPU S2 的资源等待设置 `needsRecheck`，包括同 MSHR/写回行、miss 等 MSHR 空闲，以及命中 S 的 Store 升级等 MSHR 空闲。后一种虽然 hit，仍依赖 GetM，不能漏作等待项。标记后年轻 S1 Store 已随 CPU 流水保持，不再阻止 probe 的有限拍本地前进；probe 完成、旧 miss 释放资源后重查当前 tag/state，再决定 hit、升级或重新获取。S2 暂停期间不提交 store，不增加额外请求、缓存或流水级。

有意偏离 [`l1d-spec-inputs.md`](l1d-spec-inputs.md) 第 14 节的“同 set 同 way”：miss 请求没有命中路可比，而 refill 恰好装入它要的行时它应由 miss 变为 hit，按 way 比较需为 miss 另加规则。按 set 比较对命中与缺失都不漏；代价是同 set 无关请求偶尔多一次重查（约 3 拍），refill 与 probe 低频，可忽略。

### 5.4 Load 格式化

按 `pa[2:0]` 与 `size` 从命中路 8 B 字移位、符号/零扩展；`isFlw` 时高 32 位填 1（NaN-boxing）。MSHR 回放与 MMIO 读复用同一逻辑。

## 6. MSHR、写回槽、refill 与 S0 仲裁

### 6.1 S0 仲裁（高到低）

1. 复位初始化。
2. probe 处理（第 10 节）、refill 安装、写回读（6.3 节）。三者互斥由各自状态保证（每类至多 1 笔），同拍冲突按此顺序。
3. MSHR 回放。
4. PTW 读。
5. 重查（快照失效、s2Hold 解除、翻译等待恢复），按年龄。
6. CPU 新请求（`req`）。

整行操作（2 中三者）一旦开始独占 S0 直到最后一拍，不被更高优先级打断。内部来源（1–3）不查 dTLB，携带 PA；CPU 请求（5、6）查 dTLB。除 1–2 外，全部经第 4 节冲突检查。

### 6.2 MSHR（`nMshrs` 项，v1 = 1）

| 字段 | 说明 |
| --- | --- |
| `valid`、`state` | IDLE → (WB_READ) → SEND → WAIT → INSTALL → REPLAY → (LATE) → IDLE |
| `lineAddr`、`isGetM`、`way`、`upgrade` | 行 PA、请求类型、目标 way、是否写升级 |
| `src` | CPU / PTW |
| `op`、`rd`、`size`、`signed`、`isFlw`、`word`、`mask`、`wdata` | 回放所需原请求 |
| `isLr`、`ptwKilled` | LR 回放后建 reservation；PTW 请求已 kill 标记 |
| `refill`、`err`、`grantE` | 256 bit 缓冲、错误位、得 E（DataE/AckE）或 S（DataS） |
| `lateData` | 64 bit：回放 S2 格式化后的 Load 数据，LATE 状态下驱动 `late.data` |

- SEND：victim 需写回时等写回读完成（6.3）；REQ `op` = GetS/GetM、`addr` = `lineAddr`、`id` = 0；fire 后 WAIT。
- WAIT：RSP↓ DataS/DataE/AckE 到达 → 存入 `refill`（AckE 不带数据，安装时保留原数据）→ INSTALL。`error=1` → INSTALL 执行一拍 tag-only 清理（tag 置 I、不写 data），解锁后 REPLAY 交付错误；不把错误 refill 安装为有效行。
- INSTALL：S0 内部来源，整行操作：`wordsPerLine` 拍逐字写 `way` 的 data（AckE 跳过 data，只用 1 拍）；最后一拍写 tag `{state = grantE ? E : S, tag}`、PLRU touch、解锁 way、置同 set 在途快照失效。way 在安装期间仍锁定，不会被命中。
- REPLAY：以 PA 从 S0 进入（经冲突检查），S2 执行原请求：Load → 当拍以 S2 结果直接驱动 `late`（valid、`rd`、格式化数据、`error`），`late.ready=1` 则当拍 fire 并 → IDLE；`late.ready=0` 则数据存入 `lateData` → LATE，**S2 不保持、不发 `s2Hold`**；Store → PS；LR → `resp` Done 并建 reservation；PTW → `ptw.resp`（`ptwKilled` 时丢弃）。错误：Load 送 `late.error=1`（同上，可进 LATE）；Store 丢弃；PTW → `ptw.resp.accessFault=1`。非 Load 完成 → IDLE。
- LATE：`late.valid=1`，字段取自 MSHR（`rd`、`lateData`、`err`），fire → IDLE。此时行已安装、回放已完成，不压住 probe（10.2 节以“回放完成”为准）；MSHR 未回 IDLE，新 miss 按“MSHR 满”s2Hold。后端写口饥饿保护保证 LATE 至多约 6 拍（[`backend-pipeline-design.md`](backend-pipeline-design.md) 第 6 节）。
- 回放时行可能已被 probe 收走（回放前 Inv）：回放不再 miss——第 10 节规则压住比回放更晚的 probe 直到回放完成，因此回放必命中（断言）。

### 6.3 写回槽

字段：`valid`、`lineAddr`、`hasData`、`data`、`state`（READ → SEND → WAIT_ACK）。

- 分配：S2 分配 MSHR 且 victim 有效时置 `valid`，记 victim 地址与 `hasData = (state == M)`。
- READ：`hasData` 时 S0 内部整行操作，`wordsPerLine` 拍逐字读 victim way，S2 逐字存入 `data`；无数据时跳过。victim way 已锁定，读期间不会被写。victim 的 tag 在分配拍已写 I（5.2）；data 在 refill 安装前不被覆盖，因 MSHR 在 READ 完成前不进入 SEND。
- SEND：RSP↑ `Put`、`hasData`、`addr`、`data`；fire → WAIT_ACK。
- WAIT_ACK：RSP↓ PutAck → `valid=0`。
- 写回槽有效时：同行 S2 请求 s2Hold；不得发同行 Get（由 s2Hold 保证）；需 Put 的新 MSHR 分配 s2Hold。

### 6.4 RSP↑ 发送仲裁

probe 答复 > Put（两者各至多 1 笔待发；probe 答复先发不影响正确性，L2 分开缓冲）。

### 6.5 RSP↓ 接收

`ready` 恒 1。`op` 0–2 → MSHR（断言 MSHR 在 WAIT）；3 → 写回槽（断言 WAIT_ACK）；4、5 → 断言错误。

## 7. TLB miss 与 PTW 入口

### 7.1 翻译等待

- 两项等待容量直接复用保持的 CPU S2/S1：X（S1 miss）进入 CPU S2 并标记 `translationMiss`；Y（同拍 S0 已 fire、被 `dropS1Next` 丢弃的年轻请求）保留在 CPU S1，显式保留其 TLB Valid 是否出现。无需再复制两份 CPU 请求到 FIFO。
- X 到达 CPU S2 后关闭 CPU 新入口（`req.ready=0`）；PS、MSHR、写回槽、probe、PTW 经内部完成流水继续。
- `tlb.req.ready` 恢复后 X 经内部 S0 重查 dTLB，结果回填到保持的 CPU S2，然后作正常判定；X 完成后 Y 才进入 CPU S2。Y 没有 TLB 响应时同样重查。可再次 miss；pendingFault 由重发同 VPN 得到。
- `s2Kill` 清除 CPU S2/S1 和在途 CPU 重查；`s1Kill` 只清 CPU S1 中尚未进入 WB 的请求，不清更老的 S2 重查。PTW 请求不受 CPU kill 影响。
- 等待请求不占 MSHR、不锁 way。

### 7.2 PTW 入口

- `ptw.req` Decoupled{paddr 56}：L1D 在可进入 S0 时拉 `ready`（第 4 类来源，本拍被选中且冲突检查通过）；fire 后进入 S1/S2，不查 dTLB。
- S2：PMP（S 特权、Load）、PMA（Load、8 B）；不允许或 `device` → `ptw.resp.accessFault=1`；ROM 可读。hit → `ptw.resp.data`；miss → 6.2 MSHR（`src=PTW`），回放时出 `ptw.resp`。
- 第一批采用保守接纳：PTW 接受前等 MSHR/写回槽和 PS 空，且没有可前进的更老 CPU S1/S2；CPU 翻译等待中的 X/Y 不阻塞其所需 PTE 读。接受后保留一笔 PTW 的 miss 容量，直到唯一响应；这不影响旧 MSHR 完成，但暂不实现 PTW 的 hit-under-miss。
- `ptw.resp` Valid 单拍；每笔恰一个响应；PTW 读不受 `s1Kill/s2Kill` 影响（MMU 已接受的 walk 不丢）。
- PTW 读 MSHR 满或同行 → 同 CPU 规则 s2Hold，S2 保持。

## 8. LR/SC 与 AMO

### 8.1 reservation

寄存器 `rsvValid`、`rsvLine`（`lineAddrBits`，U3 整行粒度）、`rsvTimer`（7 bit，80 拍）。

- 建立：LR 在 S2 Done（命中 E/M）或回放完成时；`rsvTimer = 80`。
- 清除：任意 probe 命中 `rsvLine`（处理时）、该行被替换（选为 victim）、`trapClearRsv`、SC 执行（成功或失败）。
- LR 与 SC 之间的其他访存不清除。
- 前进窗口：`rsvValid && rsvTimer > 0` 时命中 `rsvLine` 的 probe 不处理（SNP 保持在接收寄存器）；每拍 `rsvTimer−1`；窗口到期、SC、陷入时结束。

### 8.2 aq/rl LR/SC

S0 进入前检查：`rl` → 等 `drained` 再进入；`aq` → 其完成（resp/late）前不接收年轻请求（`req.ready=0`）。

### 8.3 AMO（独占路径）

状态机 `amo`：IDLE → DRAIN → LOOKUP → (MISS → WAIT) → RMW → DONE。

1. AMO 到达 S0：关入口，等 S1/S2 中更老请求判定、`drained`。
2. LOOKUP：正常经 S0–S2（Store 类翻译与检查，第 5.1 节 1–5 步）；异常 → resp Exc。
3. 命中 E/M → RMW；否则分配 MSHR（GetM），等 INSTALL（不持锁，probe 照常）；安装后从 S0 重查（可能又被 probe 收走，重复直到命中 E/M）。
4. RMW 窗口（S2 命中 E/M 的那一拍起）：读出旧 8 B 字 → `BreezeAmoAlu` → 次拍经 PS 写入并置 M；窗口内命中该行的 probe 延迟处理（≤2 拍）。
5. resp Done：AMO.W 按 `pa[2]` 选高/低 32 位作为 `oldOperand` 低半，写 mask 为对应 4 字节；返回值符号扩展到 64。AMO.D 全 8 字节。
6. 结果容量：resp 是 S2 判定通道，后端停在 WB 等待，无需额外缓冲。

## 9. MMIO

状态机 `mmio`：IDLE → WAIT_OLDEST → ISSUE → RESP。

- S2 识别 `device` 的 Load/Store：不判定，S2 保持（`s2Hold`），状态 → WAIT_OLDEST。
- 发出条件：该请求在 S2（即 WB，最老）、`drained`、未被 `s2Kill`。发出前可被 `s2Kill` 作废（回 IDLE）。
- ISSUE：读发 AR（`ARADDR = pa`，`ARPROT = 0`）；写发 AW + W（`WDATA` = `wdata` 左移 `pa[2:0]×8`，`WSTRB` 由 size 与 `pa[2:0]` 生成）。发出后 `mmioBusy=1`，不可取消。
- RESP：`RRESP/BRESP = OKAY` → resp Done（Load 数据经 5.4 节格式化）；SLVERR/DECERR → resp Exc（Load 5，Store 7）。
- 总线返回暂存一项结果，直到 CPU S2 获得执行端口并交付 Done/Exc 才释放；返回同拍若内部任务占用执行端口，不丢失 MMIO 完成。
- 期间 `req.ready=0`；probe、RSP↓、MSHR 不受影响（MSHR 已空）。
- 集群 `mmio` 1 笔在途、核间轮转在集群层做，L1D 只按 AXI 握手。

## 10. probe 处理

### 10.1 接收

- `snp` 接收寄存器 1 项（`snpValid`、`op`、`owner`、`addr`）。`snp.ready = !snpValid && initDone`。
- `snpValid` 时每拍判断能否处理（10.2 节）；能 → 作为 S0 内部来源（优先级 2）以整行操作进入：第 0 拍读 tag 与字 0，其后逐字读，共 `wordsPerLine` 拍（固定长度，不论是否需要数据）。
- 开始条件：除 10.2 节外，S1、S2 中没有未被 s2Hold 的同行 Store 类请求，且 PS 不同行。probe 待处理时 S0 不再接收重查与 CPU 新请求，因此该条件在 ≤3 拍内满足（有限拍本地延迟）。这保证整行读期间没有同行写入，读出的整行一致。

实现修订（2026-10-07，多核仿真）：CPU S2 为 FENCE 时，S1 的年轻 Store 随 CPU 流水保持，不能写入；因此它不阻止 probe 开始。旧 MSHR、写回槽、PS、LR 窗口与 AMO RMW 的第 10.2 节压住条件仍照常检查。probe 经内部完成流水答复后，旧事务才能取得 grant 并 drain，FENCE 再完成，年轻 Store 最后推进；不以 FENCE 的等待阻止旧事务所需的一致性答复。

### 10.2 压住条件（不处理，`snpValid` 保持）

| 条件 | 解除 |
| --- | --- |
| `addr` = MSHR 行 且 MSHR 在 WAIT/INSTALL/REPLAY 且 role > 本地状态（本地 I 收任意；本地 S 收 `owner=1`） | MSHR 离开 REPLAY（进入 LATE 或 IDLE；LATE 不压住）（PTW `ptwKilled` 时 INSTALL 后即解除） |
| `addr` = 写回槽行 且写回槽未收 PutAck | PutAck 后处理，此时本地 I，答无数据 Ack |
| `addr` = `rsvLine` 且前进窗口有效 | 窗口结束 |
| AMO RMW 窗口内同行 | 窗口结束（≤2 拍） |
| PS 有效且同行 | PS 写完（下一拍）；即 pending-store 先写、probe 次拍处理 |
| S2 中同行 Store/SC 已判定 Done 且将入 PS | 入 PS 并写完后 |

本地 S 且 MSHR 正在 GetM 升级、收到 `owner=0` 的 Inv：不压住，立即失效 S 并答复，MSHR 继续 WAIT（L2 将回 Data）。

### 10.3 处理（最后一拍的 S2）

- 查 tag（第 0 拍读出，随操作寄存）：本地状态 `st`（锁定 way 视为 I）。整行数据在各拍 S2 逐字收集。
- Inv：命中 → tag 置 I；答 InvAck，`hasData = (st == M)`，数据为读出整行。
- Down：命中 → E/M 置 S；答 DownAck，`hasData = (st == M)`。
- 未命中：答对应 Ack，`hasData=0`。
- 命中 `rsvLine` → 清 reservation。
- 修改 tag 时置同 set 在途 S1/S2 快照失效（5.3）。
- 答复进 RSP↑ 发送（6.4，优先于 Put）；发出后 `snpValid=0`。

## 11. 栅栏

- FENCE：`req.op=Fence` 进入 S0 后在 S2 等 `drained`，然后 resp Done；不访问阵列。PTW 与协议完成照常。
- FENCE.I、SFENCE.VMA：后端在 WB 观察 `drained`，L1D 无额外动作；无 `dcacheFlushReq/Done`。

## 12. ready 归类（对应 coherence spec 1.4 节）

| ready | 条件 | 纪律 |
| --- | --- | --- |
| `rspDown.ready` | 恒 1 | 1 |
| `snp.ready` | `!snpValid && initDone`；`snpValid` 的释放只依赖：MSHR 收到 RSP↓ Data/Ack 并回放（回放只等 PS 写口，有限拍本地；不等 `late.ready`）、写回槽收到 PutAck、LR 80 拍窗口、AMO ≤2 拍窗口、PS 1 拍、10.1 节开始条件 ≤3 拍、整行操作 `wordsPerLine` 拍 | 2 |
| `req`（L1D 发送） | — | L2 侧 3 |
| `rspUp`（L1D 发送） | L2 恒收 | 1 |
| `ptw.req.ready` | S0 仲裁与冲突检查 | 本地 |
| `req.ready`（后端） | 冲突、入口关闭、级保持、初始化 | 本地 |

注：回放不等 `late.ready`（6.2 节 LATE），probe 活性与后端写口无关。`late.ready` 只影响 MSHR 何时回 IDLE，后端饥饿保护使其有界。

## 13. 验证

### 13.1 断言（仿真）

- 每个后端请求恰一次 `resp`（kill 的除外）；`late` 至多 `nMshrs` 笔在途。
- S2 有效项的保持只来自本文规则（暂不判定、PTW/MSHR 满等），从不取决于 `late.ready`；S1/S2 保持的每一拍 `s2Hold=1`。
- 发送端 valid 在 fire 前字段不变（REQ、RSP↑、AXI-Lite）。
- RSP↓ op 与 MSHR/写回槽状态一致（6.5）。
- 写回槽有效时不发同行 Get；MSHR 回放必命中。
- 锁定 way 不命中、不被选为 victim。
- SC 成功 ⇒ 行状态 E/M。
- tag 不出现同 set 两路同 tag 有效。
- 每核至多 1 个未答复 probe；probe 压住只出现在 10.2 节列出的条件下。

### 13.2 模块测试（`design/src/test/scala/l1d/`）

L1D + 行为 L2 模型 + 行为 MMU：hit/miss/升级/写回（clean/dirty）、hit-under-miss、S0 冲突、快照失效、TLB miss 与 `dropS1Next`、PTW 读命中/缺失/IO fault、LR/SC 成功失败与 80 拍窗口、全部 AMO（.W/.D）、MMIO 读写与错误、probe 两种压住与升级中 Inv、refill 错误 → `late.error`、kill 各级。

### 13.3 集群测试

nCores 核 L1D + L2 + AXI 内存模型：随机访存压力 + golden memory、MP/SB/LR-SC litmus、死锁 watchdog（2000 拍无进展报错）。

### 13.4 非默认配置冒烟（必跑）

每组跑：config 推导单测（推导值与 `require`），模块测试中的 hit/miss/写回/probe 子集，集群随机压力 10k 访存 + golden memory。

| 名称 | 与默认差异 | 覆盖 |
| --- | --- | --- |
| `smoke-l2w4` | `l2Ways = 4` | L2 PLRU、victim、目录宽度 |
| `smoke-l1dw2` | `l1dWays = 2`（`l1iWays = 4`） | L1D PLRU、way 选择，L1I/L1D 路数不同 |
| `smoke-1core` | `nCores = 1` | sharers 宽度 1、无跨核 probe 路径 |

另加一条负向测试：`l1Sets = 256`（`l1Sets × lineBytes > 4096`）必须在构造时 `require` 失败。


## 2026-10-09 SOC-3e 用户授权流水边界修订

本节对应用户批准的组合优化，并优先于上文 SOC-3d 和 v1 的旧级数、旧拍数及“不得新增级/输出缓存”限制。架构数值、异常、PMP/PMA 权限与副作用规则不变；本节明确列出的流水延迟是本轮批准的合同迁移，不能以此放宽 golden、种子、规模或 watchdog。实现与证据见 `tasks/SOC-3e-pipeline-report.md`，未取得的仿真/最终布线/上板证据不得沿用基线结论。

L1D CPU 与内部完成流水均为 S0 接收、S1 捕获翻译及同步阵列输出、S2 并行判断寄存 PA 的 PMP/PMA 与 tag/data、S3 最终响应/分配/PS 接收。S0 的 dTLB 和 VIPT SRAM 查询仍并行；命中接收间隔保持一拍，命中响应从 E+2 改为 E+3。所有 CPU 在途槽随 hold 保持；旧接口 `s2Hold/s2Kill` 对应最终 S3，`s1Kill` 清年轻 S1/S2，`s2Kill` 另清最终 CPU S3 及三段重查。内部 probe/refill/PTW 不受 CPU kill 撤销。原样保留请求、翻译、末地址、归属和快照失效；permissionEvent 在 S1/S2/S3 都须使旧 CPU 权限作废并重查；内部 PTW 在上下文变化时刷新。probe 开始条件覆盖 S1/S2/S3，阻塞的年轻 store 不得卡住完成旧 miss 所需的 probe。TLB miss 的 S3 旧主、S2/S1 年轻槽由各自 valid 保持，逐一重查，无额外请求复制。

同字 Store→Load 的 S1/S2/S3/PS 冲突共四拍，下一 Load 最早 E+5 接收。32B/64b 整行 refill 在 R+1…R+4 安装、R+5 回放 S0、R+8 回放 S3，lateReg 无冲突写回 R+9。新增快照级也必须处理同拍 probe/refill/tag/data 变更。普通 store 对齐与字节掩码在较早级生成，最后合法完成控制写许可；失败 store/SC 不写，AMO 值仍来自原 RMW 算法。
