#!/usr/bin/env python3
"""Unified 1/2/4-core Breeze cluster runner.

Frozen CLI (multicore-1-2-4 specification section 22):

  python3 sim/litex/run_multicore.py \
      --profile single|dual|small \
      --test <test-name> \
      [--core-preset gshare|baseline] \
      [--elaborate] \
      [--trace] \
      [--timeout N] \
      [--output-dir PATH]

The default core preset is gshare. The runner prints the BREEZE_CLUSTER
configuration marker, requires the elaborated cluster-profile.txt on disk
to match the requested profile/preset (never the marker it printed
itself), requires the per-test [MULTICORE-<PROFILE>-<TEST>-PASS] marker,
fails on FAIL/fatal/assertion, applies a finite wall-clock watchdog over
the whole subprocess lifetime (SIGKILL on timeout, exit code 124), and
exits non-zero on any missing marker or non-zero subprocess.

Tests are registered phase by phase as their firmware/oracle land (T2+).
"""

import argparse
import os
import queue
import re
import signal
import subprocess
import sys
import threading
import time


FLOW_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DESIGN_DIR = os.path.join(FLOW_ROOT, "design")

# Frozen 1/2/4-core profile table. L2 bytes = numHarts * 2 * L1D bytes.
PROFILES = {
    "single": {"numHarts": 1, "l2Bytes": 16384},
    "dual": {"numHarts": 2, "l2Bytes": 32768},
    "small": {"numHarts": 4, "l2Bytes": 65536},
}
CORE_PRESETS = ("gshare", "baseline")

CLUSTER_MARKER_PATTERN = re.compile(
    r"BREEZE_CLUSTER profile=(\S+) harts=(\d+) core_preset=(\S+) "
    r"l1i_bytes=(\d+) l1d_bytes=(\d+) l2_bytes=(\d+) line_bytes=(\d+) "
    r"l1_ways=(\d+) l2_ways=(\d+)"
)
PASS_MARKER_PATTERN = re.compile(r"\[MULTICORE-(\S+)-(\S+)-PASS\]")
FAIL_MARKER_PATTERN = re.compile(r"\[MULTICORE-(\S+)-(\S+)-FAIL\]")
# Real simulation aborts: Verilator runtime errors ($stop/assert fires) and
# compiler fatal errors. Deliberately NOT the bare words "fatal"/"assertion":
# the Verilator command line itself carries -Wno-fatal, which must not trip
# the check.
FATAL_PATTERN = re.compile(r"%Error|assertion failed|fatal error:", re.IGNORECASE)

# Dedicated watchdog exit code, matching timeout(1) semantics.
TIMEOUT_EXIT_CODE = 124

# Test registry. Each entry is a callable run(args, output_dir) returning the
# command list for run_streaming; the command must stream the simulation
# output (markers included) to its own stdout. Registered phase by phase
# (T2+); single-profile MCU tests land with P2, dual/small with theirs.
CLUSTER_MCU_ENTRY = os.path.join(FLOW_ROOT, "sim", "litex", "run_cluster_mcu.py")


def _cluster_mcu_test(test_name, allowed_profiles):
    """TEST_REGISTRY factory with an explicit phase/profile allowlist."""
    def run(args, output_dir):
        if args.profile not in allowed_profiles:
            raise SystemExit(
                f"ERROR: test {test_name!r} is not registered for profile "
                f"{args.profile!r}; allowed profiles: {', '.join(allowed_profiles)}")
        command = [
            sys.executable, CLUSTER_MCU_ENTRY,
            "--profile", args.profile,
            "--core-preset", args.core_preset,
            "--test", test_name,
            "--output-dir", output_dir,
        ]
        if args.trace:
            command.append("--trace")
        return command
    return run


TEST_REGISTRY = {
    "boot": _cluster_mcu_test("boot", ("single", "dual", "small")),
    "generic": _cluster_mcu_test("generic", ("single",)),
    "l2-eviction": _cluster_mcu_test("l2-eviction", ("single",)),
    "sharing": _cluster_mcu_test("sharing", ("dual",)),
    "upgrade": _cluster_mcu_test("upgrade", ("dual",)),
    "dirty-read": _cluster_mcu_test("dirty-read", ("dual",)),
    "dirty-transfer": _cluster_mcu_test("dirty-transfer", ("dual",)),
    "same-line": _cluster_mcu_test("same-line", ("dual",)),
    "same-line-race": _cluster_mcu_test("same-line-race", ("dual",)),
    "small-sharing": _cluster_mcu_test("small-sharing", ("small",)),
    "small-upgrade": _cluster_mcu_test("small-upgrade", ("small",)),
    "small-dirty-transfer": _cluster_mcu_test("small-dirty-transfer", ("small",)),
    "small-same-line": _cluster_mcu_test("small-same-line", ("small",)),
    "small-same-line-race": _cluster_mcu_test("small-same-line-race", ("small",)),
    "small-l2-eviction": _cluster_mcu_test("small-l2-eviction", ("small",)),
    # RV64A: the directed vectors and an uncontended LR/SC run on every
    # profile; the contention shapes need at least two harts.
    "amo-directed": _cluster_mcu_test("amo-directed", ("single", "dual", "small")),
    "amo-contention": _cluster_mcu_test("amo-contention", ("dual", "small")),
    "lrsc-success": _cluster_mcu_test("lrsc-success", ("single", "dual", "small")),
    "lrsc-fail": _cluster_mcu_test("lrsc-fail", ("dual", "small")),
    # CLINT: the single profile exercises the self-IPI / own-timer shapes.
    "ipi": _cluster_mcu_test("ipi", ("single", "dual", "small")),
    "remote-fencei": _cluster_mcu_test("remote-fencei", ("single", "dual", "small")),
    "per-hart-timer": _cluster_mcu_test("per-hart-timer", ("single", "dual", "small")),
}


def run_checked(command, cwd=None):
    print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=cwd, check=True)


def run_streaming(command, cwd=None, timeout=None):
    """Run *command*, forwarding its combined stdout/stderr line by line.

    A wall-clock deadline covers the entire subprocess lifetime: if
    *timeout* seconds elapse before the process exits, its process group
    is killed with SIGKILL, the child is reaped, and the output captured
    so far is returned with the dedicated exit code 124. Returns
    (exit_code, captured_output); a failed launch reports 127 instead of
    raising, so every path yields a controlled non-zero code.
    """
    print("+", " ".join(command), flush=True)
    try:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            start_new_session=True,
        )
    except OSError as exc:
        print(f"ERROR: cannot launch {command[0]}: {exc}", file=sys.stderr)
        return 127, ""

    # A reader thread owns the blocking read so the watchdog loop below
    # stays in control even when the child hangs with stdout held open
    # (no EOF ever arrives in that case).
    line_queue = queue.Queue()

    def reader():
        for line in process.stdout:
            line_queue.put(line)

    reader_thread = threading.Thread(target=reader, daemon=True)
    reader_thread.start()

    captured = []

    def drain():
        while True:
            try:
                line = line_queue.get_nowait()
            except queue.Empty:
                return
            print(line, end="", flush=True)
            captured.append(line)

    deadline = None if timeout is None else time.monotonic() + timeout
    timed_out = False
    while True:
        drain()
        if process.poll() is not None:
            break
        if deadline is not None and time.monotonic() >= deadline:
            timed_out = True
            break
        time.sleep(0.02)

    if timed_out:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except OSError:
            try:
                process.kill()
            except OSError:
                pass
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass  # SIGKILL is undeliverable; nothing more we can do.
        return_code = TIMEOUT_EXIT_CODE
    else:
        return_code = process.wait()
    reader_thread.join(timeout=1.0)
    drain()
    return return_code, "".join(captured)


def git_short_sha():
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=FLOW_ROOT,
            capture_output=True,
            text=True,
            check=True,
        )
        return result.stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def build_cluster_marker(profile, core_preset):
    profile_cfg = PROFILES[profile]
    return (
        f"BREEZE_CLUSTER profile={profile} harts={profile_cfg['numHarts']} "
        f"core_preset={core_preset} l1i_bytes=8192 l1d_bytes=8192 "
        f"l2_bytes={profile_cfg['l2Bytes']} line_bytes=32 l1_ways=4 l2_ways=8"
    )


def validate_marker(marker, profile, core_preset):
    match = CLUSTER_MARKER_PATTERN.search(marker)
    if match is None:
        raise RuntimeError("missing BREEZE_CLUSTER marker")
    fields = {
        "profile": match.group(1),
        "harts": int(match.group(2)),
        "core_preset": match.group(3),
        "l1i_bytes": int(match.group(4)),
        "l1d_bytes": int(match.group(5)),
        "l2_bytes": int(match.group(6)),
        "line_bytes": int(match.group(7)),
        "l1_ways": int(match.group(8)),
        "l2_ways": int(match.group(9)),
    }
    expected = {
        "profile": profile,
        "harts": PROFILES[profile]["numHarts"],
        "core_preset": core_preset,
        "l1i_bytes": 8192,
        "l1d_bytes": 8192,
        "l2_bytes": PROFILES[profile]["l2Bytes"],
        "line_bytes": 32,
        "l1_ways": 4,
        "l2_ways": 8,
    }
    mismatches = {
        key: (fields[key], expected[key])
        for key in expected
        if fields[key] != expected[key]
    }
    if mismatches:
        detail = ", ".join(f"{key}: expected={expected[key]} actual={fields[key]}"
                           for key in sorted(mismatches))
        raise RuntimeError(f"BREEZE_CLUSTER marker mismatch: {detail}")
    return fields


def read_cluster_profile(profile, core_preset):
    profile_file = os.path.join(
        DESIGN_DIR, "build", "rtl", "cluster", profile, core_preset,
        "cluster-profile.txt",
    )
    if not os.path.isfile(profile_file):
        raise FileNotFoundError(
            "cluster RTL has not been elaborated. Expected:\n"
            f"  {profile_file}\n"
            "Generate it with:\n"
            f"  cd {DESIGN_DIR} && sbt "
            f'"runMain flow.top.GenerateBreezeMulticoreClusterWishbone {profile} {core_preset}"'
        )
    values = {}
    with open(profile_file, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or "=" not in line:
                continue
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip()
    return values


def expected_cluster_profile(profile, core_preset):
    """Expected cluster-profile.txt contents for a profile/preset pair."""
    return {
        "profile": profile,
        "numHarts": str(PROFILES[profile]["numHarts"]),
        "l1iBytes": "8192",
        "l1dBytes": "8192",
        "l2Bytes": str(PROFILES[profile]["l2Bytes"]),
        "lineBytes": "32",
        "l1Ways": "4",
        "l2Ways": "8",
        "corePreset": core_preset,
    }


def validate_cluster_profile_file(profile, core_preset):
    """The elaborated cluster-profile.txt must match this invocation exactly.

    Shared by the --elaborate path (post-generation check) and the plain
    --test path (pre-run check), so a test can never be validated against
    a marker the runner printed itself. Missing file or any field
    mismatch exits non-zero.
    """
    try:
        profile_values = read_cluster_profile(profile, core_preset)
    except FileNotFoundError as exc:
        raise SystemExit(f"ERROR: {exc}")
    expected_profile = expected_cluster_profile(profile, core_preset)
    mismatches = {
        key: (profile_values.get(key), value)
        for key, value in expected_profile.items()
        if profile_values.get(key) != value
    }
    if mismatches:
        detail = ", ".join(f"{key}: expected={value} actual={actual}"
                           for key, (actual, value) in sorted(mismatches.items()))
        raise SystemExit(f"ERROR: cluster-profile.txt mismatch: {detail}")


def main():
    parser = argparse.ArgumentParser(
        description="Build and run a 1/2/4-hart Breeze cluster test to finite completion.")
    parser.add_argument("--profile", choices=sorted(PROFILES), required=True,
        help="Cluster profile: single (1 hart), dual (2 harts) or small (4 harts).")
    parser.add_argument("--test", help="Registered test name (see TEST_REGISTRY).")
    parser.add_argument("--core-preset", choices=CORE_PRESETS, default="gshare",
        help="Core RTL preset (default: gshare; baseline is explicit).")
    parser.add_argument("--elaborate", action="store_true",
        help="Regenerate the cluster RTL for the profile/preset before running.")
    parser.add_argument("--trace", action="store_true",
        help="Enable the LiteX/Verilator waveform trace.")
    parser.add_argument("--timeout", type=int, default=600,
        help="Simulation watchdog in seconds (default: 600).")
    parser.add_argument("--output-dir",
        help="LiteX output directory; defaults to "
             "build/litex-cluster/<profile>/<preset>[/<test>]/.")
    args = parser.parse_args()

    if args.timeout <= 0:
        parser.error("--timeout must be greater than zero")

    marker = build_cluster_marker(args.profile, args.core_preset)
    print(marker, flush=True)
    try:
        validate_marker(marker, args.profile, args.core_preset)
    except RuntimeError as exc:
        raise SystemExit(f"ERROR: {exc}")
    print(f"BREEZE_GIT_SHA {git_short_sha()}", flush=True)

    output_dir_parts = [FLOW_ROOT, "build", "litex-cluster",
                        args.profile, args.core_preset]
    if args.test is not None:
        output_dir_parts.append(args.test)
    output_dir = os.path.abspath(args.output_dir or os.path.join(*output_dir_parts))

    if args.elaborate:
        try:
            run_checked([
                "sbt",
                f"runMain flow.top.GenerateBreezeMulticoreClusterWishbone "
                f"{args.profile} {args.core_preset}",
            ], cwd=DESIGN_DIR)
        except (OSError, subprocess.CalledProcessError) as exc:
            raise SystemExit(f"ERROR: cluster elaboration failed: {exc}")
        # The elaborated profile marker must match this invocation exactly.
        validate_cluster_profile_file(args.profile, args.core_preset)

    if args.test is None:
        if args.elaborate:
            print("[MULTICORE-ELABORATE-OK] cluster RTL regenerated and marker validated",
                  flush=True)
            return 0
        parser.error("--test is required unless running with --elaborate only")

    if args.test not in TEST_REGISTRY:
        registered = ", ".join(sorted(TEST_REGISTRY)) if TEST_REGISTRY else "(none yet)"
        raise SystemExit(
            f"ERROR: test {args.test!r} is not registered for profile "
            f"{args.profile!r}; registered tests: {registered}"
        )

    if not args.elaborate:
        # Do not self-certify: the cluster profile previously elaborated
        # on disk must match the requested profile/preset before running.
        validate_cluster_profile_file(args.profile, args.core_preset)

    run_test = TEST_REGISTRY[args.test]
    return_code, output = run_streaming(
        run_test(args, output_dir), cwd=FLOW_ROOT, timeout=args.timeout)
    if return_code == TIMEOUT_EXIT_CODE:
        print(f"ERROR: test subprocess timed out (watchdog {args.timeout}s)",
              file=sys.stderr)
        return TIMEOUT_EXIT_CODE
    if return_code != 0:
        raise SystemExit(f"ERROR: test subprocess exited with {return_code}")

    fail_match = FAIL_MARKER_PATTERN.search(output)
    if fail_match is not None:
        raise SystemExit(
            f"ERROR: test reported FAIL profile={fail_match.group(1)} "
            f"test={fail_match.group(2)}")

    pass_marker = f"[MULTICORE-{args.profile.upper()}-{args.test.upper()}-PASS]"
    if pass_marker not in output:
        raise SystemExit(f"ERROR: missing pass marker {pass_marker!r}")

    if FATAL_PATTERN.search(output):
        raise SystemExit("ERROR: fatal/assertion text present in simulation output")

    print(f"{pass_marker} verified (profile={args.profile} "
          f"core_preset={args.core_preset})", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
