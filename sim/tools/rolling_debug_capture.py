#!/usr/bin/env python3
"""Run a Flow simulator while keeping bounded, atomic debug tail files."""

import argparse
import collections
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path


CYCLE_RE = re.compile(r"\[FLOW-CYCLE\] cycle=(\d+)")
HART_RE = re.compile(
    r"h(?P<hart>\d+)n=(?P<count>\d+) "
    r"h(?P=hart)pc=0x(?P<pc>[0-9a-fA-F]+)"
)


def atomic_write(path, text):
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(text, encoding="utf-8")
    os.replace(temporary, path)


def write_lines(path, lines):
    atomic_write(path, "".join(lines))


class RollingCapture:
    def __init__(self, args):
        self.args = args
        self.output = Path(args.output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.snapshots = self.output / "snapshots"
        self.snapshots.mkdir(exist_ok=True)
        self.cycle_lines = collections.deque(maxlen=args.cycle_lines)
        self.retire_lines = collections.deque(maxlen=args.retire_lines)
        self.bus_lines = collections.deque(maxlen=args.bus_lines)
        self.started = time.monotonic()
        self.last_flush = 0.0
        self.current_cycle = 0
        self.retire_counts = {}
        self.last_pcs = {}
        self.last_total_retire = 0
        self.last_retire_cycle = 0
        self.stall_snapshot_cycle = None
        self.line_counts = collections.Counter()
        self.stop_requested = False
        self.child = None
        self.exit_code = None
        self.console = (self.output / "console.log").open(
            "a", encoding="utf-8", buffering=1)
        self.alerts = (self.output / "alerts.log").open(
            "a", encoding="utf-8", buffering=1)

    def request_stop(self, signum, _frame):
        self.stop_requested = True
        if self.child is not None and self.child.poll() is None:
            self.child.send_signal(signum)

    def classify(self, line):
        if line.startswith("[FLOW-CYCLE]"):
            self.cycle_lines.append(line)
            self.line_counts["cycle"] += 1
            self.update_status_from_cycle(line)
            return
        if (line.startswith("[FLOW-EVENT] kind=R") or
                line.startswith("[MEM-RETIRE]")):
            self.retire_lines.append(line)
            self.line_counts["retire"] += 1
            return
        if (line.startswith("[FLOW-EVENT]") or line.startswith("[WB-") or
                line.startswith("[DCACHE-") or line.startswith("[IRQ-")):
            self.bus_lines.append(line)
            self.line_counts["bus"] += 1
            return

        self.console.write(line)
        sys.stdout.write(line)
        sys.stdout.flush()
        self.line_counts["console"] += 1
        fatal_text = any(marker in line for marker in (
            "[LINUX-FATAL]", "[CORE-TRAP]", "Kernel panic", "Oops:",
        ))
        kernel_bug = (
            line.startswith("BUG:") or re.search(r"\]\s+BUG:", line) is not None
        )
        if fatal_text or kernel_bug:
            self.alerts.write(line)
            self.freeze_snapshot("fatal")

    def update_status_from_cycle(self, line):
        match = CYCLE_RE.search(line)
        if match is None:
            return
        self.current_cycle = int(match.group(1))
        for hart_match in HART_RE.finditer(line):
            hart = int(hart_match.group("hart"))
            self.retire_counts[hart] = int(hart_match.group("count"))
            self.last_pcs[hart] = "0x" + hart_match.group("pc").lower()

        total_retire = sum(self.retire_counts.values())
        if total_retire != self.last_total_retire:
            self.last_total_retire = total_retire
            self.last_retire_cycle = self.current_cycle
            self.stall_snapshot_cycle = None
        elif (self.args.stall_cycles and self.current_cycle >=
                self.last_retire_cycle + self.args.stall_cycles and
                self.stall_snapshot_cycle is None):
            self.stall_snapshot_cycle = self.current_cycle
            self.alerts.write(
                f"[STALL] cycle={self.current_cycle} no retirement for "
                f"{self.current_cycle - self.last_retire_cycle} cycles\n")
            self.freeze_snapshot("stall")

    def status(self):
        wall = max(time.monotonic() - self.started, 1e-9)
        child_status = None if self.child is None else self.child.poll()
        return {
            "runner_pid": os.getpid(),
            "simulator_pid": (
                None if self.child is None else self.child.pid),
            "running": self.child is not None and child_status is None,
            "stop_requested": self.stop_requested,
            "exit_code": (
                self.exit_code if self.exit_code is not None else child_status),
            "cycle": self.current_cycle,
            "wall_seconds": wall,
            "cycles_per_wall_second": self.current_cycle / wall,
            "simulated_seconds_50mhz": self.current_cycle / 50_000_000,
            "simulated_to_wall_ratio": (
                self.current_cycle / 50_000_000 / wall),
            "retire_counts": {
                str(hart): count
                for hart, count in sorted(self.retire_counts.items())
            },
            "last_pcs": {
                str(hart): pc
                for hart, pc in sorted(self.last_pcs.items())
            },
            "last_retire_cycle": self.last_retire_cycle,
            "line_counts": dict(self.line_counts),
            "buffers": {
                "cycle": len(self.cycle_lines),
                "retire": len(self.retire_lines),
                "bus": len(self.bus_lines),
            },
        }

    def flush(self, force=False):
        now = time.monotonic()
        if not force and now - self.last_flush < self.args.flush_seconds:
            return
        write_lines(self.output / "cycle-tail.log", self.cycle_lines)
        write_lines(self.output / "retire-tail.log", self.retire_lines)
        write_lines(self.output / "bus-tail.log", self.bus_lines)
        atomic_write(
            self.output / "status.json",
            json.dumps(self.status(), indent=2, sort_keys=True) + "\n")
        self.last_flush = now

    def freeze_snapshot(self, reason):
        self.flush(force=True)
        target = self.snapshots / f"{reason}-{self.current_cycle}"
        if target.exists():
            return
        target.mkdir()
        for name in (
                "cycle-tail.log", "retire-tail.log", "bus-tail.log",
                "status.json"):
            source = self.output / name
            if source.exists():
                shutil.copy2(source, target / name)

    def run(self):
        command = list(self.args.command)
        if command and command[0] == "--":
            command = command[1:]
        if not command:
            raise SystemExit("a simulator command is required after --")
        if shutil.which("stdbuf"):
            command = ["stdbuf", "-oL", "-eL", *command]
        self.child = subprocess.Popen(
            command,
            cwd=self.args.cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            errors="replace",
            bufsize=1,
        )
        assert self.child.stdout is not None
        try:
            for line in self.child.stdout:
                self.classify(line)
                self.flush()
            self.exit_code = self.child.wait()
            return self.exit_code
        finally:
            if self.exit_code is None and self.child is not None:
                self.exit_code = self.child.poll()
            self.flush(force=True)
            self.console.close()
            self.alerts.close()


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--cwd")
    parser.add_argument("--cycle-lines", type=int, default=512)
    parser.add_argument("--retire-lines", type=int, default=2048)
    parser.add_argument("--bus-lines", type=int, default=2048)
    parser.add_argument("--stall-cycles", type=int, default=5_000_000)
    parser.add_argument("--flush-seconds", type=float, default=1.0)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    for name in ("cycle_lines", "retire_lines", "bus_lines"):
        if getattr(args, name) <= 0:
            parser.error(f"--{name.replace('_', '-')} must be positive")
    if args.flush_seconds <= 0:
        parser.error("--flush-seconds must be positive")
    return args


def main():
    args = parse_args()
    capture = RollingCapture(args)
    signal.signal(signal.SIGINT, capture.request_stop)
    signal.signal(signal.SIGTERM, capture.request_stop)
    return capture.run()


if __name__ == "__main__":
    raise SystemExit(main())
