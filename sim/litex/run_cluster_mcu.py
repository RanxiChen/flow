#!/usr/bin/env python3
"""Build a Breeze MCU-style application and run it on a cluster profile.

Thin driver behind run_multicore.py's TEST_REGISTRY: rebuilds the firmware,
prints the firmware SHA, then execs multicore_sim.py with inherited stdio so
every simulation marker reaches the invoking runner unfiltered. The runner
owns marker/fatal validation; this driver only propagates the exit code.
"""

import argparse
import hashlib
import os
import subprocess
import sys


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SOFTWARE_ROOT = os.path.join(FLOW_ROOT, "software", "breeze-mcu")
SIM_ENTRY = os.path.join(FLOW_ROOT, "sim", "litex", "multicore_sim.py")

# Single-profile MCU tests (P2). Multi-hart tests register with their phases.
TEST_APPS = {
    "boot": os.path.join(SOFTWARE_ROOT, "apps", "multicore_boot.c"),
    "generic": os.path.join(SOFTWARE_ROOT, "apps", "main.c"),
    "l2-eviction": os.path.join(SOFTWARE_ROOT, "apps", "l2_eviction.c"),
}


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


def main():
    parser = argparse.ArgumentParser(
        description="Build and run one cluster MCU test application.")
    parser.add_argument("--profile", required=True,
        help="Cluster profile (single/dual/small).")
    parser.add_argument("--core-preset", default="gshare",
        help="Core RTL preset (default: gshare).")
    parser.add_argument("--test", required=True, choices=sorted(TEST_APPS),
        help="Registered test name.")
    parser.add_argument("--cross-compile", default="riscv64-unknown-elf-",
        help="Bare-metal tool prefix (default: riscv64-unknown-elf-).")
    parser.add_argument("--mcu-timeout", type=int, default=20000,
        help="Simulation completion watchdog in cycles (default: 20000).")
    parser.add_argument("--output-dir", required=True,
        help="LiteX output directory (one per profile/preset/test).")
    parser.add_argument("--trace", action="store_true",
        help="Enable the LiteX/Verilator waveform trace.")
    args = parser.parse_args()

    main_source = TEST_APPS[args.test]
    if not os.path.isfile(main_source):
        parser.error(f"test application does not exist: {main_source}")

    firmware_build = os.path.join(
        SOFTWARE_ROOT, "build",
        f"cluster-{args.test}-{args.profile}-{args.core_preset}")
    firmware_prefix = os.path.join(firmware_build, "breeze-mcu")

    run_checked([
        "make", "-B", "-C", SOFTWARE_ROOT,
        f"BUILD_DIR={firmware_build}",
        f"MAIN={main_source}",
        "MTVEC_MODE=0",
        f"CROSS_COMPILE={args.cross_compile}",
    ])

    with open(firmware_prefix + ".bin", "rb") as firmware_file:
        firmware_sha = hashlib.sha256(firmware_file.read()).hexdigest()[:16]
    print(f"BREEZE_FIRMWARE_SHA {firmware_sha} "
          f"app={args.test} profile={args.profile}", flush=True)

    result_address = read_symbol(firmware_prefix + ".sym", "__breeze_result")
    perf_address = read_symbol(firmware_prefix + ".sym", "__breeze_pmu_snapshot")

    sim_command = [
        sys.executable,
        SIM_ENTRY,
        "--profile", args.profile,
        "--core-preset", args.core_preset,
        "--test-name", args.test,
        "--rom-init", firmware_prefix + ".bin",
        "--mcu-result-address", hex(result_address),
        "--mcu-perf-address", hex(perf_address),
        "--mcu-timeout", str(args.mcu_timeout),
        "--output-dir", args.output_dir,
        "--non-interactive",
        "--build",
    ]
    if args.trace:
        sim_command.append("--trace")

    print("+", " ".join(sim_command), flush=True)
    # Inherited stdio: simulation markers flow straight to the caller.
    process = subprocess.run(sim_command, cwd=FLOW_ROOT)
    return process.returncode


if __name__ == "__main__":
    sys.exit(main())
