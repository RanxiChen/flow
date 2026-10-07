#!/usr/bin/env bash
set -euo pipefail
R02_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
R02_EVIDENCE=$(realpath -m "$1")
mkdir -p "$R02_EVIDENCE"
cd "$R02_ROOT"
git rev-parse HEAD > "$R02_EVIDENCE/sha.txt"
git status --short > "$R02_EVIDENCE/status.txt"
test "$(git branch --show-current)" = feat/rvv-20261005
test -z "$(git status --porcelain --untracked-files=no)"
bash rvv/r02/build-reference.sh "$R02_EVIDENCE/reference" > "$R02_EVIDENCE/build-reference.log" 2>&1
nice -n 10 python3 rvv/r02/make-fixtures.py "$R02_EVIDENCE/fixtures" --reference "$R02_EVIDENCE/reference" --kind directed > "$R02_EVIDENCE/spike-directed.log" 2>&1
nice -n 10 python3 rvv/r02/test-plugin.py "$R02_EVIDENCE/fixtures/directed" > "$R02_EVIDENCE/plugin-test.log" 2>&1
export R02_FIXTURES="$R02_EVIDENCE/fixtures"
export MAKEFLAGS=-j4
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:ActiveProcessorCount=4 -Xmx6g"
cd design
nice -n 10 /home/chen/.local/share/coursier/bin/sbt 'testOnly flow.rvv.RvvFrontendSpec' > "$R02_EVIDENCE/frontend.log" 2>&1
nice -n 10 /home/chen/.local/share/coursier/bin/sbt 'testOnly flow.rvv.RvvIntegrationSpec -- -z C2' > "$R02_EVIDENCE/integration.log" 2>&1
