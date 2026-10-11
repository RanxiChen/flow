#!/usr/bin/env bash
# Rebuild only the kernel against an existing GAPBS Buildroot software image.
# Keeps the input tree untouched. The complete from-source recipe is build.sh.
set -Eeuo pipefail
repo=$(cd -- "$(dirname -- "$0")/../.." && pwd)
sdk=${1:?usage: rebuild-kernel.sh EXISTING_BUILDROOT_OUTPUT LINUX_6_18_7_TAR_XZ FRESH_OUTPUT}
archive=${2:?Linux source archive required}
out=${3:?fresh absolute output required}
case "$out" in /*) ;; *) exit 2;; esac
test ! -e "$out"
test -s "$sdk/images/rootfs.cpio"
test -s "$sdk/images/fw_jump.bin"
test -x "$sdk/host/bin/riscv64-buildroot-linux-musl-gcc"
mkdir -p "$out"
trap 'rc=$?; echo "$rc" > "$out/exit-code.txt"' EXIT
git -C "$repo" rev-parse HEAD > "$out/source-commit.txt"
sha256sum "$archive" "$sdk/images/rootfs.cpio" "$sdk/images/fw_jump.bin" > "$out/input-sha256.txt"
tar -xJf "$archive" -C "$out"
src=$out/linux-6.18.7
test -f "$src/Makefile"
for p in "$repo/linux/buildroot-external/patches/linux/6.18.7/"*.patch; do
    patch --batch --fuzz=0 -d "$src" -p1 < "$p"
    sha256sum "$p" >> "$out/input-sha256.txt"
done
export ARCH=riscv CROSS_COMPILE="$sdk/host/bin/riscv64-buildroot-linux-musl-"
export PATH="$sdk/host/bin:$PATH"
make -C "$src" O="$out/kernel" defconfig
"$src/scripts/kconfig/merge_config.sh" -m -O "$out/kernel" "$out/kernel/.config" \
    "$repo/linux/buildroot-external/board/flow/linux.fragment" \
    "$repo/linux/buildroot-external/board/flow/linux-sd.fragment" \
    "$repo/linux/buildroot-external/board/flow/linux-be2.fragment"
"$src/scripts/config" --file "$out/kernel/.config" --set-str INITRAMFS_SOURCE "$sdk/images/rootfs.cpio" \
    --enable RISCV_ISA_V --enable RISCV_ISA_V_DEFAULT_ENABLE --disable RISCV_ISA_V_PREEMPTIVE \
    --set-val RISCV_ISA_V_UCOPY_THRESHOLD 2147483647
make -C "$src" O="$out/kernel" olddefconfig
make -C "$src" O="$out/kernel" -j"${JOBS:-2}" Image
mkdir "$out/images"
cp "$out/kernel/arch/riscv/boot/Image" "$sdk/images/fw_jump.bin" "$out/images/"
cp "$out/kernel/.config" "$out/kernel-config.txt"
sha256sum "$out/images/"* > "$out/output-sha256.txt"
