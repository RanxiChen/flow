#!/usr/bin/env python3
"""Host-side FASE client over the PCIe user BAR (ABI v1).

Mirrors fase_jtag.tcl's hard-won rules on a different transport:
never resubmit while busy, match the sequence number before trusting a
result, and treat a timeout as "may already have executed".

Transport is deliberately separate from the command layer so workflows can
be built on the commands without depending on the register encoding.

Read-only by default. Commands that change CPU state require --allow-halt.
"""
import argparse
import mmap
import os
import sys
import time

MAGIC = 0x46415345
ABI = 1

# User BAR offsets, ABI v1.
O_MAGIC, O_ABI, O_STATUS, O_SCRATCH = 0x00, 0x04, 0x08, 0x0c
O_OPCODE, O_DATA_LO, O_DATA_HI = 0x10, 0x14, 0x18
O_PC_LO, O_PC_HI = 0x1c, 0x20
O_SUBMIT, O_COMPLETED, O_RESULT_LO, O_RESULT_HI = 0x24, 0x28, 0x2c, 0x30
O_ACK = 0x38
O_READ_WORDS, O_WRITE_WORDS, O_MEM_ERRORS = 0x40, 0x44, 0x48

S_BUSY, S_DONE, S_ERROR = 1 << 0, 1 << 1, 1 << 2

# FaseController.scala
OPCODES = {
    "status": 0, "halt": 1, "exec": 2, "read_reg": 3, "write_reg": 4,
    "launch": 5, "snapshot": 6, "read_snapshot": 7,
    "next_pc": 8, "cause": 9, "tval": 10,
}
STATE_CHANGING = {"halt", "exec", "write_reg", "launch"}


class FaseError(RuntimeError):
    pass


class FaseTimeout(FaseError):
    """The command may already have executed. Do not blindly retry."""


class FasePcie:
    def __init__(self, bdf="0000:02:00.0", bar=0):
        self.path = "/sys/bus/pci/devices/%s/resource%d" % (bdf, bar)
        self.fd = os.open(self.path, os.O_RDWR | os.O_SYNC)
        self.map = mmap.mmap(self.fd, 4096, mmap.MAP_SHARED)
        self.seq = 0

    def close(self):
        self.map.close()
        os.close(self.fd)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    # --- transport ---------------------------------------------------
    def rd(self, off):
        return int.from_bytes(self.map[off:off+4], "little")

    def wr(self, off, value):
        # Every register in this ABI is a naturally aligned 32-bit write;
        # SUBMIT and ACK additionally require all four byte enables, which
        # a full 4-byte store satisfies.
        self.map[off:off+4] = (value & 0xffffffff).to_bytes(4, "little")

    def identify(self):
        magic, abi = self.rd(O_MAGIC), self.rd(O_ABI)
        if magic != MAGIC:
            raise FaseError("bad magic 0x%08x (expected 0x%08x): wrong BAR, "
                            "or the bitstream lacks the FASE endpoint" % (magic, MAGIC))
        if abi != ABI:
            raise FaseError("unsupported ABI version %d (expected %d)" % (abi, ABI))
        return magic, abi

    def command(self, opcode, index=0, data=0, pc=0, timeout=1.0):
        """One FASE transaction. Returns (result, error, elapsed_seconds)."""
        if not 0 <= opcode < 256 or not 0 <= index < 64:
            raise FaseError("opcode/index out of range")
        if not 0 <= data < (1 << 64) or not 0 <= pc < (1 << 64):
            raise FaseError("data/pc out of range")

        status = self.rd(O_STATUS)
        if status & S_BUSY:
            raise FaseError("transaction still busy; do not resubmit")
        if status & S_DONE:
            # An earlier completion was never acknowledged. Command fields
            # cannot be changed while one is outstanding, so clear it first.
            self.wr(O_ACK, self.rd(O_COMPLETED))
            if self.rd(O_STATUS) & S_DONE:
                raise FaseError("stale completion could not be acknowledged")

        self.wr(O_OPCODE, (index << 8) | opcode)
        self.wr(O_DATA_LO, data & 0xffffffff)
        self.wr(O_DATA_HI, data >> 32)
        self.wr(O_PC_LO, pc & 0xffffffff)
        self.wr(O_PC_HI, pc >> 32)

        self.seq = (self.seq + 1) & 0xffffffff or 1
        start = time.perf_counter()
        self.wr(O_SUBMIT, self.seq)

        deadline = start + timeout
        while True:
            status = self.rd(O_STATUS)
            if status & S_DONE:
                break
            if time.perf_counter() > deadline:
                raise FaseTimeout(
                    "no response within %.3fs; the operation may already have "
                    "executed, do not retry blindly (status=0x%08x)" % (timeout, status))
        elapsed = time.perf_counter() - start

        completed = self.rd(O_COMPLETED)
        if completed != self.seq:
            raise FaseError("sequence mismatch: completed=%d submitted=%d; "
                            "result belongs to another command" % (completed, self.seq))
        result = self.rd(O_RESULT_LO) | (self.rd(O_RESULT_HI) << 32)
        error = bool(status & S_ERROR)
        self.wr(O_ACK, completed)          # readback does not clear completion
        return result, error, elapsed

    # --- commands ----------------------------------------------------
    def status(self):
        return self.command(OPCODES["status"])

    def read_reg(self, index):
        return self.command(OPCODES["read_reg"], index=index)

    def next_pc(self):
        return self.command(OPCODES["next_pc"])

    def cause(self):
        return self.command(OPCODES["cause"])

    def tval(self):
        return self.command(OPCODES["tval"])

    def counters(self):
        return self.rd(O_READ_WORDS), self.rd(O_WRITE_WORDS), self.rd(O_MEM_ERRORS)


def verify(dev, samples):
    print("== identity ==")
    magic, abi = dev.identify()
    print("  magic 0x%08x  ABI v%d" % (magic, abi))
    print("  raw status 0x%08x" % dev.rd(O_STATUS))

    print("== STATUS handshake ==")
    result, error, elapsed = dev.status()
    print("  result 0x%016x  error %s  %.1f us" % (result, error, elapsed * 1e6))
    if error:
        print("  NOTE: FASE reported an error for STATUS", file=sys.stderr)

    print("== repeat, to confirm the handshake is reusable ==")
    lat = []
    for i in range(3):
        result, error, elapsed = dev.status()
        lat.append(elapsed)
        print("  #%d result 0x%016x  error %s  %.1f us" % (i + 1, result, error, elapsed * 1e6))

    print("== read-only diagnostics ==")
    for name, fn in (("NEXT_PC", dev.next_pc), ("CAUSE", dev.cause), ("TVAL", dev.tval)):
        try:
            result, error, elapsed = fn()
            print("  %-8s 0x%016x  error %s" % (name, result, error))
        except FaseError as exc:
            print("  %-8s FAILED: %s" % (name, exc))

    print("== register file (x0 must read 0) ==")
    for index in (0, 1, 2, 8, 10):
        try:
            result, error, elapsed = dev.read_reg(index)
            note = ""
            if index == 0 and result != 0:
                note = "  <-- x0 should be zero"
            print("  x%-2d 0x%016x  error %s%s" % (index, result, error, note))
        except FaseError as exc:
            print("  x%-2d FAILED: %s" % (index, exc))

    print("== round-trip latency, %d STATUS commands ==" % samples)
    lat = []
    for _ in range(samples):
        lat.append(dev.status()[2])
    lat.sort()
    print("  min %.1f us  median %.1f us  p95 %.1f us  max %.1f us" % (
        lat[0] * 1e6, lat[len(lat)//2] * 1e6,
        lat[int(len(lat)*0.95)] * 1e6, lat[-1] * 1e6))
    print("  -> %.0f commands/second sustained" % (1.0 / (sum(lat)/len(lat))))

    reads, writes, errors = dev.counters()
    print("== memory counters (unchanged by control traffic) ==")
    print("  read_words %d  write_words %d  errors %d" % (reads, writes, errors))


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--bdf", default="0000:02:00.0")
    p.add_argument("--bar", type=int, default=0)
    p.add_argument("--samples", type=int, default=200,
                   help="STATUS commands used for the latency measurement")
    p.add_argument("--command", help="run a single command instead of the verification")
    p.add_argument("--index", type=lambda s: int(s, 0), default=0)
    p.add_argument("--data", type=lambda s: int(s, 0), default=0)
    p.add_argument("--pc", type=lambda s: int(s, 0), default=0)
    p.add_argument("--allow-halt", action="store_true",
                   help="permit commands that change CPU state")
    args = p.parse_args()

    try:
        dev = FasePcie(args.bdf, args.bar)
    except OSError as exc:
        print("cannot open BAR: %s" % exc, file=sys.stderr)
        return 1

    with dev:
        if args.command:
            name = args.command.lower()
            if name not in OPCODES:
                print("unknown command %r; known: %s" % (
                    args.command, " ".join(sorted(OPCODES))), file=sys.stderr)
                return 2
            if name in STATE_CHANGING and not args.allow_halt:
                print("%s changes CPU state; pass --allow-halt to run it" % name,
                      file=sys.stderr)
                return 2
            dev.identify()
            result, error, elapsed = dev.command(
                OPCODES[name], args.index, args.data, args.pc)
            print("%s -> 0x%016x  error %s  %.1f us" % (name, result, error, elapsed * 1e6))
            return 1 if error else 0
        verify(dev, args.samples)
    return 0


if __name__ == "__main__":
    sys.exit(main())
