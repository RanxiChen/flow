#!/usr/bin/env python3
"""Stage A: verify FASE commands that take over the CPU, over PCIe.

DESTRUCTIVE. HALT stops the front end and drains the back end without
retaining pipeline state, so the operating system running on the core does
not survive. Requires --yes-halt-the-cpu.

Verifies, in order: HALT reaches halted+empty, the register file reads back
plausibly with x0 hardwired to zero, WriteReg/ReadReg round-trip, instruction
injection actually computes, a faulting instruction reports cause/tval, the
diagnostic snapshot works, and out-of-range requests are rejected.

STATUS bits (FaseController.scala): 0 owned, 1 halted, 2 empty, 3 busy,
4 finished, 5 faulted, 6 sending, 7 snapshot valid.
"""
import argparse
import sys
import time

from fase_pcie import FasePcie, FaseError, OPCODES

ST_OWNED, ST_HALTED, ST_EMPTY, ST_BUSY = 1 << 0, 1 << 1, 1 << 2, 1 << 3
ST_FINISHED, ST_FAULTED, ST_SENDING, ST_SNAPSHOT = 1 << 4, 1 << 5, 1 << 6, 1 << 7

XLEN_REGS = 32
SNAPSHOT_ENTRIES = 48

passed = []
failed = []


def check(label, ok, detail=""):
    (passed if ok else failed).append(label)
    print("  [%s] %-46s %s" % ("PASS" if ok else "FAIL", label, detail))
    return ok


def decode(status):
    names = [(ST_OWNED, "owned"), (ST_HALTED, "halted"), (ST_EMPTY, "empty"),
             (ST_BUSY, "busy"), (ST_FINISHED, "finished"), (ST_FAULTED, "faulted"),
             (ST_SENDING, "sending"), (ST_SNAPSHOT, "snapshot")]
    on = [n for bit, n in names if status & bit]
    return "0x%02x %s" % (status, "|".join(on) if on else "(none)")


def fase_status(dev):
    result, error, _ = dev.status()
    if error:
        raise FaseError("STATUS itself returned an error")
    return result


def wait_status(dev, mask, want, timeout=2.0, label="condition"):
    """Poll FASE STATUS until (status & mask) == want."""
    deadline = time.perf_counter() + timeout
    last = None
    while time.perf_counter() < deadline:
        last = fase_status(dev)
        if (last & mask) == want:
            return last
    raise FaseError("timed out waiting for %s; last status %s" % (label, decode(last)))


def encode_addi(rd, rs1, imm):
    return ((imm & 0xfff) << 20) | ((rs1 & 31) << 15) | ((rd & 31) << 7) | 0x13


def exec_insn(dev, insn, pc, timeout=2.0):
    """Inject one instruction and wait for the controller to finish it."""
    result, error, elapsed = dev.command(OPCODES["exec"], data=insn, pc=pc)
    if error:
        raise FaseError("EXEC rejected (core not halted/idle?)")
    status = wait_status(dev, ST_BUSY | ST_FINISHED, ST_FINISHED,
                         timeout=timeout, label="EXEC completion")
    return status, elapsed


def stage_halt(dev):
    print("== HALT ==")
    before = fase_status(dev)
    print("  status before        %s" % decode(before))
    result, error, elapsed = dev.command(OPCODES["halt"])
    check("HALT accepted", not error, "%.1f us" % (elapsed * 1e6))
    status = wait_status(dev, ST_OWNED | ST_HALTED | ST_EMPTY,
                         ST_OWNED | ST_HALTED | ST_EMPTY, label="halted+empty")
    print("  status after         %s" % decode(status))
    check("core halted and drained", True)
    return status


def stage_regfile(dev):
    print("== register file, all %d registers ==" % XLEN_REGS)
    values, errors = {}, []
    for i in range(XLEN_REGS):
        result, error, _ = dev.read_reg(i)
        if error:
            errors.append(i)
        else:
            values[i] = result
    check("every register readable", not errors,
          "" if not errors else "errored: %s" % errors)
    if 0 in values:
        check("x0 reads zero", values[0] == 0, "got 0x%016x" % values[0])
    nonzero = sum(1 for i, v in values.items() if i and v)
    check("some registers hold live state", nonzero > 0,
          "%d of %d nonzero" % (nonzero, len(values) - 1))
    for i in sorted(values):
        if i % 4 == 0:
            print("    " + "  ".join("x%-2d 0x%016x" % (j, values[j])
                                     for j in range(i, min(i + 4, XLEN_REGS))
                                     if j in values))
    return values


def stage_writereg(dev, saved):
    print("== WriteReg / ReadReg round-trip ==")
    # x31 is caller-saved and the OS is already gone; still restore it after.
    target, original = 31, saved.get(31, 0)
    for pattern in (0xa5a5a5a5deadbeef, 0x0000000000000001, 0):
        _, error, _ = dev.command(OPCODES["write_reg"], index=target, data=pattern)
        if error:
            check("write x%d = 0x%016x" % (target, pattern), False, "rejected")
            continue
        got, rd_err, _ = dev.read_reg(target)
        check("x%d round-trip 0x%016x" % (target, pattern),
              not rd_err and got == pattern, "read back 0x%016x" % got)
    dev.command(OPCODES["write_reg"], index=target, data=original)
    print("  restored x%d to 0x%016x" % (target, original))


def stage_inject(dev, pc_base):
    print("== instruction injection ==")
    # addi x31, x0, 42  -- a self-contained computation with a known result.
    dev.command(OPCODES["write_reg"], index=31, data=0)
    insn = encode_addi(31, 0, 42)
    status, elapsed = exec_insn(dev, insn, pc_base)
    got, error, _ = dev.read_reg(31)
    check("addi x31, x0, 42 computed", not error and got == 42,
          "x31 = 0x%016x (insn 0x%08x, %.1f us)" % (got, insn, elapsed * 1e6))
    check("no fault reported", not (status & ST_FAULTED), decode(status))

    # addi x30, x31, 1 -- consumes the previous result, proving register state
    # persists between injected instructions.
    insn = encode_addi(30, 31, 1)
    status, _ = exec_insn(dev, insn, pc_base + 4)
    got, error, _ = dev.read_reg(30)
    check("addi x30, x31, 1 sees previous result", not error and got == 43,
          "x30 = 0x%016x" % got)

    # Negative immediate, to exercise sign extension.
    insn = encode_addi(29, 0, -1)
    exec_insn(dev, insn, pc_base + 8)
    got, error, _ = dev.read_reg(29)
    check("addi x29, x0, -1 sign extends", not error and got == (1 << 64) - 1,
          "x29 = 0x%016x" % got)


def stage_fault(dev, pc_base):
    print("== faulting instruction ==")
    status, _ = exec_insn(dev, 0x00000000, pc_base)   # all zeros is illegal
    faulted = bool(status & ST_FAULTED)
    check("illegal instruction faults", faulted, decode(status))
    cause, c_err, _ = dev.cause()
    tval, t_err, _ = dev.tval()
    check("cause reported", not c_err and cause != 0 if faulted else True,
          "cause = %d (0x%x)  tval = 0x%x" % (cause, cause, tval))
    # Recover: a legal instruction must still execute after a fault.
    status, _ = exec_insn(dev, encode_addi(28, 0, 7), pc_base + 4)
    got, error, _ = dev.read_reg(28)
    check("recovers after fault", not error and got == 7, "x28 = 0x%016x" % got)
    check("fault flag cleared by next EXEC", not (status & ST_FAULTED), decode(status))


def stage_snapshot(dev):
    print("== diagnostic snapshot ==")
    _, error, _ = dev.command(OPCODES["snapshot"])
    check("SNAPSHOT accepted", not error)
    status = fase_status(dev)
    check("snapshot marked valid", bool(status & ST_SNAPSHOT), decode(status))
    values, errors = [], []
    for i in range(SNAPSHOT_ENTRIES):
        result, err, _ = dev.command(OPCODES["read_snapshot"], index=i)
        (errors if err else values).append(i)
    check("all %d entries readable" % SNAPSHOT_ENTRIES, not errors,
          "" if not errors else "errored: %s" % errors[:8])


def stage_negative(dev):
    print("== rejections ==")
    _, error, _ = dev.command(OPCODES["read_reg"], index=32)
    check("ReadReg index 32 rejected", error)
    _, error, _ = dev.command(OPCODES["read_snapshot"], index=48)
    check("ReadSnapshot index 48 rejected", error)
    _, error, _ = dev.command(11)
    check("unknown opcode 11 rejected", error)
    _, error, _ = dev.command(OPCODES["launch"], pc=0x80200001)
    check("Launch with odd PC rejected", error)


def stage_latency(dev, samples):
    print("== per-command latency, %d samples each ==" % samples)
    for name in ("status", "read_reg", "next_pc"):
        lat = []
        for _ in range(samples):
            lat.append(dev.command(OPCODES[name], index=1)[2])
        lat.sort()
        print("  %-10s min %.1f us  median %.1f us  p95 %.1f us" % (
            name, lat[0] * 1e6, lat[len(lat)//2] * 1e6, lat[int(len(lat)*0.95)] * 1e6))


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--bdf", default="0000:02:00.0")
    p.add_argument("--bar", type=int, default=0)
    p.add_argument("--samples", type=int, default=100)
    p.add_argument("--pc-base", type=lambda s: int(s, 0), default=0x80200000,
                   help="PC presented with injected instructions")
    p.add_argument("--yes-halt-the-cpu", action="store_true",
                   help="required: acknowledges that the running OS will stop")
    args = p.parse_args()

    if not args.yes_halt_the_cpu:
        print(__doc__, file=sys.stderr)
        print("Refusing to run without --yes-halt-the-cpu", file=sys.stderr)
        return 2

    with FasePcie(args.bdf, args.bar) as dev:
        dev.identify()
        print("FASE ABI v1 on %s BAR%d\n" % (args.bdf, args.bar))
        try:
            stage_halt(dev)
            saved = stage_regfile(dev)
            stage_writereg(dev, saved)
            stage_inject(dev, args.pc_base)
            stage_fault(dev, args.pc_base)
            stage_snapshot(dev)
            stage_negative(dev)
            stage_latency(dev, args.samples)
        except FaseError as exc:
            print("\nABORTED: %s" % exc, file=sys.stderr)
            failed.append("aborted: %s" % exc)

        print("\n== summary ==")
        print("  %d passed, %d failed" % (len(passed), len(failed)))
        for name in failed:
            print("  FAILED: %s" % name)
        print("\n  The core remains halted and owned by FASE. Reprogram the")
        print("  bitstream, or use Launch, to run code again.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
