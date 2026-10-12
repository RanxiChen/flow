#!/usr/bin/env bash
# Caller re-reads simulation-host.md and validates cloud/Alan before invocation.
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
git diff --exit-code "${PIPELINE_PRODUCTION_BASELINE_SHA:-$expected}" -- design/src/main config third_party/cvfpu
git rev-parse HEAD > "$evidence/source-sha.txt"
hostname > "$evidence/host.txt"
pwd > "$evidence/source-cwd.txt"
date -u +%FT%TZ > "$evidence/start-utc.txt"
java -version > "$evidence/java.txt" 2>&1
verilator --version > "$evidence/verilator.txt"
riscv64-unknown-elf-gcc --version > "$evidence/compiler.txt"
for item in 0:alu-independent 1:alu-dependent 2:load-hit 3:alu-compressed; do
    index=${item%%:*}
    name=${item#*:}
    riscv64-unknown-elf-gcc -march=rv64ima_zicsr_zifencei -mabi=lp64 \
      -mcmodel=medany -fno-pic -fno-pie -static -nostdlib -nostartfiles -no-pie \
      -Wl,--build-id=none -Wl,--no-relax -T tests/cluster/link.ld -Itests/cluster \
      -DNHARTS=1 -DCASE="$index" -o "$evidence/$name.elf" tests/cluster/pipeline_probe.S
    riscv64-unknown-elf-objdump -d "$evidence/$name.elf" > "$evidence/$name.dump"
done
sha256sum "$evidence/"*.elf > "$evidence/elf-sha256.txt"
cd design
export PIPELINE_EVIDENCE="$evidence"
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4
export MAKEFLAGS=-j4
taskset -c 6-9 nice -n 10 java -XX:ActiveProcessorCount=4 -Xmx6G \
  -jar /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar \
  'set Test / parallelExecution := false' \
  'testOnly flow.backend.BackendPipelineThroughputSpec flow.cluster.ClusterPipelineThroughputSpec'
