"""Compare BE2 architectural ledgers with Spike. Custom-0 Ok is a no-op.

The RAM-backed MMIO address supplies the same value as the randomized backend
fixture; bus scheduling/TLB waits are timing perturbations, not ISA semantics.
Precise exceptions and interrupts remain covered by the separate RTL tests.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess


def elf(ledger):
    base = 0x80000000
    ram = bytearray(4096)
    for item in ledger['memory']:
        a, v = int(item['address']), int(item['data'])
        if a < len(ram):
            struct.pack_into('<Q', ram, a, v)
    program = ledger['program']
    for item in program:
        pc, inst = int(item['pc']), int(item['inst'])
        if inst & 0x7f == 0x0b:
            assert not inst & (1 << 12), 'Exc is outside the no-op adapter'
            inst = 0x13
        # Map the fixture's low RAM to Spike DRAM, leaving x0 immutable.
        # x29 is unused by the DUT program; the reference prologue sets it.
        if inst & 0x7f in (3, 7) and (inst >> 15) & 31 == 0:
            inst |= 29 << 15
        struct.pack_into('<I', ram, pc, inst)
    end = int(program[-1]['pc']) + 4
    struct.pack_into('<I', ram, end, 0x6f)  # reference-only terminating loop
    start = int(program[0]['pc'])
    struct.pack_into('<II', ram, start-8, 0x00100e93, 0x01fe9e93)  # x29=1<<31
    ident = b'\x7fELF' + bytes([2, 1, 1]) + bytes(9)
    header = struct.pack('<16sHHIQQQIHHHHHH', ident, 2, 243, 1,
                         base+start-8, 64, 0x2020, 0, 64, 56, 2, 64, 2, 1)
    ph0 = struct.pack('<IIQQQQQQ', 1, 7, 4096, base, base, 4096, 4096, 4096)
    ph1 = struct.pack('<IIQQQQQQ', 1, 6, 8192, 0x12001000, 0x12001000, 8, 4096, 4096)
    mmio = next(int(v['data']) for v in ledger['memory'] if int(v['address']) == 0x12001000)
    strings = b'\0.shstrtab\0'
    sections = bytes(64) + struct.pack('<IIQQQQIIQQ', 1, 3, 0, 0, 0x2010,
                                     len(strings), 0, 0, 1, 0)
    image = (header + ph0 + ph1).ljust(4096, b'\0') + ram + struct.pack('<Q', mmio)
    return (image.ljust(0x2010,b'\0') + strings).ljust(0x2020,b'\0') + sections


def compare(ledger, output):
    lo, hi = int(ledger['program'][0]['pc']), int(ledger['program'][-1]['pc']) + 4
    commits, writes = [], []
    for line in output.splitlines():
        # --log-commits produces the privilege, PC and instruction first.
        m = re.match(r'core\s+\d+:\s+[0-3]\s+(0x[0-9a-f]+)\s+\(0x[0-9a-f]+\)(.*)', line)
        if not m:
            continue
        pc = int(m[1], 16) - 0x80000000
        if not lo <= pc < hi:
            continue
        commits.append(pc)
        for w in re.finditer(r'\b([xf])\s*(\d+)\s+(0x[0-9a-f]+)', m[2]):
            bank, rd, data = ('gpr' if w[1] == 'x' else 'fpr'), int(w[2]), int(w[3], 16)
            if bank == 'gpr' and rd == 0:
                continue
            writes.append((bank, rd, data))
    expected = [int(p) for p in ledger['commits']]
    assert commits == expected, ('commit order/once', ledger['seed'], commits, expected)
    actual_writes = Counter((w['bank'], w['rd'], int(w['data'])) for w in ledger['writes'])
    assert Counter(writes) == actual_writes, ('register effects', ledger['seed'], Counter(writes), actual_writes)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('ledgers', type=Path)
    parser.add_argument('out', type=Path)
    parser.add_argument('--spike', required=True, type=Path)
    parser.add_argument('--seeds', type=int, default=1000)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    passed = 0
    for seed in range(args.seeds):
        source = args.ledgers / f'seed-{seed}.json'
        ledger = json.loads(source.read_text())
        assert ledger['seed'] == seed
        executable = args.out / f'seed-{seed}.elf'
        executable.write_bytes(elf(ledger))
        command = [str(args.spike), '--isa=RV64IMAFDC', '-l', '--log-commits',
                   f'--instructions={len(ledger["program"])+32}',
                   '-m0x80000000:0x10000,0x12001000:0x1000', str(executable)]
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=15)
        (args.out / f'seed-{seed}.log').write_text(result.stdout)
        assert result.returncode == 0, (seed, result.returncode, result.stdout)
        compare(ledger, result.stdout)
        passed += 1
    summary = dict(seeds=passed, failures=0, spike=str(args.spike),
                   spike_sha256=hashlib.sha256(args.spike.read_bytes()).hexdigest(),
                   custom0='Ok no-op adapter; Exc excluded', mmio='fixture value in mapped RAM',
                   comparison='every actual committed PC once/in order; exact register write multiset')
    (args.out / 'result.json').write_text(json.dumps(summary, indent=2)+'\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
