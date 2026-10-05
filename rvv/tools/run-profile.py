#!/usr/bin/env python3
"""Run serial, nice'd profiles only after all token comparisons passed."""
import csv
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import subprocess
import sys
import threading

root = Path(__file__).resolve().parents[1]
run = Path(sys.argv[1]).resolve()
qemu = Path.home() / "opt/act4/gcc-2026.07.15/bin/qemu-riscv64"
comparisons = json.loads((run / "correctness/comparison.json").read_text())
expected = {f"{q}-{mode}-vlen{vl}" for q in ("q4_0", "q8_0")
            for mode, vl in [("scalar", 128)] + [("rvv", v) for v in (128, 256, 512, 1024)]}
if {r["run"] for r in comparisons if r["match"]} != expected or not all(r["match"] for r in comparisons):
    raise SystemExit("Correctness gate incomplete or failed; profiling prohibited")
parser = argparse.ArgumentParser()
parser.add_argument("run")
parser.add_argument("case", nargs="?")
parser.add_argument("repeat", nargs="?", choices=["repeat"])
parser.add_argument("--jobs", type=int, default=1)
parser.add_argument("--resume", action="store_true")
parser.add_argument("--exclude", choices=sorted(expected))
options = parser.parse_args()
if not 1 <= options.jobs <= min(4, max(1, __import__("os").cpu_count() // 2)):
    raise SystemExit("R01 profiling permits at most four workers and half the CPUs")
selected = options.case
suffix = "-repeat" if options.repeat else ""
if selected is not None and selected not in expected:
    raise SystemExit("Unknown profile case")

active = set()
lock = threading.Lock()
failed = threading.Event()

def execute(case):
    try:
        return measure(case)
    except BaseException:
        with lock:
            failed.set()
            for process in active:
                process.terminate()
        raise

def measure(case):
        if failed.is_set():
            raise RuntimeError("Profiling stopped after a failed case")
        quant, mode, vlen = case
        name = f"{quant}-{mode}-vlen{vlen}"
        cpu = f"rv64,v=true,vlen={vlen},elen=64,vext_spec=v1.0" if mode == "rvv" else "rv64,v=false"
        prefix = run / "profile" / (name + suffix)
        plugin = f'{run}/profile/profile.so,symbols={run}/profile/{mode}.symbols,output={prefix}'
        args = ["nice", "-n", "10", str(qemu), "-cpu", cpu, "-plugin", plugin,
                str(run / f"build/{mode}/r01-runner"),
                str(run / f"models/qwen2.5-0.5b-instruct-{quant}.gguf"), str(root / "prompt.txt")]
        exit_path = Path(str(prefix) + ".exit")
        resumed = options.resume and exit_path.exists() and exit_path.read_text().strip() == "0"
        if not resumed:
            print("COMMAND", json.dumps(args), flush=True)
            with Path(str(prefix) + ".json").open("w") as out, Path(str(prefix) + ".log").open("w") as err:
                with lock:
                    if failed.is_set():
                        raise RuntimeError("Profiling stopped after a failed case")
                    process = subprocess.Popen(args, stdout=out, stderr=err)
                    active.add(process)
                status = process.wait()
                with lock:
                    active.remove(process)
            exit_path.write_text(str(status) + "\n")
            if status:
                raise RuntimeError(f"Profile failed: {name} exit={status}")
        actual = json.loads(Path(str(prefix) + ".json").read_text())
        ref = json.loads((run / f"correctness/{quant}-native.json").read_text())
        if actual != ref:
            raise SystemExit(f"STOP: instrumented {name} token IDs differ")
        rows = list(csv.DictReader(Path(str(prefix) + "-functions.csv").open()))
        if mode == "scalar" and sum(int(r["vector"]) for r in rows):
            raise SystemExit("Scalar control unexpectedly executes vector instructions")
        print("PROFILE_PASS", name, flush=True)

cases = [(q, m, v) for q in ("q4_0", "q8_0")
         for m, v in [("scalar", 128)] + [("rvv", v) for v in (128, 256, 512, 1024)]
         if (selected is None or f"{q}-{m}-vlen{v}" == selected)
         and f"{q}-{m}-vlen{v}" != options.exclude]
with ThreadPoolExecutor(max_workers=options.jobs) as pool:
    list(pool.map(execute, cases))
