#!/usr/bin/env python3
"""Bounded BIOS smoke driver: captures real UART memtest evidence, no Linux."""
import argparse
import json
import os
import re
import selectors
import signal
import shlex
import shutil
import subprocess
import sys
import time
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--profile', required=True, choices=('single', 'small'))
    p.add_argument('--evidence-dir', required=True)
    args = p.parse_args()
    root = Path(__file__).resolve().parents[2]
    start_sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    evidence = Path(args.evidence_dir).resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    command = [sys.executable, '-u', str(root / 'sim/litex/multicore_sim.py'),
        '--profile', args.profile, '--output-dir', str(evidence / 'build'), '--build', '--non-interactive']
    # Keep the vendor assertion checks enabled explicitly. A private shim
    # supplies the repository's existing FPnew file-scoped compatibility rule.
    real_verilator = shutil.which('verilator')
    if real_verilator is None:
        raise RuntimeError('Verilator is required')
    shim_dir = evidence / 'tools'
    shim_dir.mkdir(exist_ok=True)
    shim = shim_dir / 'verilator'
    shim.write_text('#!/bin/sh\nexec ' + shlex.quote(real_verilator) +
        ' --assert ' + shlex.quote(str(root / 'sim/verilator/cvfpu.vlt')) + ' "$@"\n')
    shim.chmod(0o755)
    child_env = dict(os.environ)
    child_env['PATH'] = str(shim_dir) + os.pathsep + child_env.get('PATH', '')
    start = time.monotonic()
    child = subprocess.Popen(command, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             start_new_session=True, env=child_env)
    selector = selectors.DefaultSelector()
    selector.register(child.stdout, selectors.EVENT_READ)
    captured = ''
    passed = False
    outcome = 'FAIL'
    with open(evidence / 'uart-build.log', 'wb') as log:
        while time.monotonic() - start < 1800:
            for key, _ in selector.select(timeout=1):
                chunk = os.read(key.fileobj.fileno(), 65536)
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                log.write(chunk)
                log.flush()
                captured += chunk.decode('utf-8', errors='replace')
            if re.search(r'%Error|Assertion failed|assertion failed|Memory initialization failed|Memtest KO', captured):
                break
            # Success is based only on actual firmware UART output. Require
            # the console after memtest, so later initialization errors survive.
            if ('Memtest OK' in captured and 'litex>' in captured and
                    '(c) Copyright 2007-2015 M-Labs' in captured and
                    'Build your hardware, easily!' in captured):
                passed = True
                outcome = 'PASS'
                break
            if child.poll() is not None and not selector.get_map():
                break
        else:
            outcome = 'TIMEOUT'
        if child.poll() is None:
            os.killpg(child.pid, signal.SIGTERM)
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()
        # Drain the owned child's final diagnostics and reject late assertions.
        tail = child.stdout.read()
        log.write(tail)
        captured += tail.decode('utf-8', errors='replace')
    if re.search(r'%Error|Assertion failed|assertion failed|Memory initialization failed|Memtest KO', captured):
        passed, outcome = False, 'FAIL'
    metadata = {'profile': args.profile, 'sha': start_sha,
        'cwd': str(root), 'command': command, 'elapsed_seconds': time.monotonic() - start,
        'wall_limit_seconds': 1800, 'verilator_real': real_verilator, 'assertions_enabled': True, 'memtest_bytes': 65536, 'result': outcome,
        'child_exit': child.returncode, 'runner_exit': 0 if passed else 1}
    (evidence / 'result.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(json.dumps(metadata, indent=2), flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
