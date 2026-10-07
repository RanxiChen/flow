#!/usr/bin/env bash
set -euo pipefail
R02_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
R02_EVIDENCE=$(realpath -m "$1")
mkdir -p "$R02_EVIDENCE"
trap 'R02_RC=$?; printf "%s\n" "$R02_RC" > "$R02_EVIDENCE/exit-code.txt"' EXIT
cd "$R02_ROOT"
git rev-parse HEAD > "$R02_EVIDENCE/sha.txt"
test -z "$(git status --porcelain --untracked-files=no)"
bash rvv/r02/build-reference.sh "$R02_EVIDENCE/reference" > "$R02_EVIDENCE/build-reference.log" 2>&1
nice -n 10 python3 rvv/r02/make-fixtures.py "$R02_EVIDENCE/fixtures" --reference "$R02_EVIDENCE/reference" --kind gemv > "$R02_EVIDENCE/spike-gemv.log" 2>&1
nice -n 10 python3 rvv/r02/test-plugin.py "$R02_EVIDENCE/fixtures/gemv" > "$R02_EVIDENCE/plugin-test.log" 2>&1
export R02_FIXTURES="$R02_EVIDENCE/fixtures"
export R02_RESULTS="$R02_EVIDENCE/results"
export MAKEFLAGS=-j4
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:ActiveProcessorCount=4 -Xmx6g"
cd design
nice -n 10 "${R04_SBT:-/home/chen/.local/share/coursier/bin/sbt}" 'testOnly flow.rvv.RvvPerformanceSpec' > "$R02_EVIDENCE/performance.log" 2>&1
