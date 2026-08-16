#!/usr/bin/env python3
"""Stdlib-only self-tests for sim/litex/run_multicore.py.

No RTL, litex or sbt is required. Each case executes the real runner end
to end in a subprocess (so exit codes are observed exactly as CI would see
them) via a small wrapper script that:

  * points run_multicore.DESIGN_DIR at a temp directory holding a
    fabricated cluster-profile.txt (satisfying the F-11 startup check), and
  * injects fake tests into run_multicore.TEST_REGISTRY whose commands run
    tiny generated python child scripts producing the desired output shape.

Run with either:
  python3 -m unittest sim/litex/test_run_multicore.py -v
  python3 sim/litex/test_run_multicore.py
"""

import json
import os
import re
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest

SIM_LITEX_DIR = os.path.dirname(os.path.abspath(__file__))

# Executed as `python3 wrapper.py <runner args...>`; imports the real
# runner, injects the fakes from the environment, then calls main() so
# argparse and the exit-code plumbing behave exactly as in production.
WRAPPER_SOURCE = textwrap.dedent(
    """
    import json
    import os
    import sys

    sys.path.insert(0, os.environ["TEST_SIM_LITEX_DIR"])
    import run_multicore

    run_multicore.DESIGN_DIR = os.environ["TEST_DESIGN_DIR"]
    for _name, _command in json.loads(os.environ["TEST_FAKE_REGISTRY"]).items():
        run_multicore.TEST_REGISTRY[_name] = (
            lambda command: (lambda args, output_dir: command))(_command)
    sys.exit(run_multicore.main())
    """
)

# Matches expected_cluster_profile("single", "gshare") in the runner.
VALID_PROFILE_VALUES = {
    "profile": "single",
    "numHarts": "1",
    "l1iBytes": "8192",
    "l1dBytes": "8192",
    "l2Bytes": "16384",
    "lineBytes": "32",
    "l1Ways": "4",
    "l2Ways": "8",
    "corePreset": "gshare",
}

PASS_MARKER = "[MULTICORE-SINGLE-SMOKE-PASS]"


def pid_alive(pid):
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


class RunMulticoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="run-multicore-test-")
        self.addCleanup(self.tmp.cleanup)
        self.design_dir = os.path.join(self.tmp.name, "design")
        self.write_cluster_profile(VALID_PROFILE_VALUES)
        self.wrapper_path = os.path.join(self.tmp.name, "wrapper.py")
        with open(self.wrapper_path, "w", encoding="utf-8") as handle:
            handle.write(WRAPPER_SOURCE)

    def profile_file_path(self):
        return os.path.join(
            self.design_dir, "build", "rtl", "cluster", "single", "gshare",
            "cluster-profile.txt")

    def write_cluster_profile(self, values):
        profile_dir = os.path.dirname(self.profile_file_path())
        os.makedirs(profile_dir, exist_ok=True)
        with open(self.profile_file_path(), "w", encoding="utf-8") as handle:
            for key, value in values.items():
                handle.write(f"{key}={value}\n")

    def write_child(self, name, body):
        path = os.path.join(self.tmp.name, name)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("#!/usr/bin/env python3\n")
            handle.write(textwrap.dedent(body))
        return path

    def run_runner(self, child_command, extra_args=(), wall_timeout=30):
        env = dict(os.environ)
        env["TEST_SIM_LITEX_DIR"] = SIM_LITEX_DIR
        env["TEST_DESIGN_DIR"] = self.design_dir
        env["TEST_FAKE_REGISTRY"] = json.dumps({"smoke": child_command})
        started = time.monotonic()
        result = subprocess.run(
            [sys.executable, self.wrapper_path,
             "--profile", "single", "--test", "smoke", *extra_args],
            capture_output=True, text=True, env=env, timeout=wall_timeout,
        )
        result.elapsed = time.monotonic() - started
        return result

    # -- cases ---------------------------------------------------------

    def test_success_exit_zero(self):
        child = self.write_child("ok.py", f"""
            print("simulation banner")
            print("{PASS_MARKER}")
        """)
        result = self.run_runner([sys.executable, child])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("BREEZE_CLUSTER profile=single harts=1", result.stdout)
        self.assertIn(PASS_MARKER, result.stdout)
        self.assertIn(f"{PASS_MARKER} verified", result.stdout)

    def test_nonzero_child_exit(self):
        child = self.write_child("boom.py", """
            import sys
            print("dying")
            sys.exit(3)
        """)
        result = self.run_runner([sys.executable, child])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("exited with 3", result.stderr)

    def test_watchdog_timeout_kills_child(self):
        timeout_s = 2
        child = self.write_child("hang.py", """
            import os
            import time
            print(f"CHILD_PID {os.getpid()}", flush=True)
            # Hung forever with stdout held open: EOF never arrives, which is
            # exactly the case the watchdog must cover.
            time.sleep(3600)
        """)
        result = self.run_runner([sys.executable, child],
                                 extra_args=["--timeout", str(timeout_s)],
                                 wall_timeout=30)
        self.assertEqual(result.returncode, 124, result.stderr)
        self.assertIn(f"timed out (watchdog {timeout_s}s)", result.stderr)
        self.assertLess(result.elapsed, 2 * timeout_s + 2,
                        "runner did not return within ~2x the watchdog")
        match = re.search(r"CHILD_PID (\d+)", result.stdout)
        self.assertIsNotNone(match, "child pid line was not forwarded")
        self.assertFalse(pid_alive(int(match.group(1))),
                         "watchdog left the hung child running")

    def test_missing_pass_marker(self):
        child = self.write_child("nopass.py", """
            print("simulation finished but printed no marker")
        """)
        result = self.run_runner([sys.executable, child])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing pass marker", result.stderr)

    def test_fail_marker(self):
        child = self.write_child("fail.py", """
            print("[MULTICORE-SINGLE-SMOKE-FAIL] mismatch at hart 0")
        """)
        result = self.run_runner([sys.executable, child])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("reported FAIL", result.stderr)

    def test_fatal_assertion_text(self):
        child = self.write_child("fatal.py", f"""
            print("{PASS_MARKER}")
            print("Fatal: assertion failed in liteeth_mac")
        """)
        result = self.run_runner([sys.executable, child])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("fatal/assertion", result.stderr)

    def test_cluster_profile_mismatch(self):
        tampered = dict(VALID_PROFILE_VALUES, numHarts="9")
        self.write_cluster_profile(tampered)
        child = self.write_child("ok.py", f'print("{PASS_MARKER}")')
        result = self.run_runner([sys.executable, child])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("cluster-profile.txt mismatch", result.stderr)
        self.assertIn("numHarts", result.stderr)

    def test_cluster_profile_missing(self):
        os.remove(self.profile_file_path())
        child = self.write_child("ok.py", f'print("{PASS_MARKER}")')
        result = self.run_runner([sys.executable, child])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("has not been elaborated", result.stderr)


if __name__ == "__main__":
    unittest.main()
