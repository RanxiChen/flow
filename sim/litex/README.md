# BreezeCore LiteX Simulation

This directory owns the concrete LiteX simulation SoC. The reusable CPU API
adapter remains in `litex_wrapper/flow/core.py`.

## Four-hart Linux simulation

The Linux path is separate from the legacy MCU runner. It uses the `small`
four-hart cluster, the `linux` privilege profile, a 256 MiB LiteDRAM/DDR3
model, CLINT, PLIC, and LiteX's native LiteUART CSR page at `0x12001000`.

Build the reset ROM, DTB, and the S-mode handoff probe first:

```bash
make -C software/breeze-linux
```

The simulator loads three host files into modeled DDR:

```text
OpenSBI  0x80000000
DTB      0x80100000
payload  0x80200000
```

Run the handoff probe before a full Linux image:

```bash
BUILDROOT_OUT=build/buildroot-flow

python3 sim/litex/linux_sim.py \
    --opensbi "$BUILDROOT_OUT/images/fw_jump.bin" \
    --kernel software/breeze-linux/build/handoff-smoke.bin \
    --dtb "$BUILDROOT_OUT/images/flow-small.dtb" \
    --bootrom software/breeze-linux/build/bootrom.bin \
    --output-dir build/linux-handoff \
    --elaborate --build --non-interactive \
    --debug-cycles 50000000 \
    --opt-level O3 --jobs "$(nproc)"
```

`debug` RTL exposes per-hart retirement. The monitor prints the initial
retirements, a progress snapshot every one million cycles, UART MMIO writes,
fatal events, and a final per-hart summary. OpenSBI handoff is proven only when
the payload writes `K`; reaching the timeout without a fatal is progress
evidence, not a PASS.

Do not use `bootrom.bin` as `--kernel`. It contains reset code that jumps to
OpenSBI, so placing it at the payload address causes OpenSBI re-entry instead
of an S-mode handoff.

Before spending time on an OpenSBI run, validate LiteUART in bounded stages.
On the target machine, start with the complete Chisel regression:

```bash
cd design
sbt test
cd ..
```

First run the native FIFO/status/raw-event unit test and the cross-file address,
DTS and Buildroot contract checks:

```bash
python3 -m unittest \
    sim/litex/test_liteuart.py \
    sim/litex/test_liteuart_contract.py \
    sim/litex/test_migen_width_contract.py -v
```

The width-contract test converts a representative module to Verilog and bans
Signal left shifts in project Migen code. Interrupt vectors and Wishbone byte
addresses must use explicit-width `Cat` packing.

The first CPU stage boots one Linux-profile hart directly from the Linux reset
ROM. It uses the same byte accesses as OpenSBI (`TXFULL`, `RXEMPTY`,
`EV_ENABLE`, `RXTX`) and reports PASS through the completion monitor:

```bash
SBT=sbt python3 sim/litex/run_linux_liteuart.py --test csr --elaborate
```

Its marker is `[MULTICORE-SINGLE-LITEUART-CSR-PASS]`. Then validate the
interrupt route, including PLIC source 10 priority/enable/threshold,
claim and complete:

```bash
SBT=sbt python3 sim/litex/run_linux_liteuart.py --test plic
```

Its marker is `[MULTICORE-SINGLE-LITEUART-PLIC-PASS]`. A timeout or missing
marker is a failure. This test also enables the passive `[IRQ-CHAIN]` observer.
It records the native UART event enable/status/pending bits, UART IRQ, PLIC
source 10, PLIC pending/claim/MEIP, the CPU MEIP input, and the last retired
instruction. Thus a timeout can be assigned to the UART event, PLIC, or CPU
interrupt boundary without changing or acknowledging the device. The record
is also retained in `memory-trace.log`.

The standalone `test_plic_interrupt_path.py` test drives source 10 through
priority, enable, pending, MEIP, claim, and completion without involving the
CPU. Only after both CPU stages pass should the four-hart
OpenSBI handoff probe run. The Linux boot monitor prints at most the first 32
acknowledged accesses in the LiteUART CSR page as `[LINUX-UART-READ]` or
`[LINUX-UART-MMIO]`.

### Reusable memory-path monitor

Debug cluster RTL exports the existing Tandem retirement records plus a
passive DCache route record. `sim/litex/memory_monitor.py` combines those
records with passive observers on the CPU memory/MMIO Wishbone masters. The
native LiteUART is behind LiteX's Wishbone-to-CSR bridge, so the CPU MMIO
observer is the reusable source of truth. No observer drives ready, valid,
acknowledge, or data.

The stable marker families are:

```text
[MEM-RETIRE]  final architectural load/store result from Tandem
[DCACHE-REQ]  PMA attributes, hit result, byte mask and request metadata
[DCACHE-RSP]  blocking L1D response data/error
[WB-REQ]      actual byte address, Wishbone word address, select and write data
[WB-RSP]      acknowledge/error, raw read data and latency
```

The LiteUART runner enables all three layers, restricts them to the relevant
LiteUART or PLIC window, and writes the extracted records to
`<output-dir>/memory-trace.log`. General multicore and Linux simulations can
enable the same observer with:

```text
--mem-trace
--mem-trace-max-events 1024
--mem-trace-address-start 0x12001000
--mem-trace-address-end 0x12002000
```

The address end is exclusive and limits apply independently to each observer.
Production cluster generation drives the debug records to zero and firtool
eliminates the disconnected DCache trace logic.

After the handoff probe passes, replace `--kernel` with the Buildroot `Image`
or Alpine `Image-alpine`. These runs are intentionally diskless: initramfs is
embedded in the kernel image, and no VirtIO device is required.

See `docs/linux/hardware-platform.md`, `docs/linux/buildroot-alpine.md`, and
`docs/linux/verification-status.md` for the platform contract and current
evidence boundary.

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
