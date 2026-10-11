#!/usr/bin/env bash
set -e
expected=${1:?exact source SHA required}
shift
source /home/chen/FUN/flow-runs/be2-env.sh
cd /home/chen/FUN/flow-be2-b3-8ea5db5/design
label=${expected:0:7}
root=/home/chen/FUN/flow-runs
trap 'rc=$?; echo "$rc" > "$root/be2-$label-rvv-minimum.exit"' EXIT
{
 test "$(git rev-parse HEAD)" = "$expected"
 git rev-parse HEAD
 printf 'HOST=%s CWD=%s\n' "$(hostname)" "$PWD"
 set -x
 JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 taskset -c 8-9 nice -n 10 java -XX:ActiveProcessorCount=2 -Xmx6G -jar "$root/be2-tools/sbt-launch-1.9.7.jar" \
  'set Test / parallelExecution := false' \
  'testOnly flow.memsys.WriteThroughHomeSpec' \
  'runMain flow.top.GenerateBreezeCluster single-rvv gshare linux coherent-dma' \
  'runMain flow.top.GenerateBreezeCluster small gshare linux coherent-dma' \
  'runMain flow.top.GenerateBreezeCluster single gshare linux coherent-dma'
 taskset -c 8-9 nice -n 10 python ../fpga/kcu105/target.py --cpu-type breeze-rvv --with-sdcard --vivado-threads 2 --output-dir "$root/be2-$label-rvv-sd"
} > "$root/be2-$label-rvv-minimum.log" 2>&1
