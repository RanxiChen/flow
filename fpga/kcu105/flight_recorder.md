# FASE flight recorder v2

Enabled by `--with-fase`. Recording starts automatically after every reset,
in M/S/U mode, without ARM, ILA, CPU HALT or a configured fault address.
Every architectural instruction page fault (trap taken, not an interrupt,
cause 12) creates a snapshot. Speculative IF MMU failures alone do not trigger.
Normal demand instruction-page faults also trigger; this is not a panic detector.

Each snapshot retains up to 256 preceding events **per bank**, events on the
fault cycle, and events during the next 64 CPU cycles. In particular bank 3
retains the preceding 256 retired instructions, not merely 256 clock cycles.
Startup histories may be shorter. Timestamps align the six independent streams.
Eight snapshots are retained; subsequent faults recycle older slots. A selected
snapshot is leased against eviction while JTAG reads it; then the other seven
slots continue recording newer faults. Release after export. A fault storm can
recycle a snapshot before its post window completes; only ready snapshots can
be selected. This finite history cannot guarantee the original cause survives
arbitrarily many later faults.

Six 4096 x 512-bit BRAM banks provide 1.5 MiB logical storage. Each bank uses
64-entry pages, shared by overlapping snapshots and protected from overwrite.
The allocator reserves enough pages even for eight disjoint maximal snapshots.
No trace operation stalls the CPU. Loss counters expose allocation failure.
Reset/reprogramming/CLEAR destroys retained snapshots; RAM contents need not be
reset. There is no global freeze. Do not reset after a crash before exporting.

## Records

Word 0 is the source-cycle timestamp (64-bit cycles since reset). Word 1 contains
flags; bits 63:62 contain **actual privilege in the following cycle**, useful for
checking trap/xret transitions. The remaining words are as follows. Fields are
meaningful only for the event(s) indicated by the flags, not merely because a
wire had a value. Events in different banks with the same timestamp are concurrent.

| Bank | Event | Word 1 flags | Words 2 through 7 |
| --- | --- | --- | --- |
| 0 | trap taken or xret redirect | bit 0 trap, 1 interrupt, 2 raw sret_commit, 3 raw mret_commit; 17:16 pre-event privilege | source EPC/return instruction PC, selected trap/xret target, trap cause, trap tval, pre-event mstatus, satp |
| 1 | backend redirect | bits 0..7: fence.i, sfence, satp commit, xret, interrupt, exception, WFI, branch correction; 17:16 privilege | WB PC, actual output target, EX PC, EX next PC, xret target, trap target |
| 2 | committed CSR write | 11:0 CSR address; 17:16 privilege | WB PC, requested write value, old mstatus, old satp, old sepc, old mepc |
| 3 | actual instruction retirement | bit 0 integer write enable, 5:1 rd, 17:16 privilege | PC, instruction, stored WB nextPc, integer WB data, mstatus, satp |
| 4 | frontend PC register update (except reset) | bit 0 backend/FASE redirect, 1 fast prediction redirect, 2 sequential/prediction advance, 3 fetch access fault, 4 fetch page fault | old PC, adopted PC, selected redirect target, backend/FASE target, fast predictor target, fetch response PC |
| 5 | IF MMU request/response handshake or kill | bit 0 request fire, 1 response fire, 2 response page fault, 3 response access fault, 4 kill; 9:8 request privilege (saved for a response) | request VA, response VA, response PA, saved request satp, current satp, MMU diagnostic word 0 |

Trap cause/tval in bank 0 are meaningful for traps, not returns. Bank 3 `nextPc`
is the existing WB field, not a claim that no later trap/redirect intervened.
Bank 5 fault bits require response-fire. Saved request satp describes the previous
request until the next clock edge; use current satp for a request-only event.
The MMU permits only one transaction, so request context is retained until its
response; kills are explicitly recorded. This is not a complete PTW/PTE or D-cache
transaction trace. FASE register/memory inspection remains available separately.

## JTAG readout

Use the matching v2 helpers; v1 ARM/FREEZE commands have different meanings.
The helpers check the protocol version before changing recorder state.
Existing CPU commands 0..10 are unchanged. The debug path bypasses CPU loads
and works with a running, halted or stuck CPU, provided sys clock/JTAG work.

On Alan, with hw_server available on localhost:3121:

```sh
vivado -mode batch -source fpga/kcu105/flight_read.tcl -tclargs list
vivado -mode batch -source fpga/kcu105/flight_read.tcl -tclargs dump latest /absolute/fresh-fault.csv
vivado -mode batch -source fpga/kcu105/flight_read.tcl -tclargs dump 3 /absolute/fresh-fault-3.csv
vivado -mode batch -source fpga/kcu105/flight_read.tcl -tclargs capture /absolute/fresh-manual.csv
```

These commands select the KCU105 Digilent serial 210308A7B107, USER2, 10 MHz.
They do not program/reset/HALT the FPGA. Close other JTAG clients first.
`list` reports snapshot IDs, readiness, privilege, PC, cause and eviction/loss
counters. `dump` leases a completed ID, writes chronological CSV, then releases
it even after an export error. Existing output files are never overwritten.
An ID evicted before SELECT causes an explicit error; retry listing rather
than treating a different snapshot as the requested one. A disconnected client
may leave a lease; source the helpers and use `flight_release` to release it.
`capture` creates and leases a manual snapshot of current execution, clearly
marked manual; it does not reconstruct an earlier unretained fault.

## Protocol

One outstanding command. STATUS opcode 16 index 0 returns version[63:32]=2,
depth[31:16]=4096, banks[15:8]=6, recording[0]=1.

| Opcode | Meaning |
| --- | --- |
| 16 | STATUS indices 1..6 bank event totals; 7 capture count; 8 evictions; 9 latest capture cycle; 10 leased ID or 0; 11 slots; 12 pre-event limit; 13 post cycles; 14 loss mask; 15 version; 16..21 per-bank dropped events |
| 17 | SLOT_INFO data=slot: index 0 flags valid/ready/leased/manual bits 0..3; 1 ID; 2..9 cycle, flags, PC, target, cause, tval, mstatus, satp; 10..15 per-bank total[31:16], pre-count[15:0] |
| 18 | SELECT data=completed snapshot ID; atomically leases it and returns its cycle; failed selection preserves the previous lease |
| 19 | READ_SELECTED data bank[15:13], chronological entry[12:3], word[2:0]; requires completed leased snapshot; invalid bank/entry returns error |
| 20 | RELEASE lease |
| 21 | CAPTURE_NOW creates and immediately leases a manual snapshot, returning ID; simultaneous real fault takes precedence in its metadata |
| 22 | Reserved, returns error |
| 23 | CLEAR metadata/history counters/lease; recording continues automatically |

IDs start at 1 after reset/CLEAR. Manual captures share the eight slots and ID
counter with faults. Counters are 64-bit. Listing unleased slots can race with
eviction; helpers check the ID before and after reading each descriptor.
Only one reader lease is supported; concurrent clients must serialize access.

## Build and acceptance

```sh
python fpga/kcu105/target.py --cpu-type breeze-tiny --with-fase --sys-clk-freq 100000000 --output-dir ABS_FRESH_BUILD
cd ABS_FRESH_BUILD/gateware
bash build_xilinx_kcu105.sh
```

Omit `--debug` to avoid instantiating the large ILA. Existing optional ILA
probes are not needed for snapshots. Run `flight_report.tcl` on the routed DCP
to check inferred recorder BRAM and 10 ns setup/hold, then review utilization
and CDC. Simulation success is not evidence of FPGA timing or board capture.
Programming and reset/readout board verification are separate steps.
