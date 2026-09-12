# KCU105 single-hart SD and coherent DMA build

## 2026-09-12 simplification (supersedes the implementation notes below)

The target now calls official `self.add_sdcard(mode="read+write")` directly.
The custom SD helper, card-detect input, DMA status/reset/gating hardware and
all custom SD max-delay constraints have been removed. The manual MMCM
`clkouts` rewrite has also been removed; clock generation uses official CRG.
UART/SD interrupt allocation is explicitly 10/11, matching the PLIC wiring.
The Breeze coherent DMA entry and internal SD/DMA ILA probes are retained.

The BIOS driver overlay is disabled: the builder uses installed official
LiteX software. Old custom driver sources under `software/` are currently
unused and have not been adapted. Official driver alignment/error handling
and the updated LiteX/picolibc compatibility remain unfinished. No build or
board validation was performed for this simplification. `SnapshotBuilder`
in `build_support.py` only freezes gateware sources; it adds no hardware and
does not replace software packages. Manual BIOS boot remains selected.

The r5 bitstream is not timing-qualified: its all-register-Q max-delay
constraints segmented timing paths. Routed-DCP reanalysis gives WNS
-12.083 ns and WHS -0.241 ns. Preserve r5 artifacts; a fresh implementation
with valid constraints is required. Removing the erroneous constraints does
not complete external SD timing verification.

## Historical implementation notes (before simplification)

This is a JTAG-loaded FPGA product. It does not store FPGA configuration in
Flash, program the board, or modify the inserted SD card during the build.

## Implementation

The SD PHY, command/data engine and DMA engines are the installed
LiteSDCard/LiteX IP. `software/sdcard.c` is the Flow BIOS driver, selected by a
per-build software overlay. It replaces the BIOS `liblitesdcard/sdcard.c`;
the installed LiteX checkout and FatFs implementation are preserved. This is
not a `litex_demo` application or a Linux MMC driver.

`withCoherentDma=false` is the Chisel default. Passing `coherent-dma` as the
generator's fifth argument adds a 64-bit Wishbone slave to the cluster/Home.
Its RTL lives in a separate `coherent-dma` subdirectory and carries the
platform JSON SHA256 in `cluster-profile.txt`. The LiteX DMA CPU variant
rejects an absent/mismatched DMA marker or stale platform hash.

DMA uses the existing blocking Home transaction, array ports, directory,
probe/reply and memory refill/writeback machinery. CPU traffic and DMA
alternate when both are pending; existing CPU arbitration is retained. DMA
never becomes a directory owner/sharer. Coherent reads recall a UNIQUE owner
if necessary; writes invalidate all existing L1D copies, wait for replies,
merge byte enables into the latest line, and update L2 before ACK. Misses
allocate through the existing 32-byte refill path. The only permitted DMA
regions are cacheable read/write PMA memory. No DMA line cache, extra L2 bank,
extra SRAM port, or extra directory bit is added.

L1I is not a directory sharer: executing newly loaded code still requires
the normal FENCE.I handoff. Coherence also does not replace driver barriers,
DMA completion checks or software buffer ownership.

The 64-bit LiteX DMA engines require 8-byte-aligned memory buffers. Aligned
buffers are transferred directly. The BIOS driver uses one 512-byte static
array in existing SRAM for unaligned buffers and CPU copies only those
blocks. This is alignment adaptation, not cache maintenance, and does not
instantiate a separate SRAM IP. The SD IP retains its own stream FIFOs.

The driver initializes SD v2 cards at 400 kHz, then uses four data wires at
at most 5 MHz. It distinguishes sector/byte addressing through OCR, reports
raw CID/CSD words, checks actual SRAM/DDR DMA bounds, bounds command/data/DMA
waits, and propagates read errors to FatFs. Legacy cards that do not answer
CMD8 are not supported in this first-board profile. The DMA wrapper latches
Wishbone errors and stops further requests; firmware resets DMA/PHY after
failure. An error is never translated into a successful ACK.

## Current map

| Region | Address | Size |
| --- | --- | --- |
| BIOS ROM | `0x10010000` | 64 KiB |
| SRAM | `0x11000000` | 64 KiB |
| DDR | `0x80000000` | 2 GiB, ends at `0xffffffff` |
| Existing CSR pages | `0x12000000` through `0x12005000` | unchanged allocation |
| SD CSR | `0x12006000` | one 4 KiB page |
| SD DMA status/reset | `0x12007000` | one 4 KiB page |

The target checks actual SRAM/DDR regions against PMA. The MMCM search is
restricted to exact clock frequencies: at 100 MHz sys, pll4x is 400 MHz and
IDELAY reference is 200 MHz. This avoids the prior nominal-200/actual-201.389
MHz discrepancy; generated clock configuration and Vivado reports remain
the evidence for each particular build.

## Build and progress

On Alan, from `/home/chen/FUN/flow`, choose a **fresh absolute output path**:

```sh
nohup bash fpga/kcu105/build_sd.sh /absolute/fresh/output \
  > /absolute/fresh/output.log 2>&1 < /dev/null &
tail -f /absolute/fresh/output.log
```

The script runs the five directed hardware suites, generates
single/gshare/linux/fpga-debug/coherent-dma RTL, compiles the BIOS, and then
runs Vivado. `--prepare-only` stops before Vivado; `--vivado-only` consumes an
already prepared directory. It never uses `--load`.

`FLOW_TEST_CACHE=/absolute/previous/output` can reuse passing tests only when
that build's tracked design/config match the current commit, the current
design/config are clean, and the previous source status is clean. It records
the original tested commit separately from the new build commit.

Important artifacts:

- `directed-tests.log`, `directed-source-commit.txt`: hardware test evidence.
- `prepare.log`, `prepare-exit-code.txt`: RTL/BIOS/project generation result.
- `source-commit.txt`, `source-status.txt`, `platform.json`, `cluster-profile.txt`.
- `source-snapshot/manifest.json`: hashes and original paths of frozen RTL.
  Vivado consumes these copies. Include directories are copied too.
- `software-source/`: the exact BIOS/library overlay used by this build.
- `csr.json`, `csr.csv`: actual peripheral map; use these for later software.
- `ila-probes.json`: original 54 probes followed by SD/DMA diagnostics.
  CMD/DAT probes observe the PHY's internal registered inputs and pre-I/O
  outputs/enables. They never connect the bidirectional package-pad nets to
  the ILA; doing that caused r4's five unrouted SD nets (RTSTAT-1).
- `vivado-exit-code.txt`: synthesis/implementation/bitstream command result.
- `gateware/xilinx_kcu105.bit`, `gateware/xilinx_kcu105.ltx`, `gateware-sha256.txt`.

## Board checks after the user loads the image

BIOS automatic boot is disabled for initial SD diagnosis. The normal manual
`sdcardboot` command remains available after data checks. The first-board
console deliberately exposes no SD write/erase command.

```text
sd_status
sdcard_init
sdcard_read 0
sd_file_crc <existing-FAT-file>
```

Compare the reported sector/file CRC32 with the same bytes read on a host.
A successful card-detect signal does not prove SD command/data communication.
KCU105 U107 multiplexes the SD connection between FPGA and system controller;
`SYSCTLR_SDIO_MUX_SEL` is controlled by the system controller. SW15.6 ON with
the other switches OFF selects external JTAG, not a direct FPGA-controlled
SD mux pin. If identification times out, verify mux ownership and ILA command
traffic before changing driver timing or card contents.

## Verification boundary

55 directed tests passed with the optional DMA RTL and aligned 2 GiB DDR /
64 KiB SRAM PMA: DMA reads/writes, four-client invalidation, dirty partial
writes, high DDR addresses, memory errors, consecutive Wishbone cycles,
existing L2/L1 coherence and PMA checks. These tests do not establish physical
SD communication, the BIOS SD driver's behavior on a card, or Linux boot.

The SD target currently adds 5 ns internal pad-route budgets for CMD/DAT.
Those constraints are **not a complete external SD/card/mux timing model**;
the PHY uses sys-clocked I/O with a divided/gated SD clock. A generated bitstream
or clean internal setup/hold result must not be reported as complete SD
interface timing verification. First-board reads and CRC checks are still
required. Four-hart SD placement, full 2 GiB board memory tests and Linux boot
remain separate milestones. ACT4 ELF preparation follows the single-hart
FPGA build and must use its frozen map and a defined board completion protocol.

The r4 build compiled BIOS successfully (39.02 KiB ROM), then failed bitstream
DRC on the five CMD/DAT pad nets. Its directory and reports are retained.
The subsequent fix moves ILA observations behind the IOBUF/SDR input registers
and gives the output route-budget constraints explicit register startpoints;
it requires a fresh synthesis/implementation, not a bitgen-only retry.
