# Flow 1/2/4 核缓存一致性与 RV64A 实现 Specification

状态：**本地实现权威，尚未实现/编译/仿真，不进入 Git 提交**

执行环境和 Git/Alan 规则：`docs/multicore-1-2-4-environment-workflow.md`。

## 1. 目标与完成定义

### 1.1 本任务必须完成

从当前 Flow 单核实现出发，在同一套参数化源码中实现并验证：

```text
single = 1 Hart
dual   = 2 Harts
small  = 4 Harts
```

最终每个 Hart 包含：

```text
RV64IMA_Zicsr_Zifencei 顺序核
private 8 KiB、4-way、32 B line L1I
private 8 KiB、4-way、32 B line L1D
per-Hart reservation registers
L1D 内 AMO ALU/FSM
per-Hart MSIP/MTIP 输入和唯一 mhartid
```

所有 Hart 共享：

```text
single-bank、blocking、directory-based MESI L2/Home
共享 64-bit mtime
参数化 CLINT 的 msip[h]/mtimecmp[h]
LiteX RAM/MMIO
```

完成不等于“代码写完”。完成必须同时包含：

- single/dual/small 三档均能生成正确 RTL；
- 当前单核历史回归不退化；
- Chisel 定向测试和全量测试通过；
- Alan 上三档 LiteX/Verilator 仿真均通过；
- 多核共享、权限转移、LR/SC、AMO、IPI 和 remote FENCE.I 有明确 oracle；
- feature branch 和证据齐全，停在等待 Codex/用户审核。

### 1.2 本任务明确不做

- 不实现或宣称支持 8 核、16 核；
- 不实现 banked L2；
- 不实现 MSHR、多个 outstanding、hit-under-miss、store buffer、prefetch；
- 不实现 DMA、外部 coherent master 或其他外部 memory master；
- 不实现 PLIC/AIA、S-mode、U-mode、Sv39、OpenSBI 或 Linux；
- 不让 I$ 参加 MESI；
- 不实现 MOESI/MESIF/CHI；
- 不为多核展开 baseline/GShare 全组合矩阵；多核主矩阵只要求默认 GShare，baseline 只做显式兼容回归；
- 不做 IPC/Fmax/PPA 优化；
- 不修改需求来迎合失败测试。

## 2. 权威优先级

发生冲突时按以下顺序处理：

1. 本 specification；
2. `docs/multicore-1-2-4-environment-workflow.md`；
3. 当前实际 RTL/测试接口；
4. RISC-V ISA/Privileged Architecture 规范；
5. 旧 Word、旧讨论稿和图稿。

旧文档中的以下内容已被本 specification 替代：

- 8/16 核是当前实现目标；
- L1D 为 4 KiB/2-way；
- L2 容量等于 `numHarts × L1D`；
- 可以增加外部 master；
- L2 为普通 cache、以后再补 coherence；
- 直接一次做到 MESI/A 而不经过 MSI 验证阶段。

## 3. 冻结配置

### 3.1 L1 几何

L1I 和 L1D 的容量与几何完全一致：

```text
capacity  = 8192 bytes
lineBytes = 32
ways      = 4
sets      = 8192 / 32 / 4 = 64
```

当前 I$ 已是这一几何；不得为了“统一”缩小或重新实现已经工作的 I$。允许把几何参数抽到统一配置，但不得无理由改动 I$ 命中/refill 时序。

L1D 从当前：

```text
256 B = 8 entries × 32 B，fully associative，Reg Array
```

替换为：

```text
8 KiB = 64 sets × 4 ways × 32 B，set associative
```

### 3.2 L2 容量公式

冻结公式：

```text
L2 data bytes = numHarts × 2 × L1D bytes
              = numHarts × (L1I bytes + L1D bytes)
              = numHarts × 16384 bytes
```

L2 固定：

```text
lineBytes = 32
ways      = 8
banks     = 1
global outstanding coherence transaction = 1
```

配置表：

| Profile | Harts | L2 data | L2 lines | L2 sets（8-way） |
|---|---:|---:|---:|---:|
| `single` | 1 | 16 KiB | 512 | 64 |
| `dual` | 2 | 32 KiB | 1024 | 128 |
| `small` | 4 | 64 KiB | 2048 | 256 |

不得偷偷把某一档写死为不同公式。所有容量、sets、index width 都必须从参数推导，并由 elaboration-time `require` 检查。

只看 data SRAM，L1I+L1D+L2 的合计分别是：single=32 KiB、dual=64 KiB、small=128 KiB。按32-bit physical tag和本 specification 的 MESI/directory 字段估算，三档逻辑 tag/state/PLRU 约为3.2/6.4/13.3 KiB，因此 small 约为141.3 KiB 逻辑 cache 存储；这不含 SRAM 宏取整、ECC、端口复制和控制寄存器。

仅用于未来容量预估：若以后沿用同一公式，8核 L2 data=128 KiB、全芯片 cache data=256 KiB；16核 L2 data=256 KiB、全芯片 cache data=512 KiB。它们不是本版可选择或可宣传的配置。

### 3.3 Profile API

建议定义独立于现有 branch-predictor preset 的 cluster 配置：

```scala
final case class L1CacheGeometry(
  capacityBytes: Int = 8192,
  lineBytes: Int = 32,
  ways: Int = 4
)

final case class L2CacheGeometry(
  capacityBytes: Int,
  lineBytes: Int = 32,
  ways: Int = 8,
  banks: Int = 1
)

final case class BreezeClusterConfig(
  profileName: String,
  numHarts: Int,
  l1i: L1CacheGeometry,
  l1d: L1CacheGeometry,
  l2: L2CacheGeometry,
  corePreset: String = "gshare"
)
```

唯一公开 preset：

```scala
BreezeClusterPresets.single
BreezeClusterPresets.dual
BreezeClusterPresets.small
```

三种公开 preset 的 `corePreset` 都必须是 `gshare`。`baseline` 配置继续保留，供显式调试和兼容回归使用，但任何无 predictor 参数的 generator、LiteX wrapper 或 runner 都不得默认选择 baseline。

必须检查：

```text
numHarts ∈ {1, 2, 4}
L1I capacity/ways/lineBytes == L1D capacity/ways/lineBytes
L2 capacity == numHarts × 2 × L1D capacity
所有 capacity、sets、ways、lineBytes 是合法2次幂关系
所有层 lineBytes 相同
banks == 1
hartIdWidth = max(1, ceil(log2(numHarts)))
sharerWidth = numHarts
```

不得在当前 parser/preset 中接受 `standard=8` 或 `max=16`。可以在注释中说明未来名称，但当前请求必须明确失败，避免误称支持。

### 3.4 分支预测默认值

冻结规则：GShare 是今后所有正常生成与仿真入口的默认分支预测器；baseline（无分支预测器）保留，但只能显式选择。

P0 必须统一修改并测试以下默认入口：

```text
BreezeCoreConfig/BreezeFrontendConfig/BackendConfig 的无参默认值
BreezeCoreWishbone 默认 corecfg
GenerateBreezeCoreWishbone 无参数默认 preset
LiteX Flow CPU wrapper 的 core_preset
breeze_sim.py / run_mcu.py 的 --core-preset 默认值
BreezeCoreSimSupport 的默认 coreCfg
新增 cluster generator/wrapper/run_multicore.py 默认值
仍在使用的 Makefile/测试 launcher 默认 CORE_PRESET
```

统一结果：

```text
无 predictor 参数       -> gshare
--core-preset gshare     -> gshare
--core-preset baseline   -> baseline
```

继续复用当前 `BreezeCoreConfigs.gshare()` 的已验证参数（当前为 GHR=8、BTB=16）；本任务不顺带调参。`BreezeCoreConfigs.baseline()` 和显式 baseline RTL 输出目录必须保留，便于差分和退化定位。

改动默认值后，任何语义上专门测试“无预测器”的旧用例都要显式传 `baseline`/`useGShare=false`，不能依赖旧的隐式默认；普通用例则接受新的 GShare 默认。配置单元测试必须断言原始 case class 默认、公开 preset、生成器默认和 Python CLI 默认四层一致，防止只有一层被改。

## 4. 地址与缓存索引

架构地址仍为 RV64 64-bit。当前 SoC implemented physical address width 为 32 bit。

规则：

- Backend 保留 64-bit 原始字节地址，用于 `mtval`；
- PMA 在 cache/bus 请求前检查地址；
- 超出实现物理地址宽度或不在 region 的访问不能截断后继续；
- L1D/L2 tag 可以使用已验证的 32-bit physical address；
- cache line 地址统一清零低5位。

L1D：

```text
byte/line offset = addr[4:0]
set index        = addr[10:5]
tag              = physical addr[31:11]
```

L2 set/index 必须由 profile 推导，禁止固定切片：

```text
single: sets=64,  index bits=6
dual:   sets=128, index bits=7
small:  sets=256, index bits=8
```

## 5. 总体拓扑

```text
Tile[0] ... Tile[N-1]
  ├─ Core(hartId)
  ├─ private L1I 8K/4-way
  └─ private L1D 8K/4-way/MESI
       ├─ CPU request path
       ├─ coherence request/grant
       ├─ probe/probe-response
       ├─ reservation
       └─ AMO ALU/FSM

all L1I read requests ─┐
all L1D coherence req ─┼─> Shared L2/Home ─> memory Wishbone ─> RAM
all L1D probe rsp     ─┘

all per-Hart uncached/MMIO req
        └─> blocking round-robin MMIO arbiter
              └─> cluster MMIO Wishbone ─> CLINT/UART/GPIO
```

没有额外 external master。所谓 memory/MMIO master 只包括 cluster 内部汇聚出的 L2 memory master 和 per-Hart MMIO arbiter master。

## 6. 顶层兼容与新顶层

### 6.1 保留现有单核入口

必须保留：

```text
flow.top.BreezeCoreWishbone
flow.top.GenerateBreezeCoreWishbone
```

当前单核 baseline/gshare 回归仍须工作。P0 将面向用户的无参数默认值从 baseline 切到 GShare，但显式 `baseline` 选择必须继续工作；不得迫使所有历史测试立即改用 cluster 顶层。

### 6.2 新增 cluster 顶层

建议新增：

```text
flow.top.BreezeHartTile
flow.top.BreezeMulticoreClusterWishbone
flow.top.GenerateBreezeMulticoreClusterWishbone
```

generator CLI 冻结为：

```bash
sbt 'runMain flow.top.GenerateBreezeMulticoreClusterWishbone single'
sbt 'runMain flow.top.GenerateBreezeMulticoreClusterWishbone dual'
sbt 'runMain flow.top.GenerateBreezeMulticoreClusterWishbone small'
sbt 'runMain flow.top.GenerateBreezeMulticoreClusterWishbone single baseline'
```

第二个参数可选，取值 `gshare|baseline`，默认 `gshare`。前三条生成 GShare，最后一条仅用于 baseline 兼容调试。错误 profile/preset 必须非零退出。

输出目录不得互相覆盖：

```text
design/build/rtl/cluster/single/gshare/
design/build/rtl/cluster/dual/gshare/
design/build/rtl/cluster/small/gshare/
design/build/rtl/cluster/single/baseline/   // 仅兼容回归
```

每个目录必须含：

```text
BreezeMulticoreClusterWishbone.sv
filelist.f
cluster-profile.txt
```

`cluster-profile.txt` 至少记录：

```text
profile
numHarts
l1iBytes/l1dBytes/l2Bytes
lineBytes
l1Ways/l2Ways
corePreset
```

LiteX wrapper 必须校验 marker 与 CLI profile 一致，不能加载旧 profile RTL。

### 6.3 保留 L1 的 CPU-facing 接口

用户要求的“cache 保留原接口”冻结为：现有 Core↔L1I、Core↔L1D 的地址、数据、size、exception、flush 和一次请求/一次响应语义保持兼容；A 扩展只在现有 D$ 请求旁增加明确的 atomic opcode/字段，不把 CPU 端改造成新总线。MESI request/grant/probe 是 L1D 下侧新增的内部 sideband，由 Tile/legacy adapter 接走。现有单核顶层继续通过 adapter 使用旧 memory Wishbone 行为，旧测试不应因多核接口重写。

## 7. L1D 组相联实现

### 7.1 数组

推荐结构：

```text
tagArray   : 64 sets × 4 ways × tag
dataArray  : 64 sets × 4 ways × 256 bits
mesiArray  : 64 sets × 4 ways × 2 bits
plruArray  : 64 sets × 3 bits
```

Tag/Data 使用同步读语义。MESI/valid 可以用可复位 metadata regs，或使用 SRAM 加独立有效位；reset 后所有 line 必须逻辑上为 I，不能依赖未初始化 SRAM 内容。

Store hit 可以在 Compare 拿到整行后进行 full-line merge write；不强制要求物理 byte-write SRAM，但 byte mask 语义必须正确。

### 7.2 命中与 victim

```text
hit = tag match && MESI != I
```

victim 规则：

1. 在目标 set 的4个 way 中优先第一个 I；
2. 无 I 时使用该 set 的 tree-PLRU；
3. 不得跨 set 选择 victim；
4. 只有成功安装新 line 后才更新 PLRU；
5. 普通 hit 也按确定规则更新 PLRU；
6. probe miss 不更新 PLRU。

### 7.3 保留的现有语义

- blocking：一个 CPU 内存操作；
- write-back + write-allocate；
- PMA-denied 产生 access fault，不访问下级；
- device/non-cacheable 走标量 MMIO bypass；
- dirty eviction 失败时保留 valid+dirty/M，不能丢数据；
- FENCE.I flush writeback 失败进入 sticky fatal，不能静默丢已退休 store；
- load/store 原始地址、size、byte mask、`mtval` 语义不变；
- HPM dcache access/miss/uncached 计数定义不变。

### 7.4 CPU pulse 与 probe 并发

现有 Core→D$ 普通请求保持一次 pulse/一次 response 语义。新增 probe 后必须防止同周期丢请求：

```text
cpuPending   : 1 entry
probePending : 1 entry
```

- CPU pulse 到达且 `cpuPending` 空时必须锁存；
- probe ready 只在 `probePending` 可接收时拉高；
- CPU 与 probe 同周期到达时两者都锁存，probe 优先；
- 若已有 CPU 操作正在执行，Backend 不应发第二个 CPU pulse；用 assertion 检查；
- 若 probe 到达时本地 coherence request 尚未被 Home handshake，可暂停本地请求、先处理 probe、再恢复；
- ready/valid channel 在 backpressure 时 payload 必须稳定。

## 8. 一致性通道

L1D 与 Home 之间使用项目自有 ready/valid 接口，不使用 Wishbone 扩展信号。

### 8.1 Request：L1D → Home

```text
GetS
GetM
PutS
PutM
```

字段：

```text
valid/ready
opcode
srcHart
txnId
lineAddr
hasData
lineData[255:0]
```

### 8.2 Grant：Home → L1D

字段：

```text
valid/ready
dstHart
txnId
lineAddr
grantState = S | E | M
hasData
lineData
error
```

S→M upgrade 可不带数据；miss grant 必须带完整 line。

### 8.3 Probe：Home → L1D

```text
ProbeInv
ProbeToS
ProbeRecallInv
```

字段：

```text
valid/ready
dstHart
txnId
lineAddr
opcode
```

### 8.4 Probe response：L1D → Home

字段：

```text
valid/ready
srcHart
txnId
lineAddr
ack
hasData
lineData
```

第一版即使全局只有一个 transaction，也必须保留 `txnId`，用于断言、波形和防止旧 response 被误接收。

## 9. L1D MESI 稳定状态

| 当前状态 | CPU Load | CPU Store | ProbeInv | ProbeToS |
|---|---|---|---|---|
| I | GetS | GetM | Ack | Ack |
| S | hit | GetM upgrade | →I, Ack | 保持S, Ack |
| E | hit | 静默→M并写 | →I, Ack | →S, Ack |
| M | hit | 本地写 | AckData后→I | AckData后→S |

替换：

```text
I: 可覆盖
S/E: PutS，Home Ack 后覆盖
M: PutM(data)，Home Ack 后覆盖
```

不得在 Put/Probe response handshake 完成前覆盖 tag/data/state。

## 10. L2/Home 结构

### 10.1 每行字段

```text
tag
valid
dirtyToMemory
dirState = NONE | SHARED | UNIQUE
sharers[numHarts-1:0]
ownerId[hartIdWidth-1:0]
lineData[255:0]
```

`dirState` 不是 L1 MESI：

- NONE：无 L1D coherent copy；L2 data 最新；可能存在未跟踪 I$ copy；
- SHARED：一个或多个 L1D 为 S；L2 data 最新；
- UNIQUE：恰好一个 L1D owner 为 E 或 M；owner data 权威，L2 data 可能旧。

L1 E→M 可以静默，因此 UNIQUE 永远不能假设 L2 data 最新。

### 10.2 全局事务上下文

由于第一版全局单事务，Home 至少寄存：

```text
requesterHart
requestOpcode
txnId
lineAddr
selectedWay
victim tag/data/directory
pendingAckBitmap[numHarts-1:0]
recalledData
targetGrant
memoryError
```

这些寄存器在事务结束前不得被新请求覆盖。

### 10.3 L2 主 FSM

至少覆盖：

```text
IdleArbitrate
LookupRead
Compare
SendProbe
WaitProbeResponse
VictimRecall
MemoryWriteReq/Wait
MemoryReadReq/Wait
UpdateArrays
SendGrant
SendPutAck
FatalOrErrorReturn
```

具体名字可以调整，但行为和等待点不得省略。

## 11. L2 请求规则

### 11.1 GetS

| 目录 | 行为 | 结果 |
|---|---|---|
| NONE | 返回 L2 data；MSI 阶段授予 S，MESI 阶段授予 E | SHARED 或 UNIQUE |
| SHARED | 返回最新 L2 data；加入 requester bit | SHARED |
| UNIQUE，owner=requester | 返回/确认其权限；不得产生第二 owner | UNIQUE |
| UNIQUE，owner≠requester | ProbeToS old owner；response 若带 data 则更新 L2 data；返回 requester S | SHARED |

UNIQUE 不区分 E/M。Home 必须等待 old owner response：若 `hasData=1`，以返回数据覆盖 L2 data 并置 `dirtyToMemory=1`；随后清 owner，并把 old owner 与 requester 都写入 sharer bitmap。若 `hasData=0`，L2 原数据仍是最新值，但原有 `dirtyToMemory` 不得被清除。

### 11.2 GetM

| 目录 | 行为 | 结果 |
|---|---|---|
| NONE | 返回 data/M；requester 成 owner | UNIQUE |
| SHARED | invalidate 除 requester 外全部 sharer，等全部 Ack | UNIQUE/requester M |
| UNIQUE，owner=requester | grant/确认 M | UNIQUE |
| UNIQUE，owner≠requester | recall+invalidate owner，收最新 data，再给 requester M | UNIQUE |

GrantM 只能在 `pendingAckBitmap == 0` 且所需 dirty data 已收回后发出。

### 11.3 PutS/PutM

- PutS 从 sharer bitmap 清除 src；若 UNIQUE clean owner 释放则清 owner；
- PutM 只接受当前 UNIQUE owner；更新 L2 data，置 `dirtyToMemory=1`，清 owner；
- 最后一个 sharer/owner 离开后 dir→NONE；
- source/state 不匹配必须 assertion，不得静默吞掉协议错误。

### 11.4 L2 replacement

- victim NONE：若 dirtyToMemory，先写 Memory；否则可替换；
- victim SHARED：invalidate 全部 sharer、等 Ack，再根据 dirtyToMemory 写回；
- victim UNIQUE：无论 L2 data 看起来是否变化，都 recall+invalidate owner；
- recall 得到 data 后视为最新并置 dirtyToMemory；
- memory write error 时保留 victim 的完整 tag/data/directory，不覆盖；
- refill error 不安装新 line，向原始 requester 返回 error。

## 12. I$ 与统一 L2

### 12.1 I$ 不参加 MESI

- I$ 不在 sharer bitmap 中；
- Home 不向 I$ 发 invalidate/probe；
- L2 eviction 不需要通知 I$；
- I$ 只由本 Hart `FENCE.I` 清 valid 和取指流水。

### 12.2 I$ refill 请求

多核顶层把现有 I$ line request 转成 `GetInstr(srcHart,lineAddr)`，但不修改 I$ 本体协议。

L2 处理：

- L2 hit + NONE/SHARED：直接返回最新 L2 data；
- UNIQUE：ProbeToS D$ owner并等待 response；若带 data 则更新 L2 data、置 `dirtyToMemory=1`，再返回 I$；owner 清除并转为 D$ sharer；
- L2 miss：Memory refill，安装到 L2，dir=NONE，再返回 I$；
- I$ refill 不设置 D$ sharer bit。

因为 L2 容量等于所有 L1I+L1D 数据容量总和，第一版允许 instruction line 在 L2 allocate；但 inclusion 只对 L1D directory 成立。

## 13. FENCE.I

继续采用当前保守但正确的 full flush：

1. Backend 停止后续发射，等待当前访存完成；
2. 当前 Hart L1D 扫描 `64 sets × 4 ways`；
3. S/E 逐行 PutS，M 逐行 PutM；每行等 Home Ack 后置 I；
4. D$ flush 全部完成后才触发 frontend `cacheFlush`；
5. I$ 清 valid、fetch buffer/流水线 flush；
6. 从 `fence.i PC+4` 重取并允许 FENCE.I 退休。

flush 扫描索引必须是 `setIndex + wayIndex`，不能沿用旧 fully-associative 单 entry index 假设。

跨 Hart 修改代码：

```text
writer store code
writer data fence/publish
writer MMIO write target msip=1
target software interrupt handler executes fence.i
target clears own msip
target returns
```

IPI 只是通知；不能把 MSIP 写入本身当作 remote I$ flush 完成。

## 14. RV64A CPU 接口

### 14.1 指令编码

所有原子指令使用 opcode `0101111`（`0x2f`）：

```text
funct3=010 -> .W
funct3=011 -> .D
aq=inst[26]
rl=inst[25]
funct5=inst[31:27]
```

funct5 映射必须精确为：

| Operation | funct5 | 额外约束 |
|---|---|---|
| AMOADD | `00000` | |
| AMOSWAP | `00001` | |
| LR | `00010` | `rs2=0`，否则 illegal instruction |
| SC | `00011` | |
| AMOXOR | `00100` | |
| AMOOR | `01000` | |
| AMOAND | `01100` | |
| AMOMIN | `10000` | signed |
| AMOMAX | `10100` | signed |
| AMOMINU | `11000` | unsigned |
| AMOMAXU | `11100` | unsigned |

其他 funct3/funct5 组合产生 illegal instruction，不得退化成普通 load/store。

### 14.2 Cache 请求与响应

在现有 `BackendMemReq` 普通字段旁增加：

```text
memOp = Load | Store | LR | SC | AMO
amoFunc = Swap | Add | Xor | And | Or | Min | Max | MinU | MaxU
aq
rl
```

普通 load/store 行为保持兼容。响应：

```text
LR  -> data = loaded old value
SC  -> data = 0 success, 1 failure
AMO -> data = old value
```

Backend 从 atomic request 发出直到 response 必须保持相关流水级，不得重复发 request 或重复退休。

只有全部 RV64A 测试通过后才：

- `misa.A=1`；
- 多核测试软件使用 `-march=rv64ima_zicsr_zifencei`；
- README/外部声明称支持 A（但本任务不提交 docs）。

## 15. Reservation

每个 Hart 一组寄存器：

```text
resValid
resAddr       // W/D 自然对齐地址
resLineAddr   // 32 B line
resSize       // W or D
```

第一版 reservation granule 保守取整个 32 B cache line，但 SC 成功仍要求 `resAddr` 和 `resSize` 与 LR 匹配。

### LR

- 按 load/GetS；
- 对齐/PMA/access 成功且即将返回时设置 reservation；
- LR.W old value 符号扩展至 RV64。

### 清除事件

- 针对 reservation line 的 ProbeInv/ToS/Recall；
- reservation line 本地 replacement/flush；
- 任意本地 store/SC/AMO（允许保守清全部 reservation）；
- trap/reset；
- SC 完成，无论成功失败。

### SC

1. 先做对齐和 PMA/access 检查；
2. reservation 不匹配则不写、不发 GetM，返回1；
3. 匹配则取得 M；等待时若 reservation 被 probe 清除，失败；
4. M 权限和 reservation 二次检查都成立后写 SRAM；
5. 这次实际写入完成才是 SC 成功点，随后返回0。

不得提前返回0再等待 GetM。

## 16. AMO

L1D 内新增小型组合 `BreezeAmoAlu` 和多拍 FSM，不复用整颗 CPU ALU。

支持18种宽度组合：

```text
AMOSWAP/ADD/XOR/AND/OR/MIN/MAX/MINU/MAXU.W
AMOSWAP/ADD/XOR/AND/OR/MIN/MAX/MINU/MAXU.D
```

流程：

```text
Lookup
EnsureM
ReadOld
Execute
WriteNew
RespondOld
```

规则：

- M hit 直接继续；E hit 静默 E→M；S/miss 获取 GetM；
- AMO.W 先由 addr[4:3] 选择 line 内64-bit beat，再由 addr[2] 选择该 beat 中的32-bit half；
- W 运算为32 bit，返回 old value 符号扩展；
- MIN/MAX 使用 signed，MINU/MAXU 使用 unsigned；
- 写 Data SRAM 后 line=M/dirty；
- 从获得 M 到 WriteNew 完成设置 `atomicLock(lineAddr)`；
- 同 line probe 暂存，WriteNew 后以最新 data 响应；
- atomicLock 必须是有界几拍，不得等待新外部事件才能释放。

## 17. aq/rl 与普通 FENCE

当前流水线严格顺序、无 store buffer、每 Hart 一个 outstanding：

- `rl=1`：原子操作开始前确认该 Hart 更早 cached/uncached 操作已完成；
- `aq=1`：原子操作完成并退休前不发后续内存操作；
- 显式保留 barrier 控制/断言，不能只注释“天然满足”；
- 普通 FENCE 只有在仍无隐藏队列/多 outstanding 时可保持 NOP 语义；
- 若执行 agent引入任何队列，必须停止并报告需求扩张，不得自行重写内存模型。

## 18. 异常

```text
LR misaligned/access fault  -> load causes 4/5
SC misaligned/access fault  -> store/AMO causes 6/7
AMO misaligned/access fault -> store/AMO causes 6/7
```

- LR/SC/AMO 只允许自然对齐 W/D；
- device/non-cacheable/MMIO region 不支持 atomic，返回 access fault；
- 失败 SC 仍必须完成必要的地址/PMA权限检查后才能退休；
- access fault 不得更新 rd 之外的 cache/reservation architectural state。

## 19. CLINT/IPI

### 19.1 地址

复用现有 `0x0200_0000..0x0200_ffff` device region：

```text
msip[h]     = 0x0200_0000 + 4*h   // 32-bit, bit0 RW
mtimecmp[h] = 0x0200_4000 + 8*h   // 64-bit RW
mtime       = 0x0200_bff8         // 64-bit shared
```

当前 `BreezeMachineTimer` 扩展/替换为参数化 `BreezeClint(numHarts)`。

这个地址必须真正加入 LiteX SoC 的 uncached slave decode 和 `config/breeze_mcu_platform.json`，不能只在 Core 内部放一组不可总线访问的寄存器。当前不实例化第三方 external master；所有 Hart 的内部 MMIO master 经 arbiter 都可读写 CLINT，因此 Hart A 能写 Hart B 的 `msip[B]`。CLINT 保持普通 Wishbone slave 语义，未来若用户另行批准 SoC master，也不需要重做寄存器协议。

64-bit Wishbone 下，`msip[0]` 与 `msip[1]` 共享一个64-bit word；必须使用 `bus.sel` 区分低/高32-bit，不能因写 Hart1 覆盖 Hart0。

### 19.2 Hart 连接

```text
clint.msip[h] -> CSR mip.MSIP
clint.mtip[h] -> CSR mip.MTIP
```

CSR：

- `mie.MSIE` bit3 可写；
- `mip.MSIP` bit3 只读反映输入；
- machine software interrupt cause=3；
- 推荐固定优先级 `MEI > MSI > MTI`；
- `mhartid` 为 Tile elaboration 参数0..N-1，不再是所有核硬编码0。

### 19.3 外部中断边界

当前没有 PLIC。第一版：

- 外部中断只送 Hart0；
- Hart1..N-1 externalInterrupts 置0；
- per-Hart timer/software interrupt完整；
- 不宣称完整 SMP external interrupt。

## 20. 多核启动

所有 Hart 从同一 reset vector 启动。裸机 startup 必须：

1. 读取 mhartid；
2. 为每 Hart 分配不重叠 stack；
3. Hart0 初始化共享 `.data/.bss`，其他 Hart 不重复清零；
4. 使用共享 boot barrier 发布初始化完成；
5. secondary Hart 等待 barrier 后进入测试函数/parking loop；
6. 最终 Hart0 汇总 hart mask 和结果并写 completion mailbox。

禁止多个 Hart 无同步地同时初始化 UART、`.bss` 或完成 mailbox。

## 21. LiteX cluster wrapper

建议新增 Python CPU/cluster adapter，而不是把 N 个 Flow 注册成 N 个 LiteX CPU 对象。一个 cluster RTL instance 对 LiteX 暴露：

```text
resetAddr
memory Wishbone master（L2->RAM）
MMIO Wishbone master（per-Hart arbiter输出）
per-Hart msip/mtip/external interrupt inputs
per-Hart fatal/estop/retire debug（仿真所需）
```

LiteX SoC：

- 继续使用 shared Wishbone interconnect；
- 实例化一个参数化 CLINT slave；
- main RAM 地址保持 `0x8000_0000`；
- CLINT/UART/device region 保持 non-cacheable；
- 没有第三方 external master；
- cluster profile 决定 Hart 数量和 RTL filelist。

## 22. 仿真 CLI 与输出合同

新增统一 runner：

```text
sim/litex/run_multicore.py
```

CLI 必须支持：

```bash
python3 sim/litex/run_multicore.py \
  --profile single|dual|small \
  --test <test-name> \
  [--core-preset gshare|baseline] \
  [--elaborate] \
  [--trace] \
  [--timeout N] \
  [--output-dir PATH]
```

启动时必须打印并由 runner 校验：

```text
BREEZE_CLUSTER profile=<name> harts=<N> core_preset=gshare|baseline l1i_bytes=8192 l1d_bytes=8192 l2_bytes=<value> line_bytes=32 l1_ways=4 l2_ways=8
```

每个测试 PASS marker：

```text
[MULTICORE-<PROFILE>-<TEST>-PASS]
```

FAILED marker：

```text
[MULTICORE-<PROFILE>-<TEST>-FAIL] reason=<...> expected=<...> actual=<...>
```

runner 必须：

- `--core-preset` 默认 `gshare`；未显式指定时 profile marker 必须显示 GShare；
- 有有限 watchdog；
- 子进程非0则自身非0；
- 缺 profile marker/PASS marker 则非0；
- 看到 FAIL/fatal/assertion则非0；
- 输出 firmware SHA、RTL profile marker 和当前 Git short SHA；
- 每个 profile/test 使用独立 output directory；
- 不复用错误 profile 的旧 Verilator binary。

## 23. 必须实现的测试程序

### T0 legacy single-core regression

- 当前 `sbt test`；
- 当前 generic MCU；
- timer direct/vectored；
- uart direct/vectored；
- 无 `--core-preset` 时实际选择 GShare；
- 显式 baseline/gshare 现有回归都不得退化。

### T1 profile/config

对 single/dual/small 检查：

- numHarts=1/2/4；
- hartIdWidth；
- L1I/L1D=8192；
- L2=16384/32768/65536；
- L1 sets=64、ways=4；
- L2 sets=64/128/256、ways=8；
- 三档默认 `corePreset=gshare`，显式 `baseline` 仍可生成且输出目录不与 GShare 覆盖；
- 8/16 profile 被拒绝。

### T2 set-associative D$

- 四个不同 tag、同 set 地址分别填满4 way；
- 第五个同 set 地址按 PLRU 替换正确 victim；
- 不同 set 不互相驱逐；
- invalid-first；
- load/store byte/half/word/dword；
- dirty victim PutM/writeback 前不覆盖；
- refill error；
- MMIO bypass；
- flush 遍历全部256 lines；
- HPM access/miss 事件只计一次。

### T3 single L2

- I$ refill/L1D GetS/GetM；
- L2 hit/miss/refill；
- L1 dirty eviction；
- L2 dirty eviction；
- I$ line allocate 但不进入 sharer bitmap；
- memory error 不破坏 victim。

### T4 dual MSI

- Hart0 Load A，Hart1 Load A：两者S；
- 两者S后 Hart0 Store A：Hart1 InvAck 后 Hart0 M；
- Hart0 M，Hart1 Load：owner AckData/M→S，双方S；
- Hart0 M，Hart1 Store/GetM：owner AckData/M→I，Hart1 M；
- 同 line 不同 word 仍发生 line-granule invalidation；
- 两核同时请求同 line，Home 串行化且都只完成一次。

### T5 small MSI/MESI

- Hart0/1/2/3 全部读同 line，sharer bitmap=`1111`；
- Hart0 GetM，必须等待另外3个 Ack；
- 少一个 Ack 时绝不能 GrantM；
- 4路轮转请求无 starvation（给出有限请求序列）；
- E 首次 GetS、E→M 静默；
- 其他 Hart GetS/GetM 能从 UNIQUE owner 正确 recall；
- inclusive L2 eviction 对4个 sharer全部失效。

### T6 LR/SC

- 每个 profile 至少一个 LR/SC success；
- dual：Hart0 LR，Hart1 Store same address，Hart0 SC fail；
- small：Hart0 LR，Hart3 写 same line different word，按保守 line granule使 SC fail；
- SC fail 不写内存；
- SC success 写一次并返回0；
- trap/flush/probe 清 reservation；
- W/D 对齐与 fault。

### T7 AMO

- `BreezeAmoAlu` 18种 W/D directed vectors；
- signed MIN/MAX 边界；
- unsigned MINU/MAXU 边界；
- AMO.W 高/低32-bit half 和符号扩展；
- dual/small 多 Hart 循环 AMOADD；
- 最终值等于所有增量总和，无丢失更新；
- 每次 rd 返回该操作前 old value；
- AMO 中间 probe 不观察半更新状态。

### T8 CLINT/IPI

- 对每个 profile 检查所有 mhartid 唯一；
- msip[0]/msip[1] 共用64-bit word但互不覆盖；
- Hart0 分别向每个 secondary 发 IPI；
- target mcause interrupt bit=1、cause=3；
- target 清自己的 msip 后源撤销并 mret；
- mtimecmp[h] 只触发对应 Hart；
- 不存在的 Hart 地址访问返回 bus error或按固定未实现规则处理，不能别名到现有 Hart。

### T9 remote FENCE.I

- Hart0 在可执行 RAM 写入/修改测试代码；
- Hart0 发布写入并写 Hart1/Hart3 MSIP；
- target handler 执行本地 FENCE.I；
- target 后续执行修改后的指令结果；
- 未执行 FENCE.I 的控制路径不得被误当作 PASS；
- small 至少验证一个 writer、三个 target 的 completion mask。

## 24. 协议断言

必须在 Chisel 中实现并由测试激活：

- 同一 line 稳定态最多一个 E/M owner；
- SHARED 无 owner，sharer 对应 L1D 必须为 S；
- UNIQUE owner 必须在范围内且唯一；
- pending Ack 非0时不得 GrantM；
- owner data 未回收时不得把 line 交给新 owner；
- dirty victim 未成功释放/写回时不得覆盖；
- backpressure 时 ready/valid payload 稳定；
- response 的 txnId/lineAddr 必须匹配当前事务；
- 一个 CPU request 只产生一次 response；
- SC success 前已发生真正 cache write；
- atomicLock 期间不得交出目标 line；
- reservation line probe 后 resValid 清零；
- I$ 不出现在 D$ sharer bitmap；
- FENCE.I 的 I$ flush 不早于 D$ flush done；
- Hart ID 不重复且小于 numHarts；
- profile marker 参数与 elaborated hardware 一致。

## 25. 文件级实现范围

### 25.1 预计修改

```text
design/src/main/scala/config/config.scala
design/src/main/scala/interface/interface.scala
design/src/main/scala/cache/BreezeDCache.scala
design/src/main/scala/cache/BreezeCache.scala           // 只允许必要的参数/适配
design/src/main/scala/core/InstDecode.scala
design/src/main/scala/core/common.scala
design/src/main/scala/core/RegFile.scala
design/src/main/scala/backend/BreezeBackend.scala
design/src/main/scala/core/BreezeCore.scala
design/src/main/scala/sim/BreezeCoreSimSupport.scala
design/src/main/scala/top/BreezeCoreWishbone.scala      // 保持兼容
design/src/main/scala/top/GenerateBreezeCoreWishbone.scala
litex_wrapper/flow/core.py
sim/litex/breeze_sim.py                                 // 保持旧入口兼容
sim/litex/run_mcu.py
sim/breezecore/tests/branch_test/Makefile
sim/breezecore/tests/trap_valid_bug_test/Makefile
config/breeze_mcu_platform.json
software/breeze-mcu/**
```

### 25.2 预计新增

```text
design/src/main/scala/cache/Coherence.scala
design/src/main/scala/cache/BreezeL2Home.scala
design/src/main/scala/cache/BreezeAmoAlu.scala
design/src/main/scala/top/BreezeHartTile.scala
design/src/main/scala/top/BreezeMulticoreClusterWishbone.scala
design/src/main/scala/top/GenerateBreezeMulticoreClusterWishbone.scala
design/src/test/scala/cache/BreezeDCacheSetAssocSpec.scala
design/src/test/scala/cache/BreezeL2HomeSpec.scala
design/src/test/scala/cache/BreezeAmoAluSpec.scala
design/src/test/scala/top/BreezeMulticoreClusterSpec.scala
litex_wrapper/flow/clint.py
litex_wrapper/flow/cluster.py
sim/litex/multicore_sim.py
sim/litex/run_multicore.py
software/breeze-multicore/**
```

文件名允许因语言组织小幅调整，但职责边界不得合并成一个不可测试的巨型模块。

## 26. 分阶段实施和硬门槛

执行 agent 必须按顺序推进；每阶段完成本地 commit/push 和 Alan 门槛后再进入下一阶段。

### P0：基线与配置骨架

实现：

- single/dual/small 参数；
- 把 generator、LiteX wrapper、单核 runner 和多核 runner 的默认 core preset 统一切到 GShare；显式 baseline 仍可选；
- 配置派生/require；
- coherence Bundle；
- cluster generator 空骨架可 elaboration；
- 不改变现有单核功能。

门槛：

- 当前全量 `sbt test`；
- T1 config tests；
- default=GShare 与 explicit baseline 两类 preset 测试；
- existing single RTL elaboration；
- three profile cluster skeleton elaboration；
- 当前单核 generic/timer/uart smoke。

### P1：8 KiB 4-way L1D

实现：

- 替换 fully-associative arrays；
- set lookup、4-way compare、PLRU；
- 保持旧下级 memory bridge，暂不接 coherence；
- flush/error/MMIO/HPM 等价。

门槛：

- T2 全部；
- 当前 DCache/Backend/Core 定向测试；
- 全量 `sbt test`；
- 当前单核 LiteX generic/timer/uart。

### P2：single L2/Home

实现：

- 16 KiB/8-way/single-bank L2；
- L1D cached path 接 Home；
- I$ refill 接 Home；
- MMIO 保持 bypass；
- legacy top 仍可用。

门槛：

- T3；
- single cluster elaboration；
- single boot/generic/L2 eviction 仿真；
- 全量历史回归。

### P3：dual MSI

实现：

- 两 Tile；
- request arbiter；
- GetS/GetM/PutS/PutM；
- probe/probe response；
- directory NONE/SHARED/UNIQUE；
- 32 KiB L2；
- 多核 startup/hartid。

门槛：

- T4；
- dual boot mask；
- dual sharing/upgrade/dirty transfer/same-line；
- deadlock watchdog 全部不触发；
- single 回归仍 PASS。

### P4：small MSI

实现：

- 参数扩为4 Hart；
- 4路 arbiter；
- 4-bit sharer/pendingAck；
- 64 KiB L2；
- 4 Hart startup/stack/mailbox。

门槛：

- T5 中除 E 以外的 MSI 部分；
- `1111` sharing；
- 三个 invalidate Ack 后才 GrantM；
- inclusive eviction；
- single/dual 回归仍 PASS。

### P5：MESI E

实现：

- NONE 首次 D GetS 授予 E；
- E→M 静默；
- UNIQUE 保守 recall；
- single/dual/small 全启用最终 MESI。

门槛：

- T5 完整；
- E silent-upgrade assertion；
- 1/2/4 全部 MSI 旧场景复跑。

### P6：RV64A LR/SC

实现：

- decode/interface/writeback/misa gate；
- reservation；
- LR/SC cache path；
- 对齐/fault/aq/rl guard。

门槛：

- T6；
- single/dual/small LR/SC firmware；
- 旧 RV64IM regressions 全 PASS。

### P7：RV64A AMO

实现：

- AmoAlu；
- AMO FSM/atomicLock；
- 全部9 op × W/D；
- 最终打开 misa.A。

门槛：

- T7；
- dual/small contention；
- `-march=rv64ima_zicsr_zifencei` firmware；
- 全量 SBT 与旧回归。

### P8：CLINT/IPI/remote FENCE.I

实现：

- per-Hart msip/mtimecmp；
- MSIE/MSIP/cause3；
- MMIO arbiter；
- IPI handler；
- remote FENCE.I firmware/monitor。

门槛：

- T8/T9；
- single/dual/small final matrix；
- timer/uart 旧 smoke；
- full `sbt test`；
- 三档 fresh elaboration + fresh Verilator build。

## 27. 最终仿真矩阵

最终至少需要：

下表主矩阵全部使用默认 GShare。baseline 不展开三档全矩阵，但至少保留现有单核显式 baseline 的 elaboration、generic、timer 和 uart 兼容 PASS。

| Test | single | dual | small |
|---|---:|---:|---:|
| boot/mhartid | PASS | PASS | PASS |
| generic cached load/store | PASS | PASS | PASS |
| sharing | N/A或PASS | PASS | PASS |
| store upgrade/invalidate | N/A | PASS | PASS |
| dirty owner read | N/A | PASS | PASS |
| dirty owner write transfer | N/A | PASS | PASS |
| inclusive L2 eviction | PASS | PASS | PASS |
| LR/SC success | PASS | PASS | PASS |
| LR/SC interference fail | N/A | PASS | PASS |
| AMO directed | PASS | PASS | PASS |
| AMOADD contention | N/A | PASS | PASS |
| per-Hart timer | PASS | PASS | PASS |
| IPI | N/A或self-test | PASS | PASS |
| remote FENCE.I | N/A或self-test | PASS | PASS |

任何空缺必须解释；dual/small 的一致性/A/IPI 核心场景不得用 N/A。

## 28. 最终停止条件

执行 agent 只有在以下全部成立时才能报告“实现完成”：

- P0–P8 均有 PASS；
- feature branch 已 push；
- Alan checkout HEAD 与最终 commit 相同；
- `git diff --check` 通过；
- commit 不含 `docs/`；
- single/dual/small 配置 marker正确；
- 无参数默认选择 GShare，显式 baseline 兼容回归通过；
- 最终矩阵无缺失核心项；
- 所有失败尝试保留为独立日志，不被成功日志覆盖；
- 没有实现 8/16 核或其他非目标；
- 没有 merge main；
- 已向 Codex/用户提交审核材料。

如果任一门槛未满足，只能报告 `PARTIAL`、`FAILED` 或 `BLOCKED`，不得用“基本完成”“代码应该没问题”替代证据。
