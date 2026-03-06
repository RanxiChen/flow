# gustFrontend 实现计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**目标**: 实现带 I-Cache 的三级流水线前端模块（gustFrontend）

**架构**: 三级流水线（S0: PC Gen → S1: Tag Check → S2: Data Return），通过 ready-valid 握手与 I-Cache 交互，支持分支跳转和流水线冲刷，检测 PC 对齐异常。

**技术栈**: Chisel3, Scala, Verilator（后续测试）

**参考文档**: `docs/plans/2026-03-06-gustfrontend-design.md`

---

## Task 1: 创建模块骨架和 IO 接口

**目标**: 建立 gustFrontend 模块的基本结构和 IO 定义

**文件**:
- Modify: `design/src/main/scala/core/Frontend.scala:254-259`

**Step 1: 定义完整的 IO 接口**

在 `Frontend.scala` 中，找到 `class gustFrontend()` 定义（第 254 行），替换为：

```scala
/**
  * 带 I-Cache 的三级流水线前端
  *
  * 流水线结构：
  *   S0: PC 生成，发送 cache 请求，检测 PC 对齐异常
  *   S1: 等待 cache tag check，传递 PC 和异常标志
  *   S2: 接收 cache 数据，打包成 InstPack 输出
  *
  * 握手协议：
  *   - imem.req: S0 发送地址请求给 cache
  *   - imem.resp: S2 接收 cache 返回的指令数据
  *   - instpack: S2 输出指令包给后端
  *   - be_ctrl: 后端控制信号（分支跳转）
  */
class gustFrontend() extends Module {
    val io = IO(new Bundle{
        val reset_addr = Input(UInt(64.W))           // 启动 PC
        val imem = Flipped(new ICacheIO)             // I-Cache 接口
        val instpack = Decoupled(new InstPack)       // 输出指令包
        val be_ctrl = Flipped(new FE_BE_Bundle)      // 后端控制信号
    })

    // 初始化 IO 信号（避免未驱动）
    io.imem.initialize()
    io.instpack.valid := false.B
    io.instpack.bits := 0.U.asTypeOf(new InstPack)
}
```

**Step 2: 编译验证接口定义**

运行 Chisel 编译，检查语法和类型错误：

```bash
cd /home/chen/FUN/flow/design
sbt "runMain core.FireCore"
```

**期望输出**: 编译成功，无类型错误（可能有 "unconnected wire" 警告，正常）

**Step 3: 提交基础接口**

```bash
git add design/src/main/scala/core/Frontend.scala
git commit -m "feat(frontend): add gustFrontend module skeleton with IO interface

- Define IO: reset_addr, imem (ICacheIO), instpack, be_ctrl
- Add module documentation explaining 3-stage pipeline structure
- Initialize IO signals to prevent undriven warnings

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 2: 定义流水线级间寄存器

**目标**: 创建 S0→S1 和 S1→S2 的流水级寄存器

**文件**:
- Modify: `design/src/main/scala/core/Frontend.scala:259` (在 `io` 定义之后)

**Step 1: 添加 PC 寄存器和 S1 寄存器**

在 `io.instpack.bits := ...` 之后添加：

```scala
    // ========== 流水线级间寄存器 ==========

    // PC 寄存器：S0 使用的当前 PC
    // 注意：复位后 PC 指向 reset_addr
    val pc = RegInit(io.reset_addr)

    // S0 → S1 寄存器
    // 作用：锁存 S0 阶段的 PC 和异常标志，传递给 S1
    val s1_valid = RegInit(false.B)         // S1 级是否有有效指令
    val s1_pc = RegInit(0.U(64.W))          // S1 级的 PC
    val s1_misaligned = RegInit(false.B)    // PC 对齐异常标志（在 S0 检测）
```

**Step 2: 添加 S2 寄存器**

紧接着添加：

```scala
    // S1 → S2 寄存器
    // 作用：锁存 S1 阶段的数据，等待 cache 响应
    val s2_valid = RegInit(false.B)         // S2 级是否有有效指令
    val s2_pc = RegInit(0.U(64.W))          // S2 级的 PC
    val s2_misaligned = RegInit(false.B)    // PC 对齐异常标志（从 S1 传递）
```

**Step 3: 编译验证寄存器定义**

```bash
cd /home/chen/FUN/flow/design
sbt "runMain core.FireCore"
```

**期望输出**: 编译成功，可能有 "s1_valid, s2_valid ... is never used" 警告（正常，后续会使用）

**Step 4: 提交寄存器定义**

```bash
git add design/src/main/scala/core/Frontend.scala
git commit -m "feat(frontend): add pipeline stage registers for gustFrontend

- Add PC register initialized to reset_addr
- Add S0→S1 registers: s1_valid, s1_pc, s1_misaligned
- Add S1→S2 registers: s2_valid, s2_pc, s2_misaligned
- Document the purpose of each register

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 3: 实现 S0 阶段 - PC 对齐检测

**目标**: 在 S0 阶段检测 PC 是否 4 字节对齐

**文件**:
- Modify: `design/src/main/scala/core/Frontend.scala` (在寄存器定义之后)

**Step 1: 添加 S0 对齐检测逻辑**

```scala
    // ========== S0 阶段：PC 生成和异常检测 ==========

    // S0 有效位：S0 总是尝试取指（除非被 stall）
    // 注意：这个信号会在后续添加 stall 逻辑时使用
    val s0_valid = WireDefault(true.B)

    // PC 对齐检查：RISC-V 32-bit 指令必须 4 字节对齐
    // 检查 PC 的低 2 位是否为 0，如果不为 0 则产生异常
    val s0_misaligned = pc(1, 0) =/= 0.U

    // 注释：为什么在 S0 检测对齐异常？
    // 因为对齐异常是 PC 本身的属性，应该尽早检测
    // 这个标志会跟随 PC 流过 S1、S2，最终传递到后端
```

**Step 2: 编译验证**

```bash
cd /home/chen/FUN/flow/design
sbt "runMain core.FireCore"
```

**期望输出**: 编译成功

**Step 3: 提交 S0 对齐检测**

```bash
git add design/src/main/scala/core/Frontend.scala
git commit -m "feat(frontend): add PC alignment check in S0 stage

- Add s0_valid wire (always true unless stalled)
- Add s0_misaligned detection: check pc[1:0] != 0
- Document RISC-V 32-bit instruction alignment requirement

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 4: 实现 S0 → Cache 请求握手

**目标**: S0 阶段向 cache 发送地址请求

**文件**:
- Modify: `design/src/main/scala/core/Frontend.scala` (在 S0 检测逻辑之后)

**Step 1: 添加 cache 请求逻辑**

```scala
    // S0 向 cache 发送请求
    // 条件：S0 有效 且 不被 stall（stall 信号后续添加）
    // 暂时假设不 stall，直接发送
    io.imem.req.valid := s0_valid
    io.imem.req.bits.addr := pc

    // 握手成功标志：cache 接收了请求
    // 当 req.valid && req.ready 时，握手成功
    val s0_fire = io.imem.req.fire

    // 注释：为什么需要 s0_fire？
    // s0_fire 表示 S0 成功发送了一个请求，可以前进到下一个状态
    // 如果 cache.req.ready = 0（cache 忙），则 s0_fire = 0，S0 会等待
```

**Step 2: 编译验证**

```bash
cd /home/chen/FUN/flow/design
sbt "runMain core.FireCore"
```

**期望输出**: 编译成功

**Step 3: 提交 cache 请求逻辑**

```bash
git add design/src/main/scala/core/Frontend.scala
git commit -m "feat(frontend): implement S0 to cache request handshake

- Drive imem.req.valid with s0_valid
- Send PC address via imem.req.bits.addr
- Add s0_fire signal to detect successful handshake
- Document handshake protocol

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 5: 实现 S0 → S1 流水线前进逻辑

**目标**: 当 S0 握手成功时，将数据传递到 S1

**文件**:
- Modify: `design/src/main/scala/core/Frontend.scala` (在 S0 请求逻辑之后)

**Step 1: 添加 S0→S1 前进逻辑（无 stall 和 flush 版本）**

```scala
    // ========== S0 → S1 流水线前进 ==========

    // 简化版本：暂不考虑 stall 和 flush（后续添加）
    // 当 S0 握手成功时，数据进入 S1
    when(s0_fire) {
        s1_valid := true.B
        s1_pc := pc
        s1_misaligned := s0_misaligned
    }.otherwise {
        // S0 未握手成功，S1 保持原值（或初始值）
        // 注意：这里不清空 s1_valid，因为 S1 可能还在等待 cache 响应
    }
```

**Step 2: 编译验证**

```bash
cd /home/chen/FUN/flow/design
sbt "runMain core.FireCore"
```

**期望输出**: 编译成功

**Step 3: 提交 S0→S1 前进逻辑**

```bash
git add design/src/main/scala/core/Frontend.scala
git commit -m "feat(frontend): implement S0 to S1 pipeline advancement

- When s0_fire, transfer data to S1 registers
- Copy pc, misaligned flag to s1_pc, s1_misaligned
- Set s1_valid to true on successful handshake
- Note: stall and flush logic to be added later

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 6: 实现 S1 → S2 流水线前进逻辑

**目标**: S1 的数据传递到 S2

**文件**:
- Modify: `design/src/main/scala/core/Frontend.scala` (在 S0→S1 逻辑之后)

**Step 1: 添加 S1→S2 前进逻辑（简化版）**

```scala
    // ========== S1 → S2 流水线前进 ==========

    // S1 阶段的职责：等待 cache tag check
    // 简化版本：每周期都将 S1 的数据传递到 S2
    // 后续会添加条件判断（stall、flush）

    // 暂时：S1 有效时，无条件前进到 S2
    when(s1_valid) {
        s2_valid := true.B
        s2_pc := s1_pc
        s2_misaligned := s1_misaligned
    }

    // 注释：为什么 S1 不做计算？
    // S1 阶段对应 cache 内部的 tag check 周期
    // 前端只需传递数据，不需要额外计算
```

**Step 2: 编译验证**

```bash
cd /home/chen/FUN/flow/design
sbt "runMain core.FireCore"
```

**期望输出**: 编译成功

**Step 3: 提交 S1→S2 前进逻辑**

```bash
git add design/src/main/scala/core/Frontend.scala
git commit -m "feat(frontend): implement S1 to S2 pipeline advancement

- When s1_valid, transfer data to S2 registers
- Copy s1_pc, s1_misaligned to s2_pc, s2_misaligned
- Set s2_valid to true
- Document S1 stage responsibility (wait for tag check)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 7: 实现 S2 阶段 - 接收 cache 响应

**目标**: S2 阶段从 cache 接收指令数据

**文件**:
- Modify: `design/src/main/scala/core/Frontend.scala` (在 S1→S2 逻辑之后)

**Step 1: 添加 cache 响应接收逻辑**

```scala
    // ========== S2 阶段：接收 cache 数据 ==========

    // S2 准备接收 cache 响应
    // 暂时：始终 ready（后续会添加背压控制）
    io.imem.resp.ready := true.B

    // cache 响应握手成功标志
    val s2_has_data = io.imem.resp.fire

    // 注释：S2 何时有完整的指令？
    // 需要同时满足：
    // 1. s2_valid = 1（有来自 S0 的 PC）
    // 2. imem.resp.valid = 1（cache 返回了数据）
```

**Step 2: 编译验证**

```bash
cd /home/chen/FUN/flow/design
sbt "runMain core.FireCore"
```

**期望输出**: 编译成功

**Step 3: 提交 cache 响应接收**

```bash
git add design/src/main/scala/core/Frontend.scala
git commit -m "feat(frontend): add cache response reception in S2

- Set imem.resp.ready to true (always ready for now)
- Add s2_has_data flag for successful cache response
- Document conditions for complete instruction in S2

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 8: 实现 S2 → InstPack 输出

**目标**: S2 打包指令数据并输出给后端

**文件**:
- Modify: `design/src/main/scala/core/Frontend.scala` (在 S2 接收逻辑之后)

**Step 1: 添加 InstPack 输出逻辑**

```scala
    // S2 输出 InstPack 给后端
    // 条件：S2 有效 且 cache 返回了数据
    io.instpack.valid := s2_valid && io.imem.resp.valid

    // 打包指令数据
    io.instpack.bits.data := io.imem.resp.bits.data  // 32-bit 指令
    io.instpack.bits.pc := s2_pc                      // 指令的 PC
    io.instpack.bits.instruction_address_misaligned := s2_misaligned  // 对齐异常
    io.instpack.bits.instruction_access_fault := false.B  // 物理地址访问，暂无访问错误

    // 输出握手成功标志
    val s2_output_fire = io.instpack.fire

    // 注释：为什么 instruction_access_fault = false？
    // 当前使用物理地址，不存在 TLB miss 或权限错误
    // 后续如果添加 PMA/PMP 检查，可以在这里设置为 true
```

**Step 2: 移除之前的初始化代码**

找到之前的 `io.instpack.valid := false.B` 和 `io.instpack.bits := 0.U.asTypeOf(new InstPack)`，删除它们（因为现在有了正确的驱动）。

**Step 3: 编译验证**

```bash
cd /home/chen/FUN/flow/design
sbt "runMain core.FireCore"
```

**期望输出**: 编译成功，无 "unconnected wire" 警告

**Step 4: 提交 InstPack 输出**

```bash
git add design/src/main/scala/core/Frontend.scala
git commit -m "feat(frontend): implement S2 to InstPack output

- Drive instpack.valid when s2_valid && cache resp valid
- Pack instruction data: data, pc, misaligned flag
- Set instruction_access_fault to false (physical address)
- Add s2_output_fire for output handshake detection
- Remove initialization code (now properly driven)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 9: 实现 PC 更新逻辑（简化版）

**目标**: PC 在 S0 握手成功后递增 +4

**文件**:
- Modify: `design/src/main/scala/core/Frontend.scala` (在 S0 请求逻辑附近)

**Step 1: 添加 PC 更新逻辑**

在 S0→S1 前进逻辑之前添加：

```scala
    // ========== PC 更新逻辑 ==========

    // 简化版本：暂不考虑跳转（flush），只实现顺序递增
    // 当 S0 握手成功时，PC 递增 4（下一条指令）
    when(s0_fire) {
        pc := pc + 4.U
    }.otherwise {
        // S0 未握手成功（cache 忙），PC 保持不变
        // 隐式保持，不需要写代码
    }

    // 注释：为什么是 +4？
    // RISC-V 32-bit 指令长度固定为 4 字节
```

**Step 2: 编译验证**

```bash
cd /home/chen/FUN/flow/design
sbt "runMain core.FireCore"
```

**期望输出**: 编译成功

**Step 3: 提交 PC 更新逻辑**

```bash
git add design/src/main/scala/core/Frontend.scala
git commit -m "feat(frontend): add PC increment logic

- When s0_fire, increment pc by 4
- PC holds value when cache is not ready
- Document RISC-V 32-bit instruction size (4 bytes)
- Note: branch/jump logic to be added later

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 10: 实现背压传播（Stall 逻辑）

**目标**: 实现从后端到 S0 的背压传播

**文件**:
- Modify: `design/src/main/scala/core/Frontend.scala` (在所有阶段逻辑之后，流水线前进逻辑之前)

**Step 1: 添加 Stall 信号定义**

在 S2 阶段逻辑之后添加：

```scala
    // ========== 背压传播（Stall 逻辑）==========

    // S2 阻塞条件：后端不能接收新指令
    // 当 S2 有有效指令，但后端 instpack.ready = 0 时，S2 阻塞
    val stall_s2 = s2_valid && !io.instpack.ready

    // S1 阻塞条件：S2 阻塞时，S1 也必须阻塞
    // 原因：如果 S2 阻塞，S1 无法将数据传递给 S2
    val stall_s1 = stall_s2

    // S0 阻塞条件：S1 阻塞 或 cache 不 ready
    // - S1 阻塞：S0 不能发送新请求（否则会覆盖 S1 的数据）
    // - cache 不 ready：cache 忙，无法接收新请求
    val stall_s0 = stall_s1 || !io.imem.req.ready

    // 注释：背压传播链
    // 后端 ready=0 → stall_s2=1 → stall_s1=1 → stall_s0=1 → cache.req.valid=0
```

**Step 2: 更新 S0 请求逻辑以使用 stall**

找到之前的 `io.imem.req.valid := s0_valid`，修改为：

```scala
    // S0 向 cache 发送请求
    // 条件：S0 有效 且 不被 stall
    io.imem.req.valid := s0_valid && !stall_s0
    io.imem.req.bits.addr := pc
```

**Step 3: 更新 S2 响应 ready 逻辑**

找到之前的 `io.imem.resp.ready := true.B`，修改为：

```scala
    // S2 准备接收 cache 响应
    // 条件：S2 不阻塞时，可以接收
    io.imem.resp.ready := !stall_s2
```

**Step 4: 编译验证**

```bash
cd /home/chen/FUN/flow/design
sbt "runMain core.FireCore"
```

**期望输出**: 编译成功

**Step 5: 提交背压逻辑**

```bash
git add design/src/main/scala/core/Frontend.scala
git commit -m "feat(frontend): implement backpressure (stall) logic

- Add stall_s2: block when backend not ready
- Add stall_s1: block when S2 is stalled
- Add stall_s0: block when S1 stalled or cache not ready
- Update req.valid to respect stall_s0
- Update resp.ready to respect stall_s2
- Document backpressure propagation chain

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 11: 更新流水线前进逻辑以使用 Stall

**目标**: 在流水线前进时考虑 stall 信号

**文件**:
- Modify: `design/src/main/scala/core/Frontend.scala` (修改 S0→S1, S1→S2 逻辑)

**Step 1: 更新 S0→S1 前进逻辑**

找到之前的 S0→S1 前进逻辑，修改为：

```scala
    // ========== S0 → S1 流水线前进 ==========

    // 当 S0 握手成功 且 不被 stall 时，数据进入 S1
    when(!stall_s0 && s0_fire) {
        s1_valid := true.B
        s1_pc := pc
        s1_misaligned := s0_misaligned
    }.elsewhen(stall_s0) {
        // S0 阻塞，S1 保持不变
        // 隐式保持，寄存器默认行为
    }

    // 注释：为什么需要 !stall_s0？
    // 如果 S0 阻塞，说明 S1 可能还在处理之前的数据
    // 不能用新数据覆盖 S1
```

**Step 2: 更新 S1→S2 前进逻辑**

找到之前的 S1→S2 前进逻辑，修改为：

```scala
    // ========== S1 → S2 流水线前进 ==========

    // 当 S1 有效 且 不被 stall 时，数据进入 S2
    when(!stall_s1 && s1_valid) {
        s2_valid := true.B
        s2_pc := s1_pc
        s2_misaligned := s1_misaligned
    }.elsewhen(s2_output_fire) {
        // S2 输出成功，可以接收新数据
        // 清空 s2_valid，等待下一个指令
        s2_valid := false.B
    }

    // 注释：为什么有两个条件？
    // 1. !stall_s1 && s1_valid: 正常前进
    // 2. s2_output_fire: S2 输出后，腾出空间接收新数据
```

**Step 3: 更新 PC 更新逻辑**

找到之前的 PC 更新逻辑，修改为：

```scala
    // ========== PC 更新逻辑 ==========

    // 当 S0 握手成功 且 不被 stall 时，PC 递增
    when(!stall_s0 && s0_fire) {
        pc := pc + 4.U
    }.otherwise {
        // S0 阻塞或未握手，PC 保持不变
    }
```

**Step 4: 编译验证**

```bash
cd /home/chen/FUN/flow/design
sbt "runMain core.FireCore"
```

**期望输出**: 编译成功

**Step 5: 提交更新的前进逻辑**

```bash
git add design/src/main/scala/core/Frontend.scala
git commit -m "feat(frontend): update pipeline advancement to respect stall

- S0→S1: only advance when !stall_s0 && s0_fire
- S1→S2: only advance when !stall_s1 && s1_valid
- S2: clear s2_valid on output fire
- PC: only increment when !stall_s0 && s0_fire
- Document the reasoning for stall checks

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 12: 实现分支跳转和流水线冲刷

**目标**: 实现后端发起的分支跳转，冲刷流水线

**文件**:
- Modify: `design/src/main/scala/core/Frontend.scala` (在 Stall 逻辑之后)

**Step 1: 添加 Flush 信号定义**

```scala
    // ========== 分支跳转和流水线冲刷 ==========

    // 检测跳转信号：后端通知前端分支预测失败
    val flush_pipeline = io.be_ctrl.pc_misfetch

    // 注释：为什么不使用 be_ctrl.flush？
    // be_ctrl.flush 保留给 fence.i 等指令（后续实现）
    // 当前只处理分支预测失败（pc_misfetch）
```

**Step 2: 更新所有流水线前进逻辑以处理 flush**

找到 S0→S1 前进逻辑，在最前面添加 flush 处理：

```scala
    // ========== S0 → S1 流水线前进 ==========

    when(flush_pipeline) {
        // 跳转时，清空 S1
        // 原因：S1 中的指令来自错误的执行路径，必须丢弃
        s1_valid := false.B
        s1_pc := 0.U
        s1_misaligned := false.B
    }.elsewhen(!stall_s0 && s0_fire) {
        // 正常前进：S0 握手成功，数据进入 S1
        s1_valid := true.B
        s1_pc := pc
        s1_misaligned := s0_misaligned
    }
    // (elsewhen stall_s0 的分支被删除，因为隐式保持)
```

找到 S1→S2 前进逻辑，在最前面添加 flush 处理：

```scala
    // ========== S1 → S2 流水线前进 ==========

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
        s2_valid := false.B
    }
```

**Step 3: 更新 PC 更新逻辑以处理跳转**

找到 PC 更新逻辑，在最前面添加 flush 处理：

```scala
    // ========== PC 更新逻辑 ==========

    when(flush_pipeline) {
        // 跳转：PC 重定向到目标地址
        pc := io.be_ctrl.pc_redir
    }.elsewhen(!stall_s0 && s0_fire) {
        // 正常：PC 递增
        pc := pc + 4.U
    }
    // (otherwise 分支被删除，因为隐式保持)

    // 注释：跳转优先级最高
    // 即使流水线阻塞，跳转也立即生效
    // 这保证了分支预测失败能及时恢复
```

**Step 4: 编译验证**

```bash
cd /home/chen/FUN/flow/design
sbt "runMain core.FireCore"
```

**期望输出**: 编译成功

**Step 5: 提交分支跳转逻辑**

```bash
git add design/src/main/scala/core/Frontend.scala
git commit -m "feat(frontend): implement branch misprediction and pipeline flush

- Add flush_pipeline signal from be_ctrl.pc_misfetch
- Flush S1 and S2 when flush_pipeline is true
- Clear all valid bits and exception flags
- Redirect PC to be_ctrl.pc_redir on flush
- Document flush priority (highest, even when stalled)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 13: 添加调试输出

**目标**: 添加可选的调试信息，方便仿真调试

**文件**:
- Modify: `design/src/main/scala/core/Frontend.scala` (在模块末尾)

**Step 1: 添加调试开关和 printf**

在所有逻辑之后，模块结束之前添加：

```scala
    // ========== 调试输出 ==========

    // 调试开关：设置为 true 可以在仿真时打印流水线状态
    val dump = false

    if(dump) {
        printf(cf"[gustFe] ")

        // S0 阶段状态
        printf(cf"S0(pc=0x${pc}%x,v=${s0_valid},ma=${s0_misaligned}) ")

        // S1 阶段状态
        printf(cf"S1(pc=0x${s1_pc}%x,v=${s1_valid}) ")

        // S2 阶段状态
        printf(cf"S2(pc=0x${s2_pc}%x,v=${s2_valid}) ")

        // 握手信号
        when(s0_fire) {
            printf(cf"REQ ")
        }
        when(s2_has_data) {
            printf(cf"RESP ")
        }
        when(io.instpack.fire) {
            printf(cf"OUT(inst=0x${io.instpack.bits.data}%x) ")
        }

        // 控制信号
        when(flush_pipeline) {
            printf(cf"FLUSH(tgt=0x${io.be_ctrl.pc_redir}%x) ")
        }
        when(stall_s0) {
            printf(cf"STALL ")
        }

        printf(cf"\n")
    }
```

**Step 2: 编译验证**

```bash
cd /home/chen/FUN/flow/design
sbt "runMain core.FireCore"
```

**期望输出**: 编译成功

**Step 3: 提交调试输出**

```bash
git add design/src/main/scala/core/Frontend.scala
git commit -m "feat(frontend): add debug output for simulation

- Add dump flag (false by default)
- Print pipeline state: S0, S1, S2 (pc, valid, misaligned)
- Print handshake signals: REQ, RESP, OUT
- Print control signals: FLUSH, STALL
- Document usage: set dump=true for debugging

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 14: 完整编译验证

**目标**: 生成 SystemVerilog，验证模块完整性

**文件**: N/A

**Step 1: 创建编译入口（如果不存在）**

检查是否有 `FireGustFrontend` 对象。如果没有，创建新文件：

```bash
# 检查
grep -r "FireGustFrontend" design/src/main/scala/
```

如果没有找到，在 `design/src/main/scala/core/Frontend.scala` 末尾添加：

```scala
object FireGustFrontend extends App {
    import _root_.circt.stage.ChiselStage

    ChiselStage.emitSystemVerilogFile(
        new gustFrontend(),
        firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
}
```

**Step 2: 编译生成 SystemVerilog**

```bash
cd /home/chen/FUN/flow/design
sbt "runMain core.FireGustFrontend"
```

**期望输出**:
- 编译成功
- 生成 `gustFrontend.sv` 文件
- 无错误，可能有少量警告

**Step 3: 检查生成的 SystemVerilog**

```bash
ls -lh gustFrontend.sv
head -50 gustFrontend.sv
```

**期望**: 看到模块定义、端口声明、寄存器定义等

**Step 4: 提交编译入口**

```bash
git add design/src/main/scala/core/Frontend.scala
git commit -m "feat(frontend): add FireGustFrontend compilation entry

- Add object FireGustFrontend for standalone compilation
- Generate SystemVerilog for verification and testing
- Use firtool options: disable randomization, strip debug

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 15: 代码审查和文档更新

**目标**: 审查代码质量，更新 CLAUDE.md

**文件**:
- Read: `design/src/main/scala/core/Frontend.scala`
- Modify: `CLAUDE.md`

**Step 1: 审查 gustFrontend 代码**

检查以下方面：
- ✅ 所有 IO 信号都被驱动（无 unconnected wire）
- ✅ 所有寄存器都有明确的初始值
- ✅ 注释清晰，解释了"为什么"而不仅仅是"是什么"
- ✅ 逻辑清晰，易于理解

**Step 2: 更新 CLAUDE.md**

在 `CLAUDE.md` 的 "## 当前开发状态" 部分，添加 gustFrontend 的信息：

找到 I-Cache 开发计划部分，在其后添加：

```markdown
### Frontend 开发状态

**gustFrontend 实现完成：**
- ✅ 三级流水线结构（S0: PC Gen, S1: Tag Check, S2: Data Return）
- ✅ 与 I-Cache 的 ready-valid 握手
- ✅ 背压传播（Stall 逻辑）
- ✅ 分支跳转和流水线冲刷
- ✅ PC 对齐异常检测和传递
- ✅ 调试输出支持

**接口：**
- Input: `reset_addr` - 启动 PC
- Input: `be_ctrl` (FE_BE_Bundle) - 后端控制（pc_misfetch, pc_redir）
- Output: `instpack` (Decoupled[InstPack]) - 指令包输出
- I/O: `imem` (ICacheIO) - I-Cache 接口

**编译命令：**
```bash
cd /home/chen/FUN/flow/design
sbt "runMain core.FireGustFrontend"
```

**下一步：Verilator 测试**
- 创建 testbench 验证功能正确性
- 测试场景：顺序取指、背压、跳转、对齐异常
- 与 ICache 集成测试
```

**Step 3: 提交文档更新**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md with gustFrontend completion

- Add Frontend development status section
- Document gustFrontend features and interfaces
- Add compilation instructions
- Outline next steps (Verilator testing)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 16: 创建 Verilator 测试计划（后续任务）

**目标**: 规划 Verilator testbench 的设计

**文件**:
- Create: `sim/Frontend/PLAN_FRONTEND_TB.md`

**说明**: 这个任务是计划性质的，不需要立即编写代码。

**Step 1: 创建测试计划文档**

```markdown
# gustFrontend Verilator Testbench 计划

## 测试环境结构

```
gustFrontend DUT
    ├── reset_addr (from TB)
    ├── imem <-> Mock ICache (TB 实现)
    ├── instpack -> Inst Buffer (TB 验证)
    └── be_ctrl <- Branch Control (TB 驱动)
```

## Mock ICache 设计

- 固定延迟模型：req → 2 周期 → resp
- 支持配置延迟
- 自动检查 req.addr 对齐
- 可配置的 ready 信号（测试背压）

## 测试场景

### Test 1: 基本顺序取指
- ICache 全部 hit (2 周期延迟)
- 后端持续 ready
- 验证：每 2 周期输出一条指令，PC 连续递增 +4

### Test 2: 后端背压
- ICache 正常工作
- 后端间歇性 ready=0
- 验证：流水线正确阻塞和恢复，无指令丢失/重复

### Test 3: Cache 背压
- ICache 间歇性 req.ready=0
- 后端持续 ready
- 验证：S0 正确等待，PC 不前进

### Test 4: 分支跳转
- 正常取指过程中，TB 驱动 pc_misfetch=1
- 验证：所有级 valid 清零，PC 跳转，从新地址取指

### Test 5: PC 对齐异常
- reset_addr 设置为非对齐地址 (0x1002)
- 验证：instpack.instruction_address_misaligned=1

### Test 6: 连续跳转
- 连续多次 pc_misfetch
- 验证：每次跳转都正确处理

## 实现优先级

1. Mock ICache（固定 2 周期延迟）
2. Test 1: 基本顺序取指
3. Test 4: 分支跳转
4. Test 2, 3: 背压测试
5. Test 5, 6: 异常和边界情况
```

**Step 2: 提交测试计划**

```bash
mkdir -p sim/Frontend
git add sim/Frontend/PLAN_FRONTEND_TB.md
git commit -m "docs: add Verilator testbench plan for gustFrontend

- Outline test environment structure
- Design Mock ICache model
- Define 6 test scenarios
- Prioritize implementation order

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## 后续步骤（不在本计划中）

完成上述任务后，gustFrontend 模块的**实现**已完成。后续工作：

1. **Verilator Testbench 实现** - 按照 `PLAN_FRONTEND_TB.md` 实现测试环境
2. **与 ICache 集成测试** - 替换 Mock ICache 为真实的 ICache 模块
3. **与后端集成** - 连接到译码、执行阶段，运行完整程序
4. **性能优化** - 分析流水线气泡，优化冲刷延迟
5. **添加分支预测** - 实现 BTB、RAS 等预测器

---

**计划完成标志**：
- ✅ gustFrontend 模块编译通过
- ✅ 生成 SystemVerilog 文件
- ✅ CLAUDE.md 更新完成
- ✅ Verilator 测试计划就绪

---

**预计总工时**：约 2-3 小时（假设熟练的 Chisel 开发者）

**关键里程碑**：
- Task 1-8: 基础流水线（1 小时）
- Task 9-11: 背压逻辑（30 分钟）
- Task 12: 分支跳转（30 分钟）
- Task 13-16: 调试和文档（1 小时）
