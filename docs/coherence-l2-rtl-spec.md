# Breeze v1 一致性协议与 L2/Home RTL spec

状态：Claude 编写，供实现使用（2026-10-06）。设计依据为 [`dcache-pipeline-design.md`](dcache-pipeline-design.md) 第 3、4 节；本文把其中的机制落到寄存器、状态机、端口和同拍规则。设计文档第 7 节“待拍板”中与本文相关的项，在本文 0.2 节给出 v1 取值。L1D 侧见 [`l1d-rtl-spec.md`](l1d-rtl-spec.md)，集群与外壳见 [`cluster-soc-rtl-spec.md`](cluster-soc-rtl-spec.md)。

## 0. 范围与参数

### 0.1 文件

| 文件 | 内容 |
| --- | --- |
| `design/src/main/scala/coherence/CoherenceParams.scala` | 参数、常量、编码 |
| `design/src/main/scala/coherence/CoherenceBundles.scala` | 四条链路的 Bundle |
| `design/src/main/scala/l2/L2Home.scala` | 顶层：入口、主流水、目录、输出缓冲 |
| `design/src/main/scala/l2/L2Slots.scala` | 慢槽状态机 |
| `design/src/main/scala/l2/L2ProbeEngine.scala` | probe 引擎 |
| `design/src/main/scala/l2/L2MemEngine.scala` | AXI4 内存引擎 |
| `design/src/main/scala/bus/Axi4.scala` | AXI4 / AXI4-Lite Bundle（若仓库无现成定义） |

旧的 `cache/BreezeL2Home.scala`、`cache/Coherence.scala` 删除（见 [`v1-impl-plan.md`](v1-impl-plan.md)）。

### 0.2 参数（推导自 `BreezeClusterConfig`）

全部参数推导自 `config/config.scala` 的 `BreezeClusterConfig`（字段与 `require` 见 [`l1d-rtl-spec.md`](l1d-rtl-spec.md) 0.2 节）。`CoherenceParams(cfg)` 只做推导，L2 各模块不得出现几何常量。

| 参数 | 来源 | 默认（4 核） | 说明 |
| --- | --- | --- | --- |
| `nCores` | `cfg.nCores` | 4 | L1D 客户端数，1–8 |
| `lineBytes` | `cfg.lineBytes` | 32 | v1 锁定；`offBits = log2(lineBytes)` |
| `paddrBits` | `cfg.paddrBits` | 32 | v1 锁定。可缓存区域全部在 4 GiB 以下，PMA 把 ≥2^32 判为不存在，不进入 L2 |
| `l2Ways` | `cfg.l2Ways` | 8 | 2 的幂；`TreePlru` 位数 `l2Ways − 1` |
| `l2Sets` | `nCores × l2BytesPerCore / (l2Ways × lineBytes)` | 1024 | 2 的幂；`setBits = log2(l2Sets)` |
| `l2Slots` | `cfg.l2Slots` | 2 | 慢槽数 |
| `memReadsInFlight` | `= l2Slots` | 2 | 每槽固定 AXI ID |
| `memDataBits` | 常量 64 | 64 | AXI4 `mem` 数据宽度；一行 `lineBytes × 8 / 64` 拍 INCR |
| 链路数据宽度 | `lineBytes × 8` | 256 | 每条消息单拍 |
| `lineAddrBits` | `paddrBits − offBits` | 27 | 消息 `addr` 宽度 |
| `tagBits` | `paddrBits − setBits − offBits` | 17 | |
| `sharerBits` | `nCores` | 4 | |
| II | 1 | 1 | 同 set 由在途检查串行（4.3 节） |
| 写回在途 | 1 | 1 | 内存引擎 1 项写缓冲 |

正文中出现的 “8 路”“7 bit PLRU”“32 字节”“4 拍” 等数值均指默认配置，实现一律用上表推导值。非默认配置冒烟（`l2Ways = 4`、`l1dWays = 2`、`nCores = 1`）见 L1D spec 13.4 节，L2 测试同样必跑。

## 1. 协议

### 1.1 客户端与 source

| 客户端 | 编号 | 发出 | 接收 |
| --- | --- | --- | --- |
| 核 i 的 L1D | `L1D(i)` | REQ、RSP↑ | SNP、RSP↓ |
| 核 i 的 L1I | `L1I(i)` | REQ（Read） | RSP↓ |
| DMA | `DMA` | REQ（Read、MaskWrite） | RSP↓ |

每个客户端与 L2 之间有独立的点对点链路（星形），因此消息不需要携带目的地字段：L2 输入侧按端口区分来源，输出侧按端口发送。L1D 在途上界：Get 1（MSHR 数 N=1）、Put 1；L1I：Read 2（demand + 预取，各 1）；DMA：1。消息中的 `id` 字段只用于 L1I 区分两笔 Read（1 bit），其余客户端恒为 0。

### 1.2 消息编码

**REQ（客户端 → L2，可反压）**

| 字段 | 宽度 | 说明 |
| --- | --- | --- |
| `op` | 2 | 0 `GetS`、1 `GetM`、2 `Read`、3 `MaskWrite` |
| `addr` | `paddrBits - 5` | 行地址 |
| `id` | 1 | L1I 的请求编号 |
| `mask` | 32 | 仅 `MaskWrite`，按字节；不跨行 |
| `data` | 256 | 仅 `MaskWrite` |

GetS/GetM 只由 L1D 发出；Read 由 L1I、DMA 发出；MaskWrite 只由 DMA 发出。其他组合是协议错误（断言）。

**RSP↑（L1D → L2，L2 无条件接收）**

| 字段 | 宽度 | 说明 |
| --- | --- | --- |
| `op` | 2 | 0 `Put`、1 `InvAck`、2 `DownAck` |
| `hasData` | 1 | Put：行为 M；InvAck/DownAck：本地为 M |
| `addr` | `paddrBits - 5` | 行地址 |
| `data` | 256 | `hasData` 时有效 |

**SNP（L2 → L1D）**

| 字段 | 宽度 | 说明 |
| --- | --- | --- |
| `op` | 1 | 0 `Inv`、1 `Down` |
| `owner` | 1 | 目标角色：1 = L2 认为本核持 E/M，0 = 持 S。`Down` 恒为 1 |
| `addr` | `paddrBits - 5` | |

**RSP↓（L2 → 客户端，客户端无条件接收）**

| 字段 | 宽度 | 说明 |
| --- | --- | --- |
| `op` | 3 | 0 `DataS`、1 `DataE`、2 `AckE`（升级，副本仍在，不带数据）、3 `PutAck`、4 `ReadData`、5 `WriteAck` |
| `id` | 1 | 回送 REQ 的 `id` |
| `error` | 1 | 下游读错误；`DataS/DataE/ReadData` 时有效，其余为 0 |
| `data` | 256 | `DataS/DataE/ReadData` 时有效 |

`DataS/DataE/AckE/PutAck` 只发给 L1D；`ReadData` 发给 L1I/DMA；`WriteAck` 只发给 DMA。

### 1.3 握手

四条链路都是 valid/ready。RSP↑ 与 RSP↓ 的接收端 `ready` 只允许因本地有限拍数的仲裁为 0（v1 中 L2 的 RSP↑ `ready` 恒为 1，见 3.2 节）；发送端 valid 拉高后在 fire 前保持全部字段不变（断言）。

### 1.4 依赖纪律（防死锁依据）

1. RSP↑、RSP↓ 的接收不依赖任何其他消息。
2. SNP 的接收只依赖 RSP↓（MSHR 的 Data/Ack、写回槽的 PutAck）及有限拍数的本地延迟（AMO 短窗口、LR 前进窗口）。
3. REQ 的接收可以依赖其他全部链路。

本文每个 `ready` 的条件在第 9 节逐条归类。

### 1.5 L1D 本地状态与目录状态

L1D 行状态：`I`、`S`、`E`、`M`（2 bit）。E→M 静默。

L2 目录（每行）：`NONE`、`SHARED`、`UNIQUE`（2 bit）+ `sharers`（`nCores` bit）。不变式：NONE ⇔ sharers=0；UNIQUE ⇒ sharers 恰 1 位；SHARED ⇒ sharers ≥1 位。目录精确：L1D 逐出任何行（含 S、E clean）都发 Put，因此除在途 Put 外，sharers 与 L1D 实际持有一致。

## 2. L2 结构

```text
 REQ×(2·nCores+1) ─┐                   ┌─> SNP×nCores
 RSP↑×nCores ──> Put 缓冲/probe 答复缓冲 │
                   v                   │
            ┌── S0 仲裁 ──┐            probe 引擎 <── 慢槽×2 ──> 内存引擎 ──> AXI4 mem
            │ meta/plru 读 │                ^            │
            S1 tag 比较、分类、data 读       │            │
            S2 写 meta/data、形成响应、分配槽 ┘            │
                   │                                      │
                   └──> RSP↓ 输出缓冲 ×(2·nCores+1) <──────┘（错误响应）
```

### 2.1 阵列

| 阵列 | 组织 | 端口 | 内容 |
| --- | --- | --- | --- |
| `meta` | `SyncReadMem(l2Sets, Vec(8, MetaEntry))`，按 way 掩码写 | 1R1W | `valid`、`dirty`（相对内存）、`tag`、`state`、`sharers` |
| `plru` | `SyncReadMem(l2Sets, UInt(7))` | 1R1W | 8 路树形 PLRU（复用 `mmu/sv39/TreePlru.scala`） |
| `data` | `SyncReadMem(l2Sets × 8, Vec(32, UInt(8)))`，按字节掩码写 | 1R1W | 地址 `{set, way}` |

复位后由初始化状态机逐 set 写 `meta` 全无效、`plru` 为 0，共 `l2Sets` 拍；期间 S0 不接收任何任务或请求。`data` 不需要初始化。

读写同拍同地址的返回值不依赖：主流水的在途检查（4.3 节）保证同一 set 的读与写不会同拍发生。

### 2.2 寄存器总览

| 结构 | 内容 |
| --- | --- |
| 入口保持 | 每个 REQ 端口无额外缓冲：客户端保持请求，L2 只在该请求完成或移交槽时拉 `ready`（3.1 节）；`inPipe[src]` 标记该端口的请求正在主流水中 |
| Put 缓冲 | 每核 1 项：`valid`、`hasData`、`addr`、`data` |
| probe 答复缓冲 | 每核 1 项：`valid`、`op`、`hasData`、`addr`、`data` |
| 流水寄存器 | S1、S2 各一组：`valid`、`kind`（新请求 / Put 任务 / 槽任务）、`src`、`slot`、`req`、`set`、`tag`，S2 另有命中信息、目录读出值、选中 way |
| 慢槽 | 见第 5 节 |
| probe 引擎 | 见第 6 节 |
| 内存引擎 | 见第 7 节 |
| RSP↓ 输出缓冲 | 每客户端一个 FIFO，深度 = 该客户端最大在途响应数（L1D 2、L1I 2、DMA 1），因此 S2 写入时必有空位（断言） |

## 3. 入口

### 3.1 REQ：在 S2 才握手

L2 不在 S0 接收 REQ，而是**窥视**客户端保持的请求：S0 选中某端口的请求后置 `inPipe[src]`，请求随流水前进；S2 根据结果决定：

| S2 结果 | REQ 端口 | 说明 |
| --- | --- | --- |
| 快路径完成 | `ready=1`（fire） | 响应写入输出缓冲 |
| 分配慢槽 | `ready=1`（fire） | 请求内容复制进槽 |
| 需要慢槽但槽满 | 不 fire | 撤销本次查询，`inPipe` 清除，客户端继续保持；之后重新参加 S0 仲裁 |

这样不需要等待重查记录：未握手的请求由客户端保持，已握手的请求都在槽中。`inPipe[src]` 为 1 时该端口不参加 S0 仲裁，防止同一请求重复进入流水。S2 只对 S2 中那个请求的端口拉 `ready`；客户端在 `ready` 前保持 valid 与内容不变（断言），所以 S2 握手的就是 S0 看到的请求。

槽满撤销的请求在“有槽释放”之前不再参加仲裁（`slotWait[src]` 位，任一槽释放时全部清零），避免空转占用 S0。

### 3.2 RSP↑：无条件接收

每核 RSP↑ 的 `ready` 恒为 1。按 `op` 分流：`Put` 写入该核 Put 缓冲；`InvAck/DownAck` 写入该核 probe 答复缓冲。断言写入时对应缓冲为空（L1D 每类至多 1 笔在途）。

probe 答复写入缓冲的同拍通知 probe 引擎（第 6 节）。Put 缓冲由 Put 任务消费（4.5 节）。

## 4. 主流水

### 4.1 S0 仲裁

候选与优先级（高到低）：

1. **Put 任务**：Put 缓冲有效的核，核间轮转。
2. **槽任务**：慢槽发出的任务请求（第 5 节），槽间轮转。
3. **新请求**：REQ 端口有效、`!inPipe`、`!slotWait`、该端口请求的 set 未被保护的端口，端口间轮转。端口顺序：L1D(0..n-1)、L1I(0..n-1)、DMA。

所有候选还须通过在途检查（4.3 节）。不满足的候选不阻塞其他候选。

S0 动作：读 `meta(set)`、`plru(set)`，寄存候选信息进 S1。新请求在 S0 被选中时，其 set 进入 `pipeSet` 记录，直到离开 S2。

### 4.2 S1：比较与分类

- tag 比较 8 路，得 `hit`、`hitWay`；hit 时读出该行目录与 dirty。
- 读 `data({set, way})`：hit 时 way = hitWay；槽的 INSTALL/EVICT 任务时 way = 槽记录的 way；Put 任务时 way = 命中路（Put 必命中，否则协议错误）。数据在 S2 可用。
- 新请求按 4.4 节分类，结果寄存进 S2。

### 4.3 在途检查（同 set 串行）

任一拍，若 S1 或 S2 中有有效项的 set 等于候选的 set，候选不能进入 S0。由此同一 set 的 meta/data 读写不会同拍，S1 读到的 meta 总是最新的。不同 set 每拍可进一项（II=1）。

### 4.4 新请求分类（S1 判定，S2 执行）

记 `r` = 请求端口（L1D 时为核号 c），`st/sh` = 命中行目录状态与 sharers。

| 请求 | 条件 | S2 动作 | 结果 |
| --- | --- | --- | --- |
| GetS | hit，NONE | 目录 → UNIQUE{c}；响应 `DataE` | 快 |
| GetS | hit，SHARED | sharers \|= c；响应 `DataS` | 快 |
| GetS | hit，UNIQUE{o}，o≠c | 分配槽，类型 PROBE（Down，目标 o） | 慢 |
| GetM | hit，NONE | 目录 → UNIQUE{c}；响应 `DataE` | 快 |
| GetM | hit，SHARED 且 sh == {c} | 目录 → UNIQUE{c}；响应 `AckE` | 快 |
| GetM | hit，SHARED 且 sh 含其他核 | 分配槽，PROBE（Inv，目标 sh \ {c}，角色 sharer） | 慢 |
| GetM | hit，UNIQUE{o}，o≠c | 分配槽，PROBE（Inv，目标 o，角色 owner） | 慢 |
| Read | hit，NONE/SHARED | 响应 `ReadData`，目录不变 | 快 |
| Read | hit，UNIQUE{o} | 分配槽，PROBE（Down，目标 o） | 慢 |
| MaskWrite | hit，NONE | 按 mask 写 data，dirty=1；响应 `WriteAck` | 快 |
| MaskWrite | hit，SHARED/UNIQUE | 分配槽，PROBE（Inv，目标 sh，角色按状态） | 慢 |
| 任意 | miss | 分配槽，类型 MISS | 慢 |

协议错误（断言，不伪装成功）：GetS 命中 UNIQUE{c} 或 SHARED 含 c；GetM 命中 UNIQUE{c}（c 已是 owner）。L1D 的写回槽规则保证这些不会出现（见 L1D spec）。

快路径的 PLRU 在 S2 更新为命中路；响应数据取 S1 读出的 data。

### 4.5 Put 任务

Put 缓冲有效时作为任务进入 S0（不申请 set 保护，只受在途检查）。S1 必须命中（包含性，断言）。S2：

- sharers 去掉该核；若去掉后为 0，state → NONE；原为 UNIQUE 的，state → NONE。断言该核原在 sharers 中。
- `hasData`：断言原状态为 UNIQUE{该核}；写整行 data，dirty=1。
- 向该核 RSP↓ 输出缓冲写 `PutAck`；清 Put 缓冲。

Put 任务可以发生在某槽持有该 set 保护期间（设计文档 3.3 节）；槽的后续任务重新读取阵列，看到 Put 后的目录与数据。

### 4.6 S2 写入与响应

S2 至多一次 meta 写（一个 way 的 MetaEntry）、一次 data 写（一个 {set, way}，字节掩码）、一次 plru 写、一次响应写入输出缓冲、至多一次槽分配。响应写入输出缓冲的同拍，该事务的 set 保护（若是快路径的新请求，即 `pipeSet` 记录）在本拍末解除。

## 5. 慢槽

### 5.1 槽寄存器

| 字段 | 说明 |
| --- | --- |
| `valid`、`state` | 见 5.2 |
| `src`、`req` | 原请求（op、addr、id、mask、data） |
| `set`、`tag` | 由 addr 得出；槽有效期间该 set 受保护 |
| `type` | MISS / PROBE |
| `way` | MISS：选中的 victim way；PROBE：命中路 |
| `victimTag` | victim 原 tag（用于写回地址与 probe 地址） |
| `probeOp`、`probeTargets`、`probeOwner` | 待 probe 的核集合与角色 |
| `refill`、`refillErr` | 256 bit 回填缓冲与错误位 |

### 5.2 状态机

```text
MISS 分配（S2）：选 victim way V（无效 way 优先，否则 PLRU）
  V 无效                 → MEM_READ
  V 有效                 → EVICT（发 EVICT 任务）

EVICT 任务（经主流水）：读 V 的目录、dirty、data
  有 L1D 副本            → PROBE_WAIT（probe 引擎：Inv 全部 sharers，角色按状态，地址 = victimTag/set）
                            答复收齐 → 再发 EVICT 任务
  无副本、dirty          → 等内存引擎写缓冲空闲，把 {victim 地址, data} 交给写缓冲；
                            同一任务 S2 把 V 置无效 → MEM_READ
  无副本、clean          → S2 把 V 置无效 → MEM_READ
  EVICT 任务 S2 若有 probe 答复数据：先合并进 data（dirty=1），再按上面判定

MEM_READ：向内存引擎申请读（本槽 AXI ID）；返回完整后 → INSTALL（发 INSTALL 任务）
  refillErr              → S2 不安装，向 src 写错误响应（DataS/DataE/ReadData 带 error=1；
                            MaskWrite 回 WriteAck，error 位无，记入错误计数）→ 释放
INSTALL 任务：S2 写 V：valid=1、tag、state=NONE、sharers=0、dirty=0，data = refill；
  同一 S2 按 4.4 节对“命中 NONE”的规则执行原请求并响应（GetS/GetM → DataE、Read → ReadData、
  MaskWrite → 合并 mask 后写入、dirty=1、WriteAck）→ 释放

PROBE 分配（S2）：记录 probeOp/targets/owner → PROBE_WAIT
PROBE_WAIT：probe 引擎服务本槽；答复收齐 → REPLAY（发 REPLAY 任务）
REPLAY 任务：重新读取命中路（不保存快照）；S2：
  合并 probe 答复数据（若有，整行写入，dirty=1）；
  更新目录：Inv 的目标全部移除；Down 的 owner 降为 sharer（state SHARED）
  然后按 4.4 节对新状态执行原请求（此时必为快路径，断言）并响应 → 释放
```

槽任务请求：处于 EVICT / INSTALL / REPLAY 待发状态的槽向 S0 请求一个任务；被选中后进入“任务在流水中”子状态，任务在 S2 完成时按上表转移。EVICT 任务的写缓冲交接需要内存引擎写缓冲空闲：若不空闲，EVICT 任务在 S2 不做任何修改，槽留在 EVICT 待发状态，下一次再发。

合并数据的字节规则（REPLAY、MaskWrite）：写掩码 = 有 owner 数据 ? 全 1 : 0；MaskWrite 再与 `req.mask` 合并；每字节数据 = `req.mask` 选中 ? DMA 数据 : (owner 数据有效 ? owner 数据 : 不写)。响应数据（DataS/DataE/ReadData）= owner 数据有效 ? owner 数据 : S1 读出的 data。

槽释放：最后一个任务 S2 写响应的同拍，`valid` 清零，set 保护本拍末解除，`slotWait` 全部清零。

### 5.3 保护

`protectedSet(s)` = 任一有效槽的 `set` 等于 s，或 `pipeSet` 中有效项等于 s。新请求仅在其 set 未受保护时可被 S0 选中；Put 任务和槽任务不检查保护，只受在途检查。

## 6. probe 引擎

一次服务一个槽。寄存器：`busy`、`slot`、`addr`、`op`、`owner`、`toSend`（核掩码）、`waitAck`（核掩码）、`gotData`。

- 空闲时从处于 PROBE_WAIT 且未服务的槽中轮转选一个，载入目标。
- 每核 SNP 链路独立：`toSend` 中的核各自发 SNP（`op`、`owner`、`addr`），fire 后从 `toSend` 移到 `waitAck`。
- 某核 probe 答复写入缓冲（3.2 节）时：断言该核在 `waitAck` 中、`addr` 匹配、`op` 对应（Inv→InvAck、Down→DownAck）、`hasData` 只来自 owner 角色；从 `waitAck` 移除。
- `toSend` 与 `waitAck` 都为空：通知槽“答复收齐”。probe 答复缓冲不在此时清除，由槽的下一个任务（EVICT/REPLAY）在 S2 读取并清除（数据合并后）；**引擎保持 `busy` 直到该任务在 S2 清除缓冲**，才可服务下一个槽，因此每核 probe 答复缓冲不会被第二笔答复覆盖。

每核至多一个 probe 在途由“一次一个槽、每核一笔”保证。

## 7. 内存引擎（AXI4 `mem`）

### 7.1 读

- 每槽固定 `ARID = 槽号`。槽申请读时，若写缓冲有效且其行地址等于本次读地址，读等待写的 B 响应（RAW 经内存保序）。
- AR：`ARADDR = 行地址 << 5`、`ARLEN = 3`、`ARSIZE = 3`、`ARBURST = INCR`。
- 两项在途队列记录 AR 发出顺序；R 通道按队列头路由到对应槽，`RID` 与队列头断言一致（v1 下游按序返回）。4 拍拼成 256 bit 写入槽的 `refill`；任一拍 `RRESP ≠ OKAY` 置 `refillErr`；`RLAST` 时通知槽并出队。`RREADY` 恒为 1。

### 7.2 写

- 1 项写缓冲：`valid`、行地址、256 bit 数据。由 EVICT 任务填入。
- AW（`AWID = 0`、`AWLEN = 3`、`AWSIZE = 3`、INCR）与 4 拍 W（`WSTRB` 全 1，第 4 拍 `WLAST`）发出后等 B；收到 B 后清 `valid`。`BRESP ≠ OKAY` 只计入错误计数器并在仿真中报告（写回已无请求者可交付）。

## 8. RSP↓ 输出

每客户端一个输出 FIFO；S2 写入，链路侧按 valid/ready 发出。深度见 2.2 节。L1D 的 FIFO 中 `PutAck` 与 `DataX/AckE` 可以同时存在，按写入顺序发出。

## 9. ready 条件归类

| ready | 条件 | 纪律 |
| --- | --- | --- |
| REQ（各端口） | S2 中该端口的请求完成或分配槽 | 3：依赖 set 保护、槽、在途检查、阵列初始化 |
| RSP↑ | 恒 1 | 1 |
| SNP（L2 为发送方） | — | L1D 侧 ready 归类见 L1D spec |
| RSP↓（L2 为发送方） | — | 客户端恒接收 |
| AXI R、B | 恒 1 | 下游响应不依赖任何消息 |

## 10. 断言（仿真）

- 目录不变式（1.5 节）在每次 meta 写时检查写入值。
- RSP↑、probe 答复的合法性（3.2、6 节）；Put 必命中、去除的核必在 sharers 中、带数据的 Put 来自 UNIQUE owner。
- 4.4 节的协议错误组合。
- 输出 FIFO 写入时不满；Put 缓冲、probe 答复缓冲写入时为空。
- REQ 端口 valid 在 ready 前保持且内容不变。
- `RID` 与在途队列头一致。
- 同拍同 set 读写不发生（在途检查的结果）。
- 槽 REPLAY 任务的原请求在 S2 必为快路径。

## 11. 计数器接口

按 [`observability-design.md`](observability-design.md) 2.5 节输出事件脉冲（按来源细分）：请求数、命中、miss、set 保护等待拍数、槽满撤销次数、probe 发出数、内存读写数、写缓冲等待拍数。计数器本体在 observability 步骤实现，本步只引出事件线。
