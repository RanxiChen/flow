# Flow 正规 SoC 的时钟与时间基准架构

状态：设计结论，尚未据此修改 RTL，也尚未重新启动 Linux 仿真。

本文回答一个基础问题：Flow 的 CPU、CLINT、PLIC、UART 和 DDR 在真实硬件里应当怎样
随时间推进。目标不是模仿 Spike 或 gem5 的主循环，而是先建立可综合、可上 FPGA、且
能被 OpenSBI/Linux 正确描述的 SoC 时钟架构。

## 1. 最终结论

真实硬件中，各模块是并行工作的，不存在软件意义上的“CPU 执行若干次，再调用一次
所有外设”。在一个时钟域内，每个时钟沿都会同时驱动 CPU、总线和外设状态机；需要
较慢时间尺度的模块，应使用以下两种硬件方式之一：

1. 独立的低速时钟域，并在跨域边界加入 CDC；
2. 保持同一个系统时钟，但仅在周期性的 clock-enable/tick 有效时更新慢速状态。

Flow 第一版应采用第二种方式：CPU、Cache、Wishbone、CLINT 寄存器、PLIC 和 UART
控制逻辑先共用 `sys_clk`，由 SoC 顶层产生独立 `rtc_tick`。只有 `mtime` 在
`rtc_tick` 到来时增加。这样既形成真实的 CPU/timebase 频率比，又避免现在没有必要的
异步时钟和 CDC。

建议冻结的第一版参数是：

| 项目 | 初始值 | 含义 |
| --- | ---: | --- |
| `CORE_CLK_HZ` | 50 MHz | CPU、Cache 和第一版系统互连时钟 |
| `SYS_CLK_HZ` | 50 MHz | CLINT/PLIC/UART 控制面和 Wishbone 时钟 |
| `TIMEBASE_HZ` | 1 MHz | `mtime` 及设备树 `timebase-frequency` |
| `RTC_DIVISOR` | 50 | 每 50 个系统周期产生一个 `rtc_tick` |

50:1 是 Flow 在尚未选定开发板时采用的平台契约，不是 RISC-V 强制值。参数必须可改，
以后由板级 CRG 和实际时钟约束决定 `CORE_CLK_HZ`/`SYS_CLK_HZ`；但硬件参数、设备树和
OpenSBI 看到的 timebase 必须始终一致。

## 2. 哪些模块应该怎样推进

### 2.1 CPU、Cache 和系统总线

- CPU 流水线在每个 `core_clk` 上升沿推进，是否真正提交指令由 stall/flush/valid 等
  使能条件决定。
- L1、共享 L2/home 和第一版 Wishbone 互连与 CPU 使用同一时钟域。
- 总线请求、响应和仲裁每周期都可以推进，不能跟随 `rtc_tick` 降频检查。
- `mcycle` 反映 hart clock cycle；`minstret` 反映退休指令；二者都不能代替真实时间。

### 2.2 CLINT/MTIMER

CLINT 的 MMIO 寄存器和比较逻辑每个 `sys_clk` 都工作，但 `mtime` 只在
`rtc_tick` 到来时加一：

```text
sys_clk:   ^ ^ ^ ^ ^ ^ ^ ^ ...
rtc_tick:  1 0 0 ... 0 1 0 ...       每 RTC_DIVISOR 周期一次
mtime:     N N N ... N N+1 ...
```

具体规则：

- SoC 时钟/复位模块负责从已声明的系统频率产生 `rtc_tick`；
- `FlowClint` 接收 `rtc_tick`，不再自己猜测 CPU 或系统频率；
- `mtimecmp`、`msip` 和 MMIO 访问仍可在任意系统周期更新；
- `MTIP[h]` 是 `mtime >= mtimecmp[h]` 形成的 level interrupt；
- RTL 中不得用退休指令数、PC 循环次数或宿主机 wall time 驱动 `mtime`；
- 第一版只允许整数分频，并在 elaboration 时拒绝不能整除的配置；以后确有需要时再换
  成相位累加器。

这与 Rocket Chip 的硬件结构一致：其 CLINT 明确接收外部 `rtcTick`，并仅在该 tick
有效时增加 time，而不是把一次 CPU 执行等同于一次实时时钟。参见
[Rocket Chip CLINT RTL](https://github.com/chipsalliance/rocket-chip/blob/master/src/main/scala/devices/tilelink/CLINT.scala)。

### 2.3 PLIC

PLIC 不是计时器，不需要 `rtc_tick`，也不应设置“每 N 个 CPU 周期运行一次”的固定
轮询周期。它由事件和总线访问驱动：

- gateway 接收设备的电平或边沿事件；
- pending、enable、priority、threshold、claim/complete 在 PLIC 时钟域内更新；
- `MEIP/SEIP` 根据 pending/enable/priority 状态形成；
- 第一版 PLIC 与系统总线同域，因此不增加内部 CDC；
- 将来若中断源来自其他时钟域，电平信号用同步器；窄脉冲则用 pulse stretcher、toggle
  同步或异步 FIFO，不能假定慢时钟必然看见它。

PLIC 的标准行为由 gateway、pending bit、notification 和 claim/complete 构成，规范
本身没有时间基准计数器。参见
[RISC-V PLIC specification](https://github.com/riscv/riscv-plic-spec/blob/master/riscv-plic.adoc)。

### 2.4 UART

- UART CSR、FIFO 和中断状态机每个外设/系统时钟都工作；
- 串口 baud rate 由分频器产生，不靠降低整个 UART 状态机的调用频率；
- TX/RX FIFO 事件通过 PLIC 触发外部中断；PLIC 对它是事件响应，不是周期轮询；
- 第一版继续让 LiteUART 控制面处于 `sys_clk` 域。

### 2.5 DDR/LiteDRAM

- DDR PHY、控制器和校准逻辑有自己的时钟要求，由 LiteDRAM/板级 CRG 管理；
- CPU 侧访问 DDR 是总线协议交互，不是“每 N 条 CPU 指令推进一次 DDR”；
- CPU/系统域与 LiteDRAM 用户端口不同域时，必须使用明确的总线 CDC；
- LiteX 已支持 Wishbone、AXI-Lite、AXI slave 的 clock-domain crossing，可保留它负责
  这类 FPGA/DDR 优势模块。参见
  [LiteX SoC integration](https://github.com/enjoy-digital/litex/blob/master/litex/soc/integration/soc.py)。

在 Linux 跑通前，不应为了形式上的“多时钟正规化”把 CLINT、PLIC 和 UART 都拆成异步
域；这会同时引入复位跨域、总线 CDC 和中断 CDC，扩大验证面。DDR 是第一阶段真正有
理由保留独立时钟域的模块。

### 2.6 Linux SoC 参考设计实际上怎样划分时钟

#### CVA6/CoreV APU

CVA6 不只是一个 CPU 单元测试工程。官方 `corev_apu` 集成了 AXI crossbar、CLINT、
PLIC、UART/SPI/以太网接口、ROM、DRAM 接口和调试模块；官方
[CVA6 SDK](https://github.com/openhwgroup/cva6-sdk) 可以为 FPGA 板构建 OpenSBI、
U-Boot、Linux、设备树和 initramfs。因此它是 Flow Linux SoC 最直接的参考之一。

它的基础划分是：

| CVA6 部分 | 时钟/信号 | 结论 |
| --- | --- | --- |
| CVA6 core、Cache | `clk_i` | 主系统时钟域 |
| AXI crossbar、ROM、Debug bus | `clk_i` | 与 core 同域 |
| PLIC、UART、SPI、普通 timer 控制面 | `clk_i` | 基础 SoC 中与 AXI 同域 |
| CLINT AXI 寄存器 | `clk_i` | 总线访问逐周期工作 |
| CLINT `mtime` 时间输入 | `rtc_i` | 与系统时钟分开的时间 tick 输入 |
| DDR PHY/controller | 板级/MIG 时钟 | FPGA 实现中的独立物理时钟组 |
| JTAG | `tck` | 独立异步调试时钟域 |
| 可选 Ethernet | RX/TX/参考时钟 | 启用网卡时新增的 PHY 时钟域 |

官方 test harness 中，AXI crossbar、CLINT、PLIC/外设和 CPU 都连接 `clk_i`，CLINT
另外接收 `rtc_i`。参见
[CVA6 ariane_testharness](https://github.com/openhwgroup/cva6/blob/master/corev_apu/tb/ariane_testharness.sv)。
官方 Xilinx 顶层也是让 core、AXI 和 CLINT 寄存器使用主 `clk`，再产生 `rtc` 信号送给
CLINT；板级端口才另外出现 DDR 输入时钟、JTAG `tck` 和 Ethernet RX/TX clock。参见
[CVA6 Xilinx FPGA top](https://github.com/openhwgroup/cva6/blob/master/corev_apu/fpga/src/ariane_xilinx.sv)。

因此，CVA6 给出的不是“每个外设一个低速域”，而是“一个主要同步 SoC 域 + 独立 RTC
tick + 确有物理接口需要时才增加的 DDR/JTAG/Ethernet 域”。这正是 Flow 第一阶段应该
采用的层次。

#### Rocket Chip/Chipyard

Rocket Chip 把互连进一步分成可配置的逻辑总线：tile/core、System Bus、Control Bus、
Periphery Bus、Memory Bus 和 Front Bus。它们可以共用时钟，也可以配置
Synchronous、Rational 或 Asynchronous crossing；并不表示一个标准 Linux 配置必须
同时存在六个物理时钟。

Rocket 的默认配置让 control/periphery bus 声明 100 MHz，并提供单独的
`WithTimebase`；也允许分别设置 system、memory、periphery、front 和 control bus
频率及 crossing 类型。参见
[Rocket subsystem clock/bus configuration](https://github.com/chipsalliance/rocket-chip/blob/master/src/main/scala/subsystem/Configs.scala)。
[Chipyard clock-domain example](https://chipyard.readthedocs.io/en/1.10.0/Advanced-Concepts/Chip-Communication.html)
展示了 tile、memory/periphery/front bus 使用不同频率并通过异步 crossing 连接的配置，
但这是高阶可配置能力，不是启动 Linux 的最低要求。

#### Linux-on-LiteX/LiteDRAM

LiteX 的普通逻辑默认处于 `sys` 域；需要独立时钟时才通过 `ClockDomainsRenamer` 和 CDC
明确标注。SoC integration 对 Wishbone、AXI-Lite 和 AXI 提供总线 CDC。

LiteDRAM 则明确拥有 `sys`、`sys2x`/`sys4x`、IO delay 等与 PHY 类型有关的时钟；例如
DDR3/DDR4 PHY 常使用 1:2 或 1:4 的 controller/PHY 频率关系。参见
[LiteDRAM generator clock domains](https://github.com/enjoy-digital/litedram/blob/master/litedram/gen.py)
和 [LiteDRAM supported PHY ratios](https://github.com/enjoy-digital/litedram)。这说明把 DDR
留给 LiteX/LiteDRAM 是合理的，但不能据此把 PLIC、CLINT 和 UART 也随意拆域。

#### Flow 应冻结的域数量

“几个时钟域”必须按实现阶段回答，不能给一个脱离板卡和外设的固定数字：

| 阶段 | 实际 sequential clock domain | 不算独立域的 enable/tick |
| --- | --- | --- |
| 当前 Linux RTL 仿真 | 1 个：`sys` | `rtc_tick`、UART baud enable |
| 第一版 FPGA Linux SoC（保留调试） | 3 组：`sys`、DDR PHY/controller、JTAG `tck` | `rtc_tick`、UART baud enable |
| 以后加入 Ethernet | 在上面基础上增加 PHY RX/TX/reference 域 | MDC 等分频 enable/输出时钟 |

Linux 本身没有规定必须存在几个物理时钟域；若不实现 JTAG，最小 FPGA 平台还可以少一
组。对 Flow 而言，第一阶段真正需要我们自己维护的只有一个 `sys` 同步域。`rtc_tick` 是该域
里的 clock-enable，不创建第二棵低速时钟树；DDR 的若干物理时钟由 LiteDRAM/CRG
封装；JTAG 通过经过验证的 DTM/CDC 进入系统域。这样既符合 CVA6 的 Linux SoC 结构，
又给以后 FPGA 外设留出了正规的跨域边界。

## 3. Spike/gem5 能参考什么，不能照搬什么

Spike 和 gem5 是软件模拟器。它们可以把若干条指令或若干个 event queue tick 合并
处理，再推进外设时间，以提高宿主机执行效率。这是模拟器调度策略，不是芯片中的
时钟树。

例如 Spike 的 CLINT 接口是 `tick(rtc_ticks)`；确定性模式直接把传入的 tick 数加到
`mtime`，实时模式则可由宿主时间计算 `mtime`。参见
[Spike CLINT implementation](https://github.com/riscv-software-src/riscv-isa-sim/blob/master/riscv/clint.cc)。

因此可以借鉴的只有：

- 软件可见寄存器语义；
- 中断置位、清除和 claim/complete 顺序；
- CPU 频率与 timebase 的比例；
- 设备树和 OpenSBI/Linux 的平台契约。

不能照搬的是：

- “退休 N 条指令后统一调用一次外设”；
- 用宿主 wall time 作为可综合 RTL 的 `mtime`；
- 把所有外设都视为同一种固定周期轮询设备。

RTL 仿真本身会在每个时钟沿并行求值所有模块。若 Flow 使用一个 `sys_clk` 加
`rtc_tick`，仿真器只需每周期驱动 `sys_clk`，分频器自然每 50 周期产生一次 tick，
不需要额外写一个模拟器式外设调度循环。

## 4. 当前 Flow 的冻结参数

当前 Linux 仿真中：

- LiteX 仿真的 `sys_clk` 固定为 50 MHz；
- `FlowClint` 接收 50 MHz `SYS_CLK_FREQ` 和 1 MHz `TIMEBASE_FREQ`；
- 因此 `RTC_DIVISOR=50`，`mtime` 每 50 个系统周期增加一次；
- 设备树每个 hart 的 `clock-frequency` 为 50 MHz；
- 设备树 `/cpus/timebase-frequency` 为 1 MHz。

这是尚未选择 FPGA 板卡时的无板平台契约。它不声明未来板载晶振、PLL、DDR PHY 或
最终 timing closure 结果；这些属于后续 LiteX board target 的平台边界。

LiteX 官方仿真入口也采用过 1 MHz `sys_clk`，这是为了仿真便利，而不是实际芯片必须
采用的频率关系。参见
[LiteX simulation target](https://github.com/enjoy-digital/litex/blob/master/litex/tools/litex_sim.py)。

## 5. OpenSBI、Linux 和设备树契约

OpenSBI/Linux 不需要知道 CLINT 的内部除法器，只需要设备树准确描述平台：

- `/cpus/timebase-frequency` 必须等于 `mtime` 每秒增加的次数，即 `TIMEBASE_HZ`；
- CPU `clock-frequency` 若提供，应描述相应的 CPU/启动频率，不能拿 timebase 代替；
- CLINT/ACLINT 的地址、每 hart timer/software interrupt 连接必须与 RTL 一致；
- PLIC source、context 和 `interrupts-extended` 必须与实际接线一致。

Rocket Chip 也把总线/设备频率与 `DTSTimebase` 分开配置，并把 timebase 单独写入 CPU
设备树节点。参见 [Rocket Chip subsystem configuration](https://github.com/chipsalliance/rocket-chip/blob/master/src/main/scala/subsystem/Configs.scala)、
[BaseSubsystem](https://github.com/chipsalliance/rocket-chip/blob/master/src/main/scala/subsystem/BaseSubsystem.scala)
和 [BaseTile DTS generation](https://github.com/chipsalliance/rocket-chip/blob/master/src/main/scala/tile/BaseTile.scala)。

## 6. 下一步改造顺序

### Stage 0：冻结平台时钟契约

建立唯一的 SoC clock plan，初值为 50 MHz core/system、1 MHz timebase。所有生成器、
RTL 参数、DTS 和软件构建都从这一契约派生，禁止各处继续手写 1 MHz。

### Stage 1：把 RTC tick 提升到 SoC 层

新增可综合 tick generator；`FlowClint` 删除内部频率推断，改为输入 `rtc_tick`。
CLINT 的 MMIO 总线仍在 `sys_clk` 域，只有 `mtime` 更新受 tick 使能。

### Stage 2：保持 PLIC/UART/总线逐周期运行

PLIC 不接 tick。LiteUART 继续使用自身 baud divider。Wishbone 请求/响应保持逐周期，
不要把“慢外设”误实现为总线每 100 周期才响应。

### Stage 3：明确 DDR 时钟边界

由 LiteDRAM 和板级 CRG 定义 PHY/controller 时钟。CPU 侧与 DDR 用户端口异步时，只在
这条边界加入 LiteX 提供或经过验证的 CDC。

### Stage 4：先做小验证，再回到 Linux

至少验证以下不变量：

1. 50 个 `sys_clk` 恰好使 `mtime` 增加 1；
2. CLINT MMIO 在两个 tick 之间仍能正常读写；
3. `mtimecmp` 到期后 MTIP 置位，重写 compare 后按规范解除；
4. PLIC 响应 UART/测试中断的时刻与 `rtc_tick` 无关；
5. 硬件参数、生成的 DTS 和运行镜像中的 `timebase-frequency` 完全相同；
6. 改变宿主机运行速度不会改变相同 RTL cycle 数对应的 `mtime`；
7. 复位释放不会产生额外 tick、伪中断或跨域亚稳态入口。

这些门通过后，再恢复 OpenSBI/Linux 启动。届时启动日志中的 cycle、`mtime`、MTIP、
PLIC 和 UART trace 才有统一、可解释的时间关系。

## 7. 实际硬件应按什么来源学习

资料优先级应固定为：

1. **可综合 RTL/生成器**：Rocket Chip、CVA6/其 SoC、LiteX/LiteDRAM、Flow 自身生成 RTL；
2. **硬件规范**：RISC-V Privileged ISA、ACLINT/CLINT、PLIC、设备树 binding；
3. **固件和内核**：OpenSBI、Linux 驱动，用来确认软件可见契约；
4. **功能模拟器**：Spike、gem5，仅作为行为和调度对照。

[Rocket Chip](https://github.com/chipsalliance/rocket-chip) 和
[CVA6](https://github.com/openhwgroup/cva6) 是可生成/综合的处理器 RTL，能回答真实硬件中
时钟、总线和中断如何接线；Spike/gem5 主要回答软件应该观察到什么。设计 Flow SoC 时，
前者必须是主证据，后者不能反过来定义 RTL 时钟结构。

## 8. 本阶段明确不做的事情

- 不继续跑当前 Linux 长仿真；
- 不用更多 trace 掩盖尚未定义清楚的时间架构；
- 不在此阶段引入多个不必要的异步外设时钟域；
- 不删除旧 LiteX/Migen/RTL 路径，保留回溯和对照；
- 不把 PLIC、UART 或 DDR 统一抽象成“跟随 RTC 分频轮询一次”。

下一次 RTL 修改应从 Stage 0 和 Stage 1 开始，而不是继续调整 Linux timeout。
