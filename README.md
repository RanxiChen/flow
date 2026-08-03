# Breeze MCU

Breeze MCU 是一个基于 BreezeCore 和 LiteX 的 64 位 RISC-V 仿真 MCU。
当前版本已经打通从用户 `main.c`、裸机 runtime、RTL elaboration 到
LiteX/Verilator SoC 仿真的完整流程。用户只需要提供 `int main(void)`，脚本会完成
交叉编译、链接、ROM 加载、仿真运行和有限结束判断。

目前的主要目标是贴近真实 SoC 集成过程的可执行仿真，而不是 FPGA 上板。核心通过
Wishbone 访问 LiteX 提供的 ROM、SRAM、主存和 MMIO 外设；后续上板将单独建立板级
时钟、约束、存储和固件流程。

## 当前能力

- RV64 单发射、顺序执行、五级流水线，按 `IF / ID / EX / MEM / WB` 组织；
- `RV64I_Zicsr_Zifencei`，little-endian；
- 仅 Machine mode，不包含 MMU、TLB、页表、S-mode 和 U-mode；
- Machine-mode exception、interrupt、Direct/Vectored `mtvec` 和 `mret`；
- 独立 ICache、DCache 和 64-bit instruction/data Wishbone master；
- LiteX 仿真 SoC：Boot ROM、SRAM、main RAM、UART 和 machine timer；
- 通用裸机 runtime：启动、栈、`.data`/`.bss`、trap frame、UART/Timer API；
- `main()` 返回后通过 SRAM completion mailbox 结束仿真，不依赖 UART 文本解析，
  也不引入自定义停止指令或 IPC MMIO。
- M-mode PMU 提供 `mcycle`、`minstret` 和 8 个可编程 HPM counter，统计控制流、
  预测失败、Cache miss、uncached 访问和访存停顿；runner 自动计算 IPC。

默认 LiteX 顶层使用 `baseline` 配置，不启用分支预测。GShare 必须通过
`--core-preset gshare` 显式选择；两套 RTL 和 Verilator 产物使用独立目录，切换配置
不会复用另一套产物。

## 微架构参数

| 项目 | 当前默认配置 |
| --- | --- |
| 核心 | RV64，单发射，顺序执行，五级流水线 |
| ISA | `RV64I_Zicsr_Zifencei` |
| 特权级 | Machine mode only |
| 地址 | 核内 64-bit 地址；当前 MCU 平台使用 32-bit 物理地址空间 |
| 分支预测 | 默认关闭；可选 GShare 为 8-bit GHR、16-entry BTB |
| ICache | 8 KiB，4-way，64 sets，32 B line，32-bit fetch，pseudo-LRU |
| DCache | 256 B，8-entry fully-associative，32 B line |
| DCache 策略 | blocking、write-back、write-allocate、invalid-first/round-robin replacement |
| MMIO | PMA 标记为 device/non-cacheable，由 DCache bypass |

ICache 和 DCache miss 都采用阻塞式处理。一个 32-byte cache line 会在 64-bit
Wishbone 上拆成 4 个 beat。DCache 同时支持带 byte select 的单 beat MMIO/scalar
访问和多 beat cache-line refill/writeback。

## 总线与仿真 SoC

BreezeCore 对外提供相互独立的 instruction 和 data Wishbone master：数据宽度
64 bit，平台地址宽度 32 bit，`adr` 使用 8-byte word address。LiteX 将两路 master
接入共享 Wishbone interconnect，并负责存储和 MMIO 地址译码。

| 区域 | 地址 | 大小 | 属性 |
| --- | ---: | ---: | --- |
| Machine timer | `0x0200_0000` | 64 KiB | device，non-cacheable |
| Boot ROM | `0x1000_0000` | 64 KiB | read/execute，cacheable |
| SRAM | `0x1100_0000` | 256 KiB | read/write/execute，cacheable |
| LiteX MMIO | `0x1200_0000` | 16 MiB | device，non-cacheable |
| Main RAM | `0x8000_0000` | 32 MiB | read/write/execute，cacheable |

复位 PC 为 `0x1000_0000`。程序地址直接作为物理地址使用。

## UART 与 Timer

UART 使用 LiteX UART，基地址为 `0x1200_1000`。固件可通过
`breeze_uart_putc()`、`breeze_uart_puts()` 和 `breeze_uart_put_hex64()` 输出调试
信息，字符会直接显示在仿真终端。UART 也连接到 external interrupt source 0，最终
汇聚为 Machine External Interrupt。

Timer 是项目自有的 64-bit machine timer，而不是 LiteX CSR timer：

| 寄存器 | 地址 | 说明 |
| --- | ---: | --- |
| `mtimecmp` | `0x0200_4000` | 64-bit read/write compare value |
| `mtime` | `0x0200_bff8` | 64-bit read/write counter |

Timer timebase 为 1 MHz，比较命中后直接产生 `mtip`，对应 Machine Timer
Interrupt。固件可使用 `breeze_timer_read()` 和 `breeze_timer_set_compare()`。

## 环境准备

仿真需要 Python 3、LiteX/Migen、Verilator、Java/sbt、GNU Make，以及提供
`riscv64-unknown-elf-*` 的 RISC-V 裸机工具链。LiteX 可按
[官方安装说明](https://github.com/enjoy-digital/litex/wiki/Installation) 安装；本项目
只使用 LiteX/Migen 和 Verilator 仿真，不需要 FPGA 厂商工具。

可以先检查主要命令：

```bash
python3 -c 'import litex, migen'
verilator --version
sbt --version
riscv64-unknown-elf-gcc --version
```

## 编写并运行 `main.c`

用户程序只需要实现 `int main(void)`。例如新建 `hello.c`：

```c
#include "breeze/uart.h"

int main(void)
{
    breeze_uart_puts("Hello from Breeze MCU!\r\n");
    return 0;
}
```

在仓库根目录运行：

```bash
python3 sim/litex/run_mcu.py --main hello.c --elaborate
```

`--elaborate` 会先通过 sbt 重新生成 `BreezeCoreWishbone` RTL。RTL 没有变化时，
后续仿真可以省略它：

```bash
python3 sim/litex/run_mcu.py --main hello.c
```

默认命令使用 baseline。显式启用 GShare 并重新生成对应 RTL：

```bash
python3 sim/litex/run_mcu.py --main hello.c \
    --core-preset gshare --elaborate
```

脚本会依次完成：

1. 将用户 `main.c` 与项目 runtime、trap、UART、Timer 代码一起编译；
2. 按 `RV64I_Zicsr_Zifencei` 链接固件并生成 ELF、binary、反汇编和符号表；
3. 将 binary 加载到 `0x1000_0000` Boot ROM；
4. 生成并运行 LiteX/Verilator 仿真；
5. 等待 `main()` 返回以及 completion store 退休，输出 PASS/FAIL、PMU 和 IPC 并结束。

`main()` 返回 `0` 表示成功，非零表示失败。正常结束时可以看到：

```text
BREEZE_PERF cycles=<cycles> instructions=<instructions>
BREEZE_PMU cycles=<cycles> instructions=<instructions> control=<count> taken=<count> pred_miss=<count> icache_miss=<count> dcache_access=<count> dcache_miss=<count> uncached=<count> mem_stall=<cycles>
[GENERIC-PASS] MCU firmware completed
BREEZE_IPC cycles=<cycles> instructions=<instructions> ipc=<ipc>
BREEZE_METRICS prediction_miss_rate=<ratio> icache_mpki=<value> dcache_miss_rate=<ratio> memory_stall_ratio=<ratio>
```

runtime 通过 `mcountinhibit` 在 `main()` 前配置、清零并启动架构 PMU，在 `main()` 返回后
冻结计数器并把快照写入 linker 保留的 SRAM。monitor 捕获这些退休 store 后打印结果；
统计不经过 UART，也不影响 completion 判定。IPC 使用 PMU 的 `minstret/mcycle`，包含
调用/返回 `main()` 的固定少量胶水指令，适合相同固件和 SoC 参数下的相对比较。

UART 文本只用于观察程序行为，不决定仿真是否成功。若要生成波形，可增加
`--trace`：

```bash
python3 sim/litex/run_mcu.py --main hello.c --trace
```

如果交叉工具链使用其他前缀，可显式指定，例如：

```bash
python3 sim/litex/run_mcu.py \
    --main hello.c \
    --cross-compile /usr/bin/riscv64-linux-gnu-
```

仓库自带的默认程序位于
[`software/breeze-mcu/apps/main.c`](software/breeze-mcu/apps/main.c)。Timer/UART
中断回归可以这样运行：

```bash
python3 sim/litex/run_mcu.py --smoke timer --mtvec-mode direct --elaborate
python3 sim/litex/run_mcu.py --smoke uart  --mtvec-mode direct
python3 sim/litex/run_mcu.py --smoke timer --mtvec-mode vectored
python3 sim/litex/run_mcu.py --smoke uart  --mtvec-mode vectored
```

## 更多文档

- [MCU 总体目标](docs/breeze-mcu-target.md)
- [LiteX 仿真和调试选项](sim/litex/README.md)
- [裸机 runtime 与固件模板](software/breeze-mcu/README.md)
- [GShare 当前状态](docs/gshare-status.md)
- [M-mode PMU 与事件定义](docs/pmu.md)
- [开发记录](docs/worklog.md)
