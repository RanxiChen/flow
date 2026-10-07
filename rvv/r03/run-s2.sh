#!/usr/bin/env bash
set -euo pipefail
R03_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
R03_S1=$(realpath "$1")
R03_EVIDENCE=$(realpath -m "$2")
mkdir -p "$R03_EVIDENCE"
trap 'R03_RC=$?; printf "%s\n" "$R03_RC" > "$R03_EVIDENCE/exit-code.txt"' EXIT
cd "$R03_ROOT"
git rev-parse HEAD > "$R03_EVIDENCE/sha.txt"
test -z "$(git status --porcelain --untracked-files=no)"
cmp "$R03_EVIDENCE/sha.txt" "$R03_S1/sha.txt"
sha256sum "$R03_S1/reports/rvv-r03-synth.dcp" > "$R03_EVIDENCE/input-sha256.txt"
cd "$R03_EVIDENCE"
nice -n 10 /home/chen/Tool/FPGA/Vivado/2022.2/bin/vivado -mode batch \
  -source "$R03_ROOT/rvv/r03/implement.tcl" \
  -tclargs "$R03_S1/reports/rvv-r03-synth.dcp" "$R03_EVIDENCE/reports" \
  -log "$R03_EVIDENCE/vivado.log" -journal "$R03_EVIDENCE/vivado.jou" > "$R03_EVIDENCE/vivado.stdout" 2>&1
