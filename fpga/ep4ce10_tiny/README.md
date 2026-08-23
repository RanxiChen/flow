# Flow Tiny EP4CE10 MCU

This is a fixed minimal MCU configuration, not a new configurable CPU family.

- Device: Cyclone IV E `EP4CE10F17C8`.
- Clock: 50 MHz direct board clock.
- CPU: one existing Flow hart with the GShare frontend.
- ISA: fixed RV64I + Zicsr + Zifencei; no M/A/F/D/C hardware.
- Cache hierarchy: 2 KiB L1I, 2 KiB L1D, 4 KiB L2/Home.
- SoC memory: 8 KiB integrated ROM and 16 KiB integrated SRAM.
- Devices: LiteUART (`115200`, 8N1) and one-hart CLINT (`mtime` at 1 MHz).
- Board I/O: 50 MHz clock, active-low reset and USB UART only.
- External SDRAM is deliberately absent from the first resource measurement.
- Firmware: wait for the machine-timer interrupt, then transmit
  `Hello World\r\n` once per minute.

The cache and Wishbone architecture remain the same as the existing Flow
single-hart design. Only cache capacity and the fixed ISA implementation are
reduced for this EP4CE10 implementation.

Generate the fixed RTL and LiteX Quartus project:

```bash
fpga/ep4ce10_tiny/generate_gateware.sh
```

Run Windows Quartus from WSL:

```bash
fpga/ep4ce10_tiny/build_quartus.sh 2>&1 | tee build/ep4ce10-tiny-quartus.log
```

The generated project and reports are under `build/ep4ce10-tiny/gateware/`.
This command stops after producing and checking a Quartus `.sof`; it does not
program the board.

After Quartus succeeds, connect one USB-Blaster and program the volatile SOF:

```bash
fpga/ep4ce10_tiny/program_sof.sh
```

The script requires the expected EP4CE10 JTAG ID `020F10DD`. Open the board's
USB serial port as `115200 8N1`; the first line appears one minute after reset.
