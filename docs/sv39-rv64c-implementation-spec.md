# Breeze Sv39 MMU 与 RV64C 第一版实现规格

## 1. 文档状态与边界

本文记录已经确认、但尚未实现的第一版架构方案。不得把本文中的计划描述成
当前 RTL 已具备的能力。

第一版目标包含：

- 每 hart 独立 ITLB 和 DTLB；
- 每 hart 一个由 ITLB/DTLB 共享的阻塞式 Sv39 PTW；
- 16-bit ASID、global mapping 和完整本地 `SFENCE.VMA`；
- L1I/L1D 采用 VIPT，L2/Home、coherence 和 MMIO 全部使用物理地址；
- Sv39 4 KiB、2 MiB、1 GiB 页、权限检查和硬件 A/D 更新；
- redirect/flush 下可取消、可 drain、不会产生陈旧 refill 的 PTW；
- 可独立配置的 RV64C 前端、指令重对齐与 16-to-32 bit 解压。

明确不包含以下性能优化：shared L2 TLB、page-walk cache、多个并行 PTW、
hit-under-miss、错误路径 TLB refill、硬件跨 hart TLB shootdown、Sv48/Sv57。

特权档位和 C 扩展必须正交：

- `mcu`：M-mode、Bare，可启用 C；
- `linux`：M/S/U，可启用 Sv39 和 C；
- 保留关闭 C 的兼容构建，用于旧测试和问题隔离。

## 2. 每 hart 的 MMU 结构

第一版固定为：

```text
Frontend -> 16-entry fully-associative ITLB --+
                                               +-> shared blocking Sv39 PTW
Backend  -> 16-entry fully-associative DTLB --+              |
                                                              v
                                             DCache physical/PTW port -> L2
```

PTW 同时只处理一个 miss。DTLB miss 优先于 ITLB miss，因为顺序流水线中的
数据访问通常比正在等待的新取指更老。阻塞结构下每个 DTLB walk 最终会结束，
因此该固定优先级不会形成永久饥饿。

PTW 页表访问必须经过本 hart 的 coherent DCache 物理旁路端口，不能另接一条
绕过一致性的 Wishbone 通道。这样 PTW 可以看到 DCache 内尚未写回的页表修改，
并能通过现有 AMO/coherence 路径原子更新 PTE 的 A/D 位。

## 3. ASID、TLB 项和 `satp`

RV64 Sv39 `satp` 字段为：

```text
63:60  MODE     0 = Bare, 8 = Sv39
59:44  ASID     第一版实现完整 16 bit
43:0   PPN      根页表物理页号
```

每个 TLB 项至少保存：

```text
valid, asid[15:0], global, vpn, ppn, leafLevel,
R, W, X, U, A, D
```

命中条件为页级别屏蔽后的 VPN 匹配，并满足
`entry.global || entry.asid == satp.asid`。ASID 0 是正常 ASID，不表示关闭
ASID。若 walk 路径上任意非叶 PTE 的 G 位为 1，最终叶项必须标记为 global。

写 `satp` 立即改变后续翻译上下文，但本身不自动清空 TLB。软件负责在复用
ASID或修改页表后执行正确的 `SFENCE.VMA`。

## 4. `SFENCE.VMA`

必须支持四种本地失效：

| rs1 | rs2 | 失效范围 |
| --- | --- | --- |
| `x0` | `x0` | 全部项，包括 global |
| `x0` | ASID | 指定 ASID，保留 global |
| VA | `x0` | 覆盖该 VA 的全部 ASID，包括 global |
| VA | ASID | 指定 VA 和 ASID，保留 global |

VA 定向失效必须按 `leafLevel` 判断该 VA 是否落入 4 KiB、2 MiB 或 1 GiB
映射，不能只比较完整 VPN。

`SFENCE.VMA` 到 WB/commit 后才允许生效，并作为串行化操作：

```text
停止接受新翻译
-> 取消尚未发出的 PTW，或 drain 已被接受的 PTW
-> 禁止旧 PTW refill
-> 精确失效 ITLB/DTLB
-> 更新 MMU epoch
-> fence 退休并重新取指
```

`SFENCE.VMA` 只作用于当前 hart。SMP shootdown 由软件发送 IPI，使其他 hart
分别执行本地 `SFENCE.VMA`；第一版不做硬件广播 TLB 失效。

## 5. Redirect、flush 与在途 PTW

第一版采用“显式 kill 状态机 + epoch 守卫”：

```text
PTW request 未被 DCache 接受：立即撤销
PTW request 已被接受：等待并消费响应，但丢弃结果
响应 epoch 或 alive 不匹配：不响应、不 refill、不更新 A/D、不报异常
```

维护两个代际：

- `fetchEpoch`：普通分支纠正、异常 redirect、前端 flush 时更新，只杀死旧
  取指消费者；
- `mmuEpoch`：`satp` 写、`SFENCE.VMA`、有效特权级或翻译权限上下文改变时
  更新，防止旧翻译上下文返回。

每个 PTW miss 上下文保存 source(I/D)、VA、ASID、`satp.PPN`、原始访问类型、
有效 privilege、SUM/MXR/MPRV、两个 epoch 和 alive 状态。

普通分支 redirect 只能杀死错误路径 ITLB 请求。它不得无条件杀死更老的
DTLB load/store/AMO。异常、pipeline flush 和特权上下文改变则按流水线年龄
决定 D 请求是否仍然存活。

PTW FSM 至少包含：

```text
Idle -> Select -> MemRequest -> MemWait -> CheckPte
     -> AdUpdateRequest -> AdUpdateWait -> Refill/Respond
     -> DrainDiscard
```

## 6. Sv39 PTW 语义

Sv39 虚拟地址首先检查 canonical form：VA[63:39] 必须全部等于 VA[38]。
三级 VPN 每级 9 bit，PTE 为 8 byte：

```text
pteAddr = (tablePpn << 12) + (vpn[level] << 3)
level: 2 -> 1 -> 0
```

第一版必须处理：

- `V=0`、`R=0 && W=1` 和保留位等非法 PTE；
- `R || X` 的叶项判断；
- 4 KiB、2 MiB、1 GiB leaf；
- superpage 低 PPN 对齐；
- instruction/load/store/AMO 的 R/W/X/U 权限；
- S-mode 的 SUM、MXR；
- MPRV 只影响数据访问，不影响取指；
- 页表物理访问失败映射为原始访问类型对应的 access fault；
- instruction/load/store-AMO page fault cause 12/13/15；
- `xepc` 保存原指令 PC，`xtval` 保存真正 faulting VA。

为了直接运行未修改的 xv6/Linux，第一版采用硬件 A/D 更新，而不是要求软件
实现 Svade：

- fetch/load 遇到 A=0，原子置 A；
- store/AMO 遇到 A=0 或 D=0，原子置 A、D；
- 更新必须针对完整 64-bit PTE 原子执行；
- 已被 kill 的错误路径请求不得开始 A/D 更新。

## 7. ITLB 与 VIPT ICache

正常取指路径：

```text
S1: virtual PC -> BTB/PHT + ITLB lookup + ICache SRAM read
S2: translated PA -> execute permission/PMA/PMP + physical tag compare
S3: instruction realign/decompress + MiniDecode + fetch buffer
```

分支预测器继续使用虚拟 PC。当前 32 B line、64 set 的 ICache 使用 5 bit
line offset 和 6 bit set index，共 11 bit，位于 4 KiB 页内偏移中，满足 VIPT
约束。tag、refill 地址、PMA/PMP、L2 请求必须改用 PA。

ITLB miss 锁存 PC、翻译上下文和 epoch，阻塞取指，PTW refill 后重放原 PC。
错误路径 page fault 不得送入后端。

## 8. DTLB 与 VIPT DCache

数据路径：

```text
EXE: ALU 产生 VA
MEM1: DTLB lookup 与 DCache VA-index SRAM read 并行
MEM2: PA permission/PMA/PMP + physical tag compare + cache/MMIO 决策
```

当前 8 KiB、4-way、32 B line 的 DCache 同样为 64 set，offset+index 为
11 bit，满足 VIPT 约束。应增加 elaboration `require`，保证未来配置仍满足
`lineBytes * sets <= 4096`。

DTLB miss 必须锁存完整 LSU 请求：VA、load/store/AMO、size、write data/mask、
aq/rl、整数或 FP 写回信息、有效 privilege、SUM/MXR/MPRV、请求 alive/epoch。
PTW 完成后只重放一次，确保 store/AMO 不重复执行。

以下地址必须为物理地址：DCache tag、L2/Home line address、coherence transaction、
LR/SC reservation、MMIO/PMA/PMP 查询。store/AMO 只有在翻译、权限和存活检查
全部通过后才允许修改 cache。

TLB 项保存 PTE 权限，每次 hit 使用当前 SUM/MXR/effective privilege 重新检查，
使这些 CSR 修改立即生效而不依赖 TLB flush。

## 9. RV64C 总体结构

C 扩展主要落在前端，但并非只增加一个组合解码器。第一版仍保持后端只接收
32-bit canonical instruction：

```text
ICache 32-bit aligned fetch word
    -> instruction realigner/halfword buffer
    -> compressed decoder (16 -> canonical 32)
    -> existing MiniDecode/backend decoder
```

送入后端的 fetch bundle 增加：

```text
pc
inst[31:0]          // 解压后的 canonical 指令
rawInst[31:0]       // 原始 16/32-bit 指令，16-bit 时高位清零
instLen[1:0]        // 2 或 4 byte
isCompressed
illegalCompressed
fetch fault metadata
prediction metadata
```

执行、寄存器读取、ALU、LSU 和正常 32-bit decoder 不需要复制一套 C 指令
实现；绝大多数压缩指令先展开为既有 RV64I/F/D 指令。reserved encoding 输出
`illegalCompressed`，architectural hint 按规范展开为 NOP，`C.EBREAK` 展开为
标准 EBREAK。

## 10. 指令重对齐与跨界取指

启用 C 后 IALIGN 从 32 变为 16，32-bit 指令允许从 `PC[1]=1` 开始。当前
ICache 直接按 32-bit word 选择数据，不能正确处理该情况，必须增加
`BreezeInstrRealigner`。

第一版保持单发射、每次最多输出一条指令，并使用 32-bit 对齐 fetch word：

1. 请求地址为 `PC & ~3`，同时保留原始 PC；
2. `PC[1]=0` 时从低半字判断长度；
3. `PC[1]=1` 时从高半字判断长度；
4. 若高半字是 32-bit 指令开头，再请求下一个对齐 word，将两个半字拼接；
5. 使用一个带 aligned VA/epoch 的 32-bit word buffer，避免连续两条压缩指令
   反复读取同一个 ICache word；
6. redirect 使 realigner、word buffer 和在途第二次取指全部失效。

必须覆盖三种边界：

- 32-bit word 边界；
- ICache line 边界；
- 4 KiB page 边界。

跨页 32-bit 指令需要对两个页分别进行 ITLB/权限检查。若后半部分 fault，
`xepc` 指向指令起始 PC，`xtval` 指向真正 fault 的页边界地址。已经取得前半条
指令不能绕过第二页的执行权限。

## 11. PC、预测器和异常需要同步修改

所有固定 `pc + 4` 必须改为 `pc + instLen`，包括：

- 顺序取指 next PC；
- branch not-taken fall-through；
- JAL/JALR link value；
- FENCE.I 后继续执行的位置；
- redirect expected-next-PC 比较；
- tandem/debug retirement next PC。

BTB 必须保存精确到 2 byte 的虚拟 PC 和 target。BTB/GShare 索引应丢弃
恒为零的 PC[0]，但必须保留 PC[1]；不能继续隐含按 `PC >> 2` 建索引。RAS
压栈返回地址使用 `PC + instLen`。

`MiniDecode` 可以继续观察解压后的 32-bit JAL/branch/JALR，但必须同时获得
`instLen`，以计算 fall-through 和 link semantics。

启用 C 后：

- `mepc/sepc` WARL 对齐只强制 bit 0 为零，不能继续清零 bits[1:0]；
- branch/jump/xRET target 的 bit 1 是合法地址位；
- JALR 仍按 ISA 清零 target bit 0；
- trace、debug、非法指令 `xtval` 应保留原始 compressed bits；
- `minstret` 对一条 compressed 指令只增加一次。

## 12. RV64C 指令范围

整数部分完整实现 RV64 的标准 compressed integer 指令，不把 RV32 专用编码
误解为 RV64 指令。特别注意 RV64 中的 `C.LD/C.SD/C.ADDIW`，以及
`C.JR/C.JALR/C.MV/C.ADD/C.EBREAK` 共享编码的判定条件。

F/D 由独立编译能力控制：

- D 启用时实现标准 compressed double load/store 形式并解压到 FLD/FSD；
- 对当前配置未实现的压缩浮点编码产生 illegal instruction；
- 不把 RV32-only 的压缩浮点编码带入 RV64。

第一版不实现 `Zcb/Zcmp/Zcmt` 等额外 Zc 扩展。

## 13. 配置与验收边界

建议最终配置能力拆为：

```text
enableCompressed
enableSupervisorUser
enableSv39
enableFloat
enableDouble
```

并添加一致性约束，例如 `enableSv39` 必须依赖 `enableSupervisorUser`，而
`enableCompressed` 不依赖 MMU 或 S/U。

第一版完成的判据不是“能解压几个 opcode”，而是同时满足：

- 所有 RV64C legal/reserved/hint 编码分类正确；
- 混合 16/32-bit 顺序流和所有 word/line/page 边界正确；
- branch、JAL/JALR、trap、xRET、FENCE.I 的 PC 长度语义正确；
- MCU Bare+C 和 Linux Sv39+C 两种配置都能 elaboration；
- C 关闭时保持原 RV64 兼容路径；
- 后续分别通过 directed tests、`riscv-tests`/architectural tests 和 LiteX 固件。

## 14. xv6 启动目标与平台边界

实现 C、ITLB/DTLB、Sv39 只完成了 CPU 地址翻译能力，不等同于 xv6 已经可用。
第一版 xv6 目标冻结为：

```text
4 hart 启动
-> M-mode early boot
-> mret 进入 S-mode
-> 建立并启用 Sv39 kernel page table
-> S-mode timer interrupt
-> PLIC supervisor external interrupt
-> UART console
-> block device/root fs
-> init 进入 U-mode
-> 出现可交互 shell
```

“只执行到一个串口 PASS marker”和“启动到 xv6 shell”必须作为两个不同里程碑。
前者可以临时使用 polling UART 且不依赖 PLIC；后者需要完整的中断和块设备闭环。

### 14.1 PLIC 是否必要

PLIC 对以下最小探针不是绝对必要：

- 单 hart M->S；
- `satp`/Sv39 开启；
- S/U page fault；
- polling UART 输出固定 marker；
- 内存中预置的一小段 U-mode 程序。

但对尽量少修改的 xv6、UART 输入、异步 console write、VirtIO block interrupt、
多 hart S-mode external interrupt以及最终 shell，PLIC 视为第一版必需平台模块。

第一版不以“把所有外部中断 OR 到 hart0 的 MEIP”代替 PLIC。xv6 需要每 hart
独立的 supervisor external interrupt context，并通过 claim/complete 得到 IRQ ID。

### 14.2 PLIC 硬件范围

新增 LiteX 侧 `BreezePlic` Wishbone slave，使用 QEMU virt/xv6 熟悉的地址：

```text
PLIC base                 0x0c00_0000
source priority[id]       base + 0x0000 + 4*id
pending bitmap            base + 0x1000
S enable[hart]            base + 0x2080 + 0x100*hart
S threshold[hart]         base + 0x201000 + 0x2000*hart
S claim/complete[hart]    base + 0x201004 + 0x2000*hart
```

第一版实现 32 个 source ID 和每 hart 一个 S context；source 0 永远表示 no
interrupt。至少冻结：

```text
IRQ 10 = UART
IRQ 1  = block device（若使用 VirtIO-MMIO）
```

每个 context 独立保存 enable、threshold 和 in-service；仲裁选择
`pending && enabled && priority > threshold` 中优先级最高的 source，同优先级
选择较小 ID。claim 清除/占用该 source，complete 允许 level source 再次 pending。

PLIC 输出为：

```text
seip[hart] = hart 的 S context 存在可领取中断
```

Cluster/Core 接口增加独立的 `supervisorExternalInterrupt[hart]`，写入 `mip.SEIP`
bit 9。不能继续只把平台输入写入 `mip.MEIP` bit 11，再期待 `mideleg` 自动把
它变成 SEIP。MCU profile 保留现有简单 external-interrupt 路径；xv6/linux
profile 选择 PLIC 路径。

### 14.3 Timer：现有 CLINT 与当前 xv6 的差异

现有 Flow 已有经典 CLINT：

```text
msip[hart]     0x0200_0000 + 4*hart
mtimecmp[hart] 0x0200_4000 + 8*hart
mtime          0x0200_bff8
```

它产生 MSIP/MTIP，适合 MCU 和较老的 xv6 M-mode timer shim。但当前上游 xv6
在 early boot 中启用 Sstc，并在 S-mode 直接读 `time`、写 `stimecmp`，期待
STIP cause 5。

第一版全面方案选择支持当前 Sstc 路线：

- 实现 `menvcfg.STCE` 的合法 WARL 行为；
- 实现每 hart 的 `stimecmp` CSR；
- `time >= stimecmp[hart]` 产生独立 STIP；
- 将 STIP 写入 `mip.STIP` bit 5；
- `mcounteren.TM` 控制 S-mode 对 `time` 的访问，U-mode 还需同时满足
  `scounteren.TM`；
- CLINT `mtime/mtimecmp` 和 MTIP 路径继续保留给 MCU/M-mode 软件。

若为了更早 bring-up 选择固定的旧 xv6 commit，可以暂时沿用 M-mode timer shim，
但必须把 xv6 commit SHA 和 timer ABI 写入运行记录，不能把旧版本通过等同于
当前上游 xv6 通过。

### 14.4 PMP 与 `menvcfg.ADUE`

当前 xv6 M-mode start 会写 `pmpaddr0/pmpcfg0`，给 S-mode 开放物理内存；它还
会设置 `menvcfg.ADUE`，表明使用硬件 A/D 更新。

因此 xv6 profile 至少需要：

- 实现可读写/WARL 的 `pmpaddr0`、`pmpcfg0`；
- entry 0 支持 xv6 使用的 TOR 全地址空间 R/W/X 配置；
- ICache、DCache、PTW 页表访问均接入 PMP 检查；
- 实现 `menvcfg.ADUE`，并与本规格的 PTW 原子 A/D 更新一致；
- 不允许简单把这些 CSR 当作非法指令。

后续 Linux 产品化可以扩展为 8 或 16 个 PMP entry；xv6 第一启动门槛只要求
entry 0 和正确的默认拒绝/允许语义。

### 14.5 UART、块设备和地址图

当前 Breeze MCU 地址图与 QEMU virt/xv6 不同：当前 boot ROM 占用
`0x1000_0000`，LiteX UART 位于 `0x1200_1000`；标准 xv6 则把 UART 放在
`0x1000_0000`、VirtIO-MMIO 放在 `0x1000_1000`、RAM 放在 `0x8000_0000`。

不得破坏已经存在的 MCU 地址图。新增独立 `xv6`/`linux` platform profile：

```text
mcu profile:
    保持当前 reset vector、boot ROM、LiteX UART 和 CLINT地址

xv6 profile:
    reset/boot stub 与设备地址按 xv6 平台冻结
    kernel load address = 0x8000_0000
    PLIC = 0x0c00_0000
    UART = 0x1000_0000（16550兼容子集或明确的软件端口）
    block = 0x1000_1000（推荐 VirtIO-MMIO）
```

推荐为 xv6 profile 增加最小 16550-compatible UART wrapper，而不是让 xv6
依赖 LiteX 动态 CSR 地址。这样 xv6 和后续 Linux/device tree 可以复用同一
稳定平台 ABI。

启动到 shell 还必须提供 root filesystem block device。优先方案为仿真侧
VirtIO-MMIO block，加载 xv6 `fs.img` 并通过 PLIC IRQ 1 中断。临时 RAM disk
只能作为更早 bring-up 阶段，不能作为“未修改 xv6 shell”完成证据。

当前仿真 main RAM 为 32 MiB，而上游 xv6 的默认物理内存上限通常按 QEMU virt
设置得更大。第一版应在固定 xv6 port 中把 `PHYSTOP` 与真实 32 MiB RAM 一致，
避免为了软件常量在 Verilator 中实例化不必要的 128 MiB 寄存器内存。

### 14.6 OpenSBI 边界

xv6 自身从 M-mode entry 启动、配置 delegation/PMP/timer，然后 `mret` 到
S-mode，因此第一版 xv6 不要求 OpenSBI。

正常 Linux 启动则建议采用：

```text
Boot ROM -> OpenSBI(M-mode) -> Linux(S-mode)
```

OpenSBI、device tree、Linux virtio/console 是 xv6 shell 之后的独立阶段，不能
加入 xv6 第一版 RTL 验收而无限扩大当前实现范围。

### 14.7 xv6 profile 的 CPU/SoC 配置

建议最终增加：

```text
PrivilegeProfile.Mcu:
    enableCompressed = selectable/default true
    enableSupervisorUser = false
    enableSv39 = false
    enableSstc = false
    enablePlic = false

PrivilegeProfile.Xv6:
    enableCompressed = true
    enableSupervisorUser = true
    enableSv39 = true
    enableSstc = true
    enablePmp = true
    enablePlic = true
    numHarts = 1/2/4 selectable
```

`misa.C`、toolchain `-march`、xepc 对齐和 C decoder 必须由同一个
`enableCompressed` 控制。Sv39、Sstc、PMP 和 PLIC 不能仅在软件字符串中声明。

### 14.8 实施顺序

在“同一个最终目标”内仍按以下可定位顺序落地：

1. C realigner/decompressor 与全流水线 `instLen`；
2. `satp`、ASID、ITLB/DTLB、PTW、VIPT physical tag；
3. page fault、A/D、PMP、`SFENCE.VMA`；
4. 单 hart M->S->Sv39->U 内存探针，不依赖 PLIC；
5. Sstc/STIP 和 S-mode timer trap；
6. PLIC + SEIP + UART IRQ；
7. block device + `fs.img`；
8. 单 hart xv6 shell；
9. 2/4 hart xv6 SMP、IPI 和 TLB shootdown场景。

每一级失败时保留第一个异常 cause、`xepc`、`xtval`、当前 privilege、`satp`、
PTW state 和最后一个物理总线事务，禁止只用延长 watchdog 掩盖卡死。
