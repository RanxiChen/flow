# Flow 多核缓存一致性、RV64A 与 IPI 实现设计

状态：**设计冻结候选，尚未实现/仿真**

输入：`RV64_Multicore_MESI_A_Extension_Hardware_Design.docx`、当前 Flow RTL、现有组相联 DCache 与 MESI 草稿

目标：在尽量保留现有核心接口和单核回归入口的前提下，形成可分阶段实现的私有 L1I/L1D、共享 L2/Home、RV64A 和核间软件中断方案。

## 1. 先冻结的结论

1. 每个 Hart 组成一个 Tile：`Core + private L1I + private L1D`；多个 Tile 共享一个 `L2/Home`。
2. L1D 参与数据一致性。L2/Home **必须**能向 L1D 发送 invalidate、downgrade、dirty-data recall；否则不能实现 MESI。
3. L1I 不参加 MESI，也不接收 L2 invalidate。每个 Hart 只在执行本地 `FENCE.I` 时 invalidate 自己的 I$ 和取指流水。
4. L1I miss 必须通过 L2/Home 读取。如果目录显示最新数据在某个 L1D owner，Home 必须先取回最新数据，不能直接返回可能过期的 L2 data。
5. AMO ALU 放在每个私有 L1D 内是可行的；原子性来自“先取得 M 权限 + RMW 期间不交出该 line”，而不是来自 ALU 本身。
6. LR/SC 每 Hart 只需要一组 reservation 寄存器，不需要给每条 cache line 加 reservation bit，也不需要一张大 reg array。
7. IPI/CLINT 属于 SoC MMIO 外设，应在 LiteX 一侧实现。继续使用当前已经占用的 `0x0200_0000` 经典 CLINT 地址布局。
8. 第一版先闭合 directory-based **MSI**，通过后再加入 E 状态成为 MESI。最终接口和目录按 MESI 预留，避免先做一个普通 L2 再推倒重来。
9. 当前单核 `BreezeCoreWishbone` 保留，作为回归顶层；新增多核 cluster 顶层，不直接把现有单核顶层改成多核。
10. 第一版继续采用 blocking L1 和 L2 全局单一致性事务，暂不做多 MSHR、hit-under-miss、并行 probe 或 store buffer。

## 2. 必须纠正的两个边界

### 2.1 “L2 不给 L1 发 invalid”只对 I$ 成立

- 对 L1I：成立。I$ 是软件维护的一致性域，本地 `FENCE.I` 负责失效。
- 对 L1D：不成立。若 Hart0/Hart1 都持有 S，Hart0 要写时，Home 必须 invalidate Hart1 并等到 Ack 后才能授予 Hart0 M。
- 若旧 owner 是 M，Home 还必须取回 owner 的最新整行数据并使其降级或失效。

因此文中后续把两类通道分开：

```text
L1I <---- GetInstr/DataResp ----> L2/Home     不记 I$ sharer，不发 I$ probe
L1D <---- GetS/GetM/Put/Probe --> L2/Home     完整数据一致性
```

这里的“inclusive L2”只对参与目录的一致性 L1D 副本成立。未被目录跟踪的 L1I 不应被描述为严格 inclusive。

### 2.2 “保留原接口”需要按边界理解

可以保留：

- Core 与 L1I 的现有取指接口及 I$ 的 line refill 形态；Hart ID 可由顶层 adapter 附加。
- Core 与 L1D 的普通 load/store 字段和一次请求、一次响应语义。
- L2 下游到 LiteX/RAM 的 64-bit Wishbone 接口。
- 现有单核 `BreezeCoreWishbone` 顶层和单核测试入口。

不能原样保留：

- L1D 不能继续只用当前 `DCacheMemReqIO/DCacheMemRespIO` 直连 Wishbone。缓存数据请求必须改走带权限和 probe 的一致性通道。
- A 扩展需要在 CPU→L1D 请求上增加 `LR/SC/AMO`、`amoFunc`、`aq`、`rl` 等 sideband。
- 多核必须给 CSR 增加 `hartId` 和 `machineSoftwareInterrupt` 输入。

推荐把 L1D 下游拆成两个端口：

```text
cachedCoherencePort  -> shared L2/Home
uncachedMmioPort     -> LiteX Wishbone interconnect
```

MMIO 保持标量、byte-mask、不可缓存；RAM cached 请求不再绕过 Home。

## 3. 总体硬件结构

```text
 Hart 0 Tile                                      Hart N-1 Tile
┌───────────────────────────┐                  ┌───────────────────────────┐
│ Core(hartId=0)            │                  │ Core(hartId=N-1)          │
│   ├─ private L1I          │                  │   ├─ private L1I          │
│   └─ private L1D          │                  │   └─ private L1D          │
│      ├─ MESI controller   │                  │      ├─ MESI controller   │
│      ├─ reservation regs  │                  │      ├─ reservation regs  │
│      └─ AMO ALU/FSM       │                  │      └─ AMO ALU/FSM       │
└───────┬─────────┬─────────┘                  └───────┬─────────┬─────────┘
        │ I read  │ D coherence                         │ I read  │ D coherence
        └────┬────┴─────────────────────────────────────┴────┬────┘
             │                                             │
             └──────────────┬──────────────────────────────┘
                            ▼
                  ┌─────────────────────┐
                  │ Shared L2 / Home    │
                  │ arbiter + directory│
                  │ tag/data + FSM     │
                  └──────────┬──────────┘
                             │ cached memory Wishbone
                             ▼
                       RAM / DDR controller

 each Tile uncached/MMIO ──> arbiter ──> LiteX shared bus ──> CLINT/UART/GPIO/...
                                              ▲
                               optional SoC masters can also write CLINT
```

建议新增 `BreezeMulticoreClusterWishbone(numHarts)`，内部实例化 Tile、L2/Home 和 MMIO arbiter。当前 `BreezeCoreWishbone` 不删除，继续负责单核基线。

## 4. 第一版建议参数

以下是实现起点，不是性能结论：

| 部件 | 第一版参数 | 原因 |
|---|---:|---|
| L1I | 8 KiB，4-way，32 B line | 保持当前 `64 sets × 4 ways × 32 B` |
| L1D | 4 KiB，2-way，32 B line | 先解决当前 256 B 全相联 D$ 的容量问题；2-way 替换逻辑简单 |
| L2 | 32 KiB，4-way，32 B line，单 bank | 足够覆盖双核功能测试，先避免 bank 冲突和跨 bank 排序 |
| Harts | 参数化，第一阶段 `N=2` | 先闭合双核；目录 sharer bitmap 从一开始按 `numHarts` 参数化 |
| Outstanding | 每 L1 一个；L2 全局一个 coherence transaction | 降低瞬态状态和死锁验证复杂度 |

L1D 配置应从 `entryNum` 改为 `sets/ways/lineBytes`：

```text
lineOffset = log2(lineBytes)
setIndex   = address[lineOffset + log2(sets) - 1 : lineOffset]
tag        = address[PLEN-1 : lineOffset + log2(sets)]
```

L1D 元数据为每个 set、每个 way 的 `tag + MESI(2b)`；替换状态按 set 保存。2-way 第一版只需每 set 1 个 replacement bit，invalid-first 后再选择替换位。

## 5. 一致性接口

L1D 与 L2/Home 之间不要继续扩展 Wishbone 私有信号，应定义项目自己的 ready/valid channel。建议四条逻辑通道：

### 5.1 Request：L1D → Home

| opcode | 含义 |
|---|---|
| `GetS` | 请求可读共享副本；load/LR miss |
| `GetM` | 请求可写唯一副本；store/SC/AMO miss 或 S upgrade |
| `PutS` | clean S/E line 主动释放，不带数据 |
| `PutM` | M line 主动释放，携带最新整行数据 |

字段：`valid/ready, opcode, srcHart, txnId, lineAddr, lineData, byteMask`。第一版虽只有一个全局事务，也保留小 `txnId` 便于断言、波形和以后扩展。

### 5.2 Grant：Home → L1D

字段：`valid/ready, dstHart, txnId, lineAddr, grantState(S/E/M), hasData, lineData, error`。

- miss 返回 `hasData=1`；
- S→M upgrade 可以 `hasData=0`；
- requester 必须在 Grant handshake 后才安装权限或向 CPU 报完成。

### 5.3 Probe：Home → L1D

| opcode | 动作 |
|---|---|
| `ProbeInv` | S/E→I；M 需带最新数据返回后→I |
| `ProbeToS` | E/M→S；M 需返回最新数据 |
| `ProbeRecallInv` | owner 返回数据并失效；用于 owner transfer/L2 eviction |

字段：`valid/ready, dstHart, txnId, lineAddr, opcode`。

### 5.4 Probe response：L1D → Home

字段：`valid/ready, srcHart, txnId, lineAddr, ack, hasData, lineData`。

L1D 必须能在 CPU 请求与 probe 同周期到来时同时锁存二者，随后 probe 优先。可用一项 `cpuPending` 和一项 `probePending` 保留当前 CPU pulse 约定；不能因为选了 probe 而丢掉 CPU 的单周期请求。

## 6. L1D 组相联结构与状态机

### 6.1 数组

```text
tagArray  : sets × ways × tag
stateArray: sets × ways × MESI
dataArray : sets × ways × lineBits，支持 byte write mask
replArray : sets × replacement bits
```

推荐使用同步 SRAM 语义：`LookupRead` 发 set index，下一拍 `Compare` 取得所有 way 的 tag/state/data，W 路并行比较后选择 hit way。

### 6.2 CPU 主 FSM

```text
Idle
  -> LookupRead
  -> Compare
     -> LoadHitResp
     -> StoreHitWrite                 (M/E hit；E→M 静默)
     -> UpgradeReq/WaitGrant          (S hit)
     -> VictimReleaseReq/WaitAck      (miss victim S/E/M)
     -> MissReq/WaitGrant             (GetS/GetM)
  -> RefillWrite
  -> Respond
```

规则：

- hit 条件是 tag match 且 state != I。
- victim 优先 I；S/E 用 `PutS` 释放，M 用 `PutM` 释放。
- store 在真正写 Data SRAM 且 line 已拥有 M 后才向 CPU ack。
- 下级 request 必须保持 `valid + payload` 稳定直到 `ready`，不能沿用“无条件下一拍认为对方收到了”的 pulse 假设。

### 6.3 Probe FSM

```text
ProbePending
  -> ProbeLookupRead
  -> ProbeCompare
     -> miss/I: Ack
     -> S/E + Inv: state=I, Ack
     -> E + ToS: state=S, Ack
     -> M + ToS: AckData(latest), state=S
     -> M + Inv: AckData(latest), state=I
```

普通 CPU miss 请求尚未与 Home handshake 时，允许 probe 抢占并在处理后恢复原请求。这样可避免“某 L1 等待仲裁，同时 Home 等它响应 probe”的环形等待。

## 7. L2/Home 数据与目录

L2 每行建议保存：

```text
tag
valid
dirtyToMemory
dirState = NONE | SHARED | UNIQUE
sharers[numHarts-1:0]
ownerId
lineData
```

不要把 L2 目录状态直接写成 MESI：L1 owner 获得 E 后可以静默 E→M，因此 `UNIQUE` 表示唯一 L1D 是权威副本，但 Home 不能假设 L2 data 一定最新。

稳定状态不变量：

- `NONE`：没有 L1D 副本，L2 data 最新。
- `SHARED`：一个或多个 L1D 持有 S，L2 data 最新，`ownerId` 无效。
- `UNIQUE`：恰好一个 owner 持有 E 或 M，owner data 是权威副本，L2 data 可能旧。
- 任意稳定状态下，同一 line 最多一个 M/E owner。

## 8. L2/Home 第一版事务 FSM

```text
Idle/Arbitrate
  -> LookupRead
  -> Compare
     -> SendProbe(s)
     -> WaitProbeAck/Data
     -> VictimRecall
     -> MemoryWriteback
     -> MemoryRefill
  -> UpdateArrays
  -> SendGrant/Ack
  -> Idle
```

事务上下文必须寄存：`requester, opcode, txnId, lineAddr, selectedWay, victim metadata, pendingAckBitmap, recalledData, targetGrant`。

核心转移：

| 请求 | 目录状态 | 动作 |
|---|---|---|
| GetS | NONE | 返回 L2 data；MSI 阶段授予 S，MESI 阶段可授予 E |
| GetS | SHARED | 返回 L2 data；加入 requester sharer |
| GetS | UNIQUE/other owner | `ProbeToS` owner，必要时收数据；旧 owner 和 requester 都为 S |
| GetM | NONE | 授予 requester M，目录→UNIQUE |
| GetM | SHARED | invalidate 其他 sharers，等全部 Ack，再授予 M |
| GetM | UNIQUE/other owner | recall+invalidate 旧 owner，收最新数据，再转给新 owner M |
| PutS | SHARED/UNIQUE clean | 清 requester bit/owner；不改数据 |
| PutM | UNIQUE owner | 用返回数据更新 L2，`dirtyToMemory=1`，清 owner |

L2 replacement：

- victim `SHARED`：invalidate 所有 D$ sharer并等 Ack；
- victim `UNIQUE`：从 owner recall 最新数据并使其 I；
- 若 `dirtyToMemory=1` 或 recall 得到修改数据，再写回 Memory；
- 完成这些动作前不能覆盖 victim tag/data/directory。

## 9. I$ 与 FENCE.I

### 9.1 I$ 平时如何取数

I$ 本体保留现有只读 line request/response。多核顶层给请求附加 `srcHart`，转换为 `GetInstr`：

- 目录 NONE/SHARED：L2 data 是最新，直接响应；
- 目录 UNIQUE：先向 D$ owner 发 `ProbeToS` 并收回最新数据，再响应 I$；
- I$ 副本不加入 D$ sharer bitmap，也不接收 Home probe。

这样做的前提是软件遵守修改代码协议；I$ 可以在软件执行 `FENCE.I` 前继续看到旧指令。

### 9.2 本地 FENCE.I

当前 Flow 已实现“D$ 全 flush 完成后，frontend redirect + I$ invalidate”。第一版多核继续使用这条保守路径：

1. 停止发射新指令，等待当前访存完成；
2. D$ 逐 set/way 扫描；S/E 发 `PutS`，M 发 `PutM`，等待 Home Ack 后置 I；
3. D$ 全部完成后给前端 `cacheFlush`；
4. I$ 清 valid，fetch buffer/流水线清空，从 `fence.i PC+4` 重取；
5. `FENCE.I` 退休。

它比规范要求更重，但与现有接口最接近，也容易验证。后续再拆为 D$ clean-only、按地址范围维护等优化。

### 9.3 跨 Hart 修改代码

推荐软件顺序：

```text
writer: 写代码 -> data fence/发布 -> 写目标 hart 的 MSIP=1
target: 进入 software-interrupt handler -> fence.i -> MSIP=0 -> 返回
writer: 如需同步完成，再等待 target 的完成标志
```

`FENCE.I` 只同步执行它的本地 Hart，IPI 只是通知手段，不会自动刷新 I$。

## 10. RV64A 流水线接口

保留 `BackendMemReq` 现有字段，并追加：

```text
memOp    = Load | Store | LR | SC | AMO
amoFunc  = Swap | Add | Xor | And | Or | Min | Max | MinU | MaxU
aq       : Bool
rl       : Bool
```

响应继续使用 `data`：

- LR：返回 load value；
- SC：成功返回 0，失败返回 1；
- AMO：返回修改前的 old value；
- 普通 store 仍通过 `isWriteAck` 表示完成。

Backend 在原子请求发出到响应返回期间冻结相关流水级。只有完整实现并通过测试后，才把 `misa.A` 置 1，并把软件 ISA 改为 `rv64ima_zicsr_zifencei`。

异常分类：

- LR.W/D 未对齐或访问失败：load address-misaligned/access-fault（4/5）；
- SC.W/D、AMO.W/D 未对齐或访问失败：store/AMO address-misaligned/access-fault（6/7）；
- 第一版禁止对 MMIO/device/non-atomic PMA region 执行 LR/SC/AMO，返回 access fault。

## 11. Reservation 单元

每个私有 L1D 内一组寄存器即可：

```text
resValid
resAddr       // 自然对齐的 W/D 地址
resLineAddr
resSize       // W 或 D
```

### 11.1 LR

按普通 load/GetS 流程执行。只有访问成功、数据即将返回时才设置 reservation。LR.W 返回值符号扩展到 RV64。

### 11.2 清 reservation 的事件

- 本 L1D 收到针对 reservation line 的 invalidate、downgrade 或 recall；
- reservation line 被本地替换/flush；
- 本 Hart 的成功 store、SC 或 AMO；第一版也可对任意本地 store 保守清除；
- trap、reset；为简化上下文切换也可在 `mret` 前由软件用 dummy SC 清除。

外部 agent 若能写 cached RAM，也必须经过 Home 或显式 cache-maintenance；否则它的写不会触发 probe，LR/SC 和数据一致性都会失效。

### 11.3 SC

1. 先完成地址对齐与 PMA/权限检查；
2. 检查 `resValid + address + size`；不匹配则不发 GetM、不写 cache，返回 1；
3. 匹配则获取 M；等待期间若 probe 清掉 reservation，重新检查并失败；
4. 已有 M 且 reservation 仍有效时写 Data SRAM，这一拍是 SC 成功提交点；
5. 写完成后返回 0；无论成功失败都清 reservation。

不能在 GetM/写 SRAM 之前向流水线返回 SC 成功。

## 12. AMO ALU 与原子状态机

AMO ALU 放在 L1D 内，输入 `oldOperand, rs2, amoFunc, isWord`，输出 `newOperand`。支持：

`AMOSWAP/ADD/XOR/AND/OR/MIN/MAX/MINU/MAXU.W/D`。

流程：

```text
AmoLookup
  -> EnsureM               // M hit；E→M；S/miss 发 GetM
  -> AmoRead               // 锁存 old value
  -> AmoExec               // new = f(old, rs2)
  -> AmoWrite              // byte mask 写回 Data SRAM，state=M
  -> AmoRespond            // rd = old value
```

规则：

- AMO.W 只运算地址选择的 32 bits，old value 对 rd 符号扩展；AMO.D 运算 64 bits。
- 从获得 M 到 `AmoWrite` 完成期间设置 `atomicLock(lineAddr)`。
- 同 line probe 暂存在 `probePending`，在 `AmoWrite` 后用最新数据响应；锁定必须有固定、很短的上界，不能无限阻塞 probe。
- 不同 line probe 第一版也可以等 AMO 完成，因为整个 L1D 是 blocking；这影响性能，不影响正确性。
- AMO 的原子性证明点是：Home 已撤销其他 D$ 副本、当前 Hart 独占 M、RMW 中间不响应交权 probe。

## 13. aq/rl 与 FENCE

当前是严格顺序、单 outstanding、无 store buffer 的流水线，因此保守实现即可：

- `rl=1`：原子请求开始前，确认本 Hart 之前的 cached/uncached 事务均已完成；
- `aq=1`：原子响应并退休前，不允许后续访存请求发出；
- `aq=rl=1`：同时满足两者；
- 即使当前大部分约束由 blocking pipeline 天然满足，也保留显式 barrier 状态和断言，避免以后加入 buffer/MSHR 后语义悄悄失效。

普通 `FENCE` 当前被当成 NOP，只在“所有内存事务严格按序且没有隐藏写队列”时成立。一旦引入 writeback queue、store buffer、多个 outstanding 或独立 cached/MMIO 队列，就必须重新实现 pred/succ 排序，不能沿用 NOP。

## 14. CLINT/IPI：放在 LiteX 的具体方式

当前平台已把 `0x0200_0000..0x0200_ffff` 声明为不可缓存 device，并实现单 Hart `mtime/mtimecmp`。应把它扩成 `BreezeClint(numHarts)`：

| 寄存器 | 地址 | 宽度/语义 |
|---|---:|---|
| `msip[h]` | `0x0200_0000 + 4*h` | 32-bit RW，仅 bit0 有效 |
| `mtimecmp[h]` | `0x0200_4000 + 8*h` | 64-bit RW，产生 `mtip[h]` |
| `mtime` | `0x0200_bff8` | 64-bit shared counter |

这是 Rocket/经典 CLINT 兼容布局，不是 base ISA 强制的唯一物理地址；Flow 选择它是为了软件兼容和复用已有地址图。

64-bit Wishbone 的注意点：两个 32-bit `msip` 可能位于同一个 64-bit bus word。slave 必须结合 `bus.sel[7:0]` 更新对应半字，不能只按 64-bit word address 区分 Hart。未选中的 byte lane 和其他 Hart 的 MSIP 必须保持不变。

连接：

```text
clint.msip[h] -> tile[h].machineSoftwareInterrupt
clint.mtip[h] -> tile[h].machineTimerInterrupt
```

CSRFile 增加：

- `mip.MSIP` bit 3 只读反映输入；
- `mie.MSIE` bit 3 可写；
- software interrupt cause = 3；
- `mhartid` 改为每 Tile 的只读参数/常量，不再硬编码 0。

LiteX 中用 `self.bus.add_slave(...)` 注册 CLINT；任何通过 `self.bus.add_master(...)` 或 SoC 已有共享 master 接入该 interconnect 的设备，都能写 CLINT。这里允许“其他 master 写”的是 CLINT MMIO；若另一个 master 写 cached RAM，必须通过 Home 的 coherent-I/O 入口，或由软件先做完整 cache maintenance。

## 15. 多核启动与外部中断边界

- 所有 Hart 可从同一 reset vector 启动，启动代码读取 `mhartid`；Hart0 初始化系统，其他 Hart 进入等待 IPI 的 parking loop。
- 第一版 IPI 和 per-Hart timer 都完整连接。
- 当前外部中断只是 8 路 OR-reduce 成单个 MEIP，没有 PLIC context。双核 bring-up 阶段可明确只送 Hart0，其他 Hart 置 0；不要把它描述成完整 SMP 外部中断支持。
- 后续运行 OpenSBI/Linux SMP 时需要 PLIC/AIA、S-mode、delegation、Sv39 等另外的系统工作；CLINT/IPI 只是其中一部分。

## 16. 文件级实施映射

### 保留/兼容

- `design/src/main/scala/top/BreezeCoreWishbone.scala`：保留单核顶层。
- `design/src/main/scala/cache/BreezeCache.scala`：I$ 本体与 flush 端口尽量不改。
- `design/src/main/scala/bus/DCacheWishboneBridge.scala`：可继续用于单核旧顶层或改作 L2 memory-side bridge，不再作为多核 L1D cached 下游。

### 修改

- `design/src/main/scala/config/config.scala`：D$ `sets/ways/lineBytes`、L2 和 `numHarts` 参数。
- `design/src/main/scala/interface/interface.scala`：atomic sideband、coherence 四通道、per-Hart interrupt。
- `design/src/main/scala/cache/BreezeDCache.scala`：组相联 SRAM、MESI/probe、reservation、AMO FSM、cached/uncached 分流。
- `design/src/main/scala/core/InstDecode.scala`：完整 AMO opcode/funct5、W/D、aq/rl decode。
- `design/src/main/scala/backend/BreezeBackend.scala`：atomic 请求/返回、对齐异常、pipeline hold、SC/AMO 写回。
- `design/src/main/scala/core/RegFile.scala`：`misa.A`、`mhartid`、MSIP/MSIE/cause 3。
- `config/breeze_mcu_platform.json`：把 machine-timer 描述扩展为参数化 CLINT，地址保持不变。
- `litex_wrapper/flow/core.py`：增加 cluster variant 和 per-Hart interrupt wiring。
- `sim/litex/breeze_sim.py`：实例化 CLINT、多核 cluster、MMIO/master 连接和测试监视器。

### 建议新增

- `design/src/main/scala/cache/Coherence.scala`
- `design/src/main/scala/cache/BreezeL2Home.scala`
- `design/src/main/scala/cache/BreezeAmoAlu.scala`
- `design/src/main/scala/top/BreezeHartTile.scala`
- `design/src/main/scala/top/BreezeMulticoreClusterWishbone.scala`
- `litex_wrapper/flow/clint.py`（验证完成后替代或兼容导出旧 `machine_timer.py`）

## 17. 分阶段实现与硬门槛

不要一次同时改 D$、L2、MESI、A、CLINT 和多核。每阶段独立保留 PASS 证据：

### P0：冻结接口和不变量

- coherence Bundle、目录字段、错误/背压、MMIO 边界、同 line 串行规则定稿；
- Chisel elaboration 和接口单测通过；
- 尚不声称有 L2/MESI 功能。

### P1：单核组相联 L1D

- 4 KiB/2-way/32 B，blocking，仍接旧 memory bridge；
- load/store hit/miss、dirty eviction、MMIO、flush、error 全部回归；
- 与当前 D$ 定向测试的 architectural result 一致。

### P2：单核 L2/Home

- L1D cached 请求改接 L2；I$ refill 改接 L2 read port；
- 验证 L2 hit/miss、L1/L2 replacement、dirty RAM writeback；
- 仍只有 Hart0，不声称多核一致性。

### P3：双核 MSI

- GetS/GetM/PutS/PutM、ProbeInv/ToS/AckData 全闭环；
- 强制 L2 单事务；
- 通过两核 read-sharing、write-invalidate、dirty-owner read/transfer、同 line 不同 word、L1/L2 eviction。

### P4：MESI E 状态

- NONE 上首个 GetS 可授予 E；E→M 静默；
- Home 的 UNIQUE 永远保守认为 owner data 可能更新；
- 重新跑 P3 全套并增加 E silent-upgrade 断言。

### P5：LR/SC

- reservation set/clear、SC 成功/失败、probe 破坏 reservation；
- 两核争用同一地址和同一 line 不同 word；
- 先通过 directed test，再做受约束随机测试。

### P6：AMO + aq/rl

- 18 个 W/D AMO 组合（9 op × 2 width）；
- signed/unsigned MIN/MAX、AMO.W 符号扩展、返回 old value；
- 双核循环 AMOADD 最终和精确，无丢失更新。

### P7：CLINT/IPI + remote FENCE.I

- 每 Hart MSIP/MTIMECMP 独立；64-bit Wishbone byte lane 测试；
- Hart0 写 Hart1 MSIP，Hart1 cause=3 进入 handler 并清零；
- 代码修改 + 发布 + IPI + Hart1 FENCE.I 后取到新指令。

## 18. 必备协议断言

- 同一 line 稳定态最多一个 M/E owner。
- SHARED 状态没有 owner，所有有效 D$ 副本只能是 S。
- Home 未收齐 `pendingAckBitmap` 前不得发 GrantM。
- UNIQUE 旧 owner 未 AckData/InvAck 前不得把 M 授给新 owner。
- dirty victim 未成功 PutM/写回前不得覆盖。
- AMO `atomicLock` 期间不得交出目标 line。
- SC 返回 0 当拍之前必须已经完成真正的 cache write。
- reservation line 收到 invalidate/recall 后 `resValid` 必须清零。
- FENCE.I 未完成 D$ release/clean 前不得触发 I$ invalidate 完成信号。
- 任一 ready/valid 通道在 `valid && !ready` 时 payload 必须保持稳定。

## 19. 当前方案的明确非目标

- 不在第一版加入多 MSHR、non-blocking cache、store buffer、victim cache、prefetch。
- 不让 I$ 参加 MESI，也不让 L2 广播 I$ invalidate。
- 不允许 DMA/外部 master 绕过 Home 修改 cached RAM 后仍声称 coherent。
- 不在 MSI 未通过前优化成 MESI/MOESI/MESIF。
- 不把 CLINT/IPI 等同于完整 Linux SMP；PLIC、S-mode、Sv39、SBI 等另行实现。

## 20. 对原 Word 方案的最终收敛

原方案的主体方向是对的，以下四点作为实现时的最终解释：

1. “cache 里加 ALU”：具体是每 Hart 的 **L1D controller 内放小型 AMO ALU/FSM**，且必须先取得 M。
2. “小 reg array 记 reserve”：具体是一组 per-Hart reservation registers；不是每 line 一 bit，也不是多项 CAM。
3. “加核间软件中断，用 Rocket 地址”：具体是在 LiteX SoC 把现有 `0x0200_0000` timer 扩成多 Hart CLINT，MSIP 为 `base+4*hart`。
4. “L2 不向 L1 发 invalid”：只适用于 L1I；L1D 的 invalidate/recall 是 MESI 正确性的必要组成。

这四点冻结后，接口、状态机和验证路线可以直接进入 P0/P1，而不会在做到 A 扩展时再次推翻内存层次。
