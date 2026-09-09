#!/usr/bin/env python3
"""Run a selected ACT suite on the full Flow Linux SoC."""

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
    parser.add_argument("--elf-dir", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--max-cycles", type=int, default=2_000_000)
    parser.add_argument("--jobs", type=int, default=20)
    parser.add_argument("--fresh-build", action="store_true")
    parser.add_argument("--include-extension", action="append", default=[])
    parser.add_argument("--exclude-extension", action="append", default=[])
    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    act_root = script_dir.parent
    flow_root = act_root.parents[1]
    elf_root = Path(args.elf_dir).resolve()
    output_root = Path(args.output_dir).resolve()
    shared_build = output_root / "soc-build"
    case_dir = output_root / "cases"
    case_dir.mkdir(parents=True, exist_ok=True)

    include_extensions = set(args.include_extension)
    exclude_extensions = set(args.exclude_extension)
    overlap = include_extensions & exclude_extensions
    if overlap:
        raise ValueError(
            "extensions cannot be both included and excluded: "
            + ", ".join(sorted(overlap)))
    elfs = [path for path in elf_root.rglob("*.elf") if path.is_file()]
    if include_extensions:
        elfs = [path for path in elfs if path.parent.name in include_extensions]
    elfs = [path for path in elfs if path.parent.name not in exclude_extensions]
    extension_order = {
        "I": 0,
        "Zifencei": 1,
        "Zicsr": 2,
        "Zca": 3,
        "Zcd": 4,
        "F": 5,
        "D": 6,
        "M": 7,
        "Zmmul": 8,
    }
    elfs.sort(key=lambda path: (
        extension_order.get(path.parent.name, 100),
        path.parent.name,
        path.name,
    ))
    if not elfs:
        raise ValueError(f"no selected ACT ELFs below {elf_root}")

    subprocess.run(["make"], cwd=act_root / "platform", check=True)
    driver = flow_root / "sim" / "litex" / "act4_linux_soc.py"
    records = []
    selected_extensions = sorted({path.parent.name for path in elfs})

    def write_summary():
        counts = {
            status: sum(record["status"] == status for record in records)
            for status in ("PASS", "FAIL", "TIMEOUT", "INFRA_ERROR")
        }
        summary = {
            "scope": selected_extensions,
            "excluded": sorted(exclude_extensions),
            "selected": len(elfs),
            "ran": len(records),
            "counts": counts,
            "soc": {
                "profile": "small",
                "harts": 4,
                "core_preset": "gshare",
                "privilege": "linux",
                "memory": "LiteDRAM 256 MiB",
            },
            "tests": records,
        }
        summary_path = output_root / "summary.json"
        summary_path.write_text(
            json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        return counts, summary_path

    print(
        f"ACT4_SOC_SELECTION tests={len(elfs)} "
        f"extensions={','.join(selected_extensions)} "
        f"excluded={','.join(sorted(exclude_extensions)) or 'none'}",
        flush=True)
    needs_compile = args.fresh_build or not (
        shared_build / "gateware" / "obj_dir" / "Vsim").is_file()

    for index, elf in enumerate(elfs, 1):
        log_path = case_dir / f"{elf.stem}.log"
        command = [
            sys.executable, str(driver),
            "--elf", str(elf),
            "--output-dir", str(shared_build),
            "--max-cycles", str(args.max_cycles),
            "--jobs", str(args.jobs),
            "--run",
        ]
        if needs_compile:
            command.extend(("--elaborate", "--compile"))
        completed = subprocess.run(
            command, cwd=flow_root, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        log_path.write_text(completed.stdout, encoding="utf-8")
        if "[ACT4-SOC-PASS]" in completed.stdout:
            status = "PASS"
        elif "[ACT4-SOC-FAIL]" in completed.stdout:
            status = "FAIL"
        elif "[ACT4-SOC-TIMEOUT]" in completed.stdout:
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
        counts, summary_path = write_summary()
        print(f"[{index}/{len(elfs)}] {status} {elf.name}", flush=True)
        needs_compile = False
        if status == "INFRA_ERROR":
            break

    counts, summary_path = write_summary()
    print(json.dumps({"summary": str(summary_path), **counts}, sort_keys=True))
    return 0 if len(records) == len(elfs) and counts["PASS"] == len(elfs) else 1


if __name__ == "__main__":
    raise SystemExit(main())
