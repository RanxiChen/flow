#!/usr/bin/env python3
"""Generate executable programs; the actual Spike checkpoints are the oracle."""
import argparse
import json
import os
from pathlib import Path
import random
import subprocess

BASE = 0x81000000
SIZE = 73728
PIN = '813cba14c9f0a731b4904925851a2820a6320b5b'

def vector(f6, vd, vs2=0, vs1=0, f3=0, masked=False):
    return (f6 << 26) | (int(not masked) << 25) | (vs2 << 20) | (vs1 << 15) | (f3 << 12) | (vd << 7) | 0x57

def memory(vd, sew, store=False, masked=False, mode=0, nf=0):
    return (nf << 29) | (mode << 26) | (int(not masked) << 25) | (10 << 15) | ((0 if sew == 0 else sew+4) << 12) | (vd << 7) | (0x27 if store else 0x07)

class Program:
    def __init__(self, seed, vlen=512):
        self.rng = random.Random(seed)
        self.vlen = vlen
        self.initial = bytes(self.rng.randrange(256) for _ in range(SIZE))
        self.lines = ['.section .text', '.global _start', '_start:', 'li t0, 0x600', 'csrs mstatus, t0']
        self.metadata = []
        for reg in range(32):
            self.emit(memory(reg,0),0,0,vlen//8,base=BASE+reg*(vlen//8),label=f'init-v{reg}')

    def emit(self, word, sew, lmul, vl, scalar=0, base=BASE+8192, label=''):
        self.lines += [f'li a3, {vl}', f'vsetvli zero, a3, e{8 << sew}, '+{0:'m1',1:'m2',2:'m4',3:'m8',5:'mf8',6:'mf4',7:'mf2'}[lmul]+', tu, mu',
                       f'li a0, {base}', f'li a1, {scalar & ((1 << 64)-1)}', '.word 0x0000000b', f'.word 0x{word:08x}']
        self.metadata.append(label)

    def finish(self):
        # Serialize the final architectural VRF through the same store contract.
        for reg in range(32):
            self.emit(memory(reg,0,True),0,0,self.vlen//8,base=BASE+65536+reg*(self.vlen//8),label=f'dump-v{reg}')
        self.lines += ['.word 0x0000000b', 'nop', f'li a0, {BASE}', f'li a2, {SIZE}',
                       f'.word 0x{((12 << 20) | (10 << 15) | 0x100b):08x}',
                       'la t0, tohost', 'li t1, 1', 'sd t1, 0(t0)', '1: j 1b',
                       '.section .data', '.global image', 'image:']
        self.lines += ['.byte '+','.join(str(b) for b in self.initial[i:i+64]) for i in range(0,SIZE,64)]
        self.lines += ['.section .tohost,"aw",@progbits', '.balign 64', '.global tohost', 'tohost: .dword 0', '.global fromhost', 'fromhost: .dword 0']
        return '\n'.join(self.lines)+'\n'

def directed():
    p = Program(0x523032)
    for sew in range(4):
        vl = min(7,512//(8 << sew))
        for masked in (False,True):
            for f6,f3 in [(0,0),(0,4),(2,0),(9,4),(40,4)]:
                p.emit(vector(f6,4,8,11 if f3==4 else 12,f3,masked),sew,0,vl,scalar=-3,label=f'alu-{sew}-{f6}-{f3}-{masked}')
        p.emit(vector(23,4,0,11,4),sew,0,vl,scalar=-127,label='move-x')
        p.emit(vector(23,5,0,4,0),sew,0,vl,label='move-v')
        p.emit(vector(45,4,8,12,2),sew,0,vl,label='macc')
        p.emit(memory(8,sew),sew,0,vl,base=BASE+0x4001,label='unaligned-load')
        p.emit(memory(8,sew,True),sew,0,vl,base=BASE+0x5003,label='unaligned-store')
        p.emit(memory(8,sew),sew,0,vl,base=BASE+0x5ffb,label='discontinuous-cross-page-load')
        p.emit(memory(8,sew,True),sew,0,vl,base=BASE+0x6ffd,label='discontinuous-cross-page-store')
        p.emit(vector(16,15,8,0,2),sew,0,0,label='scalar-vl-zero')
    for f6 in (44,42):
        for scalar in (0x80808080,0x7f7f7f7f,0xff00fe01):
            p.emit(vector(f6,16,8,11,6),2,2,64,scalar=scalar,label='dot-sign-overflow')
    p.emit(memory(8,2),2,2,64,base=BASE+0x8000,label='raw-producer')
    p.emit(vector(44,16,8,11,6),2,2,64,scalar=0x807fff01,label='raw-consumer')
    p.emit(memory(8,2),2,2,64,base=BASE+0x9000,label='war-young-load')
    p.emit(memory(12,2),2,2,64,base=BASE+0xa000,label='concurrent-load')
    for overlap in (True,False):
        b = BASE+0xb003
        other = b if overlap else BASE+0xc001
        p.emit(memory(8,2,True),2,2,64,base=b,label='store-load-old')
        p.emit(memory(12,2),2,2,64,base=other,label='store-load-young')
        p.emit(memory(8,2),2,2,64,base=b,label='load-store-old')
        p.emit(memory(12,2,True),2,2,64,base=other,label='load-store-young')
        p.emit(memory(16,2,True),2,2,64,base=other,label='store-store-young')
    for lmul in (0,1,2,3,5,6,7):
        vlmax = int(512/32 * (2**lmul if lmul<4 else 2**(lmul-8)))
        p.emit(vector(0,8,16,24,0),2,lmul,max(0,vlmax-1),label='lmul-tail')
        p.emit(memory(8,2),2,lmul,0,base=0xdead0000,label='zero-load-no-translation')
    return p

def randomized(seed):
    p = Program(seed)
    rng = p.rng
    for _ in range(64):
        sew = rng.randrange(4); lm = rng.choice([0,1,2,3,5,6,7])
        group = 1 << lm if lm < 4 else 1
        vmax = int(512/(8 << sew)*(2**lm if lm<4 else 2**(lm-8)))
        vl = rng.randrange(vmax+1)
        vd,vs2,vs1 = [rng.choice(list(range(group,32,group))) for _ in range(3)]
        op = rng.randrange(12)
        scalar = rng.getrandbits(64)
        if op < 2:
            addr = BASE+rng.randrange(8192,48000)
            p.emit(memory(vd,sew,op==1),sew,lm,vl,base=addr,label='random-memory')
        else:
            f6,f3 = {2:(0,0),3:(0,4),4:(2,0),5:(9,4),6:(40,4),7:(23,4),8:(23,0),9:(45,2),10:(44,6),11:(42,6)}[op]
            if op >= 10:
                sew = 2; vmax = int(16*(2**lm if lm<4 else 2**(lm-8))); vl = rng.randrange(vmax+1)
            masked = op in (2,3,4,5,6,9,10,11) and bool(rng.getrandbits(1))
            p.emit(vector(f6,vd,0 if f6==23 else vs2,11 if f3 in (4,6) else vs1,f3,masked),sew,lm,vl,scalar=scalar,label='random-compute')
    return p

def gemv():
    p = Program(0x47454d56)
    p.emit(vector(23,16,0,11,4),2,2,64,scalar=0,label='zero-acc')
    p.emit(vector(23,20,0,11,4),2,2,64,scalar=0,label='zero-output')
    for k in range(0,896,4):
        reg = 8 if (k//4)%2==0 else 12
        p.emit(memory(reg,2),2,2,64,base=BASE+4096+(k//4)*256,label=f'gemv-load-{k}')
        p.emit(vector(44,16,reg,11,6),2,2,64,scalar=p.rng.getrandbits(32),label=f'gemv-dot-{k}')
        if k%32==28:
            p.emit(vector(0,20,20,16,0),2,2,64,label='block-accumulate')
            p.emit(vector(23,16,0,11,4),2,2,64,scalar=0,label='block-reset')
    p.emit(memory(20,2,True),2,2,64,base=BASE+0x10800,label='kernel-final-store')
    return p

def run(p, path, reference):
    path.mkdir(parents=True,exist_ok=True)
    source = path/'program.S'; source.write_text(p.finish())
    compiler = os.environ.get('R02_CC','/home/chen/Tool/RISCV/bin/riscv64-unknown-linux-gnu-gcc')
    spike = os.environ.get('R02_SPIKE','/home/chen/work/breeze-spike-simulator-build/spike')
    root = Path(__file__).resolve().parent
    with (path/'compile.log').open('w') as log:
        subprocess.run(['nice','-n','10',compiler,'-nostdlib','-nostartfiles','-static','-march=rv64gcv','-mabi=lp64d','-Wl,--build-id=none','-T',str(root/'link.ld'),str(source),'-o',str(path/'program.elf')],stdout=log,stderr=log,check=True)
    with (path/'spike.log').open('w') as log:
        subprocess.run(['nice','-n','10',spike,'--isa=rv64gcv','--varch',f'vlen:{p.vlen},elen:64',f'--extlib={reference}/r02_dot.so','--extension=r02_dot',str(path/'program.elf')],stdout=log,stderr=log,check=True,timeout=60)
    lines = (path/'spike.log').read_text().splitlines()
    snapshots = [json.loads(l[len('R02_TRACE '):]) for l in lines if l.startswith('R02_TRACE ')]
    mem = [l.split(' ',2) for l in lines if l.startswith('R02_MEMORY ')]
    records = []
    for index,s in enumerate(snapshots):
        if s['instruction'] & 127 not in (7,0x27,0x57): continue
        s['label'] = p.metadata[len(records)]
        if (s['instruction'] & 0xfc00707f) == (16<<26 | 2<<12 | 0x57):
            s['scalarExpected'] = snapshots[index+1]['xpr'][s['rd']]
        records.append(s)
    assert len(records)==len(p.metadata) and len(mem)==1
    fixture = {'base':BASE,'initial':p.initial.hex(),'expected':mem[0][2],'records':records,'dotCommit':PIN}
    (path/'fixture.json').write_text(json.dumps(fixture,separators=(',',':'))+'\n')
    return fixture

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('out',type=Path); ap.add_argument('--reference',type=Path,required=True)
    ap.add_argument('--kind',choices=['directed','random','gemv'],required=True)
    ap.add_argument('--seeds',type=int,default=1000); args = ap.parse_args()
    if args.kind=='random':
        for seed in range(args.seeds):
            run(randomized(seed),args.out/f'seed-{seed:04}',args.reference)
            if seed%50==0: print(f'SPIKE seed={seed}',flush=True)
    else: run(directed() if args.kind=='directed' else gemv(),args.out/args.kind,args.reference)

if __name__=='__main__': main()
