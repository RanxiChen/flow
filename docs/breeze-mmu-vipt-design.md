# Breeze 新版 MMU 设计

> 2026-10-04 讨论定案。本文定义新版 MMU（iTLB、dTLB、walk-cache、PTW）的结构、拍级行为和对外接口，以及取舍理由。RTL 实现细节（寄存器划分、状态机、断言、测试）见 [`breeze-mmu-rtl-spec.md`](breeze-mmu-rtl-spec.md)；依赖其他模块、需在系统闭环时检查的假设见 [`breeze-mmu-closure-checklist.md`](breeze-mmu-closure-checklist.md)。旧 Breeze 的 MMU、系统约束和当前 D-cache 框架不作为依据。D-cache 内部如何使用 MMU 的输出、如何实现 PTW 通道，属于后续 D-cache 设计，本文只规定接口。

## 1. 范围

- RV64，分页模式支持 `Bare` 和 `Sv39`。
- 独立的 iTLB、dTLB；共享一个 PTW；两级非叶 walk-cache。
- PTW 通过 D-cache 的 PTW 专用通道读取 PTE（物理地址，不经过 dTLB）。
- A/D 位采用 Svade：硬件不更新 A/D，缺位时报 page fault。
- 不实现二级叶 TLB、Svpbmt、Svnapot、Svinval、H 扩展。
- 数据非对齐访问由 LSU 直接 trap，MMU 不处理跨页数据访问。
- 跨页取指由前端负责（重构前端时实现），MMU 假设前端具备该能力，系统闭环时检查（清单 C1）。

## 2. 顶层结构

```text
取指 VA ──→ iTLB ──hit/PA/fault──→ 前端
              │ miss
              ↓
          iTLB miss reg ─┐
                         ├─→ 仲裁(D 优先) ─→ walk-cache 并行查询 ─→ 结果寄存 ─→ PTW 状态机
          dTLB miss reg ─┘                                                    │
              ↑ miss                                                          │ PTE 读 (PA)
访存 VA ──→ dTLB ──hit/PA/fault──→ D-cache                                      ↓
                                                                  D-cache PTW 专用通道
                                                                  (PMP/PMA、命中、miss 由 D-cache 负责)
```

## 3. 翻译使能与上下文

- 翻译使能：`satp.MODE == Sv39 && 有效特权级 < M`。
  - 取指的有效特权级是当前特权级。
  - load/store 的有效特权级：`mstatus.MPRV == 1` 时取 `MPP`，否则取当前特权级。
- 翻译未使能时，TLB 直接返回 `PA = VA`、hit，不做页权限检查。
- 翻译使能时，VA[63:39] 必须全部等于 VA[38]，否则直接返回 page fault，不查 TLB，不发起 walk。
- TLB 命中路径直接使用当前的 `satp.ASID`、`mstatus.SUM/MXR/MPRV/MPP` 和特权级。修改这些状态的指令（写 `satp`、`mstatus`、xRET 等）之后，核心必须冲刷流水，使后续指令用新值查询。

## 4. 一级 TLB

### 4.1 组织

每个 TLB（iTLB、dTLB 各一份）由两部分组成，查询时并行比较：

| 部分 | 组织 | 存放 |
| --- | --- | --- |
| 基础页阵列 | 组相联，组索引取 VPN[0] 低位，PLRU 替换 | 4 KiB 叶映射 |
| 超页阵列 | 全相联，触发器实现，PLRU 替换 | 2 MiB、1 GiB 叶映射，带 level 字段 |

参数（默认值参考 Rocket 的规模，后续按仿真统计调整）：

| 参数 | 默认值 |
| --- | --- |
| `iTlbSets` × `iTlbWays` | 8 × 4 |
| `dTlbSets` × `dTlbWays` | 8 × 4 |
| `iTlbSuperpages` / `dTlbSuperpages` | 4 / 4 |
| `asidBits` | 16（Sv39 的 `satp.ASID` 最大宽度，已定） |

表项字段：`valid`、`VPN tag`、`ASID`、`G`、`PPN`、`level`（仅超页阵列）、`R/W/X/U/A/D`。

- 基础页阵列只有 `valid` 用触发器实现，保证复位和全刷在一拍内完成；`G`、`ASID`、VPN tag、PPN、权限位放在 SRAM/LUTRAM（`SyncReadMem`）里，N 拍读出、N+1 拍比较。sfence 不需要按 ASID 扫描整个阵列（`rs1 = x0` 一律全刷，`rs1 ≠ x0` 只查一个 set），所以 `G`、`ASID` 不需要放在触发器里。超页阵列整体用触发器。
- 匹配条件：`valid && VPN 匹配（超页按 level 截断比较位） && (G || ASID == satp.ASID)`。
- 切换进程只写 `satp` 不刷 TLB，靠 ASID tag 区分地址空间。
- 回填时优先选 invalid 的路，否则按 PLRU 选。命中时更新 PLRU。
- 基础页阵列若为单口，refill 写入的那一拍阻塞查询。

### 4.2 命中路径

| 拍 | 动作 |
| --- | --- |
| N | 请求方给出 VA 和访问类型。基础页阵列用 VPN 选组读出；VA、访问类型锁存。 |
| N+1 | 比较基础页阵列各路和超页阵列；做权限检查；输出 `hit`、`PA`、`fault`。 |

N+1 拍的输出就是 MMU 对请求方的完整响应。请求方（D-cache、前端）拿到 PA 之后是在同拍做 tag match 还是再锁存一拍，由请求方自行决定，不属于 MMU。

PA 拼接：4 KiB 页 `PA = {PPN, VA[11:0]}`；2 MiB 页 `PA = {PPN[43:9], VA[20:0]}`；1 GiB 页 `PA = {PPN[43:18], VA[29:0]}`。

### 4.3 权限检查（在 N+1 拍、命中时动态进行）

TLB 只存 PTE 原始权限位；权限结果不在回填时固化，因为 SUM、MXR、MPRV 的修改不需要 `sfence.vma` 即生效。

| 条件 | 结果 |
| --- | --- |
| `A == 0` | page fault |
| 取指：`X == 0` | instruction page fault |
| load / LR：`R == 0 && !(MXR && X)` | load page fault |
| store / SC / AMO：`W == 0` 或 `D == 0` | store/AMO page fault |
| 有效特权级为 U 且 `U == 0` | page fault（对应访问类型） |
| 有效特权级为 S 且 `U == 1`：取指 | instruction page fault |
| 有效特权级为 S 且 `U == 1`：load/store 且 `SUM == 0` | page fault（对应访问类型） |

PMP/PMA 检查不在 MMU 内：数据访问由 D-cache 在 tag match 之后完成；PTW 的 PTE 读由 D-cache 的 PTW 通道完成（见 6 节）；取指侧由前端/I-cache 完成。

## 5. Miss 路径

### 5.1 阻塞语义

- dTLB miss：访存流水线阻塞，请求方保持同一个请求（VA、访问类型不变），直到 MMU 返回 hit 或 fault。不使用重放机制。
- iTLB miss：前端取指阻塞，同上。
- 每侧同一时刻至多一个 miss，PTW 同一时刻至多一个在途 walk。

### 5.2 拍级流程

| 拍 | 动作 |
| --- | --- |
| N+1 | TLB 比较缺失。把 VPN、`satp.ASID`、`satp.PPN` 写入该侧 miss 寄存器，置 valid。 |
| N+2 | PTW 空闲时仲裁两个 miss 寄存器：dTLB 有效选 dTLB，否则选 iTLB。选中侧的 VPN、ASID、根 PPN 锁入 PTW 寄存器。 |
| N+3 | 用 PTW 寄存器中的 VPN、ASID 并行查询两级 walk-cache，结果（起始层级和该层页表基址 PPN）在拍末锁存。 |
| N+4 起 | PTW 状态机按锁存结果发出第一个 PTE 读。 |

仲裁的 2 选 1 和 walk-cache 的 tag 比较分在两拍，避免串在同一条组合路径上；第一个 PTE 读仍在 N+4 发出。

起始层级：

| walk-cache 结果 | 起始层级 | 基址 |
| --- | --- | --- |
| 中层命中（不论上层） | level 0 | 中层项返回的 PPN |
| 仅上层命中 | level 1 | 上层项返回的 PPN |
| 均未命中 | level 2 | `satp.PPN` |

D 优先不会饿死 I：dTLB miss 来自比当前取指更老的指令，且 D 侧 miss 期间访存流水阻塞，walk 完成后 I 侧即可获得仲裁。

### 5.3 PTW 状态机

```text
IDLE → REQ(level) → WAIT_RESP → CHECK ─┬─ 非叶且 level>0 → 回填 walk-cache，level-1 → REQ
                                       ├─ 叶            → 回填请求方 TLB → DONE
                                       └─ 错误          → 返回 fault → DONE
DONE → IDLE
```

- PTE 地址：`base_PPN × 4096 + VPN[level] × 8`。
- REQ 状态向 D-cache PTW 通道发请求，`ready` 之前保持请求不变。
- 每读到一个有效非叶 PTE，就把它回填对应层的 walk-cache（level 2 读到的回填上层，level 1 读到的回填中层）。

PTW 只检查 PTE 结构合法性，以下任一条件报 page fault：

- `V == 0`，或 `R == 0 && W == 1`；
- PTE[63:54] 非零（未实现 Svpbmt/Svnapot，PBMT、N 和保留位必须为 0）；
- level 0 仍为非叶；
- 超页未对齐：level 2 的叶要求 `PPN[17:0] == 0`，level 1 的叶要求 `PPN[8:0] == 0`。

叶 PTE 只要结构合法就回填 TLB，A/D/R/W/X/U 的检查统一在 TLB 命中路径完成（4.3 节）。回填后请求方保持的请求重新查询，得到 hit 或权限 fault。这样权限检查只有一处实现。

D-cache 返回 access fault 时，PTW 结束 walk，返回 access fault（取指、load、store/AMO 对应的类型由请求方按自身访问类型确定）。

### 5.4 Fault 与 kill

- fault 不写入 TLB，由 PTW 连同来源（I/D）直接返回请求方，请求方据此产生异常。
- miss 寄存器可被冲刷清除（分支误预测、异常、中断）。walk 一旦开始不中断；若其请求方已被清除，结构合法的结果照常回填 TLB 和 walk-cache，fault 丢弃，不上报。

## 6. Walk-cache

| 层 | 查询 tag | 返回 | 组织 |
| --- | --- | --- | --- |
| 上层 | `ASID + VPN[2]` | 中层页表 PPN | 组相联，PLRU |
| 中层 | `ASID + VPN[2:1]` | 最低层页表 PPN | 组相联，PLRU |

参数默认值：上层 `1 × 4`，中层 `2 × 4`（组数 × 路数；组数为 1 即全相联）。

- 只缓存结构合法的有效非叶 PTE。
- 所有表项都带 ASID tag，非叶 PTE 的 G 位不使用。切换进程不清空。
- 任何 `sfence.vma` 都整体清空 walk-cache。

## 7. PTW ↔ D-cache 接口

MMU 侧对 D-cache 提出的接口（D-cache 内部实现在 D-cache 设计中完成）：

| 方向 | 信号 | 说明 |
| --- | --- | --- |
| MMU → D-cache | `req.valid / req.ready` | 握手；`ready` 前 MMU 保持请求不变 |
| MMU → D-cache | `req.paddr` | PTE 物理地址，8 B 对齐 |
| D-cache → MMU | `resp.valid` | 单拍有效，MMU 随时接收 |
| D-cache → MMU | `resp.data[63:0]` | PTE |
| D-cache → MMU | `resp.accessFault` | PMP/PMA 检查失败 |

- 只读，8 B。Svade 下不需要写和原子操作。
- PMP 检查按 S 模式权限进行（页表隐式访问一律按 S 模式检查，不受 MPRV 影响）。
- 一个请求被 `ready` 接收后，D-cache 保证最终给出 `resp`。
- 前进保证由下面两条共同成立：
  1. TLB miss 的请求在 N+1 判定 miss 后，不分配任何 D-cache 资源（MSHR、写缓冲槽位等）；
  2. D-cache 为 PTW 通道保留可用的 miss 资源，或给予其最高优先级。

## 8. sfence.vma

`sfence.vma` 由核心串行化执行：执行前流水已排空、store buffer 已写入 D-cache、PTW 空闲；执行后核心冲刷前端并从下一条指令重新取指。

MMU 内的动作：

| rs1 | rs2 | iTLB / dTLB | walk-cache |
| --- | --- | --- | --- |
| ≠ x0 | = x0 | 用 VA 查找两部分阵列，清除 VPN 匹配的表项（不论 ASID、G） | 全部清空 |
| ≠ x0 | ≠ x0 | 用 VA 查找，清除 VPN 匹配、`ASID == rs2` 且 `G == 0` 的表项 | 全部清空 |
| = x0 | 任意 | 全部清空（含 G 表项；多刷合法） | 全部清空 |

按 VA 查找复用 TLB 的查询路径：N 拍选组读出，N+1 拍比较，匹配的路在 valid 触发器上清零；超页阵列按各自 level 比较。

必须加入的断言，防止串行化条件在集成时被遗漏：

- `sfence.valid` 时 PTW 处于 `IDLE`，两个 miss 寄存器均无效；
- `sfence.valid` 时 iTLB、dTLB 没有正在进行的查询；
- `sfence.valid` 时 store buffer 为空（核心层断言）；
- sfence 处理期间不发生 TLB refill。

## 9. 尚未定的参数

- TLB、超页阵列、walk-cache 的组数和路数：按仿真统计调整，默认值见上。

## 10. 参考

- [RISC-V 特权规范：`satp`、Sv39、页表遍历、超页与 `SFENCE.VMA`](https://docs.riscv.org/reference/isa/priv/supervisor.html)
- [Rocket PTW](https://raw.githubusercontent.com/chipsalliance/rocket-chip/master/src/main/scala/rocket/PTW.scala)
- [Rocket TLB](https://raw.githubusercontent.com/chipsalliance/rocket-chip/master/src/main/scala/rocket/TLB.scala)
