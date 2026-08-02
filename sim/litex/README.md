# BreezeCore LiteX Simulation

This directory owns the concrete LiteX simulation SoC. The reusable CPU API
adapter remains in `litex_wrapper/flow/core.py`.

Generate the fixed core RTL and the standalone ROM image first:

```bash
cd design && sbt elaborate
cd ..
make -C software/breeze-smoke
```

Elaboration emits split SystemVerilog. `design/build/rtl/filelist.f` is the
authoritative source manifest; the LiteX CPU wrapper registers every file in
that list, including `BreezeCoreWishbone.sv` as the top-level module.

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

The simulation uses LiteX's standard `CRG`, including its power-on reset pulse.
This reset is required for the Chisel core to load the ROM reset vector before
the first instruction fetch.
