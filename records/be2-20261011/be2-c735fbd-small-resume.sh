#!/usr/bin/env bash
set -e
source /home/chen/FUN/flow-runs/be2-env.sh
log=/home/chen/FUN/flow-runs/be2-c735fbd-small-default-resume.log
exitfile=/home/chen/FUN/flow-runs/be2-c735fbd-small-default-resume.exit
trap 'rc=$?; echo "$rc" > "$exitfile"' EXIT
{
 test "$(cat /home/chen/FUN/flow-runs/be2-candidate-d5-validate.exit)" = 0
 cd /home/chen/FUN/flow-runs/be2-c735fbd-small-default/gateware
 sha256sum xilinx_kcu105_synth.dcp /home/chen/FUN/flow-runs/be2-owned-soc2-bram-gate.tcl
 printf 'HOST=%s CWD=%s PRODUCTION_SHA=c735fbd\n' "$(hostname)" "$PWD"
 set -x
 taskset -c 0-3 nice -n 10 vivado -mode batch -source xilinx_kcu105-be2-resume.tcl
} > "$log" 2>&1
