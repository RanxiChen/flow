#!/usr/bin/env python3
"""Prepare files for a FAT SD partition; never opens a block device."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zlib


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--images', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--cross-prefix', default='riscv64-unknown-elf-')
    parser.add_argument('--profile', choices=('four', 'single', 'single-rvv'), default='single')
    parser.add_argument('--hardware-dir', type=Path,
                        help='bind boot files to this generated SoC CSR map and BIOS')
    parser.add_argument('--verified-legacy', action='store_true',
                        help='require the historical booted Image/OpenSBI hashes')
    args = parser.parse_args()
    # Known booted software baseline. Do not silently package unrelated binaries.
    expected = {
        'Image': 'cbaf6857c807001d73a983a6551d9d8105fdc6783935c5def398871e02341006',
        'fw_jump.bin': 'fa96fc4b7b7f56c2110f713c3268db62eb90951f0fec0f56e81b811007fefd41',
    }
    for name, digest in expected.items():
        image = args.images / name
        if not image.is_file() or image.stat().st_size == 0:
            raise SystemExit(f'{name}: missing or empty software image')
        if args.verified_legacy and hashlib.sha256(image.read_bytes()).hexdigest() != digest:
            raise SystemExit(f'{name}: differs from the verified software baseline')
    args.output.mkdir(parents=True, exist_ok=False)
    for name in expected:
        shutil.copy2(args.images / name, args.output / name)
    repo = Path(__file__).resolve().parents[2]
    key = 'quad' if args.profile == 'four' else args.profile
    dtb_name = f'flow-be2-{key}.dtb'
    dts = repo / 'linux/buildroot-external/board/flow/dts/flow' / f'flow-be2-{key}.dts'
    subprocess.run(['dtc', '-I', 'dts', '-O', 'dtb', '-o', str(args.output / dtb_name), str(dts)], check=True)
    hardware = {}
    if args.hardware_dir:
        csr = json.loads((args.hardware_dir / 'csr.json').read_text())
        if csr['csr_bases'].get('sdcard') != 0x12006000:
            raise SystemExit('SD CSR base does not match the selected device tree')
        expected_harts = 4 if args.profile == 'four' else 1
        if csr['constants'].get('breeze_num_harts') != expected_harts:
            raise SystemExit('hardware hart count does not match boot profile')
        for name in ('csr.json', 'software/bios/bios.bin'):
            data = (args.hardware_dir / name).read_bytes()
            hardware[name] = {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
    source = repo / 'software/breeze-linux'
    elf = args.output / 'serial-handoff.elf'
    subprocess.run([args.cross_prefix + 'gcc', '-march=rv64im_zicsr_zifencei',
                    '-mabi=lp64', '-nostdlib', '-nostartfiles', '-Wl,--no-relax',
                    '-T', str(source / 'serial-handoff.ld'),
                    str(source / 'serial-handoff.S'), '-o', str(elf)], check=True)
    subprocess.run([args.cross_prefix + 'objcopy', '-O', 'binary', str(elf),
                    str(args.output / 'serial-handoff.bin')], check=True)
    boot = {'Image': '0x80200000', dtb_name: '0x80100000',
            'fw_jump.bin': '0x80000000', 'serial-handoff.bin': '0x80080000',
            'addr': '0x80080000'}
    (args.output / 'boot.json').write_text(json.dumps(boot, indent=2) + '\n')
    manifest = {}
    for name in (*list(boot)[:-1], 'boot.json'):
        data = (args.output / name).read_bytes()
        manifest[name] = {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                          'crc32': f'{zlib.crc32(data):08x}'}
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    binding = {'profile': args.profile, 'harts': 4 if args.profile == 'four' else 1,
               'hardware': hardware, 'software_images': str(args.images.resolve()),
               'rvv_scope': 'R04 integer and unit-stride subset; unsupported encodings trap' if args.profile == 'single-rvv' else None}
    (args.output / 'product-binding.json').write_text(json.dumps(binding, indent=2) + '\n')
    print(f'Prepared FAT files in {args.output}; no SD card was written.')


if __name__ == '__main__':
    main()
