#!/usr/bin/env python3
"""Extract the cycle-window counters printed by ZveGemvQ4Spec."""

import argparse
import json
import re
from pathlib import Path


HEADER = re.compile(
    r"\+ (\w+) pass=(\w+) totalCycles=(\d+) kernelCycles=(\d+) "
    r"blocks=(\d+) weightBytes=(\d+) maxErrorBits=([0-9a-f]+) "
    r"replay=([0-9,]*) soloCycles=(\d+) zvePerf=(.*)"
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("log", type=Path)
    args = parser.parse_args()
    result = {}
    for line in args.log.read_text().splitlines():
        match = HEADER.search(line)
        if not match:
            continue
        name, passed, total, cycles, blocks, weight, error, replay, solo, perf_text = match.groups()
        perf = {key: int(value) for key, value in re.findall(r"\(([^(),]+),(-?\d+)\)", perf_text)}
        cycles, blocks, weight = int(cycles), int(blocks), int(weight)
        phase = perf.get("phase_cycles", 0)
        result[name] = {
            "pass": passed == "true",
            "total_cycles": int(total),
            "kernel_cycles": cycles,
            "blocks": blocks,
            "weight_bytes": weight,
            "cycles_per_block": cycles / blocks,
            "weight_bytes_per_cycle": weight / cycles,
            "max_error_bits_hex": error,
            "replay_by_reason": [int(x) for x in replay.split(",") if x],
            "whole_run_solo_cycles": int(solo),
            "phase_cycles": phase,
            "busy_pct": {unit: 100 * perf.get(unit + "_busy_cycles", 0) / phase
                         for unit in ("alu", "mac", "conversion", "fma", "stream")}
                         if phase else {},
            "perf": perf,
        }
    if not result:
        raise SystemExit(f"no benchmark results found in {args.log}")
    if "scalar" in result:
        scalar = result["scalar"]["cycles_per_block"]
        for name, entry in result.items():
            if name != "scalar":
                entry["speedup_vs_scalar_per_block"] = scalar / entry["cycles_per_block"]
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
