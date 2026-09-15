# FASE flight recorder v1

Built with the existing `--with-fase` option; absent when FASE is disabled.
Reset leaves recording disarmed. No CPU stalls, trap overrides, or automatic HALT
are introduced. All events are sampled in the CPU/sys clock domain, then written
into six independent synchronous circular memories. Each bank holds 1024 events,
each 8 x 64 bits (384 KiB logical total). Vivado must infer block RAM and meet the
10 ns clock constraint; source elaboration alone does not establish either fact.

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
transaction trace. Existing ILA D-cache probes and FASE snapshots remain available.

Each bank independently overwrites its oldest entry. Frequent instruction events
cannot evict trap history. Finite capacity means the most recent 1024 traps,
not every trap since boot. Counts saturate at 1024; totals wrap at 2^32 events.
Reading is frozen-only. Manual freeze retains events already sampled before the
freeze command. Arm clears history counters and previous trigger state. RAM itself
is not reset. Reset/reprogramming loses all records.

## FASE commands

Existing CPU opcodes 0..10 are unchanged. The router holds one outstanding command.
The recorder works while the CPU runs, drains, is halted, or is stuck.

| Opcode | Meaning |
| --- | --- |
| 16 | STATUS: index 0 = depth[31:16], banks[15:8], triggered[2], frozen[1], active[0]; indices 1..6 = total[63:32], count[31:16], next-write-slot[15:0]; 7 = trigger source cycle; 8..13 = next-write-slot for each bank at trigger |
| 17 | CONTROL data: bit 0 arm and clear, 1 freeze, 2 clear while frozen; at most one of these bits; 3 enable IF response page-fault trigger, 4 enable IF request address trigger, 11:8 allowed privilege mask (U=bit0, S=bit1, M=bit3), 31:16 post-trigger cycles. Every accepted CONTROL updates trigger settings. |
| 18 | Set 64-bit match address |
| 19 | READ WORD: data bank[15:13], slot[12:3], word[2:0]. Invalid bank/slot, unwritten slot, or active recording returns error. |
| 20 | Set 64-bit address mask; comparison is `(requestVA & mask) == (address & mask)` |

Fault/address matches are ORed and privilege-filtered. The triggering event is
written, then `post_cycles` additional sys cycles are recorded before freezing.
Zero freezes immediately after writing the trigger event. Trigger slots are the
next write positions **before** that cycle's writes; a bank without an event that
cycle has no trigger record. Timestamp comparison is authoritative. A large post
window can overwrite pre-trigger history; 32 cycles is the initial default.
A normal demand-page fault can trigger recording; enable the fault trigger only
with the intended privilege/filter. The default helper uses address matching.

## Capture procedure

1. Program the new bitstream and matching LTX as a separate, explicit board step.
2. In normal Hardware Manager mode, source `flight_ila.tcl`, then call
   `flight_ila_arm [lindex [get_hw_ilas] 0]`. It clears previous trigger comparisons
   and triggers on recorder hit, with 3072 pre-trigger and 1024 remaining samples
   in the 4096-depth ILA. All cycles are captured. Verify ILA reports waiting.
3. Switch to USER2 target mode (`open_hw_target -jtag_mode on`, TCK <= 10 MHz),
   source `fase_jtag.tcl`, select the measured chain, then source
   `flight_recorder.tcl`. Arm before launching Linux, for example:

   ```tcl
   flight_arm 0xffffffff88ba597e 0xffffffffffffffff 0 1 15 32
   flight_cmd 16
   ```

   This example targets the previously observed VA; it does not assume the next
   crash must use that address. For S-mode IF page faults use
   `flight_arm 0 0xffffffffffffffff 1 0 2 32`.
4. After failure, STATUS low bits 6 mean triggered and frozen. Export with
   `flight_dump /absolute/fresh-capture.csv`. If no trigger fired, explicitly
   `flight_freeze` first; preserve history before issuing FASE HALT/injections.
5. Return to normal Hardware Manager mode and upload the ILA data. Switching JTAG
   ownership does not stop sys-clock acquisition; do not reprogram/reset while
   switching. CPU HALT does not implicitly freeze the recorder.

ILA `flight_flags` bits: clear=0, active=1, frozen=2, triggered=3, hit=4,
event-valid banks 0..5=bits 5..10. `flight_cycle` and sampled event payload probes
are aligned to each other; existing unrelated ILA probes describe their current
cycle, one cycle later than the source events. ILA's common two input pipeline
stages affect all probes equally. These helpers still require board verification
with the new bitstream; simulation is not a hardware capture.
