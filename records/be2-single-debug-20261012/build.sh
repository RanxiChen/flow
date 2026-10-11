#!/usr/bin/env bash
set -euo pipefail
expected=${1:?exact source SHA required}
shift
source /home/chen/FUN/flow-runs/be2-env.sh
root=/home/chen/FUN/flow-runs
work=/home/chen/FUN/flow-be2-single-debug-20261012
output="$root/be2-single-debug-20261012"
stage=minimum
trap 'status=$?; printf "%s\n" "$status" > "$root/be2-single-debug-20261012.exit"; printf "STAGE=%s EXIT=%s\n" "$stage" "$status"' EXIT
cd "$work"
test "$(git rev-parse HEAD)" = "$expected"
test -z "$(git status --porcelain --untracked-files=no)"
test "$(git -C third_party/cvfpu rev-parse HEAD)" = 23153ba10a0ab8c6ee774b5e1a928007c3b2adcc
hostname
git rev-parse HEAD
date -u +%FT%TZ
set -x
taskset -c 6-9 nice -n 10 python3 -m unittest discover -s sim/litex -p test_breeze_axi_monitor.py -v
taskset -c 6-9 nice -n 10 python3 -m unittest discover -s sim/litex -p test_breeze_ila.py -v
cd design
JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 taskset -c 6-9 nice -n 10 \
  java -XX:ActiveProcessorCount=4 -Xmx6G -jar "$root/be2-tools/sbt-launch-1.9.7.jar" \
  'set Test / parallelExecution := false' \
  'testOnly flow.memsys.BreezeHangMonitorSpec' \
  'runMain flow.top.GenerateBreezeCluster single gshare linux tandem fpga-debug coherent-dma'
stage=prepare
cd "$work"
test ! -e "$output"
taskset -c 6-9 nice -n 10 python3 fpga/kcu105/target.py \
  --cpu-type breeze-tiny --debug --with-sdcard --sys-clk-freq 100000000 \
  --vivado-threads 4 --output-dir "$output"
git rev-parse HEAD > "$output/source-commit.txt"
git status --porcelain --untracked-files=no > "$output/source-status.txt"
cp design/build/rtl/axi-cluster/single/gshare/linux/tandem/fpga-debug/coherent-dma/cluster-profile.txt "$output/cluster-profile.txt"
stage=vivado
cd "$output/gateware"
taskset -c 6-9 nice -n 10 vivado -mode batch -source xilinx_kcu105.tcl \
  -log vivado.log -journal vivado.jou
test -s xilinx_kcu105.bit
test -s xilinx_kcu105.ltx
sha256sum xilinx_kcu105.bit xilinx_kcu105.ltx ../software/bios/bios.bin > ../product-sha256.txt
