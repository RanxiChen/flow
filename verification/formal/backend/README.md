# T01 MDU protocol proofs

Generate on Alan only, from `design`:

```sh
sbt 'runMain flow.backend.GenerateIntMduFormal /absolute/run/directory/generated'
```

Current step-3 review decision: prove **control only** for both units.
Copy `mul_protocol_abc.sby`, `div_abc.sby`, `lower_immediate.py` and
`UnsignedRadix4DividerAbstract.sv` into that run directory. Lower the concrete
wrapper/harness SV with `python3 lower_immediate.py generated/mul yosys/mul`
and the corresponding command for DIV. This preserves all expressions,
guards, labels and properties, replacing only diagnostic `else $error` actions
unsupported by Yosys 0.62. Retain raw RTL and the lowering audit.

Run one task at a time, with no second solver running:

```sh
sby -f mul_protocol_abc.sby bmc
sby -f mul_protocol_abc.sby prove
sby -f mul_protocol_abc.sby cover
sby -f div_abc.sby bmc
sby -f div_abc.sby prove
sby -f div_abc.sby cover
```

BMC is ABC bmc3 at depth 80; prove is ABC PDR, unbounded with no induction
depth; cover is SMTBMC/Z3 at depth 80. Timeout is **3600 seconds per task**.
If a task returns unknown/timeout, record it and stop the task; do not switch
engines and retry. F03/F05/F07/F08/F11 unit asserts and every cover must pass
before backend integration. Save exact source/RTL/config hashes, commands,
exit codes, logs and witnesses. Arithmetic is covered by step-2 B random tests
and U01/U02; these control proofs make no arithmetic correctness claim.

Historical full-arithmetic `mul.sby` and `div.sby` are abandoned for solver
resources (arithmetic covered by simulation); do not execute them. Keep their
original run directories/logs. `mul_protocol.sby` is also a historical Z3
configuration, not the current acceptance command.

| Requirement | Boundary / check | Assumptions | Evidence |
| --- | --- | --- | --- |
| F03 | Accepted-request FIFO loses only its uncommitted tail on kill; every output belongs to its committed head | Legal caller below | BMC + unbounded PDR; write after kill and kill on done covers |
| F05 | Actual production assertions check result valid, rd and data stability under arbitrary result backpressure, including kill | No fairness or maximum hold | BMC + unbounded PDR; kill while held cover |
| F07 | FIFO commit prefix equals physical committed entries; output requires committed head; commit resolved before kill | Commit names oldest accepted uncommitted item | BMC + unbounded PDR; commit + kill cover |
| F08 | Accepted = written + killed + live modulo 2^32; FIFO equals physical live slots and committed population; capacities 4 and 1 | No WAW restriction, no result fairness | BMC + unbounded PDR; full MUL, eight-cycle uncommitted P4 hold then commit/kill covers |
| F11 (unit part) | Physical uncommitted item exists for every legal FIFO commit | WB commit refers to an accepted uncommitted instruction | BMC + unbounded PDR; commit + kill cover |

Common caller assumptions are an initial reset, commit only when the independent
accepted-request FIFO has an uncommitted entry, kill suppresses younger EX
request valid, and request rd is nonzero. Reset may recur with work in flight;
it begins a new accounting epoch. Input payloads and result ready are otherwise
arbitrary. There is no no-WAW assumption, no permanently-low-reset assumption,
no fairness assumption and no bounded external hold. The physical observation
ports are confined to subclasses instantiated only by this formal generator.
All wrapper/harness production assertions remain in the emitted RTL. F11 scoreboard set/clear
and cross-unit covers require the later integrated harness; they are not claimed
by these unit proofs. This protocol proof does not replace arithmetic U01/U02.

`mul_protocol.sby` additionally offers a control proof with one conservative
datapath cutpoint: the single combinational `$mul` output becomes an arbitrary
130-bit value each cycle. This permits every concrete product and more values,
so a safety proof on this model implies the same safety properties on the
concrete multiplier. No register, handshake, property, assumption or cover is
removed; assertions still check the full result and full stored payload under
backpressure. A false counterexample is possible, a false safety pass is not.
The selector fails unless exactly one multiplication cell is found. Budgets and
assumptions are unchanged. Keep the abandoned full arithmetic run and its logs separate;
this control proof makes no arithmetic equivalence claim (see U01).

Additional ownership assertions compare every live MUL stage's rd, full product
and operation to its independent accepted-request FIFO entry, and compare its
committed bit to the committed FIFO prefix. The FIFO captures the combinational
product as a payload; in the control model that payload is arbitrary, shared by
the actual P1 input and the FIFO, and must retain its transaction ownership all
the way through kill/commit/write. This verifies ownership rather than duplicating
the arithmetic equivalence claim. Occupied DIV rd ownership is checked before
as well as after commit, and internally completed DIV data is checked for
stability even before it becomes externally valid. These are extra assertions,
not assumptions; they strengthen the invariant under indefinite legal holds.

DIV replaces only the emitted unsigned radix-4 core module with
`UnsignedRadix4DividerAbstract.sv`. `CommittedDivUnit` remains unchanged,
including occupied/commit/kill/done, sign and W restoration, result holding,
and the fast path. Quotient/remainder are arbitrary 64-bit values; completion
may occur on acceptance (zero waiting edges) or 1..34 edges afterward. The
completion output is registered, so zero waiting edges has the same interface
timing as the concrete core's short path. The one additional approved assume
forces `finish_now` when an active request's age reaches 33, i.e. on the 34th
edge after acceptance. Reset/flush cancel that request and its obligation.
The model retains the core's request-while-busy assertion and adds a counter
bound assertion plus covers for the 0 and 34 latency endpoints. It contains
no arithmetic constraints, output-ready fairness or external hold bound.
