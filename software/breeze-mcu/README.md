# Breeze MCU freestanding template

This directory is the reusable bare-metal runtime for the LiteX Breeze MCU.
The runtime owns reset/startup, `.data`/`.bss`, a complete integer trap frame,
Direct or Vectored `mtvec`, UART/timer access, finite simulation completion,
and `mcycle`/`minstret` measurement. An application only supplies `int main(void)`.

The default application is `apps/main.c`. Build it directly with:

```bash
make -C software/breeze-mcu
```

The canonical simulation command is the project runner:

```bash
python sim/litex/run_mcu.py --main path/to/main.c --elaborate
```

After `main` returns, the firmware prints a machine-readable line:

```text
BREEZE_STATS cycles=0x... instructions=0x...
```

The Python runner computes and prints `BREEZE_IPC`; RV64I firmware performs no
division. A return value of zero reports PASS, while a non-zero return value or
an unexpected trap reports FAIL.

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
