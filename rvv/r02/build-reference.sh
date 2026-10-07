#!/usr/bin/env bash
set -euo pipefail
R02_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
R02_REF=$(realpath -m "$1")
R02_SPIKE_SRC=${R02_SPIKE_SRC:-/home/chen/work/breeze-spike-simulator}
R02_SPIKE_BUILD=${R02_SPIKE_BUILD:-/home/chen/work/breeze-spike-simulator-build}
mkdir -p "$R02_REF"
if [[ -n "${R04_SPIKE_PREFIX:-}" ]]; then
  cp "$R04_SPIKE_PREFIX/PROVENANCE.txt" "$R02_REF/spike-provenance.txt"
  cp "$R04_SPIKE_PREFIX/exported-sha256.txt" "$R02_REF/spike-exported-sha256.txt"
  sha256sum "$R04_SPIKE_PREFIX/bin/spike" > "$R02_REF/spike-binary-sha256.txt"
  cat "$R04_SPIKE_PREFIX/source-sha256.txt" > "$R02_REF/spike-sha.txt"
  : > "$R02_REF/spike-status.txt"
  nice -n 10 g++ -std=c++17 -O2 -fPIC -shared -I"$R04_SPIKE_PREFIX/include/riscv" \
    -I"$R04_SPIKE_PREFIX/include/fesvr" -I"$R04_SPIKE_PREFIX/include/softfloat" \
    "$R02_ROOT/rvv/r02/spike-extension.cc" -o "$R02_REF/r02_dot.so"
else
if [[ -n "${R04_SPIKE_SOURCE_SHA:-}" ]]; then
  echo "$R04_SPIKE_SOURCE_SHA" > "$R02_REF/spike-sha.txt"
  sha256sum "$R02_SPIKE_BUILD/spike" > "$R02_REF/spike-binary-sha256.txt"
  : > "$R02_REF/spike-status.txt"
else
  git -C "$R02_SPIKE_SRC" rev-parse HEAD > "$R02_REF/spike-sha.txt"
  git -C "$R02_SPIKE_SRC" status --short > "$R02_REF/spike-status.txt"
fi
nice -n 10 g++ -std=c++17 -O2 -fPIC -shared -I"$R02_SPIKE_BUILD" -I"$R02_SPIKE_SRC" \
  -I"$R02_SPIKE_SRC/riscv" -I"$R02_SPIKE_SRC/fesvr" -I"$R02_SPIKE_SRC/softfloat" \
  "$R02_ROOT/rvv/r02/spike-extension.cc" -o "$R02_REF/r02_dot.so"
sha256sum "$R02_REF/r02_dot.so" > "$R02_REF/plugin-sha256.txt"
fi
