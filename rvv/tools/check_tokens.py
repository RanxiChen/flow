#!/usr/bin/env python3
"""Serial correctness gate. Stop at the first failure; collect no profile data."""
import json
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
run = Path(sys.argv[1]).resolve()
qemu = Path.home() / "opt/act4/gcc-2026.07.15/bin/qemu-riscv64"
out = run / "correctness"
out.mkdir(exist_ok=True)
results = []

def execute(name, args):
    command = ["nice", "-n", "10"] + [str(x) for x in args]
    print("COMMAND", json.dumps(command), flush=True)
    with (out / (name + ".json")).open("w") as stdout, (out / (name + ".log")).open("w") as stderr:
        result = subprocess.run(command, stdout=stdout, stderr=stderr)
    if result.returncode:
        raise RuntimeError(f"{name}: exit={result.returncode}; see {out / (name + '.log')}")
    return json.loads((out / (name + ".json")).read_text())

selected = sys.argv[2:] or ["q4_0", "q8_0"]
if any(q not in ("q4_0", "q8_0") for q in selected):
    raise SystemExit("Optional quant arguments must be q4_0 and/or q8_0")
for quant in selected:
    model = run / f"models/qwen2.5-0.5b-instruct-{quant}.gguf"
    ref = execute(f"{quant}-native", [run / "build/native/r01-runner", model, root / "prompt.txt"])
    for mode, vlen in [("rvv", v) for v in (128, 256, 512, 1024)] + [("scalar", 128)]:
        name = f"{quant}-{mode}-vlen{vlen}"
        cpu = f"rv64,v=true,vlen={vlen},elen=64,vext_spec=v1.0" if mode == "rvv" else "rv64,v=false"
        actual = execute(name, [qemu, "-cpu", cpu,
                                run / f"build/{mode}/r01-runner", model, root / "prompt.txt"])
        match = actual == ref and len(actual["token_ids"]) == 16
        results.append({"run": name, "match": match, "reference": ref, "actual": actual})
        (out / "comparison.json").write_text(json.dumps(results, indent=2) + "\n")
        print(name, "PASS" if match else "FAIL", flush=True)
        if not match:
            raise SystemExit("STOP: token IDs differ. R01 2.1 prohibits further profiling.")
print("CORRECTNESS_GATE_PASS", flush=True)
