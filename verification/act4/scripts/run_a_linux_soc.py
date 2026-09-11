#!/usr/bin/env python3
"""Run only ACT Zaamo and Zalrsc ELFs on the full Flow Linux SoC."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", choices=("single", "dual", "small"), default="small")
    parser.add_argument("--elf-dir", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--max-cycles", type=int, default=2_000_000)
    parser.add_argument("--jobs", type=int, default=20)
    parser.add_argument("--fresh-build", action="store_true")
    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    act_root = script_dir.parent
    flow_root = act_root.parents[1]
    elf_root = Path(args.elf_dir).resolve()
    output_root = Path(args.output_dir).resolve()
    shared_build = output_root / "soc-build"
    case_dir = output_root / "cases"
    case_dir.mkdir(parents=True, exist_ok=True)

    elfs = sorted(
        path for extension in ("Zaamo", "Zalrsc")
        for path in elf_root.rglob(f"{extension}/*.elf")
        if path.is_file())
    if not elfs:
        raise ValueError(f"no Zaamo/Zalrsc ELFs below {elf_root}")

    subprocess.run(["make"], cwd=act_root / "platform", check=True)
    driver = flow_root / "sim" / "litex" / "act4_linux_soc.py"
    records = []
    needs_compile = args.fresh_build or not (
        shared_build / "gateware" / "obj_dir" / "Vsim").is_file()

    for index, elf in enumerate(elfs, 1):
        log_path = case_dir / f"{elf.stem}.log"
        command = [
            sys.executable, str(driver),
            "--profile", args.profile,
            "--elf", str(elf),
            "--output-dir", str(shared_build),
            "--max-cycles", str(args.max_cycles),
            "--jobs", str(args.jobs),
            "--run",
        ]
        if needs_compile:
            command.extend(("--elaborate", "--compile"))
        with log_path.open("w", encoding="utf-8") as log:
            completed = subprocess.run(
                command, cwd=flow_root, text=True,
                stdout=log, stderr=subprocess.STDOUT)
        output = log_path.read_text(encoding="utf-8", errors="replace")
        if "[ACT4-SOC-PASS]" in output and completed.returncode == 0:
            status = "PASS"
        elif "[ACT4-SOC-FAIL]" in output:
            status = "FAIL"
        elif "[ACT4-SOC-TIMEOUT]" in output:
            status = "TIMEOUT"
        else:
            status = "INFRA_ERROR"
        record = {
            "test": elf.name,
            "extension": elf.parent.name,
            "status": status,
            "returncode": completed.returncode,
            "elf": str(elf),
            "elf_sha256": sha256(elf),
            "log": str(log_path),
        }
        records.append(record)
        print(f"[{index}/{len(elfs)}] {status} {elf.name}", flush=True)
        needs_compile = False
        if status == "INFRA_ERROR":
            break

    counts = {
        status: sum(record["status"] == status for record in records)
        for status in ("PASS", "FAIL", "TIMEOUT", "INFRA_ERROR")
    }
    summary = {
        "scope": ["Zaamo", "Zalrsc"],
        "selected": len(elfs),
        "ran": len(records),
        "counts": counts,
        "soc": {
            "profile": args.profile,
            "harts": {"single": 1, "dual": 2, "small": 4}[args.profile],
            "core_preset": "gshare",
            "privilege": "linux",
            "memory": "LiteDRAM 256 MiB",
        },
        "tests": records,
    }
    summary_path = output_root / "summary.json"
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"summary": str(summary_path), **counts}, sort_keys=True))
    return 0 if len(records) == len(elfs) and counts["PASS"] == len(elfs) else 1


if __name__ == "__main__":
    raise SystemExit(main())
