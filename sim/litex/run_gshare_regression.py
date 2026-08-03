#!/usr/bin/env python3
"""Run one MCU firmware through baseline and GShare LiteX simulations."""

import argparse
import hashlib
import os
import re
import subprocess
import sys


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RUN_MCU = os.path.join(FLOW_ROOT, "sim", "litex", "run_mcu.py")
SOFTWARE_ROOT = os.path.join(FLOW_ROOT, "software", "breeze-mcu")
PMU_PATTERN = re.compile(
    r"BREEZE_PMU cycles=\s*([0-9]+) instructions=\s*([0-9]+)"
)
IPC_PATTERN = re.compile(
    r"BREEZE_IPC cycles=([0-9]+) instructions=([0-9]+) ipc=([0-9.]+)"
)


def stream(command, cwd):
    print("+", " ".join(command), flush=True)
    process = subprocess.Popen(
        command,
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    captured = []
    assert process.stdout is not None
    for line in process.stdout:
        print(line, end="")
        captured.append(line)
    return_code = process.wait()
    output = "".join(captured)
    if return_code != 0:
        raise RuntimeError(
            f"MCU simulation failed with exit code {return_code}: "
            f"{' '.join(command)}"
        )
    return output


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as input_file:
        for block in iter(lambda: input_file.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_metrics(output, preset, check_kind):
    config_marker = f"BREEZE_CONFIG core_preset={preset}"
    pass_marker = f"[{check_kind.upper()}-PASS] MCU firmware completed"
    if config_marker not in output:
        raise RuntimeError(f"missing simulator marker: {config_marker}")
    if pass_marker not in output:
        raise RuntimeError(f"missing completion marker: {pass_marker}")

    pmu_match = PMU_PATTERN.search(output)
    ipc_match = IPC_PATTERN.search(output)
    if pmu_match is None or ipc_match is None:
        raise RuntimeError(f"missing PMU/IPC output for {preset}")

    pmu_cycles = int(pmu_match.group(1), 10)
    pmu_instructions = int(pmu_match.group(2), 10)
    ipc_cycles = int(ipc_match.group(1), 10)
    ipc_instructions = int(ipc_match.group(2), 10)
    if (pmu_cycles, pmu_instructions) != (ipc_cycles, ipc_instructions):
        raise RuntimeError(f"PMU/IPC count mismatch for {preset}")

    return {
        "cycles": pmu_cycles,
        "instructions": pmu_instructions,
        "ipc": float(ipc_match.group(3)),
    }


def main():
    parser = argparse.ArgumentParser(
        description="Compare baseline and GShare with the same MCU firmware.")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--main", help="Application main.c (default: apps/main.c).")
    source.add_argument("--smoke", choices=("timer", "uart"),
        help="Run an interrupt smoke application.")
    parser.add_argument("--mtvec-mode", choices=("direct", "vectored"),
        default="direct", help="Trap mode used by both simulations.")
    parser.add_argument("--cross-compile", default="riscv64-unknown-elf-",
        help="Bare-metal tool prefix.")
    parser.add_argument("--elaborate", action="store_true",
        help="Regenerate each selected RTL preset before simulation.")
    parser.add_argument("--mcu-timeout", type=int, default=20000,
        help="Simulation watchdog passed to run_mcu.py.")
    parser.add_argument("--output-root",
        help="Root for independent baseline/GShare simulation products.")
    args = parser.parse_args()

    if args.mcu_timeout <= 0:
        parser.error("--mcu-timeout must be greater than zero")

    if args.smoke:
        app_name = f"{args.smoke}-irq"
        check_kind = args.smoke
        source_args = ["--smoke", args.smoke]
    else:
        main_source = os.path.abspath(
            args.main or os.path.join(SOFTWARE_ROOT, "apps", "main.c"))
        if not os.path.isfile(main_source):
            parser.error(f"main source does not exist: {main_source}")
        app_name = os.path.splitext(os.path.basename(main_source))[0]
        check_kind = "generic"
        source_args = ["--main", main_source]

    firmware_bin = os.path.join(
        SOFTWARE_ROOT,
        "build",
        f"{app_name}-{args.mtvec_mode}",
        "breeze-mcu.bin",
    )
    output_root = os.path.abspath(args.output_root or os.path.join(
        FLOW_ROOT,
        "build",
        "litex-gshare-regression",
        f"{app_name}-{args.mtvec_mode}",
    ))

    results = {}
    firmware_hashes = {}
    for preset in ("baseline", "gshare"):
        command = [
            sys.executable,
            RUN_MCU,
            *source_args,
            "--mtvec-mode", args.mtvec_mode,
            "--cross-compile", args.cross_compile,
            "--core-preset", preset,
            "--mcu-timeout", str(args.mcu_timeout),
            "--output-dir", os.path.join(output_root, preset),
        ]
        if args.elaborate:
            command.append("--elaborate")

        output = stream(command, cwd=FLOW_ROOT)
        results[preset] = parse_metrics(output, preset, check_kind)
        firmware_hashes[preset] = sha256_file(firmware_bin)

    if firmware_hashes["baseline"] != firmware_hashes["gshare"]:
        raise RuntimeError("baseline and GShare did not run the same firmware binary")

    if check_kind == "generic" and (
        results["baseline"]["instructions"] != results["gshare"]["instructions"]
    ):
        raise RuntimeError(
            "generic baseline/GShare retired-instruction counts differ: "
            f"{results['baseline']['instructions']} != "
            f"{results['gshare']['instructions']}"
        )

    print(
        "BREEZE_GSHARE_REGRESSION "
        f"app={app_name} mtvec={args.mtvec_mode} "
        f"firmware_sha256={firmware_hashes['baseline']} PASS"
    )
    for preset in ("baseline", "gshare"):
        metrics = results[preset]
        print(
            "BREEZE_GSHARE_RESULT "
            f"preset={preset} cycles={metrics['cycles']} "
            f"instructions={metrics['instructions']} ipc={metrics['ipc']:.6f}"
        )


if __name__ == "__main__":
    main()
