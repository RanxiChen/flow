#!/usr/bin/env bash
set -euo pipefail
R02_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
R02_EVIDENCE=$(realpath -m "$1")
mkdir -p "$R02_EVIDENCE"
cd "$R02_ROOT"
git status --porcelain > "$R02_EVIDENCE/status.txt"
git rev-parse HEAD > "$R02_EVIDENCE/sha.txt"
git branch --show-current > "$R02_EVIDENCE/branch.txt"
test "$(git branch --show-current)" = feat/rvv-20261005
test -z "$(git status --porcelain --untracked-files=no)"
verilator --version > "$R02_EVIDENCE/verilator-version.txt"
java -version > "$R02_EVIDENCE/java-version.txt" 2>&1
cd design
export MAKEFLAGS=-j4
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:ActiveProcessorCount=4 -Xmx6g"
for config in 512-512 256-256 512-256; do
  nice -n 10 /home/chen/.local/share/coursier/bin/sbt "runMain flow.rvv.GenerateRvv $config $R02_EVIDENCE/rtl/$config" > "$R02_EVIDENCE/emit-$config.log" 2>&1
done
nice -n 10 /home/chen/.local/share/coursier/bin/sbt 'testOnly flow.rvv.RvvSmokeSpec' > "$R02_EVIDENCE/smoke.log" 2>&1
