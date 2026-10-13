#!/usr/bin/env python3
"""Check that the newest Zve32f emission matches the Vivado source snapshot."""

import argparse
import hashlib
import json
from pathlib import Path


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("emission_root", type=Path)
    parser.add_argument("snapshot_root", type=Path)
    args = parser.parse_args()
    newest = max((p for p in args.emission_root.iterdir() if p.is_dir()),
                 key=lambda p: p.stat().st_mtime_ns)
    snapshot = {}
    for path in args.snapshot_root.rglob("*.sv"):
        snapshot.setdefault(path.name, []).append(path)
    missing = []
    mismatched = []
    checked = 0
    for path in sorted(newest.glob("*.sv")):
        # This generated include list is a Verilator helper; LiteX adds the
        # underlying CVFPU sources to the Vivado project separately.
        if path.name == "FlowFpnewSources.sv":
            continue
        matches = snapshot.get(path.name, [])
        if not matches:
            missing.append(path.name)
        elif not any(digest(path) == digest(other) for other in matches):
            mismatched.append(path.name)
        else:
            checked += 1
    result = {"emission": str(newest), "snapshot": str(args.snapshot_root),
              "checked_sv": checked, "missing": missing, "mismatched": mismatched,
              "pass": not missing and not mismatched}
    print(json.dumps(result, indent=2))
    if not result["pass"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
