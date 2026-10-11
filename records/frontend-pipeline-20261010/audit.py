#!/usr/bin/env python3
"""Audit collected standalone frontend gates without running hardware tools."""
import hashlib
import json
import csv
import datetime
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parent
repo = root.parents[1]
tests, rtl, route = sys.argv[1:4]
stages = [root / name for name in (tests, rtl, route)]
results = [json.loads((stage / 'result.json').read_text()) for stage in stages]
assert all(result['exit_code'] == 0 for result in results), results
digests = {result['source_digest'] for result in results}
assert len(digests) == 1, digests
manifest = json.loads((stages[2] / 'source-manifest.json').read_text())
assert all(json.loads((stage / 'source-manifest.json').read_text()) == manifest for stage in stages)
generation = json.loads((root / 'generated-rtl-audit.json').read_text())
assert generation['source_digest'] == next(iter(digests))
assert generation['generator_gate'] == rtl and generation['timing_gate'] == route
generated_files = {item['file'] for item in generation['files']}
assert generated_files == {file.name for file in (stages[2] / 'rtl').glob('*.sv')}
generation_start = datetime.datetime.fromisoformat(results[1]['started_utc']).timestamp()
generation_end = datetime.datetime.fromisoformat(results[1]['ended_utc']).timestamp()
for item in generation['files']:
    assert hashlib.sha256((stages[2] / 'rtl' / item['file']).read_bytes()).hexdigest() == item['sha256']
    assert generation_start - 1 <= item['generation_mtime'] <= generation_end + 1
for item in json.loads((stages[2] / 'input-manifest.json').read_text()):
    file = stages[2] / item['path']
    if file.suffix == '.dcp' and not file.exists():
        # Large checkpoints remain on Alan. Collector hashes must equal the
        # immutable pre-execution input hash; Vivado also opened this DCP.
        checkpoints = json.loads((stages[2] / 'checkpoints.json').read_text())
        matching = [entry for entry in checkpoints if pathlib.Path(entry['path']).name == file.name]
        assert len(matching) == 1 and matching[0]['sha256'] == item['sha256'], item['path']
    else:
        assert hashlib.sha256(file.read_bytes()).hexdigest() == item['sha256'], item['path']
checked = 0
for item in manifest['files']:
    if item['path'].startswith(('design/', 'config/')):
        assert hashlib.sha256((repo / item['path']).read_bytes()).hexdigest() == item['sha256'], item['path']
        checked += 1
for stage in stages:
    export = json.loads((root / f'{stage.name}-manifest.json').read_text())
    for item in export:
        assert hashlib.sha256((root / item['path']).read_bytes()).hexdigest() == item['sha256'], item['path']
counts = dict(tests=0, failures=0, errors=0, skipped=0)
reports = list((stages[0] / 'test-reports').glob('*.xml'))
assert reports, 'no fresh JUnit reports'
for report in reports:
    suites = ET.parse(report).getroot()
    for suite in ([suites] if suites.tag == 'testsuite' else suites.findall('testsuite')):
        for key in counts:
            counts[key] += int(suite.get(key, '0'))
assert len(reports) == 5 and counts['tests'] == 62, (reports, counts)
assert not any(counts[key] for key in ('failures', 'errors', 'skipped')), counts
assert 'Suites: completed 5, aborted 0' in (stages[0] / 'log.txt').read_text()
gate = json.loads((stages[2] / 'gate.json').read_text())
assert gate['period_ns'] == 10.0 and gate['clock_uncertainty_ns'] == 0.1, gate
assert gate['wns_ns'] >= 0 and gate['whs_ns'] >= 0, gate
assert 'All user specified timing constraints are met.' in (stages[2] / 'timing-route.rpt').read_text()
status = (stages[2] / 'route-status.rpt').read_text()
def nets(label):
    match = re.search(re.escape(label) + r'\.*\s*:\s*(\d+)', status)
    assert match, label
    return int(match.group(1))
assert nets('# of routable nets') == nets('# of fully routed nets')
assert nets('# of nets with routing errors') == 0
checks = re.findall(r'^\d+\. checking (\w+) \((\d+)\)', (stages[2] / 'check-timing.rpt').read_text(), re.M)
assert checks and all(int(count) == 0 for _, count in checks), checks
drc = (stages[2] / 'drc.rpt').read_text()
assert not re.search(r'\|\s*(Error|Critical Warning)\s*\|', drc), drc
physical_context = {'clock': 'actual internal BUFGCE', 'data_ports': 'placed INT virtual pins'}
if (stages[2] / 'phase-inputs.tcl').exists():
    phase = root / 'phase-interface-02'
    phase_result = json.loads((phase / 'result.json').read_text())
    assert phase_result['exit_code'] == 0 and phase_result['compile_exit_code'] == 0
    assert phase_result['source_digest'] == next(iter(digests))
    assert hashlib.sha256((phase / 'phase-interface.sv').read_bytes()).hexdigest() == phase_result['test_sha256']
    for item in json.loads((root / 'phase-interface-02-manifest.json').read_text()):
        assert hashlib.sha256((root / item['path']).read_bytes()).hexdigest() == item['sha256'], item['path']
    assert 'PHASE_INTERFACE_PASS: 8 clock phases, >=80000 vector comparisons; raw negative control failed as expected' in (phase / 'console.log').read_text()
    rows = list(csv.DictReader((stages[2] / 'phase-inputs.csv').open()))
    assert rows and len({row['port'] for row in rows}) == len(rows)
    assert len({row['register'] for row in rows}) == len(rows)
    assert all(int(row['sinks']) > 0 for row in rows)
    physical_context.update(input_sampling='falling edge in physical test environment; frontend RTL unchanged',
                            input_sampling_registers=len(rows),
                            boundary_test='phase-interface-02', clock_phases=8, vector_comparisons_min=80000)
if (stages[2] / 'hold-placement.csv').exists():
    moved = list(csv.DictReader((stages[2] / 'hold-placement.csv').open()))
    assert moved and len({row['endpoint'] for row in moved}) == len(moved)
    assert all(float(row['hold_before']) < 0 and float(row['setup_before']) >= 1.0
               and row['old_site'] != row['new_site'] for row in moved)
    physical_context.update(hold_repair='placement-only movement of measured short-path endpoints',
                            hold_endpoints_moved=len(moved))
audit = {'base_sha': manifest['base_sha'], 'source_digest': next(iter(digests)),
         'host': 'Alan', 'stages': [stage.name for stage in stages],
         'current_design_config_files_checked': checked, 'junit': counts,
         'generated_sv_files_checked': len(generated_files),
         'suites': len(reports), 'timing': gate, 'route_errors': 0,
         'check_timing': dict(checks), 'physical_context': physical_context,
         'scope': 'standalone frontend with explicit physical test context; backend integration excluded'}
(root / 'acceptance.json').write_text(json.dumps(audit, indent=2) + '\n')
print(json.dumps(audit, indent=2))
