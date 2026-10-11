#!/usr/bin/env bash
set -e
source /home/chen/FUN/flow-runs/be2-env.sh
log=/home/chen/FUN/flow-runs/be2-c735fbd-structure.log
exitfile=/home/chen/FUN/flow-runs/be2-c735fbd-structure.exit
trap 'rc=$?; echo "$rc" > "$exitfile"' EXIT
{
 test "$(cat /home/chen/FUN/flow-runs/be2-candidate-d5-validate.exit)" = 0
 git -C /home/chen/FUN/flow-be2-r6-handoff-8e5bd3b rev-parse HEAD
 sha256sum /home/chen/FUN/flow-runs/be2-c735fbd-small-default/gateware/xilinx_kcu105_synth.dcp
 set -x
 taskset -c 4-5 nice -n 10 vivado -mode batch -source /home/chen/FUN/flow-runs/be2-structure-final.tcl -tclargs \
  /home/chen/FUN/flow-runs/be2-c735fbd-small-default/gateware/xilinx_kcu105_synth.dcp \
  /home/chen/FUN/flow-runs/be2-c735fbd-structure
} > "$log" 2>&1
