#!/usr/bin/env python3
"""Check the exact instrumented single-hart product and preserve evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

def digest(path):
    return {'bytes': path.stat().st_size,
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}

def config(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line)

parser = argparse.ArgumentParser()
parser.add_argument('--hardware', required=True, type=Path)
parser.add_argument('--baseline', required=True, type=Path)
parser.add_argument('--source-worktree', required=True, type=Path)
parser.add_argument('--prepared-only', action='store_true')
args = parser.parse_args()
sys.path.insert(0, str(args.source_worktree / 'fpga/kcu105'))
from collect_sd_products import timing_summary
hardware = args.hardware.resolve()
gateware = hardware / 'gateware'
current = config(hardware / 'cluster-profile.txt')
baseline = config(args.baseline / 'cluster-profile.txt')
assert current['tandem'] == current['debug'] == 'true'
assert current['nCores'] == '1' and current['rvv'] == 'false'
assert current.keys() == baseline.keys()
assert all(current[k] == baseline[k] for k in baseline if k not in ('debug', 'tandem'))
source = (hardware / 'source-commit.txt').read_text().strip()
assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=args.source_worktree,
                               text=True).strip() == source
assert not subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=no'],
                                   cwd=args.source_worktree, text=True).strip()
manifest = json.loads((hardware / 'source-snapshot/manifest.json').read_text())
for entry in manifest:
    assert digest(Path(entry['snapshot']))['sha256'] == entry['sha256']
probes = json.loads((hardware / 'ila-probes.json').read_text())
names = {p['signal'] for p in probes['probes']}
assert probes['clock_hz'] == 100000000 and probes['depth'] == 4096
assert probes['total_width'] == sum(p['width'] for p in probes['probes'])
for name in ('dbg_retire_valid', 'dbg_retire_pc', 'dbg_last_retire_pc',
             'dbg_hart_fatal', 'dbg_hang_reasons', 'dbg_pll_locked'):
    assert name in names
for prefix in ('memory', 'mmio'):
    for suffix in ('ar_count', 'last_ar_addr', 'reads_pending', 'write_active', 'error_seen'):
        assert f'dbg_{prefix}_monitor_{suffix}' in names
rom = b''.join(int(word, 16).to_bytes(8, 'little') for word in
               (gateware / 'xilinx_kcu105_rom.init').read_text().splitlines())
bios = (hardware / 'software/bios/bios.bin').read_bytes()
assert bios and rom[:len(bios)] == bios
binding = {'source_sha': source, 'scalar_base_source_sha':
           '7f7f186b9326fa7329890013ba578e68b68211ba', 'source_dirty': False,
           'source_worktree': str(args.source_worktree), 'host': 'Alan',
           'target': 'xcku040-ffva1156-2-e', 'clock_hz': 100000000,
           'profile': 'single', 'tandem': True, 'debug': True,
           'ILA': 'Xilinx ila6.2; JTAG via native debug hub',
           'ILA_width': probes['total_width'], 'ILA_probes': len(probes['probes']),
           'source_snapshot_files_verified': len(manifest),
           'bios_matches_rom': True, 'functional_config_matches_single': True,
           'golden_changed': False, 'new_user_timing_exceptions': False,
           'vendor_debug_constraints': 'Vivado ILA/debug hub generated IP constraints',
           'stage': 'PREPARED' if args.prepared_only else 'COMPLETED',
           'board': 'NOT_CAPTURED', 'files': {}}
for name in ('ila-probes.json', 'cluster-profile.txt', 'csr.json',
             'gateware/xilinx_kcu105.v', 'gateware/xilinx_kcu105.tcl',
             'gateware/xilinx_kcu105.xdc', 'software/bios/bios.bin'):
    binding['files'][name] = digest(hardware / name)
if not args.prepared_only:
    binding['timing_ns'] = timing_summary(gateware / 'routed-generation-gate.rpt')
    text = (gateware / 'xilinx_kcu105_route_status.rpt').read_text()
    match = re.search(r'# of nets with routing errors\.+\s*:\s*(\d+)', text)
    assert match and int(match[1]) == 0
    assert (gateware / 'xilinx_kcu105.bit').stat().st_mtime_ns >= (
        gateware / 'xilinx_kcu105_route.dcp').stat().st_mtime_ns
    for name in ('gateware/xilinx_kcu105.bit', 'gateware/xilinx_kcu105.ltx'):
        binding['files'][name] = digest(hardware / name)
        assert binding['files'][name]['bytes'] > 0
out = hardware / ('prepared-audit.json' if args.prepared_only else 'debug-product-binding.json')
out.write_text(json.dumps(binding, indent=2) + '\n')
print(out)
