#!/usr/bin/env bash
set -euo pipefail
R02_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
R02_REF=$(realpath -m "$1")
R02_SPIKE_SRC=${R02_SPIKE_SRC:-/home/chen/work/breeze-spike-simulator}
R02_SPIKE_BUILD=${R02_SPIKE_BUILD:-/home/chen/work/breeze-spike-simulator-build}
mkdir -p "$R02_REF"
git -C "$R02_SPIKE_SRC" rev-parse HEAD > "$R02_REF/spike-sha.txt"
git -C "$R02_SPIKE_SRC" status --short > "$R02_REF/spike-status.txt"
nice -n 10 g++ -std=c++20 -O2 -fPIC -shared -I"$R02_SPIKE_BUILD" -I"$R02_SPIKE_SRC" \
  -I"$R02_SPIKE_SRC/riscv" -I"$R02_SPIKE_SRC/fesvr" -I"$R02_SPIKE_SRC/softfloat" \
  "$R02_ROOT/rvv/r02/spike-extension.cc" -o "$R02_REF/r02_dot.so"
sha256sum "$R02_REF/r02_dot.so" > "$R02_REF/plugin-sha256.txt"
