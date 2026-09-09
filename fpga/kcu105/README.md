# Breeze on KCU105: first bare-metal milestone

This target intentionally stops before OpenSBI and Linux.  The first board
milestone is:

1. start the four-hart Breeze cluster at the integrated LiteX BIOS ROM;
2. let hart 0 initialize and test the KCU105 DDR4 through LiteDRAM;
3. upload LiteX's standard bare-metal demo to DDR over UART;
4. execute the demo from DDR and interact with its serial console.

The other three harts are parked by the Breeze LiteX startup code while the
single-hart BIOS/demo path is exercised.

## Fixed hardware layout

| Region | Address | Implementation |
| --- | ---: | --- |
| CLINT | `0x02000000` | Breeze SystemVerilog CLINT, 1 MHz `mtime` |
| PLIC | `0x0c000000` | Breeze SystemVerilog PLIC |
| ROM | `0x10010000` | 64 KiB FPGA BRAM containing the LiteX BIOS |
| SRAM | `0x11000000` | 64 KiB FPGA BRAM for BIOS/demo data and stack |
| LiteX CSR | `0x12000000` | control, UART, BIOS timer and LiteDRAM CSRs |
| main RAM | `0x80000000` | KCU105 DDR4 through LiteDRAM |

The system clock is fixed at 125 MHz.  `integrated_main_ram_size` is zero:
only LiteDRAM implements `main_ram`.  LiteX's optional L2 is disabled because
Breeze already has its own coherent shared L2.  The build uses Vivado's
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
`build/fpga/kcu105-breeze`, but does not launch Vivado.  Run implementation
only when explicitly wanted:

```sh
python fpga/kcu105/target.py --build
```

## Build LiteX's standard bare-metal demo

The installed LiteX source provides the unmodified template at
`litex/soc/software/demo/` and exposes it as `litex_bare_metal_demo`.  Build it
against this SoC's generated headers and memory map:

```sh
cd /home/chen/FUN/flow/build/fpga/kcu105-breeze
litex_bare_metal_demo --build-path=.
```

The resulting `demo.bin` is linked to `main_ram` at `0x80000000`.

## Load and run on the board

After Vivado has produced the bitstream and the board is connected:

```sh
cd /home/chen/FUN/flow
python fpga/kcu105/target.py --load
litex_term /dev/ttyUSBX --kernel=build/fpga/kcu105-breeze/demo.bin
```

Replace `/dev/ttyUSBX` with the KCU105 UART device.  Success evidence is kept
separate by stage:

- the BIOS banner and successful SDRAM initialization/memtest prove the ROM,
  UART, timer and initial DDR path;
- `Executing booted program at 0x80000000` followed by the
  `litex-demo-app>` prompt proves serial loading and CPU execution from DDR;
- this milestone does not yet prove OpenSBI, S-mode, Sv39 or Linux.
