# CORE-003

## Title

CSRRW instruction (CSR_CMD.RW) write to mepc fails in handler context after ecall trap redirect, while csrrs (RS) works.

## Status

open — 已确认可复现 (2026-05-26)

当前 `breezecoreSpec.scala` 的 ecall handler 测试已改为使用 `csrrw`，**33/34 测试通过，ecall→handler→mret 测试失败**，确认 csrrw 在 handler 上下文写 mepc 确实触发此 bug。

## Symptom

In the ecall→handler→mret end-to-end test, the handler uses the sequence:

```
0x200: csrrs x5, mepc, x0   // x5 = mepc (= 0x808)
0x204: addi  x5, x5, 4      // x5 = 0x80c
0x208: csrrw x0, mepc, x5   // mepc ← x5 (RW command, rd=x0)
0x20c: mret                  // should jump to mepc = 0x80c
```

Observed:
- `cy=42 pc=0x208 mepc=0x808` — csrrw retires but mepc unchanged
- `cy=43 pc=0x20c mepc=0x4`   — mret fires, mepc becomes 0x4 instead of 0x80c
- Core jumps to low memory (0x0) instead of returning to 0x80c

When csrrw is replaced by csrrs (identical semantics via set-bits: `mepc | x5`), the test passes correctly with mepc=0x80c.

## Expected

After csrrw x0, mepc, x5 retires at cycle 42, mepc should be updated to 0x80c by the next cycle. mret at cycle 43 should redirect to mepc=0x80c.

## Repro

```bash
cd design
# 当前代码已使用 csrrw，直接全量跑即可复现：
sbt test
# 或单测：
sbt 'testOnly flow.core.BreezeCoreNoFASESpec -- -t "handler"'
```

当前 handler 代码（已改为 csrrw）：
```scala
val csrrw_x5_mepc = encodeCsr(rd = 5, rs1 = 0, csr = CSRMAP.mepc, funct3 = 1)  // csrrw x5, mepc, x0
val csrrw_mepc   = encodeCsr(rd = 0, rs1 = 5, csr = CSRMAP.mepc, funct3 = 1)  // csrrw x0, mepc, x5
```

失败结果：`false was not equal to true (breezecoreSpec.scala:1919)`

如需绕过 bug，改回 csrrs：
```scala
val csrrs_x5_mepc = encodeCsr(rd = 5, rs1 = 0, csr = CSRMAP.mepc, funct3 = 2)  // csrrs x5, mepc, x0
val csrrs_mepc   = encodeCsr(rd = 0, rs1 = 5, csr = CSRMAP.mepc, funct3 = 2)  // csrrs x0, mepc, x5
```

## Analysis

### What works (not the bug)

- CSRFile unit test: `CSR_CMD.RW` write to mtvec, commit, read-back — **passes**
- FASE pipeline test: `addi x1, 0x345 → csrrw x0, mtvec, x1 → csrrs x2, mtvec` — **passes**

Both confirm the CSRRW instruction encoding, CSR_CMD.RW path, and commit mechanism are correct in isolation.

### Root cause confirmed (2026-05-26): forwarding path selects immediate instead of ALU result when addi rs1==rd

The bug is triggered specifically when:
1. `addi` has **rs1 == rd** (same register as both source and destination)
2. `csrrw` subsequently **reads that same register as rs1**

In this scenario, the forwarding/bypass logic routes the **immediate value (4)** instead of the **ALU result (0x80c)** to the CSR write data path, causing mepc to become `0x4`.

### Diagnostic test matrix

All tests use the same ecall→handler→mret framework, varying only the handler instruction sequence:

| # | Handler pattern | rs1==rd? | Result |
|---|----------------|----------|--------|
| 1 | `csrrw x5, mepc → addi x5, x5, 4 → csrrw x0, mepc, x5` | ✅ rs1==rd | ❌ FAIL |
| 2 | `csrrs x5, mepc → addi x5, x5, 4 → csrrw x0, mepc, x5` | ✅ rs1==rd | ❌ FAIL |
| 3 | `csrrw x5, mepc → addi x6, x5, 4 → csrrw x0, mepc, x6` | ❌ rd≠rs1 | ✅ PASS |
| 4 | `csrrw x5, mepc → addi x5, x5, 4 → nop → csrrw x0, mepc, x5` | ✅ rs1==rd | ❌ FAIL |
| 5 | `csrrw x3, mepc → addi x5, x3, 4 → csrrw x0, mepc, x5` | ❌ rs1≠rd | ✅ PASS |

**结论：**
- Test 2 排除了「csrrw 读 zero 了 mepc」的假说（csrrs 读也同样失败）
- Test 3 排除了「csrrw 本身有问题」的假说（不同 rd 就通过）
- Test 4 排除了简单的「背靠背时序不够」假说（NOP 不管用）
- Test 5 锁定：addi 的 rs1 ≠ rd 时一切正常
- **只有当 addi 的 rs1==rd 时，后续 csrrw 读同一个寄存器才会拿到错误的值（立即数而非 ALU 结果）**

### Next step for fix

排查 `BreezeBackend.scala` 中 EXE 阶段的转发/bypass 逻辑：当 `addi rd, rs1, imm` 且 `rd == rs1` 时，转发路径可能错误地将 imm 旁路给了后续 CSR 指令的 rs1 输入。

### Workaround

Use `csrrs x0, mepc, x5` (CSR_CMD.RS, funct3=2) instead of `csrrw x0, mepc, x5`:

```scala
// csrrs with rs1=x5: new_val = mepc | x5
// If mepc=0x808 (0b1000_0000_1000) and x5=0x80c (0b1000_0000_1100),
// then mepc | x5 = 0x80c — same result as direct write
val csrrs_mepc = encodeCsr(rd = 0, rs1 = 5, csr = CSRMAP.mepc, funct3 = 2)
```

## Related Files

- `design/src/main/scala/core/RegFile.scala` — CSRFile CSR_CMD.RW/RS path
- `design/src/main/scala/backend/BreezeBackend.scala` — pipeline bypass and CSR commit（重点排查 EXE 阶段转发逻辑）
- `design/src/test/scala/core/breezecoreSpec.scala` — Base test + 5 CORE-003 diagnostic variants (Tests 1-5 above)
