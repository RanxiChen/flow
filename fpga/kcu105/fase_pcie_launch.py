#!/usr/bin/env python3
"""Final gate: DMA a bare-metal program into DDR over PCIe and launch it.

DESTRUCTIVE. Requires --yes-overwrite-ddr. The core must be halted; any
operating system on it is already gone by then, and this overwrites DDR.

Sequence: halt, disable paging and interrupts through injected CSR writes,
DMA the program into DDR, read it back, inject fence.i so the instruction
cache cannot serve stale lines (DMA completion alone does not do this),
launch, let it run, halt again, and check the registers.

The program sums 1..100, so a correct run leaves 5050 in x21 -- a value that
does not occur by accident. It then spins, incrementing x24, so a growing x24
proves the core was still fetching from DDR when it was halted.

No cross toolchain: instructions are encoded here and self-checked against
known encodings with --dry-run.
"""
import argparse
import os
import sys
import time

from fase_pcie import FasePcie, FaseError, OPCODES

ST_OWNED, ST_HALTED, ST_EMPTY, ST_BUSY = 1 << 0, 1 << 1, 1 << 2, 1 << 3
ST_FINISHED, ST_FAULTED = 1 << 4, 1 << 5

FENCE_I = 0x0000100F


# --- instruction encoding ------------------------------------------------
def i_type(opcode, funct3, rd, rs1, imm):
    return (((imm & 0xfff) << 20) | ((rs1 & 31) << 15) | ((funct3 & 7) << 12)
            | ((rd & 31) << 7) | opcode)


def r_type(opcode, funct3, funct7, rd, rs1, rs2):
    return (((funct7 & 0x7f) << 25) | ((rs2 & 31) << 20) | ((rs1 & 31) << 15)
            | ((funct3 & 7) << 12) | ((rd & 31) << 7) | opcode)


def addi(rd, rs1, imm):
    return i_type(0x13, 0, rd, rs1, imm)


def add(rd, rs1, rs2):
    return r_type(0x33, 0, 0, rd, rs1, rs2)


def csrrw(rd, csr, rs1):
    return i_type(0x73, 1, rd, rs1, csr)


def csrrci(rd, csr, uimm):
    return i_type(0x73, 7, rd, uimm, csr)


def blt(rs1, rs2, offset):
    """B-type: the immediate is scattered across the word."""
    assert -4096 <= offset < 4096 and offset % 2 == 0, offset
    imm = offset & 0x1fff
    return (((imm >> 12) & 1) << 31 | ((imm >> 5) & 0x3f) << 25
            | (rs2 & 31) << 20 | (rs1 & 31) << 15 | 4 << 12
            | ((imm >> 1) & 0xf) << 8 | ((imm >> 11) & 1) << 7 | 0x63)


def jal(rd, offset):
    """J-type: likewise scattered."""
    assert -(1 << 20) <= offset < (1 << 20) and offset % 2 == 0, offset
    imm = offset & 0x1fffff
    return (((imm >> 20) & 1) << 31 | ((imm >> 1) & 0x3ff) << 21
            | ((imm >> 11) & 1) << 20 | ((imm >> 12) & 0xff) << 12
            | (rd & 31) << 7 | 0x6f)


def self_check():
    """Encoders must reproduce well-known encodings."""
    cases = [
        ("nop (addi x0,x0,0)", addi(0, 0, 0), 0x00000013),
        ("addi x1,x0,1", addi(1, 0, 1), 0x00100093),
        ("add x1,x2,x3", add(1, 2, 3), 0x003100B3),
        ("j . (jal x0,0)", jal(0, 0), 0x0000006F),
        ("jal x0,-4", jal(0, -4), 0xFFDFF06F),
        ("blt x0,x0,0", blt(0, 0, 0), 0x00004063),
        ("csrw satp,x0", csrrw(0, 0x180, 0), 0x18001073),
        ("csrw mie,x0", csrrw(0, 0x304, 0), 0x30401073),
        ("csrci mstatus,8", csrrci(0, 0x300, 8), 0x30047073),
    ]
    bad = []
    for name, got, want in cases:
        ok = got == want
        if not ok:
            bad.append(name)
        print("  [%s] %-22s 0x%08x (expected 0x%08x)" % (
            "ok" if ok else "BAD", name, got, want))
    return not bad


# --- the program ---------------------------------------------------------
LIMIT = 100
EXPECTED_SUM = LIMIT * (LIMIT + 1) // 2      # 5050
MARKER = 0x5A5


def build_program():
    """Returns (words, description). Offsets are 4 bytes apart.

    0x00  addi x20, x0, 0        counter
    0x04  addi x21, x0, 0        running sum
    0x08  addi x22, x0, LIMIT    loop bound
    0x0c  addi x24, x0, 0        spin counter
    0x10  addi x20, x20, 1     <-+ loop
    0x14  add  x21, x21, x20      |
    0x18  blt  x20, x22, -8     --+
    0x1c  addi x23, x0, MARKER    completion marker
    0x20  addi x24, x24, 1      <-+ spin
    0x24  jal  x0, -4           --+
    """
    words = [
        (addi(20, 0, 0), "addi x20, x0, 0"),
        (addi(21, 0, 0), "addi x21, x0, 0"),
        (addi(22, 0, LIMIT), "addi x22, x0, %d" % LIMIT),
        (addi(24, 0, 0), "addi x24, x0, 0"),
        (addi(20, 20, 1), "addi x20, x20, 1"),
        (add(21, 21, 20), "add  x21, x21, x20"),
        (blt(20, 22, -8), "blt  x20, x22, -8"),
        (addi(23, 0, MARKER), "addi x23, x0, 0x%x" % MARKER),
        (addi(24, 24, 1), "addi x24, x24, 1"),
        (jal(0, -4), "jal  x0, -4"),
    ]
    return words


def program_bytes(words, pad_to=None):
    blob = b"".join(w.to_bytes(4, "little") for w, _ in words)
    if pad_to and len(blob) < pad_to:
        blob += b"\x00" * (pad_to - len(blob))
    return blob


# --- FASE helpers --------------------------------------------------------
def fase_status(dev):
    result, error, _ = dev.status()
    if error:
        raise FaseError("STATUS returned an error")
    return result


def decode(status):
    names = [(ST_OWNED, "owned"), (ST_HALTED, "halted"), (ST_EMPTY, "empty"),
             (ST_BUSY, "busy"), (ST_FINISHED, "finished"), (ST_FAULTED, "faulted")]
    on = [n for bit, n in names if status & bit]
    return "0x%02x %s" % (status, "|".join(on) if on else "(none)")


def wait_status(dev, mask, want, timeout=2.0, label="condition"):
    deadline = time.perf_counter() + timeout
    last = None
    while time.perf_counter() < deadline:
        last = fase_status(dev)
        if (last & mask) == want:
            return last
    raise FaseError("timed out waiting for %s; last %s" % (label, decode(last)))


def ensure_halted(dev):
    status = fase_status(dev)
    if (status & (ST_OWNED | ST_HALTED | ST_EMPTY)) == (ST_OWNED | ST_HALTED | ST_EMPTY):
        print("  already halted: %s" % decode(status))
        return status
    print("  status %s -> issuing HALT" % decode(status))
    _, error, _ = dev.command(OPCODES["halt"])
    if error:
        raise FaseError("HALT rejected")
    status = wait_status(dev, ST_OWNED | ST_HALTED | ST_EMPTY,
                         ST_OWNED | ST_HALTED | ST_EMPTY, label="halted+empty")
    print("  halted: %s" % decode(status))
    return status


def inject(dev, insn, pc, label, timeout=5.0, tolerate_fault=False):
    """Inject one instruction and wait for the controller to finish it.

    fence.i waits for the data cache flush to complete, so the timeout here
    is deliberately generous.
    """
    _, error, _ = dev.command(OPCODES["exec"], data=insn, pc=pc)
    if error:
        print("  [FAIL] %-28s EXEC rejected (core not halted/idle)" % label)
        return None
    status = wait_status(dev, ST_BUSY | ST_FINISHED, ST_FINISHED,
                         timeout=timeout, label="%s completion" % label)
    faulted = bool(status & ST_FAULTED)
    if faulted:
        cause, _, _ = dev.cause()
        tval, _, _ = dev.tval()
        tag = "tolerated" if tolerate_fault else "FAIL"
        print("  [%s] %-28s faulted, cause=%d tval=0x%x" % (tag, label, cause, tval))
    else:
        print("  [ok]   %-28s 0x%08x" % (label, insn))
    return status


def read_regs(dev, indices):
    out = {}
    for i in indices:
        value, error, _ = dev.read_reg(i)
        out[i] = None if error else value
    return out


# --- DMA -----------------------------------------------------------------
def dma_write(data, addr, h2c="/dev/xdma0_h2c_0"):
    fd = os.open(h2c, os.O_WRONLY)
    try:
        return os.pwrite(fd, data, addr)
    finally:
        os.close(fd)


def dma_read(size, addr, c2h="/dev/xdma0_c2h_0"):
    fd = os.open(c2h, os.O_RDONLY)
    try:
        return os.pread(fd, size, addr)
    finally:
        os.close(fd)


# --- stages --------------------------------------------------------------
def stage_prepare_core(dev, pc_base):
    """Disable paging and interrupts so a physical-address program can run.

    Linux left satp pointing at its page tables and a timer armed; either
    would divert the launched program. The privilege mode after halt is not
    known here, so machine-level writes are attempted and allowed to fault.
    """
    print("== prepare core state ==")
    steps = [
        (csrrw(0, 0x180, 0), "csrw satp, x0", False),
        (csrrw(0, 0x104, 0), "csrw sie, x0", False),
        (csrrci(0, 0x100, 0x2), "csrci sstatus, 2", False),
        (csrrw(0, 0x304, 0), "csrw mie, x0", True),
        (csrrci(0, 0x300, 0x8), "csrci mstatus, 8", True),
    ]
    pc = pc_base
    for insn, label, tolerate in steps:
        inject(dev, insn, pc, label, tolerate_fault=tolerate)
        pc += 4
    print("  note: machine-level writes fault harmlessly from S-mode")


def stage_load(dev, words, addr, verify):
    print("== DMA program into DDR at 0x%08x ==" % addr)
    blob = program_bytes(words)
    n = dma_write(blob, addr)
    print("  wrote %d bytes (%d instructions)" % (n, len(words)))
    if n != len(blob):
        raise FaseError("short DMA write: %d of %d" % (n, len(blob)))
    if not verify:
        return
    back = dma_read(len(blob), addr)
    if back != blob:
        differ = [i for i in range(min(len(back), len(blob))) if back[i] != blob[i]]
        raise FaseError("read-back mismatch: %d bytes differ, first at %d"
                        % (len(differ), differ[0]))
    print("  [ok]   read-back matches byte for byte")
    reads, writes, errors = dev.counters()
    print("  memory counters: read_words %d  write_words %d  errors %d"
          % (reads, writes, errors))
    if errors:
        print("  WARNING: the bridge reported %d downstream errors" % errors)


def stage_fence(dev, pc_base):
    print("== instruction cache synchronization ==")
    status = inject(dev, FENCE_I, pc_base, "fence.i", timeout=10.0)
    if status is None:
        raise FaseError("fence.i could not be injected")
    if status & ST_FAULTED:
        raise FaseError("fence.i faulted; refusing to launch stale code")
    print("  the frontend honours cacheFlush while paused, so this reaches")
    print("  the instruction cache even with fetch disabled")


def stage_launch(dev, addr, run_seconds):
    print("== launch at 0x%08x ==" % addr)
    before = fase_status(dev)
    print("  status before        %s" % decode(before))
    _, error, elapsed = dev.command(OPCODES["launch"], pc=addr)
    if error:
        raise FaseError("LAUNCH rejected (core not halted/idle, or odd PC)")
    print("  [ok]   launch accepted            %.1f us" % (elapsed * 1e6))
    status = fase_status(dev)
    print("  status after         %s" % decode(status))
    if status & ST_OWNED:
        raise FaseError("core still owned by FASE after launch")
    pc1, _, _ = dev.next_pc()
    time.sleep(run_seconds)
    pc2, _, _ = dev.next_pc()
    print("  running: NEXT_PC 0x%016x then 0x%016x" % (pc1, pc2))
    lo, hi = addr, addr + 4 * 16
    if not (lo <= pc2 < hi):
        print("  WARNING: PC is outside the loaded program")
    return pc1, pc2


def stage_check(dev, run_seconds):
    print("== halt and check results ==")
    ensure_halted(dev)
    regs = read_regs(dev, (20, 21, 22, 23, 24))
    names = {20: "counter", 21: "sum", 22: "limit", 23: "marker", 24: "spins"}
    for i in sorted(regs):
        value = regs[i]
        print("  x%-2d %-8s %s" % (
            i, names[i], "unreadable" if value is None else "0x%016x (%d)" % (value, value)))

    results = []
    results.append(("loop ran to completion", regs[20] == LIMIT,
                    "x20 = %s, expected %d" % (regs[20], LIMIT)))
    results.append(("sum of 1..%d is %d" % (LIMIT, EXPECTED_SUM),
                    regs[21] == EXPECTED_SUM,
                    "x21 = %s, expected %d" % (regs[21], EXPECTED_SUM)))
    results.append(("completion marker written", regs[23] == MARKER,
                    "x23 = %s, expected 0x%x" % (regs[23], MARKER)))
    results.append(("still fetching when halted", bool(regs[24]) and regs[24] > 0,
                    "x24 = %s after %.2fs" % (regs[24], run_seconds)))
    print()
    failures = 0
    for label, ok, detail in results:
        if not ok:
            failures += 1
        print("  [%s] %-34s %s" % ("PASS" if ok else "FAIL", label, detail))
    return failures


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--bdf", default="0000:02:00.0")
    p.add_argument("--bar", type=int, default=0)
    p.add_argument("--addr", type=lambda s: int(s, 0), default=0x81000000,
                   help="DDR load address, inside 0x80000000..0xffffffff")
    p.add_argument("--scratch-pc", type=lambda s: int(s, 0), default=0x80200000,
                   help="PC presented with injected instructions")
    p.add_argument("--run-seconds", type=float, default=0.25)
    p.add_argument("--no-verify", action="store_true",
                   help="skip the DMA read-back comparison")
    p.add_argument("--dry-run", action="store_true",
                   help="self-check the encoders and print the program; touches no hardware")
    p.add_argument("--yes-overwrite-ddr", action="store_true",
                   help="required: acknowledges halting the core and overwriting DDR")
    args = p.parse_args()

    words = build_program()

    if args.dry_run:
        print("== encoder self-check ==")
        ok = self_check()
        print("\n== program, %d instructions, %d bytes ==" % (len(words), 4 * len(words)))
        for i, (word, text) in enumerate(words):
            print("  +0x%02x  0x%08x   %s" % (4 * i, word, text))
        print("\n  expected after a correct run:")
        print("    x20 = %d, x21 = %d (sum 1..%d), x23 = 0x%x, x24 > 0"
              % (LIMIT, EXPECTED_SUM, LIMIT, MARKER))
        return 0 if ok else 1

    if not args.yes_overwrite_ddr:
        print(__doc__, file=sys.stderr)
        print("Refusing to run without --yes-overwrite-ddr "
              "(use --dry-run to inspect the program)", file=sys.stderr)
        return 2

    if not 0x80000000 <= args.addr < 0x100000000 - 4 * len(words):
        print("load address must sit inside DDR, 0x80000000..0xffffffff",
              file=sys.stderr)
        return 2
    if args.addr % 4:
        print("load address must be 4-byte aligned", file=sys.stderr)
        return 2

    if not self_check():
        print("encoder self-check failed; refusing to touch the board", file=sys.stderr)
        return 1
    print()

    failures = 0
    with FasePcie(args.bdf, args.bar) as dev:
        dev.identify()
        print("FASE ABI v1 on %s BAR%d\n" % (args.bdf, args.bar))
        try:
            print("== halt ==")
            ensure_halted(dev)
            stage_prepare_core(dev, args.scratch_pc)
            stage_load(dev, words, args.addr, not args.no_verify)
            stage_fence(dev, args.scratch_pc)
            stage_launch(dev, args.addr, args.run_seconds)
            failures = stage_check(dev, args.run_seconds)
        except FaseError as exc:
            print("\nABORTED: %s" % exc, file=sys.stderr)
            failures += 1

        print("\n== summary ==")
        if failures:
            print("  %d check(s) failed" % failures)
        else:
            print("  the program was delivered over PCIe and executed on the core")
        print("  the core is halted; reprogram the bitstream to restore the system")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
