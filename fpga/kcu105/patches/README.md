# Official LiteX SD BIOS adaptation

Base LiteX: `6d8a38cade2092cb1e7db3e5602e81093aead8f9`.
`SnapshotBuilder` verifies the upstream sdcard.c SHA256, copies liblitesdcard
into the fresh build directory, then applies litex-sdcard.patch with no fuzz.
The installed editable LiteX checkout stays clean. Changes to upstream require
reviewing and regenerating this patch, not silently skipping it.

The patch keeps official SD commands, initialization, FatFs and BIOS commands.
It adds RV64 ordering fences, explicit eight-byte alignment, a shared 512-byte
SRAM bounce buffer only for unaligned callers, DMA alignment-error checking,
SCR failure cleanup, and bounded command/data waits. Direct requests are split
at 128 sectors so the one-second data wait is reasonable at the initial 5 MHz
operating clock. The 400 kHz initialization clock is unchanged. These waits do
not recover a hung Wishbone transaction: upstream DMA does not handle bus ERR.
Only valid cacheable SRAM/DDR destinations should be used.

Alan's editable pythondata-software-picolibc was also updated to
`6a13ccce7c575b32c102dd9dc52178505b81fe39`, submodule
`16ff442da4b92e28d0753fabed18ad4a15254498`, matching LiteX's libc/ layout.
This is a BIOS-only adaptation, not a Linux MMC driver patch.

No write command is executed by the build. Board SD reads, writes, external
interface timing and Linux boot require separate verification.

## Boot files

`prepare_sd_boot.py --images EXISTING_IMAGES --output FRESH_DIRECTORY
--profile four|single|single-rvv --hardware-dir FINAL_HARDWARE` builds the
matching BE2 DTB and existing handoff trampoline, checks actual CSR/hart ABI,
and emits explicit-entry boot.json plus file and hardware hashes. Historical
Image/OpenSBI hash enforcement is available only with `--verified-legacy`;
new software images are recorded under their own hashes. Copy boot.json and
its four referenced files to the FAT partition root. BIOS automatically tries
SD software boot after DDR initialization; manual `sdcard_init`,
`sdcard_read 0`, and `sdcardboot` remain available.

The BE2 kernel enables MMC_LITEX, SMP4 and the configured user vector context
support; matching DTBs include the actual SD controller. Linux uses its
embedded rootfs. New SD/MMC enumeration and boot are still BOARD_NOT_RUN.
See [the three-product instructions](../sd-bringup.md) for independent
software rebuilds and the RVV subset limit.

The SD debug successor adds storage qualification and 8192-point ILA. Use
`capture_sd.tcl` with the matching LTX and an unused output prefix, then run
sdcard_init only after FLOW_SD_ARMED. Interpret `dbg_sd_ticks` as elapsed sys
cycles; filtered sample indices are not a time scale. FSM probes are one-hot,
with bit names in ila-sd-states.json. See ../sd-timing.md for timing limits.
