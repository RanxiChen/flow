# T01 MDU protocol proofs

Generate on Alan only, from `design`:

```sh
sbt 'runMain flow.backend.GenerateIntMduFormal /absolute/run/directory/generated'
```

Copy `mul.sby`, `div.sby` and `lower_immediate.py` into that run directory.
Run `python3 lower_immediate.py generated/mul yosys/mul` and the corresponding
command for DIV. Yosys 0.62 does not parse firtool's diagnostic `else $error`
action blocks: this lowering preserves each assertion/assumption expression,
cover, label and guard, replaces only the diagnostic action with a semicolon,
and audits property counts, removed diagnostic text and input/output hashes.
The raw generated RTL is retained. This does not weaken any property.
Run `sby -f mul.sby`
and `sby -f div.sby` there. Save exact source SHA, tool versions, generated
RTL, logs, results and cover witnesses. Before the first run the registered
budget is BMC 80, induction 80, cover 80, Z3 SMTBMC, 1800 seconds per task.
A timeout or unknown is not a pass. Budgets must not be reduced for acceptance.

| Requirement | Boundary / check | Assumptions | Evidence |
| --- | --- | --- | --- |
| F03 | Accepted-request FIFO loses only its uncommitted tail on kill; every output belongs to its committed head | Legal caller below | BMC + induction; write after kill and kill on done covers |
| F05 | Actual production assertions check result valid, rd and data stability under arbitrary result backpressure, including kill | No fairness or maximum hold | BMC + induction; kill while held cover |
| F07 | FIFO commit prefix equals physical committed entries; output requires committed head; commit resolved before kill | Commit names oldest accepted uncommitted item | BMC + induction; commit + kill cover |
| F08 | Accepted = written + killed + live modulo 2^32; FIFO equals physical live slots and committed population; capacities 4 and 1 | No WAW restriction, no result fairness | BMC + induction; full MUL, eight-cycle uncommitted P4 hold then commit/kill covers |
| F11 (unit part) | Physical uncommitted item exists for every legal FIFO commit | WB commit refers to an accepted uncommitted instruction | BMC + induction; commit + kill cover |

The only assumptions are an initial reset, commit only when the independent
accepted-request FIFO has an uncommitted entry, kill suppresses younger EX
request valid, and request rd is nonzero. Reset may recur with work in flight;
it begins a new accounting epoch. Input payloads and result ready are otherwise
arbitrary. There is no no-WAW assumption, no permanently-low-reset assumption,
no fairness assumption and no bounded external hold. The physical observation
ports are confined to subclasses instantiated only by this formal generator.
All production assertions remain in the emitted RTL. F11 scoreboard set/clear
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
assumptions are unchanged. Keep the full arithmetic run and its result separate;
this control proof makes no arithmetic equivalence claim (see U01).
