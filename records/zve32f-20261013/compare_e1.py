#!/usr/bin/env python3
"""Compare frozen scalar RTL after removing only source paths and assertion line numbers."""

import argparse
import hashlib
import json
from pathlib import Path
import re


def emitted(root: Path, profile: str) -> Path:
    base = root / "design/build/rtl/axi-cluster" / profile / "gshare/linux/production/cpu/coherent-dma/emitted"
    runs = [p for p in base.iterdir() if p.is_dir()]
    if len(runs) != 1:
        raise ValueError(f"expected one fresh emission in {base}; found {runs}")
    return runs[0]


def canonical(path: Path, root: Path) -> bytes:
    data = path.read_text()
    data = data.replace(str(root), "<SOURCE_ROOT>")
    data = re.sub(r"(\.scala:)\d+", r"\1<LINE>", data)
    return data.encode()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    args = parser.parse_args()
    result = {"baseline": str(args.baseline), "candidate": str(args.candidate),
              "normalization": ["absolute source root", "Scala assertion line number"],
              "profiles": {}}
    for profile in ("single", "single-rvv"):
        a = {p.name: p for p in emitted(args.baseline, profile).iterdir() if p.is_file()}
        b = {p.name: p for p in emitted(args.candidate, profile).iterdir() if p.is_file()}
        missing = sorted(set(a) - set(b))
        extra = sorted(set(b) - set(a))
        changed = sorted(name for name in set(a) & set(b)
                         if canonical(a[name], args.baseline) != canonical(b[name], args.candidate))
        digest = hashlib.sha256()
        for name in sorted(a):
            digest.update(name.encode() + b"\0" + canonical(a[name], args.baseline))
        result["profiles"][profile] = {"files": len(a), "missing": missing, "extra": extra,
                                       "changed": changed, "baseline_normalized_sha256": digest.hexdigest()}
    result["pass"] = all(not any(v[k] for k in ("missing", "extra", "changed"))
                         for v in result["profiles"].values())
    print(json.dumps(result, indent=2))
    if not result["pass"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
