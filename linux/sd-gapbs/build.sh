#!/usr/bin/env bash
set -Eeuo pipefail
repo=$(cd -- "$(dirname -- "$0")/../.." && pwd)
: "${BUILDROOT_DIR:?Set BUILDROOT_DIR to the Buildroot source directory}"
out=${BUILDROOT_OUT:-$repo/build/sd-gapbs/output}
boot=${SD_GAPBS_BOOT:-$repo/build/sd-gapbs/boot}
mkdir -p "$out" "$boot"
make -C "$BUILDROOT_DIR" O="$out" BR2_EXTERNAL="$repo/linux/buildroot-external" flow_tiny_gapbs_defconfig
make -C "$BUILDROOT_DIR" O="$out" -j"${JOBS:-8}"
for name in Image fw_jump.bin flow-kcu105-tiny.dtb; do
    cp "$out/images/$name" "$boot/$name"
done
prefix=$out/host/bin/riscv64-buildroot-linux-musl-
"${prefix}gcc" -march=rv64im_zicsr_zifencei -mabi=lp64 -nostdlib -nostartfiles -static -no-pie -Wl,--no-relax \
    -T "$repo/software/breeze-linux/serial-handoff.ld" "$repo/software/breeze-linux/serial-handoff.S" -o "$boot/serial-handoff.elf"
"${prefix}objcopy" -O binary "$boot/serial-handoff.elf" "$boot/serial-handoff.bin"
python3 - "$boot" <<'PY'
import hashlib,json,pathlib,sys
p=pathlib.Path(sys.argv[1])
boot={'Image':'0x80200000','flow-kcu105-tiny.dtb':'0x80100000','fw_jump.bin':'0x80000000','serial-handoff.bin':'0x80080000','addr':'0x80080000'}
(p/'boot.json').write_text(json.dumps(boot,indent=2)+'\n')
files=[*list(boot)[:-1],'boot.json']
(p/'SHA256SUMS').write_text(''.join(hashlib.sha256((p/n).read_bytes()).hexdigest()+'  '+n+'\n' for n in files))
PY
printf 'SD boot files ready: %s\n' "$boot"
