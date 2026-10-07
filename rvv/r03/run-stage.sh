#!/usr/bin/env bash
set -euo pipefail
R03_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
R03_EVIDENCE=$(realpath -m "$1")
mkdir -p "$R03_EVIDENCE"
trap 'R03_RC=$?; printf "%s\n" "$R03_RC" > "$R03_EVIDENCE/exit-code.txt"' EXIT
cd "$R03_ROOT"
git rev-parse HEAD > "$R03_EVIDENCE/sha.txt"
git status --porcelain > "$R03_EVIDENCE/status.txt"
printf '%s\n' "$PWD" > "$R03_EVIDENCE/cwd.txt"
for phase in c1 c2 s1; do
  case "$phase" in
    c1) R03_SCRIPT=rvv/r02/run-c1.sh ;;
    c2) R03_SCRIPT=rvv/r03/run-c2.sh ;;
    s1) R03_SCRIPT=rvv/r03/run-s1.sh ;;
  esac
  printf 'bash %s %s\n' "$R03_SCRIPT" "$R03_EVIDENCE/$phase" >> "$R03_EVIDENCE/commands.txt"
  set +e
  bash "$R03_SCRIPT" "$R03_EVIDENCE/$phase" > "$R03_EVIDENCE/$phase-driver.log" 2>&1
  R03_PHASE_RC=$?
  set -e
  printf '%s\n' "$R03_PHASE_RC" > "$R03_EVIDENCE/$phase-exit-code.txt"
  test "$R03_PHASE_RC" = 0
done
python3 rvv/r03/summarize.py "$R03_EVIDENCE/s1/reports" > "$R03_EVIDENCE/resources.json"
