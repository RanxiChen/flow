# PHT training control timing

The saved 2026-09-11 routed KCU105 checkpoint has 105 failing endpoints from
CLINT mtime to PHT table CE pins, worst slack -0.194 ns. The expanded worst
path is 22 levels and 9.775 ns (2.027 ns logic, 7.748 ns routing):

```
mtime >= mtimecmp -> machine interrupt eligibility -> interruptRedirect
-> divReqIssued -> pipelineHold -> gated training index
-> PHT old-counter read -> saturation check -> table CE
```

The comparator's register fanin confirms CLINT mtimecmp, not Sstc stimecmp.
The baseline exports are `/tmp/flow-mtime-pht-timing.rpt` and
`/tmp/flow-mtime-pht-cone.txt`. They predate this change.

## Change

For GShare, the backend continuously supplies the EXE instruction's saved
PHT index and actual taken result. Existing training validity conditions
remain unchanged. Invalid-cycle data is ignored by the PHT, so the update
index no longer waits for the hold decision before selecting the old counter.
Training remains in the original cycle; no new stage is added.

Multiply, divide, and FP issue no longer test `!interruptRedirect`. Each
already requires `exeMemReg.valid`, whereas interruptRedirect requires
pipelineEmpty and therefore `!exeMemReg.valid`. These predicates are mutually
exclusive. Exception, xret, and fence cancellation remain, as do functional
unit flush connections. This simplification depends on taking interrupts
only after the backend drains; revisit it if interrupt entry changes.

An enabled pending interrupt still blocks decode acceptance. Existing backend
instructions finish and branches may train. Only after drain does interrupt
entry redirect and flush the frontend. No blanket pending-interrupt training
suppression is introduced.

## Tests prepared, not executed

The existing older-load/held-branch test in `BreezeBackendGShareSpec` now also
checks that the nonzero training index is available while update.valid is low.
Three new cases raise a timer interrupt at multiply, divide, and FP request
issue with a younger branch already in EXE. They check blocked acceptance of
another offered instruction, completion of both older instructions, exactly
one PHT update, and timer trap cause/mepc after drain. Integer cases also
check the result value.

Per user instruction, this batch has **not been compiled, elaborated, or
simulated**. Only source/diff review was performed. Keep it uncommitted with
the other timing changes and run combined validation on Alan after the
remaining edits are ready. Suggested commands from Alan's `flow/design/`:

```sh
sbt 'testOnly flow.backend.BreezeBackendGShareSpec flow.backend.BreezeBackendMulSpec flow.backend.BreezeBackendDivSpec flow.backend.BreezeBackendFpSpec'
sbt 'testOnly flow.core.CSRFileSpec flow.core.BreezePrivilegeSpec flow.core.BreezeCsrPipelineSpec'
sbt 'runMain flow.top.GenerateBreezeMulticoreClusterWishbone single gshare linux fpga-debug'
```

The CSR tests previously passed locally before this backend change; that is
not validation of the combined revision. Area and timing improvements require
a subsequent Vivado implementation with the same FPGA configuration.
