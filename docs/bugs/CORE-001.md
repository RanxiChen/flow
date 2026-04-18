# CORE-001

## Title

Load instruction pipeline execution path loses the outstanding memory operation before writeback completes.

## Status

fixed

## Symptom

`BreezeCore` previously could issue a data-memory request for a load instruction and generate the correct loaded value internally, while the dedicated regression still failed because the outstanding memory operation and the testcase observation window were not aligned robustly enough.

## Expected

For a load instruction, after `io.dmem.req.valid` is observed and a matching response is returned on `io.dmem.rsp`, the backend should preserve the outstanding load state until writeback is produced:

- `memWaitingResp` should reflect the pending transaction
- the load context should remain alive until response handling completes
- `memWbValid` should pulse
- `wbData` should match the loaded value with the proper sign/zero extension semantics

## Repro

Resolved reproduction coverage:

- `design/src/test/scala/core/breezecoreSpec.scala`
- `flow.core.BreezeCoreSpec`
- `"BreezeCore should execute supported RV64I load instructions through dmem"`
- `"BreezeCore should execute supported RV64I store instructions through dmem"`

Run:

```bash
cd design
sbt "testOnly flow.core.BreezeCoreSpec"
```

Current result:

- arithmetic pipeline tests pass
- load-path regression passes
- store-path regression passes

## Evidence

The earlier failing `lb` debug output was:

```text
[lb-debug] req: valid=true isWrite=false addr=0x20 exeMemValid=true memWaitingResp=false
[lb-debug] post-rsp cycle 0: exeMemValid=false exeMemData=0x0 memWaitingResp=false memWbValid=true wbData=0xffffffffffffff80
[lb-debug] post-rsp cycle 1: exeMemValid=false exeMemData=0x0 memWaitingResp=false memWbValid=false wbData=0x0
```

That showed:

- the load request is issued correctly
- the response can still generate the expected loaded value
- but the old testcase observed writeback through a fragile one-cycle window and still reported failure

## Root Cause

This issue had two parts:

- The backend load path needed to hold pipeline state in the memory request / response window so the outstanding memory operation was not overwritten before completion.
- The old testcase drove memory responses with ad-hoc `step` sequencing and then tried to observe a one-cycle `memWbValid` pulse after advancing past it.

In other words, the core pipeline needed stronger state retention, and the regression itself needed a deterministic memory driver instead of manual cycle-by-cycle pokes.

## Resolution

This bug is considered fixed.

- The core pipeline was fixed by holding the backend pipeline in the memory request cycle so the outstanding memory operation remains alive across request, wait, response, and writeback handling.
- The regression testcase was rewritten around a fake instruction queue plus a deterministic dmem driver.
- Instructions are now issued only when `inst_ready` is high.
- Memory requests are observed and recorded centrally.
- Matching dmem responses or write acks are returned after a fixed 6-cycle delay.
- Load and store regressions now use the same driver structure.

This removes the previous fragile observation pattern and verifies the fixed core pipeline behavior through a stable end-to-end regression.

## Related Files

- `design/src/main/scala/backend/BreezeBackend.scala`
- `design/src/main/scala/core/BreezeCore.scala`
- `design/src/test/scala/core/breezecoreSpec.scala`
