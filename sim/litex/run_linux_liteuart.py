#!/usr/bin/env python3
"""Build/run the bounded Linux-profile LiteUART CSR and PLIC smoke tests."""

import argparse
import os
import subprocess
import sys


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SOFTWARE_ROOT = os.path.join(FLOW_ROOT, "software", "breeze-mcu")
SIM_ENTRY = os.path.join(FLOW_ROOT, "sim", "litex", "multicore_sim.py")
TRACE_PREFIXES = ("[MEM-RETIRE]", "[DCACHE-", "[WB-")
TESTS = {
    "csr": os.path.join(SOFTWARE_ROOT, "apps", "linux_liteuart_smoke.c"),
    "plic": os.path.join(
        SOFTWARE_ROOT, "apps", "linux_liteuart_plic_smoke.c"),
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
        description="Run a bounded Linux-profile LiteUART validation.")
    parser.add_argument("--test", choices=sorted(TESTS), default="csr",
        help="CSR data-path or PLIC source-10 interrupt test (default: csr).")
    parser.add_argument("--cross-compile", default="riscv64-unknown-elf-")
    parser.add_argument("--core-preset", choices=("baseline", "gshare"),
        default="gshare")
    parser.add_argument("--elaborate", action="store_true",
        help="Regenerate the single-hart Linux-profile cluster RTL.")
    parser.add_argument("--mcu-timeout", type=int, default=200000)
    parser.add_argument("--output-dir",
        help="LiteX output directory (default: one directory per test).")
    parser.add_argument("--mem-trace-file",
        help="Trace output path (default: <output-dir>/memory-trace.log).")
    parser.add_argument("--mem-trace-max-events", type=int, default=64)
    args = parser.parse_args()

    if args.mcu_timeout <= 0:
        parser.error("--mcu-timeout must be greater than zero")
    if args.mem_trace_max_events <= 0:
        parser.error("--mem-trace-max-events must be greater than zero")
    if args.output_dir is None:
        args.output_dir = os.path.join(
            FLOW_ROOT, "build", f"linux-liteuart-{args.test}-single")

    if args.elaborate:
        sbt = os.environ.get("SBT", "sbt")
        run_checked([
            sbt,
            "runMain flow.top.GenerateBreezeMulticoreClusterWishbone "
            f"single {args.core_preset} linux",
        ], cwd=os.path.join(FLOW_ROOT, "design"))

    firmware_build = os.path.join(
        SOFTWARE_ROOT, "build", f"linux-liteuart-{args.test}")
    firmware_prefix = os.path.join(firmware_build, "breeze-mcu")
    run_checked([
        "make", "-B", "-C", SOFTWARE_ROOT,
        f"BUILD_DIR={firmware_build}",
        f"MAIN={TESTS[args.test]}",
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
        "--test-name", f"liteuart-{args.test}",
        "--rom-init", firmware_prefix + ".bin",
        "--mcu-result-address", hex(result_address),
        "--mcu-perf-address", hex(perf_address),
        "--mcu-timeout", str(args.mcu_timeout),
        "--output-dir", os.path.abspath(args.output_dir),
        "--non-interactive",
        "--mem-trace",
        "--mem-trace-address-start", (
            "0x0c000000" if args.test == "plic" else "0x12001000"),
        "--mem-trace-address-end", (
            "0x10000000" if args.test == "plic" else "0x12002000"),
        "--mem-trace-max-events", str(args.mem_trace_max_events),
        "--build",
    ]
    print("+", " ".join(command), flush=True)
    return_code, output = run_streaming(command, cwd=FLOW_ROOT)
    trace_file = os.path.abspath(args.mem_trace_file or os.path.join(
        args.output_dir, "memory-trace.log"))
    os.makedirs(os.path.dirname(trace_file), exist_ok=True)
    with open(trace_file, "w", encoding="utf-8") as handle:
        for line in output.splitlines():
            if line.startswith(TRACE_PREFIXES):
                handle.write(line + "\n")
    print(f"BREEZE_MEMORY_TRACE path={trace_file}", flush=True)
    if return_code != 0:
        raise SystemExit(return_code)

    pass_marker = f"[MULTICORE-SINGLE-LITEUART-{args.test.upper()}-PASS]"
    if pass_marker not in output:
        print(
            f"ERROR: simulator did not report {pass_marker}",
            file=sys.stderr,
        )
        raise SystemExit(1)


if __name__ == "__main__":
    main()
