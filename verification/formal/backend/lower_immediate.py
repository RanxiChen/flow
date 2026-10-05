#!/usr/bin/env python3
"""Lower diagnostic action blocks for Yosys, preserving every property expression.

The raw firtool output remains untouched. No assertion, assumption, cover or
clock/reset guard is removed. Only `else $error("diagnostic")` becomes `;`.
Fail closed if a different action syntax appears. Save counts and diagnostics
so the transformation can be reviewed independently of the proof result.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

ACTION = re.compile(
    r'\b((?:assert|assume)\s*\([\s\S]*?\))\s*'
    r'else\s*\$error\(\s*("(?:\\.|[^"\\])*")\s*\)\s*;'
)
def lower(source: str):
    diagnostics = []

    def replace(match):
        diagnostics.append({"expression": match[1], "diagnostic": match[2]})
        return match[1] + ";"

    lowered = ACTION.sub(replace, source)
    before = {kind: len(re.findall(r'\b' + kind + r'\s*\(', source))
              for kind in ("assert", "assume", "cover")}
    after = {kind: len(re.findall(r'\b' + kind + r'\s*\(', lowered))
             for kind in before}
    if before != after or "else $error" in lowered:
        raise ValueError("Unsupported action or changed property count")
    return lowered, {"properties": before, "diagnostics": diagnostics}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("raw", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    audit = {}
    for path in sorted(args.raw.glob("*.sv")):
        raw = path.read_text()
        lowered, record = lower(raw)
        record["raw_sha256"] = hashlib.sha256(raw.encode()).hexdigest()
        record["lowered_sha256"] = hashlib.sha256(lowered.encode()).hexdigest()
        (args.output / path.name).write_text(lowered)
        audit[path.name] = record
    (args.output / "lowering-audit.json").write_text(json.dumps(audit, indent=2) + "\n")


if __name__ == "__main__":
    main()
