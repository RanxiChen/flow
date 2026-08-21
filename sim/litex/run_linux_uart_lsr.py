#!/usr/bin/env python3
"""Build and run the single-hart Linux-profile UART LSR smoke test."""

import argparse
import os
import subprocess
import sys


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SOFTWARE_ROOT = os.path.join(FLOW_ROOT, "software", "breeze-mcu")
MAIN_SOURCE = os.path.join(SOFTWARE_ROOT, "apps", "linux_uart_lsr_smoke.c")
SIM_ENTRY = os.path.join(FLOW_ROOT, "sim", "litex", "multicore_sim.py")


def run_checked(command, cwd=None):
    print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=cwd, check=True)


def read_symbol(symbol_file, name):
    with open(symbol_file, encoding="utf-8") as symbols:
        for line in symbols:
            fields = line.split()
            if len(fields) >= 3 and fields[-1] == name:
                return int(fields[0], 16)
    raise RuntimeError(f"missing symbol {name!r} in {symbol_file}")


def run_streaming(command, cwd=None):
    process = subprocess.Popen(
        command, cwd=cwd, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True, bufsize=1)
    captured = []
    assert process.stdout is not None
    for line in process.stdout:
        print(line, end="")
        captured.append(line)
    return process.wait(), "".join(captured)


def main():
    parser = argparse.ArgumentParser(
        description="Run an end-to-end CPU LBU from the Linux ns16550a LSR.")
    parser.add_argument("--cross-compile", default="riscv64-unknown-elf-")
    parser.add_argument("--core-preset", choices=("baseline", "gshare"),
        default="gshare")
    parser.add_argument("--elaborate", action="store_true",
        help="Regenerate the single-hart Linux-profile cluster RTL.")
    parser.add_argument("--mcu-timeout", type=int, default=200000)
    parser.add_argument("--output-dir", default=os.path.join(
        FLOW_ROOT, "build", "linux-uart-lsr-single"))
    args = parser.parse_args()

    if args.mcu_timeout <= 0:
        parser.error("--mcu-timeout must be greater than zero")

    if args.elaborate:
        sbt = os.environ.get("SBT", "sbt")
        run_checked([
            sbt,
            "runMain flow.top.GenerateBreezeMulticoreClusterWishbone "
            f"single {args.core_preset} linux",
        ], cwd=os.path.join(FLOW_ROOT, "design"))

    firmware_build = os.path.join(
        SOFTWARE_ROOT, "build", "linux-uart-lsr")
    firmware_prefix = os.path.join(firmware_build, "breeze-mcu")
    run_checked([
        "make", "-B", "-C", SOFTWARE_ROOT,
        f"BUILD_DIR={firmware_build}",
        f"MAIN={MAIN_SOURCE}",
        "LINK_SCRIPT=link-linux.ld",
        f"CROSS_COMPILE={args.cross_compile}",
    ])

    result_address = read_symbol(firmware_prefix + ".sym", "__breeze_result")
    perf_address = read_symbol(
        firmware_prefix + ".sym", "__breeze_pmu_snapshot")
    command = [
        sys.executable, SIM_ENTRY,
        "--profile", "single",
        "--core-preset", args.core_preset,
        "--privilege", "linux",
        "--test-name", "uart-lsr",
        "--rom-init", firmware_prefix + ".bin",
        "--mcu-result-address", hex(result_address),
        "--mcu-perf-address", hex(perf_address),
        "--mcu-timeout", str(args.mcu_timeout),
        "--output-dir", os.path.abspath(args.output_dir),
        "--non-interactive",
        "--build",
    ]
    print("+", " ".join(command), flush=True)
    return_code, output = run_streaming(command, cwd=FLOW_ROOT)
    if return_code != 0:
        raise SystemExit(return_code)

    pass_marker = "[MULTICORE-SINGLE-UART-LSR-PASS]"
    if pass_marker not in output:
        print(
            f"ERROR: simulator did not report {pass_marker}",
            file=sys.stderr,
        )
        raise SystemExit(1)


if __name__ == "__main__":
    main()
