#!/usr/bin/env python3
"""Independent byte arithmetic validates executed extension checkpoint pairs."""
import json
from pathlib import Path
import sys

def signed(x): return x-256 if x >=128 else x

root=Path(sys.argv[1])
snapshots=[json.loads(line[len('R02_TRACE '):]) for line in (root/'spike.log').read_text().splitlines() if line.startswith('R02_TRACE ')]
checked=0
for before,after in zip(snapshots,snapshots[1:]):
    word=before['instruction']
    if word & 0xfc00707f not in (0xb0006057,0xa8006057): continue
    sew=(before['vtype']>>3)&7
    assert sew==2
    vd=(word>>7)&31; vs2=(word>>20)&31; vm=(word>>25)&1
    source=bytes.fromhex(''.join(before['regs'][vs2:]))
    old=bytes.fromhex(''.join(before['regs'][vd:]))
    result=bytes.fromhex(''.join(after['regs'][vd:]))
    mask=bytes.fromhex(before['regs'][0]); scalar=before['rs1'] & 0xffffffff
    for i in range(before['vl']):
        a=int.from_bytes(source[4*i:4*i+4],'little')
        expected=int.from_bytes(old[4*i:4*i+4],'little')
        if i>=before['vstart'] and (vm or (mask[i//8]>>(i%8))&1):
            for j in range(4):
                av=signed((a>>(8*j))&255)
                bv=(scalar>>(8*j))&255
                if word & 0xfc00707f == 0xb0006057: bv=signed(bv)
                expected=(expected+av*bv)&0xffffffff
        actual=int.from_bytes(result[4*i:4*i+4],'little')
        assert actual==expected,(hex(word),i,hex(expected),hex(actual))
    checked+=1
assert checked>0
print(f'R02_PLUGIN_PASS instructions={checked}')
