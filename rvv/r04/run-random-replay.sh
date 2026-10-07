#!/usr/bin/env bash
set -euo pipefail
R04_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
R04_ORACLE=$(realpath "$1")
R04_EVIDENCE=$(realpath -m "$2")
mkdir -p "$R04_EVIDENCE"
trap 'R04_RESULT=$?; echo "$R04_RESULT" > "$R04_EVIDENCE/exit-code.txt"' EXIT
cd "$R04_ROOT"
test -z "$(git status --porcelain --untracked-files=no)"
git rev-parse HEAD > "$R04_EVIDENCE/sha.txt"
hostname > "$R04_EVIDENCE/host.txt"
R04_ORACLE_SHA=$(cat "$R04_ORACLE/sha.txt")
echo "$R04_ORACLE_SHA" > "$R04_EVIDENCE/oracle-source-sha.txt"
git diff --exit-code "$R04_ORACLE_SHA" HEAD -- rvv/r02/make-fixtures.py rvv/r02/spike-extension.cc rvv/r02/link.ld > "$R04_EVIDENCE/oracle-base-equivalence.txt"
python3 - "$R04_ORACLE_SHA" <<'PY' > "$R04_EVIDENCE/oracle-random-equivalence.txt"
import ast, pathlib, subprocess, sys
name='rvv/r04/make-fixtures.py'
old=ast.parse(subprocess.check_output(['git','show',sys.argv[1]+':'+name],text=True))
new=ast.parse(pathlib.Path(name).read_text())
for function in ['random','main']:
    a=next(n for n in old.body if isinstance(n,ast.FunctionDef) and n.name==function)
    b=next(n for n in new.body if isinstance(n,ast.FunctionDef) and n.name==function)
    assert ast.dump(a)==ast.dump(b),function+' changed'
    print(function+' AST unchanged; all random oracle dependencies unchanged')
PY
test "$(find "$R04_ORACLE/fixtures" -name fixture.json | wc -l)" = 1000
nice -n 10 python3 rvv/r02/test-plugin.py "$R04_ORACLE/fixtures" > "$R04_EVIDENCE/plugin-test.log" 2>&1
cp -a "$R04_ORACLE/reference" "$R04_EVIDENCE/reference"
printf '%s\n' "$R04_ORACLE/fixtures" > "$R04_EVIDENCE/fixtures-path.txt"
export R02_FIXTURES="$R04_ORACLE/fixtures" MAKEFLAGS=-j4
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:ActiveProcessorCount=4 -Xmx6g"
cd design
nice -n 10 "${R04_SBT:-$(command -v sbt)}" 'testOnly flow.rvv.RvvIntegrationSpec -- -z C3' > "$R04_EVIDENCE/random.log" 2>&1
