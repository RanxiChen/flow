#!/usr/bin/env bash
set -e
source /home/chen/FUN/flow-runs/be2-env.sh
cd /home/chen/FUN/flow-be2-b3-8ea5db5/design
log=/home/chen/FUN/flow-runs/be2-1ce2b97-cluster-minimum.log
exitfile=/home/chen/FUN/flow-runs/be2-1ce2b97-cluster-minimum.exit
trap 'rc=$?; echo "$rc" > "$exitfile"' EXIT
{
 git rev-parse HEAD
 printf 'HOST=%s CWD=%s\n' "$(hostname)" "$PWD"
 set -x
 BE2_FIB_ELF=/home/chen/FUN/flow-runs/be2-fibonacci-64k.elf JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 taskset -c 0-3 nice -n 10 java -XX:ActiveProcessorCount=4 -Xmx6G -jar /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar \
  'set Test / parallelExecution := false' \
  'testOnly flow.config.BreezeCoreConfigSpec' \
  'testOnly flow.cluster.BE2MinimumSpec' \
  'testOnly flow.cluster.ClusterProgramSpec -- -t "multi-hart programs, small (4 harts), Linux profile"' \
  'testOnly flow.memsys.L1DL2MultiCoreFaultSpec -- -z "BE2 S1 kill" -z "four cores, seed 91"' \
  'testOnly flow.memsys.BE2SerialConditionSpec -- -z "B3 audit"'
} > "$log" 2>&1
