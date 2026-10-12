#!/usr/bin/env bash
set -u
source /home/chen/Tool/FPGA/Vivado/2022.2/settings64.sh
root=/home/chen/FUN/flow-runs/f2-owner-ce-d4350cb-full-route-20261012
gateware=/home/chen/FUN/flow-runs/f2-owner-ce-d4350cb-retry2-20261012/single/gateware
mkdir -p "$root"
cd "$root" || exit 2
date -u +%FT%TZ > start-utc.txt
sha256sum "$gateware/xilinx_kcu105_synth.dcp" > input-checkpoint-sha256.txt
vivado -version > vivado-version.txt 2>&1
taskset -c 10-13 nice -n 10 vivado -mode batch -source run-full-route.tcl > vivado.console.log 2>&1
code=$?
printf '%s\n' "$code" > run.exit
date -u +%FT%TZ > end-utc.txt
exit "$code"
