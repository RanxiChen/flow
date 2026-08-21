# Flow Buildroot external tree

This tree builds the first diskless Linux milestone for the four-hart Flow
LiteX/LiteDRAM simulation.  It deliberately embeds the root filesystem in the
Linux `Image`; no block device or VirtIO dependency is present.

From a fresh Buildroot 2026.05.1 checkout:

```sh
make BR2_EXTERNAL=/path/to/flow/linux/buildroot-external flow_small_defconfig
make -j$(nproc)
```

The simulator consumes `output/images/Image`, `flow-small.dtb`, and
`fw_jump.bin` at 0x80200000, 0x80100000, and 0x80000000 respectively.
