#!/usr/bin/env bash
set -e
source /home/chen/FUN/flow-runs/be2-env.sh
cd /home/chen/FUN/flow-be2-r6-handoff-8e5bd3b/design
log=/home/chen/FUN/flow-runs/be2-c735fbd-rtl.log
exitfile=/home/chen/FUN/flow-runs/be2-c735fbd-rtl.exit
trap 'rc=$?; echo "$rc" > "$exitfile"' EXIT
{
 test "$(git rev-parse --short=7 HEAD)" = c735fbd
 git rev-parse HEAD
 set -x
 JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 taskset -c 8-9 nice -n 10 java -XX:ActiveProcessorCount=2 -Xmx6G -jar /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar \
 'runMain flow.top.GenerateBreezeCluster small gshare linux' \
 'runMain flow.top.GenerateBreezeCluster single gshare linux'
 for profile in small single; do
  BE2_VIVADO_THREADS=2 taskset -c 8-9 nice -n 10 python /home/chen/FUN/flow-runs/build-default-soc.py \
   /home/chen/FUN/flow-be2-r6-handoff-8e5bd3b /home/chen/FUN/flow-runs/be2-c735fbd-$profile-default $profile --generate-only
 done
} > "$log" 2>&1
