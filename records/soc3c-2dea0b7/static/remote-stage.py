import datetime, json, os, pathlib, shutil, signal, subprocess, sys, time

repo = pathlib.Path(sys.argv[1]).resolve()
expected_sha, stage, command = sys.argv[2:]
sha = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip()
assert sha == expected_sha, (sha, expected_sha)
assert not subprocess.check_output(['git', '-C', str(repo), 'status', '--porcelain'], text=True).strip()
root = pathlib.Path('/home/cloud_chen/evidence') / ('soc3c-' + sha[:7]) / stage
root.mkdir(parents=True, exist_ok=False)
meta = dict(source_sha=sha, host=subprocess.check_output(['hostname'], text=True).strip(),
            ssh_target='cloud_chen@47.96.71.231', cwd=str(repo / 'design'), command=command,
            started_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
            cvfpu_sha=subprocess.check_output(['git', '-C', str(repo / 'third_party/cvfpu'), 'rev-parse', 'HEAD'], text=True).strip())
(root / 'command.txt').write_text(command + '\n')
(root / 'running.json').write_text(json.dumps(meta, indent=2) + '\n')
start = time.monotonic()
with (root / 'run.log').open('w') as log:
    p = subprocess.Popen(['bash', '-lc', 'source /home/cloud_chen/setup/activate-flow.sh\n' + command],
                         cwd=repo / 'design', stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    (root / 'pid').write_text(str(p.pid) + '\n')
    try:
        code = p.wait(timeout=3600)
    except subprocess.TimeoutExpired:
        os.killpg(p.pid, signal.SIGTERM)
        try: p.wait(timeout=15)
        except subprocess.TimeoutExpired: os.killpg(p.pid, signal.SIGKILL); p.wait()
        code = 124
meta.update(exit_code=code, elapsed_seconds=time.monotonic() - start,
            ended_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
(root / 'exit').write_text(str(code) + '\n')
for f in (repo / 'design/target/test-reports').glob('*.xml'):
    if f.stat().st_mtime >= time.time() - meta['elapsed_seconds'] - 5:
        (root / 'xml').mkdir(exist_ok=True)
        shutil.copy2(f, root / 'xml' / f.name)
(root / 'result.json').write_text(json.dumps(meta, indent=2) + '\n')
print(json.dumps(meta, indent=2), flush=True)
sys.exit(code)
