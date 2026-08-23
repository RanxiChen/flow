#!/usr/bin/env python3
"""Run linux_sim.py while separating compact events from the console log."""

import argparse
import os
import signal
import subprocess
import sys


SIM_DIR = os.path.dirname(os.path.abspath(__file__))
EVENT_PREFIX = "[FLOW-EVENT] "


def main():
    parser = argparse.ArgumentParser(
        description="Capture a complete compact Flow event history.")
    parser.add_argument("--event-trace-file", required=True)
    args, linux_args = parser.parse_known_args()
    if "--compact-event-trace" not in linux_args:
        linux_args.append("--compact-event-trace")

    trace_path = os.path.abspath(args.event_trace_file)
    os.makedirs(os.path.dirname(trace_path), exist_ok=True)
    command = [sys.executable, os.path.join(SIM_DIR, "linux_sim.py"), *linux_args]
    print("BREEZE_EVENT_TRACE path={}".format(trace_path), flush=True)
    print("+ {}".format(" ".join(command)), flush=True)

    child = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    event_count = 0
    try:
        with open(trace_path, "w", buffering=1024 * 1024) as trace:
            trace.write("# Flow compact event trace v1\n")
            trace.write("# command={}\n".format(" ".join(command)))
            assert child.stdout is not None
            for line in child.stdout:
                if line.startswith(EVENT_PREFIX):
                    trace.write(line[len(EVENT_PREFIX):])
                    event_count += 1
                    if (event_count & 0xfff) == 0:
                        trace.flush()
                else:
                    print(line, end="", flush=True)
    except KeyboardInterrupt:
        child.send_signal(signal.SIGINT)
    finally:
        if child.poll() is None:
            child.wait()
    print(
        "BREEZE_EVENT_TRACE_COMPLETE path={} events={} exit={}".format(
            trace_path, event_count, child.returncode),
        flush=True,
    )
    raise SystemExit(child.returncode)


if __name__ == "__main__":
    main()
