# CORE-001

## Title

Load instruction pipeline execution path loses the outstanding memory operation before writeback completes.

## Status

open

## Symptom

`BreezeCore` can issue a data-memory request for a load instruction, but the load does not always reach architectural writeback through the normal pipeline observation path.

The current dedicated regression fails on the `lb` case:

- request is observed on `io.dmem.req`
- the test returns a response on `io.dmem.rsp`
- but the testcase still fails while waiting for the normal `memWbValid` observation window

## Expected

For a load instruction, after `io.dmem.req.valid` is observed and a matching response is returned on `io.dmem.rsp`, the backend should preserve the outstanding load state until writeback is produced:

- `memWaitingResp` should reflect the pending transaction
- the load context should remain alive until response handling completes
- `memWbValid` should pulse
- `wbData` should match the loaded value with the proper sign/zero extension semantics

## Repro

Current failing reproduction:

- `design/src/test/scala/core/breezecoreSpec.scala`
- `flow.core.BreezeCoreSpec`
- `"BreezeCore should execute supported RV64I load instructions through dmem"`

Run:

```bash
cd design
sbt "testOnly flow.core.BreezeCoreSpec"
```

Current result:

- arithmetic tests pass
- store-path test passes
- load-path test fails
- failing subcase: `load case lb`

## Evidence

Current debug output from the failing `lb` reproduction:

```text
[lb-debug] req: valid=true isWrite=false addr=0x20 exeMemValid=true memWaitingResp=false
[lb-debug] post-rsp cycle 0: exeMemValid=false exeMemData=0x0 memWaitingResp=false memWbValid=true wbData=0xffffffffffffff80
[lb-debug] post-rsp cycle 1: exeMemValid=false exeMemData=0x0 memWaitingResp=false memWbValid=false wbData=0x0
```

This shows:

- the load request is issued correctly
- the response can still generate the expected loaded value
- but the load wait/hold behavior around the pipeline is inconsistent
- the observation window for normal load completion is fragile enough that the testcase still fails

## Current Hypothesis

The load pipeline path has a state-retention problem around the memory request / response window.

More specifically:

- the outstanding load state is not being held in a robust way through the full request/response/writeback sequence
- `exeMemReg`, `memWaitingRespReg`, and `memWbReg` timing interact in a way that makes load completion dependent on a narrow cycle window
- stores are less affected because they do not need the same final loaded-data writeback path

One mitigation has already been tried:

- hold the pipeline in the memory request cycle (`memReqIssued`) so `exeMemReg` is not overwritten immediately

That improved the situation enough to expose a correct `wbData` pulse in debug, but the bug is not considered resolved yet.

## Current Resolution State

Unresolved.

This bug note exists to preserve the current failing reproduction and the latest evidence while debugging continues.

## Related Files

- `design/src/main/scala/backend/BreezeBackend.scala`
- `design/src/main/scala/core/BreezeCore.scala`
- `design/src/test/scala/core/breezecoreSpec.scala`
