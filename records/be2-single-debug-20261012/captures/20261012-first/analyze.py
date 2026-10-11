"""Analyze exported ILA CSV; this is evidence processing, not a hardware test."""
import collections
import csv
import gzip
import hashlib
import json
import pathlib

root = pathlib.Path(__file__).resolve().parent
results = []
for number in (1, 2):
    name = f"snapshot{number}.csv"
    path = root / name
    raw = path.read_bytes() if path.exists() else gzip.decompress((root / (name + '.gz')).read_bytes())
    lines = raw.decode().splitlines()
    reader = csv.DictReader(lines)
    next(reader)  # Vivado radix description row, not a sample.
    rows = list(reader)
    retired = [row for row in rows if int(row['dbg_retire_valid'], 16)]
    pending = []
    reads = collections.Counter()
    for row in rows:
        if row['dbg_mmio_ar_valid'] == row['dbg_mmio_ar_ready'] == '1':
            pending.append(row['dbg_mmio_ar_addr[31:0]'])
        if row['dbg_mmio_r_valid'] == row['dbg_mmio_r_ready'] == '1' and pending:
            reads[(pending.pop(0), row['dbg_mmio_r_data[63:0]'], row['dbg_mmio_r_resp[1:0]'])] += 1
    results.append({
        'file': name, 'sha256': hashlib.sha256(raw).hexdigest(),
        'samples': len(rows), 'retirements': len(retired),
        'retired_pc_counts': dict(collections.Counter(row['dbg_retire_pc[63:0]'] for row in retired)),
        'max_no_retire_cycles': max(int(row['dbg_no_retire_cycles[31:0]'], 16) for row in rows),
        'flags': {field: sorted(set(row[field] for row in rows)) for field in (
            'dbg_reset', 'dbg_pll_locked', 'dbg_hang', 'dbg_hang_reasons[14:0]',
            'dbg_hart_fatal', 'dbg_hart_estop', 'dbg_memory_monitor_error_seen',
            'dbg_mmio_monitor_error_seen', 'dbg_memory_monitor_accounting_fault',
            'dbg_mmio_monitor_accounting_fault')},
        'paired_mmio_reads': [{'addr': addr, 'rdata': data, 'resp': resp, 'count': count}
                              for (addr, data, resp), count in sorted(reads.items())],
        'retired_loads': [{'pc': pc, 'addr': addr, 'value': data, 'count': count}
                         for (pc, addr, data), count in sorted(collections.Counter(
                             (row['dbg_retire_pc[63:0]'], row['dbg_retire_mem_addr[63:0]'],
                              row['dbg_retire_mem_rdata[63:0]'])
                             for row in retired if row['dbg_retire_mem_en'] == '1'
                             and row['dbg_retire_mem_is_write'] == '0').items())],
        'counter_endpoints': {field: [rows[0][field], rows[-1][field]]
                              for field in rows[0] if 'monitor_' in field and '_count[' in field},
    })
(root / 'analysis.json').write_text(json.dumps(results, indent=2) + '\n')
print(json.dumps([{'capture': r['file'], 'retirements': r['retirements'],
                  'distinct_pcs': len(r['retired_pc_counts'])} for r in results]))
