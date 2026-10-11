#!/usr/bin/env python3
"""Check persistent data placement exported from two exact-source Vivado DCPs."""
import csv
import hashlib
import json
from pathlib import Path
import re
import sys

before_path, after_path, output = map(Path, sys.argv[1:])
def read(path):
    with path.open() as stream:
        rows = list(csv.DictReader(stream, delimiter='\t'))
    data = {row['name']: row for row in rows}
    if len(data) != len(rows) or not data:
        raise ValueError('Empty or duplicate primitive names')
    if any(name.startswith('IDELAYCTRL') for name in data):
        raise ValueError('Calibration network must be explicitly scoped outside data audit')
    return data
before, after = read(before_path), read(after_path)
errors = []
bel_count = 0
for name, row in before.items():
    actual = after.get(name)
    if actual is None or row['loc'] != actual['loc']:
        errors.append({'name': name, 'field': 'loc', 'before': row, 'after': actual})
    if re.fullmatch(r'LUT[1-6]|FD[RCPS]E', row['ref']):
        bel_count += 1
        if actual is None or row['bel'] != actual['bel']:
            errors.append({'name': name, 'field': 'bel', 'before': row, 'after': actual})
result = {'persistent_data_LOC_checked': len(before),
          'simple_LUT_FF_BEL_checked': bel_count,
          'errors': len(errors), 'first_errors': errors[:20],
          'scope_excluded': 'top-level IDELAYCTRL* calibration network recreated by placer',
          'input_sha256': {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                           for p in (before_path, after_path)}}
output.write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({k: v for k, v in result.items() if k != 'first_errors'}))
if errors:
    raise SystemExit(1)
