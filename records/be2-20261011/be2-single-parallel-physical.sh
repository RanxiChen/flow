#!/usr/bin/env bash
# Consume the already generated single-hart project from the same four-hart SHA.
set -e
expected=${1:?exact source SHA required}
shift
source /home/chen/FUN/flow-runs/be2-env.sh
root=/home/chen/FUN/flow-runs
label=${expected:0:7}
prepared=$root/be2-$label-breeze-tiny-sd
output=$root/be2-$label-single-parallel-sd
trap 'rc=$?; echo "$rc" > "$root/be2-$label-single-parallel-physical.exit"' EXIT
{
 test "$(cat "$prepared/source-commit.txt")" = "$expected"
 test ! -e "$output"
 test ! -e "$prepared/gateware/xilinx_kcu105.bit"
 cp -a "$prepared" "$output"
 cd "$output/gateware"
 printf 'HOST=%s CWD=%s SOURCE=%s\n' "$(hostname)" "$PWD" "$expected"
 # Tcl keeps absolute references to the original immutable RTL/hooks, and
 # generated top/ROM init are byte-identical copies. All outputs go here.
 sha256sum ../source-snapshot/manifest.json xilinx_kcu105.v xilinx_kcu105_rom.init \
  xilinx_kcu105.tcl xilinx_kcu105.xdc ../software/bios/bios.bin
 taskset -c 4-7 nice -n 10 vivado -mode batch -source xilinx_kcu105.tcl
 test -s xilinx_kcu105.bit
 sha256sum xilinx_kcu105.bit ../software/bios/bios.bin > ../product-sha256.txt
} > "$root/be2-$label-single-parallel-physical.log" 2>&1
