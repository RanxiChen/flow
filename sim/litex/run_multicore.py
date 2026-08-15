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

The default core preset is gshare. The runner prints and validates the
BREEZE_CLUSTER configuration marker, requires the per-test
[MULTICORE-<PROFILE>-<TEST>-PASS] marker, fails on FAIL/fatal/assertion,
applies a finite watchdog, and exits non-zero on any missing marker or
non-zero subprocess.

Tests are registered phase by phase as their firmware/oracle land (T2+).
"""

import argparse
import os
import re
import subprocess
import sys


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
FATAL_PATTERN = re.compile(r"\b(fatal|assertion)\b", re.IGNORECASE)

# Test registry. Each entry is a callable run(args, profile, output_dir)
# returning the completed subprocess exit code; it must stream output.
# Registered phase by phase (T2 onward); empty during P0.
TEST_REGISTRY = {}


def run_checked(command, cwd=None):
    print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=cwd, check=True)


def run_streaming(command, cwd=None, timeout=None):
    print("+", " ".join(command), flush=True)
    try:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
    except FileNotFoundError as exc:
        print(f"ERROR: cannot launch {command[0]}: {exc}", file=sys.stderr)
        return 127, ""
    captured = []
    assert process.stdout is not None
    for line in process.stdout:
        print(line, end="")
        captured.append(line)
    return_code = process.wait(timeout=timeout)
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
    except subprocess.CalledProcessError:
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
        help="LiteX output directory; defaults to a per-profile/preset directory.")
    args = parser.parse_args()

    if args.timeout <= 0:
        parser.error("--timeout must be greater than zero")

    marker = build_cluster_marker(args.profile, args.core_preset)
    print(marker, flush=True)
    validate_marker(marker, args.profile, args.core_preset)
    print(f"BREEZE_GIT_SHA {git_short_sha()}", flush=True)

    output_dir = os.path.abspath(args.output_dir or os.path.join(
        FLOW_ROOT, "build", "litex-cluster", args.profile, args.core_preset))

    if args.elaborate:
        run_checked([
            "sbt",
            f"runMain flow.top.GenerateBreezeMulticoreClusterWishbone "
            f"{args.profile} {args.core_preset}",
        ], cwd=DESIGN_DIR)
        # The elaborated profile marker must match this invocation exactly.
        profile_values = read_cluster_profile(args.profile, args.core_preset)
        expected_profile = {
            "profile": args.profile,
            "numHarts": str(PROFILES[args.profile]["numHarts"]),
            "l1iBytes": "8192",
            "l1dBytes": "8192",
            "l2Bytes": str(PROFILES[args.profile]["l2Bytes"]),
            "lineBytes": "32",
            "l1Ways": "4",
            "l2Ways": "8",
            "corePreset": args.core_preset,
        }
        mismatches = {
            key: (profile_values.get(key), value)
            for key, value in expected_profile.items()
            if profile_values.get(key) != value
        }
        if mismatches:
            detail = ", ".join(f"{key}: expected={value} actual={actual}"
                               for key, (actual, value) in sorted(mismatches.items()))
            raise SystemExit(f"ERROR: cluster-profile.txt mismatch: {detail}")

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

    run_test = TEST_REGISTRY[args.test]
    return_code, output = run_streaming(
        run_test(args, output_dir), cwd=FLOW_ROOT, timeout=args.timeout)
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
