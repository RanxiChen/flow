#!/usr/bin/env python3
"""Bind an immutable RVV build to its unchanged scalar hardware base."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--repo', required=True, type=Path)
p.add_argument('--hardware', required=True, type=Path)
p.add_argument('--source', required=True)
p.add_argument('--scalar-base', required=True)
a = p.parse_args()

def git(*args):
    return subprocess.check_output(['git', '-C', str(a.repo), *args], text=True).strip()

assert git('rev-parse', 'HEAD') == a.source
assert not git('status', '--short'), 'Build tree must be clean'
changed = git('diff', '--name-only', a.scalar_base, a.source, '--', 'design/src/main', 'config').splitlines()
assert changed == ['design/src/main/scala/rvv/RvvLoadStore.scala'], changed
for entry in json.loads((a.hardware / 'source-snapshot/manifest.json').read_text()):
    assert hashlib.sha256(Path(entry['snapshot']).read_bytes()).hexdigest() == entry['sha256']
profile = a.repo / 'design/build/rtl/axi-cluster/single-rvv/gshare/linux/production/cpu/coherent-dma/cluster-profile.txt'
shutil.copy2(profile, a.hardware / 'cluster-profile.txt')
shutil.copy2(a.repo / 'config/breeze_mcu_platform.json', a.hardware / 'platform.json')
words = (a.hardware / 'gateware/xilinx_kcu105_rom.init').read_text().splitlines()
rom = b''.join(int(w, 16).to_bytes(8, 'little') for w in words)
bios = (a.hardware / 'software/bios/bios.bin').read_bytes()
assert bios and rom[:len(bios)] == bios
binding = dict(source_sha=a.source, scalar_base_source_sha=a.scalar_base,
               scalar_base_rtl_diff=changed, source_dirty=False,
               host='Alan', hostname=subprocess.check_output(['hostname'], text=True).strip(),
               cwd=str(a.repo), profile='single-rvv', clock_hz=100000000,
               target='xcku040-ffva1156-2-e', vivado='2022.2',
               golden_changed=False, board='NOT_RUN', new_timing_exceptions=False)
(a.hardware / 'build-binding.json').write_text(json.dumps(binding, indent=2) + '\n')
(a.hardware / 'source-commit.txt').write_text(a.source + '\n')
(a.hardware / 'source-status.txt').write_text('')
(a.hardware / 'bios-rom-binding.json').write_text(json.dumps({
    'bios_bytes': len(bios), 'bios_sha256': hashlib.sha256(bios).hexdigest(),
    'rom_prefix_matches_bios': True,
    'rom_init_sha256': hashlib.sha256((a.hardware / 'gateware/xilinx_kcu105_rom.init').read_bytes()).hexdigest()
}, indent=2) + '\n')
print(json.dumps(binding, indent=2))
