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

`prepare_sd_boot.py --images EXISTING_IMAGES --output FRESH_DIRECTORY` verifies
known Image/OpenSBI hashes, builds the existing handoff trampoline and emits an
explicit-entry boot.json. It updates the copied DTB memory size to 2 GiB to
match the current target/PMA, leaving the original images untouched. Copy
boot.json and its four referenced files into a FAT partition only when ready.
In BIOS use `sdcard_init`, `sdcard_read 0`, then `sdcardboot`.
The kernel still uses its embedded rootfs; the baseline has MMC_LITEX disabled
and this DTB has no MMC node. Linux SD mounting is a separate pending step.
