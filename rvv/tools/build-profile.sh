#!/usr/bin/env bash
set -euo pipefail
set -x
R01_RUN=$(realpath "$1")
R01_ROOT=$(cd "$(dirname "$0")/.." && pwd)
R01_QEMU_PREFIX=$HOME/opt/act4/gcc-2026.07.15
mkdir -p "$R01_RUN/profile"
gcc -Wall -Wextra -Werror -shared -fPIC -O2 -I"$R01_QEMU_PREFIX/include" \
    $(pkg-config --cflags glib-2.0) "$R01_ROOT/tools/profile.c" -o "$R01_RUN/profile/profile.so"
"$HOME/Tool/RISCV/bin/riscv64-unknown-linux-gnu-gcc" -O2 -fno-tree-vectorize -static -march=rv64gcv -mabi=lp64d \
    "$R01_ROOT/tools/profile-smoke.c" -o "$R01_RUN/profile/smoke"
for mode in rvv scalar; do
    python3 "$R01_ROOT/tools/symbols.py" "$R01_RUN/build/$mode/r01-runner" "$R01_RUN/profile/$mode.symbols"
done
python3 "$R01_ROOT/tools/symbols.py" "$R01_RUN/profile/smoke" "$R01_RUN/profile/smoke.symbols"
nice -n 10 "$R01_QEMU_PREFIX/bin/qemu-riscv64" -cpu rv64,v=true,vlen=128,elen=64,vext_spec=v1.0 \
    -plugin "$R01_RUN/profile/profile.so,symbols=$R01_RUN/profile/smoke.symbols,output=$R01_RUN/profile/smoke" \
    "$R01_RUN/profile/smoke"
python3 - "$R01_RUN/profile/smoke-functions.csv" "$R01_RUN/profile/smoke-vectors.csv" <<'PY'
import csv, sys
functions = list(csv.DictReader(open(sys.argv[1])))
vectors = list(csv.DictReader(open(sys.argv[2])))
for phase in ('prefill', 'decode'):
    rows = [r for r in functions if r['phase'] == phase]
    assert sum(int(r['load_unit']) for r in rows) == 16, (phase, rows)
    assert sum(int(r['store_unit']) for r in rows) == 16, (phase, rows)
    assert sum(int(r['vector']) for r in rows) == 3, (phase, rows)
    data = [r for r in vectors if r['phase'] == phase and not r['opcode'].startswith('vset')]
    assert sum(int(r['count']) for r in data) == 2, data
    assert all(int(r['sew']) == 8 and int(r['vl']) == 16 and int(r['lmul_log2']) == 0 for r in data), data
print('PROFILE_SMOKE_PASS: per phase 3 vector instructions, 16 load bytes, 16 store bytes, SEW8 LMUL1 VL16')
PY
