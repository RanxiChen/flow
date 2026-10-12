#!/usr/bin/env bash
# Caller must re-read shared simulation-host.md and preflight the selected host.
set -euo pipefail
expected=${1:?exact source SHA}
work=${2:?isolated source worktree}
evidence=${3:?fresh evidence directory}
test ! -e "$evidence"
mkdir -p "$evidence"
trap 'rc=$?; printf "%s\n" "$rc" > "$evidence/run.exit"' EXIT
cd "$work"
test "$(git rev-parse HEAD)" = "$expected"
test -z "$(git status --porcelain --untracked-files=no)"
git rev-parse HEAD > "$evidence/source-sha.txt"
hostname > "$evidence/host.txt"
pwd > "$evidence/source-cwd.txt"
date -u +%FT%TZ > "$evidence/start-utc.txt"
java -version > "$evidence/java.txt" 2>&1
verilator --version > "$evidence/verilator.txt"
python -m unittest discover -s sim/litex -p test_breeze_cpu_wrapper.py -v > "$evidence/wrapper.log" 2>&1
cd design
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 MAKEFLAGS=-j4
taskset -c 6-9 nice -n 10 java -XX:ActiveProcessorCount=4 -Xmx6G \
  -jar /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar \
  'set Test / parallelExecution := false' \
  'testOnly flow.fpu.FpUnitSpec flow.config.BreezeCoreConfigSpec' \
  'testOnly flow.frontend.BreezeFrontendPipelineSpec flow.frontend.BreezeResolutionReservationSpec flow.memsys.L1IRegisteredReturnSpec' \
  'testOnly flow.memsys.CoherentSdDmaSpec'
for suite in flow.fpu.FpUnitSpec flow.config.BreezeCoreConfigSpec \
    flow.frontend.BreezeFrontendPipelineSpec flow.frontend.BreezeResolutionReservationSpec \
    flow.memsys.L1IRegisteredReturnSpec flow.memsys.CoherentSdDmaSpec; do
  cp "target/test-reports/TEST-$suite.xml" "$evidence/"
done
