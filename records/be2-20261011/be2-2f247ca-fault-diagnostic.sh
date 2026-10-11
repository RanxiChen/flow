#!/usr/bin/env bash
set -e
source /home/chen/miniforge3/bin/activate flow
cd /home/chen/FUN/flow-be2-r6-handoff-8e5bd3b/design
log=/home/chen/FUN/flow-runs/be2-2f247ca-fault-diagnostic.log
exitfile=/home/chen/FUN/flow-runs/be2-2f247ca-fault-diagnostic.exit
trap 'rc=$?; echo "$rc" > "$exitfile"' EXIT
{
 git rev-parse HEAD
 printf 'HOST=%s CWD=%s\n' "$(hostname)" "$PWD"
 set -x
 BE2_LEDGER_DIR=/home/chen/FUN/flow-runs/be2-6ac3415-ledgers JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 taskset -c 0-3 nice -n 10 java -XX:ActiveProcessorCount=4 -Xmx6G -jar /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar \
  'set Test / parallelExecution := false' \
  'testOnly flow.memsys.L1DL2MultiCoreFaultSpec -- -z "two cores, seed 91" -z "two cores, seed 92"' \
  'testOnly flow.memsys.L1DL2LitmusSpec -- -t "SB+fence.rw.rw (2 cores, seed 35): no forbidden outcome, every required outcome observed"'
} > "$log" 2>&1
