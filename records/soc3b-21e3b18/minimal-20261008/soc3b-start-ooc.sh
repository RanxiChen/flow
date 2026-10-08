#!/usr/bin/env bash
set -euo pipefail
run_root=/home/chen/FUN/flow-runs/soc3b-21e3b18/cluster-ooc
cd "$run_root"
sha256sum rtl-snapshot.tgz
if [[ -e result.json || -e vivado.pid ]]; then
  echo 'Existing OOC launch evidence; refusing a duplicate run.' >&2
  exit 2
fi
tar -xzf rtl-snapshot.tgz
cp soc3b-synth.tcl synth.tcl
cp soc3b-queries.tcl queries.tcl
cp soc3b-clock.xdc clock.xdc
nohup python3 soc3b-ooc-job.py > launcher.log 2>&1 < /dev/null &
echo "$!" > job.pid
cat job.pid
