#!/usr/bin/env bash
# Add CoreMark to the existing GAPBS archive and relink a fresh kernel output.
set -Eeuo pipefail
repo=$(cd -- "$(dirname -- "$0")/../.." && pwd)
sdk=${1:?SDK output required}
archive=${2:?CoreMark1.01 tar.gz required}
kernel_base=${3:?validated kernel output parent required}
out=${4:?fresh absolute output required}
case "$out" in /*) ;; *) exit 2;; esac
test ! -e "$out"
test -x "$sdk/host/bin/riscv64-buildroot-linux-musl-gcc"
test -s "$sdk/images/rootfs.cpio"
test -s "$kernel_base/kernel/arch/riscv/boot/Image"
mkdir -p "$out/images"
trap 'rc=$?; echo "$rc" > "$out/exit-code.txt"' EXIT
git -C "$repo" rev-parse HEAD > "$out/source-commit.txt"
printf '%s  %s\n' 99c5a6d63af85a281b4e4d6ccb522c446653c435dfec9455ad73ef9e71f28bde "$archive" | sha256sum -c -
sha256sum "$archive" "$sdk/images/rootfs.cpio" "$sdk/images/fw_jump.bin" \
  "$kernel_base/kernel-config.txt" "$kernel_base/images/Image" > "$out/input-sha256.txt"
tar -xzf "$archive" -C "$out"
cc=${COREMARK_CC:-$sdk/host/bin/riscv64-buildroot-linux-musl-gcc}
readelf=${COREMARK_READELF:-$sdk/host/bin/riscv64-buildroot-linux-musl-readelf}
link_flags=${COREMARK_LINK_FLAGS:--lrt}
flags='-O3 -march=rv64imafdc -mabi=lp64d'
"$cc" --version > "$out/coremark-build-info.txt"
printf 'CoreMark=1.01\nCC=%s\nPORT_DIR=linux64\nPORT_CFLAGS=%s\nLFLAGS_END=%s\nRuntime=NOT_RUN\n' "$cc" "$flags" "$link_flags" >> "$out/coremark-build-info.txt"
make -C "$out/coremark-1.01" -j"${JOBS:-2}" CC="$cc" \
  PORT_CFLAGS="$flags" LFLAGS_END="$link_flags" PORT_DIR=linux64 EXE= link
"$readelf" -h -A -l -d \
  "$out/coremark-1.01/coremark" > "$out/coremark-elf.txt"
python3 "$repo/linux/sd-gapbs/add-coremark-initramfs.py" \
  --base "$sdk/images/rootfs.cpio" --coremark "$out/coremark-1.01/coremark" \
  --license "$out/coremark-1.01/LICENSE.md" --build-info "$out/coremark-build-info.txt" \
  --output "$out/images/rootfs.cpio"
# Copy build objects, not hardlinks; the prior validated kernel remains intact.
cp -a --reflink=auto "$kernel_base/kernel" "$out/kernel"
rm "$out/kernel/Makefile"
src="$kernel_base/linux-6.18.7"
"$src/scripts/config" --file "$out/kernel/.config" --set-str INITRAMFS_SOURCE "$out/images/rootfs.cpio"
export ARCH=riscv CROSS_COMPILE="$sdk/host/bin/riscv64-buildroot-linux-musl-"
export PATH="$sdk/host/bin:$PATH"
make -C "$src" O="$out/kernel" olddefconfig
make -C "$src" O="$out/kernel" -j"${JOBS:-2}" Image
cp "$out/kernel/arch/riscv/boot/Image" "$sdk/images/fw_jump.bin" "$out/images/"
cp "$out/kernel/.config" "$out/kernel-config.txt"
sha256sum -c "$out/input-sha256.txt"
sha256sum "$out/images/"* > "$out/output-sha256.txt"
