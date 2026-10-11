#!/usr/bin/env python3
"""Verify downloaded release artifacts against the remote collector manifest."""
import hashlib
import json
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
manifest = json.loads((root / 'manifest.json').read_text())
assert set(manifest) == {'four', 'single', 'single-rvv'}
checked = 0
products = {}
for profile, product in manifest.items():
    for name, expected in product['files'].items():
        path = root / profile / name
        actual = {'bytes': path.stat().st_size,
                  'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
        if actual != expected:
            raise SystemExit(f'Changed or incomplete artifact: {path}')
        checked += 1
    products[profile] = {
        'source_sha': product['source_sha'],
        'bitstream': product['files'][f'be2-{profile}-sd.bit'],
        'routed_timing_ns': product['routed_timing_ns'],
        'board': product['board'],
        'bios_matches_rom_init': product['bios_matches_rom_init'],
    }
print(json.dumps({'release': str(root), 'checked_files': checked,
                  'hash_errors': 0, 'products': products}, indent=2))
