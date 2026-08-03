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
| 分支预测 | 默认关闭；可显式选择 GShare，具体参数见下节 |
| ICache | 8 KiB，4-way，64 sets，32 B line，32-bit fetch，pseudo-LRU |
| DCache | 256 B，8-entry fully-associative，32 B line |
| DCache 策略 | blocking、write-back、write-allocate、invalid-first/round-robin replacement |
| MMIO | PMA 标记为 device/non-cacheable，由 DCache bypass |

ICache 和 DCache miss 都采用阻塞式处理。一个 32-byte cache line 会在 64-bit
Wishbone 上拆成 4 个 beat。DCache 同时支持带 byte select 的单 beat MMIO/scalar
访问和多 beat cache-line refill/writeback。

## GShare 配置

LiteX 仿真支持两套核心 preset：

- `baseline`：默认配置，不实例化分支预测器；
- `gshare`：显式 opt-in，实例化 GShare 方向预测器和 BTB。

当前 `gshare` preset 使用以下固定参数：

| 参数 | 配置 |
| --- | --- |
| GHR | 8 bit，实际分支结果每次移入 1 bit |
| PHT | 256 entries，每项为 2-bit 饱和计数器 |
| PHT 初始状态 | weakly not-taken |
| PHT 索引 | `PC[9:2] xor GHR` |
| BTB | 16 entries，使用完整对齐 PC tag |
| BTB 控制流类型 | conditional branch、JAL、JALR |
| BTB replacement | 优先使用无效 entry，满表后 round-robin |

预测元数据随取指和后端流水传递。实际控制流结果与预测不一致时，后端重定向前端；
训练以一次有效执行为单位，流水停顿不会重复更新。GShare 目前完成的是正确性 v1
验收，IPC 只作为测量结果报告，不是 PASS/FAIL 门槛。

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

## Example：计算 Fibonacci 数列

仓库提供了一个完整的计算型程序
[`software/breeze-mcu/apps/fibonacci.c`](software/breeze-mcu/apps/fibonacci.c)。它在目标
核上循环计算 `fib(40)`，通过 UART 输出结果，并在结果等于十进制 `102334155` 时
从 `main()` 返回 0。输入迭代次数使用 `volatile`，避免编译器把整个计算折叠为常量。

程序的核心内容是：

```c
static uint64_t fibonacci(uint32_t count)
{
    uint64_t previous = 0;
    uint64_t current = 1;

    for (uint32_t index = 0; index < count; ++index) {
        uint64_t next = previous + current;
        previous = current;
        current = next;
    }
    return previous;
}
```

先运行默认 baseline：

```bash
python3 sim/litex/run_mcu.py \
    --main software/breeze-mcu/apps/fibonacci.c \
    --core-preset baseline \
    --elaborate
```

再显式启用 GShare：

```bash
python3 sim/litex/run_mcu.py \
    --main software/breeze-mcu/apps/fibonacci.c \
    --core-preset gshare \
    --elaborate
```

两次运行都会编译同一份 C 程序、生成所选 preset 的 RTL、构建 LiteX/Verilator SoC，
然后启动固件。UART 应输出：

```text
Breeze MCU Fibonacci example
fib(40) = 0x0000000006197ecb
```

之后 monitor 会输出 completion、PMU 和 IPC，最终出现：

```text
[GENERIC-PASS] MCU firmware completed
BREEZE_IPC cycles=<cycles> instructions=<instructions> ipc=<ipc>
```

也可以用一个命令让同一固件依次运行 baseline 和 GShare，并自动检查固件 SHA256、
completion、PMU/IPC 字段和退休指令数是否一致：

```bash
python3 sim/litex/run_gshare_regression.py \
    --main software/breeze-mcu/apps/fibonacci.c \
    --elaborate
```

脚本最后会分别打印两套配置的 cycles、instructions 和 IPC；这些数值用于观察，不设
性能通过门槛。当前版本在 chen 的 LiteX/Verilator 环境中实测输出为：

```text
BREEZE_GSHARE_REGRESSION app=fibonacci mtvec=direct firmware_sha256=3ba36095e8ca1fdf95f189b0680dc2275477eabf53c744c81392bd8da91613ff PASS
BREEZE_GSHARE_RESULT preset=baseline cycles=3905 instructions=1226 ipc=0.313956
BREEZE_GSHARE_RESULT preset=gshare cycles=2748 instructions=1226 ipc=0.446143
```

这里最重要的正确性证据是两套核心运行同一 SHA256 固件、退休相同数量的指令并都
完成 PASS。cycles 和 IPC 会受工具版本与 SoC 参数影响，这一组数值只是典型执行记录。

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
