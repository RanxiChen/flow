#!/usr/bin/env bash
set -e
expected=${1:?exact source SHA required}
shift
source /home/chen/FUN/flow-runs/be2-env.sh
cd /home/chen/FUN/flow-be2-b3-8ea5db5/design
label=${expected:0:7}
log=/home/chen/FUN/flow-runs/be2-$label-cluster-minimum.log
exitfile=/home/chen/FUN/flow-runs/be2-$label-cluster-minimum.exit
trap 'rc=$?; echo "$rc" > "$exitfile"' EXIT
{
 test "$(git rev-parse HEAD)" = "$expected"
 git rev-parse HEAD
 printf 'HOST=%s CWD=%s\n' "$(hostname)" "$PWD"
 set -x
 BE2_FIB_ELF=/home/chen/FUN/flow-runs/be2-fibonacci-64k.elf JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 taskset -c 2-3 nice -n 10 java -XX:ActiveProcessorCount=2 -Xmx6G -jar /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar \
  'set Test / parallelExecution := false' \
  'testOnly flow.config.BreezeCoreConfigSpec' \
  'testOnly flow.cluster.BE2MinimumSpec' \
  'testOnly flow.cluster.ClusterProgramSpec -- -t "multi-hart programs, small (4 harts), Linux profile"' \
  'testOnly flow.memsys.L1DL2MultiCoreFaultSpec -- -z "BE2 S1 kill" -z "four cores, seed 91" -z "BE2 deployment geometry"' \
  'testOnly flow.memsys.BE2FencePtwSpec' \
  'testOnly flow.cluster.BE2PerformanceSpec'
} > "$log" 2>&1
