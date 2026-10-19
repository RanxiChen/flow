#!/usr/bin/env python3
"""Collect three completed, source-bound SD products; never program hardware."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil


PROFILES = ('four', 'single', 'single-zve32f')
REPORTS = ('xilinx_kcu105_timing.rpt', 'xilinx_kcu105_route_status.rpt',
           'xilinx_kcu105_drc.rpt', 'soc-utilization.rpt', 'soc-worst-10.rpt',
           'sd_input_paths.rpt', 'sd_output_paths.rpt', 'sd_output_min_paths.rpt')


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return {'bytes': path.stat().st_size, 'sha256': h.hexdigest()}


def timing_summary(path):
    text = path.read_text()
    section = text.split('| Design Timing Summary', 1)[1]
    for line in section.splitlines():
        fields = line.split()
        if len(fields) == 12 and all(re.fullmatch(r'-?\d+(?:\.\d+)?', x) for x in fields):
            metrics = dict(zip(('WNS', 'TNS', 'setup_failures', 'setup_endpoints',
                                'WHS', 'THS', 'hold_failures', 'hold_endpoints',
                                'WPWS', 'TPWS', 'pulse_failures', 'pulse_endpoints'),
                               map(float, fields)))
            if any(metrics[x] < 0 for x in ('WNS', 'WHS', 'WPWS')) or any(
                    metrics[x] != 0 for x in ('TNS', 'THS', 'TPWS', 'setup_failures',
                                             'hold_failures', 'pulse_failures')):
                raise ValueError(f'{path}: routed timing failed: {metrics}')
            return metrics
    raise ValueError(f'{path}: no complete design timing summary')


def profile_config(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line)


def validate_product_configs(configs):
    # User 2026-10-12 explicitly allows independent scalar resource profiles.
    scalar_derived = {'profile', 'nCores', 'idBits', 'fpTagEntries',
                      'l1Sets', 'l1dWays', 'l1iWays', 'l2Ways',
                      'l2BytesPerCore', 'l2Slots'}
    zve_changes = {'profile', 'zve', 'vlen', 'vectorL2Ids'}
    # User requested ILA/tandem on the ordinary single hart. These flags
    # describe passive observation; all compute/cache/clock fields still match.
    if configs['single'].get('debug') == 'true':
        scalar_derived |= {'debug', 'tandem'}
        zve_changes |= {'debug', 'tandem'}
    for key in configs['four'].keys() | configs['single'].keys():
        if key not in scalar_derived and configs['four'].get(key) != configs['single'].get(key):
            raise ValueError(f'Ordinary single hart differs from the four-hart configuration: {key}')
    for key in configs['single'].keys() | configs['single-zve32f'].keys():
        if key not in zve_changes and configs['single'].get(key) != configs['single-zve32f'].get(key):
            raise ValueError(f'Zve32f changed an unapproved base configuration: {key}')
    expected = {'profile': 'single-zve32f', 'zve': 'zve32f',
                'rvv': 'false', 'memoryBits': '64'}
    for key, value in expected.items():
        if configs['single-zve32f'].get(key) != value:
            raise ValueError(f'Zve32f product identity mismatch: {key}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for profile in PROFILES:
        parser.add_argument('--' + profile, required=True, type=Path)
        parser.add_argument('--' + profile + '-software', required=True, type=Path)
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--zve-source-sha',
                        help='Zve32f-only revision whose scalar base is the supplied source SHA')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit('Refusing to reuse the release directory')
    if not re.fullmatch('[0-9a-f]{40}', args.source_sha):
        raise SystemExit('A complete exact source SHA is required')
    if args.zve_source_sha and not re.fullmatch('[0-9a-f]{40}', args.zve_source_sha):
        raise SystemExit('A complete exact Zve32f source SHA is required')
    products = {}
    configs = {}
    # Validate every input before creating a release directory.
    for profile in PROFILES:
        hardware = getattr(args, profile.replace('-', '_'))
        software = getattr(args, profile.replace('-', '_') + '_software')
        binding = json.loads((hardware / 'build-binding.json').read_text())
        expected_source = args.zve_source_sha if profile == 'single-zve32f' and args.zve_source_sha else args.source_sha
        if binding['source_sha'] != expected_source or binding['source_dirty']:
            raise ValueError(f'{profile}: source identity mismatch')
        if expected_source != args.source_sha and binding.get('scalar_base_source_sha') != args.source_sha:
            raise ValueError(f'{profile}: Zve32f revision lacks the matching scalar base identity')
        if binding['clock_hz'] != 100000000 or binding['target'] != 'xcku040-ffva1156-2-e':
            raise ValueError(f'{profile}: target/clock mismatch')
        gateware = hardware / 'gateware'
        for name in REPORTS:
            if not (gateware / name).is_file() or (gateware / name).stat().st_size == 0:
                raise ValueError(f'{profile}: missing final report: {name}')
        bit = gateware / 'xilinx_kcu105.bit'
        checkpoint = gateware / 'xilinx_kcu105_route.dcp'
        if not bit.is_file() or bit.stat().st_size == 0 or not checkpoint.is_file():
            raise ValueError(f'{profile}: missing completed bitstream/routed checkpoint')
        if bit.stat().st_mtime_ns < checkpoint.stat().st_mtime_ns:
            raise ValueError(f'{profile}: bitstream predates the routed checkpoint')
        rom_words = (gateware / 'xilinx_kcu105_rom.init').read_text().splitlines()
        if not rom_words or any(not re.fullmatch('[0-9a-fA-F]{16}', w) for w in rom_words):
            raise ValueError(f'{profile}: unexpected 64-bit BIOS ROM initialization')
        rom_data = b''.join(int(w, 16).to_bytes(8, 'little') for w in rom_words)
        bios = (hardware / 'software/bios/bios.bin').read_bytes()
        if not bios or rom_data[:len(bios)] != bios:
            raise ValueError(f'{profile}: BIOS binary differs from the synthesized ROM input')
        timing = timing_summary(gateware / 'xilinx_kcu105_timing.rpt')
        route = (gateware / 'xilinx_kcu105_route_status.rpt').read_text()
        errors = re.search(r'# of nets with routing errors\.+\s*:\s*(\d+)', route)
        if not errors or int(errors[1]) != 0:
            raise ValueError(f'{profile}: routing errors or missing route-status evidence')
        boot_binding = json.loads((software / 'product-binding.json').read_text())
        if boot_binding['profile'] != profile or boot_binding['harts'] != (4 if profile == 'four' else 1):
            raise ValueError(f'{profile}: boot profile/hart mismatch')
        for name in ('csr.json', 'software/bios/bios.bin', 'gateware/xilinx_kcu105.bit'):
            if boot_binding['hardware'].get(name) != digest(hardware / name):
                raise ValueError(f'{profile}: software was not bound to this final {name}')
        software_manifest = json.loads((software / 'manifest.json').read_text())
        dtb = 'flow-be2-quad.dtb' if profile == 'four' else f'flow-be2-{profile}.dtb'
        required_boot = {'Image', 'fw_jump.bin', 'serial-handoff.bin', dtb, 'boot.json'}
        if set(software_manifest) != required_boot:
            raise ValueError(f'{profile}: incomplete or unexpected SD boot manifest')
        for name, expected in software_manifest.items():
            actual = digest(software / name)
            if actual['bytes'] != expected['bytes'] or actual['sha256'] != expected['sha256']:
                raise ValueError(f'{profile}: boot image changed: {name}')
        configs[profile] = profile_config(hardware / 'cluster-profile.txt')
        if configs[profile].get('debug') == 'true':
            if profile != 'single' or configs[profile].get('tandem') != 'true':
                raise ValueError(f'{profile}: debug requires the authorized single-hart tandem build')
            for name in ('gateware/xilinx_kcu105.ltx', 'ila-probes.json'):
                if not (hardware / name).is_file() or not (hardware / name).stat().st_size:
                    raise ValueError(f'{profile}: missing matching ILA artifact: {name}')
        products[profile] = {'source_sha': expected_source, 'scalar_base_source_sha': args.source_sha,
                             'hardware_input': str(hardware.resolve()),
                             'software_input': str(software.resolve()), 'build': binding,
                             'routed_timing_ns': timing, 'route_errors': 0,
                             'routed_checkpoint': digest(checkpoint), 'config': configs[profile],
                             'bios_matches_rom_init': True,
                             'board': 'NOT_RUN', 'sd_external_timing': 'OPEN; see sd-timing.md'}
        candidate_path = hardware / 'physical-candidate.json'
        if candidate_path.is_file():
            candidate = json.loads(candidate_path.read_text())
            if (candidate['source_sha'] != expected_source or
                    candidate['clock_hz'] != binding['clock_hz'] or
                    candidate['new_timing_exceptions'] or not candidate.get(
                        'timing_constraints_unchanged', candidate.get('constraints_unchanged', False))):
                raise ValueError(f'{profile}: physical candidate changed source/timing requirements')
            if candidate.get('input_checkpoint_filename', 'xilinx_kcu105_route.dcp') not in (
                    'xilinx_kcu105_route.dcp', 'clock-placed.dcp'):
                raise ValueError(f'{profile}: unexpected physical input checkpoint stage')
            original = Path(candidate['original'])
            if (json.loads((original / 'build-binding.json').read_text())['source_sha'] != expected_source or
                    digest(original / 'gateware' / candidate.get(
                        'input_checkpoint_filename', 'xilinx_kcu105_route.dcp'))['sha256'] !=
                    candidate['input_checkpoint_sha256']):
                raise ValueError(f'{profile}: physical candidate input identity mismatch')
            products[profile]['physical_candidate'] = candidate
    validate_product_configs(configs)
    args.output.mkdir(parents=True)
    for profile in PROFILES:
        hardware = getattr(args, profile.replace('-', '_'))
        software = getattr(args, profile.replace('-', '_') + '_software')
        destination = args.output / profile
        destination.mkdir()
        shutil.copy2(hardware / 'gateware/xilinx_kcu105.bit', destination / f'be2-{profile}-sd.bit')
        if configs[profile].get('debug') == 'true':
            shutil.copy2(hardware / 'gateware/xilinx_kcu105.ltx', destination / f'be2-{profile}-sd.ltx')
            shutil.copy2(hardware / 'ila-probes.json', destination / 'ila-probes.json')
        shutil.copy2(hardware / 'software/bios/bios.bin', destination / 'bios.bin')
        shutil.copy2(hardware / 'gateware/xilinx_kcu105_rom.init', destination / 'bios-rom.init')
        for name in ('csr.json', 'cluster-profile.txt', 'build-binding.json', 'platform.json'):
            shutil.copy2(hardware / name, destination / name)
        if (hardware / 'physical-candidate.json').is_file():
            shutil.copy2(hardware / 'physical-candidate.json', destination / 'physical-candidate.json')
        shutil.copytree(software, destination / 'sd')
        for name in REPORTS:
            shutil.copy2(hardware / 'gateware' / name, destination / name)
        products[profile]['files'] = {str(p.relative_to(destination)): digest(p)
                                     for p in sorted(destination.rglob('*')) if p.is_file()}
    (args.output / 'manifest.json').write_text(json.dumps(products, indent=2) + '\n')
    (args.output / 'README.txt').write_text(
        'KCU105: load the selected be2-*-sd.bit through JTAG. BIOS is embedded.\n'
        'Copy that product sd/ boot.json, Image, fw_jump.bin, DTB and serial-handoff.bin '
        'to the FAT partition root. SD stores software images only.\n'
        'UART: 115200 baud. BIOS automatically loads SD software after DDR initialization.\n'
        'Linux embeds the GAPBS initramfs; software can be rebuilt independently.\n'
        'The vector product is the configured Zve32f implementation; inspect its recorded validation scope.\n'
        'Routed internal timing and SD IO transport budgets pass. Board boot and complete '
        'external SD timing have not been verified; inspect the recorded console on hardware.\n')
    print(args.output)


if __name__ == '__main__':
    main()
