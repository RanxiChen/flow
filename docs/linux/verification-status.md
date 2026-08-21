# Linux bring-up 验证状态

本文只记录已经执行过的证据及其边界。构建成功、结构检查、固件执行和 Linux 启动是
四个不同门槛，不能互相替代。

## 已通过

### CPU/RTL

- 迁移前基线完整 Chisel 回归：43 suite、181 tests，全部通过；
- LiteUART迁移后的 Linux PMA定向测试：3 tests通过，覆盖 DDR、LiteUART CSR页面和
  MCU boot ROM；当前提交的完整 Chisel回归留给目标机执行，不能沿用旧基线替代；
- DCache 定向测试：12 tests 通过；
- 精确 EBREAK decode 与 breakpoint trap 测试通过；
- 四核 Linux debug RTL 和关闭 tandem 的 production RTL 均成功 elaboration。

### SoC

- 四个 hart 都能从 Linux reset ROM `0x1001_0000` 启动；
- debug RTL 和 LiteX 仿真已接入统一三层 memory monitor：Tandem 退休内存结果、
  DCache PMA/route/response、CPU memory/MMIO Wishbone request/response；
  monitor 只读信号，支持地址和事件数量过滤；
- LiteDRAM DDR3 模型按 256 MiB 建立，OpenSBI/DTB/payload 能完成范围与重叠检查；
- CLINT、PLIC 和原生 LiteUART 都进入 Linux SoC地址图；
- LiteUART CSR位于 `0x1200_1000`，其 `ev.irq` 单独连接 PLIC source 10；
- 独立 `FlowPlic.sv` 模块测试取得 `[FLOW-PLIC-PASS]`；单 hart CPU集成测试取得
  `[MULTICORE-SINGLE-LITEUART-PLIC-PASS]`，日志闭环覆盖 UART event、PLIC source 10、
  pending、MEIP、claim/complete、handler 和 `MRET`；
- 独立 `FlowClint.sv` 模块测试取得 `[FLOW-CLINT-PASS]`；Linux profile 进一步取得
  `[MULTICORE-SINGLE-CLINT-MSIP-PASS]`、`[MULTICORE-SINGLE-CLINT-MTIP-PASS]`、
  `[MULTICORE-SMALL-CLINT-IPI-PASS]` 和
  `[MULTICORE-SMALL-CLINT-PER-HART-TIMER-PASS]`；
- 四 hart IPI逐 hart寻址通过，`msip[0..3]` 未出现共享总线字 lane别名；per-hart
  timer先只触发 hart 3，再分别触发全部 hart，排除了 MTIP广播实现；
- 原生 LiteUART FIFO/status/raw-event Python单元测试、SoC/DTS/Buildroot契约测试和
  memory monitor单元测试通过；
- LiteUART CSR与PLIC两个短固件、reset ROM、handoff smoke和 DTB 均完成编译；
- DTB声明 `litex,liteuart`，kernel fragment和 getty名称与上游驱动契约一致。

上述独立中断控制器的目标机运行提交为：

| 门槛 | Commit | Required marker |
| --- | --- | --- |
| PLIC 模块级 | `31acc63` | `[FLOW-PLIC-PASS]` |
| LiteUART→PLIC→CPU | `31acc63` | `[MULTICORE-SINGLE-LITEUART-PLIC-PASS]` |
| CLINT 模块级 | `47c769b` | `[FLOW-CLINT-PASS]` |
| 单 hart MSIP/MTIP | `47c769b` | `[MULTICORE-SINGLE-CLINT-MSIP-PASS]`、`[MULTICORE-SINGLE-CLINT-MTIP-PASS]` |
| 四 hart IPI | `43ef995` | `[MULTICORE-SMALL-CLINT-IPI-PASS]` |
| 四 hart 独立 timer | `43ef995` | `[MULTICORE-SMALL-CLINT-PER-HART-TIMER-PASS]` |

这些 marker 证明外设与 CPU 中断闭环，不证明 OpenSBI、kernel 或用户空间启动。

### 软件构建

在一次干净的 Buildroot 2026.05.1 构建中，已生成：

| Artifact | 大小 | SHA-256 |
| --- | ---: | --- |
| Buildroot `Image` | 约 33 MiB | `e2b609cc47ae5fdf0970062a7f01629a96b2640b97e27ce283b94b3e805c66c4` |
| `rootfs.cpio.gz` | 约 6.8 MiB | `4e928179dec3f2127d46c7d2254566b1b0a52815dab58914669fa6acf99ae7fe` |
| OpenSBI `fw_jump.bin` | 约 272 KiB | `fa96fc4b7b7f56c2110f713c3268db62eb90951f0fec0f56e81b811007fefd41` |
| `flow-small.dtb` | 2434 B | `f876002d20f7ce146d02def3bbb9e174ce2a464e7163ea047d85b7d71c440c22` |
| Alpine `Image-alpine` | 约 29 MiB | `adcedf0c3a86d48e6f6fad6e10ce124eaa158b25029acfa2ca2bd30bfae08e38` |

这些 hash 证明的是该次构建产物身份；版本、工具链或时间戳变化后，重建 hash 可以不同。

### OpenSBI 执行

- 四个 hart 均从 ROM 跳到 `0x8000_0000` 并持续退休 OpenSBI 指令；
- 2000 万周期运行中每 hart 退休约 229 万到 237 万条指令；
- 没有 `LINUX-FATAL`；
- 符号解析显示 boot hart 在 libfdt 初始化，其他 hart 经过
  `_wait_for_boot_hart`、HSM wait 和 atomic 状态路径。

## 尚未通过

- 当前提交尚未取得目标机完整 `sbt test`结果；
- 新的单核 Linux-profile LiteUART CSR短测试尚未取得
  `[MULTICORE-SINGLE-LITEUART-CSR-PASS]` Verilator运行证据；
- LiteUART迁移后的 handoff smoke尚未运行，尚未输出 `K`；
- OpenSBI UART banner 尚未取得；
- Buildroot Linux kernel 尚未取得 earlycon、SMP 和用户空间启动日志；
- Alpine 尚未取得 `/init` 或 shell 日志；
- 尚未运行真实块设备、网络或 FPGA 板级验证。

### 2026-08-22 Buildroot 首次长跑定位

在 `6352366` 的四 hart Buildroot 运行中，Verilator 的主仿真线程保持约 100% CPU，约 6100 万
周期内四个 hart 都持续退休且没有 `LINUX-FATAL`，但没有任何 UART MMIO、OpenSBI
banner 或 Linux console 输出。符号定位显示 hart 0/2/3 位于 OpenSBI HSM wait，boot
hart 1 后期反复位于 `_trap_handler`、`sbi_trap_handler` 和 `sbi_trap_redirect`。
这证明仿真器没有机械卡死，但软件启动进度异常；旧日志缺少 trap cause/epc/tval、
特权级和中断输入，因此不能据此把根因判给 CPU、CLINT/PLIC 或 kernel。

debug RTL 现已在核心精确 trap 点直接打印 `[CORE-TRAP]`，不增加顶层接口或额外
monitor。下一次运行应先用约 3500 万周期取得 trap 序列，再根据 cause、PC、特权级、
target 和中断输入决定修 CPU 还是 SoC，不再盲目等待一亿周期。

因此当前项目状态是：**硬件平台与镜像构建已实现，长时间 Linux 运行验证待完成**。

## 已纠正的误区

早期诊断曾把 `software/breeze-linux/build/bootrom.bin` 同时作为 kernel 占位文件。
这不是合法 handoff payload：OpenSBI 跳到 `0x8020_0000` 后会执行其中“跳回
`0x8000_0000`”的 reset 逻辑，造成 OpenSBI 重入。由此产生的 2000 万周期运行只可
用于证明四核和 OpenSBI 持续执行，不能证明 S-mode handoff。

仓库已经加入正确的 `handoff-smoke.bin`：入口链接到 `0x8020_0000`，轮询 LiteUART
`TXFULL`、写 `K` 后停在 WFI。后续应以 `K` 为 OpenSBI交接门槛。

旧自研16550路径曾在 Python/Migen测试中显示 LSR byte lane正确，但生成后的 Verilog
实际把动态移位量截断，真实CPU短测读回零。该结果证明旧测试模型不能作为生成RTL的
证据，也是迁移到原生 LiteUART的直接原因。

第一次 LiteUART PLIC短测进一步发现 `1-bit irq << 9` 在生成 Verilog后仍按1-bit
求值，使 PLIC sources恒为零。接线已改为显式31-bit `Cat`；同次审计也替换了旧单核
SoC的8-bit IRQ移位和所有调试监控中的 Wishbone地址移位，并加入生成Verilog位宽测试
及项目 Migen Signal-left-shift禁入检查。该接线随后被独立 `FlowPlic.sv` 取代，并已
取得模块级和 CPU集成级 PASS；上述旧路径问题不再属于当前 Linux profile。

## 下一次验证顺序

1. 在目标 commit上运行完整 `sbt test`；
2. 运行 LiteUART CSR短测，要求独立 PASS marker；
3. 重新构建 Buildroot，使 OpenSBI、DTB、kernel和 getty全部切到 LiteUART；
4. 用足够长 watchdog运行 handoff smoke，保存 `K`、UART MMIO和 per-hart progress；
5. handoff通过后依次运行 Buildroot `Image`和 `Image-alpine`；
6. 每一步单独保存命令、commit SHA、镜像 SHA、退出码和 fatal/panic扫描；
7. 在仿真完全闭环前不进入 FPGA上板或持久化存储实现。
