#!/usr/bin/env bash
set -e
source /home/chen/FUN/flow-runs/be2-env.sh
cd /home/chen/FUN/flow-be2-r6-handoff-8e5bd3b/design
log=/home/chen/FUN/flow-runs/be2-790c1f6-safety.log
exitfile=/home/chen/FUN/flow-runs/be2-790c1f6-safety.exit
trap 'rc=$?; echo "$rc" > "$exitfile"' EXIT
{
 git rev-parse HEAD
 printf 'HOST=%s CWD=%s\n' "$(hostname)" "$PWD"
 set -x
 BE2_LEDGER_DIR=/home/chen/FUN/flow-runs/be2-790c1f6-ledgers JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 taskset -c 8-9 nice -n 10 java -XX:ActiveProcessorCount=2 -Xmx6G -jar /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar \
  'set Test / parallelExecution := false' \
  'testOnly flow.backend.BackendContractSpec -- -z "BE2 R1" -z "BE2 R6" -z "S13_" -z "P09" -z "P10" -z "flags-only"' \
  'testOnly flow.backend.BE2PerturbationSpec'
} > "$log" 2>&1
