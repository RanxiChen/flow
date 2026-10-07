#!/usr/bin/env bash
set -euo pipefail
R02_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
R02_EVIDENCE=$(realpath -m "$1")
mkdir -p "$R02_EVIDENCE"
trap 'R02_RC=$?; printf "%s\n" "$R02_RC" > "$R02_EVIDENCE/exit-code.txt"' EXIT
cd "$R02_ROOT"
git rev-parse HEAD > "$R02_EVIDENCE/sha.txt"
test -z "$(git status --porcelain --untracked-files=no)"
bash rvv/r02/run-c2.sh "$R02_EVIDENCE/c2-check" > "$R02_EVIDENCE/c2-check.log" 2>&1
bash rvv/r02/build-reference.sh "$R02_EVIDENCE/reference" > "$R02_EVIDENCE/build-reference.log" 2>&1
if [[ -n "${R02_REUSE_REFERENCE:-}" ]]; then
  R02_REFERENCE_SHA=$(cat "$R02_REUSE_REFERENCE/sha.txt")
  git diff --exit-code "$R02_REFERENCE_SHA" HEAD -- rvv/r02/make-fixtures.py rvv/r02/spike-extension.cc rvv/r02/link.ld rvv/r02/build-reference.sh
  cmp "$R02_REUSE_REFERENCE/reference/spike-sha.txt" "$R02_EVIDENCE/reference/spike-sha.txt"
  test ! -s "$R02_EVIDENCE/reference/spike-status.txt"
  printf '%s  %s\n' c0a8eb834cc94e92afb372f29bd7d2a87215c5fb6ee0dc19ed84792e64222c2a /home/chen/work/breeze-spike-simulator-build/spike | sha256sum -c - > "$R02_EVIDENCE/reference/spike-binary-check.txt"
  cp -a "$R02_REUSE_REFERENCE/fixtures" "$R02_EVIDENCE/fixtures"
  printf 'Reference fixtures reused from %s; generator/plugin/linker/Spike identity unchanged.\n' "$R02_REUSE_REFERENCE" > "$R02_EVIDENCE/spike-random.log"
else
  nice -n 10 python3 rvv/r02/make-fixtures.py "$R02_EVIDENCE/fixtures" --reference "$R02_EVIDENCE/reference" --kind random --seeds 1000 > "$R02_EVIDENCE/spike-random.log" 2>&1
fi
nice -n 10 python3 rvv/r02/test-plugin.py "$R02_EVIDENCE/fixtures" > "$R02_EVIDENCE/plugin-test.log" 2>&1
export R02_FIXTURES="$R02_EVIDENCE/fixtures"
export MAKEFLAGS=-j4
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:ActiveProcessorCount=4 -Xmx6g"
cd design
nice -n 10 "${R04_SBT:-/home/chen/.local/share/coursier/bin/sbt}" 'testOnly flow.rvv.RvvIntegrationSpec -- -z C3' > "$R02_EVIDENCE/random.log" 2>&1
