#!/usr/bin/env bash
set -e
source /home/chen/miniforge3/bin/activate flow
cd /home/chen/FUN/flow-be2-b3-8ea5db5/design
log=/home/chen/FUN/flow-runs/be2-6ac3415-minimum-cluster.log
exitfile=/home/chen/FUN/flow-runs/be2-6ac3415-minimum-cluster.exit
trap 'rc=$?; echo "$rc" > "$exitfile"' EXIT
{
 git rev-parse HEAD
 printf 'HOST=%s CWD=%s\n' "$(hostname)" "$PWD"
 set -x
 BE2_FIB_ELF=/home/chen/FUN/flow-runs/be2-fibonacci-64k.elf JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 taskset -c 4-7 nice -n 10 java -XX:ActiveProcessorCount=4 -Xmx6G -jar /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar \
  'set Test / parallelExecution := false' \
  'testOnly flow.cluster.BE2MinimumSpec' \
  'testOnly flow.cluster.ClusterProgramSpec -- -t "single-hart system programs, single core, Linux profile"' \
  'testOnly flow.cluster.BE2PerformanceSpec'
} > "$log" 2>&1
