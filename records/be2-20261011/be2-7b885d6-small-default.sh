#!/usr/bin/env bash
set -e
root=/home/chen/FUN/flow-runs/be2-7b885d6-small-default
log=/home/chen/FUN/flow-runs/be2-7b885d6-small-default.log
exitfile=/home/chen/FUN/flow-runs/be2-7b885d6-small-default.exit
trap 'rc=$?; echo "$rc" > "$exitfile"' EXIT
cd "$root/gateware"
{
 git -C /home/chen/FUN/flow-be2-1a4deba rev-parse HEAD
 printf 'PROFILE=small CLOCK=100000000 PART=xcku040-ffva1156-2-e DEFAULT_DIRECTIVES\n'
 sha256sum xilinx_kcu105.tcl xilinx_kcu105.xdc ../source-snapshot/manifest.json
 set -x
 taskset -c 4-7 nice -n 10 bash /home/chen/FUN/flow-runs/be2-env.sh bash build_xilinx_kcu105.sh
} > "$log" 2>&1
