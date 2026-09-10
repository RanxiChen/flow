# Breeze on KCU105: first bare-metal milestone

This target intentionally stops before OpenSBI and Linux.  The first board
milestone is:

1. start the four-hart Breeze cluster at the integrated LiteX BIOS ROM;
2. let hart 0 run the BIOS using on-chip ROM and SRAM;
3. upload a small bare-metal demo to 256 KiB on-chip main RAM over UART;
4. execute the demo from on-chip RAM and interact with its serial console.

The other three harts are parked by the Breeze LiteX startup code while the
single-hart BIOS/demo path is exercised.

## Fixed hardware layout

| Region | Address | Implementation |
| --- | ---: | --- |
| CLINT | `0x02000000` | Breeze SystemVerilog CLINT, 1 MHz `mtime` |
| PLIC | `0x0c000000` | Breeze SystemVerilog PLIC |
| ROM | `0x10010000` | 64 KiB FPGA BRAM containing the LiteX BIOS |
| SRAM | `0x11000000` | 64 KiB FPGA BRAM for BIOS/demo data and stack |
| LiteX CSR | `0x12000000` | control, UART and BIOS timer CSRs |
| main RAM | `0x80000000` | 256 KiB FPGA BRAM (`0x80000000`–`0x8003ffff`) |

The system clock is fixed at 50 MHz (20 ns period); the board reference clock
remains 125 MHz. `integrated_main_ram_size` is 256 KiB. This temporary
bring-up target omits the DDR controller/PHY and DDR/IDELAY clock domains,
so the BIOS does not run SDRAM training. Breeze retains its coherent shared
L2, with no extra LiteX cache between the cluster and main RAM.  The build uses Vivado's
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
`build/fpga/kcu105-breeze-bram`, but does not launch Vivado.  Run implementation
only when explicitly wanted:

```sh
python fpga/kcu105/target.py --build
```

## Build LiteX's standard bare-metal demo

The installed LiteX source provides the unmodified template at
`litex/soc/software/demo/` and exposes it as `litex_bare_metal_demo`.  Build it
against this SoC's generated headers and memory map:

```sh
cd /home/chen/FUN/flow/build/fpga/kcu105-breeze-bram
litex_bare_metal_demo --build-path=.
```

The resulting `demo.bin` is linked to `main_ram` at `0x80000000`.

## Load and run on the board

After Vivado has produced the bitstream and the board is connected:

```sh
cd /home/chen/FUN/flow
python fpga/kcu105/target.py --load
litex_term /dev/ttyUSBX --kernel=build/fpga/kcu105-breeze-bram/demo.bin
```

Replace `/dev/ttyUSBX` with the KCU105 UART device.  Success evidence is kept
separate by stage:

- the BIOS banner and CRC check establish initial CPU/ROM/UART execution;
- RAM testing and execution of the uploaded demo are separate checks of the
  BRAM main-memory path;
- `Executing booted program at 0x80000000` followed by the
  `litex-demo-app>` prompt proves serial loading and CPU execution from BRAM;
- this milestone does not yet prove OpenSBI, S-mode, Sv39 or Linux.

## PMP resource configuration

Each hart's blocking MMU shares one PMP checker between page-table accesses
and the final physical-address check. All eight active entries are checked
in parallel; there is no added entry-scan state. The 16-entry CSR layout
remains visible, but entries 8–15 (including `pmpcfg2`) are read-zero,
write-ignored slots. Entries 0–7 retain TOR/NA4/NAPOT and lock semantics.

This small-memory target is for BIOS and small bare-metal programs. The
existing DDR Linux images and device tree describe a different RAM size and
are not the payload for this stage. The working DDR bitstream at commit
`b7b6005` remains archived on Alan in its original build directory.
