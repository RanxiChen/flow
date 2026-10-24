#!/usr/bin/env bash
set -Eeuo pipefail
run=${1:?independent run directory}
work=${2:?clean git checkout}
source /home/chen/miniforge3/bin/activate flow
finish() { rc=$?; trap - EXIT; printf '%s\n' "$rc" > "$run/validation.exit"; date -Is > "$run/finished.txt"; exit "$rc"; }
trap finish EXIT
export HTTP_PROXY=http://127.0.0.1:18897 HTTPS_PROXY=http://127.0.0.1:18897
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 MAKEFLAGS=-j4
date -Is > "$run/started.txt"
git -C "$work" rev-parse HEAD > "$run/source-sha.txt"
git -C "$work" status --porcelain > "$run/source-status.txt"
test ! -s "$run/source-status.txt"
cd "$work/design"
java -Xmx8G -XX:ActiveProcessorCount=4 -Dsbt.supershell=false \
  -jar /home/chen/FUN/flow-runs/mmu-extraction-20261019-f058e3b/sbt-launch.jar \
  'testOnly flow.frontend.experiment.BreezeGshareHistoryTimingSpec' > "$run/tests.log" 2>&1
python3 - "$work" "$run" <<'PY'
from pathlib import Path
import json,sys,xml.etree.ElementTree as E
w,r=map(Path,sys.argv[1:]); p=w/'design/target/test-reports/TEST-flow.frontend.experiment.BreezeGshareHistoryTimingSpec.xml'
e=E.parse(p).getroot()
assert int(e.get('tests','0'))==1
assert all(int(e.get(k,'0'))==0 for k in ('failures','errors','skipped'))
(r/'tests-audit.json').write_text(json.dumps({'tests':1,'failures':0,'errors':0,'skipped':0,'cases':[c.get('name') for c in e.findall('testcase')]},indent=2)+'\n')
(r/'tests.xml').write_bytes(p.read_bytes())
PY
java -Xmx8G -XX:ActiveProcessorCount=4 -Dsbt.supershell=false \
  -jar /home/chen/FUN/flow-runs/mmu-extraction-20261019-f058e3b/sbt-launch.jar \
  "runMain flow.frontend.experiment.GenerateBreezeGshareHistoryTimingHarness --target-dir $run/rtl" > "$run/generate.log" 2>&1
python3 - "$run" <<'PY'
from pathlib import Path
import json,sys,hashlib
r=Path(sys.argv[1]); files=sorted((r/'rtl').glob('*.sv'))
assert files and (r/'rtl/BreezeBankedPht.sv').exists()
(r/'rtl-manifest.json').write_text(json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in files},indent=2)+'\n')
PY
