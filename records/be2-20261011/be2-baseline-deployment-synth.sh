#!/usr/bin/env bash
set -e
source /home/chen/FUN/flow-runs/be2-env.sh
log=/home/chen/FUN/flow-runs/be2-baseline-deployment-synth.log
exitfile=/home/chen/FUN/flow-runs/be2-baseline-deployment-synth.exit
trap 'rc=$?; echo "$rc" > "$exitfile"' EXIT
{
 hostname
 java -version
 verilator --version
 df -h /home/chen/FUN
 free -h
 cd /home/chen/FUN/flow-be2-baseline-2051dd0/design
 test "$(git rev-parse --short=7 HEAD)" = 2051dd0
 git rev-parse HEAD
 git diff --stat -- src/main ../fpga ../config
 sha256sum /home/chen/FUN/flow-runs/be2-baseline-deployment-production.patch
 set -x
 taskset -c 8-9 nice -n 10 python /home/chen/FUN/flow-runs/be2-spike-ledger.py \
  /home/chen/FUN/flow-runs/be2-790c1f6-ledgers /home/chen/FUN/flow-runs/be2-790c1f6-spike --spike /home/chen/RISCV/bin/spike
 BE2_FIB_ELF=/home/chen/FUN/flow-runs/be2-fibonacci-64k.elf JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 \
 taskset -c 8-9 nice -n 10 java -XX:ActiveProcessorCount=2 -Xmx6G -jar /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar \
  'set Test / parallelExecution := false' 'testOnly flow.cluster.BE2BaselinePerformanceSpec' \
  'runMain flow.top.GenerateBreezeCluster small gshare linux'
 BE2_VIVADO_THREADS=2 taskset -c 8-9 nice -n 10 python /home/chen/FUN/flow-runs/build-default-soc.py \
  /home/chen/FUN/flow-be2-baseline-2051dd0 /home/chen/FUN/flow-runs/be2-baseline-deployment-small small --synth-only
} > "$log" 2>&1
