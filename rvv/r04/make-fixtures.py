#!/usr/bin/env python3
"""Add R04 stress programs to the unchanged R02 Spike oracle."""
import importlib.util
from pathlib import Path
import argparse
s = importlib.util.spec_from_file_location('r02',Path(__file__).resolve().parents[1]/'r02/make-fixtures.py')
r = importlib.util.module_from_spec(s); s.loader.exec_module(r)

def chain():
    p=r.Program(0x523034)
    for i in range(16):
        p.emit(r.vector(44,16,8,11,6),2,2,64,scalar=0x817fff01+i,label=f'p6-dot-{i}')
    return p

def prefetch():
    p=r.Program(0x523035)
    p.emit(r.vector(45,16,8,24,2),3,3,64,label='r04-slow-reader')
    for i in range(16):
        p.emit(r.vector(44,16,8,11,6),2,2,64,scalar=0x817f00ff+i,label='r04-queued-dot')
    p.emit(r.memory(8,2),2,2,64,base=r.BASE+0x8000,label='r04-prefetch-war')
    p.emit(r.memory(12,2),2,2,64,base=r.BASE+0x9000,label='r04-prefetch-full')
    p.emit(r.memory(12,2),2,2,64,base=r.BASE+0xa001,label='r04-zero-start-load')
    p.lines[-2:-2] = ['li t0, 64', 'csrw vstart, t0']
    p.emit(r.memory(12,2,True),2,2,64,base=r.BASE+0xa001,label='r04-store-barrier')
    p.emit(r.memory(8,2),2,2,64,base=r.BASE+0xa001,label='r04-overlap-after-store')
    for i in range(272):
        # Advance the age through wrap with ordinary committed memory traffic.
        p.emit(r.memory(12,2),2,2,64,base=r.BASE+0x9000,label='r04-wrap-load')
        p.emit(r.vector(44,16,12,11,6),2,2,64,scalar=i,label='r04-wrap-dot')
    # Bypass tags can outlive the modular active window. An external write must
    # invalidate even when its age is over half a sequence space away.
    p.emit(r.vector(44,16,12,11,6),2,2,64,scalar=1,label='r04-cache-old')
    for i in range(130):
        p.emit(r.vector(0,6,4,5,0),2,0,16,label='r04-cache-age-gap')
    p.emit(r.memory(16,2),2,2,64,base=r.BASE+0xb000,label='r04-cache-external-write')
    for i in range(100):
        p.emit(r.vector(0,6,4,5,0),2,0,16,label='r04-cache-age-gap')
    p.emit(r.vector(44,16,12,11,6),2,2,64,scalar=2,label='r04-cache-after-wrap')
    return p

def random(seed):
    p=r.randomized(seed)
    p.emit(r.vector(45,16,8,24,2),3,3,64,label='r04-random-slow')
    for i in range(8):
        p.emit(r.vector(44,16,8,11,6),2,2,64,scalar=p.rng.getrandbits(32),label='r04-random-chain')
    p.emit(r.memory(12,2),2,2,64,base=r.BASE+0xa000,label='r04-random-prefetch')
    return p

def main():
    a=argparse.ArgumentParser(); a.add_argument('out',type=Path); a.add_argument('--reference',type=Path,required=True)
    a.add_argument('--random',type=int,default=0); args=a.parse_args()
    if args.random:
        for seed in range(args.random):
            r.run(random(seed),args.out/f'seed-{seed:04}',args.reference)
            if seed%50==0: print(f'R04_SPIKE seed={seed}',flush=True)
    else:
        r.run(chain(),args.out/'chain',args.reference); r.run(prefetch(),args.out/'prefetch',args.reference)
if __name__=='__main__': main()
