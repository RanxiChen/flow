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
