# gustFrontend 设计文档

**日期**: 2026-03-06
**模块**: gustFrontend - 带 Cache 的三级流水线前端
**作者**: Claude & 用户协作设计

---

## 1. 概述

### 1.1 设计目标

实现一个带指令缓存（I-Cache）的三级流水线前端模块，用于取指（Instruction Fetch）阶段。该模块负责：
- 流水线式地从 I-Cache 获取指令
- 支持分支跳转和流水线冲刷
- 检测 PC 对齐异常
- 通过 Decoupled 接口向后端传递指令包（InstPack）

### 1.2 设计约束

- **Cache 延迟**: I-Cache 在 hit 时有 2 周期延迟（tag check + data return）
- **流水线级数**: 三级（S0 → S1 → S2）以匹配 cache 延迟
- **跳转控制**: 后端通过 `FE_BE_Bundle` 通知前端分支预测失败
- **异常检测**: 仅检测 PC 未对齐异常（物理地址访问，暂无访问错误）
- **无分支预测**: 当前版本不包含分支预测器，PC 默认顺序递增

### 1.3 接口定义

```scala
class gustFrontend() extends Module {
    val io = IO(new Bundle{
        val reset_addr = Input(UInt(64.W))           // 启动 PC
        val imem = Flipped(new ICacheIO)             // I-Cache 接口
        val instpack = Decoupled(new InstPack)       // 输出指令包
        val be_ctrl = Flipped(new FE_BE_Bundle)      // 后端控制信号
    })
}
```

---

## 2. 整体架构

### 2.1 三级流水线结构

```
┌─────────────────────────────────────────────────────────┐
│                     gustFrontend                         │
│                                                           │
│  ┌────────────┐      ┌────────────┐      ┌────────────┐ │
│  │    S0      │      │    S1      │      │    S2      │ │
│  │  PC Gen    │─────▶│  Tag Check │─────▶│Data Return │ │
│  │            │      │   (Wait)   │      │   (Pack)   │ │
│  └────────────┘      └────────────┘      └────────────┘ │
│         │                                        │        │
│         ▼                                        ▼        │
│  imem.req (addr)                          imem.resp      │
│                                           (data 32-bit)   │
│                                                 │          │
│                                                 ▼          │
│                                          instpack.bits    │
└─────────────────────────────────────────────────────────┘
           ▲                                      │
           │                                      ▼
    be_ctrl (flush/redir)                 Inst Buffer (后端)
```

### 2.2 各级职责

| 流水级 | 职责 | 主要操作 |
|--------|------|----------|
| **S0** | PC 生成 | - 选择下一个 PC（顺序 +4 或跳转）<br>- 检测 PC 对齐异常<br>- 发送 cache 请求（`imem.req`） |
| **S1** | Tag Check | - 等待 cache 完成 tag 比较<br>- 传递 PC 和异常标志<br>- 无额外计算 |
| **S2** | Data Return | - 接收 cache 响应（`imem.resp`）<br>- 打包成 InstPack<br>- 输出到后端 |

---

## 3. 详细设计

### 3.1 流水线级间寄存器

**S0 → S1 寄存器：**
```scala
val s1_valid = RegInit(false.B)         // S1 级是否有有效指令
val s1_pc = RegInit(0.U(64.W))          // S1 级的 PC
val s1_misaligned = RegInit(false.B)    // PC 对齐异常标志
```

**S1 → S2 寄存器：**
```scala
val s2_valid = RegInit(false.B)         // S2 级是否有有效指令
val s2_pc = RegInit(0.U(64.W))          // S2 级的 PC
val s2_misaligned = RegInit(false.B)    // PC 对齐异常标志
```

**PC 寄存器：**
```scala
val pc = RegInit(io.reset_addr)         // 当前 PC（S0 使用）
```

### 3.2 握手协议

#### S0 → Cache 请求

```scala
// S0 发送请求给 cache
io.imem.req.valid := s0_valid && !stall_s0
io.imem.req.bits.addr := pc

// 握手成功条件
val s0_fire = io.imem.req.fire  // req.valid && req.ready
```

**说明：**
- `s0_valid` 表示 S0 有有效的 PC 需要取指
- `stall_s0` 为真时，阻止 S0 发送新请求
- `cache.req.ready` 由 cache 控制（cache 忙时为 false）

#### Cache → S2 响应

```scala
// S2 接收 cache 响应
io.imem.resp.ready := !stall_s2

// 握手成功条件
val s2_has_data = io.imem.resp.fire  // resp.valid && resp.ready
```

**说明：**
- Cache 在 tag check + data return 完成后，`resp.valid` 置为真
- S2 在不阻塞时，始终 ready 接收数据

#### S2 → 后端输出

```scala
// S2 输出 InstPack
io.instpack.valid := s2_valid && io.imem.resp.valid
io.instpack.bits.data := io.imem.resp.bits.data
io.instpack.bits.pc := s2_pc
io.instpack.bits.instruction_address_misaligned := s2_misaligned
io.instpack.bits.instruction_access_fault := false.B  // 物理地址，暂无访问错误

// 握手成功条件
val s2_output_fire = io.instpack.fire  // instpack.valid && instpack.ready
```

### 3.3 背压传播（Stall 逻辑）

```scala
// S2 阻塞条件：后端不能接收
val stall_s2 = s2_valid && !io.instpack.ready

// S1 阻塞条件：S2 阻塞
val stall_s1 = stall_s2

// S0 阻塞条件：S1 阻塞或 cache 不 ready
val stall_s0 = stall_s1 || !io.imem.req.ready
```

**背压传播链：**
```
后端 ready=0 → stall_s2=1 → stall_s1=1 → stall_s0=1 → cache.req.valid=0
后端 ready=1 → 流水线流动
```

### 3.4 流水线前进逻辑

**S0 → S1：**
```scala
when(flush_pipeline) {
    // 跳转时，清空 S1
    s1_valid := false.B
    s1_pc := 0.U
    s1_misaligned := false.B
}.elsewhen(!stall_s0 && s0_fire) {
    // 正常前进：S0 握手成功，数据进入 S1
    s1_valid := true.B
    s1_pc := pc
    s1_misaligned := s0_misaligned
}.elsewhen(stall_s0) {
    // 阻塞：S1 保持不变
    // (隐式，寄存器默认保持)
}
```

**S1 → S2：**
```scala
when(flush_pipeline) {
    // 跳转时，清空 S2
    s2_valid := false.B
    s2_pc := 0.U
    s2_misaligned := false.B
}.elsewhen(!stall_s1 && s1_valid) {
    // 正常前进：S1 有效且不阻塞
    s2_valid := true.B
    s2_pc := s1_pc
    s2_misaligned := s1_misaligned
}.elsewhen(s2_output_fire) {
    // S2 输出后，可以接收新数据
    s2_valid := false.B  // 等待下一个有效数据
}
```

### 3.5 PC 更新逻辑

```scala
val pc = RegInit(io.reset_addr)

when(flush_pipeline) {
    // 跳转：PC 重定向到目标地址
    pc := io.be_ctrl.pc_redir
}.elsewhen(!stall_s0 && s0_fire) {
    // 正常：PC 递增
    pc := pc + 4.U
}.otherwise {
    // 阻塞：PC 保持不变
    // (隐式)
}
```

### 3.6 异常检测

**PC 对齐检查（S0 阶段）：**
```scala
// RISC-V 32-bit 指令必须 4 字节对齐
val s0_misaligned = pc(1, 0) =/= 0.U
```

**异常标志传播：**
```
S0: 检测 pc[1:0] =/= 0 → s0_misaligned
  ↓ (寄存器)
S1: s1_misaligned
  ↓ (寄存器)
S2: s2_misaligned → instpack.instruction_address_misaligned
  ↓
后端：触发异常处理（例如：跳转到异常向量）
```

**instruction_access_fault 处理：**
- 当前版本固定为 `false.B`
- 原因：使用物理地址，不存在 TLB miss 或访问权限错误
- 后续可扩展：PMA/PMP 检查失败时置为真

---

## 4. 分支跳转和流水线冲刷

### 4.1 跳转信号

后端通过 `FE_BE_Bundle` 通知前端：
```scala
class FE_BE_Bundle extends Bundle {
    val flush = Bool()           // 保留给 fence.i 等指令（当前未使用）
    val pc_misfetch = Bool()     // 分支预测失败标志
    val pc_redir = UInt(64.W)    // 跳转目标地址
}
```

### 4.2 冲刷逻辑

```scala
val flush_pipeline = io.be_ctrl.pc_misfetch

when(flush_pipeline) {
    // 清空所有流水级的 valid 位
    s1_valid := false.B
    s1_pc := 0.U
    s1_misaligned := false.B

    s2_valid := false.B
    s2_pc := 0.U
    s2_misaligned := false.B

    // PC 跳转
    pc := io.be_ctrl.pc_redir
}
```

**设计要点：**
- **冲刷优先级最高**：即使流水线阻塞，跳转也立即生效
- **不清空 Cache**：Cache 中的数据仍然有效，只清空流水线寄存器
- **原子性**：所有级同时冲刷，避免部分级保留旧指令

### 4.3 跳转时序示例

```
周期 N:   S0(PC=100) → S1(PC=96) → S2(PC=92, 即将输出)
         后端检测到分支预测失败：
         be_ctrl.pc_misfetch = 1
         be_ctrl.pc_redir = 200

周期 N+1: flush_pipeline 生效
         S0(PC=200) → S1(valid=0) → S2(valid=0)
         S1、S2 的指令被丢弃

周期 N+2: S0(PC=204) → S1(PC=200, valid=1) → S2(valid=0)
         从新地址继续取指

周期 N+3: S0(PC=208) → S1(PC=204) → S2(PC=200, 第一条新指令输出)
```

---

## 5. 边界情况处理

### 5.1 复位行为

**复位时的初始状态：**
```scala
val pc = RegInit(io.reset_addr)       // PC 指向启动地址
val s1_valid = RegInit(false.B)       // S1 初始无效
val s2_valid = RegInit(false.B)       // S2 初始无效
```

**复位后的启动流程：**
```
周期 1: S0 发送请求(reset_addr) → S1(invalid) → S2(invalid)
周期 2: S0 发送请求(+4)         → S1(等待)     → S2(invalid)
周期 3: S0 发送请求(+8)         → S1(等待)     → S2(接收第一条指令)
周期 4: ...                     → ...          → S2(输出第一条指令)
```

### 5.2 后端持续不 ready

**场景：** Inst Buffer 已满，后端 `instpack.ready = 0`

**行为：**
1. S2 阻塞（`stall_s2 = 1`）
2. S1 阻塞（`stall_s1 = 1`）
3. S0 阻塞（`stall_s0 = 1`）
4. `imem.req.valid = 0`，cache 停止接收新请求
5. 已经在 S1、S2 的指令保持不变
6. 一旦后端 ready，流水线恢复流动

**不变性：**
- 无指令丢失
- 无重复取指

### 5.3 Cache miss 导致长时间等待

**场景：** Cache miss，需要 refill（多个周期）

**行为：**
1. S0 发送请求，cache 接收（`req.fire`）
2. Cache 开始 refill，`resp.valid = 0`
3. S2 等待 `resp.valid`，无法输出 instpack
4. S0、S1 **可能继续前进**或**被 cache 阻塞**（取决于 cache 的 `req.ready` 信号）
   - 如果 cache 支持流水线请求：S0 可以继续发送新请求
   - 如果 cache 阻塞新请求：`req.ready = 0`，S0 阻塞
5. Refill 完成后，cache 返回数据，流水线恢复

**设计要点：**
- 前端不关心 cache 内部状态（miss/hit）
- 完全依赖握手协议（ready/valid）
- Cache 负责管理在途请求和阻塞策略

### 5.4 跳转和阻塞同时发生

**场景：** 后端发送跳转信号，同时 instpack.ready = 0

**行为：**
```scala
when(flush_pipeline) {
    // 跳转优先级最高，立即冲刷流水线
    // 不管是否 stall
    s1_valid := false.B
    s2_valid := false.B
    pc := io.be_ctrl.pc_redir
}
```

**结果：**
- 流水线被清空
- PC 跳转到新地址
- 下一个周期开始，从新 PC 取指（如果不阻塞）

---

## 6. 设计不变性（Invariants）

以下性质在任何时刻都应成立：

1. **Valid 传播**：
   - 只有当前级 `valid=1` 且握手成功，下一级才会 `valid=1`
   - 冲刷时，所有级的 valid 同时清零

2. **PC 连续性**：
   - 在无跳转、无阻塞时，PC 严格递增 +4
   - 跳转时，PC 立即切换到 `pc_redir`

3. **异常标志粘性**：
   - 一旦在 S0 检测到 `misaligned`，标志跟随 PC 传递到 S1、S2
   - 跳转时，旧指令的异常标志被丢弃（随 valid 清零）

4. **冲刷原子性**：
   - 跳转时，所有级同时冲刷
   - 不会出现"S1 清空但 S2 保留"的情况

5. **握手正确性**：
   - `valid && !ready` → 数据保持，等待握手
   - `valid && ready` → 握手成功，数据传递
   - 无数据丢失，无重复传输

---

## 7. 测试策略

### 7.1 单元测试场景

| 测试编号 | 场景 | 验证点 | 期望结果 |
|----------|------|--------|----------|
| **Test 1** | 基本顺序取指 | Cache 全部 hit，后端持续 ready | 每周期取一条指令，PC 连续递增 +4，流水线满载 |
| **Test 2** | 后端背压 | 后端间歇性 `ready=0` | 流水线正确阻塞和恢复，无指令丢失/重复 |
| **Test 3** | Cache miss | Cache miss 导致长时间等待 | 前端等待 `resp.valid`，refill 完成后恢复 |
| **Test 4** | 分支跳转 | 后端发送 `pc_misfetch` + `pc_redir` | 所有级 valid 清零，PC 跳转，旧指令丢弃 |
| **Test 5** | PC 对齐异常 | `reset_addr` 或 `pc_redir` 非对齐（如 0x1002） | `instruction_address_misaligned` 正确传递到后端 |
| **Test 6** | 连续跳转 | 连续多次 `pc_misfetch` | 每次跳转都正确冲刷，无旧指令残留 |

### 7.2 集成测试

**与 ICache 联合测试：**
- 使用真实的 ICache 模块（已实现）
- 测试场景：
  - 冷启动 miss → refill → hit
  - 连续访问不同地址（测试流水线吞吐）
  - 跳转到不同 cache line（测试冲刷 + 新取指）
- 验证工具：Verilator C++ testbench

**后续完整系统测试：**
- 与后端（译码、执行）集成
- 运行 RISC-V 测试程序（如 riscv-tests）
- 验证分支指令、异常处理的正确性

### 7.3 调试辅助

**建议添加的调试信息：**
```scala
val dump = false  // 可配置的调试开关

if(dump) {
    printf(cf"[gustFe] ")
    printf(cf"S0(pc=0x${pc}%x,v=${s0_valid}) ")
    printf(cf"S1(pc=0x${s1_pc}%x,v=${s1_valid}) ")
    printf(cf"S2(pc=0x${s2_pc}%x,v=${s2_valid}) ")
    when(flush_pipeline) {
        printf(cf"FLUSH(tgt=0x${io.be_ctrl.pc_redir}%x) ")
    }
    when(io.instpack.fire) {
        printf(cf"OUT(inst=0x${io.instpack.bits.data}%x) ")
    }
    printf(cf"\n")
}
```

### 7.4 成功标准

- ✅ **顺序取指**：流水线满载，每周期一条指令（cache hit 情况）
- ✅ **背压处理**：阻塞和恢复正确，无数据丢失
- ✅ **跳转响应**：一个周期内完成冲刷和 PC 重定向
- ✅ **异常传递**：对齐异常标志正确到达后端
- ✅ **与 ICache 集成**：无协议错误，功能正确
- ✅ **Verilator 测试**：通过所有单元测试和集成测试

---

## 8. 后续扩展方向

### 8.1 短期扩展

1. **fence.i 支持**：
   - 响应 `be_ctrl.flush` 信号
   - 向 cache 发送 invalidate 请求
   - 冲刷流水线

2. **指令预取优化**：
   - 在 cache miss 期间，继续发送后续请求（如果 cache 支持）
   - 减少 miss penalty

### 8.2 中期扩展

3. **分支预测器**：
   - 在 S0 加入 BTB（Branch Target Buffer）
   - 预测跳转目标，减少分支延迟
   - 需要增加：预测器更新逻辑、预测失败恢复路径

4. **返回地址栈（RAS）**：
   - 预测函数返回地址
   - 提高 `ret` 指令的预测准确率

### 8.3 长期扩展

5. **虚拟地址支持**：
   - 集成 TLB（Translation Lookaside Buffer）
   - 处理 TLB miss 异常
   - 增加 `instruction_page_fault` 异常标志

6. **多发射前端**：
   - 每周期取多条指令
   - 需要更宽的 cache 接口和指令对齐逻辑

---

## 9. 参考资料

- **RISC-V 规范**：指令对齐要求、异常定义
- **现有模块**：
  - `SimpleFrontend`：简单的非流水线前端实现
  - `Frontend`：之前的流水线前端尝试（使用 ITCM）
  - `ICache`：已实现的指令缓存模块
- **接口定义**：
  - `ICacheIO`：cache 的 req/resp 接口（interface/interface.scala）
  - `InstPack`：指令包定义（core/Frontend.scala）
  - `FE_BE_Bundle`：前后端控制接口（core/Frontend.scala）

---

## 10. 设计决策记录

| 决策 | 原因 | 备选方案 |
|------|------|----------|
| **三级流水线** | 匹配 cache 的 2 周期 hit latency | 两级（无法流水线化）、四级（过度复杂） |
| **Valid 位控制** | 简单清晰，易于调试 | Skid Buffer（增加复杂度）、两级流水线（性能差） |
| **跳转不清空 Cache** | Cache 数据仍有效，性能损失大 | 每次跳转都 invalidate（性能暴跌） |
| **异常标志跟随 PC** | 保证异常信息不丢失 | S2 重新检测（时序复杂，且 PC 可能已变） |
| **Stall 后向传播** | 标准流水线设计 | 前向传播（逻辑更复杂） |

---

**文档结束**
