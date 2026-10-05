#!/usr/bin/env python3
"""Compare every measured field for one independently rerun profile."""
import csv
from pathlib import Path
import sys

run = Path(sys.argv[1]).resolve()
name = sys.argv[2]
for kind in ("functions", "vectors"):
    def rows(suffix):
        with (run / "profile" / f"{name}{suffix}-{kind}.csv").open() as f:
            return sorted(csv.reader(f))
    a, b = rows(""), rows("-repeat")
    if a != b:
        print(f"REPEAT_DIFFERENCE {kind}: original_rows={len(a)} repeat_rows={len(b)}")
        original, repeat = set(map(tuple, a)), set(map(tuple, b))
        for r in sorted(original - repeat)[:10]:
            print("ORIGINAL_ONLY", r)
        for r in sorted(repeat - original)[:10]:
            print("REPEAT_ONLY", r)
        raise SystemExit(1)
    print("REPEAT_IDENTICAL", kind, len(a) - 1, "rows")
