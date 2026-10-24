#!/usr/bin/env bash
set -Eeuo pipefail
run=${1:?independent run directory}
work=${2:?clean git checkout}
source /home/chen/miniforge3/bin/activate flow
finish() { rc=$?; trap - EXIT; printf '%s\n' "$rc" > "$run/validation.exit"; date -Is > "$run/validation-finished.txt"; exit "$rc"; }
trap finish EXIT
export HTTP_PROXY=http://127.0.0.1:18897 HTTPS_PROXY=http://127.0.0.1:18897
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 MAKEFLAGS=-j4
date -Is > "$run/validation-started.txt"
git -C "$work" rev-parse HEAD > "$run/source-sha.txt"
git -C "$work" status --porcelain > "$run/source-status.txt"
test ! -s "$run/source-status.txt"
cd "$work/design"
java -Xmx8G -XX:ActiveProcessorCount=4 -Dsbt.supershell=false \
  -jar /home/chen/FUN/flow-runs/mmu-extraction-20261019-f058e3b/sbt-launch.jar \
  'testOnly flow.mmu.experiment.BreezePmpFourStageSpec' > "$run/tests.log" 2>&1
python3 - "$work" "$run" <<'PY'
from pathlib import Path
import json,sys,xml.etree.ElementTree as E
w,r=map(Path,sys.argv[1:])
p=w/'design/target/test-reports/TEST-flow.mmu.experiment.BreezePmpFourStageSpec.xml'
e=E.parse(p).getroot()
assert int(e.get('tests','0'))==3
assert all(int(e.get(k,'0'))==0 for k in ('failures','errors','skipped'))
(r/'tests-audit.json').write_text(json.dumps({'tests':3,'failures':0,'errors':0,'skipped':0,'cases':[c.get('name') for c in e.findall('testcase')]},indent=2)+'\n')
PY
java -Xmx8G -XX:ActiveProcessorCount=4 -Dsbt.supershell=false \
  -jar /home/chen/FUN/flow-runs/mmu-extraction-20261019-f058e3b/sbt-launch.jar \
  "runMain flow.mmu.experiment.GenerateBreezePmpFourStageHarness --target-dir $run/rtl" > "$run/generate.log" 2>&1
python3 - "$run" <<'PY'
from pathlib import Path
import json,sys,hashlib
r=Path(sys.argv[1]); files=sorted((r/'rtl').glob('*.sv'))
assert files and (r/'rtl/BreezePmpFourStageHarness.sv').exists()
(r/'rtl-manifest.json').write_text(json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in files},indent=2)+'\n')
PY
