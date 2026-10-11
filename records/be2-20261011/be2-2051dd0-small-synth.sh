#!/usr/bin/env bash
set -e
source /home/chen/miniforge3/bin/activate flow
cd /home/chen/FUN/flow-be2-baseline-2051dd0/design
log=/home/chen/FUN/flow-runs/be2-2051dd0-small-synth.log
exitfile=/home/chen/FUN/flow-runs/be2-2051dd0-small-synth.exit
trap 'rc=$?; echo "$rc" > "$exitfile"' EXIT
{
 git rev-parse HEAD
 git diff --stat -- src/main ../fpga ../config
 set -x
 JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 taskset -c 8-9 nice -n 10 java -XX:ActiveProcessorCount=2 -Xmx6G -jar /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar 'runMain flow.top.GenerateBreezeCluster small gshare linux'
 BE2_VIVADO_THREADS=2 taskset -c 8-9 nice -n 10 bash /home/chen/FUN/flow-runs/be2-env.sh python /home/chen/FUN/flow-runs/build-default-soc.py /home/chen/FUN/flow-be2-baseline-2051dd0 /home/chen/FUN/flow-runs/be2-2051dd0-small-baseline small --synth-only
} > "$log" 2>&1
