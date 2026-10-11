#!/usr/bin/env python3
import datetime, hashlib, json, os, pathlib, shutil, subprocess, sys, time

root = pathlib.Path('/home/chen/FUN/flow-frontend-pipeline-20261010')
evidence = pathlib.Path('/home/chen/FUN/flow-runs/frontend-pipeline-20261010')
name, command = sys.argv[1:3]
out = evidence / name
out.mkdir(parents=True, exist_ok=False)
manifest = json.loads((root / 'source-manifest.json').read_text())
for item in manifest['files']:
    assert hashlib.sha256((root / item['path']).read_bytes()).hexdigest() == item['sha256'], item['path']
if command == 'runMain flow.frontend.GenerateBreezeFrontendTimingHarness --target-dir ../rtl-timing':
    # Earlier candidates retain immutable RTL copies under evidence/route-xx.
    # Emit into an empty task-owned directory so no obsolete modules survive.
    shutil.rmtree(root / 'rtl-timing', ignore_errors=True)
java = '/home/chen/miniforge3/pkgs/openjdk-20.0.2-haa376d0_2/lib/jvm/bin/java'
launcher = '/home/chen/miniforge3/pkgs/sbt-1.9.7-hd8ed1ab_0/share/sbt/bin/sbt-launch.jar'
argv = [java, '-Xmx8g', '-XX:ActiveProcessorCount=4', '-Dsbt.supershell=false', '-Dsbt.server.autostart=false', '-jar', launcher, command]
record = {'host': 'Alan', 'base_sha': manifest['base_sha'], 'source_digest': manifest['source_digest'],
          'cwd': str(root / 'design'), 'argv': argv, 'started_utc': datetime.datetime.now(datetime.timezone.utc).isoformat()}
(out / 'command.json').write_text(json.dumps(record, indent=2) + '\n')
(out / 'source-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
start = time.monotonic()
report_start = time.time()
with (out / 'log.txt').open('w') as log:
    result = subprocess.run(argv, cwd=root / 'design', stdout=log, stderr=subprocess.STDOUT)
reports = root / 'design/target/test-reports'
if reports.exists():
    fresh_reports = [p for p in reports.glob('*.xml') if p.stat().st_mtime >= report_start]
    if fresh_reports:
        (out / 'test-reports').mkdir()
        for report in fresh_reports:
            shutil.copy2(report, out / 'test-reports' / report.name)
record.update(exit_code=result.returncode, wall_seconds=time.monotonic() - start,
              ended_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
(out / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
print(json.dumps(record))
sys.exit(result.returncode)
