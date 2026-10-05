#!/usr/bin/env python3
"""Run serial, nice'd profiles only after all token comparisons passed."""
import csv
import json
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
run = Path(sys.argv[1]).resolve()
qemu = Path.home() / "opt/act4/gcc-2026.07.15/bin/qemu-riscv64"
comparisons = json.loads((run / "correctness/comparison.json").read_text())
expected = {f"{q}-{mode}-vlen{vl}" for q in ("q4_0", "q8_0")
            for mode, vl in [("scalar", 128)] + [("rvv", v) for v in (128, 256, 512, 1024)]}
if {r["run"] for r in comparisons if r["match"]} != expected or not all(r["match"] for r in comparisons):
    raise SystemExit("Correctness gate incomplete or failed; profiling prohibited")

for quant in ("q4_0", "q8_0"):
    for mode, vlen in [("scalar", 128)] + [("rvv", v) for v in (128, 256, 512, 1024)]:
        name = f"{quant}-{mode}-vlen{vlen}"
        cpu = f"rv64,v=true,vlen={vlen},elen=64,vext_spec=v1.0" if mode == "rvv" else "rv64,v=false"
        prefix = run / "profile" / name
        plugin = f'{run}/profile/profile.so,symbols={run}/profile/{mode}.symbols,output={prefix}'
        args = ["nice", "-n", "10", str(qemu), "-cpu", cpu, "-plugin", plugin,
                str(run / f"build/{mode}/r01-runner"),
                str(run / f"models/qwen2.5-0.5b-instruct-{quant}.gguf"), str(root / "prompt.txt")]
        print("COMMAND", json.dumps(args), flush=True)
        with Path(str(prefix) + ".json").open("w") as out, Path(str(prefix) + ".log").open("w") as err:
            result = subprocess.run(args, stdout=out, stderr=err)
        Path(str(prefix) + ".exit").write_text(str(result.returncode) + "\n")
        if result.returncode:
            raise SystemExit(f"Profile failed: {name} exit={result.returncode}")
        actual = json.loads(Path(str(prefix) + ".json").read_text())
        ref = json.loads((run / f"correctness/{quant}-native.json").read_text())
        if actual != ref:
            raise SystemExit(f"STOP: instrumented {name} token IDs differ")
        rows = list(csv.DictReader(Path(str(prefix) + "-functions.csv").open()))
        if mode == "scalar" and sum(int(r["vector"]) for r in rows):
            raise SystemExit("Scalar control unexpectedly executes vector instructions")
        print("PROFILE_PASS", name, flush=True)
