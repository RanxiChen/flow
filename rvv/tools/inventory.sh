#!/usr/bin/env bash
set -euo pipefail
set -x
date -Is
hostname
nproc
lscpu
ps -eo pid,ppid,ni,pcpu,comm
git -C "$HOME/FUN/flow" status --short
git -C "$HOME/FUN/flow" branch --show-current
git -C "$HOME/FUN/flow" rev-parse HEAD
git -C "$HOME/FUN/flow" worktree list
git -C "$HOME/FUN/flow-rvv-r01" status --short
git -C "$HOME/FUN/flow-rvv-r01" branch --show-current
git -C "$HOME/FUN/flow-rvv-r01" rev-parse HEAD
/usr/bin/riscv64-linux-gnu-gcc --version
/usr/bin/riscv64-unknown-elf-gcc --version
"$HOME/Tool/rvtoolchain/bin/riscv64-unknown-linux-gnu-gcc" --version
"$HOME/Tool/RISCV/bin/riscv64-unknown-linux-gnu-gcc" --version
"$HOME/opt/act4/gcc-2026.07.15/bin/riscv64-unknown-elf-gcc" --version
clang --version
/usr/bin/qemu-riscv64 --version
"$HOME/opt/act4/gcc-2026.07.15/bin/qemu-riscv64" --version
"$HOME/Tool/RISCV/bin/spike" --help
file "$HOME/Tool/RISCV/riscv64-unknown-linux-gnu/bin/pk"
sha256sum "$HOME/Tool/RISCV/bin/spike" "$HOME/Tool/RISCV/riscv64-unknown-linux-gnu/bin/pk" \
    "$HOME/opt/act4/gcc-2026.07.15/bin/qemu-riscv64"
cmake --version
ninja --version
pkg-config --modversion glib-2.0
python3 --version
python3 -c 'import numpy; print(numpy.__version__)'
