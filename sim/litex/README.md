# BreezeCore LiteX Simulation

This directory owns the concrete LiteX simulation SoC. The reusable CPU API
adapter remains in `litex_wrapper/flow/core.py`.

Generate the baseline core RTL and the standalone ROM image first:

```bash
cd design && sbt elaborate
cd ..
make -C software/breeze-smoke
```

Elaboration emits split SystemVerilog under `design/build/rtl/baseline`.
`filelist.f` is the authoritative source manifest; the LiteX CPU wrapper
registers every file in that list, including `BreezeCoreWishbone.sv` as the
top-level module. GShare is opt-in and uses an independent RTL directory:

```bash
cd design
sbt "runMain flow.top.GenerateBreezeCoreWishbone gshare"
```

Each directory contains `core-preset.txt`. The wrapper rejects a missing or
mismatched marker so a previous GShare build cannot silently replace the
default baseline core.

Then, in the LiteX Python environment, generate the simulation build tree:

```bash
python sim/litex/breeze_sim.py \
    --rom-init software/breeze-smoke/build/breeze-smoke.bin
```

Pass `--build` to compile and run the Verilator simulation. Without
`--rom-init`, the ROM is zero-filled and is not a useful boot test. The smoke
firmware should print:

```text
BreezeCore ROM boot
main RAM cached R/W: PASS
```

The simulated UART is connected to machine-external interrupt source 0 and is
used in polling mode by this first image. LiteX's CSR timer remains disabled;
the project-owned architectural machine timer provides:

```text
mtimecmp  0x02004000  64-bit RW
mtime     0x0200bff8  64-bit RW
timebase  1 MHz
```

Its level-sensitive `mtip` output is connected directly to the CPU.

## Reusable MCU application runner

`run_mcu.py` builds the common startup/trap/UART runtime with either a supplied
`main.c` or one of the directed interrupt applications and runs a finite LiteX
simulation. The runner reads the completion-mailbox address from the firmware
symbol table; the monitor terminates only after a complete 64-bit PASS/FAIL
store retires. The runtime also snapshots the architectural M-mode PMU into
linker-reserved SRAM. The monitor captures those retired stores and
`run_mcu.py` computes IPC from `minstret/mcycle`:

```bash
python sim/litex/run_mcu.py --main software/breeze-mcu/apps/main.c --elaborate
python sim/litex/run_mcu.py --smoke timer --mtvec-mode direct
python sim/litex/run_mcu.py --smoke uart --mtvec-mode direct
python sim/litex/run_mcu.py --smoke timer --mtvec-mode vectored
```

All commands above default to `--core-preset baseline`. Enable GShare only when
explicitly requested; use `--elaborate` the first time or after RTL changes:

```bash
python sim/litex/run_mcu.py --main software/breeze-mcu/apps/main.c \
    --core-preset gshare --elaborate
```

The default LiteX/Verilator output directory includes the selected preset, so
baseline and GShare builds do not share generated simulation products. The
simulator prints `BREEZE_CONFIG core_preset=<preset>` and the runner requires
that marker before accepting a result.

Run the same firmware through both presets as a correctness regression:

```bash
python3 sim/litex/run_gshare_regression.py --elaborate
```

The regression requires both completion markers, checks that both runs used the
same firmware SHA256, and requires equal retired-instruction counts for the
deterministic generic application. It reports cycles and IPC but does not use a
performance threshold as a correctness gate. Timer/UART smoke applications can
also be selected with `--smoke timer|uart` and `--mtvec-mode direct|vectored`.

The performance output is independent of UART text and is not used to decide
PASS/FAIL:

```text
BREEZE_PERF cycles=<cycles> instructions=<instructions>
BREEZE_PMU cycles=<cycles> instructions=<instructions> control=<count> taken=<count> pred_miss=<count> icache_miss=<count> dcache_access=<count> dcache_miss=<count> uncached=<count> mem_stall=<cycles>
BREEZE_IPC cycles=<cycles> instructions=<instructions> ipc=<ipc>
BREEZE_METRICS prediction_miss_rate=<ratio> icache_mpki=<value> dcache_miss_rate=<ratio> memory_stall_ratio=<ratio>
```

`BREEZE_PERF` remains a simulation-window cross-check. `BREEZE_PMU` is the
architectural snapshot and is the source used for `BREEZE_IPC`. It excludes
startup and section initialization but includes fixed call/return and stop
glue, so comparisons must use the same firmware and SoC configuration.

Interrupt checks require the raw interrupt source to assert, the expected
Direct/Vectored table slot to retire, the source to be deasserted before
`mret`, and the post-return firmware PASS signature to retire. A watchdog makes
all modes finite.

The simulation uses LiteX's standard `CRG`, including its power-on reset pulse.
This reset is required for the Chisel core to load the ROM reset vector before
the first instruction fetch.

## First instruction refill diagnostic

The optional fetch monitor checks the instruction Wishbone path without adding
ports to the core RTL:

```bash
python sim/litex/breeze_sim.py \
    --rom-init software/breeze-smoke/build/breeze-smoke.bin \
    --debug-fetch \
    --stop-after-first-fetch \
    --build
```

It requires the first request to use word address `0x02000000` (byte address
`0x10000000`), compares the first returned 64-bit word with the actual ROM
image, prints all four beats of the first 32-byte refill, and fails after 100
cycles by default. Use `--fetch-timeout N` to change the watchdog. Omit
`--stop-after-first-fetch` to let the smoke firmware continue to UART after a
successful refill.

## Retirement and smoke-memory diagnostics

The canonical `BreezeCoreWishbone` RTL exposes the existing architectural
`TracePayload` retirement interface. Regenerate RTL after updating this source.
To validate only the first retired instruction:

```bash
cd design && sbt elaborate
cd ..
python sim/litex/breeze_sim.py \
    --rom-init software/breeze-smoke/build/breeze-smoke.bin \
    --debug-retire \
    --stop-after-first-retire \
    --build
```

The expected first retirement is the instruction at the ROM reset vector. With
the smoke image, the monitor also checks that its first `AUIPC` writes stack
pointer `x2 = 0x11040000` when the memory check is enabled:

```bash
python sim/litex/breeze_sim.py \
    --rom-init software/breeze-smoke/build/breeze-smoke.bin \
    --debug-retire \
    --check-smoke-memory \
    --build
```

This second mode waits for the `SD` and `LD` retiring at main-RAM address
`0x80000000`, checks data `0x0123456789abcdef` and store mask `0xff`, and then
finishes. The first-retirement and memory watchdogs default to 200 and 10000
cycles respectively. It also prints the first 64 retired instructions and every
retired memory instruction. `--retire-log-limit N` changes the initial trace
length. If no instruction retires for 500 cycles, `--retire-stall-timeout`
prints the last retired instruction and memory access before terminating.
