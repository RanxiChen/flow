#!/usr/bin/env bash
set -e
source /home/chen/FUN/flow-runs/be2-env.sh
log=/home/chen/FUN/flow-runs/be2-baseline-deployment-structure.log
exitfile=/home/chen/FUN/flow-runs/be2-baseline-deployment-structure.exit
trap 'rc=$?; echo "$rc" > "$exitfile"' EXIT
{
 test "$(cat /home/chen/FUN/flow-runs/be2-baseline-d5-validate.exit)" = 0
 git -C /home/chen/FUN/flow-be2-baseline-2051dd0 rev-parse HEAD
 sha256sum /home/chen/FUN/flow-runs/be2-baseline-deployment-production.patch
 set -x
 taskset -c 8-9 nice -n 10 vivado -mode batch -source /home/chen/FUN/flow-runs/be2-structure-final.tcl -tclargs \
  /home/chen/FUN/flow-runs/be2-baseline-deployment-small/gateware/xilinx_kcu105_synth.dcp \
  /home/chen/FUN/flow-runs/be2-baseline-deployment-structure
} > "$log" 2>&1
