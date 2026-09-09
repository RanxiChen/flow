#!/usr/bin/env python3
"""Run a directory of ACT ELFs and write a machine-readable summary."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("elf_dir")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--max-cycles", type=int, default=1_000_000)
    parser.add_argument("--match", default="*.elf")
    parser.add_argument("--keep-going", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    script_dir = Path(__file__).resolve().parent
    elf_dir = Path(args.elf_dir).resolve()
    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    elfs = sorted(path for path in elf_dir.rglob(args.match) if path.is_file())
    if not elfs:
        raise ValueError(f"no ELFs matching {args.match} under {elf_dir}")

    records: list[dict[str, object]] = []
    for elf in elfs:
        command = [
            sys.executable,
            str(script_dir / "run_one.py"),
            str(elf),
            "--output-dir", str(output_dir),
            "--max-cycles", str(args.max_cycles),
        ]
        completed = subprocess.run(command, text=True, capture_output=True)
        try:
            record = json.loads(completed.stdout.strip().splitlines()[-1])
        except (IndexError, json.JSONDecodeError):
            record = {
                "test": elf.name,
                "status": "INFRA_ERROR",
                "returncode": completed.returncode,
                "stderr": completed.stderr,
            }
        records.append(record)
        print(f"[{len(records)}/{len(elfs)}] {record['status']} {elf.name}", flush=True)
        if record["status"] != "PASS" and not args.keep_going:
            break

    counts = {
        status: sum(record["status"] == status for record in records)
        for status in ("PASS", "FAIL", "INFRA_ERROR")
    }
    summary = {"selected": len(elfs), "ran": len(records), "counts": counts, "tests": records}
    summary_path = output_dir / "summary.json"
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"summary": str(summary_path), **counts}, sort_keys=True))
    return 0 if len(records) == len(elfs) and counts["FAIL"] == 0 and counts["INFRA_ERROR"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
