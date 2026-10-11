#!/usr/bin/env python3
"""Resume identical placed RTL/constraints with explicit physical input phase sampling."""
import datetime, hashlib, json, pathlib, shutil, subprocess, sys, time

root = pathlib.Path('/home/chen/FUN/flow-runs/frontend-pipeline-20261010')
source = pathlib.Path('/home/chen/FUN/flow-frontend-pipeline-20261010')
parent, name = sys.argv[1:3]
upstream = root / parent
out = root / name
out.mkdir(exist_ok=False)
for item in json.loads((upstream / 'input-manifest.json').read_text()):
    assert hashlib.sha256((upstream / item['path']).read_bytes()).hexdigest() == item['sha256'], item['path']
shutil.copytree(upstream / 'rtl', out / 'rtl')
for name in ['source-manifest.json', 'frontend.xdc', 'ooc-context.tcl']:
    shutil.copy2(upstream / name, out / name)
shutil.copy2(upstream / 'place.dcp', out / 'input-place.dcp')
for filename in ['phase-candidate.tcl', 'phase-inputs.tcl']:
    shutil.copy2(root / filename, out / filename)
lineage = {'parent': str(upstream), 'checkpoint': str(upstream / 'place.dcp'),
           'checkpoint_sha256': hashlib.sha256((out / 'input-place.dcp').read_bytes()).hexdigest(),
           'parent_command': json.loads((upstream / 'command.json').read_text()),
           'change': 'Add test-environment falling-edge input sampling outside frontend; core RTL/XDC unchanged'}
(out / 'upstream.json').write_text(json.dumps(lineage, indent=2) + '\n')
inputs = [{'path': str(p.relative_to(out)), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
          for p in sorted(out.rglob('*')) if p.is_file()]
(out / 'input-manifest.json').write_text(json.dumps(inputs, indent=2) + '\n')
argv = ['/home/chen/Tool/FPGA/Vivado/2022.2/bin/vivado', '-mode', 'batch', '-notrace',
        '-source', str(out / 'phase-candidate.tcl')]
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
