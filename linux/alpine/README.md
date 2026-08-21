# Diskless Alpine for Flow

This stage reuses the kernel configuration and cross-toolchain produced by the
fresh Flow Buildroot build, but replaces its initramfs with the official
Alpine 3.24.1 RISC-V minirootfs.  The release archive and SHA-256 are pinned;
the result is a separate `Image-alpine`, so Buildroot's `Image` is preserved.

```sh
./linux/alpine/build-alpine-image.sh /path/to/buildroot-output
```

No persistent block device is required.  On every simulated boot the host
loads `Image-alpine` into LiteDRAM at 0x80200000, exactly like the Buildroot
image.  Persistence can be added later without changing the CPU or the basic
OpenSBI/Linux boot contract.
