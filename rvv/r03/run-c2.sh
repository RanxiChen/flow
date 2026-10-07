#!/usr/bin/env bash
set -euo pipefail
R03_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
R03_EVIDENCE=$(realpath -m "$1")
cd "$R03_ROOT"
bash rvv/r02/run-c2.sh "$R03_EVIDENCE"
export MAKEFLAGS=-j4
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:ActiveProcessorCount=4 -Xmx6g"
cd design
nice -n 10 "${R04_SBT:-/home/chen/.local/share/coursier/bin/sbt}" 'testOnly flow.rvv.RvvR03Spec' > "$R03_EVIDENCE/r03.log" 2>&1
