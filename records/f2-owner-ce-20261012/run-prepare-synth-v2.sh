#!/usr/bin/env bash
set -Eeuo pipefail
work=/home/chen/FUN/flow-f2-owner-ce-20261012
root=/home/chen/FUN/flow-runs/f2-wholecore-v2-7820bf6-20261012
source_sha=7820bf6eb16eaef40337c97e0a59ac8369b35d79
stage=prepare
mkdir -p "$root"
trap 'rc=$?; echo "$rc" > "$root/$stage.exit"; date -u +%FT%TZ > "$root/$stage.end-utc.txt"' EXIT
source /home/chen/miniforge3/bin/activate flow
export PATH=/home/chen/Tool/FPGA/Vivado/2022.2/bin:$PATH
export CPATH=/home/chen/FUN/flow-soc2-deps-20261007/sysroot/usr/include:${CPATH:-}
export LIBRARY_PATH=/home/chen/FUN/flow-soc2-deps-20261007/sysroot/usr/lib/x86_64-linux-gnu:${LIBRARY_PATH:-}
export LD_LIBRARY_PATH=/home/chen/FUN/flow-soc2-deps-20261007/sysroot/lib:/home/chen/FUN/flow-soc2-deps-20261007/sysroot/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}
export http_proxy=http://127.0.0.1:18897 https_proxy=http://127.0.0.1:18897
export SBT=/home/chen/FUN/flow-runs/pipeline-sbt-prepare-fulltlb-20261012.sh MAKEFLAGS=-j4 VIVADO_THREADS=4
cd "$work"
test "$(git rev-parse HEAD)" = "$source_sha"
test -z "$(git status --porcelain --untracked-files=no)"
test "$(cat /home/chen/FUN/flow-runs/f2-wholecore-v2-7820bf6-20261012/tests/run.exit)" = 0
hostname > "$root/$stage.host.txt"; date -u +%FT%TZ > "$root/$stage.start-utc.txt"
bash fpga/kcu105/build_sd.sh single "$root/single" --prepare-only --debug 2>&1 | tee "$root/prepare.console.log"
python records/be2-pipeline-probe-20261012/bind-production.py --repo "$work" --hardware "$root/single" --source "$source_sha"
echo 0 > "$root/prepare.exit"; date -u +%FT%TZ > "$root/prepare.end-utc.txt"
stage=synth
hostname > "$root/$stage.host.txt"; date -u +%FT%TZ > "$root/$stage.start-utc.txt"
vivado -version > "$root/vivado-version.txt"
python /home/chen/FUN/flow-runs/f2-wholecore-v2-make-synth-only-20261012.py "$root/single/gateware"
cd "$root/single/gateware"
printf '%s\n' 'taskset -c 14-17 nice -n 10 vivado -mode batch -source f2-synth-only.tcl' > "$root/synth-command.txt"
taskset -c 14-17 nice -n 10 vivado -mode batch -source f2-synth-only.tcl 2>&1 | tee "$root/synth.console.log"
