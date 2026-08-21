#!/bin/sh
set -eu

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "usage: $0 BUILDROOT_OUTPUT [DESTINATION]" >&2
    exit 2
fi

br_out=$(readlink -f "$1")
destination=${2:-"$br_out/images/Image-alpine"}
destination=$(readlink -m "$destination")
kernel_src=$(find "$br_out/build" -maxdepth 1 -type d -name 'linux-[0-9]*' | head -n 1)
cross="$br_out/host/bin/riscv64-buildroot-linux-musl-"
cpio_bin="$br_out/host/bin/cpio"

if [ -z "$kernel_src" ] || [ ! -f "$kernel_src/.config" ]; then
    echo "Buildroot Linux has not been built under $br_out" >&2
    exit 1
fi
if [ ! -x "${cross}gcc" ] || [ ! -x "$cpio_bin" ]; then
    echo "Buildroot host toolchain/cpio is incomplete under $br_out/host" >&2
    exit 1
fi

version=3.24.1
archive="alpine-minirootfs-${version}-riscv64.tar.gz"
url="https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/riscv64/$archive"
sha256=7201513262d851f39105102cf95519410100259bd7996fca13bade517838d7b7
work=$(mktemp -d "${TMPDIR:-/tmp}/flow-alpine.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM

curl -fL "$url" -o "$work/$archive"
printf '%s  %s\n' "$sha256" "$work/$archive" | sha256sum -c -
mkdir -p "$work/rootfs" "$work/kernel-build" "$(dirname "$destination")"
tar -xzf "$work/$archive" -C "$work/rootfs"
cp "$(dirname "$0")/init" "$work/rootfs/init"
chmod 0755 "$work/rootfs/init"

(
    cd "$work/rootfs"
    find . -print0 | "$cpio_bin" --null -o --format=newc --owner=0:0
) > "$work/alpine.cpio"
gzip -9 "$work/alpine.cpio"

cp "$kernel_src/.config" "$work/kernel-build/.config"
"$kernel_src/scripts/config" --file "$work/kernel-build/.config" \
    --enable BLK_DEV_INITRD \
    --set-str INITRAMFS_SOURCE "$work/alpine.cpio.gz"
PATH="$br_out/host/bin:$PATH" make -C "$kernel_src" O="$work/kernel-build" \
    ARCH=riscv CROSS_COMPILE="$cross" olddefconfig
PATH="$br_out/host/bin:$PATH" make -C "$kernel_src" O="$work/kernel-build" \
    ARCH=riscv CROSS_COMPILE="$cross" -j"$(nproc)" Image
cp "$work/kernel-build/arch/riscv/boot/Image" "$destination"
sha256sum "$destination"
echo "FLOW_ALPINE_IMAGE=$destination"
