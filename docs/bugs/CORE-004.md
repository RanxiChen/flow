# CORE-004

## Title

Branch redirect is resolved with a stale register value while an older load
holds the pipeline.

## Status

fixed locally — 2026-08-03

## Symptom

`breeze_uart_puts()` emitted one extra NUL after every string. The retirement
trace showed the terminator load and the incorrect UART store explicitly:

```text
LBU loads 0 from the string terminator
BNEZ immediately after it redirects as if the previous character were nonzero
SW writes 0 to UART_RXTX
```

The UART interrupt smoke also printed its PASS line twice. These were core
execution errors, not characters inserted by the LiteX UART model.

## Expected

A branch immediately dependent on a completed load must use the loaded value.
While the load stalls ID/EXE, the branch must not redirect or train the branch
predictor.

## Root Cause

`BreezeBackend` held the dependent branch in ID/EXE with `pipelineHold`, but
`redirectDirectionMismatch` and `redirectTargetMismatch` were still evaluated
from that valid ID/EXE entry. The branch therefore generated a redirect using
the pre-load register value before MEM/WB forwarding became available.

## Fix

Require `!pipelineHold` before branch redirect resolution and GShare training.
The branch is resolved only on the cycle in which it can advance to EXE/MEM.

## Repro and Verification

The directed test initializes `x2=1`, loads zero into `x2`, then executes an
adjacent `bne x2,x0`. It checks that the branch retires and no redirect occurs.

```bash
cd design
sbt 'testOnly flow.core.BreezeCoreSpec -- -z load-to-branch'
```

The same test fails on the old backend because the branch does not retire, and
passes with the fix. End-to-end LiteX runs for generic `main.c` plus Timer/UART
Direct/Vectored all pass; all five raw logs have zero NUL bytes, and each UART
PASS line occurs exactly once.

The two initially failing supported-load/store groups were separate test bugs:
the tests observed the CPU-to-DCache request, whose address is the full
`rs1 + imm` effective address, but expected the later DCache-to-Wishbone
beat-aligned address. After changing those expectations to `0x20 + offset`,
the full `BreezeCoreSpec` passes all 12 tests. `sbt build` also passes.

## Related Files

- `design/src/main/scala/backend/BreezeBackend.scala`
- `design/src/test/scala/core/breezecoreSpec.scala`
- `software/breeze-mcu/lib/uart.c`
