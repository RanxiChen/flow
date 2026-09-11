# CSR parallel reads and pipelined performance counters

The 2026-09-11 saved KCU105 report attributes 6481 LUTs and 2643 FFs to
`csrFile` at post-place. Its routed checkpoint retains 64 FFs for each of the
eight HPM event selectors, although software can only select events 0..10.
The saved set of 748 failing setup endpoints includes 239 HPM counter endpoints:
120 from division logic and 119 from MMU state. These are baseline results,
not measurements of this refactor.

## Read structure

`CSRFile` groups reads into privilege state, address protection, performance,
and floating-point/identity/custom banks. Each entry describes one value and
its read aliases. Address comparisons are shared by the implemented-CSR
whitelist and the read selectors. Elaboration rejects duplicate addresses.
Each bank uses `Mux1H` with explicitly zero-extended XLEN-wide data; unselected
banks return zero and their results are ORed together. Machine/user counter
aliases therefore select the same value once instead of adding separate
wide priority-mux entries.

Privilege checks, CSR command behavior, MEM-stage reads, WB-stage writes,
trap/return priorities, and continuously available MMU/PMP outputs retain
their existing interface. Unsupported PMP slots remain implemented read-zero,
write-ignored CSRs. Unknown addresses remain illegal.

## Performance state

`BreezePerformanceCounters` owns `coreinst`, `mcycle`, `minstret`,
`mcountinhibit`, and eight HPM counters/selectors. CSRFile qualifies its write
input with commit-valid, write-enable, and absence of a trap. Existing cycle,
retirement, and explicit-write priorities are preserved.

Selectors store four bits and are zero-extended on read. The full XLEN write
value must be in 0..10 before its low four bits are accepted; e.g. 0x101 still
selects NONE. Selector state shrinks by 480 bits. This is an RTL state-count
reduction, not a measured CLB saving.

Each HPM counter has stored count B and one pending-increment bit P. Its
architectural read value is V = (B + P) modulo 2^XLEN. On an ordinary edge:

```
B' = V
P' = selected current event && !current inhibit
V' = V + selected enabled event    (modulo 2^XLEN)
```

An accepted software counter write instead sets B' to the write value and
P' to zero, giving it the same priority as before. Selector and inhibit writes
affect subsequent events: the decision captured on their write edge uses the
pre-edge configuration. A trap suppresses CSR writes but does not discard
events or pending counts; reset clears both stored and pending state.

This cuts the current event-to-wide-counter update path at the pending FF.
It adds eight FFs, making the combined selector/pending state reduction 472
bits. Read compensation adds combinational logic driven only by registered
state; its timing and the remaining event-to-pending path require measurement.
No additional CPU instruction stall or CSR access stage is introduced.

## Validation

`BreezeCsrPipelineSpec` compares all eight counters, both read aliases, event
selectors, inhibit, cycle and retirement counts against an immediate-count
software model after every edge. Directed cases cover all ten events,
configuration boundaries, continuous events, pending overwrite, wraparound,
invalid high-bit selector writes, and reset. Four hundred seeded random cycles
mix events, writes, inhibit, retirement, and rejected/trap-suppressed writes.
A separate test checks distinct full-width values across read banks, aliases,
read-modify-write commands, and read-zero versus unknown addresses.

Commands from `design/`:

```sh
sbt 'testOnly flow.core.CSRFileSpec flow.core.BreezePrivilegeSpec'
sbt 'testOnly flow.core.BreezeCsrPipelineSpec' 'runMain flow.top.GenerateBreezeMulticoreClusterWishbone single gshare linux fpga-debug'
sbt 'testOnly flow.core.BreezeCoreSpec -- -z CSR'
```

Local validation on 2026-09-11: **23/23 tests passed**: CSRFile (6), privilege
and PMP/Sstc (12), new pipeline/read-bank tests (2), and whole-core CSR
dependencies/adjacent writes/CSRRW with rd=0 (3). The Linux/GShare/FPGA-debug
single-hart top elaborated successfully. Generated RTL has eight 4-bit
selectors, eight pending FFs, parallel masked-OR reads, and counter updates
from registered state. Top elaboration retains the 27 pre-existing single-hart
index-width warnings in L2/Home and the MMIO arbiter.

Logs: `/tmp/flow-csr-validation.log`, `/tmp/flow-csr-pipeline-validation.log`,
and `/tmp/flow-csr-core-validation.log`. These are local functional and
elaboration checks; no new Linux boot or Vivado implementation was run.

The reports under `fpga/kcu105/reports/` predate these changes. A new controlled
Vivado implementation is needed to compare LUT/FF/CLB use and setup timing.
