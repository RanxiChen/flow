#!/usr/bin/env bash
set -e
source /home/chen/FUN/flow-runs/be2-env.sh
log=/home/chen/FUN/flow-runs/be2-c735fbd-small-default.log
exitfile=/home/chen/FUN/flow-runs/be2-c735fbd-small-default.exit
trap 'rc=$?; echo "$rc" > "$exitfile"' EXIT
{
 test "$(git -C /home/chen/FUN/flow-be2-r6-handoff-8e5bd3b rev-parse --short=7 HEAD)" = c735fbd
 git -C /home/chen/FUN/flow-be2-r6-handoff-8e5bd3b rev-parse HEAD
 git -C /home/chen/FUN/flow-be2-r6-handoff-8e5bd3b status --short
 cd /home/chen/FUN/flow-runs/be2-c735fbd-small-default/gateware
 sed -i "s/set_param general.maxThreads 2/set_param general.maxThreads 4/" xilinx_kcu105.tcl
 printf 'HOST=%s CWD=%s\n' "$(hostname)" "$PWD"
 set -x
 nice -n 10 taskset -c 0-3 bash build_xilinx_kcu105.sh
} > "$log" 2>&1
