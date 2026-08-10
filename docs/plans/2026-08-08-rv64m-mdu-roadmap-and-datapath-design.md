# RV64M / RV64A 路线与 MDU 数据通路设计

**日期：** 2026-08-08  
**状态：** 设计草案，尚未开始 RTL 实现  
**当前基线：** `main` / `e2ec329`，RV64I_Zicsr_Zifencei、M-mode-only、单发射顺序五级流水线  
**本阶段边界：** 冻结路线并设计 RV64M/MDU 接口与流水控制；不在本文阶段实现 A、S/U 或 MMU

## 1. 已冻结的总体顺序

后续主线按以下顺序推进：

1. 实现完整 RV64M 乘除法扩展；
2. 实现 RV64A 原子指令扩展；
3. 集中建设 ISA、差分和系统级测试；
4. 重构并实现 M/S/U 三种特权模式；
5. 再进入 Sv39、OpenSBI、Linux 和用户态程序闭环。

这里的“第三阶段再加入测试”指集中建设完整验证体系，不表示前两个阶段可以零测试。
M 和 A 的每个提交仍必须具有最小单元测试、边界用例和旧功能回归，否则错误会一直
积累到第三阶段，届时无法判断故障属于 M、A 还是原流水线。

第一阶段结束时，ISA 目标从：

```text
RV64I_Zicsr_Zifencei
```

变为：

```text
RV64IM_Zicsr_Zifencei
```

第二阶段结束后变为：

```text
RV64IMA_Zicsr_Zifencei
```

## 2. MDU 的时序认识

“乘法一定比普通整数运算慢、除法一定更慢”是常见实现结果，但不是 ISA 强制时序。
RISC-V 只定义架构结果，不定义每条指令使用几个周期。

- 普通 ALU 的加减、逻辑和比较适合单周期组合执行；
- 64x64 乘法可以做成一个长组合路径、若干级流水，或多周期迭代结构；
- 除法通常采用多周期迭代结构，直接把 `/`、`%` 综合成单周期组合逻辑通常面积大、
  关键路径差；
- FPGA 上乘法还可能映射到 DSP，因此乘法的最佳实现会随目标器件改变；
- 第一版需要优先保证语义、阻塞、flush 和退休正确，之后才能依据综合结果替换实现。

因此，BreezeCore 不应把固定延迟写进流水线控制，而应把 MDU 看成一个不定延迟、
单请求在途的执行单元。

## 3. 设计目标与非目标

### 3.1 第一版目标

- 支持 RV64M 全部 13 条指令：
  - `MUL`、`MULH`、`MULHSU`、`MULHU`；
  - `DIV`、`DIVU`、`REM`、`REMU`；
  - `MULW`、`DIVW`、`DIVUW`、`REMW`、`REMUW`。
- MDU 同一时刻只接受一条指令，符合当前单发射顺序核定位；
- 后端不依赖具体乘除法周期数；
- MDU 可以被 reset、older trap 或全流水 flush 取消；
- 一条 M 指令只发起一次、只写回一次、只退休一次；
- 第一版实现可替换，后端接口不随具体开源实现改变；
- 更新 `misa.M`、软件编译 ISA 和仿真配置，使软硬件声明一致。

### 3.2 第一版非目标

- 不做多条 M 指令并行在途；
- 不做乱序完成、reservation station 或 scoreboard；
- 不做乘加融合；
- 不承诺乘法每周期吞吐一条；
- 不在功能签收前针对某块 FPGA 或 ASIC 工艺做极限优化。

## 4. 固定的 Breeze MDU 接口

后端只连接项目自有接口，不直接引用 Rocket、Ibex 或 CVA6 的内部 opcode、Bundle 或
状态机。建议新增：

```scala
object MDU_OP {
  val MUL    = 0
  val MULH   = 1
  val MULHSU = 2
  val MULHU  = 3
  val DIV    = 4
  val DIVU   = 5
  val REM    = 6
  val REMU   = 7
  val MULW   = 8
  val DIVW   = 9
  val DIVUW  = 10
  val REMW   = 11
  val REMUW  = 12
  val width  = 4
}

class BreezeMduReq(val xlen: Int) extends Bundle {
  val op  = UInt(MDU_OP.width.W)
  val lhs = UInt(xlen.W)
  val rhs = UInt(xlen.W)
}

class BreezeMduResp(val xlen: Int) extends Bundle {
  val data = UInt(xlen.W)
}

class BreezeMduIO(val xlen: Int) extends Bundle {
  val req  = Flipped(Decoupled(new BreezeMduReq(xlen)))
  val resp = Decoupled(new BreezeMduResp(xlen))
  val kill = Input(Bool())
}
```

接口约束：

1. 只在 `req.fire` 时接收请求；
2. 接收后允许经过任意正整数个周期才拉高 `resp.valid`；
3. `resp.valid && !resp.ready` 时必须保持结果稳定；
4. `kill` 后丢弃当前请求，不允许再返回该请求的响应；
5. 第一版最多一个请求在途，因此不需要 transaction tag；
6. 除零和有符号溢出都返回 RISC-V 规定值，不产生异常；
7. W 类指令只运算低 32 位，最终结果符号扩展到 64 位。

`kill` 不是性能信号，而是替换不同 MDU 时必须保持的精确状态边界。

## 5. 推荐的数据通路位置

### 5.1 选择 EXE/MEM 作为 MDU 占用槽

推荐主路径：

```text
FetchBuffer
    |
    v
  Decode ---- 译码 mdu_valid/mdu_op，读取 rs1/rs2
    |
    v
 ID/EXE ---- forwarding 后得到最终 lhs/rhs
    |
    v
 EXE/MEM ---- 保存 M 指令全部元数据和操作数
    |  \
    |   +--> BreezeMDU req ---- 多周期运算 ---- resp
    |                                      |
    +--------------------------------------+
                    |
                    v
                  MEM/WB ---- 写回 rd、退休、tandem trace
```

不建议让 MDU 直接取代现有 ALU，也不建议让 M 指令一直停在 ID/EXE：

- EXE/MEM 已经是阻塞式访存占用的槽位，已有“请求一次、等待响应、完成后写回”的控制
  基础；
- M 指令进入 EXE/MEM 前，`exeRs1Data/exeRs2Data` 已完成 forwarding；
- MDU 等待期间，M 指令是后端中最老的未退休指令，顺序语义清晰；
- MEM/WB 仍然是唯一提交点，PMU、tandem 和寄存器写回模型不需要改变；
- 将来替换成流水乘法器或更快除法器时，只需替换 `BreezeMDU` 内部实现。

### 5.2 流水寄存器需要增加的字段

`EXE_Ctrl`：

```text
mdu_valid : Bool
mdu_op    : UInt(4.W)
```

`BreezeBackendEXEMEM`：

```text
mdu_valid : Bool
mdu_op    : UInt(4.W)
mdu_lhs   : UInt(64.W)
mdu_rhs   : UInt(64.W)
```

`BreezeBackendMEMWB`：

```text
mdu_data  : UInt(64.W)
```

`SEL_WB` 增加独立的 `MDU` 选择。当前 `SEL_WB` 为 2 bit 且只使用 ALU/MEM/CSR 三个
编码，剩余编码可以用于 MDU。不要为了少加一个字段而把 MDU 结果伪装成 ALU 结果；
独立来源能让 trace、forwarding 和后续调试更直观。

## 6. 请求、等待与完成控制

建议后端增加：

```text
exeMemIsMdu
mduWaitingRespReg
mduReqFire
mduRspFire
mduUseHazard
```

### 6.1 请求周期

当 `EXE/MEM` 中存在 M 指令且尚未发送请求时：

```text
mdu.req.valid = exeMemIsMdu && !mduWaitingRespReg && !olderRedirect
mdu.req.bits  = exeMemReg.mdu_op/lhs/rhs
```

只有 `mdu.req.fire` 后才能置 `mduWaitingRespReg`。不能假设 MDU 永远 ready，也不能把
`valid` 写成无条件单周期脉冲，否则以后替换 MDU 时会丢请求。

### 6.2 等待周期

从请求被接受到响应到达：

- 保持 EXE/MEM 中的 M 指令和元数据不变；
- 阻止 ID/EXE 和前端接收新的指令；
- `MEM/WB.valid := false`，防止上一条指令重复退休；
- 不重复发送 MDU 请求；
- branch predictor 不训练 held instruction；
- 中断仍按当前策略等待流水线排空，不越过 M 指令。

现有 `pipelineHold` 应扩展为“存储等待或 MDU 等待或 FENCE.I 等待”。不过实现时必须
为 MEM/WB 增加 MDU 专用分支，不能只把 `mduBusy` 粗暴 OR 到 `pipelineHold`，否则很
容易让旧的 MEM/WB 或 EXE/MEM 内容重复退休。

### 6.3 响应周期

`mdu.resp.fire` 时：

1. 用响应数据构造一次有效 MEM/WB 项；
2. 保留原 M 指令的 PC、inst、rd、nextPc 和 trace 元数据；
3. `wb_sel = MDU`；
4. 清除 `mduWaitingRespReg`；
5. 允许 EXE/MEM 在安全条件下接受下一条指令。

### 6.4 紧邻数据相关

示例：

```asm
mul x5, x1, x2
add x6, x5, x3
```

响应到达的同一个周期里，`x5` 尚未位于可由现有 `wbData` 转发的 MEM/WB 寄存器中。
第一版建议复用 load-use 的保守办法：

- 若 held ID/EXE 指令读取 MDU 的 `rd`，在 `mduRspFire` 周期再保留它一个周期；
- MDU 结果先进入 MEM/WB；
- 下一周期通过通用 MEM/WB forwarding 执行 dependent instruction；
- 同时向 EXE/MEM 插入 bubble，防止 M 指令被再次发起。

后续性能优化时可以增加 `mdu.resp.data -> exeRs1Data/exeRs2Data` 的直接旁路，消除这
一个额外周期，但第一版不需要为此增加控制风险。

## 7. Flush、异常和精确退休

MDU 必须遵守以下顺序语义：

- M 指令之前的异常/返回重定向发生时，不得启动该 M 指令，已启动则 `kill`；
- M 指令本身不会产生算术异常；除零和 overflow 是正常结果；
- M 指令等待期间，更年轻的 branch、CSR、load/store 不允许越过它执行或退休；
- reset、exception redirect、MRET redirect 等全后端 flush 必须同时清除
  `mduWaitingRespReg` 并拉高 `mdu.kill`；
- 被 kill 的旧响应即使内部晚到，也不得进入 MEM/WB；
- 一条 M 指令只能令 `retire.valid` 生效一次；
- tandem trace 的 `rdData` 必须记录最终 MDU 结果。

当前分支解析已经用 `!pipelineHold` 防止 held instruction 重复 redirect/training，MDU
接入必须保留这一性质。

## 8. 译码设计

RV64M 使用现有 `OP`/`OP_32` opcode，`funct7 = 0000001`：

| opcode | funct3 | 指令 |
| --- | --- | --- |
| OP | 000 | MUL |
| OP | 001 | MULH |
| OP | 010 | MULHSU |
| OP | 011 | MULHU |
| OP | 100 | DIV |
| OP | 101 | DIVU |
| OP | 110 | REM |
| OP | 111 | REMU |
| OP_32 | 000 | MULW |
| OP_32 | 100 | DIVW |
| OP_32 | 101 | DIVUW |
| OP_32 | 110 | REMW |
| OP_32 | 111 | REMUW |

合法 M 指令统一设置：

```text
sel_alu1 = RS1
sel_alu2 = RS2
wb_en    = true
sel_wb   = MDU
mdu_valid= true
mdu_op   = 对应操作
```

其他 `funct7 = 0000001` 的未定义组合仍必须保持 illegal instruction。

## 9. 可借鉴或临时采用的开源实现

### 9.1 首选：Rocket Chip `MulDiv`

来源：

- <https://github.com/chipsalliance/rocket-chip/blob/master/src/main/scala/rocket/Multiplier.scala>
- 文件头声明适用 `LICENSE.Berkeley` 和 `LICENSE.SiFive`。

优点：

- 与本项目同为 Chisel；
- 支持 32/64 位和 RV64 W 类运算；
- 使用 Decoupled request/response，并提供 kill；
- 乘除均可配置 unroll 和 early-out；
- 接口和状态机已经经过成熟 RISC-V 核使用。

限制：

- 不能原文件无脑复制后立即实例化；
- 它依赖 Rocket 的 ALU function 编码、`DecodeLogic`、`Log2`、`.option` 等工具；
- 当前 Breeze 使用 Chisel 7，而 Rocket 当前构建环境和内部 API 未必完全一致；
- vendoring 时必须固定 commit，保留原 copyright/license notice，并记录修改。

采用方式：

1. 先确定上游 commit 和适用许可证；
2. 只移植 `MulDiv` 所需算法到 `flow.mdu`；
3. 删除 Rocket 专属 opcode、tag 和工具依赖；
4. 用 `BreezeMduIO` 包装；
5. 在文件头保留来源、commit、许可证和本地修改说明；
6. 后端永远只看 `BreezeMduIO`。

这是当前推荐方案。

### 9.2 参考：Ibex slow/fast multdiv

来源：

- <https://github.com/lowRISC/ibex/blob/master/rtl/ibex_multdiv_slow.sv>
- <https://github.com/lowRISC/ibex/blob/master/rtl/ibex_multdiv_fast.sv>

优点是实现小、验证成熟、Apache-2.0、快慢两个版本边界清楚。缺点是 Ibex 为 RV32、
实现语言是 SystemVerilog，接入 Breeze 的 Chisel/RV64/W 指令需要额外封装和扩展。
适合用于理解迭代算法和状态机，不作为第一版直接集成首选。

### 9.3 参考：CVA6 `mult` / `serdiv`

来源：<https://github.com/openhwgroup/cva6>

CVA6 是支持 RV64 和 Linux 的成熟应用级核心，乘法与串行除法的数据通路、发射/完成
边界很有参考价值。但它是 SystemVerilog、流水和 scoreboard 体系远复杂于 Breeze，
直接抽取依赖较多，适合设计对照，不适合第一版快速落地。

### 9.4 参考：VexRiscv `MulDivIterativePlugin`

来源：<https://github.com/SpinalHDL/VexRiscv>

它展示了 FPGA 友好的 iterative/unroll 取舍，以及如何把多周期执行单元接入五级
顺序流水线。但 VexRiscv 是 RV32 + SpinalHDL，不能直接放进当前工程。

## 10. 第一版实现选择

第一版采用：

```text
Breeze 自有稳定接口
    +
固定版本、保留许可证的 Rocket MulDiv 算法适配
    +
单请求在途、EXE/MEM 阻塞、MEM/WB 唯一退休
```

建议初始参数以面积和易调试为先：

```text
mulUnroll = 1 或较小值
divUnroll = 1
mulEarlyOut = false
divEarlyOut = false
```

参数最终值需要在适配代码能 elaboration 后，通过单元测试和一次综合比较决定。本文不
提前承诺具体 latency，因为不同 unroll 配置和 W/XLEN 操作可能不同。

后期替换顺序建议：

1. 保持 `BreezeMduIO` 不变；
2. 先把乘法替换为 FPGA DSP 友好或 1～2 级流水实现；
3. 再为除法增加更大 unroll 或 early-out；
4. 根据 PMU 增加 MDU busy cycle 事件；
5. 最后才考虑流水乘法吞吐和 result bypass。

## 11. 实施拆分

### M0：接口与译码

- 新增 `MDU_OP`、`BreezeMduIO`；
- 扩展 `EXE_Ctrl`、`SEL_WB`；
- 完成 13 条指令精确译码；
- 非 M 编码不得从 illegal 变成合法；
- 暂不连接真实 MDU。

### M1：独立 MDU 模块

- 固定 Rocket 上游 commit 和许可证；
- 适配为无 Rocket 工程依赖的 Chisel 模块；
- 独立覆盖 13 条操作和关键边界；
- 明确并验证 `req/resp/kill` 协议。

### M2：后端集成

- M 指令进入 EXE/MEM；
- 增加一次请求、等待、响应控制；
- 接入 pipeline hold、flush/kill、MEM/WB 写回；
- 处理紧邻数据相关；
- 接入 forwarding、retire、tandem trace；
- 保证旧 RV64I、Cache、GShare 和 Trap 路径不被破坏。

### M3：软件和声明

- `misa.M = 1`；
- 工具链参数更新为 RV64IM；
- 增加实际生成 M 指令的 C/汇编程序；
- README 和当前能力表更新为已经验证的事实。

### M4：集中测试阶段之前的最小签收

- MDU 独立模块定向测试；
- 13 条指令 smoke；
- 除零、overflow、signed/unsigned、W sign-extension；
- 相邻 RAW、连续 M 指令、M 后 branch/store/CSR；
- MDU busy 时 reset/flush；
- `sbt test` 旧回归；
- LiteX baseline/GShare 各至少一个 RV64M 固件 completion PASS。

完整随机差分、ISA compliance、长序列压力和覆盖率仍按总体顺序放在 A 完成后的集中
测试阶段。

## 12. 进入编码前仍需确认的两项

1. 是否接受第一版 vendoring Rocket `MulDiv` 算法，并按上游许可证保留 attribution；
2. 第一版优化目标选“最小面积/较长 latency”，还是“FPGA DSP/较短乘法 latency”。

若没有新的取舍，默认按本文推荐执行：先采用 Rocket 迭代算法适配、最小面积参数，
把接口和精确流水控制做正确，再依据综合结果替换乘法实现。

