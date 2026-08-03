# Breeze MCU freestanding template

This directory is the reusable bare-metal runtime for the LiteX Breeze MCU.
The runtime owns reset/startup, `.data`/`.bss`, a complete integer trap frame,
Direct or Vectored `mtvec`, UART/timer access, finite simulation completion,
and a simulation completion mailbox. An application only supplies
`int main(void)`.

The default application is `apps/main.c`. Build it directly with:

```bash
make -C software/breeze-mcu
```

The canonical simulation command is the project runner:

```bash
python sim/litex/run_mcu.py --main path/to/main.c --elaborate
```

After `main` returns, the runtime writes a PASS or FAIL signature to its
linker-reserved SRAM completion mailbox and parks the core. The simulation
monitor observes the architecturally retired 64-bit store and terminates the
simulation. UART output is diagnostic only. A return value of zero reports
PASS, while a non-zero return value or an unexpected trap reports FAIL.

Before `main`, the runtime programs and clears the architectural machine-mode
HPM counters, then starts them with `mcountinhibit`. After `main` returns it
freezes the counters and stores a ten-word snapshot in linker-reserved SRAM.
The LiteX monitor captures those retired stores and reports PMU data and IPC.
The firmware does not print or parse performance data over UART. Advanced
M-mode firmware can use `include/breeze/perf.h` to access the same CSRs.

Directed interrupt checks are:

```bash
python sim/litex/run_mcu.py --smoke timer --mtvec-mode direct --elaborate
python sim/litex/run_mcu.py --smoke uart  --mtvec-mode direct
python sim/litex/run_mcu.py --smoke timer --mtvec-mode vectored
python sim/litex/run_mcu.py --smoke uart  --mtvec-mode vectored
```

The UART smoke uses LiteX's deterministic TX-ready level event. Its handler
disables `uart_ev_enable` before `mret`; writing `uart_ev_pending` cannot clear
the event while the TX FIFO remains ready.
