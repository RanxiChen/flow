#!/usr/bin/env python3
"""ELF STT_FUNC ranges. Preserve mangled names for unambiguous ownership."""
import subprocess
import sys
from pathlib import Path

elf, out = map(Path, sys.argv[1:])
text = subprocess.check_output([str(Path.home() / "Tool/RISCV/bin/riscv64-unknown-linux-gnu-readelf"),
                                "--wide", "--syms", str(elf)], text=True)
ranges = {}
for line in text.splitlines():
    fields = line.split()
    if len(fields) >= 8 and fields[3] == "FUNC" and fields[6] != "UND":
        address, size, name = int(fields[1], 16), int(fields[2]), fields[7]
        if size:
            # Address aliases have the same implementation. Prefer descriptive names.
            old = ranges.get(address)
            if old is None or len(name) > len(old[1]):
                ranges[address] = (size, name)
with out.open("w") as f:
    for address, (size, name) in sorted(ranges.items()):
        f.write(f"{address:x} {size:x} {name}\n")
