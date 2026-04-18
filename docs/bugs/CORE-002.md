# CORE-002

## Title

Taken-branch redirect in no-FASE flow could restart fetch from the correct target PC, but icache miss handling could reuse stale request state and stale line addressing.

## Status

fixed

## Symptom

In the `BreezeCore` no-FASE path, a taken conditional branch could redirect frontend `s1` to the correct target PC, and frontend `s2` could later also carry that target PC, but the redirected target instruction stream did not reliably advance into `s3` and decode.

During debug, the observed behavior split into two separate faults:

- after redirect, frontend recovery correctly produced:
- `s1_pcReg = targetPc`
- then `s2_pcReg = targetPc`
- but a new miss request for the redirected target did not always issue because cache miss request state was not re-armed for the next miss transaction
- after fixing that request-state bug, the redirected miss request issued again, but the request address could still be wrong:
- `s2_vaddr = 0x40`
- `line_addr = 0x0`
- so the cache refilled the old `0x0` line while tagging the response with frontend PC `0x40`

This meant the old line's instruction data could be returned under the redirected target PC context.

## Expected

For a taken branch redirect in the no-FASE path:

- backend should compute the correct redirect target
- frontend should flush younger wrong-path state
- frontend should restart fetch from the redirect target
- if the redirected target misses in icache, the cache should:
- issue exactly one miss request for that target line
- wait for the matching refill
- return the instruction data from the redirected target line
- the redirected target should then advance through:
- frontend `s1`
- frontend `s2`
- frontend `s3`
- backend decode

## Repro

Dedicated reproduction testcase:

- `design/src/test/scala/core/breezecoreSpec.scala`
- `flow.core.BreezeCoreNoFASESpec`
- `"BreezeCore should redirect to the taken branch target after a frontend mispredict"`

Run:

```bash
cd design
sbt "testOnly flow.core.BreezeCoreNoFASESpec"
```

## Evidence

The debugging pass established the following sequence:

1. Branch execution and redirect generation were correct.
   - branch decoded at `pc = 0x8`
   - backend produced `redirectValid = true`
   - backend produced `exeJumpAddr = 0x40`

2. Frontend flush/restart behavior was also correct.
   - after the flush boundary:
   - `s1_pcReg = 0x40`
   - `s2_valid = false`
   - `s3_valid = false`
   - later, `s2_pcReg = 0x40`

3. The first root cause was in cache miss request state.
   - `s2_req_pulse_done` was held high by `when(s2_valid)` and was not reliably cleared for the next miss transaction
   - this could suppress later miss requests entirely

4. After re-arming miss request state, a second root cause became visible.
   - for redirected miss `s2_vaddr = 0x40`, the cache still generated `line_addr = 0x0`
   - the miss request therefore went to `paddr = 0x0`
   - the refill data returned from `0x0` was then used while frontend state had already advanced to `pc = 0x40`

5. The final regression now proves the correct end-to-end behavior.
   - post-flush miss request is issued for `paddr = 0x40`
   - refill returns the `0x40` line
   - frontend `s3` becomes valid at `pc = 0x40`
   - backend decode observes:
   - `decodePc = 0x40`
   - `decodeInst = 0x02a00293`
   - which is `addi x5, x0, 42`, the first instruction in the redirected target line

## Root Cause

There were two independent cache-side bugs.

1. Miss request pulse state was modeled as a level tied to `s2_valid` instead of a per-miss transaction state.

Original behavior:

- `s2_req_pulse_done` was set whenever `s2_valid` was true
- `s2_done` did not have priority to clear it at the end of a miss
- later misses could inherit stale `pulse_done = true` and never emit a new `next_level_req`

2. Cache line alignment used a narrow-width mask.

Original code shape:

```scala
line_addr := s2_vaddr & ~((cacheConfig.ICACHE_LINE_BYTES - 1).U)
```

Because the literal mask used the minimum width of the constant instead of `PLEN`, the effective aligned address could collapse to `0x0` instead of preserving the upper address bits.

## Fix

The fix has two parts in `design/src/main/scala/cache/BreezeCache.scala`:

1. Rework miss request pulse state around a transaction-level pulse condition:

- introduce `issue_s2_req_pulse = s2_valid && !s2_req_pulse_done`
- clear `s2_req_pulse_done` when `s2_done`
- set `s2_req_pulse_done` only when a new pulse is issued
- update `wait_rsp` with the same transaction boundaries

2. Widen the cache line alignment mask to `PLEN`:

```scala
line_addr := s2_vaddr & ~((cacheConfig.ICACHE_LINE_BYTES - 1).U(cacheConfig.PLEN.W))
```

## Verification

Verified with:

```bash
cd design
sbt "testOnly flow.core.BreezeCoreNoFASESpec"
```

Observed result:

- both `BreezeCoreNoFASESpec` tests pass
- redirected target line is requested at `0x40`
- redirected target decode instruction matches the programmed target line content

## Related Files

- `design/src/main/scala/cache/BreezeCache.scala`
- `design/src/main/scala/frontend/BreezeFrontend.scala`
- `design/src/main/scala/core/BreezeCore.scala`
- `design/src/test/scala/core/breezecoreSpec.scala`
