#!/usr/bin/env bash
set -euo pipefail
R02_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
R02_EVIDENCE=$(realpath -m "$1")
mkdir -p "$R02_EVIDENCE"
trap 'R02_RC=$?; printf "%s\n" "$R02_RC" > "$R02_EVIDENCE/exit-code.txt"' EXIT
cd "$R02_ROOT"
git rev-parse HEAD > "$R02_EVIDENCE/sha.txt"
test -z "$(git status --porcelain --untracked-files=no)"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:ActiveProcessorCount=4 -Xmx6g"
cd design
nice -n 10 "${R04_SBT:-/home/chen/.local/share/coursier/bin/sbt}" "runMain flow.rvv.GenerateRvv 512-512 $R02_EVIDENCE/rtl" > "$R02_EVIDENCE/emit.log" 2>&1
cd "$R02_EVIDENCE"
nice -n 10 /home/chen/Tool/FPGA/Vivado/2022.2/bin/vivado -mode batch \
  -source "$R02_ROOT/rvv/r03/synthesize.tcl" -tclargs "$R02_EVIDENCE/rtl" "$R02_EVIDENCE/reports" \
  -log "$R02_EVIDENCE/vivado.log" -journal "$R02_EVIDENCE/vivado.jou" > "$R02_EVIDENCE/vivado.stdout" 2>&1
