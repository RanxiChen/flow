# Breeze PCIe/FASE: board-verified state, 2026-09-22

The PCIe endpoint works end to end on hardware: enumeration, the FASE control
path and DMA all pass on a KCU105 in a PCIe host. A bare-metal program was
delivered over PCIe and executed on the core.

Read `pcie.md` first for the hardware contract and the user BAR ABI. This file
records what has been verified on a board, with which bitstream, and how to
reproduce it. **No rebuild is needed to use this** — the bitstream below is
already synthesized.

## Bitstream

```
alan:/home/chen/FUN/flow/build/fpga/kcu105-pcie-fase-100mhz-r3/gateware/xilinx_kcu105.bit
```

| | |
| --- | --- |
| SHA-256 | `1918e74fdffc973cc6130260b7c16f112350ed120909ea59c8aaecf771d89f1d` |
| Source commit | `bd10119` (`fix(pcie): synchronize reset sources before clock-domain fan-in`) |
| Profile | single hart, RV64GC, 100 MHz, 2 GiB DDR4, SD, FASE, flight recorder, no ILA |
| Post-route timing | WNS 0.038 ns, WHS 0.029 ns, 0 failing endpoints |
| Routing | 164288 nets fully routed, 0 errors |
| Exit codes | prepare 0, vivado 0, tests 0 |

`277545e`, the branch head, only changes a Buildroot profile, so r3 remains the
current gateware. The previous build `kcu105-pcie-fase-100mhz-r2` (`5a3e535`,
SHA `20792432…`) predates the reset-synchronization fix; prefer r3.

The PCIe-free bitstream published as release `breeze-kcu105-20260918`
(SHA `3e3d0329…`, commit `29ee514`) is byte-identical to
`kcu105-sd-fase-100mhz-29ee514` and is the one to use when the board must boot
without a PCIe host.

## Programming order matters

This profile **cannot boot without a live PCIe reference clock and a released
PERST#**. `FlowPcieReset.sv` folds XDMA's `axi_aresetn` into the MMCM reset
request, so with the host powered off the system clock never starts: the CPU,
BIOS and UART produce nothing. That silence is expected, not a failure.

1. Power the board from its own 12 V supply, not from the slot.
2. With the host **powered off**, program the bitstream over JTAG from Alan.
   Configuration lives in SRAM, so it survives as long as the board stays
   powered.
3. Power on the host. Reference clock and PERST# arrive, the MMCM leaves reset,
   and UART starts emitting BIOS output.

Programming after the host has booted does not work: the BIOS enumerates PCIe
at power-on, when the FPGA would still be unconfigured. Flash programming, so
the FPGA configures itself before enumeration, is a separate operation these
scripts do not perform.

## Host setup

Verified on Ubuntu 24.04, kernel 6.14.0-36-generic, gcc 13.3.0.

The in-tree `xdma` module (`CONFIG_XILINX_XDMA=m`, from
`linux-modules-extra`) is a **platform** driver — `modinfo` shows only
`alias: platform:xdma`, no PCI IDs — so it never binds to this endpoint. Use
Xilinx's out-of-tree driver:

```sh
git clone --depth 1 https://github.com/Xilinx/dma_ip_drivers.git
cd dma_ip_drivers/XDMA/linux-kernel/xdma
make && sudo insmod xdma.ko
```

It compiled unmodified on 6.14. Success looks like:

```
lspci -nn | grep -i 10ee      ->  02:00.0 ... [10ee:9038]
ls /dev/xdma0_*               ->  user, control, h2c_0, c2h_0, events_0..15, xvc
lspci -s 02:00.0 -vv          ->  Control: ... BusMaster+     (was BusMaster- before)
```

`BusMaster-` before loading the driver is the concrete reason a driver is
needed at all: MMIO through sysfs works without one, but the device cannot
master the bus, so DMA is impossible.

Driver probe reports `identify_bars: 2 BARs: config 1, user 0`, which matches
what the magic value shows independently:

| BAR | Role | Size |
| --- | --- | --- |
| 0 | **user** BAR — the FASE ABI, offset 0 reads `0x46415345` | 64 KiB |
| 1 | XDMA's own register BAR | 64 KiB |

Do not use `/dev/xdma0_xvc`: the driver places it at BAR 0 offset `0x40000`,
outside this design's 64 KiB user BAR.

### Reading the user BAR without the driver

Register access needs no driver at all — the BAR is mapped into the physical
address space by the firmware, and sysfs exposes it:

```sh
sudo python3 -c '
import mmap,os
f=os.open("/sys/bus/pci/devices/0000:02:00.0/resource0",os.O_RDONLY|os.O_SYNC)
m=mmap.mmap(f,4096,mmap.MAP_SHARED,mmap.PROT_READ)
print(hex(int.from_bytes(m[0:4],"little")))   # 0x46415345
'
```

This is the quickest check that a freshly programmed board is alive.

## Scripts

All three live in `fpga/kcu105/` and must sit in the same directory, since the
latter two import the first. Run them as root on the PCIe host.

| Script | Effect |
| --- | --- |
| `fase_pcie.py` | **Read-only.** Client library plus a verification pass. Safe with an OS running. |
| `fase_pcie_halt.py` | **Destructive.** Needs `--yes-halt-the-cpu`. Verifies the state-changing commands. |
| `fase_pcie_launch.py` | **Destructive.** Needs `--yes-overwrite-ddr`. DMAs a program into DDR and launches it. `--dry-run` inspects the program without touching hardware. |

`FasePcie` in `fase_pcie.py` is the intended base for further work. Its
`command()` implements one FASE transaction and carries over the rules
`fase_jtag.tcl` learned the hard way on JTAG:

- never resubmit while `busy`; that corrupts state
- compare the completed sequence number against the submitted one before
  trusting a result, so a stale response is never mistaken for this one
- a timeout means the operation **may already have executed** — do not retry
  blindly
- SUBMIT (`0x24`) and ACK (`0x38`) require all four byte enables, which a full
  32-bit store satisfies
- command fields cannot change while a completion is unacknowledged, so
  `command()` acknowledges a stale one first (this matters after a Ctrl-C)

## STATUS bits

`FaseController.scala` packs STATUS as follows. `fase_jtag.tcl`'s advice to
poll "bits 2:1 == 3" after HALT means waiting for `halted|empty`.

| Bit | Meaning |
| --- | --- |
| 0 | `owned` — FASE has taken the core |
| 1 | `halted` |
| 2 | `empty` — pipeline drained (and no `fence.i` pending) |
| 3 | busy — a command is in flight |
| 4 | `finished` — the last EXEC completed |
| 5 | `faulted` — the last EXEC trapped |
| 6 | sending |
| 7 | snapshot valid |

A command is only accepted when `available = owned && halted && empty && idle`.
`ReadReg`/`WriteReg`/`Exec`/`Launch` all return an error otherwise — which is
why they fail while an OS is running, and is not a transport fault.

## Measured behaviour

### Link and latency

Negotiated Gen3 x8 at full width (`LnkSta: Speed 8GT/s, Width x8`,
`EqualizationComplete+`).

| Command | Median round trip |
| --- | --- |
| STATUS | 2.5 us |
| ReadReg | 2.5 us |
| NextPc | 1.7 us |

About 400 000 commands/second, roughly three orders of magnitude faster than
JTAG. **This is why the hardware ABI needs no batching.** A workflow that
injects tens of instructions costs well under a millisecond, so command
sequencing belongs in host software, matching the original FASE's own split
(its controller, `src/sysv2/sysv2.cpp`, runs on the host).

Bulk data must still go through DMA, not through FASE one word at a time. That
is the real advantage of PCIe over the original UART transport.

### Verified on the board

`fase_pcie_halt.py`, 23 checks, all passing:

- HALT reaches `owned|halted|empty`
- all 32 registers read back; `x0` is zero; 23 of 31 held live kernel state.
  The values were self-evidently real: `x1`/`x2`/`x8` in kernel space with a
  consistent stack frame, and `x17 = 0x54494D45` — ASCII `"TIME"`, the SBI
  Timer extension ID, matching a core idling in WFI after arming a timer.
- WriteReg/ReadReg round-trip three bit patterns
- **injected instructions execute and compose**: `addi x31,x0,42` gives 42,
  then `addi x30,x31,1` gives 43 — reading the previous instruction's result —
  and `addi x29,x0,-1` sign-extends to `0xffffffffffffffff`
- an illegal instruction sets `faulted` with `cause = 2`; a legal instruction
  afterwards still executes, and the flag clears
- snapshot, all 48 entries
- out-of-range register/snapshot indices, unknown opcodes and an odd-PC Launch
  are all rejected

`fase_pcie_launch.py`, all four checks passing: a 10-instruction program summing
1..100 was DMAed to `0x81000000`, launched, and left `x21 = 5050`,
`x23 = 0x5a5`, with `x24` reaching 1 390 925 spins in 0.25 s (about 5.6 M loop
iterations/second, ~11 M instructions/second).

`5050` is worth dwelling on: it requires 100 correct iterations of a loop with
a branch, and it also proves `fence.i` worked. The core had been running Linux,
so the instruction cache was full of kernel lines; stale lines at `0x81000000`
would have executed garbage.

## Two things that are easy to get wrong

### Clear `satp` before launching

Linux leaves `satp` pointing at its page tables and a timer armed. Launching to
a physical address then faults on instruction fetch, or a timer interrupt
diverts into a kernel handler whose page tables are no longer trustworthy.
Inject, in this order:

```
csrw  satp, x0        0x18001073
csrw  sie,  x0        0x10401073
csrci sstatus, 2      0x10017073
csrw  mie,  x0        0x30401073   (faults from S-mode; harmless)
csrci mstatus, 8      0x30047073   (likewise)
```

The privilege mode after HALT is not known in advance, so the machine-level
writes are attempted and allowed to fault.

### `fence.i` must be injected before launching DMAed code

`pcie.md` states it plainly: DMA completion does not perform `fence.i`. Data
writes reach L2 and D-cache coherence is reused, but the **I-cache is not a
directory sharer**.

Injecting `fence.i` (`0x0000100f`) is enough, and it works even though the
front end is paused. `BreezeCore.scala:176` sets `fasePaused` while FASE owns
the core, and `BreezeFrontend.scala:107` uses it to gate fetch — but the
invalidate path does not pass through that gate:

```scala
icache.io.flush := io.beRedirect.cacheFlush     // BreezeFrontend.scala:374
```

The backend drives that from `fence.i` retirement (`BreezeBackend.scala:1655`),
and only after the D-cache flush completes (`:1157-1158`) — exactly the ordering
`fence.i` requires. Injected instructions reach the same backend pipeline
through the FASE fetch buffer (`BreezeCore.scala:215`), so they decode and
retire normally.

Two details keep this from hanging: `f.empty` stays low while a `fence.i` is
pending (`BreezeBackend.scala:1706`), so STATUS does not report completion
early, and `fence.i` only enters writeback once `fenceiFlush` asserts
(`:1434`), so retirement does fire. Allow a generous timeout — it waits for a
cache flush — and never launch if it faulted.

## Open items

### An unexplained extra 16-byte read

A DMA read of a **full 4 KiB page** reports 514 read words where 512 are
expected — one extra 16-byte access, 100% reproducible. Smaller transfers, and
the same length from an unaligned start, are exact. Data is byte-identical every
time and `errors` stays 0, so the access lands inside the legal window: a
redundant read, not a correctness defect, costing 0.4% of one page.

Attribution is **not settled**. Simulation now asserts that this bridge, given
one 128-beat AR, produces exactly 512 Wishbone words, 128 R beats and 1 AR — so
the simplest explanation (the bridge duplicating beats) is excluded. But that
covers one timing scenario only, with a fixed 3-cycle Wishbone latency and no
backpressure variation, so a timing-dependent bug in the bridge is not ruled
out either.

Four AXI-side counters were added to settle it on hardware. They are **in the
source but not in the r3 bitstream**; reading them requires a rebuild.

| Offset | Counter |
| --- | --- |
| `0x4c` | `ar_requests` — AR handshakes |
| `0x50` | `ar_beats` — sum of `arlen+1` |
| `0x54` | `ar_narrow` — requests with `arsize != 5` |
| `0x58` | `r_beats` — R beats returned |

The test is one reading: for a 4 KiB transfer, `ar_beats == 128` alongside
`read_words == 514` means this bridge over-reads; `ar_beats == 130` or
`ar_requests == 2` means XDMA asked for it.

### Not yet done

- **ELF loading.** `fase_pcie_launch.py` hand-encodes its program; it does not
  parse ELF. A real loader must walk `PT_LOAD` segments, honour
  `vaddr`/`filesz`/`memsz`, zero `.bss`, set up a stack and enter at `e_entry`.
  The transport underneath it is now proven, so failures there will be parsing
  bugs rather than plumbing.
- **Program output.** Nothing is wired up yet. Three options: write the LiteX
  UART MMIO registers directly; agree on a DDR result area and read it back by
  DMA; or implement the original FASE's approach — the program executes
  `ecall`, the core faults, and the host reads `a0`-`a7`, services the call,
  writes `a0` back and resumes. The third is what lets a program run "without a
  target-side Linux image", and is the natural next step for the software side.
- **`tval` is unproven.** The fault test injected `0x00000000`, so `tval = 0`
  cannot be distinguished from "always zero". Use a non-zero illegal encoding.
- **PCIe/SoC reset coupling.** See the programming order above. Decoupling
  would let Breeze survive a host reboot, but it needs a real drain handshake
  rather than resetting L2 alongside: `FlowPcieMemory.sv:59` drops `wb_cyc`
  combinationally on reset, which would abandon an accepted L2 transaction.
  `FaseArbiter` also needs attention — it only tracks `cpu.reset`, so losing
  the PCIe side mid-transaction could wedge JTAG FASE permanently.
- **Hot reset is not board-validated**, per `pcie.md`. Stop DMA before any
  board or software reset.
- **JTAG and PCIe must not debug concurrently.** Arbitration is per
  transaction, not per session, so it gives no whole-session ownership.

## Restoring the system

HALT drains the pipeline without retaining state, and `fase_pcie_launch.py`
overwrites DDR, so the Linux image does not survive either script. To get back
to a booting system, program the bitstream again with the host powered off and
power the host on — the sequence at the top of this file.
