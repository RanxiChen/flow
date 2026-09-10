# Breeze on KCU105: DDR4 bring-up

This target intentionally stops before OpenSBI and Linux.  The first board
milestone is:

1. start the selected Breeze cluster at the integrated LiteX BIOS ROM;
2. let hart 0 run the BIOS using on-chip ROM and SRAM;
3. complete DDR4 training and the BIOS memory test;
4. upload a bare-metal demo to DDR4 over UART and execute it.

In the four-hart product, the other three harts are parked by the Breeze LiteX
startup code while the single-hart BIOS/demo path is exercised.

## Select the CPU product

| `--cpu-type` | RTL profile | Harts | L1I / L1D per hart | L2 | PLIC contexts |
| --- | --- | ---: | --- | ---: | ---: |
| `breeze` (default) | `small` | 4 | 8 / 8 KiB | 64 KiB | 8 (M/S per hart) |
| `breeze-tiny` | `single` | 1 | 8 / 8 KiB | 16 KiB | 2 (M/S for hart 0) |

Both use the same Linux-capable RV64GC core, Sv39 MMU, MESI L1D, L2/Home,
CLINT and PLIC. Tiny retains the cluster fabric with one coherence client;
the separate instruction-cache path also passes through L2/Home. It uses
the existing single-cluster preset rather than a standalone core. Address
maps, DDR geometry, clock frequency and the eight active PMP entries agree.
Tiny's CLINT has one MSIP/MTIMECMP pair and the shared MTIME counter.
The LiteX internal CPU registry key is `breeze_tiny` so generated C macros
remain valid; the command-line product name is `breeze-tiny`.

Generate matching production RTL before building the selected product:

```sh
cd /home/chen/FUN/flow/design
sbt 'runMain flow.top.GenerateBreezeMulticoreClusterWishbone single gshare linux production'
cd ..
python fpga/kcu105/target.py --cpu-type breeze-tiny --build
```

Use `small` and `--cpu-type breeze` for four harts. The default output paths
are respectively `build/fpga/kcu105-breeze-tiny-ddr` and
`build/fpga/kcu105-breeze-ddr`; `--output-dir PATH` selects an isolated run.
Omitting `--build` only generates the project and compiles the BIOS.
The bitstream is `gateware/xilinx_kcu105.bit` within the selected output path.
Run these commands in the LiteX environment with Vivado on `PATH`.

Tiny is intended to leave space for subsequent ILA debugging; it does not
itself establish that the DDR training issue is fixed. Its Linux device tree
and Buildroot configuration are described in
[`linux/buildroot-external/README.md`](../../linux/buildroot-external/README.md).

## Fixed hardware layout

| Region | Address | Implementation |
| --- | ---: | --- |
| CLINT | `0x02000000` | Breeze SystemVerilog CLINT, 1 MHz `mtime` |
| PLIC | `0x0c000000` | Breeze SystemVerilog PLIC |
| ROM | `0x10010000` | 64 KiB FPGA BRAM containing the LiteX BIOS |
| SRAM | `0x11000000` | 64 KiB FPGA BRAM for BIOS/demo data and stack |
| LiteX CSR | `0x12000000` | control, UART, timer and SDRAM controller/PHY CSRs |
| main RAM | `0x80000000` | 1 GiB DDR4 window (`0x80000000`–`0xbfffffff`) |

The system clock is fixed at 50 MHz (20 ns period); the board reference clock
remains 125 MHz. The board's LiteX `_CRG` supplies the DDR and IDELAY domains.
`USDDRPHY` and `EDY4016A` use the 1:4 ratio, corresponding to a 200 MHz DDR
clock / 400 MT/s data rate; IDELAY uses 200 MHz. `integrated_main_ram_size=0`
lets LiteDRAM own `main_ram`. The board geometry is 2 GiB, while this target
exposes a 1 GiB window. BIOS SDRAM training runs before memory testing.
Breeze retains its coherent shared L2, with no extra LiteX cache between the
cluster and DDR. The build uses Vivado's
area-oriented synthesis/implementation directives because the fixed four-hart
RV64GC cluster is close to the KCU105's KU040 LUT limit.

## Generate the SoC and BIOS

Use the LiteX environment on Alan:

```sh
cd /home/chen/FUN/flow
source /home/chen/miniforge3/etc/profile.d/conda.sh
conda activate flow
python fpga/kcu105/target.py
```

This compiles the ROM BIOS and emits the Vivado project under
`build/fpga/kcu105-breeze-ddr`, but does not launch Vivado. Run implementation
only when explicitly wanted:

```sh
python fpga/kcu105/target.py --build
```

## Build LiteX's standard bare-metal demo

The installed LiteX source provides the unmodified template at
`litex/soc/software/demo/` and exposes it as `litex_bare_metal_demo`.  Build it
against this SoC's generated headers and memory map:

```sh
cd /home/chen/FUN/flow/build/fpga/kcu105-breeze-ddr
litex_bare_metal_demo --build-path="$PWD"
```

The resulting `demo/demo.bin` is linked to `main_ram` at `0x80000000`.
Use the template command only to initialize a fresh demo directory: it copies
the template over existing source. To rebuild an already customized demo,
enter its directory and run:

```sh
make clean
make BUILD_DIR=/home/chen/FUN/flow/build/fpga/kcu105-breeze-ddr
```

Use the actual DDR run directory if a timestamped build was selected.
Existing C tests using `main_ram` generally need no address changes; data and
stack still use the 64 KiB SRAM with the standard demo linker script. The
small scratch-buffer tests do not cover all external memory.

## Load and run on the board

After Vivado has produced the bitstream and the board is connected:

```sh
cd /home/chen/FUN/flow
loadfpga /home/chen/FUN/flow/build/fpga/kcu105-breeze-ddr/gateware/xilinx_kcu105.bit
litex_term /dev/ttyUSB1 --speed 115200 \
  --kernel build/fpga/kcu105-breeze-ddr/demo/demo.bin --kernel-adr 0x80000000
```

`loadfpga` is the existing Alan board-loading command. Keep only one terminal
reading the FPGA UART. If BIOS is already at its prompt, enter `serialboot`.
Success evidence is kept separate by stage:

- the BIOS banner and CRC check establish initial CPU/ROM/UART execution;
- DDR training completion, RAM testing and execution of the uploaded demo
  are separate checks of the external main-memory path;
- `Executing booted program at 0x80000000` followed by the
  `litex-demo-app>` prompt proves serial loading and CPU execution from DDR;
- this milestone does not yet prove OpenSBI, S-mode, Sv39 or Linux.

## PMP resource configuration

Each hart's blocking MMU shares one PMP checker between page-table accesses
and the final physical-address check. All eight active entries are checked
in parallel; there is no added entry-scan state. The 16-entry CSR layout
remains visible, but entries 8–15 (including `pmpcfg2`) are read-zero,
write-ignored slots. Entries 0–7 retain TOR/NA4/NAPOT and lock semantics.

The integer and FP register files retain the distributed-RAM implementation
validated in `e7dd64d`, including reset-zero behavior and write bypass.

## Validated BRAM fallback

The four-hart 50 MHz / 256 KiB BRAM version at `e7dd64d` passed the user's
integer, memory, floating-point and multicore demo tests. Its bitstream and
reports are archived outside the repository on Alan:

```text
/home/chen/fpga-artifacts/flow/kcu105-bram-50mhz-e7dd64d/
```

DDR is restored here for further bring-up; the earlier DDR build at `b7b6005`
generated a timing-clean bitstream, but its board training did not have a
confirmed pass. Restoring that integration at 50 MHz does not by itself fix
or establish the cause of the earlier training issue. Validate DDR before
trying OpenSBI/Linux. The BRAM demo and archived bitstream are not overwritten.
