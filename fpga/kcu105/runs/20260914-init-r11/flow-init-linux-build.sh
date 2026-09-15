#!/bin/bash
set -euo pipefail
root=/home/chen/FUN/flow
out=$root/build/linux-kcu105-init-20260914-r11
old=$root/build/linux-kcu105-single-100mhz-6b0d572/output
mkdir "$out"
trap 'rc=$?; echo "$rc" > "$out/exit-code.txt"; date -Is > "$out/finished-at.txt"' EXIT
export PATH="$old/host/bin:/usr/bin:/bin"
unset LD_LIBRARY_PATH PYTHONPATH
export CROSS_COMPILE="$old/host/bin/riscv64-buildroot-linux-musl-"
export ARCH=riscv
date -Is > "$out/started-at.txt"
git -C "$root" rev-parse HEAD > "$out/source-commit.txt"
git -C "$root" diff --binary > "$out/source.patch"
mkdir "$out/images" "$out/input"
cp "$old/images/rootfs.cpio" "$out/images/"
cp "$old/build/linux-6.18.7/.config" "$out/input/kernel-baseline.config"
cp "$root/linux/buildroot-external/board/flow/dts/flow/flow-kcu105-tiny.dts" "$out/input/"
cp "$root/software/breeze-linux/serial-handoff.S" "$root/software/breeze-linux/serial-handoff.ld" "$out/input/"
sha256sum "$old/images/rootfs.cpio" "$CROSS_COMPILE"gcc /home/chen/FUN/flow-linux-work/buildroot/dl/linux/linux-6.18.7.tar.xz > "$out/input-sha256.txt"
tar -xf /home/chen/FUN/flow-linux-work/buildroot/dl/linux/linux-6.18.7.tar.xz -C "$out"
ksrc=$out/linux-6.18.7
cp "$out/input/kernel-baseline.config" "$ksrc/.config"
"$ksrc/scripts/config" --file "$ksrc/.config" --set-str INITRAMFS_SOURCE "$out/images/rootfs.cpio" -d MMC -d MTD
make -C "$ksrc" olddefconfig
make -C "$ksrc" -j12 Image
cp "$ksrc/arch/riscv/boot/Image" "$out/images/"
cp "$ksrc/vmlinux" "$ksrc/System.map" "$ksrc/.config" "$out/"
"$old/host/bin/dtc" -I dts -O dtb -o "$out/images/flow-kcu105-tiny.dtb" "$out/input/flow-kcu105-tiny.dts"
"$old/host/bin/dtc" -I dtb -O dts -o "$out/built-device-tree.dts" "$out/images/flow-kcu105-tiny.dtb"
rsync -a --exclude=build "$old/build/opensbi-1.9/" "$out/opensbi-source/"
make -C "$out/opensbi-source" -j8 PLATFORM=generic FW_TEXT_START=0x80000000 FW_JUMP_ADDR=0x80200000 FW_JUMP_FDT_ADDR=0x80100000 FW_PAYLOAD=n
cp "$out/opensbi-source/build/platform/generic/firmware/fw_jump.bin" "$out/opensbi-source/build/platform/generic/firmware/fw_jump.elf" "$out/images/"
"${CROSS_COMPILE}gcc" -march=rv64im_zicsr_zifencei -mabi=lp64 -nostdlib -nostartfiles -static -no-pie -Wl,--build-id=none -Wl,--no-relax -T "$out/input/serial-handoff.ld" "$out/input/serial-handoff.S" -o "$out/images/serial-handoff.elf"
"${CROSS_COMPILE}objcopy" -O binary "$out/images/serial-handoff.elf" "$out/images/serial-handoff.bin"
"${CROSS_COMPILE}objdump" -d "$out/images/serial-handoff.elf" > "$out/serial-handoff.disasm"
python3 - "$out" <<'PY'
import hashlib,json,pathlib,sys,zlib
p=pathlib.Path(sys.argv[1])/'images'
boot={'Image':'0x80200000','flow-kcu105-tiny.dtb':'0x80100000','fw_jump.bin':'0x80000000','serial-handoff.bin':'0x80080000'}
(p/'boot.json').write_text(json.dumps(boot,indent=2)+'\n')
manifest={}
for name in list(boot)+['boot.json','rootfs.cpio']:
 d=(p/name).read_bytes(); manifest[name]={'bytes':len(d),'sha256':hashlib.sha256(d).hexdigest(),'crc32':f'{zlib.crc32(d):08x}'}
(p/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
PY
cd "$out/images"
sha256sum Image fw_jump.bin flow-kcu105-tiny.dtb serial-handoff.bin rootfs.cpio > SHA256SUMS
echo BUILD_COMPLETE > "$out/status.txt"
