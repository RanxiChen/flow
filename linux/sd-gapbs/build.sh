#!/usr/bin/env bash
set -Eeuo pipefail
repo=$(cd -- "$(dirname -- "$0")/../.." && pwd)
: "${BUILDROOT_DIR:?Set BUILDROOT_DIR to the Buildroot source directory}"
out=${BUILDROOT_OUT:-$repo/build/sd-gapbs/output}
boot=${SD_GAPBS_BOOT:-$repo/build/sd-gapbs/boot}
mkdir -p "$out" "$boot"
profile=${SD_GAPBS_PROFILE:-single}
case "$profile" in
    four) config=flow_be2_quad_defconfig;;
    single) config=flow_be2_single_defconfig;;
    single-rvv) config=flow_be2_single_rvv_defconfig;;
    single-zve32f) config=flow_be2_single_zve32f_defconfig;;
    *) echo "Unknown SD_GAPBS_PROFILE: $profile" >&2; exit 2;;
esac
make -C "$BUILDROOT_DIR" O="$out" BR2_EXTERNAL="$repo/linux/buildroot-external" "$config"
make -C "$BUILDROOT_DIR" O="$out" -j"${JOBS:-8}"
for name in Image fw_jump.bin; do
    cp "$out/images/$name" "$boot/$name"
done
prefix=$out/host/bin/riscv64-buildroot-linux-musl-
stage=$(mktemp -d "$boot/.package.XXXXXX")
python3 "$repo/fpga/kcu105/prepare_sd_boot.py" --images "$out/images" --output "$stage/files" \
    --profile "$profile" --cross-prefix "$prefix"
cp "$stage/files/"* "$boot/"
printf 'SD boot files ready: %s (profile %s)\n' "$boot" "$profile"
