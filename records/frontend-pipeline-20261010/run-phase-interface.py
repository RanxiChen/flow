#!/usr/bin/env python3
"""Run the phase-boundary contract test on the validated simulation host."""
import datetime, hashlib, json, pathlib, shutil, subprocess, time
root = pathlib.Path('/home/chen/FUN/flow-runs/frontend-pipeline-20261010')
out = root / 'phase-interface-02'
out.mkdir(exist_ok=False)
shutil.copy2(root / 'phase-interface.sv', out / 'phase-interface.sv')
shutil.copy2(root / 'route-09' / 'source-manifest.json', out / 'source-manifest.json')
argv = ['/usr/local/bin/verilator', '--binary', '--timing', '-Wno-fatal', '--top-module',
        'phase_interface_test', '--Mdir', str(out / 'obj'), '-j', '4', str(out / 'phase-interface.sv')]
record = {'host': 'Alan', 'cwd': str(out), 'compile_argv': argv,
          'source_digest': json.loads((out / 'source-manifest.json').read_text())['source_digest'],
          'test_sha256': hashlib.sha256((out / 'phase-interface.sv').read_bytes()).hexdigest(),
          'host_selection': {'cloud_chen': 'finite 8s SSH connection timeout; exit 255',
                             'Alan': 'flow activated; 57 GB free disk; 47 GB available memory'},
          'verilator': subprocess.check_output(['/usr/local/bin/verilator', '--version'], text=True).strip(),
          'started_utc': datetime.datetime.now(datetime.timezone.utc).isoformat()}
(out / 'command.json').write_text(json.dumps(record, indent=2) + '\n')
started = time.monotonic()
with (out / 'console.log').open('w') as log:
    compiled = subprocess.run(argv, cwd=out, stdout=log, stderr=subprocess.STDOUT)
    record['compile_exit_code'] = compiled.returncode
    if compiled.returncode == 0:
        record['run_argv'] = [str(out / 'obj' / 'Vphase_interface_test')]
        result = subprocess.run(record['run_argv'], cwd=out, stdout=log, stderr=subprocess.STDOUT)
        record['exit_code'] = result.returncode
    else:
        record['exit_code'] = compiled.returncode
record.update(wall_seconds=time.monotonic() - started,
              ended_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
(out / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
assert record['exit_code'] == 0, record
assert 'PHASE_INTERFACE_PASS' in (out / 'console.log').read_text()
print(json.dumps(record))
