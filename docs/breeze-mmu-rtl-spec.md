# Breeze Sv39 MMU RTL Specification

本文是新版 MMU 的 RTL 实现规格，写 RTL 时以本文为准。架构背景和取舍理由见 [`breeze-mmu-vipt-design.md`](breeze-mmu-vipt-design.md)；两者冲突、或本文没有覆盖到的行为，停下来问用户，不要自行补设计。

## 0. 实现规则（防止跑偏）

必须遵守：

- 拍级行为、寄存器划分、状态机按本文实现。不增加或减少流水级，不把本文规定为寄存器输出的信号改成组合输出，反之亦然。
- miss 一律阻塞，不实现重放、不实现 hit-under-miss、不实现多个在途 walk。
- 不实现：二级叶 TLB、硬件 A/D 更新、Svpbmt、Svnapot、Svinval、H 扩展、fault 缓存。
- PMP/PMA 不在 MMU 里做（由 D-cache/前端负责）。
- 不依赖、不修改旧实现：`design/src/main/scala/mmu/BreezeMmu.scala`、`BreezeDataTranslator.scala`、`BreezePmpChecker.scala`。不复用 `cache/BreezeCache.scala` 中写死 4 路的 `BreezePLRU`。
- 第 10 节的断言全部实现。第 11 节的测试全部实现并通过后，才算模块完成。

## 1. 文件与模块

包名 `flow.mmu.sv39`，目录 `design/src/main/scala/mmu/sv39/`：

| 文件 | 内容 |
| --- | --- |
| `Sv39MmuParams.scala` | 参数、常量 |
| `Sv39MmuBundles.scala` | 所有接口 Bundle、`MmuCmd` 枚举、PTE 解码 |
| `TreePlru.scala` | 通用树形 PLRU（任意 2 的幂路数） |
| `Sv39Tlb.scala` | 一级 TLB（iTLB、dTLB 共用同一个模块，参数不同） |
| `Sv39WalkCache.scala` | 一级 walk-cache 阵列（上层、中层各实例化一次） |
| `Sv39Ptw.scala` | 仲裁 + walk-cache 查询 + PTW 状态机 |
| `Sv39Mmu.scala` | 顶层：2 个 TLB + 2 个 walk-cache + PTW |

测试放在 `design/src/test/scala/mmu/sv39/`，使用仓库现有的 `chisel3.simulator.scalatest.ChiselSim`。

## 2. 参数与常量

```scala
case class Sv39TlbParams(sets: Int, ways: Int, superpages: Int)

case class Sv39MmuParams(
  asidBits: Int = 16,
  itlb: Sv39TlbParams = Sv39TlbParams(sets = 8, ways = 4, superpages = 4),
  dtlb: Sv39TlbParams = Sv39TlbParams(sets = 8, ways = 4, superpages = 4),
  wcUpperSets: Int = 1,  wcUpperWays: Int = 4,
  wcMiddleSets: Int = 2, wcMiddleWays: Int = 4
)
```

- 所有 sets、ways、superpages 必须是 ≥1 的 2 的幂，用 `require` 检查。
- 常量：`VLEN = 64`、`PgOffsetBits = 12`、`VpnBits = 27`、`VpnSliceBits = 9`、`PpnBits = 44`、`PaddrBits = 56`。
- MMU 对外输出的 `paddr` 宽 64 位：Sv39 下高 8 位补 0；Bare 下等于 VA。
- `VPN[2] = vpn(26,18)`，`VPN[1] = vpn(17,9)`，`VPN[0] = vpn(8,0)`。
- level 编码：`0` 为 4 KiB 叶 / 最低层，`1` 为 2 MiB / 中层，`2` 为 1 GiB / 根层。

## 3. 编码

### 3.1 访问类型

```scala
object MmuCmd extends ChiselEnum { val Fetch, Load, Store = Value }
```

- LR 按 `Load` 发送。SC、AMO 按 `Store` 发送。
- iTLB 只接收 `Fetch`；dTLB 只接收 `Load`/`Store`（加断言）。
- 非对齐访存由 LSU 在送 dTLB 之前直接 trap，dTLB 不会收到非对齐请求，MMU 不处理跨页数据访问。

### 3.2 PTE

| 字段 | 位 |
| --- | --- |
| V R W X U G A D | 0 1 2 3 4 5 6 7 |
| RSW | 9:8（忽略） |
| PPN | 53:10 |
| 保留 / PBMT / N | 63:54，必须全 0 |

叶判定：`R || X`。

## 4. 接口

### 4.1 CSR 输入 `MmuCsrIO`（全部 Input，由核心直接给当前值）

| 信号 | 宽度 | 含义 |
| --- | --- | --- |
| `sv39` | 1 | `satp.MODE == Sv39`（0 表示 Bare） |
| `asid` | 16 | `satp.ASID` |
| `rootPpn` | 44 | `satp.PPN` |
| `priv` | 2 | 当前特权级（U=0, S=1, M=3） |
| `mprv` | 1 | `mstatus.MPRV` |
| `mpp` | 2 | `mstatus.MPP` |
| `sum` | 1 | `mstatus.SUM` |
| `mxr` | 1 | `mstatus.MXR` |

核心保证：这些值修改后会冲刷流水，所以 MMU 在 N+1 拍直接读当前值，不做快照。PTW 使用的 `asid`、`rootPpn` 在 miss 时快照（见 5.2 节）。

### 4.2 每侧 TLB 端口 `TlbPortIO`（iTLB、dTLB 各一个）

| 信号 | 方向（相对 MMU） | 说明 |
| --- | --- | --- |
| `req.valid` | In | |
| `req.ready` | Out | 方程见 5.4 节 |
| `req.bits.vaddr` | In, 64 | |
| `req.bits.cmd` | In, `MmuCmd` | |
| `resp.valid` | Out, 寄存器驱动 | 每个被接收（且未被丢弃）的请求在接收后的下一拍恰好有一个 resp |
| `resp.bits.hit` | Out | |
| `resp.bits.miss` | Out | |
| `resp.bits.pageFault` | Out | |
| `resp.bits.accessFault` | Out | 只来自 PTW 读 PTE 时 D-cache 返回的 access fault |
| `resp.bits.paddr` | Out, 64 | 仅 `hit` 时有意义 |
| `kill` | In | 请求方冲刷：丢弃该侧所有未完成的请求 |
| `candidatePaddr` | 可选 Out, 64 | SOC-3d 内部时序旁带：与 resp 同拍的 PA 候选；不经过页权限/fault 资格。只供下游并行计算 PMP/PMA，不授权访问。 |

`resp.valid` 时 `hit / miss / pageFault / accessFault` 恰好一个为 1（断言）。异常码由请求方根据自己的 `cmd` 映射（取指、load、store/AMO 三类）。

SOC-3d 第一批启用 `withPmpCandidate=true` 的生产集群；默认 false 的独立模块保留原端口。候选为 `translate ? pa : s1Vaddr`，只在成功 hit 时保证等于 `resp.bits.paddr`；miss/fault/kill 时不得使用候选发请求或产生副作用。原有 resp 的优先级、paddr 置零、valid/ready/kill、流水拍数与 PTW/sfence 行为保持。

### 4.3 sfence 输入 `SfenceIO`

| 信号 | 宽度 | 说明 |
| --- | --- | --- |
| `valid` | 1 | 单拍脉冲 |
| `rs1Nz` | 1 | rs1 ≠ x0 |
| `rs2Nz` | 1 | rs2 ≠ x0 |
| `vaddr` | 64 | rs1 的值 |
| `asid` | 16 | rs2 的值低 16 位 |

### 4.4 MMU 状态输出

`idle`：两侧 `missValid == 0`、两侧 `pendingFault == 0`、PTW 处于 `sIdle`、TLB 的 sfence 流水级为空、两侧查询 S1（`s1Valid`）为空。

查询 S1 计入 `idle`（2026-10-07 修订，见 [`tasks/CLUSTER-sfence-idle-fix.md`](tasks/CLUSTER-sfence-idle-fix.md)）：前端 kill 与 SFENCE 首次到 WB 同拍时，上一拍收下的取指查询仍在 S1；`idle` 必须在该拍为 0，使 `sfence.valid` 不早于 kill 生效后的一拍。后端 T20 的“第一个 `drained && mmu.idle` 拍”按本定义计算，条文不变。

核心执行 `sfence.vma` 的顺序：冲刷前端（对 iTLB 拉 `kill`）→ 等 store buffer 排空 → 等 `idle` → 发 `sfence.valid` 单拍 → 等 `idle` → 从下一条指令重新取指。

### 4.5 PTW ↔ D-cache 端口 `PtwMemIO`

| 信号 | 方向（相对 MMU） | 说明 |
| --- | --- | --- |
| `req.valid / req.ready` | Out / In | MMU 在 `ready` 之前保持请求不变 |
| `req.bits.paddr` | Out, 56 | 8 B 对齐的 PTE 地址 |
| `resp.valid` | In | 单拍，MMU 不反压 |
| `resp.bits.data` | In, 64 | |
| `resp.bits.accessFault` | In, 1 | D-cache 以 S 模式权限做 PMP/PMA 检查，失败为 1 |

D-cache 保证：`req` 被接收后，若干拍后恰好返回一个 `resp`。

## 5. Sv39Tlb

### 5.1 存储

| 存储 | 实现 | 内容 |
| --- | --- | --- |
| 基础页阵列数据 | `SyncReadMem(sets, Vec(ways, BaseEntry))`，带 way 写掩码 | `tag`（27 − log2(sets) 位）、`asid`、`g`、`ppn`、`r w x u a d` |
| 基础页 valid | `RegInit` `sets × ways` 位 | 复位为 0 |
| 基础页 PLRU | `RegInit` `sets × (ways−1)` 位 | |
| 超页阵列 | `RegInit` `superpages` 项 | `valid`、`vpn`（27）、`level`（1 或 2）、`asid`、`g`、`ppn`、`r w x u a d` |
| 超页 PLRU | `RegInit` `superpages − 1` 位 | |

- 基础页组索引 `set = vpn(log2(sets)−1, 0)`，`tag = vpn(26, log2(sets))`。sets 为 1 时没有索引位。
- `SyncReadMem` 读和写不会发生在同一拍（读只在 `req.fire` 或 sfence S0 拍，写只在 refill 拍，两者互斥，见 5.4 节），用断言保证。因此可以综合成单口 RAM。

### 5.2 寄存器清单

| 寄存器 | 说明 |
| --- | --- |
| `s1Valid` | N+1 拍有请求 |
| `s1Vaddr`、`s1Cmd` | 请求锁存 |
| `s1FromFault` | 本请求用于交付 `pendingFault` |
| `missValid` | 该侧有 miss 等待或正在被 PTW 服务 |
| `missGranted` | PTW 已接受该 miss |
| `missKilled` | 已被 PTW 接受后又被 `kill` |
| `missVpn`、`missAsid`、`missRootPpn` | miss 时的 VPN 和 `satp` 快照 |
| `pendingFault`、`pendingFaultIsAccess` | PTW 返回的 fault，等请求方重新发请求时交付 |
| `sfS1Valid`、`sfVaddr`、`sfRs2Nz`、`sfAsid` | sfence 按 VA 查找的第二拍 |

### 5.3 命中路径

**N 拍（`req.fire`）：**

- 基础页阵列以 `set(req.vaddr)` 读出（`SyncReadMem.read`，使能为 `req.fire`）。
- `s1Valid := req.fire && !dropS1Next`（`dropS1Next` 见下）；`s1Vaddr`、`s1Cmd` 锁存；`s1FromFault := pendingFault`；`req.fire && pendingFault` 时清 `pendingFault`。

**N+1 拍（`s1Valid`）：组合计算，`resp` 由下列组合结果驱动，`resp.valid := s1Valid && !kill`。**

```text
effPriv   = (s1Cmd != Fetch && mprv) ? mpp : priv
translate = sv39 && effPriv != M
canonical = s1Vaddr(63,38) 全 0 或全 1
vpn       = s1Vaddr(38,12)

baseHit(i)  = baseValid(set)(i) && mem(i).tag == tag(vpn) && (mem(i).g || mem(i).asid == csr.asid)
superHit(j) = spValid(j) && vpnMatch(j) && (sp(j).g || sp(j).asid == csr.asid)
              vpnMatch: level==2 → vpn(26,18) 相等；level==1 → vpn(26,9) 相等
anyHit      = baseHit.orR || superHit.orR
entry       = 命中项（基础页优先的 PriorityMux；断言命中数 ≤ 1）

if s1FromFault:            fault（pageFault 或 accessFault，取 s1 锁存的类型）
else if !translate:        hit,  paddr = s1Vaddr
else if !canonical:        pageFault
else if !anyHit:           miss
else if permFail(entry):   pageFault
else:                      hit,  paddr = concat(entry, s1Vaddr)
```

`s1FromFault` 需要一并锁存 fault 类型（在 N 拍从 `pendingFaultIsAccess` 拷贝）。

`permFail(e)` 为以下任一条件：

```text
!e.a
s1Cmd == Fetch && !e.x
s1Cmd == Load  && !(e.r || (mxr && e.x))
s1Cmd == Store && !(e.w && e.d)
effPriv == U && !e.u
effPriv == S && e.u && (s1Cmd == Fetch || !sum)
```

PA 拼接：

```text
4 KiB: {ppn,             s1Vaddr(11,0)}
2 MiB: {ppn(43,9),       s1Vaddr(20,0)}
1 GiB: {ppn(43,18),      s1Vaddr(29,0)}
```

**N+1 拍的寄存器更新：**

- 命中且 translate：更新对应 PLRU（基础页：该 set 的 PLRU；超页：全局 PLRU）。
- miss 且 `!kill`：`missValid := 1`、`missGranted := 0`、`missKilled := 0`、`missVpn := vpn`、`missAsid := csr.asid`、`missRootPpn := csr.rootPpn`。
- `dropS1Next = s1Valid && miss`：本拍若同时 `req.fire`（更年轻的请求），该请求被丢弃，下一拍不产生 resp。请求方收到 miss 后，必须在 `ready` 恢复后按程序顺序重新发送被 miss 的请求及其后的请求。

`ready` 不依赖 N+1 的比较结果，避免 tag 比较进入 ready 的组合路径。

### 5.4 ready 与 miss 状态

```text
req.ready = !missValid && !sfence.valid && !sfS1Valid && !kill
```

- `pendingFault` 有效时 `ready = 1`，请求方重新发出的请求在 N+1 得到 fault（断言其 VPN 等于 `missVpn`）。
- refill 发生在 `missValid == 1` 期间，此时 `ready == 0`，所以 refill 写入和读不会同拍。`missValid` 在 refill 当拍清零，下一拍 `ready` 恢复，请求方重新发出的请求会读到新写入的项。

PTW 交互（`grant`、`done` 来自 PTW，见 7 节）：

| 事件 | 动作 |
| --- | --- |
| `grant` | `missGranted := 1` |
| `kill` 且 `missValid` 且 `!(missGranted \|\| grant)` | `missValid := 0` |
| `kill` 且 `missValid` 且 `(missGranted \|\| grant)` | `missKilled := 1`（walk 继续） |
| `kill` | `pendingFault := 0`；本拍 `s1Valid` 的 resp 不输出，也不建立 miss |
| `done` | `missValid := 0`、`missGranted := 0`、`missKilled := 0`；若有 refill 就写入；若是 fault 且 `!(missKilled \|\| kill)`，置 `pendingFault` 和类型 |

### 5.5 Refill

PTW 的 `done` 中带有 refill 项（`refillValid`、`vpn`、`asid`、`level`、`ppn`、`r w x u g a d`）。

- `level == 0`：写基础页阵列。`set = set(vpn)`；victim 为该 set 第一个 invalid 的 way，没有则取 PLRU 的选择；`mem.write(set, entryVec, wayMask)`；`baseValid(set)(victim) := 1`；PLRU touch victim。
- `level != 0`：写超页阵列。victim 为第一个 invalid 项，没有则取 PLRU 的选择；`vpn` 存完整 27 位，`level` 存 1 或 2；PLRU touch。
- `g` 只取叶 PTE 的 G 位，不继承非叶的 G。

### 5.6 sfence

| 拍 | `rs1Nz == 0` | `rs1Nz == 1` |
| --- | --- | --- |
| S0（`sfence.valid`） | 基础页 valid、超页 valid 全部清零 | 以 `set(sfence.vaddr)` 读基础页阵列；锁存 `sfVaddr`、`sfRs2Nz`、`sfAsid`；`sfS1Valid := 1` |
| S1（`sfS1Valid`） | — | 对该 set 每一路和每个超页项，若 VPN 匹配且 `(!sfRs2Nz \|\| (!g && asid == sfAsid))` 则 valid 清零；`sfS1Valid := 0` |

- VPN 匹配规则与命中路径相同（超页按 level 截断）。不检查 `ASID == csr.asid`，也不检查 sfence VA 的规范性（多刷合法）。
- `rs1Nz == 0` 时忽略 `rs2Nz`，一律全刷（含 G 项）。

## 6. Sv39WalkCache

一个模块，参数 `(sets, ways, keyBits)`；上层实例 `keyBits = 9`（键为 `VPN[2]`），中层实例 `keyBits = 18`（键为 `VPN[2:1]`）。

| 存储 | 实现 |
| --- | --- |
| 每项 `valid`、`tag`、`asid`、`ppn` | 全部 `RegInit`，valid 复位为 0 |
| PLRU | 每 set 一份 `RegInit` |

`set = key(log2(sets)−1, 0)`，`tag = key(keyBits−1, log2(sets))`。

端口：

- `lookup`：输入 `key`、`asid`；组合输出 `hit`、`ppn`。匹配条件 `valid && tag 相等 && asid 相等`。输入 `lookupFire` 为 1 且命中时更新 PLRU。
- `fill`：输入 `valid`、`key`、`asid`、`ppn`。victim 为第一个 invalid 的 way，没有则取 PLRU 的选择。断言 fill 时该 key/asid 不命中。
- `flush`：输入；为 1 时所有 valid 清零（接 `sfence.valid`，不论 rs1/rs2）。

## 7. Sv39Ptw

### 7.1 寄存器

| 寄存器 | 宽度 | 说明 |
| --- | --- | --- |
| `state` | enum | `sIdle, sLookup, sReq, sWait, sCheck` |
| `wSide` | 1 | 0 = I，1 = D |
| `wVpn` | 27 | |
| `wAsid` | 16 | |
| `wRoot` | 44 | |
| `wLevel` | 2 | 当前要读的层 |
| `wBase` | 44 | 当前层页表基址 PPN |
| `wPte` | 64 | 锁存的 PTE |
| `wAf` | 1 | 锁存的 access fault |

### 7.2 状态转移

| 状态 | 组合输出 | 转移与寄存器更新 |
| --- | --- | --- |
| `sIdle` | `dGrant = dMissValid && !dMissGranted`；`iGrant = iMissValid && !iMissGranted && !dGrant` | 有 grant：锁存所选侧的 `missVpn/missAsid/missRootPpn` 到 `wVpn/wAsid/wRoot`，`wSide` 置位，→ `sLookup` |
| `sLookup` | 上层 walk-cache `lookup(wVpn(26,18), wAsid)`；中层 `lookup(wVpn(26,9), wAsid)`；`lookupFire = 1` | 中层命中：`wLevel := 0`，`wBase := 中层 ppn`；否则上层命中：`wLevel := 1`，`wBase := 上层 ppn`；否则 `wLevel := 2`，`wBase := wRoot`。→ `sReq` |
| `sReq` | `mem.req.valid = 1`，`paddr = {wBase, vpnSlice(wVpn, wLevel), 3'b000}` | `mem.req.fire` → `sWait` |
| `sWait` | — | `mem.resp.valid`：`wPte := data`、`wAf := accessFault`，→ `sCheck` |
| `sCheck` | 见 7.3 节 | 继续下一层 → `sReq`；结束 → `sIdle` |

`vpnSlice(vpn, 2) = vpn(26,18)`，`(vpn, 1) = vpn(17,9)`，`(vpn, 0) = vpn(8,0)`。

### 7.3 sCheck

```text
ppn      = wPte(53,10)
leaf     = r || x
badPte   = !v || (!r && w) || wPte(63,54) != 0
misalign = (wLevel == 2 && ppn(17,0) != 0) || (wLevel == 1 && ppn(8,0) != 0)

if wAf:                      done(accessFault)
else if badPte:              done(pageFault)
else if leaf && misalign:    done(pageFault)
else if leaf:                done(refill: vpn=wVpn, asid=wAsid, level=wLevel, ppn, rwxugad)
else if wLevel == 0:         done(pageFault)
else:                        // 有效非叶
    wLevel == 2 → 上层 walk-cache fill(key=wVpn(26,18), asid=wAsid, ppn)
    wLevel == 1 → 中层 walk-cache fill(key=wVpn(26,9),  asid=wAsid, ppn)
    wBase := ppn; wLevel := wLevel - 1; → sReq
```

- 非叶 PTE 的 D/A/U 位不检查。
- 叶 PTE 的 A/D/R/W/X/U 不在 PTW 检查，结构合法就 refill，权限在 TLB 命中路径检查（5.3 节）。
- `done` 是单拍信号，带 `side = wSide`、`pageFault`、`accessFault`、refill 项；只送给 `wSide` 那一侧的 TLB。`done` 当拍 → `sIdle`，下一拍才能再次 grant。

## 8. Sv39Mmu 顶层连接

```text
Sv39Mmu
├── itlb: Sv39Tlb(params.itlb)      io.itlb ↔ 前端
├── dtlb: Sv39Tlb(params.dtlb)      io.dtlb ↔ LSU / D-cache
├── wcUpper:  Sv39WalkCache(wcUpperSets,  wcUpperWays,  9)
├── wcMiddle: Sv39WalkCache(wcMiddleSets, wcMiddleWays, 18)
└── ptw: Sv39Ptw                    io.ptwMem ↔ D-cache PTW 通道
io.csr    → itlb、dtlb（ptw 不直接用 csr，只用 miss 快照）
io.sfence → itlb、dtlb、wcUpper.flush、wcMiddle.flush
io.idle   ← 4.4 节定义
```

## 9. 时序

### 9.1 命中

| 拍 | 请求方 | TLB |
| --- | --- | --- |
| N | `req.fire` | 读基础页阵列，锁存 s1 |
| N+1 | 收 `resp` | 比较、权限检查，`resp.valid` |

背靠背请求每拍可接收一个。

### 9.2 miss（D-cache 读 PTE 延迟为 L 拍，L ≥ 1，表示 `req.fire` 后第 L 拍 `resp.valid`）

以中层 walk-cache 命中（只读 1 次 PTE）为例：

| 拍 | 事件 |
| --- | --- |
| N | `req.fire` |
| N+1 | `resp.miss`；`missValid` 写入 |
| N+2 | PTW `sIdle` grant，锁存 `w*` |
| N+3 | `sLookup`：并行查两级 walk-cache，锁存起点 |
| N+4 | `sReq`：发 PTE 读（假设 `ready`） |
| N+4+L | `sWait`：收 resp |
| N+5+L | `sCheck`：`done`，refill 写入，`missValid` 清零 |
| N+6+L | `req.ready = 1`，请求方重新 `req.fire` |
| N+7+L | `resp.hit` |

每多读一层 PTE，增加 `L + 2` 拍（`sReq`、等待 L 拍、`sCheck`）。walk-cache 都没命中时读 3 次 PTE。

### 9.3 PTW fault

同 9.2，只是 `sCheck` 当拍 `done` 带 fault：不 refill，置 `pendingFault`；N+6+L 请求方重新发送，N+7+L 收到 `pageFault` 或 `accessFault`。

### 9.4 kill

- grant 之前 kill：`missValid` 清零，下一拍 `ready` 恢复。
- grant 之后 kill：walk 继续，`ready` 保持 0 直到 `done`；结果照常 refill，fault 丢弃。

## 10. 断言

用 `assert`，带简短的中文或英文说明：

1. `resp.valid` 时 `hit/miss/pageFault/accessFault` 独热。
2. 基础页与超页命中数之和 ≤ 1。
3. 基础页 `SyncReadMem` 不在同一拍读和写。
4. iTLB 只收到 `Fetch`；dTLB 不收到 `Fetch`。
5. `req.fire && pendingFault` 时请求 VPN 等于 `missVpn`。
6. `sfence.valid` 时 `idle == 1`。
7. sfence S0/S1 期间没有 `req.fire`、没有 refill。
8. `sfence.valid` 时两个 `sfence.valid` 之间至少隔一拍（S1 未结束时不能来新的 sfence）。
9. `mem.resp.valid` 只在 `state == sWait` 时出现。
10. `sReq` 期间 `mem.req.bits.paddr` 保持不变直到 fire。
11. walk-cache fill 时该 key/asid 未命中。
12. `done` 只发给 `missValid && missGranted` 的那一侧。
13. 同一拍 `iGrant` 和 `dGrant` 不同时为 1。

## 11. 测试（ChiselSim，`design/src/test/scala/mmu/sv39/`）

测试环境用一个行为模型扮演 D-cache PTW 通道：保存一张稀疏物理内存（PA → 64 位），`ready` 可随机反压，延迟 L 可配置（至少测 1 和 5），可对指定 PA 返回 access fault。测试里统计 PTE 读的次数，用来检查 walk-cache 是否生效。

| 编号 | 场景 | 检查 |
| --- | --- | --- |
| T1 | Bare、M 模式 | 直通 `paddr = vaddr`，不发 PTE 读 |
| T2 | 非规范 VA | `pageFault`，不发 PTE 读 |
| T3 | 4 KiB 首次访问 | miss → 3 次 PTE 读 → 重发 hit；PA 正确；按 9.2 节的拍数 |
| T4 | 同一 2 MiB 区域第二个 4 KiB 页 | 1 次 PTE 读（中层 walk-cache 命中） |
| T5 | 同一 1 GiB 区域、不同 2 MiB 区域 | 2 次 PTE 读（上层命中） |
| T6 | 2 MiB、1 GiB 叶 | 写入超页阵列；区域内其他地址直接 hit |
| T7 | 超页未对齐、V=0、R=0&W=1、高 10 位非 0、level 0 仍为非叶 | `pageFault`，TLB 不写入 |
| T8 | PTE 地址 access fault | `accessFault` |
| T9 | 权限矩阵：priv{U,S,M+MPRV} × cmd × SUM × MXR × PTE{R,W,X,U,A,D} | 与 5.3 节的公式逐项比对（用 Scala 参考模型） |
| T10 | ASID 隔离 | 换 ASID 后原表项不命中；G=1 的表项跨 ASID 命中 |
| T11 | sfence 三种组合 | 按 5.6 节清除；不该清的仍然命中；walk-cache 被清空（PTE 读次数恢复） |
| T12 | i/d 同时 miss | D 先被服务 |
| T13 | grant 前 kill、grant 后 kill | 9.4 节的行为；被 kill 的 fault 不交付 |
| T14 | hit 之后紧跟一个 miss，同时又 fire 了更年轻的请求 | 年轻请求被丢弃（无 resp） |
| T15 | 一个 set 填满后继续 refill | 先用 invalid 的 way，之后按 PLRU 替换 |
| T16 | PTW 通道随机反压 | 结果与无反压一致 |
| T17 | 随机测试 | 随机页表 + 随机访问序列，与 Scala 参考 Sv39 walker 比对结果 |
