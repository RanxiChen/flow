#!/usr/bin/env python3
"""Fail if any frozen file differs from its recorded sha256.

Usage:
  tools/frozen_check.py          check (exit 1 on any mismatch)
  tools/frozen_check.py --record FILE...   add/update entries (Claude/user only)
"""
import hashlib, json, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "tools" / "frozen.json"

def digest(rel):
    return hashlib.sha256((ROOT / rel).read_bytes()).hexdigest()

def main():
    table = json.loads(MANIFEST.read_text()) if MANIFEST.exists() else {}
    if len(sys.argv) > 1 and sys.argv[1] == "--record":
        for rel in sys.argv[2:]:
            table[rel] = digest(rel)
        MANIFEST.write_text(json.dumps(table, indent=2, sort_keys=True) + "\n")
        return 0
    bad = []
    for rel, want in sorted(table.items()):
        if not (ROOT / rel).exists():
            bad.append(f"missing  {rel}")
        elif digest(rel) != want:
            bad.append(f"changed  {rel}")
    for line in bad:
        print(line)
    print("frozen check:", "FAIL" if bad else f"OK ({len(table)} files)")
    return 1 if bad else 0

if __name__ == "__main__":
    sys.exit(main())
