#!/usr/bin/env bash
set -Eeuo pipefail
run=${1:?validated run directory}
work=${2:?clean git checkout}
out=${3:?new output directory}
test "$(cat "$run/validation.exit")" = 0
mkdir "$out"
cd "$out"
finish() { rc=$?; trap - EXIT; printf '%s\n' "$rc" > vivado.exit; date -Is > finished.txt; exit "$rc"; }
trap finish EXIT
date -Is > started.txt
/home/chen/Tool/FPGA/Vivado/2022.2/bin/vivado -mode batch -notrace \
  -source "$work/records/pmp-fourstage-20261024/implement.tcl" \
  -tclargs "$run/rtl" "$work/records/pmp-fourstage-20261024" > console.log 2>&1
