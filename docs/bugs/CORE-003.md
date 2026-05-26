# CORE-003

## Title

CSRRW instruction (CSR_CMD.RW) write to mepc fails in handler context after ecall trap redirect, while csrrs (RS) works.

## Status

open

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
# csrrs version works:
#   "BreezeCore should handle ecall → handler(advance mepc) → mret → return to next instruction"

# csrrw version fails — change handler line from csrrs to csrrw:
#   val csrrw_mepc = encodeCsr(rd = 0, rs1 = 5, csr = CSRMAP.mepc, funct3 = 1)
sbt 'testOnly flow.core.BreezeCoreNoFASESpec -- -t "handler"'
```

## Analysis

### What works (not the bug)

- CSRFile unit test: `CSR_CMD.RW` write to mtvec, commit, read-back — **passes**
- FASE pipeline test: `addi x1, 0x345 → csrrw x0, mtvec, x1 → csrrs x2, mtvec` — **passes**

Both confirm the CSRRW instruction encoding, CSR_CMD.RW path, and commit mechanism are correct in isolation.

### Where it fails

The bug only manifests in the handler context after an ecall trap redirect. Possible causes:

1. **Pipeline bypass issue**: The `addi x5, x5, 4` instruction produces 0x80c, but when `csrrw x0, mepc, x5` reads x5 in the EXE stage, the forwarded/bypassed value might be the immediate (4) instead of the ALU result (0x80c). This would explain mepc becoming 0x4.

2. **CSR state hazard**: The `csrrs x5, mepc, x0` at 0x200 reads mepc, then `csrrw x0, mepc, x5` at 0x208 writes mepc. If the CSR pipeline hazard detection incorrectly stalls or misroutes the write data.

3. **Commit timing**: When csrrw retires, the `io.commit_write_en` from CSRFile may be deasserted due to a pipeline flush/redirect from a prior event.

### Workaround

Use `csrrs x0, mepc, x5` (CSR_CMD.RS, funct3=2) instead of `csrrw x0, mepc, x5`:

```scala
// csrrs with rs1=x5: new_val = mepc | x5
// If mepc=0x808 (0b1000_0000_1000) and x5=0x80c (0b1000_0000_1100),
// then mepc | x5 = 0x80c — same result as direct write
val csrrs_mepc = encodeCsr(rd = 0, rs1 = 5, csr = CSRMAP.mepc, funct3 = 2)
```

## Related Files

- `design/src/main/scala/core/RegFile.scala` — CSRFile CSR_CMD.RW/RW path
- `design/src/main/scala/backend/BreezeBackend.scala` — pipeline bypass and CSR commit
- `design/src/test/scala/core/breezecoreSpec.scala` — mret test (currently uses csrrs workaround)
