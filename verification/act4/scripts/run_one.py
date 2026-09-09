#!/usr/bin/env python3
"""Run one ACT ELF on BreezeCoreSimApp and preserve its evidence."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("elf")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--core-preset", choices=("baseline", "gshare"), default="baseline")
    parser.add_argument("--max-cycles", type=int, default=1_000_000)
    parser.add_argument("--tandem-log", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.max_cycles <= 0:
        raise ValueError("--max-cycles must be greater than zero")

    script_dir = Path(__file__).resolve().parent
    flow_root = script_dir.parents[2]
    elf = Path(args.elf).resolve()
    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    case_json = output_dir / f"{elf.stem}.json"
    log_path = output_dir / f"{elf.stem}.log"
    toolchain_root = Path(os.environ.get(
        "ACT4_TOOLCHAIN_ROOT", "/home/chen/opt/act4/gcc-2026.07.15"))
    nm_executable = toolchain_root / "bin" / "riscv64-unknown-elf-nm"
    if not nm_executable.is_file():
        nm_executable = Path("riscv64-unknown-elf-nm")

    convert_command = [
        sys.executable,
        str(script_dir / "elf_to_memory_json.py"),
        "--elf", str(elf),
        "--output", str(case_json),
        "--max-cycles", str(args.max_cycles),
        "--tandem-log", "true" if args.tandem_log else "false",
        "--nm", str(nm_executable),
    ]
    subprocess.run(convert_command, check=True)

    default_sbt = Path("/home/chen/.sdkman/candidates/sbt/current/bin/sbt")
    sbt_executable = os.environ.get(
        "ACT4_SBT", str(default_sbt) if default_sbt.is_file() else "sbt")
    sim_env = os.environ.copy()
    default_java_home = Path("/home/chen/.sdkman/candidates/java/current")
    if "JAVA_HOME" not in sim_env and default_java_home.is_dir():
        sim_env["JAVA_HOME"] = str(default_java_home)
    sim_command = [
        sbt_executable,
        "runMain flow.sim.BreezeCoreSimApp "
        f"{case_json} {args.core_preset} linux",
    ]
    completed = subprocess.run(
        sim_command,
        cwd=flow_root / "design",
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        env=sim_env,
    )
    log_path.write_text(completed.stdout, encoding="utf-8")
    passed = completed.returncode == 0 and "exitCode=0x1" in completed.stdout
    record = {
        "test": elf.name,
        "elf": str(elf),
        "json": str(case_json),
        "log": str(log_path),
        "returncode": completed.returncode,
        "status": "PASS" if passed else "FAIL",
    }
    print(json.dumps(record, sort_keys=True))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
