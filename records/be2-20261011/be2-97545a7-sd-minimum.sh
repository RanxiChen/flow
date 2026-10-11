#!/usr/bin/env bash
set -e
source /home/chen/FUN/flow-runs/be2-env.sh
cd /home/chen/FUN/flow-be2-b3-8ea5db5/design
log=/home/chen/FUN/flow-runs/be2-97545a7-sd-minimum.log
exitfile=/home/chen/FUN/flow-runs/be2-97545a7-sd-minimum.exit
trap 'rc=$?; echo "$rc" > "$exitfile"' EXIT
{
 test "$(git rev-parse --short=7 HEAD)" = 97545a7
 git rev-parse HEAD
 printf 'HOST=%s CWD=%s\n' "$(hostname)" "$PWD"
 set -x
 JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 taskset -c 6-9 nice -n 10 java -XX:ActiveProcessorCount=4 -Xmx6G -jar /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar \
  'set Test / parallelExecution := false' \
  'testOnly flow.bus.CoherentDmaWishboneSpec flow.config.BreezeCoreConfigSpec' \
  'testOnly flow.memsys.L2HomeSpec -- -z "DMA MaskWrite"' \
  'runMain flow.top.GenerateBreezeCluster small gshare linux coherent-dma' \
  'runMain flow.top.GenerateBreezeCluster single gshare linux coherent-dma'
 for cpu in breeze breeze-tiny; do
  taskset -c 6-9 nice -n 10 python ../fpga/kcu105/target.py --cpu-type "$cpu" --with-sdcard --output-dir "/home/chen/FUN/flow-runs/be2-97545a7-$cpu-sd"
 done
} > "$log" 2>&1
