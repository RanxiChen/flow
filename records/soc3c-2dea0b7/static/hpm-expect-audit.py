"""Audit HPM expect changes from stimuli only; never reads RTL outputs.

Replays the two software models over BreezeCsrPipelineSpec's unchanged
directed cases and Scala/Java Random(0x435352) stream. CSV lists each changed
CSR expect, including machine/user aliases, at its original checkpoint.
"""
import csv
import json
from pathlib import Path


class JavaRandom:
    def __init__(self, seed):
        self.seed = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def bits(self, n):
        self.seed = (self.seed * 0x5DEECE66D + 11) & ((1 << 48) - 1)
        return self.seed >> (48 - n)

    def integer(self, bound):
        if bound & (bound - 1) == 0:
            return (bound * self.bits(31)) >> 31
        while True:
            bits = self.bits(31)
            value = bits % bound
            if bits - value + bound - 1 < 1 << 31:
                return value

    def boolean(self):
        return self.bits(1) != 0

    def bigint64(self):
        # java.math.BigInteger(64, Random): nextBytes emits each int LSB first.
        data = b"".join(self.bits(32).to_bytes(4, "little") for _ in range(2))
        return int.from_bytes(data, "big")


mask = (1 << 64) - 1
old = [0] * 8
new = [0] * 8
pending = [0] * 8
selector = [0] * 8
inhibit = 0
step = 0
rows = []


def tick(events=0, write=None, retire=False, trap=False, valid=True,
         write_enable=True, conflict=0):
    global inhibit, step
    accepted = write if valid and write_enable and not trap else None
    for i in range(8):
        increment = conflict if selector[i] == 13 else int(
            selector[i] != 0 and bool(events & (1 << (selector[i] - 1))))
        attributed = 0 if inhibit & (1 << (i + 3)) else increment
        overwritten = accepted is not None and accepted[0] == 0xB03 + i
        prior = pending[i]
        old[i] = accepted[1] if overwritten else (old[i] + attributed) & mask
        new[i] = accepted[1] if overwritten else (new[i] + prior) & mask
        pending[i] = 0 if overwritten else attributed
        if old[i] != new[i]:
            for address, call_line in ((0xB03 + i, 71), (0xC03 + i, 72)):
                rows.append((step + 1, i + 3, hex(address),
                             f"BreezeCsrPipelineSpec.scala:67 via :{call_line}",
                             old[i], new[i], prior, attributed,
                             "publish previous attributed event; defer current event"))
    if accepted is not None:
        address, value = accepted
        if 0x323 <= address < 0x32B:
            selector[address - 0x323] = value if value <= 13 else 0
        if address == 0x320:
            inhibit = value & 0x7FD
    step += 1


for event in range(1, 14):
    for i in range(8):
        tick(0xFFF, (0x323 + i, event), conflict=2)
tick(0x3FF, (0x320, mask))
tick(0x3FF)
tick(0x3FF, (0x320, 0))
for i in range(8):
    tick(0x3FF, (0xB03 + i, mask))
    tick(0x3FF)
    tick(0x3FF, (0xB03 + i, 42))
    tick()
for value in (14, 0x101, (1 << 63) | 13, mask):
    for i in range(8):
        tick(0x3FF, (0x323 + i, value))
tick(write=(0xB00, mask))
tick(retire=True, write=(0xB02, mask))
tick(retire=True)
random = JavaRandom(0x435352)
for _ in range(400):
    i = random.integer(8)
    kind = random.integer(6)
    if kind == 0:
        write = (0x323 + i, random.integer(16))
    elif kind == 1:
        write = (0xB03 + i, random.bigint64())
    elif kind == 2:
        write = (0x320, random.integer(2048))
    elif kind == 3:
        write = (0xB00, random.bigint64())
    elif kind == 4:
        write = (0xB02, random.bigint64())
    else:
        write = None
    tick(random.integer(4096), write, random.boolean(),
         random.integer(7) == 0, random.integer(9) != 0,
         random.integer(9) != 0, random.integer(3))
tick(write=(0x320, 0))
tick(write=(0x323, 1))
tick(events=1)
root = Path(__file__).resolve().parent
with (root / "hpm-expect-changes.csv").open("w") as f:
    writer = csv.writer(f, lineterminator="\n")
    writer.writerow(("cycle", "counter", "csr", "expect_location", "old", "new",
                     "previous_increment", "current_increment", "derivation"))
    writer.writerows(rows)
(root / "hpm-expect-audit.json").write_text(json.dumps({
    "source_sha": "2dea0b7689d270a14cd59cfff4a99cc66fa98ec2",
    "seed": "0x435352", "ticks": step, "changed_expect_instances": len(rows),
    "hpm_expect_instances_including_initial_and_reset": (step + 2) * 16,
    "input": "unchanged Scala test stimuli only; no RTL outputs",
}, indent=2) + "\n")
print(f"{step} stimulus ticks; {len(rows)} changed HPM expect instances")
