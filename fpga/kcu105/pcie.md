# Breeze PCIe / FASE bring-up

This is an experimental **single-hart** profile. Board enumeration, DMA throughput
and timing are separate validation gates. It does not change the published SD bitstream.

## Hardware contract

- KU040 XDMA 4.1, Gen3 x8 target, AXI-MM 256-bit/64-bit address/4-bit ID,
  one H2C and one C2H channel; user AXI-Lite BAR, 64 KiB.
- PCIe user clock and 100 MHz SoC clock use AXI Clock Converter 2.1,
  asynchronous mode, three synchronization stages, for both MM and control.
- MM is serialized into 64-bit classic Wishbone transfers. Only INCR bursts,
  size <=32 bytes; narrow transfers must be naturally aligned. Full-width
  transfers can start unaligned, with legal byte strobes. AXI 4 KiB boundaries
  are enforced. Errors return SLVERR; no upper-address truncation to DDR.
- `0x40000000..0x40000fff`: private PCIe test RAM, not CPU-visible; useful before
  DDR initialization. `0x80000000..0xffffffff`: coherent L2 DMA access. All other
  destinations are rejected. SD and PCIe share LiteX DMA arbitration.
- LiteX's synthetic timeout ACK is disabled on this profile's memory/DMA fabric;
  it must not turn a stalled transfer into false success. A permanently stalled
  target requires host timeout and coordinated reset, not retry while still active.
- FASE commands arbitrate per transaction with JTAG, locking from command
  presentation through response acceptance. Users must not run concurrent debug
  sessions: transaction arbitration does not provide exclusive whole-session ownership.
- XDMA reset resets the SoC/DDR clock tree to avoid abandoning L2 transactions.
  This profile requires a live PCIe reference clock and released PERST to boot.
  Stop DMA before board/software reset. Hot-reset/recovery is not board-validated.
- Data writes enter L2; D-cache coherence is reused. I-cache is not a directory
  sharer: execute the existing instruction-cache synchronization before launching
  overwritten code. DMA completion alone does not perform `fence.i`.
- For DDR transfers wait for BIOS DDR initialization and halt/drain the core.
  The host must choose unused physical memory; hardware does not allocate it.

## FASE user BAR ABI v1

Use the XDMA driver's **user** BAR mapping, not its internal DMA register BAR.
All registers are little-endian 32-bit, naturally aligned. Undefined offsets
and invalid writes return SLVERR. Readback does not clear completion.

| Offset | Meaning |
| --- | --- |
| 0x00 | magic `0x46415345` |
| 0x04 | ABI version 1 |
| 0x08 | bit 0 busy, bit 1 completion present, bit 2 FASE response error |
| 0x0c | read/write scratch, supports byte enables |
| 0x10 | command opcode [7:0], register index [13:8] |
| 0x14 / 0x18 | command data low / high |
| 0x1c / 0x20 | command PC low / high |
| 0x24 | write sequence number to submit; read last submitted sequence |
| 0x28 | completed sequence |
| 0x2c / 0x30 | result low / high |
| 0x38 | write completed sequence to acknowledge response |
| 0x40 / 0x44 | completed 64-bit read / write transfer counters, modulo 2^32 |
| 0x48 | downstream Wishbone error count, modulo 2^32 |

SUBMIT and ACK require all four byte enables. Command fields cannot change while
busy or a completion remains unacknowledged. FASE opcodes retain their existing
meaning: a returned EXEC response is not necessarily instruction retirement;
poll the existing FASE status protocol. No automatic CPU launch after DMA.

## Build on Alan

```sh
cd /home/chen/FUN/flow
bash fpga/kcu105/build_pcie.sh /home/chen/FUN/flow/build/fpga/kcu105-pcie-fase-100mhz-r1
```

Use a new suffix for another build. No ILA; FASE/flight recorder remain enabled.
Generated IP, RTL snapshots, source identity, tests and Vivado reports stay under
the output directory. `vivado-exit-code.txt=0` and a bitstream do not prove timing.

## Validation

`bash sim/pcie/run.sh` exercises the custom bridges and command arbitration with
Icarus Verilog and Migen. It covers burst/partial/narrow accesses, invalid address
and boundary rejection, Wishbone errors, response backpressure, independent
AXI-Lite AW/W arrivals, command snapshot and JTAG/PCIe response ownership.
These tests do not simulate PCIe PHY/link training or replace board validation.

On the host tomorrow: enumerate and check negotiated link, load the matching
XDMA driver, read magic/ABI/scratch, DMA test RAM with byte comparison, then
initialize DDR and test a reserved DDR area with the core halted. Only after
that use FASE to load/launch an ELF. Do not run a destructive DDR test over a
running Linux image. The original host ELF loader is not changed by this profile.

SRAM configuration is volatile: after moving the powered-off board, program
the bitstream again on the PCIe host. Programming the FPGA before host boot is
the first bring-up route; runtime re-enumeration is host-dependent. Flash
programming is a separate operation and is not performed by these scripts.
