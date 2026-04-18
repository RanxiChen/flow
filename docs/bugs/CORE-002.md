# CORE-002

## Title

Taken branch redirect reaches frontend `s1` and `s2`, but the redirected target does not advance into frontend `s3`.

## Status

open

## Symptom

In the `BreezeCore` no-FASE path, a taken conditional branch can execute correctly in the backend and generate a valid frontend redirect, but the redirected target flow stalls before the target instruction reaches frontend `s3`.

The currently observed sequence is:

- the branch instruction is fetched, decoded, and enters backend execute
- backend computes the correct branch result:
- `exeBruTaken = true`
- `exeJumpAddr = targetPc`
- `redirectValid = true`
- one cycle later, frontend receives the redirect and updates:
- `s1_valid = true`
- `s1_pcReg = targetPc`
- `s2_valid = false`
- `s3_valid = false`
- later, frontend `s2` becomes valid again and:
- `s2_pcReg = targetPc`
- in that `s2` cycle, backend pipeline state matches the expected flush/recovery shape:
- `decodeValid = false`
- `idExeValid = false`
- `exeMemValid = false`
- `memWbValid = true`
- but after that, the testcase still does not observe `s3_valid` for the redirected target PC within the observation window

So the redirected target is able to re-enter frontend up to `s2`, but the target path is not observed to progress into `s3`.

## Expected

For a taken branch with a redirect:

- backend should execute the branch and assert a redirect with the correct target
- frontend should flush the wrong-path younger state
- frontend should restart fetch from the redirected target PC
- after recovery:
- `s1` should move to the target PC
- then `s2` should move to the target PC
- then `s3` should also become valid for that same target PC
- during the recovery window before new target data reaches decode:
- decode/execute-side younger stages should remain invalid until the redirected fetch path produces new frontend output

## Repro

Dedicated reproduction testcase:

- `design/src/test/scala/core/breezecoreSpec.scala`
- `flow.core.BreezeCoreNoFASESpec`
- `"BreezeCore should redirect to the taken branch target after a frontend mispredict"`

Run:

```bash
cd design
sbt testOnly flow.core.BreezeCoreNoFASESpec -- -z "redirect to the taken branch target after a frontend mispredict"
```

Current testcase structure:

1. Instantiate `BreezeCore(BreezeCoreConfig(useFASE = false), enabledebug = true)`.
2. Drive icache misses through an address-to-32B-line map.
3. Program layout:
- branch at `0x8`
- redirect target at `0x40`
4. Observe backend debug signals:
- `decodeInst/decodePc`
- `idExeInst/idExePc`
- `exeBruTaken`
- `exeJumpAddr`
- `redirectValid`
- `exeMemPc`
5. Observe frontend debug signals:
- `s1_pcReg`
- `s2_pcReg`
- `s3_pcReg`
- `s1_valid/s2_valid/s3_valid`

## Evidence

The current regression establishes all of the following successfully before failing:

- the branch is decoded at `pc = 0x8`
- the branch enters execute at `pc = 0x8`
- the execute result is:
- `exeBruTaken = true`
- `exeJumpAddr = 0x40`
- `redirectValid = true`
- on the next cycle after redirect:
- `frontend.s1_pcReg = 0x40`
- `frontend.s2_valid = false`
- `frontend.s3_valid = false`
- `backend.decodeValid = false`
- `backend.idExeValid = false`
- `backend.exeMemValid = true`
- `backend.exeMemPc = 0x8`
- later, when frontend `s2` becomes valid again:
- `frontend.s2_pcReg = 0x40`
- `backend.decodeValid = false`
- `backend.idExeValid = false`
- `backend.exeMemValid = false`
- `backend.memWbValid = true`

The failing part is the final observation:

- the testcase continues watching for `frontend.s3_valid`
- while requiring `backend.decodeValid = false` and `backend.idExeValid = false`
- but it does not observe `frontend.s3_valid` carrying `pc = 0x40` before the timeout window is exhausted

The current failure is therefore a timeout-style observation failure:

- not "s3 became valid with the wrong PC"
- but "the expected redirected target never became visible at s3 within the observation window"

## Current Hypothesis

The bug is likely in the frontend redirect recovery path between `s2` and `s3`, not in backend branch execution itself.

What is already ruled in:

- branch execution is correct
- redirect generation is correct
- redirected target PC reaches frontend `s1`
- redirected target PC later reaches frontend `s2`

What is still suspicious:

- the `s2_respValid -> s3` handoff after redirect recovery
- interaction between redirect suppression and the `s3` load condition in `BreezeFrontend`
- whether the redirected fetch response is overwritten, masked, or never considered eligible for `s3`

In particular, the current `BreezeFrontend` logic contains a narrow `s3` load condition that is worth re-checking under redirect recovery:

- `when(!reset.asBool && !redirectValid && s2_respValid) { ... }`

The recovery path may be reaching a state where the redirected target returns to `s2`, but the corresponding transition into `s3` is not reconstructed correctly afterward.

## Related Files

- `design/src/main/scala/frontend/BreezeFrontend.scala`
- `design/src/main/scala/backend/BreezeBackend.scala`
- `design/src/main/scala/interface/interface.scala`
- `design/src/test/scala/core/breezecoreSpec.scala`
