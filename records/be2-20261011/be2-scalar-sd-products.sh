#!/usr/bin/env bash
set -e
expected=${1:?exact source SHA required}
shift
source /home/chen/FUN/flow-runs/be2-env.sh
cd /home/chen/FUN/flow-be2-1a4deba/design
root=/home/chen/FUN/flow-runs
label=${expected:0:7}
trap 'rc=$?; echo "$rc" > "$root/be2-$label-scalar-sd-products.exit"' EXIT
{
 test "$(git rev-parse HEAD)" = "$expected"
 git rev-parse HEAD
 printf 'HOST=%s CWD=%s\n' "$(hostname)" "$PWD"
 set -x
 JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 taskset -c 0-3 nice -n 10 java -XX:ActiveProcessorCount=4 -Xmx6G \
  -jar "$root/be2-tools/sbt-launch-1.9.7.jar" 'set Test / parallelExecution := false' \
  'testOnly flow.bus.CoherentDmaWishboneSpec flow.memsys.CoherentSdDmaSpec' \
  'runMain flow.top.GenerateBreezeCluster small gshare linux coherent-dma' \
  'runMain flow.top.GenerateBreezeCluster single gshare linux coherent-dma'
 for cpu in breeze breeze-tiny; do
  taskset -c 0-3 nice -n 10 python ../fpga/kcu105/target.py --cpu-type "$cpu" --with-sdcard \
   --vivado-threads 4 --output-dir "$root/be2-$label-$cpu-sd"
 done
 # Vivado reads immutable project snapshots; later repository edits are independent.
 for cpu in breeze breeze-tiny; do
  (
   cd "$root/be2-$label-$cpu-sd/gateware"
   sha256sum ../source-snapshot/manifest.json xilinx_kcu105.tcl xilinx_kcu105.xdc
   taskset -c 0-3 nice -n 10 vivado -mode batch -source xilinx_kcu105.tcl
   test -s xilinx_kcu105.bit
   sha256sum xilinx_kcu105.bit ../software/bios/bios.bin > ../product-sha256.txt
  )
 done
} > "$root/be2-$label-scalar-sd-products.log" 2>&1
