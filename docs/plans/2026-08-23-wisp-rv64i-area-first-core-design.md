# Wisp：面向资源稀缺 FPGA 的 RV64I 8 位串行核

**日期：** 2026-08-23  
**状态：** v0 RTL 首轮完成；连续程序测试与 EP4CE10 Quartus 首轮通过  
**首个器件：** Cyclone IV E `EP4CE10F17C8`  
**首个系统：** LiteX 最小 SoC，片上 ROM/SRAM、UART，外部 SDRAM 后置  

## 1. 决策

新核心正式命名为 **Wisp**。

Wisp 是与 Breeze 平级的独立核心，不是 Breeze 的 preset、条件编译配置或删减版。
旧 `feat/tiny-fpga-ep4ce10` 路线保留为历史实验，不继续作为 Wisp 的实现基础，也不删除
其 Git 历史。

Wisp 的固定优先级为：

1. 架构正确；
2. 最小逻辑面积；
3. 可预测、可验证；
4. 时钟频率；
5. IPC 和单条指令延迟。

“时序不优先”不等于允许负 slack。首版仍以板载 50 MHz 时钟完成 setup/hold
收敛为验收条件，但不为提高 Fmax 增加流水级、旁路或复制执行单元。

## 2. 目标与非目标

### 2.1 首版目标

- 实现完整 RV64I 非特权整数指令语义，包括 RV64 特有的 `*W` 指令。
- 32 个 64 位整数寄存器，`x0` 恒为零。
- 8 位共享 ALU，64 位运算按 8 个字节串行完成。
- 同步 SRAM/M9K 寄存器文件。
- 单发射、非流水、多周期控制器。
- 单一、阻塞式 LiteX Wishbone 主接口。
- 支持任意长度的 Wishbone `ack` 等待和明确的 `err` 终止。
- 在 LiteX 仿真中运行裸机程序，再在 EP4CE10 上生成并下载 `.sof`。

### 2.2 首版明确不做

- 不实现 M/A/F/D/C 扩展。
- 不实现 S/U-mode、MMU、Sv39、SBI 或 Linux。
- 不实现 ICache、DCache、L2、分支预测、流水线、旁路或乱序执行。
- 不实现多核、一致性、DMA 并发或非阻塞访存。
- 不以 LiteX BIOS 能启动作为第一阶段门槛；首版使用 Wisp 自己的最小裸机固件。
- 不复制 Breeze 的 Backend、Cache、Cluster 或控制 Bundle。

Breeze 继续承担现有 RV64 高功能、Cache、多核和 Linux 方向；Wisp 只承担小 FPGA
裸机/轻量系统方向。

## 3. 为什么可行

EP4CE10 具有约 10K 逻辑单元和 414 Kbit M9K 嵌入式存储。Cyclone IV 的每个
M9K 是 9 Kbit SRAM，可配置为单口、简单双口或真双口 RAM；真双口模式支持两路读取，
`256 x 8` 的 Wisp 寄存器布局能够装入一个 M9K。器件依据见 Intel Cyclone IV
Device Handbook：

- <https://www.intel.com/content/www/us/en/content-details/654744/cyclone-iv-device-handbook-volume-1-chapter-3-memory-blocks.html>
- <https://www.intel.com/content/www/us/en/products/details/fpga/cyclone/iv/e/products.html>

将 64 位数据通路缩成 8 位后，可复用同一个加法器、逻辑单元和比较状态完成所有
整数计算。代价是普通 ALU 指令需要几十个周期，移位最坏还会增加 63 个周期；这与
Wisp 的面积优先目标一致。

可行性仍须由 Quartus 证明，不能把 Chisel `Mem` 或推断结果当成物理资源结论。

## 4. 总体结构

```text
                   +----------------------+
                   |      WispControl     |
                   |  one instruction FSM |
                   +----+------------+----+
                        |            |
              micro-op  |            | Wishbone request
                        v            v
 +-------------+   +-----------+   +------------------+
 | WispDecoder |   | WispAlu8  |   | WispWishbone32   |
 | compact op  |   | carry/flag|   | one outstanding  |
 +------+------+   +-----+-----+   +---------+--------+
        |                |                   |
        |                v                   v
        |         +-------------+      LiteX bus fabric
        +-------->| WispRegFile |
                  | 256 x 8 M9K |
                  +-------------+
```

核心只保留少量宽状态：

- `pcReg[63:0]`：当前指令 PC；
- `instReg[31:0]`：当前指令；
- `value[63:0]`：算术结果、移位值、有效地址、load 结果或跳转目标，按阶段复用；
- 少量 byte index、carry、borrow、compare 和 Wishbone 状态位。

不建立 Breeze 式宽控制 Bundle，不在模块之间长期广播两个 64 位源操作数。

## 5. 寄存器文件

### 5.1 物理布局

逻辑结构为 32 个 64 位寄存器，物理结构改为 256 个 8 位单元：

```text
physical_address = {register_index[4:0], byte_index[2:0]}
physical_data    = register[8*byte_index +: 8]
```

目标推断为一个 `1024 x 8` 配置的 M9K，其中只使用前 256 个地址。

### 5.2 端口调度

- 真双口 Port A：读取 `rs1` 当前字节；写回阶段改作 `rd` 写端口。
- 真双口 Port B：读取 `rs2` 当前字节。
- 执行阶段不写寄存器文件。
- 写回阶段不读取下一条指令的操作数，因此不需要第三个端口。
- 同步读延迟由控制器显式安排一个启动拍，不能依赖异步读。
- 读取 `x0` 时绕过 RAM 输出零；写 `x0` 时禁止 `wren`。
- 复位时不逐项清零寄存器文件；除 `x0` 外的通用寄存器复位值不作为软件契约。

### 5.3 物理验收

首轮 Quartus 已确认 RF 没有展开成 LE，并推断为 M9K；但当前通用 `SyncReadMem`
模板被拆成一个 simple-dual-port M9K 加一个 single-port M9K，共 **2 个 M9K**。
因此“进入块 RAM”门槛通过，“单 M9K RF”面积目标尚未通过。下一轮应增加隔离的 Intel
true-dual-port backend，通用 Wisp 核继续保持厂商无关接口。

## 6. 8 位执行单元

### 6.1 加减与逻辑运算

ALU 每拍处理一个低位优先的字节：

```text
ADD byte: result = a_byte + b_byte + carry
SUB byte: result = a_byte + ~b_byte + carry, initial carry = 1
```

- 64 位 ADD/SUB：8 个 byte step。
- ADDW/SUBW：4 个 byte step，随后把 bit 31 符号扩展到高 32 位。
- AND/OR/XOR：每拍生成一个结果字节，共 8 拍。
- SLT/SLTU：复用串行减法，记录最终 borrow、输入符号和符号关系。
- BEQ/BNE：逐字节 XOR 后进行 OR-reduction。
- BLT/BGE/BLTU/BGEU：复用 SLT/SLTU 比较状态。

### 6.2 移位

首版不实现 64 位 barrel shifter。

- 先把 `rs1` 串行装入 `workReg`。
- 只捕获 `rs2[5:0]` 或立即数 `shamt[5:0]`。
- 每拍将 `workReg` 固定左移或右移 1 位。
- RV64I shift 最坏执行 63 拍。
- `*W` shift 只使用低 32 位和 5 位 shift amount，最坏 31 拍，结果符号扩展。

固定 1 位移位主要是寄存器连线和一个算术右移符号选择，不产生宽可变移位网络。

### 6.3 PC 与立即数

- PC 保持 64 位架构状态。
- `PC+4`、branch target、JAL/JALR target 和有效地址都复用 8 位加法器。
- 立即数不先生成一个带宽多路器的 64 位值；`WispImmediateByte` 根据格式和
  `byteIndex` 每拍生成一个 8 位立即数字节及符号填充值。
- JALR 完成后清零目标地址 bit 0。
- 无 C 扩展，取指 PC 必须 4 字节对齐。

RV64I 的 `*W` 结果必须从 bit 31 符号扩展，普通 RV64 shift 使用 6 位 shift amount；
实现以 RISC-V Ratified RV64I 2.1 为准：
<https://docs.riscv.org/reference/isa/unpriv/rv64.html>。

## 7. 译码

Wisp 不使用 Breeze 的 `EXE_Ctrl`，也不把 32 位指令直接做成大 ROM 地址。

译码器只输出紧凑信息：

- `microOp`：ADD、SUB、LOGIC、SHIFT、COMPARE、BRANCH、JUMP、LOAD、STORE、LUI、SYSTEM；
- `width32`：是否为 `*W` 运算；
- `immKind`：I/S/B/U/J；
- `signedOp`；
- `memSize` 和 load sign-extension；
- `usesRs1`、`usesRs2`、`writesRd`；
- `illegal`。

译码仍是组合逻辑，但只选择控制器路径，不产生大量并行执行控制。使用清晰的
`opcode/funct3/funct7` 表格描述，让 Quartus 做布尔优化。是否改成声明式 BitPat
truth table 由 A/B 综合决定，不预设 ROM 一定节省面积。

## 8. 多周期控制器

建议状态族如下，具体实现可将相邻状态合并：

```text
RESET
FETCH_REQ -> FETCH_WAIT
DECODE
RF_READ_ISSUE -> RF_READ_BYTES
EXEC_BYTES | SHIFT_LOOP | COMPARE_BYTES
ADDR_BYTES
MEM_REQ -> MEM_WAIT -> MEM_NEXT_BEAT
RF_WRITE_BYTES
COMMIT
TRAP_ENTER
```

一次只允许一条在途指令、一个 Wishbone 请求和一个写回目标。状态推进条件必须显式依赖
RAM 同步读有效拍和 Wishbone `ack/err`，不能假定固定内存延迟。

周期数只作为设计预期，不作为首版性能承诺：

| 指令族 | 主要串行工作 |
|---|---|
| ADD/SUB/logic | 8 个执行字节 + 8 个写回字节 |
| `*W` ALU | 4 个执行字节 + 8 个含符号扩展的写回字节 |
| compare/branch | 8 个比较字节，taken 时再串行计算目标 |
| shift | 8 字节装载 + 0..63 次固定移位 + 8 字节写回 |
| load/store address | 8 字节地址加法 |
| LD/SD | 两个 32 位 Wishbone beat |

## 9. 存储系统与 Wishbone

### 9.1 首版总线

Wisp 使用一个统一的 32 位、阻塞式 Wishbone master：

- 取指：一个 32 位 beat；
- LB/LH/LW：一个 beat，根据地址和大小选择字节；
- LD：两个连续 32 位 beat，完成后一次性写回；
- SB/SH/SW：一个 beat，使用 byte select；
- SD：两个连续 32 位 beat；
- 任意时刻最多一个 beat 在途；
- `cyc/stb` 保持到 `ack` 或 `err`；
- 总线等待期间控制器和寄存器写使能保持稳定。

32 位总线是在核心面积、单拍取指和 LiteX 互连复杂度之间的首版折中。首版系统不允许
DMA 或其他并发内存 master，因此拆分的 LD/SD 不会被另一个 master 插入；加入 DMA 前必须
重新审查可见原子性和总线仲裁。

### 9.2 地址边界

- 核心计算完整 64 位有效地址。
- EP4CE10 首版物理地址限制在低 4 GiB；发起 Wishbone 前检查高 32 位。
- 无 C 扩展时，指令地址必须 4 字节对齐。
- 首版允许对非自然对齐的 load/store 产生 address-misaligned trap，不实现跨边界拼接。
- 无 Cache 且一次只有一个事务时，`FENCE` 可作为合法的已完成屏障。

## 10. RV64I、异常与 CSR 边界

### 10.1 W0：非特权 RV64I 执行闭环

先通过 RV64I 整数、控制流和 load/store 定向测试。SYSTEM 指令统一进入明确的 trap/halt
路径，不伪装成 NOP。该阶段使用轮询 UART 固件，不宣称 Zicsr、机器中断或 LiteX BIOS。

### 10.2 W1：最小 M-mode/Zicsr

在 RV64I 数据通路稳定后，新增单独的 `WispCsr`：

- `mstatus`、`misa`、`mie`、`mtvec`；
- `mscratch`、`mepc`、`mcause`、`mtval`、`mip`；
- `mhartid`；
- ECALL、EBREAK、illegal instruction、instruction/load/store fault；
- machine timer/external interrupt；
- MRET。

CSR 数据也通过 8 位 ALU和串行写回处理。是否用第二个 byte-wide M9K 保存 CSR，必须根据
CSR 数量和 Quartus A/B 结果决定，不提前增加厂商 RAM 实例。

只有 W1 完成后，工具链字符串才允许从 `rv64i` 扩展为实际实现的
`rv64i_zicsr`；Zifencei 也必须在实现和测试完成后再宣告。

## 11. LiteX 集成

Wisp 使用独立 CPU wrapper 和 target：

```text
design/src/main/scala/wisp/
design/src/test/scala/wisp/
litex_wrapper/wisp/
fpga/ep4ce10_wisp/
software/wisp-smoke/
```

建议顶层接口：

- `clock`、`reset`；
- `resetAddr[63:0]`；
- 单个 32 位 Wishbone master；
- W1 后增加 timer/external interrupt；
- 仿真专用 retire 记录不进入 production RTL。

LiteX 首版 SoC：

- 50 MHz 直接板载时钟；
- 片上 ROM 和 SRAM；
- LiteUART，轮询输出；
- 不接 SDRAM、Cache、PLIC 或复杂调试模块。

外部 32 MB SDRAM 是独立阶段：先验证控制器、初始化、随机等待和 memtest，再让 Wisp
执行其上的程序；不能从片上 SRAM 成功直接推导 SDRAM 成功。

## 12. 验证与实现阶段

### P0：接口和编码冻结

- 建立 Wisp package、配置和顶层接口。
- 冻结 8 位 RF 地址、32 位 Wishbone 和 reset map。
- 不接 Breeze 模块。

### P1：WispRegFile + WispAlu8

- RF 同步双读、分时写回、x0 语义。
- 8 位 add/sub/carry/borrow/logic。
- 随机重组为 64 位结果，与 Scala/软件参考模型比较。

### P2：Decoder + 多周期 RV64I ALU

- 完成立即数、普通 ALU、`*W`、shift、compare 和 branch。
- 每条指令检查最终寄存器、PC 和 illegal 行为。

### P3：Wishbone load/store

- 加入随机 `ack` 延迟、`err`、大小和对齐测试。
- 检查 LD/SD 两 beat 地址、顺序和只写回一次。

### P4：连续程序与差分

- 运行多条连续程序，不只做单指令 smoke。
- 使用 RV64I 参考模型逐条比较 PC、指令和寄存器写回。
- production RTL 移除 retire/debug 端口。

### P5：LiteX 仿真

- 片上 ROM/SRAM；
- polling UART `Hello Wisp`；
- 随机 Wishbone 等待；
- 无 fatal、重复提交或总线协议错误。

### P6：EP4CE10 Quartus

- 由 LiteX 生成 Verilog/QSF/SDC；
- Quartus 18 Full Compilation 成功；
- setup/hold slack 非负；
- 生成非空 `.sof`；
- 单独记录 core hierarchy 和 full SoC 资源。

### P7：上板

- USB-Blaster/JTAG ID 正确；
- `.sof` Configuration succeeded；
- UART 实际输出与仿真一致；
- 最后再考虑 timer interrupt 和 SDRAM。

每个阶段独立提交、独立验证、独立停止。Scala 编译、RTL elaboration、仿真、Quartus
综合、SOF 生成、JTAG 下载和板上 UART 是不同证据层。

## 13. 初始资源目标

首轮实测（LiteX 最小测量壳，Quartus 18，EP4CE10F17C8）：

- 全 SoC：2,643 LE（26%）、742 registers、2 M9K、0 DSP；
- WispCore hierarchy：2,414 logic cells、618 registers、2 M9K、0 DSP；
- 50 MHz setup/hold 通过，slow 85C Fmax 54.48 MHz；
- 结果包含一位 `areaProbe` 可观察归约逻辑，避免无输出测量顶层被整体裁剪；
- `.sof` 已生成。RF 的 2 M9K 复制仍是下一步优化项。

原始设计目标：

- Wisp core：尽量不超过 2,500 LE；
- WispRegFile：1 个 M9K；
- 不使用 DSP；
- 最小 LiteX SoC：尽量不超过器件 60% LE，为 UART、片上存储和后续实验留余量；
- 首版 50 MHz 时序非负，不追求额外 Fmax。

最终只接受 Quartus `fit.summary`、hierarchy/resource 报告、`sta.summary` 和 `.sof`
作为物理实现结论。

## 14. 主要风险与处置

| 风险 | 影响 | 处置 |
|---|---|---|
| RF 未推断成一个 M9K | 面积目标失效 | 固定同步模板，检查 Technology Map；必要时隔离 Intel RAM backend |
| 控制器产生宽 mux | 8 位 ALU收益被抵消 | 使用紧凑 micro-op、局部寄存器和 byte generator，不复制 Breeze Bundle |
| 同步 RF 拍序错误 | 读到前一地址数据 | RF 单元测试和显式 ISSUE/CONSUME 状态 |
| LD/SD 两 beat 部分完成 | 异常恢复不精确 | 首版无并发 master；写回只在全部 beat 成功后发生，后续单独审查 store fault |
| shift/branch 边界错误 | RV64I 语义失败 | 覆盖 shamt 0/31/32/63、符号边界和 `*W` sign extension |
| 低性能影响软件体验 | 固件运行慢 | 接受；先测周期，再决定是否增加 8 位到 16 位可配置数据通路 |
| 为 BIOS/Linux提前加功能 | 面积和范围失控 | 首版只跑自有裸机固件；Zicsr、interrupt、SDRAM分别设门 |

## 15. 可行性结论

Wisp 已由实际 Quartus fitter 证明可以放入 EP4CE10。最关键的面积机制不是简单“删掉指令”，而是：

1. 用一个 byte-wide M9K 替代 2,048 bit 多端口触发器寄存器文件；
2. 用一个 8 位 ALU 在时间上复用全部 64 位运算；
3. 用固定 1 位 shift loop 替代 64 位 barrel shifter；
4. 用一个阻塞式 Wishbone 接口替代 Cache、队列和并发事务；
5. 让紧凑 FSM 直接驱动微操作，不保留 Breeze 的宽流水控制。

当前首要问题已经从“是否可行”收敛为两个具体优化：RF 从 2 个 M9K 降为 1 个，以及把
WispCore 的约 2.4K logic cells 继续压低。完整 ISA 随机/差分测试、随机 Wishbone wait-state、
真实固件 UART 和上板仍属于后续验证，不能由本轮小程序测试和生成 SOF 代替。
