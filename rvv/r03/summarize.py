#!/usr/bin/env python3
"""Extract unchanged Vivado hierarchy columns and critical-path descriptions."""
import json,re,sys
from pathlib import Path
p=Path(sys.argv[1]); rows={}
for line in (p/'utilization-hierarchical.rpt').read_text().splitlines():
    fields=[x.strip() for x in line.split('|')[1:-1]]
    if len(fields)==11 and fields[2].isdigit():
        rows[fields[0]]=dict(zip(('LUT','logicLUT','LUTRAM','SRL','FF','RAMB36','RAMB18','URAM','DSP'),map(int,fields[2:])))
summary=(p/'timing-summary.rpt').read_text()
m=re.search(r'WNS\(ns\).*?\n\s*-+.*?\n\s*([-\d.]+)',summary,re.S)
print(json.dumps({'hierarchy':rows,'WNS':float(m[1]) if m else None},indent=2))
