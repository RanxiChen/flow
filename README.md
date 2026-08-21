# Flow / Breeze RISC-V Processor

Flow 是一个使用 Chisel 实现的 64 位 RISC-V 处理器与多核 SoC 项目。当前开发主线
已经从裸机 MCU 扩展到四核 Linux：CPU 具备 M/S/U 特权级、Sv39、PMP、RV64A 和
压缩指令，多核系统包含私有 L1、共享一致性 L2、CLINT、PLIC、16550 UART，以及由
LiteX/LiteDRAM 管理的 256 MiB DDR3 仿真内存。

现阶段优先在 LiteX/Verilator 中完成可重复的软件仿真，再进行 FPGA 板级适配。Linux
启动不依赖 VirtIO：OpenSBI、DTB 和内嵌 initramfs 的 Linux `Image` 由宿主机直接装入
模拟 DDR，持久化块设备留到后续阶段。

## 最新进展

截至 2026-08-21，仓库已经完成：

- 1/2/4 hart 可参数化集群，四核 `small` profile 使用 64 KiB 共享 L2；
- Linux profile：M/S/U trap/CSR、delegation、PMP、Sv39、TLB、`SFENCE.VMA`、
  `FENCE.I`、RV64C、RV64A LR/SC/AMO，以及 Linux 所需异常分类；
- 精确识别 `EBREAK`，产生 breakpoint exception（`mcause=3`、`mtval=0`）；
- Linux PMA：256 MiB DDR、CLINT、PLIC、LiteX CSR 和独立 16550 UART 区域；
- LiteX 仿真中的 LiteDRAM DDR3 控制器路径，关闭额外 LiteX L2，避免绕过 Flow 的
  一致性 home/L2；
- 四核 reset ROM、OpenSBI `fw_jump`、DTB 和 kernel 的固定装载契约；
- 全新 Buildroot external tree，可生成四核、musl、initramfs Linux 镜像；
- 基于 Alpine 官方 RISC-V minirootfs 的无盘 `Image-alpine` 构建脚本；
- Linux bring-up 进度、每 hart retirement、fatal 和 16550 MMIO 诊断；
- 可复用的三层存储 monitor：Tandem 退休结果、DCache PMA/route、Wishbone
  request/response，并支持地址过滤和独立 trace 文件；
- Chisel 完整回归 43 个 suite、181 个测试全部通过。

当前边界也要明确：Buildroot 和 Alpine 镜像已经真实构建成功，四核 ROM 到 OpenSBI
的执行路径也已进入实际 RTL 仿真；但 OpenSBI early console 当前仍停在 16550 LSR
轮询，尚未取得 handoff、Linux kernel 与用户空间完整启动日志。应先用单核短测试和
三层 memory trace 定位 CPU 到 SoC 的 byte-MMIO 返回路径，再继续 handoff 和真实
`Image`，不能把“镜像成功装入 DDR”当成“Linux 已启动”。详细证据见
[`docs/linux/verification-status.md`](docs/linux/verification-status.md)。

## 结构概览

| 层级 | 当前实现 |
| --- | --- |
| Core | RV64、单发射、顺序五级流水，GShare/BTB 可选 |
| ISA | RV64IMAFDC、Zicsr、Zifencei；Linux profile 启用 A/C/F/D 路径 |
| Privilege | 可选 MCU（M-only）或 Linux（M/S/U）profile |
| MMU | Sv39、私有 I/D TLB、硬件 page-table walk、PMP、`SFENCE.VMA` |
| L1 | 每 hart 8 KiB 4-way ICache + 8 KiB 4-way DCache，32 B line |
| Coherence | 私有 coherent L1D + 共享 L2/home，支持 1/2/4 hart 与 LR/SC/AMO |
| Linux SoC | 4 hart、256 MiB LiteDRAM、CLINT、PLIC、16550 UART、reset ROM |
| Firmware | OpenSBI 1.9 `fw_jump`，DTB 位于固定 DDR 地址 |
| Rootfs | Buildroot initramfs；Alpine minirootfs 重新内嵌进独立 kernel Image |

硬件和启动地址的权威说明在
[`docs/linux/hardware-platform.md`](docs/linux/hardware-platform.md)。

## Linux 启动契约

| 内容 | 地址 | 说明 |
| --- | ---: | --- |
| Reset ROM | `0x1001_0000` | 设置 `a0=mhartid`、`a1=DTB`，跳到 OpenSBI |
| OpenSBI | `0x8000_0000` | `fw_jump.bin` |
| DTB | `0x8010_0000` | 四核 Flow 平台描述 |
| Linux / payload | `0x8020_0000` | Buildroot `Image`、`Image-alpine` 或 handoff smoke |
| DDR | `0x8000_0000` | 256 MiB，结束于 `0x9000_0000` |

`sim/litex/linux_sim.py` 会检查镜像范围和重叠，然后把三段镜像装入 LiteDRAM。它不
创建 VirtIO 磁盘，Buildroot 和 Alpine 的根文件系统均内嵌在 kernel Image 中。

## 快速阅读顺序

后续开发者或 Agent 建议按以下顺序阅读：

1. 本 README：项目目标和当前边界；
2. [`docs/linux/hardware-platform.md`](docs/linux/hardware-platform.md)：CPU、SoC、
   地址与中断契约；
3. [`docs/linux/buildroot-alpine.md`](docs/linux/buildroot-alpine.md)：从干净源码构建
   OpenSBI、Buildroot 和 Alpine；
4. [`docs/linux/verification-status.md`](docs/linux/verification-status.md)：已验证项、
   未完成门槛和已知陷阱；
5. [`sim/litex/README.md`](sim/litex/README.md)：仿真命令和诊断选项；
6. `design/src/main/scala/config/config.scala`、
   `design/src/main/scala/top/BreezeMulticoreClusterWishbone.scala` 和
   `sim/litex/multicore_sim.py`：实现源代码。

## 构建 Linux 辅助镜像

先生成 reset ROM、handoff smoke 和 DTB：

```bash
make -C software/breeze-linux
```

handoff smoke 链接到 `0x8020_0000`。OpenSBI 成功进入 S-mode payload 后，它会向
Linux 16550 UART 写出字符 `K`，随后留在 `WFI` 循环。它用于把“OpenSBI 仍在运行”
和“OpenSBI 已完成交接”区分开。

生成四核 Linux debug RTL：

```bash
cd design
sbt "runMain flow.top.GenerateBreezeMulticoreClusterWishbone small gshare linux debug"
cd ..
```

`debug` 保留 retirement 接口；最终综合准备可使用 `production`，它关闭 tandem
trace，减少非产品端口和逻辑。

Buildroot、Alpine 和完整仿真命令见
[`docs/linux/buildroot-alpine.md`](docs/linux/buildroot-alpine.md)。这些仿真可能运行很
久，建议使用独立输出目录和可持久保存的日志。

## MCU 与回归

Linux profile 没有删除原有 MCU 流程。裸机程序仍可使用：

```bash
python3 sim/litex/run_mcu.py \
    --main software/breeze-mcu/apps/main.c \
    --core-preset gshare \
    --elaborate
```

主要回归入口：

```bash
cd design
sbt test
```

定向仿真、GShare、Timer/UART、1/2/4 hart 测试见
[`sim/litex/README.md`](sim/litex/README.md) 和
[`software/breeze-mcu/README.md`](software/breeze-mcu/README.md)。

## 更多文档

- [Linux 硬件平台](docs/linux/hardware-platform.md)
- [Buildroot 与 Alpine](docs/linux/buildroot-alpine.md)
- [Linux 验证状态](docs/linux/verification-status.md)
- [LiteX 仿真](sim/litex/README.md)
- [MCU 总体目标](docs/breeze-mcu-target.md)
- [GShare](docs/gshare-status.md)
- [PMU](docs/pmu.md)
- [Tandem trace](docs/tandem-trace.md)
