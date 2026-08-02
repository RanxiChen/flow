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
