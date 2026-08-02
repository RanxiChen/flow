# BreezeCore ROM Smoke Firmware

This is a freestanding RV64I ROM image for the first LiteX simulation. It does
not use LiteX BIOS or a C library.

The startup code sets the SRAM stack, installs a direct-mode trap entry, copies
`.data`, clears `.bss`, and calls `main`. The C smoke test prints through the
LiteX UART in polling mode, then performs one cached 64-bit write/read check at
the base of main RAM.

Build it with the RISC-V bare-metal toolchain:

```bash
make -C software/breeze-smoke
```

The ROM input consumed by the LiteX simulation is:

```text
software/breeze-smoke/build/breeze-smoke.bin
```

The first image deliberately leaves timer/external interrupts disabled. Any
unexpected trap stays in `trap_entry`, making a missing success message visible
instead of repeatedly fetching from an unmapped default trap vector.
