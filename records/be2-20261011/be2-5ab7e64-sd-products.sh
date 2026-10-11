#!/usr/bin/env bash
set -e
source /home/chen/FUN/flow-runs/be2-env.sh
cd /home/chen/FUN/flow-be2-b3-8ea5db5/design
root=/home/chen/FUN/flow-runs
log=$root/be2-5ab7e64-sd-products.log
exitfile=$root/be2-5ab7e64-sd-products.exit
trap 'rc=$?; echo "$rc" > "$exitfile"' EXIT
{
 test "$(git rev-parse --short=7 HEAD)" = 5ab7e64
 git rev-parse HEAD
 printf 'HOST=%s CWD=%s\n' "$(hostname)" "$PWD"
 set -x
 JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 taskset -c 6-9 nice -n 10 java -XX:ActiveProcessorCount=4 -Xmx6G -jar "$root/be2-tools/sbt-launch-1.9.7.jar" \
  'set Test / parallelExecution := false' \
  'testOnly flow.bus.CoherentDmaWishboneSpec flow.memsys.CoherentSdDmaSpec' \
  'runMain flow.top.GenerateBreezeCluster small gshare linux coherent-dma' \
  'runMain flow.top.GenerateBreezeCluster single gshare linux coherent-dma'
 for cpu in breeze breeze-tiny; do
  taskset -c 6-9 nice -n 10 python ../fpga/kcu105/target.py --cpu-type "$cpu" --with-sdcard --output-dir "$root/be2-5ab7e64-$cpu-sd"
 done
 # The new SD source snapshots do not depend on later worktree edits.
 for cpu in breeze breeze-tiny; do
  (
   cd "$root/be2-5ab7e64-$cpu-sd/gateware"
   sha256sum ../source-snapshot/manifest.json xilinx_kcu105.tcl xilinx_kcu105.xdc
   taskset -c 4-7 nice -n 10 vivado -mode batch -source xilinx_kcu105.tcl
  )
 done
} > "$log" 2>&1
