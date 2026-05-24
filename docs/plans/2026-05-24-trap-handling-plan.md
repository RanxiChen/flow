# 中断/异常处理实现计划

**日期：** 2026-05-24
**状态：** 待实施
**目标：** 为 breezeCore 添加 M-mode 异常/中断处理能力

---

## 背景

当前 core 已实现：
- RV64I 大部分指令、CSR 指令、CSRFile 骨架（printer/retire_counter/misa）
- ECALL/MRET 未译码，mstatus/mtvec/mepc/mcause/mtval/mip/mie 未实例化
- `illegal_inst` 信号已流水到 WB，但 WB 阶段无 trap 动作

---

## 目标：跑通 `hello world`（通过 ECALL + 非法指令 trap 打印）

---

## 实施计划

### 阶段 1：CSRFile 扩展

**文件：** `design/src/main/scala/core/common.scala`（新增 scala CSR），`sim/vir_flow/rtl/CSRFile.sv`（生成）

新增 CSR 寄存器（read-write）：

| 寄存器 | 地址 | 字段 | 说明 |
|--------|------|------|------|
| mstatus | 0x300 | MPP[12:11], MPIE[7], MIE[3] | 机器状态，复位 0x0 |
| mtvec | 0x305 | BASE[63:2], MODE[1:0] | 仅支持 MODE=0（直接模式） |
| mepc | 0x341 | 完整 64-bit | 异常指令 PC |
| mcause | 0x342 | Interrupt[63], Code[62:0] | 异常/中断原因 |
| mtval | 0x343 | 完整 64-bit | 附加信息（非法指令编码/地址） |

**步骤：**
1. 在 Chisel 中定义 CSR 寄存器（RegInit）
2. 实现 read_csr 的地址路由（已有一个 `addr == 0x301 ? misa : 0` 的模式，按此扩展）
3. 实现 write_csr 的条件写入（CSRRW/RS/RC + 地址匹配）
4. 确保读路径可先返回旧值（同时钟周期写需要 bypass）
5. sbt test 全部通过验证

---

### 阶段 2：ECALL / MRET 译码

**文件：** `design/src/main/scala/core/InstDecode.scala`

**ECALL：** OPCODE.SYSTEM, funct3=000, imm12=0x000（注意与 ESTOP 0x7FF 区分）

```scala
when(io.inst(31, 20) === 0x000.U && io.inst(11, 7) === 0.U && io.inst(19, 15) === 0.U) {
    io.I_ctrl.is_ecall := true.B
    io.illegal_inst := false.B
}
```

**MRET：** OPCODE.SYSTEM, funct3=000, imm12=0x302

```scala
when(io.inst(31, 20) === 0x302.U) {
    io.I_ctrl.is_mret := true.B
    io.illegal_inst := false.B
}
```

**新增控制信号：** `I_Ctrl` 中增加 `is_ecall: Bool`, `is_mret: Bool`，随流水线传递

---

### 阶段 3：WB 阶段 Trap 触发

**文件：** `design/src/main/scala/backend/BreezeBackend.scala`

**触发条件（WB 阶段）：**
- `memWbReg.illegal_inst === true.B` → 非法指令异常
- `memWbReg.is_ecall === true.B` → 环境调用异常
- 当前只处理这两种同步异常

**硬件动作（原子，单周期完成）：**

```
when (hasTrap) {
    // 1. 写 CSR
    mstatus.MPP  := CURRENT_PRIV    // 当前只有 M，填 2'b11 (machine)
    mstatus.MPIE := mstatus.MIE
    mstatus.MIE  := 0               // 关全局中断

    mcause  := exception_code       // 2=illegal, 11=ecall-from-M
    mtval   := exception_info       // illegal→指令编码, ecall→0
    mepc    := memWbReg.pc          // 出错指令的 PC

    // 2. PC 跳转
    pc_redirect := mtvec            // 跳转到异常入口

    // 3. 丢弃该指令的结果（WEN=0）
    wb_reg_en := false.B
}
```

**与现有流水线的交互：**
- Trap 触发时冲刷整个流水线（类似分支跳转，清空 IF/ID/EXE/MEM 所有级的 valid）
- `pc_redirect` 需要复用现有的分支跳转路径（`io_fe_ctl_pc_redir` + `io_fe_ctl_pc_misfetch`），或者新增一个独立的 trap redirect 端口

---

### 阶段 4：MRET 返回

**文件：** `design/src/main/scala/backend/BreezeBackend.scala`

**处理时机：** MRET 指令到达 WB 阶段

```
when (memWbReg.is_mret) {
    mstatus.MIE  := mstatus.MPIE
    mstatus.MPIE := 1
    mstatus.MPP  := 0    // 返回 U-mode（未来用）
    pc_redirect  := mepc
    // 冲刷流水线
}
```

---

### 阶段 5：验证

**测试用例：**

1. **非法指令 trap → 恢复 → MRET**
   ```
   // 执行一条非法指令 → trap 到 handler
   // handler 不做任何事，直接 MRET 返回
   // 预期：流水线回到正常执行
   ```

2. **ECALL trap**
   ```
   ecall(0)    // mcause=11, mepc=ecall_pc
   // handler 执行 MRET
   // 回到 ecall 下一条指令继续
   ```

3. **连续非法指令**
   ```
   // 验证 trap → return → 遇到另一条非法指令 → trap again
   ```

4. **所有 27 个现有测试保持通过**（引入 trap 不影响现有功能）

---

## 后续（本计划之外）

- 中断：mip/mie 寄存器 + 指令间中断采样
- 异步异常：取指/访存地址未对齐、访问错误
- 异常优先级仲裁
- S-mode 支持（medeleg/mideleg/stvec/sstatus 等）
- mtvec 向量模式
- 异常处理中的多周期 CSR 操作 → 引入 STALL

---

## 参考

- RISC-V Privileged Spec v1.12, Chapter 3.1.8 (Machine Trap Setup), 3.1.15 (Machine Trap Handling)
- 现有 CSRFile：`sim/vir_flow/rtl/CSRFile.sv`（3 个寄存器实现）
- 现有译码器：`design/src/main/scala/core/InstDecode.scala`（OPCODE.SYSTEM 段）
- 现有后端：`design/src/main/scala/backend/BreezeBackend.scala`（WB 写回路径）
