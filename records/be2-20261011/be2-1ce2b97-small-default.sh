#!/usr/bin/env bash
set -e
source /home/chen/FUN/flow-runs/be2-env.sh
log=/home/chen/FUN/flow-runs/be2-1ce2b97-small-default.log
exitfile=/home/chen/FUN/flow-runs/be2-1ce2b97-small-default.exit
trap 'rc=$?; echo "$rc" > "$exitfile"' EXIT
{
 git -C /home/chen/FUN/flow-be2-r6-handoff-8e5bd3b rev-parse HEAD
 git -C /home/chen/FUN/flow-be2-r6-handoff-8e5bd3b status --short
 cd /home/chen/FUN/flow-runs/be2-1ce2b97-small-default/gateware
 printf 'HOST=%s CWD=%s\n' "$(hostname)" "$PWD"
 set -x
 nice -n 10 taskset -c 0-3 bash build_xilinx_kcu105.sh
} > "$log" 2>&1
