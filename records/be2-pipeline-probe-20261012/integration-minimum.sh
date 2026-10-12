#!/usr/bin/env bash
# Caller re-reads the shared host configuration and validates the chosen host.
set -euo pipefail
expected=${1:?exact source SHA}
work=${2:?isolated worktree}
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
cd design
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4
export MAKEFLAGS=-j4
taskset -c 6-9 nice -n 10 java -XX:ActiveProcessorCount=4 -Xmx6G \
  -jar /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar \
  'set Test / parallelExecution := false' \
  'testOnly flow.config.BreezeCoreConfigSpec flow.frontend.BreezeResolutionReservationSpec flow.memsys.L1IRegisteredReturnSpec' \
  'testOnly flow.frontend.BreezeFrontendPipelineSpec flow.frontend.BreezePipelinePredictorSpec' \
  'testOnly flow.backend.BackendContractSpec -- -z "S13" -z "P10_exception" -z "T18_FENCEI" -z "T20_SFENCE" -z "BE2 R1 cancellation"' \
  'testOnly flow.memsys.BE2SerialConditionSpec flow.memsys.BE2FencePtwSpec'
cp target/test-reports/TEST-flow.config.BreezeCoreConfigSpec.xml "$evidence/"
cp target/test-reports/TEST-flow.frontend.*Spec.xml "$evidence/"
cp target/test-reports/TEST-flow.memsys.L1IRegisteredReturnSpec.xml "$evidence/"
cp target/test-reports/TEST-flow.backend.BackendContractSpec.xml "$evidence/"
cp target/test-reports/TEST-flow.memsys.BE2SerialConditionSpec.xml "$evidence/"
cp target/test-reports/TEST-flow.memsys.BE2FencePtwSpec.xml "$evidence/"
