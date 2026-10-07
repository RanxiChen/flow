#!/usr/bin/env bash
set -euo pipefail
R04_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
R04_EVIDENCE=$(realpath -m "$1")
mkdir -p "$R04_EVIDENCE"
trap 'R04_RESULT=$?; echo "$R04_RESULT" > "$R04_EVIDENCE/exit-code.txt"' EXIT
cd "$R04_ROOT"
test -z "$(git status --porcelain --untracked-files=no)"
git rev-parse HEAD > "$R04_EVIDENCE/sha.txt"
hostname > "$R04_EVIDENCE/host.txt"
bash rvv/r02/build-reference.sh "$R04_EVIDENCE/reference" > "$R04_EVIDENCE/reference.log" 2>&1
nice -n 10 python3 rvv/r04/make-fixtures.py "$R04_EVIDENCE/fixtures" --reference "$R04_EVIDENCE/reference" --random 1000 > "$R04_EVIDENCE/fixtures.log" 2>&1
nice -n 10 python3 rvv/r02/test-plugin.py "$R04_EVIDENCE/fixtures" > "$R04_EVIDENCE/plugin-test.log" 2>&1
export R02_FIXTURES="$R04_EVIDENCE/fixtures" MAKEFLAGS=-j4
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:ActiveProcessorCount=4 -Xmx6g"
cd design
nice -n 10 "${R04_SBT:-$(command -v sbt)}" 'testOnly flow.rvv.RvvIntegrationSpec -- -z C3' > "$R04_EVIDENCE/random.log" 2>&1
