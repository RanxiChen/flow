#!/usr/bin/env python3
"""Repair measured short paths in an immutable routed frontend checkpoint."""
import datetime, hashlib, json, pathlib, shutil, subprocess, sys, time
root = pathlib.Path('/home/chen/FUN/flow-runs/frontend-pipeline-20261010')
parent, name = sys.argv[1:3]
upstream = root / parent
out = root / name
out.mkdir(exist_ok=False)
for item in json.loads((upstream / 'input-manifest.json').read_text()):
    assert hashlib.sha256((upstream / item['path']).read_bytes()).hexdigest() == item['sha256'], item['path']
shutil.copytree(upstream / 'rtl', out / 'rtl')
for filename in ['source-manifest.json', 'frontend.xdc', 'ooc-context.tcl', 'phase-inputs.tcl', 'phase-inputs.csv']:
    shutil.copy2(upstream / filename, out / filename)
shutil.copy2(upstream / 'route.dcp', out / 'input-route.dcp')
shutil.copy2(root / 'hold-eco.tcl', out / 'hold-eco.tcl')
lineage = {'parent': str(upstream), 'checkpoint': str(upstream / 'route.dcp'),
           'checkpoint_sha256': hashlib.sha256((out / 'input-route.dcp').read_bytes()).hexdigest(),
           'parent_command': json.loads((upstream / 'command.json').read_text()),
           'change': 'Identity LUT1 on measured negative-hold endpoint nets; no state/constraint changes'}
(out / 'upstream.json').write_text(json.dumps(lineage, indent=2) + '\n')
inputs = [{'path': str(p.relative_to(out)), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
          for p in sorted(out.rglob('*')) if p.is_file()]
(out / 'input-manifest.json').write_text(json.dumps(inputs, indent=2) + '\n')
argv = ['/home/chen/Tool/FPGA/Vivado/2022.2/bin/vivado', '-mode', 'batch', '-notrace', '-source', str(out / 'hold-eco.tcl')]
record = {'host': 'Alan', 'cwd': str(out), 'argv': argv,
          'source_digest': json.loads((out / 'source-manifest.json').read_text())['source_digest'],
          'started_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'upstream': lineage}
(out / 'command.json').write_text(json.dumps(record, indent=2) + '\n')
started = time.monotonic()
with (out / 'console.log').open('w') as log:
    result = subprocess.run(argv, cwd=out, stdout=log, stderr=subprocess.STDOUT)
record.update(exit_code=result.returncode, wall_seconds=time.monotonic() - started,
              ended_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
(out / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
print(json.dumps(record))
sys.exit(result.returncode)
