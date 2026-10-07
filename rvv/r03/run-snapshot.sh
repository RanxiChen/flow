#!/usr/bin/env bash
set -euo pipefail
R03_S1=$(realpath "$1")
cd "$R03_S1"
trap 'R03_RC=$?; printf "%s\n" "$R03_RC" > exit-code.txt' EXIT
sha256sum -c input-sha256.txt > input-check.txt
printf '%s\n' "$PWD" > cwd.txt
nice -n 10 /home/chen/Tool/FPGA/Vivado/2022.2/bin/vivado -mode batch \
  -source "$R03_S1/synthesize.tcl" -tclargs "$R03_S1/rtl" "$R03_S1/reports" \
  -log "$R03_S1/vivado.log" -journal "$R03_S1/vivado.jou" > "$R03_S1/vivado.stdout" 2>&1
nice -n 10 python3 "$R03_S1/summarize.py" "$R03_S1/reports" > resources.json
