#!/usr/bin/env python3
"""Extract the ten report_timing paths without rounding their Vivado values."""
import json, re, sys
from pathlib import Path
text=Path(sys.argv[1]).read_text()
paths=[]
for block in re.split(r'(?=Slack \((?:MET|VIOLATED)\))',text)[1:]:
    def get(pattern):
        m=re.search(pattern,block)
        if not m: raise ValueError(pattern)
        return m.groups()
    slack,=get(r'Slack \((?:MET|VIOLATED)\)\s*:\s*([-\d.]+)ns')
    source,=get(r'Source:\s*([^\n]+)')
    destination,=get(r'Destination:\s*([^\n]+)')
    total,logic,route=get(r'Data Path Delay:\s*([\d.]+)ns\s*\(logic\s+([\d.]+)ns.*?route\s+([\d.]+)ns')
    levels,=get(r'Logic Levels:\s*(\d+)')
    paths.append(dict(source=source.strip(),destination=destination.strip(),levels=int(levels),slack=float(slack),data=float(total),logic=float(logic),route=float(route)))
assert len(paths)==10, len(paths)
print(json.dumps(paths,indent=2))
