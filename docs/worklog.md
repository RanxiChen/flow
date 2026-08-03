# BreezeCore Worklog

本文档记录 BreezeCore / Breeze MCU 的实际工作进展、已经确认的设计决策和验证边界。

记录原则：

- 只把已经完成的修改写成完成项。
- 尚未编译、尚未运行或仍在讨论的内容必须明确标注。
- 后续工作按日期向下追加，不覆盖历史记录。
- 总体目标以 `docs/breeze-mcu-target.md` 为准，本文档侧重实际进展。

## 2026-08-02

### 本阶段目标

将现有顺序执行 RV64 核向 MCU 核方向完善，建立 PMA、ICache/DCache 下级接口、LiteX-facing
Wishbone 顶层以及稳定的 Chisel elaboration 入口。

### 已确认的架构方向

- 目标 ISA 为 `RV64I_Zicsr_Zifencei`。
- 仅实现 Machine mode，不实现 S-mode、U-mode、MMU、TLB 和页表。
- 程序地址直接作为物理地址使用。
- SoC 初期采用 ROM、片上 RAM、UART 和 Timer；DDR 不是早期启动条件。
- ICache 和 DCache 使用相互独立的下级通路，并在 SoC 边界暴露独立 Wishbone master。
- PMA 由核心内硬件检查，LiteX 的 region 属性不能替代核心 PMA。
- DCache 固定采用 write-back + write-allocate。
- DCache 默认 8 个全相连 entry、32-byte cacheline，使用 Reg Array 实现。
- 替换策略采用 invalid-first，全部有效后使用 round-robin。
- MMIO/device 区域绕过 DCache。
- `FENCE.I` 必须先等待 DCache 脏行写回完成，再冲刷 ICache。
- 保留项目原有的阻塞式单周期请求脉冲协议；专用下级必须在空闲态无条件锁存请求。

### 已完成的实现

#### 平台与 PMA

- 新增固定 MCU 地址布局：`config/breeze_mcu_platform.json`。
- 新增 elaboration-time 平台配置读取：
  `design/src/main/scala/platform/BreezeMcuPlatform.scala`。
- 新增组合逻辑 PMA checker：
  `design/src/main/scala/platform/PMAChecker.scala`。
- ICache 取指路径已接入 PMA execute/cacheable 检查。

#### ICache 与 Wishbone

- 保留 ICache 原有 cacheline miss 脉冲接口。
- miss response 增加 `error`，错误回填不会更新 tag/data/valid。
- 新增 `ICacheWishboneBridge`，将 32-byte cacheline 拆为 64-bit Wishbone 事务。

#### DCache

- 新增 `BreezeDCache`：
  `design/src/main/scala/cache/BreezeDCache.scala`。
- 已实现 load/store hit、load/store miss、dirty victim writeback、cacheline refill 和 store merge。
- 已实现 PMA 拒绝、不可缓存访问和 MMIO 标量旁路。
- 已实现逐 entry flush；dirty line 写回成功后才失效。
- 普通替换写回错误归属到触发替换的当前 load/store。
- 已退休 store 在 `FENCE.I` 清脏时发生写回错误，进入 sticky fatal 状态并保留脏行。

#### 数据侧 Wishbone 与异常

- 新增 `DCacheWishboneBridge`：
  `design/src/main/scala/bus/DCacheWishboneBridge.scala`。
- 支持 32-byte line 读写和单个 64-bit 标量/MMIO 事务。
- Wishbone 请求在收到 `ack` 或 `err` 前保持地址、控制和数据稳定。
- Backend memory request 现在携带原始字节地址和 `sizeLog2`。
- PMA/Wishbone 数据访问错误能够形成：
  - load access fault：`mcause = 5`；
  - store access fault：`mcause = 7`。
- store address misaligned 的异常号修正为 `mcause = 6`。
- `mtval` 使用原始故障字节地址。

#### 正式顶层与构建入口

- 正式 SoC-facing Top 固定为：
  `flow.top.BreezeCoreWishbone`。
- Top 当前连接关系为：

  ```text
  BreezeCore Backend -> BreezeDCache -> DCacheWishboneBridge -> dWishbone
  BreezeCore ICache  -> ICacheWishboneBridge                 -> iWishbone
  ```

- 独立 elaboration App 固定为：
  `flow.top.GenerateBreezeCoreWishbone`。
- `design/build.sbt` 新增两个正式命令：

  ```bash
  cd design
  sbt build
  sbt elaborate
  ```

- `sbt build` 编译 production 和 test Scala 源码，但不运行测试。
- `sbt elaborate` 的目标输出固定为：
  `design/build/rtl/BreezeCoreWishbone.sv`。

### 阶段提交

```text
b9d38c4 feat: add PMA-aware cached Wishbone core top
```

该提交完成后工作区为 clean，提交尚未推送远端。

### 当前验证边界

- 已执行 `git diff --check`，未发现补丁格式或行尾空白问题。
- 尚未运行 `sbt build`。
- 尚未运行 `sbt elaborate`，因此 `BreezeCoreWishbone.sv` 是否能够成功生成仍待实际确认。
- 尚未编译生成的 SystemVerilog。
- 尚未运行 Chisel、Verilator 或 LiteX 仿真。
- 当前 LiteX wrapper 仍是过渡代码；最终的 RTL 消费方式、端口绑定和仿真入口尚未冻结。

### 下一步

1. 运行 `sbt build`，处理 Scala/Chisel 编译问题。
2. 运行 `sbt elaborate`，确认固定 SV 文件和顶层端口能够生成。
3. 重新讨论并冻结 BreezeCore 与 LiteX 的交互边界。
4. 在交互边界确认后，再建立唯一正式的 LiteX 仿真流程。

### LiteX 接入分层

随后冻结并落实了 LiteX 接入边界：

- `litex_wrapper/flow/core.py` 保留为符合 LiteX `CPU` API 的外置 CPU
  适配层；显式提供两路 64-bit、word-addressed Wishbone master。
- CPU 适配层不再自动运行 SBT、不再猜测或复制 RTL，只消费固定产物
  `design/build/rtl/BreezeCoreWishbone.sv`；缺失时报告准确的生成命令。
- CPU 类和 SoC 类均显式声明冻结的 ROM、SRAM、CSR 和 main RAM 地址，避免
  LiteX 默认映射覆盖平台约定。
- 新增项目自有仿真入口 `sim/litex/breeze_sim.py`，显式注册
  `CPUS["flow"] = Flow`，不修改上游 LiteX 源码，也不依赖当前目录自动发现。
- 初始仿真 SoC 加入 LiteX 内置 simulated UART，CSR 地址固定为
  `0x12001000`，当前仅按 polling 模式使用。
- LiteX 内置 `timer0` 是 CSR 外设，不符合已冻结的 `mtime/mtimecmp` 地址和
  RISC-V `mtip` 语义，因此当前明确关闭，等待后续实现专用 machine timer。

本次仍只进行了代码编写和 `git diff --check`；未运行 Python 入口、SBT、
LiteX、Verilator 或仿真。

### Machine-mode 中断机制

在现有同步 Trap 和 `mret` 骨架上补充了 M-mode 中断路径：

- 从平台 JSON 读取固定的 8-bit 外部中断宽度，并贯穿
  `BreezeCoreWishbone -> BreezeCore -> BreezeBackend`。
- 顶层新增独立 `machineTimerInterrupt` 和 `externalInterrupts[7:0]` 输入。
- `mie` 实现可写的 `MTIE`/`MEIE`，`mip` 以只读方式反映 `MTIP`/`MEIP`。
- 8 路外部中断在核心中 OR-reduce 为 `MEIP`；Machine External Interrupt
  优先于 Machine Timer Interrupt。
- 中断 Trap 的 `mcause[63]` 置位，cause 分别为 11 和 7，`mtval` 固定为零。
- `mtvec` 第一版只实现 Direct 模式，CSR 写入时将 MODE WARL 为零。
- Backend 在 enabled interrupt pending 后停止发射新指令，等待已有流水级和
  访存事务排空，再发出中断重定向。
- 新增随退休指令更新的 architectural next PC；中断用它写 `mepc`，不依赖
  前端是否已有有效取指响应。
- LiteX CPU wrapper 只暴露 `interrupt[7:0]` 和 `mtip`；具体 SoC 的
  `interrupt_map` 将 UART 固定为外部中断 0、GPIO0..3 固定为 1..4。
- 现有仿真驱动和测试初始化把新增中断输入默认拉低，避免影响原有路径。

当前专用 `mtime/mtimecmp` Wishbone 外设尚未实现，因此 `mtip` 还没有实际
SoC 侧驱动。本阶段仍未运行 SBT、elaboration、LiteX 或 Verilator。

### Machine Timer Wishbone 外设

随后补齐了 SoC 侧 architectural machine timer：

- 平台 JSON 新增固定 `mtimeFrequencyHz = 1000000`。
- 新增 `litex_wrapper/flow/machine_timer.py`，实现独立 64-bit、word-addressed
  Wishbone slave。
- `mtime` 复位为零，`mtimecmp` 复位为全 1；`mtime >= mtimecmp` 时持续拉高
  `mtip`，软件通过把 `mtimecmp` 写到未来时间来撤销中断。
- `mtime` 和 `mtimecmp` 支持 Wishbone `sel[7:0]` byte-mask 合并写；同周期
  timebase tick 与 `mtime` 写入冲突时，软件写入优先。
- 有效寄存器访问在一个等待周期后返回 `ack`，Timer region 内其他偏移返回
  `err`。
- timebase 使用整数分频，要求 SoC `sys_clk_freq` 是 1 MHz 的整数倍；当前
  LiteX 仿真时钟为 1 MHz，因此 `mtime` 每个系统周期递增一次。
- `BreezeSimSoC` 从平台 JSON 读取 Timer region、寄存器偏移和 timebase，注册
  `0x02000000/64KiB` 非缓存 Wishbone slave，并把 `machine_timer.mtip` 连接到
  `cpu.mtip`。
- 向 LiteX 软件常量导出 `BREEZE_MTIME`、`BREEZE_MTIMECMP` 和
  `BREEZE_MTIME_FREQUENCY`。

本阶段执行了 JSON 语法检查和 `git diff --check`；仍未运行 LiteX Python
入口、SBT、RTL elaboration、Verilator 或仿真。

### ROM 启动镜像与最小裸机固件

为第一次端到端 LiteX 仿真补充了明确的复位启动内容：

- `sim/litex/breeze_sim.py` 新增 `--rom-init`，将原始 binary 按 64-bit
  little-endian 数据装入 `0x10000000` 的 64KiB integrated ROM。
- 新增 `software/breeze-smoke` 独立裸机工程，目标 ISA 固定为
  `RV64I_Zicsr_Zifencei`，不依赖 LiteX BIOS、libc 或 M 扩展。
- `start.S` 从 ROM 复位入口设置 SRAM 栈和 Direct-mode `mtvec`，复制
  `.data`、清零 `.bss` 后进入 C `main`。
- smoke 程序以 polling 方式使用 LiteX UART 的 `rxtx`/`txfull` CSR，打印
  启动消息，并在 `0x80000000` 做一次 64-bit cached main RAM 写回读检查。
- 第一版固件暂不使能 Timer 和外部中断；意外异常停留在 ROM 内的
  `trap_entry`，避免跳到未映射的默认异常入口。

本阶段仍只写代码，不构建固件、不运行 SBT/LiteX/Verilator/仿真；实际指令
生成、ROM 装载和 UART 输出均等待统一编译阶段验证。

### 首次 LiteX Verilator 构建反馈：split RTL 清单

远端首次构建已经成功生成 LiteX `sim.v` 并进入 Verilator，但暴露了 RTL
注册问题：Chisel/CIRCT 将设计拆为 24 个 SystemVerilog 文件并生成
`design/build/rtl/filelist.f`，原 wrapper 却只向 LiteX 注册了顶层
`BreezeCoreWishbone.sv`，导致 Verilator 找不到 `BreezeCore`、两个 Wishbone
bridge 和 `BreezeDCache` 等子模块。

对应修正为：

- `litex_wrapper/flow/core.py` 读取 `filelist.f`，检查清单非空及每个文件存在，
  再按清单把完整 RTL 集合注册给 LiteX。
- wrapper 显式连接顶层 `io_estop` 输出，消除 `PINMISSING` 警告并为后续仿真
  停机控制保留信号。
- `TIMESCALEMOD` 当前只是 `-Wno-fatal` 下的非阻断警告，本次不改变 RTL
  时序语义，待完整编译通过后再统一处理。

该修复完成后尚未在本地或远端重新运行 LiteX/Verilator。

### 首次运行无 UART 输出：仿真上电复位

补齐 split RTL 后 Verilator 已成功编译、链接并启动，但持续占用 CPU 且没有
smoke 固件的 UART 输出。远端现场确认：

- `breeze-smoke.bin` 为 352 字节，LiteX 生成的 `sim_rom.init` 非空且首条
  指令内容正确；
- 仿真进程持续运行，说明不是编译失败或 ROM 文件缺失；
- 原项目自建 `SimCRG` 只连接 `sys_clk`，生成的 `sys_rst` 从初值零开始且从未
  拉高；
- Chisel 前端的 `s1_pcReg` 只在同步 reset 时装载 `io_resetAddr`，因此没有
  上电复位就不能保证从 `0x10000000` 启动。

修正为直接使用 LiteX 标准 `CRG`。该 CRG 通过独立的 reset-less `por` 时钟域
产生 power-on reset pulse，再驱动 `cd_sys.rst`，满足 Chisel 核的同步复位
要求。修正后尚未重新运行仿真。

当前验证边界：

- 已确认完整 split RTL 能通过 Verilator 编译，并由 `clang++`/mold 成功链接
  出 `obj_dir/Vsim`；此前的子模块缺失错误已经跨过。
- 已确认无输出的旧仿真持续运行超过 90 秒并占满一个 CPU core；这不是正常的
  固件等待状态，已停止继续把它视为性能问题。
- smoke 固件本身以无限循环结尾，因此修复后的正确判据不是 `Vsim` 自动退出，
  而是先看到：

  ```text
  BreezeCore ROM boot
  main RAM cached R/W: PASS
  ```

  随后进程继续运行才属于预期行为。
- power-on reset 修复尚未经过远端复跑。同步修改后只需重新生成/构建 LiteX
  仿真，不需要再次 elaboration 核心 RTL，也不需要重新编译未变化的 smoke
  固件。

### 首次取指 Wishbone 诊断器

为避免继续只用 UART 输出判断整个启动链路，在 `sim/litex/breeze_sim.py` 中
新增了纯仿真 `FetchWishboneMonitor`：

- `--debug-fetch` 开启第一次 ICache refill 的事件式打印与检查；
- 第一笔请求必须是 64-bit Wishbone word address `0x02000000`，对应 reset
  byte address `0x10000000`；
- 第一拍返回数据直接与 `get_mem_data()` 载入的 ROM 第一个 64-bit word 比较，
  不在 monitor 中写死具体固件内容；
- 每次 `ack` 打印 cycle、beat、地址和数据；当前 32-byte cacheline 应收到四个
  64-bit beat；
- Wishbone `err`、错误首地址、首字数据不匹配都会打印明确原因并结束仿真；
- `--fetch-timeout` 默认 100 cycles，未完成首次 refill 时打印当前
  `cyc/stb/ack/err/address/beat` 后结束；
- `--stop-after-first-fetch` 可在首次完整 cacheline 返回后结束，便于把取指链路
  独立成有限测试；不加该选项时继续执行 ROM smoke 固件。

该 monitor 只观察 LiteX wrapper 的 `cpu.ibus`，不修改 BreezeCore RTL 或正常
总线逻辑。代码完成后尚未运行 Python、LiteX 或 Verilator。

首次远端生成 `sim.v` 时，monitor 的两类 `Display` 参数暴露了 Migen lowering
限制：组合表达式 `ibus.adr << 3` 被按 Python object 字符串写进 Verilog，ROM
首字的 Python 大整数则被写成超过 32-bit 的 unsized decimal literal，均导致
Verilator 语法错误。由于当前 Migen Verilog backend 的 `Display` 只对
`Signal` 做名称 lowering，对其他参数直接调用 Python `str()`，修正为把 byte
address、预期 Wishbone 地址和 ROM 首字全部落到显式位宽的组合 `Signal` 后再
比较和打印。该修正尚未重新生成 `sim.v` 验证。

### 退休与 smoke 内存指令诊断器

为验证 ICache refill 之后的执行路径，正式 `BreezeCoreWishbone` elaboration
配置开启已有 `TracePayload` tandem trace，LiteX CPU wrapper 显式接出退休 PC、
指令、寄存器写回和内存访问信息。该 trace 只观察架构退休状态，不参与控制。

`sim/litex/breeze_sim.py` 新增 `RetireMonitor` 及两种有限检查：

- `--debug-retire --stop-after-first-retire`：200 cycles 内必须退休第一条指令，
  PC 必须等于 reset vector，指令必须等于 ROM image 的首个 32-bit word；
- `--debug-retire --check-smoke-memory`：除首条检查外，还确认首条 `AUIPC` 写出
  `x2 = 0x11040000`，再等待 main RAM `0x80000000` 上的 `SD/LD` 退休；
- smoke `SD` 必须写入 `0x0123456789abcdef` 且 mask 为 `0xff`；随后 `LD` 的
  trace memory data 和寄存器写回必须返回同一数值；
- memory check 默认在首次退休后的 10000 cycles 超时，并在通过或失败时用
  `$finish` 结束，避免无输出的无限仿真。

该功能改变了生成顶层端口，因此远端验证前必须重新运行 `sbt elaborate`，再
重新生成 LiteX `sim.v`。代码完成后尚未编译或运行。

首次 memory check 已在远端确认第一条 `AUIPC` 于 cycle 17 正确退休并写出
`x2 = 0x11040000`，但 10000 cycles 内没有观察到 `0x80000000` 的目标 store，
旧超时信息只能给出 `store_seen=0`，无法区分启动汇编、SRAM 栈访问、DCache
和 UART polling。

因此进一步增强退休诊断：

- 默认打印前 64 条退休指令的 count、cycle、PC 和 instruction；
- 每一条退休内存指令都打印读写方向、地址、读写数据和 mask；
- 持续保存最后退休 PC/instruction 及最后内存访问；
- memory timeout 现在报告 retirement count 和上述最后状态；
- 500 cycles 没有任何新退休时触发独立 `RETIRE-STALL`，避免等待完整的
  10000-cycle memory timeout；阈值可通过 `--retire-stall-timeout` 调整。

该增强完成后尚未重新运行远端仿真。

## 2026-08-03

### MCU 软件模板、中断 smoke 与 IPC 统计

本阶段已经实现并推送以下代码：

- CSRFile 增加真实 64-bit `mcycle`/`minstret`，以及 `cycle`/`instret` 只读别名；
  CSR 地址匹配改为严格 12-bit decode，修复 `0xB00` 错误命中 `mstatus` 的别名问题。
- `mtvec` 支持 Direct 和 Vectored 两种 WARL mode；中断在 Vectored 模式跳到
  `BASE + 4 * cause`，同步异常仍跳到 BASE。
- 新增 `software/breeze-mcu` 通用裸机模板：`_start`、64-byte trap vector table、
  整数上下文保存/恢复、C trap handler、`mret`、用户 `main.c`、UART/Timer 小型库和
  linker script。
- runtime 在 `main()` 前后读取 `mcycle`/`minstret`，通过 UART 输出：

  ```text
  BREEZE_STATS cycles=0x... instructions=0x...
  ```

  Python runner 解析这两个值并计算、打印 `BREEZE_IPC`。
- 新增 Timer IRQ 与 UART IRQ 两个定向 smoke，以及 LiteX 侧有限 completion monitor；
  monitor 检查中断源、预期 trap PC、`mret`、固件结果签名和超时。
- `sim/litex/run_mcu.py` 统一完成 firmware build、可选 RTL elaboration、非交互
  LiteX/Verilator 运行、PASS marker 检查和 IPC 计算。

对应已推送提交：

```text
8af6d56 feat: add MCU interrupt smoke and IPC runtime
85b493b fix: use exact-width CSR decode patterns
30c733c fix: run automated MCU simulation non-interactively
df2c0af fix: recognize MCU runtime arm signature
```

### 已确认的远端验证

验证机器仍为：

```text
local -> ssh clawbot@120.27.148.132
      -> ssh -p 2222 chen@127.0.0.1
repo  -> /home/chen/FUN/flow
```

- `sbt "testOnly flow.core.CSRFileSpec"`：3/3 通过，覆盖 `mtvec` mode 和 counter CSR。
- `sbt elaborate`：通过，能够重新生成正式 split RTL。
- Timer Direct firmware：能够无警告完成编译、链接、binary/disassembly/symbol 生成。
- Timer Direct 仿真能够进入 MCU runtime，completion monitor 观察到
  `[TIMER-ARM] firmware runtime started`。

### 当前阻塞点及证据

Timer Direct 仍未通过。当前精确输出为：

```text
[TIMER-ARM] firmware runtime started
TI[TIMER-TIMEOUT] cycle=19999 source_seen=0 vector_seen=0 mret_seen=0
```

因此现在不能宣称 Timer、UART、Direct 或 Vectored smoke 已通过，也没有可用的
最终 IPC 数据。`source_seen=0` 说明 Timer 固件在打印 `TIMER_IRQ: ARM` 的前两个
字符后就已经跑偏，尚未执行到设置 `mtimecmp` 的位置；当前问题不是已经确认的
Timer 外设故障。

退休轨迹提供了更具体的证据：

- `breeze_uart_puts` 在 `PC=0x10000310` 调用 `0x100002c0`；
- 当前 ELF/ROM 在 `0x100002c0` 的指令应为 `0x120017b7`（UART putc 的 `lui`）；
- 实际退休指令却为 `0x00050913`，它来自当前 ROM 的 `0x10000320`；
- 后续不断进入另一函数序言并重复压栈，最终形成递归式错误执行；
- `breeze-mcu.bin`、`sim_rom.init` 和 objdump 三者在 `0x2c0` 的内容已经核对一致，
  都是正确的 UART putc 指令。因此错位发生在核心取指/返回关联路径，而不是固件
  binary 或 LiteX ROM 文件生成阶段。

### 当前未提交修改

本地和远端开发树目前都有一处尚未提交的实验性修正：

```text
design/src/main/scala/frontend/BreezeFrontend.scala
```

该修改要求 ICache response 的 `vaddr` 与 frontend 当前 `s2_pcReg` 完全一致，尝试
丢弃 redirect 后迟到的 wrong-path refill。当前精确版本已经成功 `sbt elaborate`，
但 Timer Direct 复跑仍得到相同的 `TI` 超时，所以它不是完整修复，暂时不得提交。

曾尝试同时让 ICache 在 `s2_done` 返回拍禁止接收新请求，但该临时修改使现有
`BreezeFrontendSpec` 在早期 ready 时序断言处失败，已经在本地和远端全部撤销；
`BreezeCache.scala` 当前没有未提交修改。

### 新窗口继续位置

1. 保留并审查 frontend 的 response-address guard，暂不提交。
2. 给 ICache miss/refill 路径增加有限诊断：打印 miss line address、Wishbone 四个
   beat、cache response `vaddr/data`，重点观察跳转到 `0x100002c0` 前后是否仍把
   `0x10000320` 的 line data 标记成 `0x100002c0`。
3. 根据诊断修正 ICache 内部 `s1/s2` 在 refill 完成拍与新请求同拍时的状态覆盖，
   并新增 redirect + outstanding miss 的回归测试。
4. 修通后依次执行 Timer Direct、UART Direct、Timer Vectored、UART Vectored；
   Vectored 预期入口分别为 `BASE+28` 和 `BASE+44`。
5. 四个中断 smoke 通过后再跑通用 `main.c`、检查 `BREEZE_STATS/BREEZE_IPC`，最后
   执行 `sbt build` 和旧 memory smoke 回归，更新本 worklog 后提交并 push。

### ICache refill 写入错误 set：根因、修复与复跑

继续排查后已确认上述错位的根因位于 `BreezeCache` refill 写地址，而不是
Wishbone 返回数据或 ROM 镜像：

- miss 进入 `s2` 后，`s2_vaddr` 正确保存了 outstanding miss 地址；
- refill 拍虽使用 `s2_refill_tag` 和返回数据，但 tag/data SRAM 的 `addr` 仍然使用
  当前组合的 `s0_vaddr = io.dreq.bits.vaddr`；
- miss 阻塞期间 frontend 可因 redirect 把 `dreq.bits.vaddr` 改成新目标，即使
  `dreq.fire = false`，旧逻辑仍会把返回行写到新目标的 set。

现场的 `0x10000320` 和 `0x100002c0` 位于同一个 2 KiB tag 范围、但 set index
不同。因此旧逻辑把 0x320 行写进 0x2c0 的 set 后，随后的 0x2c0 取指会 tag
命中并返回 0x320 的数据，这与之前观察到 `PC=0x100002c0` 退休
`0x00050913` 完全一致。

已实施修复：

- refill 写拍显式将所有 tag/data SRAM 地址切换为 `s2_index`；
- 保留 frontend response-address guard，继续丢弃 redirect 后与当前 `s2_pcReg`
  不匹配的迟到 response；
- 新增定向回归：先发起 `0x10000320` miss，再在 backpressure 期间把
  `dreq.bits` 改成 `0x100002c0`，验证 refill 后 0x320 在正确 set 命中并返回
  `0x00050913`，同时 0x2c0 仍为 miss；
- 同时将旧 `BreezeCacheSpec` 的 `0x0` 取指地址更新为真实 boot ROM 地址，
  因为 PMA 引入后 `0x0` 已应当返回 instruction access fault，不再是合法
  cache miss 测试地址。

远端 `/home/chen/FUN/flow` 已完成以下实际验证：

- `sbt "testOnly flow.cache.BreezeCacheSpec"`：3/3 通过；
- `sbt elaborate`：通过；
- `sbt build`：通过；
- Timer Direct：source cycle 1122，vector PC `0x10000080`，观察到 `mret` 和
  `[TIMER-PASS]`；
- UART Direct：source cycle 1725，vector PC `0x10000080`，观察到 `mret` 和
  `[UART-PASS]`；
- Timer Vectored：source cycle 1123，vector PC `0x1000009c = BASE+28`，观察到
  `mret` 和 `[TIMER-PASS]`；
- UART Vectored：source cycle 1726，vector PC `0x100000ac = BASE+44`，观察到
  `mret` 和 `[UART-PASS]`。

上述四次仿真均已通过硬件 completion monitor，原 `TI` 后取指跑偏和
20,000-cycle timeout 已消失。但四次 `run_mcu.py` 最终仍返回非零：仿真 UART
字符流在每段 `breeze_uart_puts` 边界多出 `NUL`，例如：

```text
BREEZE_STATS cycles=<NUL>0x<NUL>000000000000098b instructions=<NUL>0x<NUL>0000000000000242
```

因此 runner 的严格正则未匹配到 stats，还没有输出最终 `BREEZE_IPC`。这是已与
ICache 错 set 问题分离的新边界：本次不通过过滤 `NUL` 来伪造 runner 成功，
通用 `main.c`、最终 IPC 和旧 memory smoke 也尚未复跑。当前修复及测试仍未提交、
未 push。

### 仿真 completion 收口：删除 IPC 依赖

按当前目标，仿真固件不再承担 IPC 测量。用户程序只提供 `int main(void)`；`main`
返回后，runtime 使用标准 RISC-V `fence` 和 64 位 `sd` 向 SRAM 中链接器保留的
`__breeze_result` 写入 PASS/FAIL magic，随后原地驻留。仿真侧 monitor 观察该次已经
架构退休的写入并调用 `$finish`，不新增自定义停止指令、IPC MMIO 或核心端口。

已完成以下修改：

- runtime 删除 `mcycle/minstret` 起止读取、`BREEZE_STATS` 和固件 PASS/FAIL UART
  打印；UART 只作为应用诊断输出，不再控制仿真结束；
- completion store 前执行 `fence iorw, w`；反汇编确认结果最终以完整 `sd` 写往
  `0x11000000 <__breeze_result>`；
- `run_mcu.py` 从 firmware symbol table 读取 `__breeze_result`，通过
  `--mcu-result-address` 传给仿真，不再在 monitor 中重复写死地址；
- monitor 仅接受 `mem_wmask == 0xff` 的完整 64 位退休写入；
- runner 删除 stats 正则、cycle/instruction 解析和 `BREEZE_IPC` 计算；
- generic completion 与 interrupt proof 的生成逻辑分开，避免 generic 模式引用
  已被 Migen 优化掉的中断证明信号。

本地完成 `git diff --check`、两个 Python 入口的 `py_compile`，并使用
`/usr/bin/riscv64-linux-gnu-` 完成通用固件编译、链接和反汇编检查。本地 LiteX
环境缺少完整生成依赖，因此运行验证放在 `chen` 机器的临时副本
`/tmp/flow-mcu-completion-20260803`，正式远端仓库未被修改。远端运行前使用：

```bash
source /home/chen/miniforge3/etc/profile.d/conda.sh
source ~/FUN/env.sh
```

实际运行结果：

- 通用 `main.c` Direct：`[GENERIC-ARM]`、应用 UART 文本、
  `[GENERIC-PASS]`，runner 返回 0；
- Timer Direct：source cycle 1083，vector `0x10000080`，观察到 `mret` 和 PASS；
- UART Direct：source cycle 1677，vector `0x10000080`，观察到 `mret` 和 PASS；
- Timer Vectored：source cycle 1084，vector `0x1000009c`，观察到 `mret` 和 PASS；
- UART Vectored：source cycle 1678，vector `0x100000ac`，观察到 `mret` 和 PASS。

五次 `run_mcu.py` 均返回 0，证明 simulation completion 已与 IPC/UART 文本解析
解耦。UART 字符流问题仍然存在且没有被过滤：每次 `breeze_uart_puts()` 的字符串
结束后都会多出一个 `NUL`，UART interrupt smoke 中还观察到 `UART_IRQ: PASS`
重复输出。源码中该行只有一次调用，因此当前只把问题定位到
CPU MMIO store/Wishbone-to-CSR/LiteX UART simulation path，尚未确认具体责任模块；
不能据此宣称 UART console 已正确。

### CORE-004：load-to-branch 错误 redirect 导致 UART NUL/重复输出

对通用 `main.c` 开启退休内存轨迹后，确认 NUL 已经由核心架构提交，而不是
LiteX UART 仿真模型额外生成。字符串末尾的关键退休序列为：

```text
LBU 从字符串终止地址读回 0
紧邻 BNEZ 仍按上一个非零字符产生 taken redirect
SW 向 UART_RXTX 写入 0，mask=0x0f
```

根因为 `BreezeBackend` 在 older load 令 `pipelineHold` 有效时虽然保持 ID/EXE，
却仍允许该槽中的 dependent branch 参与 `redirectDirectionMismatch`/
`redirectTargetMismatch`。因此 branch 在 load 数据进入 MEM/WB forwarding 之前就使用
旧寄存器值重定向；GShare 更新也存在同样的 held-instruction 重复训练风险。

修复内容：

- branch redirect resolution 增加 `!pipelineHold` 条件；
- GShare BTB/PHT/GHR training 同样只在 `!pipelineHold` 时进行；
- 新增定向回归：先令 `x2=1`，再 load zero 到 `x2`，紧接
  `bne x2,x0`，要求 branch 正常退休且全程不产生 redirect。

远端临时树 `/tmp/flow-uart-fix-20260803` 的 A/B 结果：

- 旧 backend：定向测试失败，只退休 addi/load，branch 被错误 redirect 冲掉；
- 修复 backend：定向测试 1/1 通过；
- `sbt elaborate`：通过；
- 通用 `main.c`、Timer Direct、UART Direct、Timer Vectored、UART Vectored：
  五次 runner 全部返回 0；
- 五份原始日志 `NUL_COUNT=0`；Timer/UART 应用 PASS 文本每份只出现一次；
- Direct vector 为 `0x10000080`，Vectored Timer/UART 分别为 `0x1000009c`、
  `0x100000ac`，均观察到 source、vector、mret 和 completion PASS。

首次全量 `BreezeCoreSpec` 中，supported-load/store 两组以 `0x21` 实际地址对
`0x20` 期望失败；将 backend 换回修复前版本后也得到相同结果，排除了 CORE-004
回归。进一步核对接口后确认测试混淆了两层地址：`BreezeCore.io.dmem.req.addr` 是完整
的 `rs1 + imm` 有效地址，只有 DCache 下游的 Wishbone 请求才按 8-byte beat 对齐。
将用例期望改为 `0x20 + offset` 后，远端隔离树完整 `BreezeCoreSpec` 12/12 通过，
`sbt build` 通过。本次修复的独立记录见 `docs/bugs/CORE-004.md`。

### LiteX completion monitor 增加 IPC 统计

在不恢复固件 UART stats、不增加 IPC MMIO 或核心端口的前提下，复用现有 completion
mailbox 定义仿真测量窗口：runtime 在调用 `main()` 前退休的零值 ARM store 打开窗口，
最终 PASS/FAIL store 关闭窗口。`McuCompletionMonitor` 在窗口内按仿真时钟累计 cycle，
并按 retirement trace 的 `valid` 累计退休指令；两个 marker store 本身不计入。

monitor 输出纯整数：

```text
BREEZE_PERF cycles=<cycles> instructions=<instructions>
```

`run_mcu.py` 解析该行并计算：

```text
BREEZE_IPC cycles=<cycles> instructions=<instructions> ipc=<ipc>
```

completion PASS/FAIL 仍只由完整 64-bit mailbox store 和 magic 决定，性能文本不经过
UART，也不参与硬件 completion 判定。测量排除了 startup 和 section 初始化，但包含
调用/返回 `main()` 的固定少量胶水，当前定位是相同固件、相同 SoC 参数下的相对比较。

本地完成两个 Python 文件的 `py_compile`、stats parser 普通/定宽空格输入检查和
`git diff --check`。chen 机器先执行 conda 初始化及 `source ~/FUN/env.sh`，再在隔离树
`/tmp/flow-uart-fix-20260803` 验证：

- generic `main.c`：2044 cycles、612 instructions、IPC 0.299413，completion PASS；
- Timer Direct：2593 cycles、623 instructions、IPC 0.240262，并观察到 source、
  Direct vector、mret 和 completion PASS；
- UART Vectored：2514 cycles、600 instructions、IPC 0.238663，并观察到 source、
  Vectored vector、mret 和 completion PASS。

### GShare 状态记录（本次不实现）

本次没有修改 GShare RTL、配置和测试。代码审计确认 BTB、PHT、GHR、S1 lookup、
S3 修正、预测元数据传递及后端训练反馈的基本闭环已经存在，但独立单元测试、
训练后命中回归、复杂 redirect/stall/trap 组合验证、LiteX preset 选择和已审核的
baseline/GShare 性能对照仍未完成。正式 LiteX elaboration 继续固定使用 baseline；
新增 IPC 结果也只验证 baseline MCU。详细状态和后续验收条件见
`docs/gshare-status.md`。

### M-mode PMU：可编程 HPM counter 与 MCU 自动报告

在保持 baseline、不修改 GShare 的前提下，实现 RISC-V machine HPM 框架。新增
`mhpmcounter3` 至 `mhpmcounter10`、对应 `mhpmevent` WARL selector，以及支持
`CY/IR/HPM3..10` 的 `mcountinhibit`；其余 HPM CSR 保持只读零。Breeze event 表覆盖
退休控制流、taken、预测失败、ICache access/miss、DCache access/miss/uncached、
memory-stall cycle 和 load-use stall。Cache miss 按一次 cache transaction 计数，不按
Wishbone beat 计数。

runtime 在 `main()` 前配置、清零并启动 PMU，返回后冻结 counter，将 `mcycle`、
`minstret` 和 8 个 HPM counter 写入 linker 保留的 SRAM snapshot。LiteX completion
monitor 从退休 store 捕获快照并打印 `BREEZE_PMU`；runner 使用 PMU 的
`minstret/mcycle` 计算 `BREEZE_IPC`。UART、completion PASS/FAIL 和 PMU 仍彼此独立。

首次端到端运行发现连续 `csrr` 后的 store 保存了上一个 CSR 结果。根因是通用
MEM/WB forwarding 人为排除了 `SEL_WB.CSR`，导致短距离 CSR-to-store 相关读到旧值；
删除该排除后，PMU snapshot 字段恢复正确。chen 隔离树
`/tmp/flow-pmu-20260803`（运行前初始化 conda 并 `source ~/FUN/env.sh`）验证：

- `sbt compile` 与 `sbt elaborate` 通过；
- `CSRFileSpec` 4/4、`BreezeCoreSpec` 12/12 通过；
- generic：2078 cycles、612 instructions、IPC 0.294514；188 control、156 taken、
  156 prediction miss、10 ICache miss、164 DCache access、4 DCache miss、60 uncached、
  683 memory-stall cycles；
- Timer Direct：2598/623/0.239800，source/vector/mret/PMU/completion PASS；
- UART Vectored：2525/600/0.237624，source/vector/mret/PMU/completion PASS；
- 256 次紧分支循环：1597/522/0.326863，259 control、258 taken/miss；
- 256 次热 load/store 循环：3661/1294/0.353455，514 DCache access、0 miss、
  1028 memory-stall cycles，吻合每次热访问 2 个 hold cycle。

事件定义、默认 selector mapping 和指标公式见 `docs/pmu.md`。本次没有启用或修改
GShare。

### LiteX MCU 增加可选 GShare preset（默认保持 baseline）

完成第一阶段 GShare 集成。`GenerateBreezeCoreWishbone` 接受 `baseline|gshare`，并将
RTL 分别写到 `design/build/rtl/<preset>/`；每套产物包含 `core-preset.txt`，LiteX CPU
wrapper 在加载 `filelist.f` 前校验 marker。`breeze_sim.py` 和 `run_mcu.py` 增加
`--core-preset`，默认值均为 `baseline`；默认 Verilator 输出目录也包含 preset，避免
两种配置复用旧产物。runner 还要求仿真输出匹配
`BREEZE_CONFIG core_preset=<preset>` 才接受 PASS。

首次显式 GShare 运行能启动固件，但在 `main.c` 的 32 次循环退出处一直回跳。退休
轨迹确认计数寄存器已经从 31 更新到 32，问题不在数据旁路；根因是 predicted-taken、
actual-not-taken 时，后端检测到方向错误后仍使用 branch target 重定向。将 redirect
目标改为 `exeNextPc` 后，taken 使用 branch target，not-taken 使用 `pc+4`。

chen 隔离树 `/tmp/flow-pmu-20260803` 在初始化 conda 并 `source ~/FUN/env.sh` 后验证：

- baseline 与 GShare elaboration 均成功；baseline manifest 不包含 BTB/PHT，GShare
  manifest 包含 `BreezeBTB.sv` 和 `BreezePHT.sv`；
- `BreezeCoreSpec` 12/12 通过；
- 不传 `--core-preset` 的默认 baseline：2078 cycles、612 instructions、
  IPC 0.294514，completion PASS；
- 显式 `--core-preset gshare`：1548 cycles、612 instructions、IPC 0.395349，
  23 次 prediction miss，completion PASS。

这组数值只证明可选路径和循环退出修复有效。正式 GShare 性能结论仍需补齐预测器单元
测试、复杂控制流/异常回归和多 workload 对照，见 `docs/gshare-status.md`。

### GShare 单元测试与 baseline/GShare 正确性回归

按先验证预测器电路、再验证核心和 LiteX 路径的顺序补齐第一阶段回归：

- `BreezeBTBSpec` 2 项：复位 miss、metadata/update、完整 PC tag、round-robin replacement；
- `BreezePHTSpec` 3 项：PC/GHR xor index、weakly not-taken、2-bit counter 状态转移与
  饱和、alias；
- `MiniDecodeSpec` 1 项：BR/JAL/JALR/普通指令分类及直接目标；
- `BreezeFrontendGShareSpec` 3 项：GHR 单次移入与保持、复位状态、训练后 BTB/PHT
  命中并选择 predicted next PC；
- `BreezeBackendGShareSpec` 4 项：两种方向误预测的正确 redirect、正确预测仍训练、
  older load hold 期间不重复 BTB/PHT/GHR training；
- `BreezeCoreGShareSpec` 3 项：逐条比较 baseline/GShare 的退休 PC、指令、next PC、
  寄存器写回和 estop，覆盖 20 次循环退出、交替分支与 JAL、load-to-branch。

为观察 frontend 内部预测状态，增加只在 `enabledebug=true` 时生成的 GHR、S1 预测和
S3 fast redirect debug 信号；正式 LiteX core 使用 `enabledebug=false`，没有新增产品
顶层端口。core simulation runner 同时显式驱动新增 DMem/HPM 输入默认值，并将原
`BreezeCoreSim` 的 boot 地址调整到当前 ICache/PMA 可执行的 ROM 基址 `0x10000000`。

新增 `sim/litex/run_gshare_regression.py`：同一个 firmware binary 依次通过 baseline
和 GShare 独立产物运行，校验 preset marker、completion PASS、固件 SHA256、
PMU/IPC 字段；generic deterministic 程序还要求退休指令数相等。chen 隔离树
`/tmp/flow-pmu-20260803` 的实际结果：

```text
BREEZE_GSHARE_REGRESSION app=main mtvec=direct firmware_sha256=9863e4f55241a1170753271f8d52bd0bf073db0fc95b3194443ff7197bed6b9e PASS
BREEZE_GSHARE_RESULT preset=baseline cycles=2078 instructions=612 ipc=0.294514
BREEZE_GSHARE_RESULT preset=gshare cycles=1548 instructions=612 ipc=0.395349
```

chen 上新增 16 项定向测试全部通过。全量 `sbt test` 为 61 项中 46 通过、15 失败，
失败集中在 `BreezeFrontendSpec`、`BreezeFrontendFE001Spec`、
`BreezeFrontendFE002Spec`、`BreezeCoreNoFASESpec` 和
`BreezeCoreNoFASECustomInstrSpec`；这些不是本次新增 GShare 套件，因此当前不能宣称
全仓测试全绿，也没有把它们计作 GShare 第一阶段通过项。下一阶段仍需补 JALR 动态
目标、ICache miss/redirect、trap/mret、Timer/UART 和多 workload 回归。

### 修复 Frontend/NoFASE 历史测试地址与 PMA 不一致

继续排查上述 15 个失败后确认它们具有同一个根因：测试仍从 `0x0`、`0x200` 或
`0x800` 构造取指和 trap handler，但加入 MCU PMA 后，这些地址都不属于 executable
region。ICache 因而立即返回 instruction access fault，不会产生测试所期待的 cold
miss/refill；NoFASE 程序也从未真正执行。此前看到的 `cache_s0_ready=true`、零指令和
错误 trap cause 都是该测试平台失配的后果，不是新的 frontend/backend RTL 故障。

修复仅调整测试平台：

- Frontend 三个 suite 统一从 `BreezeMcuPlatform.ResetVector` 取指，并将所有流水 PC、
  miss 地址和 refill 期望相应平移；同时显式将 refill `error` 输入拉低；
- NoFASE suite 的程序、分支目标、异常入口和期望退休 PC 全部放入合法 ROM region；
- trap 测试用一条 `LUI` 构造 ROM 基址并写入 `mtvec`，不再把非法的 `0x200` 当作
  handler；测试指令间距、异常点和 `mret` 返回语义保持不变；
- 测试地址直接引用平台配置的 reset vector，不重复硬编码 `0x10000000`。

chen 机器按规定初始化 conda 并 `source ~/FUN/env.sh` 后验证：原失败的 5 个 suite
共 16 项测试全部通过；完整 `sbt test` 为 21 suites、61 tests，61/61 通过。
