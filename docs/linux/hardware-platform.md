# Flow Linux 硬件平台

本文定义 Flow 四核 Linux 仿真所依赖的 CPU、Cache、一致性、内存和外设契约。这里
只写仓库可移植信息；机器登录、工具绝对路径和私有构建目录不属于本文件。

## 1. CPU core

Linux 使用 `linux` privilege profile。相较于早期 xv6/MCU 目标，当前 core 补齐了：

- M/S/U 特权级、`mstatus/sstatus`、trap delegation、M/S interrupt pending/enable；
- `MRET`、`SRET`、`WFI`、`SFENCE.VMA` 及其特权检查；
- 16 项 RV64 PMP（TOR、NA4、NAPOT）及 page-table walk/访问侧检查；
- Sv39，私有 instruction/data TLB，硬件 page-table walk 和精确 TLB invalidation；
- RV64C 指令重排与 16/32-bit 指令长度推进；
- RV64A 的 LR/SC 与 9 类 AMO，支持 W/D 宽度并接入一致性 DCache；
- RV64F/D 执行路径、RV64M、Zicsr、Zifencei；
- instruction/load/store/page/access/misaligned 等异常进入统一 trap 路径；
- 精确 `EBREAK` decode，保留编码不误判，breakpoint exception 使用 `mcause=3`；
- PMA 在 ICache、DCache 和 MMIO 路径统一分类，device region 不进入 Cache。

这不等价于已经通过完整 RISC-V 架构认证。Linux 启动仍是系统级验收门槛，不能只靠
单元测试宣布兼容。

## 2. 多核与 Cache

集群有三种固定 profile：

| Profile | hart | 每核 L1I | 每核 L1D | 共享 L2 |
| --- | ---: | ---: | ---: | ---: |
| `single` | 1 | 8 KiB | 8 KiB | 16 KiB |
| `dual` | 2 | 8 KiB | 8 KiB | 32 KiB |
| `small` | 4 | 8 KiB | 8 KiB | 64 KiB |

L1I/L1D 都是 4-way、32 B cache line。L1D 为 write-back、write-allocate，并通过
共享 L2/home 维护多 hart 一致性。L2 容量按 `numHarts * 2 * L1D` 冻结，Linux 当前
使用四核 `small` profile。

LR/SC reservation 属于每 hart DCache；probe、eviction、flush、store/SC/AMO 和 trap
会按实现规则清除 reservation。AMO 在获得独占权限后完成 read-modify-write。MMIO
由独立仲裁路径处理，不能缓存，也不能作为原子内存使用。

## 3. LiteX/LiteDRAM SoC

Linux 仿真复用 LiteX 的 SoC 集成和 LiteDRAM 控制器结构：

- DDR 模块模型为 DDR3 `MT41K64M16`；
- 容量限制为 256 MiB；
- 仿真 PHY 使用 `SDRAMPHYModel`；
- Flow 集群已经含共享一致性 L2，因此 LiteX SDRAM 侧 `l2_cache_size=0`；
- 宿主机在仿真开始前装入 OpenSBI、DTB 和 kernel/payload；
- 后续 FPGA 适配可替换 PHY、时钟与管脚，而保留 CPU 到 LiteDRAM 的系统结构。

当前没有板级约束、真实 DDR PHY 校准或 FPGA timing closure 结论。

## 4. 地址空间

| 区域 | 地址 | 大小 | 属性 |
| --- | ---: | ---: | --- |
| CLINT | `0x0200_0000` | 64 KiB | device, R/W |
| PLIC | `0x0c00_0000` | 64 MiB window | device, R/W |
| Linux reset ROM | `0x1001_0000` | 64 KiB | cacheable, R/X |
| SRAM | `0x1100_0000` | 256 KiB | cacheable, R/W/X |
| LiteX CSR | `0x1200_0000` | 16 MiB window | device, R/W |
| ns16550a UART | `0x1300_0000` | 256 B | device, R/W |
| DDR | `0x8000_0000` | 256 MiB | cacheable, R/W/X |

Linux UART 从早期的 `0x1000_0000` 移到 `0x1300_0000`，避免与真实 reset/ROM 地址
规划冲突。PMA 对 DDR 覆盖整个 `0x8000_0000..0x8fff_ffff`，不能继续保留早期
32 MiB RAM 上限。

## 5. 中断控制器

CLINT 提供：

- 每 hart `msip`，用于 IPI；
- 每 hart `mtimecmp`；
- 全局 64-bit `mtime`，timebase 为 1 MHz。

PLIC 支持 31 个外部 source，并为每个 hart 提供 M-mode 和 S-mode context。Linux
16550 UART 固定使用 PLIC source 10。LiteX 内部 CSR interrupt vector 不与 source 10
做 OR，避免设备树无法表达的中断别名。

## 6. 16550 UART

`litex_wrapper/flow/uart16550.py` 实现 Linux/OpenSBI bring-up 所需寄存器：

- RBR/THR 和 DLAB 下的 DLL；
- IER/DLM；
- IIR 与 FCR 接受；
- LCR、MCR、LSR、MSR、SCR；
- LSR 的 THRE/TEMT；
- RX 和 TX interrupt 条件。

TX/RX 内部直接复用 LiteX UART 同类的 16-entry buffered `stream.SyncFIFO`，状态位
描述本地 FIFO，而不是下游 PHY 的组合 `ready`。这样即使仿真 backend 只在看到
`valid` 后拉高 `ready`，OpenSBI 也能观察到复位后的 THRE/TEMT 并发送第一个字符。
串行 PHY 仍由 LiteX 提供；设备树将寄存器前端描述为 `ns16550a`，clock 1.8432 MHz、
115200 baud、`reg-io-width=1`。

## 7. Boot contract

reset ROM 执行：

1. `a0 = mhartid`；
2. `a1 = 0x8010_0000`（DTB）；
3. 跳到 `0x8000_0000`（OpenSBI）。

OpenSBI `fw_jump` 构建参数必须保持：

```text
FW_TEXT_START=0x80000000
FW_JUMP_ADDR=0x80200000
FW_JUMP_FDT_ADDR=0x80100000
```

DDR 装载布局：

```text
0x80000000  OpenSBI fw_jump.bin
0x80100000  flow-small.dtb
0x80200000  handoff smoke or Linux Image
```

`software/breeze-linux/handoff-smoke.bin` 是正确的交接探针。不要使用
`bootrom.bin` 充当 kernel：它被链接用于 reset ROM，放在 `0x8020_0000` 执行时仍会
跳回 `0x8000_0000`，导致 OpenSBI 被重复进入。

## 8. 仿真与 FPGA 边界

目前已实现的是可综合 CPU/集群 RTL 加 LiteX 行为级 SoC 仿真。尚未实现或验证：

- 板级 CRG、复位同步、DDR PHY 校准和约束；
- FPGA 上的 UART pin、实际 PLIC source 接线和时钟频率；
- 持久化存储控制器；
- 以太网、PCIe 或 VirtIO transport；
- Linux 完整启动和多核用户空间压力验证。

无盘 Buildroot/Alpine 不要求先加入块设备。以后增加 SD/eMMC/NVMe 时，只需扩展 SoC
设备、设备树和驱动配置，不需要改变当前 OpenSBI/kernel 的基本装载地址。
