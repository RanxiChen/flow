#!/usr/bin/env bash
set -euo pipefail
R03_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
R03_C1=$(realpath "$1")
R03_S1=$(realpath -m "$2")
cd "$R03_ROOT"
test -z "$(git status --porcelain --untracked-files=no)"
test "$(cat "$R03_C1/sha.txt")" = "$(git rev-parse HEAD)"
mkdir -p "$R03_S1"
cp "$R03_C1/sha.txt" "$R03_S1/sha.txt"
cp -a "$R03_C1/rtl/512-512" "$R03_S1/rtl"
cp rvv/r03/synthesize.tcl rvv/r03/clock.xdc rvv/r03/summarize.py rvv/r03/run-snapshot.sh "$R03_S1/"
cd "$R03_S1"
sha256sum rtl/*.sv synthesize.tcl clock.xdc summarize.py run-snapshot.sh > input-sha256.txt
touch ready.txt
