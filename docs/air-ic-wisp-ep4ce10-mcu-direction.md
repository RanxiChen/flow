# Air-IC 与 Wisp：EP4CE10 MCU 实现记录和后续方向

## 1. 定位

Air 和 Wisp 是 Flow 工程中面向 EP4CE10F17C8 的两个独立 RV64 MCU 核。

- **Air-IC**：单核、单硬件线程、单指令在途，使用 8-bit 串行数据通路和单个 byte-wide 寄存器文件 RAM，支持 RV64I、RV64C、Zicsr 和 Zifencei。
- **Air-I**：Air-IC 去掉 C 扩展后的对照版本，用于测量压缩指令取指和解压逻辑的资源、时序代价。
- **Wisp**：保留已有实现和未来硬件多线程/异构计算研究边界，不与 Air 共享核心 RTL。

Air-I 和 Air-IC 已证明窄数据通路 RV64 核可以通过 LiteX 流程在 EP4CE10 上完成综合、布局布线并生成 SOF。项目从这里开始冻结 CPU 内部面积优化，不再为追求更低 LE 反复重写微架构。

下一阶段的目标是把 **Air-IC 和 Wisp 都做成真正可编程、可使用板载外设的 MCU**。实现以开拓者 EP4CE10 开发板官方原理图、引脚表和开发指南为硬件依据，以 LiteX 作为 SoC 和外设组织框架。

## 2. Air 微架构

Air 不是流水线处理器。同一时刻只有一条指令在执行，由微状态机依次完成取指、寄存器读取、运算、访存和写回。

主要约束如下：

- 32-bit 统一 Wishbone 指令/数据主接口；
- 32-bit 物理地址，64-bit RISC-V 整数语义；
- 8-bit 物理运算通路，64-bit 运算按 byte 多周期完成；
- 一个 `256 x 8-bit` 的物理寄存器文件 RAM；
- 寄存器文件只有一个同步读端口和一个写端口；
- `rs1`、`rs2` 在同一条指令内部流水读取：发出 A 地址、发出 B 地址、依次收回 A/B；
- 移位采用迭代实现，不实例化 64-bit barrel shifter；
- 不包含乘除法器、Cache、MMU、分支预测、指令流水和硬件多线程；
- Machine Mode 提供最小 CSR、异常和 trap 路径；
- Air-IC 增加 16-bit parcel 取指、跨 32-bit Wishbone word 的指令拼接和 RV64C 整数指令解压。

这种设计节省宽运算器和多端口寄存器文件的资源，代价是每条指令需要更多周期。面积优先不等于时钟频率低：窄组合路径仍可运行在较高时钟，只是单线程指令吞吐率较低。

## 3. 当前 LiteX MCU

当前 EP4CE10 Air/Wisp 公共 MCU SoC 包含：

- EP4CE10F17C8；
- 50 MHz 时钟，管脚 `E1`；
- 低有效复位，管脚 `M1`；
- 4 个 LED，管脚 `D11/C11/E10/F9`，由 4-bit GPIO CSR 控制；
- 6 位动态扫描数码管，位选 `N16/N15/P16/P15/R16/T15`，段选
  `M11/N12/C9/N13/M10/N11/P11/D9`；
- UART RX/TX，管脚 `A12/B12`，115200 baud；
- 8 KiB boot ROM，地址 `0x1000_0000`；
- 8 KiB SRAM，地址 `0x1100_0000`；
- LiteX CSR 区，基地址 `0x1200_0000`；
- LiteX control、UART 和 timer；
- 统一 shared Wishbone interconnect。

公共固件位于 `software/ep4ce10-mcu/`。Air-I、Air-IC 和 Wisp 均从
`0x1000_0000` 启动，使用相同的 UART、Timer、GPIO、数码管和中断薄驱动。

## 4. 验证结果

### 4.1 RTL 与固件仿真

完整 Air 测试共 6 项，全部通过：

- 单端口 byte-wide 寄存器文件同步读写；
- 代表性 RV64C 指令解压和保留编码拒绝；
- Air-I 的 ALU、双源 RF 调度、load/store 和 branch；
- Air-IC 的压缩指令执行；
- Air-IC 执行跨 32-bit 取指 word 的标准 32-bit 指令；
- Air-I、Air-IC 分别启动独立编译的 UART 固件。

这些测试证明了当前实现的基本执行闭环，但不能替代完整的 RISC-V ISA compliance suite。

### 4.2 Quartus 18 实测

目标器件为 EP4CE10F17C8，工程由 LiteX 生成，再调用 Quartus Prime 18.0 完成全流程编译。

| 项目 | Air-I | Air-IC |
| --- | ---: | ---: |
| 整个 LiteX MCU Logic Elements | 3,871 / 10,320（38%） | 4,375 / 10,320（42%） |
| 整个 MCU Registers | 1,249 | 1,315 |
| 整个 MCU Memory Bits | 67,840（16%） | 67,840（16%） |
| 整个 MCU M9K | 11 | 11 |
| Embedded Multiplier 9-bit elements | 0 | 0 |
| AirCore Logic Cells | 2,780 | 3,248 |
| AirCore Registers | 605 | 671 |
| AirCore RF | 1 M9K，2,048 bit | 1 M9K，2,048 bit |
| Slow 1200 mV 85 C Fmax | 55.6 MHz | 53.92 MHz |
| 50 MHz setup slack | +2.013 ns | +1.455 ns |

两种实现都通过 50 MHz 时序并生成约 351 KiB 的 SOF：

- `build/ep4ce10-air-i/gateware/air_ep4ce10_i.sof`
- `build/ep4ce10-air-ic/gateware/air_ep4ce10_ic.sof`

Air-IC 相对 Air-I 增加 504 个整机 LE。RVC 解压器实体本身约 124 logic cells，其余增量主要来自变长取指、parcel 缓冲和控制路径。

当前 Air 并没有比既有 Wisp 更省逻辑。这个结果被保留为真实工程数据，但不再触发新一轮 CPU 重写：EP4CE10 仍有超过一半 LE 可供外围设备和实验逻辑使用，而“能作为 MCU 使用”现在比继续减少几百个 LE 更重要。

## 5. 构建方法

在 `design/` 下生成两套 RTL：

```bash
sbt 'runMain flow.top.GenerateAirI' 'runMain flow.top.GenerateAirIC'
```

构建两个独立固件：

```bash
make -C software/air-ep4ce10 variants
```

生成 LiteX/Quartus 工程：

```bash
python3 fpga/ep4ce10_air/target.py --variant i
python3 fpga/ep4ce10_air/target.py --variant ic
```

Quartus 工程分别位于：

```text
build/ep4ce10-air-i/gateware/air_ep4ce10_i.qpf
build/ep4ce10-air-ic/gateware/air_ep4ce10_ic.qpf
```

运行 Air 测试：

```bash
cd design
sbt 'testOnly flow.air.AirRegisterFileSpec flow.air.AirRvcDecompressorSpec flow.air.AirCoreSpec'
```

## 6. 冻结决策

从本记录开始执行以下约束：

1. Air 的默认使用版本是 **Air-IC**；Air-I 作为面积和取指对照保留。
2. 不再继续 Air 内部面积优化，不因当前结果高于 Wisp 而重写核心。
3. 不把 Wisp 改造成 Air，也不让两者共享核心 RTL。
4. Air-IC 和 Wisp 都作为 MCU CPU 接入相同类型的 LiteX 板级外设。
5. 后续新增功能优先放在 LiteX SoC、外设控制器、存储系统和软件驱动层。
6. 每个外设都必须根据官方资料确认管脚、电气标准和板上跳帽/复用关系，不能仅凭同系列开发板经验推断。

## 7. 下一阶段：把两个核心做成真正的 MCU

外设工作不以“一次加入所有东西”为目标，而以每个外设形成完整可验证闭环为标准：

```text
官方手册/原理图
  -> 管脚与电气约束
  -> LiteX 外设或独立控制器
  -> Wishbone/CSR 地址映射
  -> 固件驱动
  -> 仿真
  -> Quartus fit/timing
  -> JTAG 下载
  -> 板上现象和串口日志
```

建议优先顺序：

1. **GPIO/LED/按键**：建立最小 MMIO 输入输出和板上读写闭环。
2. **UART**：从固定启动字符串扩展成可收发、可交互的 MCU console。
3. **Timer**：验证计时、轮询和 Machine Mode 中断路径。
4. **SPI Flash 或 SD 卡**：实现不重新综合 FPGA 即可更换程序或加载数据的存储路径。
5. **板载 SDRAM**：增加可供程序、数据和后续矩阵/模型实验使用的主存。
6. **数码管、蜂鸣器、红外、I2C/SPI 等板载资源**：以官方手册确认的实际器件和管脚为准逐项加入。

Air-IC 与 Wisp 应分别运行同一组外设 smoke firmware。这样可以把 CPU 正确性、LiteX 外设正确性和板级电气问题分开：同一外设在两个核心上都通过，说明 MCU 平台层已经具备可复用性；只在一个核心失败时，再回到对应 CPU 的总线、异常或软件路径定位。

## 8. 当前证据边界

已经确认：

- Air-I/Air-IC RTL 可生成；
- 基本指令和 UART 固件仿真通过；
- LiteX 工程可调用本机 Quartus 18；
- 两个版本均完成 fit、timing 和 SOF 生成；
- 50 MHz 时序通过；
- 寄存器文件进入 1 个 M9K；
- 不使用硬件乘法器；
- Wisp SOF 已通过 USB-Blaster 下载到真实 EP4CE10F17C8；
- Wisp 在板上执行公共 MCU 固件，UART 启动时输出一次 `MCU IRQ READY`；
- Timer Machine 中断、`WFI` 唤醒、4-bit LED GPIO 和六位数码管均已形成板级闭环；
- 数码管已实际观察到十进制计数，并确认 `000009 -> 000010` 进位正确。

尚未确认：

- Air 当前公共 MCU SOF 尚未通过 JTAG 下载并在物理开发板运行；
- UART、LED、数码管尚未在 Air 上完成同一套板级对照；
- 全量 RV64I/RV64C/Zicsr/Zifencei compliance；
- GPIO、按键、SDRAM、SD 卡等新增外设；
- 两个核心使用同一外设测试套件的板级对照结果。

后续文档和提交必须继续区分“仿真通过”“Quartus 通过”“生成 SOF”和“物理板验证通过”，不能用前一种证据替代后一种证据。

## 9. MCU 公共骨架（2026-08-24）

Air-IC 和 Wisp 现在具有同一套 Machine Mode 中断与板级软件接口。该骨架不是完整 PLIC，而是面向 EP4CE10 小型 MCU 的固定、可扩展中断约定：

```text
LiteX Timer IRQ1  -> CPU timerIrq    -> mip.MTIP -> mcause interrupt 7
其他 LiteX IRQ    -> CPU externalIrq -> mip.MEIP -> mcause interrupt 11
```

两颗核心都增加：

- `mie`（`0x304`）与只读硬件 pending 视图 `mip`（`0x344`）；
- `mstatus.MIE/MPIE/MPP` 的 trap 进入和 `mret` 恢复语义；
- `mie.MTIE/MEIE`；
- 标准 Machine Timer Interrupt 和 Machine External Interrupt cause；
- 只在指令边界接受异步中断；
- `WFI` 等待状态；
- 独立 `timerIrq` 和 `externalIrq` 核接口。

LiteX 为两个 SoC 固定相同 IRQ 编号：

| IRQ | 名称 | 当前状态 |
| ---: | --- | --- |
| 0 | UART | 已实例化，汇总到 MEIP |
| 1 | Timer0 | 已实例化，直接映射 MTIP |
| 2 | GPIO input IRQ | 预留，尚未实例化输入 GPIO |
| 3 | Watchdog | 预留，尚未实例化 Watchdog |

外部 IRQ 的具体来源仍由各 LiteX 外设自己的 `ev_pending/ev_enable` 寄存器识别和清除。这保留了以后加入 UART RX、GPIO input、SPI、SD 等中断的空间，同时避免在 EP4CE10 上立即实例化完整 PLIC。

## 10. 公共 MCU 地址与 BSP

两个 SoC 使用同一地址规范：

| 区域 | 地址 | 状态 |
| --- | ---: | --- |
| Control | `0x12000000` | 已实例化 |
| UART | `0x12001000` | 已实例化 |
| Timer0 | `0x12002000` | 已实例化 |
| Matrix | `0x12003000` | Wisp 可选，Air 保留空洞 |
| GPIO | `0x12004000` | 已实例化，低 4 bit 驱动 DS0--DS3 |
| Seven-segment | `0x12005000` | 已实例化，digits/enable/dots 三个 CSR |
| Watchdog | `0x12006000` | 地址预留，尚未实例化 |

原先直接连接 LED 的 `areaProbe` 已从板级输出移除。4 个 LED 现在由
LiteX `GPIOOut` 的软件可写寄存器驱动。数码管控制器由硬件以每位约
1 ms 的周期自主扫描，软件只写入 6 个压缩十六进制数字、位使能和小数点位图。

公共裸机 BSP 位于 `software/ep4ce10-mcu/`，提供：

- Air-I、Air-IC、Wisp 三个编译 profile；
- 8 KiB ROM / 8 KiB SRAM linker script；
- `.data` 复制、`.bss` 清零和栈初始化；
- 保存全部整数寄存器的 Machine trap 入口；
- GPIO、Timer、UART 和中断的薄驱动头文件；
- `WFI` 主循环；
- 每秒 Timer ISR 清除 pending、软件计数加一、以低 4 bit 更新 LED，并更新
  六位 packed-BCD 数码管显示。

默认固件启动后先输出：

```text
MCU IRQ READY
```

随后以板载 50 MHz `E1` 时钟为 Timer 时基，配置 `50,000,000` 周期的周期事件。这里不使用 PLL，也不把系统 Timer 称为 RTC。真正掉电保持的 RTC 需要以后根据官方手册确认外部低速时钟或 RTC 器件。

## 11. MCU 骨架验证结果

仿真已经覆盖：

- Air 的 MEIP 进入、`mcause`、指令边界 `mepc` 和 `mret`；
- Wisp 的 MTIP、`WFI` 唤醒、`mcause`、指令边界 `mepc` 和 `mret`；
- Air-IC 执行真实编译的公共 C/汇编固件；
- Wisp 执行真实编译的公共 C/汇编固件；
- UART 启动字符串；
- Timer MMIO 配置；
- `WFI` 后注入 Timer IRQ；
- ISR 清除 `timer0_ev_pending`；
- ISR 写 `gpio_out=1`；
- Wisp 连续处理 10 次真实固件 Timer IRQ 后写出 `seg7_digits=0x000010`；
- Wisp 寄存器型 `SLLW/SRLW` 的非零移位量回归。

加入 MCU 骨架后的 Quartus 18 结果：

| 项目 | Air-IC 中断骨架基线 | Wisp 当前完整 MCU |
| --- | ---: | ---: |
| 整机 Logic Elements | 4,880 / 10,320（47%） | 4,005 / 10,320（39%） |
| 整机 Registers | 1,319 | 1,351 |
| 整机 M9K | 11 | 12 |
| DSP 9-bit elements | 0 | 0 |
| 核 Logic Cells | 3,428 | 2,322 |
| 核 Registers | 673 | 620 |
| Slow 85 C Fmax | 55.54 MHz | 58.52 MHz |
| 50 MHz setup slack | +1.996 ns | +2.913 ns |

两个工程都完成 Analysis & Synthesis、Fitter、Assembler 和 Timing Analyzer，生成新 SOF，并满足 50 MHz。

Wisp 当前 SOF 已于 2026-08-25 使用 USB-Blaster 成功配置到真实器件，Quartus
Programmer 报告目标为 `EP4CE10F17@1`，JTAG ID 为 `0x020F10DD`。板上已经确认：

- UART 使用 P2 的 A12/B12 TTL 路径，启动字符串只打印一次；
- Timer 每秒进入 Machine Timer ISR，主循环使用 `WFI`；
- DS0--DS3 显示软件秒计数低 4 bit；
- 六位数码管持续显示十进制秒计数；
- 最低位从 9 清零时，次低位正确进 1。

Air-IC 仍只具有仿真和较早中断骨架的 Quartus 证据；加入 4-bit GPIO/数码管后的
Air 当前资源以及同一套物理板现象尚未复测，不能用 Wisp 的板级结果代替。

## 12. Wisp 十进制进位缺陷记录（2026-08-25）

首次上板时，数码管最低位计满 10 后清零，但次低位没有进 1。固件反汇编表明，
第一次进位路径会首次执行非零寄存器型 `SLLW/SRLW`。Wisp 的 byte-wide RF
按 byte0 到 byte7 读取源寄存器，旧实现却在最后一个读取周期才采样 `rs2` 作为
移位量，因此取到 `rs2[63:56]`，而不是保存 shamt 的 `rs2[7:0]`。

修复方式是在读取 `rs2` byte0 时锁存移位量，之后再进入迭代 Shift 状态。为防止
再次漏测，验证增加了两层覆盖：

1. 指令级测试执行非零寄存器型 `SLLW` 和 `SRLW`；
2. MCU 固件测试连续注入 10 次 Timer IRQ，并要求最终写出
   `seg7_digits=0x000010`。

修复后的 RTL 仿真、固件仿真、Quartus 18 Full Compilation、50 MHz 时序、JTAG
下载和真实数码管进位现象全部通过。当前产物为：

```text
software/ep4ce10-mcu/build/wisp/led-timer-irq.bin
  size   816 bytes
  sha256 28f297027d04f79cb54595471411ad5d29f02064bae07f7e386b56540a79a4a5

build/ep4ce10-wisp/gateware/wisp_ep4ce10.sof
  size   358699 bytes
  sha256 4e53189babed93fb70de47cfecafb5dd7846c27417c86b1e46c837a34ab40c5a
```
