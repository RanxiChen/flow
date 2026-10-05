#!/usr/bin/env bash
set -euo pipefail
set -x
R01_RUN=$(realpath "$1")
R01_ROOT=$(cd "$(dirname "$0")/.." && pwd)
R01_QEMU_PREFIX=$HOME/opt/act4/gcc-2026.07.15
mkdir -p "$R01_RUN/probe"
gcc -shared -fPIC -I"$R01_QEMU_PREFIX/include" $(pkg-config --cflags glib-2.0) \
    "$R01_ROOT/tools/probe-registers.c" -o "$R01_RUN/probe/registers.so"
"$HOME/Tool/RISCV/bin/riscv64-unknown-linux-gnu-gcc" -O2 -static -march=rv64gcv -mabi=lp64d \
    "$R01_ROOT/tools/intrinsics-smoke.c" -o "$R01_RUN/probe/gcc-smoke"
clang --target=riscv64-linux-gnu --gcc-toolchain=/usr -O2 -static -march=rv64gcv -mabi=lp64d \
    "$R01_ROOT/tools/intrinsics-smoke.c" -o "$R01_RUN/probe/clang-smoke"
for compiler in gcc clang; do
    for vlen in 128 256 512 1024; do
        nice -n 10 "$R01_QEMU_PREFIX/bin/qemu-riscv64" \
            -cpu "rv64,v=true,vlen=$vlen,elen=64,vext_spec=v1.0" \
            -plugin "$R01_RUN/probe/registers.so" "$R01_RUN/probe/$compiler-smoke" \
            > "$R01_RUN/probe/$compiler-$vlen.stdout" 2> "$R01_RUN/probe/$compiler-$vlen.log"
    done
done
