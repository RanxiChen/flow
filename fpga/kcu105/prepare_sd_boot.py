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
    args = parser.parse_args()
    # Known booted software baseline. Do not silently package unrelated binaries.
    expected = {
        'Image': 'cbaf6857c807001d73a983a6551d9d8105fdc6783935c5def398871e02341006',
        'fw_jump.bin': 'fa96fc4b7b7f56c2110f713c3268db62eb90951f0fec0f56e81b811007fefd41',
    }
    for name, digest in expected.items():
        if hashlib.sha256((args.images / name).read_bytes()).hexdigest() != digest:
            raise SystemExit(f'{name}: differs from the verified software baseline')
    args.output.mkdir(parents=True, exist_ok=False)
    for name in (*expected, 'flow-kcu105-tiny.dtb'):
        shutil.copy2(args.images / name, args.output / name)
    dtb = args.output / 'flow-kcu105-tiny.dtb'
    subprocess.run(['fdtput', '-t', 'x', str(dtb), '/memory@80000000',
                    'reg', '0', '80000000', '0', '80000000'], check=True)
    source = Path(__file__).resolve().parents[2] / 'software/breeze-linux'
    elf = args.output / 'serial-handoff.elf'
    subprocess.run([args.cross_prefix + 'gcc', '-march=rv64im_zicsr_zifencei',
                    '-mabi=lp64', '-nostdlib', '-nostartfiles', '-Wl,--no-relax',
                    '-T', str(source / 'serial-handoff.ld'),
                    str(source / 'serial-handoff.S'), '-o', str(elf)], check=True)
    subprocess.run([args.cross_prefix + 'objcopy', '-O', 'binary', str(elf),
                    str(args.output / 'serial-handoff.bin')], check=True)
    boot = {'Image': '0x80200000', 'flow-kcu105-tiny.dtb': '0x80100000',
            'fw_jump.bin': '0x80000000', 'serial-handoff.bin': '0x80080000',
            'addr': '0x80080000'}
    (args.output / 'boot.json').write_text(json.dumps(boot, indent=2) + '\n')
    manifest = {}
    for name in (*list(boot)[:-1], 'boot.json'):
        data = (args.output / name).read_bytes()
        manifest[name] = {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                          'crc32': f'{zlib.crc32(data):08x}'}
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'Prepared FAT files in {args.output}; no SD card was written.')


if __name__ == '__main__':
    main()
