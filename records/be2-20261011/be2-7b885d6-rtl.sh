#!/usr/bin/env bash
set -e
source /home/chen/miniforge3/bin/activate flow
cd /home/chen/FUN/flow-be2-1a4deba/design
log=/home/chen/FUN/flow-runs/be2-7b885d6-rtl.log
exitfile=/home/chen/FUN/flow-runs/be2-7b885d6-rtl.exit
trap 'rc=$?; echo "$rc" > "$exitfile"' EXIT
{
 git rev-parse HEAD
 set -x
 JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 taskset -c 8-9 nice -n 10 java -XX:ActiveProcessorCount=2 -Xmx6G -jar /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar \
 'runMain flow.top.GenerateBreezeCluster small gshare linux' \
 'runMain flow.top.GenerateBreezeCluster single gshare linux'
} > "$log" 2>&1
