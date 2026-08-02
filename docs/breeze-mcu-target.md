# Breeze MCU 总体目标

## 文档性质

本文档记录 BreezeCore 和 Breeze MCU SoC 的已确认总体目标。

它描述的是“项目最终要做成什么”，不是当前实现状态，也不表示下列功能已经完成。当前实现进展和已知问题仍应以源码、测试及 bug 记录为准。

ICache、DCache 与 LiteX 之间的具体接口调研和实现建议见
[litex-cache-interface-research.md](litex-cache-interface-research.md)。

## 总目标

将现有 BreezeCore 完善为一颗面向 MCU / 嵌入式场景的 64 位单发射顺序 RISC-V 核，并使用 LiteX 作为 SoC 集成框架。

早期目标不是 FPGA 上板，而是在 LiteX 仿真 SoC 中真正使用 BreezeCore：从 ROM / RAM 取指，经过 ICache 和 DCache 访问存储，并能够访问 UART、Timer 等 MMIO 外设。

目标 ISA 和执行环境为：

- `RV64I_Zicsr_Zifencei`
- 仅支持 Machine mode
- 运行裸机程序或轻量 RTOS
- 采用 little-endian
- 程序地址直接作为物理地址使用

## 已确认的架构取舍

### 处理器核心

- 保持单发射、顺序执行微架构。
- 完善 RV64I 整数指令、Zicsr、Zifencei 和基本 Machine-mode trap / interrupt 能力。
- 不实现 S-mode 和 U-mode。
- 不实现 MMU、TLB、页表、`satp` 和地址翻译。
- 不以启动 Linux 为目标。
- 第一阶段不把分支预测优化作为主线工作。

### Cache

- 保留并完善现有 ICache。
- 增加一个正式的数据 Cache，而不是仿真占位模块。
- DCache 采用少量 entry 的全相连结构，以较低复杂度提供基本数据缓存能力。
- ICache 与 DCache 在核心内部保持相互独立。
- DCache 的 entry 数、cache line 大小、替换算法、写策略和 miss 策略在后续专项设计中确定，本文档暂不提前冻结。
- MMIO 访问必须绕过 DCache。
- `FENCE.I` 必须保证先前已经完成的数据写入能够被后续本 hart 的取指观察到。

### LiteX SoC

- 使用 LiteX 负责 SoC 框架、地址空间、片上存储、外设和仿真环境。
- 为 BreezeCore 建立正式的 LiteX CPU wrapper。
- 将 ICache miss/refill 路径和 DCache miss/writeback 路径接入 LiteX 总线。
- 初期优先采用 LiteX/Wishbone 接口，不要求一开始实现 AXI。
- 初期 SoC 至少包含 Boot ROM、RAM、UART 和 Timer。
- 未映射地址或总线错误应返回核心，并形成对应的 instruction/load/store access fault。

### 存储与平台

- 初期不需要 DDR、DDR controller 或 DDR PHY。
- Boot ROM 用于复位入口和启动代码。
- RAM 用于程序、数据、栈和堆，并允许按地址映射策略执行其中的代码。
- 初期使用 LiteX 仿真存储或 FPGA 片上 RAM 对应的存储模型。
- 将来容量需求增加时，可以在不改变核心 ISA 定位的前提下扩展 DDR 或其他外部存储。
- 早期不处理 FPGA 板级时钟、引脚约束、DDR 校准和 bitstream 下载。

## 目标结构

```text
                           BreezeCore
                  +------------+-------------+
                  |                          |
               ICache              small fully-associative
                  |                       DCache
                  |                          |
          instruction bus adapter    data bus adapter
                  |                          |
                  +-------- LiteX bus -------+
                               |
                +--------------+--------------+
                |              |              |
             Boot ROM         RAM        UART / Timer
```

## 分阶段目标

### 第一阶段：核心架构闭合

- 补齐目标 ISA 的指令和非法编码处理。
- 完善 Machine-mode CSR、同步异常、机器中断和 `mret`。
- 补齐 instruction/load/store 的 misaligned 和 access-fault 路径。
- 明确核心内部 ICache、DCache 与 MMIO 的访问边界。

### 第二阶段：DCache 与总线

- 完成少量 entry、全相连 DCache 的专项设计。
- 实现 refill、替换、写入、脏数据处理和 MMIO bypass。
- 为 ICache 和 DCache 建立可靠的 LiteX/Wishbone 事务适配。

### 第三阶段：LiteX 仿真 SoC

- 实现可生成 RTL 的 BreezeCore LiteX 顶层和 CPU wrapper。
- 接入 Boot ROM、RAM、UART、Timer 和地址译码。
- 让软件能够在 LiteX 仿真框架中启动并访问存储和外设。

### 后续阶段

- 在核心和 LiteX 仿真 SoC 稳定后，再考虑 FPGA 上板。
- DDR、更多外设、更大的 Cache、乘除法扩展和性能优化均属于后续可选扩展，不进入当前第一版目标。

## 尚待后续讨论的参数

以下问题尚未决定，后续应分别形成设计文档：

- DCache entry 数量和每个 entry 的 line 大小。
- DCache 使用 write-through 还是 write-back。
- store miss 是否 write-allocate。
- DCache 的替换算法及脏 line 回写流程。
- I/D Wishbone 数据宽度、burst 或多拍传输方式。
- LiteX ROM、RAM 和 MMIO 的具体地址及容量。
- UART、Timer 和外部中断的具体寄存器接口。
