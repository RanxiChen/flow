#!/usr/bin/env python3
import datetime, hashlib, json, pathlib, shutil, subprocess, sys, time

source = pathlib.Path('/home/chen/FUN/flow-frontend-pipeline-20261010')
evidence = pathlib.Path('/home/chen/FUN/flow-runs/frontend-pipeline-20261010')
name = sys.argv[1]
out = evidence / name
out.mkdir(parents=True, exist_ok=False)
rtl = source / 'rtl-timing'
shutil.copytree(rtl, out / 'rtl')
for file in ['frontend.xdc', 'implement.tcl', 'ooc-context.tcl', 'source-manifest.json']:
    shutil.copy2(source / file, out / file)
inputs = [{'path': str(p.relative_to(out)), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
          for p in sorted(out.rglob('*')) if p.is_file()]
(out / 'input-manifest.json').write_text(json.dumps(inputs, indent=2) + '\n')
argv = ['/home/chen/Tool/FPGA/Vivado/2022.2/bin/vivado', '-mode', 'batch', '-notrace',
        '-source', str(out / 'implement.tcl'), '-tclargs', str(out / 'rtl'), str(out / 'frontend.xdc')]
record = {'host': 'Alan', 'cwd': str(out), 'argv': argv,
          'source_digest': json.loads((out / 'source-manifest.json').read_text())['source_digest'],
          'started_utc': datetime.datetime.now(datetime.timezone.utc).isoformat()}
(out / 'command.json').write_text(json.dumps(record, indent=2) + '\n')
started = time.monotonic()
with (out / 'console.log').open('w') as log:
    result = subprocess.run(argv, cwd=out, stdout=log, stderr=subprocess.STDOUT)
record.update(exit_code=result.returncode, wall_seconds=time.monotonic() - started,
              ended_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
(out / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
print(json.dumps(record))
sys.exit(result.returncode)
