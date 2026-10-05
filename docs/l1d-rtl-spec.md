# Breeze v1 L1D RTL spec

状态：Claude 编写，供实现使用（2026-10-06）。设计依据为 [`dcache-pipeline-design.md`](dcache-pipeline-design.md) 第 2 节，输入摘录与已定决定见 [`l1d-spec-inputs.md`](l1d-spec-inputs.md)。协议编码以 [`coherence-l2-rtl-spec.md`](coherence-l2-rtl-spec.md) 第 1 节为准；MMU 合同以 [`breeze-mmu-rtl-spec.md`](breeze-mmu-rtl-spec.md) 为准。

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
| `late` | 出，Decoupled | 迟到数据：`rd`、`data` 64、`error`；后端 ready=0 时保持 |
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
| `data` | `SyncReadMem(sets × wordsPerLine, Vec(ways, Vec(8, UInt(8))))`，按 way+字节掩码写 | 1R1W | 地址 `{set, word}`；一次读出各路同一 8 B 字（S1 预选） |
| `plru` | 寄存器 `Vec(sets, UInt(plruBits))` | — | `TreePlru`；选 victim 时跳过锁定 way |

- refill 安装写整行：`data` 连续 `wordsPerLine` 拍写（每拍一字、只写目标 way），期间 S0 被 refill 占用；或在实现中把 `data` 拆成 `wordsPerLine` 个 bank 一拍写完。v1 取后者：`data` = `wordsPerLine` 个 `SyncReadMem(sets, Vec(ways, Vec(8, UInt(8))))`，读时只用 `vaddr[offBits−1:3]` 选中的 bank，安装时全部 bank 同拍写。
- 复位：初始化状态机逐 set 写 `tag` 全 I，共 `sets` 拍；期间 `req.ready=0`、`snp.ready=0`。`data` 不初始化。
- 同拍同地址读写的返回值不被依赖：S0 冲突检查（第 4 节）与快照失效（5.3 节）覆盖全部读写重叠情况。

## 3. 流水级

| 级 | 寄存器 | 动作 |
| --- | --- | --- |
| S0 | 无（组合仲裁，第 6 节） | 选中一个来源；读 `tag(idx)`、各 bank `data`；CPU 请求同拍发 `tlb.req`（`req.ready` 与 `tlb.req.ready` 同为 1 才 fire） |
| S1 | `s1`：valid、src、req、idx、word、age | 收 `tlb.resp`（CPU）或携带 PA（内部来源）；寄存 tag 各路、data 各路字、PA、翻译异常 |
| S2 | `s2`：同上 + PA、`tagVec`、`dataVec`、`snapInvalid` | tag 比较、命中路、格式化、PMP/PMA、MSHR 同行、判定（第 5 节） |
| PS | `ps`：valid、idx、word、way、mask、data | pending-store：写 `data`；E→M 写 `tag` |

- S1/S2 保持：S2 暂不判定时 S2 保持，S1 若有效则保持，S0 不 fire。
- S1 的 TLB miss：S1 请求与可能已 fire 的 S0 请求转入翻译等待（第 7 节），S1、S0 清空。
- `s1Kill`：当拍清 S1；若同拍 S0 fire 的请求更年轻，S0 请求同样丢弃（`req.ready` 置 0 当拍不接收）。
- `s2Kill`：当拍清 S2（若 S2 尚未判定）与 S1，翻译等待 FIFO 中 CPU 请求一并清除；不撤销 PS（已判定为更老）、MSHR、写回槽、probe。

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
- 判定为 Done/Mshr 时：PLRU 更新为命中路（miss 时不更新，安装时更新）。

### 5.3 快照失效

S1/S2 请求寄存 `idx` 与命中候选。下列事件发生在其阵列读之后、S2 判定之前，且同 set（refill 安装、probe 修改 tag 时比较 set 与 way；简化为同 set 即置位）→ 置 `snapInvalid`：

- refill 安装写 tag/data；
- probe 修改 tag（I 或 S）；
- 写回 victim 置 I。

PS 写 data 不触发（由第 4 节保证）。

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

内部来源（1–3）不查 dTLB，携带 PA；CPU 请求（5、6）查 dTLB。除 1–2 外，全部经第 4 节冲突检查。

### 6.2 MSHR（`nMshrs` 项，v1 = 1）

| 字段 | 说明 |
| --- | --- |
| `valid`、`state` | IDLE → (WB_READ) → SEND → WAIT → INSTALL → REPLAY → IDLE |
| `lineAddr`、`isGetM`、`way`、`upgrade` | 行 PA、请求类型、目标 way、是否写升级 |
| `src` | CPU / PTW |
| `op`、`rd`、`size`、`signed`、`isFlw`、`word`、`mask`、`wdata` | 回放所需原请求 |
| `isLr`、`ptwKilled` | LR 回放后建 reservation；PTW 请求已 kill 标记 |
| `refill`、`err`、`grantE` | 256 bit 缓冲、错误位、得 E（DataE/AckE）或 S（DataS） |

- SEND：victim 需写回时等写回读完成（6.3）；REQ `op` = GetS/GetM、`addr` = `lineAddr`、`id` = 0；fire 后 WAIT。
- WAIT：RSP↓ DataS/DataE/AckE 到达 → 存入 `refill`（AckE 不带数据，安装时保留原数据）→ INSTALL。`error=1` → 不安装，解锁 way（置 I），直接 REPLAY 交付错误。
- INSTALL：S0 内部来源，全部 bank 同拍写 `way` 的 data（AckE 不写 data），tag 写 `{state = grantE ? E : S, tag}`；PLRU touch；解锁 way；置同 set 在途快照失效。
- REPLAY：以 PA 从 S0 进入（经冲突检查），S2 执行原请求：Load → `late`（`late.ready=0` 时 S2 保持，不阻塞 RSP↓ 与 probe）；Store → PS；LR → `resp` Done 并建 reservation；PTW → `ptw.resp`（`ptwKilled` 时丢弃）。错误：Load 送 `late.error=1`；Store 丢弃；PTW → `ptw.resp.accessFault=1`。完成 → IDLE。
- 回放时行可能已被 probe 收走（回放前 Inv）：回放不再 miss——第 10 节规则压住比回放更晚的 probe 直到回放完成，因此回放必命中（断言）。

### 6.3 写回槽

字段：`valid`、`lineAddr`、`hasData`、`data`、`state`（READ → SEND → WAIT_ACK）。

- 分配：S2 分配 MSHR 且 victim 有效时置 `valid`，记 victim 地址与 `hasData = (state == M)`。
- READ：`hasData` 时 S0 内部读 victim way 全部 bank（一拍），S2 前把整行存入 `data`；无数据时跳过。victim 的 tag 在分配拍已写 I（5.2）；data 在 refill 安装前不被覆盖，因 MSHR 在 READ 完成前不进入 SEND。
- SEND：RSP↑ `Put`、`hasData`、`addr`、`data`；fire → WAIT_ACK。
- WAIT_ACK：RSP↓ PutAck → `valid=0`。
- 写回槽有效时：同行 S2 请求 s2Hold；不得发同行 Get（由 s2Hold 保证）；需 Put 的新 MSHR 分配 s2Hold。

### 6.4 RSP↑ 发送仲裁

probe 答复 > Put（两者各至多 1 笔待发；probe 答复先发不影响正确性，L2 分开缓冲）。

### 6.5 RSP↓ 接收

`ready` 恒 1。`op` 0–2 → MSHR（断言 MSHR 在 WAIT）；3 → 写回槽（断言 WAIT_ACK）；4、5 → 断言错误。

## 7. TLB miss 与 PTW 入口

### 7.1 翻译等待

- 2 项 FIFO `xlatWait`：X（S1 miss）与 Y（同拍 S0 已 fire、被 `dropS1Next` 丢弃的年轻请求，显式记录 `hasY`）。
- 进入等待次拍起关闭 CPU 新入口（`req.ready=0`）；S2、PS、MSHR、写回槽、probe、PTW 照常。
- `tlb.req.ready` 恢复后按年龄从 S0 重发 X、再 Y（作为第 5 类来源，查 dTLB）；可再次 miss，重新进入等待。pendingFault 由重发同 VPN 得到。
- `s2Kill` 清空 `xlatWait`；`s1Kill` 只在 X 尚在 S1 时有效（之后 X 已不在 S1，由 `s2Kill` 覆盖）。
- 等待请求不占 MSHR、不锁 way。

### 7.2 PTW 入口

- `ptw.req` Decoupled{paddr 56}：L1D 在可进入 S0 时拉 `ready`（第 4 类来源，本拍被选中且冲突检查通过）；fire 后进入 S1/S2，不查 dTLB。
- S2：PMP（S 特权、Load）、PMA（Load、8 B）；不允许或 `device` → `ptw.resp.accessFault=1`；ROM 可读。hit → `ptw.resp.data`；miss → 6.2 MSHR（`src=PTW`），回放时出 `ptw.resp`。
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
- 期间 `req.ready=0`；probe、RSP↓、MSHR 不受影响（MSHR 已空）。
- 集群 `mmio` 1 笔在途、核间轮转在集群层做，L1D 只按 AXI 握手。

## 10. probe 处理

### 10.1 接收

- `snp` 接收寄存器 1 项（`snpValid`、`op`、`owner`、`addr`）。`snp.ready = !snpValid && initDone`。
- `snpValid` 时每拍判断能否处理；能 → 作为 S0 内部来源（优先级 2）进入，读 tag 与 data（整行，全部 bank）。

### 10.2 压住条件（不处理，`snpValid` 保持）

| 条件 | 解除 |
| --- | --- |
| `addr` = MSHR 行 且 MSHR 在 WAIT/INSTALL/REPLAY 且 role > 本地状态（本地 I 收任意；本地 S 收 `owner=1`） | MSHR 回到 IDLE（PTW `ptwKilled` 时 INSTALL 后即解除） |
| `addr` = 写回槽行 且写回槽未收 PutAck | PutAck 后处理，此时本地 I，答无数据 Ack |
| `addr` = `rsvLine` 且前进窗口有效 | 窗口结束 |
| AMO RMW 窗口内同行 | 窗口结束（≤2 拍） |
| PS 有效且同行 | PS 写完（下一拍）；即 pending-store 先写、probe 次拍处理 |
| S2 中同行 Store/SC 已判定 Done 且将入 PS | 入 PS 并写完后 |

本地 S 且 MSHR 正在 GetM 升级、收到 `owner=0` 的 Inv：不压住，立即失效 S 并答复，MSHR 继续 WAIT（L2 将回 Data）。

### 10.3 处理（S2 拍）

- 查 tag：本地状态 `st`（锁定 way 视为 I）。
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
| `snp.ready` | `!snpValid && initDone`；`snpValid` 的释放只依赖：MSHR 收到 RSP↓ Data/Ack 并回放（回放只等 `late.ready` 与 PS 写口，均为有限拍本地）、写回槽收到 PutAck、LR 80 拍窗口、AMO ≤2 拍窗口、PS 1 拍 | 2 |
| `req`（L1D 发送） | — | L2 侧 3 |
| `rspUp`（L1D 发送） | L2 恒收 | 1 |
| `ptw.req.ready` | S0 仲裁与冲突检查 | 本地 |
| `req.ready`（后端） | 冲突、入口关闭、级保持、初始化 | 本地 |

注：MSHR 回放受 `late.ready` 影响，后端写口按 L1D 迟到数据最高优先，至多等 WB 中一拍，属有限拍本地延迟。

## 13. 验证

### 13.1 断言（仿真）

- 每个后端请求恰一次 `resp`（kill 的除外）；`late` 至多 `nMshrs` 笔在途。
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
