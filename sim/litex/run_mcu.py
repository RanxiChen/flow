#!/usr/bin/env python3
"""Build a Breeze MCU application and run it to finite completion."""

import argparse
import os
import subprocess
import sys


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SOFTWARE_ROOT = os.path.join(FLOW_ROOT, "software", "breeze-mcu")
SIM_ENTRY = os.path.join(FLOW_ROOT, "sim", "litex", "breeze_sim.py")

SMOKE_APPS = {
    "timer": os.path.join(SOFTWARE_ROOT, "apps", "timer_irq_smoke.c"),
    "uart": os.path.join(SOFTWARE_ROOT, "apps", "uart_irq_smoke.c"),
}
INTERRUPT_CAUSES = {"timer": 7, "uart": 11}


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
    return process.wait(), "".join(captured)


def main():
    parser = argparse.ArgumentParser(
        description="Build and run a Breeze MCU main.c to finite completion.")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--main", help="Application main.c (default: template apps/main.c).")
    source.add_argument("--smoke", choices=("timer", "uart"),
        help="Run a directed interrupt smoke application.")
    parser.add_argument("--mtvec-mode", choices=("direct", "vectored"),
        default="direct", help="Trap mode used by the runtime (default: direct).")
    parser.add_argument("--cross-compile", default="riscv64-unknown-elf-",
        help="Bare-metal tool prefix (default: riscv64-unknown-elf-).")
    parser.add_argument("--elaborate", action="store_true",
        help="Regenerate BreezeCoreWishbone RTL before simulation.")
    parser.add_argument("--trace", action="store_true",
        help="Enable the LiteX/Verilator waveform trace.")
    parser.add_argument("--mcu-timeout", type=int, default=20000,
        help="Simulation watchdog in cycles (default: 20000).")
    parser.add_argument("--output-dir",
        help="LiteX output directory; defaults to a per-application build directory.")
    args = parser.parse_args()

    if args.mcu_timeout <= 0:
        parser.error("--mcu-timeout must be greater than zero")

    if args.smoke:
        main_source = SMOKE_APPS[args.smoke]
        check_kind = args.smoke
        app_name = f"{args.smoke}-irq"
    else:
        main_source = os.path.abspath(
            args.main or os.path.join(SOFTWARE_ROOT, "apps", "main.c"))
        check_kind = "generic"
        app_name = os.path.splitext(os.path.basename(main_source))[0]

    if not os.path.isfile(main_source):
        parser.error(f"main source does not exist: {main_source}")

    mode_number = "1" if args.mtvec_mode == "vectored" else "0"
    firmware_build = os.path.join(
        SOFTWARE_ROOT, "build", f"{app_name}-{args.mtvec_mode}")
    firmware_prefix = os.path.join(firmware_build, "breeze-mcu")
    output_dir = os.path.abspath(args.output_dir or os.path.join(
        FLOW_ROOT, "build", f"litex-mcu-{app_name}-{args.mtvec_mode}"))

    if args.elaborate:
        run_checked(["sbt", "elaborate"], cwd=os.path.join(FLOW_ROOT, "design"))

    run_checked([
        "make", "-B", "-C", SOFTWARE_ROOT,
        f"BUILD_DIR={firmware_build}",
        f"MAIN={main_source}",
        f"MTVEC_MODE={mode_number}",
        f"CROSS_COMPILE={args.cross_compile}",
    ])

    trap_vector = read_symbol(firmware_prefix + ".sym", "breeze_trap_vector")
    result_address = read_symbol(firmware_prefix + ".sym", "__breeze_result")
    sim_command = [
        sys.executable,
        SIM_ENTRY,
        "--rom-init", firmware_prefix + ".bin",
        "--check-mcu-completion", check_kind,
        "--mcu-result-address", hex(result_address),
        "--mtvec-mode", args.mtvec_mode,
        "--mcu-timeout", str(args.mcu_timeout),
        "--output-dir", output_dir,
        "--non-interactive",
        "--build",
    ]
    if check_kind in INTERRUPT_CAUSES:
        sim_command += ["--expected-trap-vector", hex(trap_vector)]
    if args.trace:
        sim_command.append("--trace")

    return_code, output = run_streaming(sim_command, cwd=FLOW_ROOT)
    if return_code != 0:
        raise SystemExit(return_code)

    pass_marker = f"[{check_kind.upper()}-PASS] MCU firmware completed"
    if pass_marker not in output:
        print(f"ERROR: simulator did not report {pass_marker!r}", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
