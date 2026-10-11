#!/usr/bin/env bash
set -e
source /home/chen/FUN/flow-runs/be2-env.sh
cd /home/chen/FUN/flow-be2-b3-8ea5db5/design
root=/home/chen/FUN/flow-runs
trap 'rc=$?; echo "$rc" > "$root/be2-d6eca9c-rvv-elaboration.exit"' EXIT
{
 test "$(git rev-parse --short=7 HEAD)" = d6eca9c
 git rev-parse HEAD
 printf 'HOST=%s CWD=%s\n' "$(hostname)" "$PWD"
 set -x
 JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 taskset -c 8-9 nice -n 10 java -XX:ActiveProcessorCount=2 -Xmx6G -jar "$root/be2-tools/sbt-launch-1.9.7.jar" \
  'set Test / parallelExecution := false' \
  'runMain flow.top.GenerateBreezeCluster single-rvv gshare linux coherent-dma' \
  'runMain flow.top.GenerateBreezeCluster small gshare linux coherent-dma' \
  'runMain flow.top.GenerateBreezeCluster single gshare linux coherent-dma'
} > "$root/be2-d6eca9c-rvv-elaboration.log" 2>&1
