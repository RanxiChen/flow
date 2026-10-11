#!/usr/bin/env python3
"""Diagnose a documented hold optimization, retaining checkpoint provenance."""
import datetime, hashlib, json, pathlib, shutil, subprocess, time
root = pathlib.Path('/home/chen/FUN/flow-runs/frontend-pipeline-20261010')
out = root / 'hold-probe-01'
out.mkdir(exist_ok=False)
parent = root / 'route-09'
shutil.copy2(parent / 'route.dcp', out / 'input-route.dcp')
shutil.copy2(parent / 'source-manifest.json', out / 'source-manifest.json')
shutil.copy2(root / 'hold-probe.tcl', out / 'hold-probe.tcl')
inputs = [{'path': str(p.relative_to(out)), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
          for p in sorted(out.rglob('*')) if p.is_file()]
(out / 'input-manifest.json').write_text(json.dumps(inputs, indent=2) + '\n')
argv = ['/home/chen/Tool/FPGA/Vivado/2022.2/bin/vivado', '-mode', 'batch', '-notrace', '-source', str(out / 'hold-probe.tcl')]
record = {'host': 'Alan', 'cwd': str(out), 'argv': argv,
          'source_digest': json.loads((out / 'source-manifest.json').read_text())['source_digest'],
          'started_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'parent': str(parent), 'scope': 'diagnostic only; not a timing acceptance gate'}
(out / 'command.json').write_text(json.dumps(record, indent=2) + '\n')
started = time.monotonic()
with (out / 'console.log').open('w') as log:
    result = subprocess.run(argv, cwd=out, stdout=log, stderr=subprocess.STDOUT)
record.update(exit_code=result.returncode, wall_seconds=time.monotonic() - started,
              ended_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
(out / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
print(json.dumps(record))
