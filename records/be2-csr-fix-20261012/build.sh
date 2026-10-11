#!/usr/bin/env bash
set -euo pipefail
expected=${1:?exact source SHA required}
work=/home/chen/FUN/flow-be2-csr-fix-20261012
output=/home/chen/FUN/flow-runs/be2-single-debug-csrfix-20261012
evidence=/home/chen/FUN/flow-runs/be2-csr-fix-426aae1-20261012
oldwork=/home/chen/FUN/flow-be2-single-debug-20261012
oldoutput=/home/chen/FUN/flow-runs/be2-single-debug-20261012
stage=integration
trap 'status=$?; printf "%s\n" "$status" > "$evidence/build.exit"; printf "STAGE=%s EXIT=%s\n" "$stage" "$status"' EXIT
cd "$work"
test "$(git rev-parse HEAD)" = "$expected"
test -z "$(git status --porcelain --untracked-files=no)"
git diff --exit-code b8e62666de08752a96fd27b838a2fcd79536d3da -- design third_party/cvfpu
hostname
git rev-parse HEAD
date -u +%FT%TZ
set -x
taskset -c 6-9 nice -n 10 python -m unittest -v \
  sim.litex.test_breeze_cpu_wrapper sim.litex.test_breeze_ila \
  sim.litex.test_breeze_axi_monitor sim.litex.test_migen_width_contract
stage=reuse_unchanged_core_rtl
python - "$work" "$oldwork" "$oldoutput" "$evidence" <<'PY'
import pathlib, json, hashlib, shutil, subprocess, sys
work, oldwork, oldoutput, evidence = map(pathlib.Path, sys.argv[1:])
manifest = json.loads((oldoutput / 'source-snapshot/manifest.json').read_text())
for entry in manifest:
    assert hashlib.sha256(pathlib.Path(entry['original']).read_bytes()).hexdigest() == entry['sha256'], entry
    assert hashlib.sha256(pathlib.Path(entry['snapshot']).read_bytes()).hexdigest() == entry['sha256'], entry
relative = pathlib.Path('design/build/rtl/axi-cluster/single/gshare/linux/tandem/fpga-debug/coherent-dma')
assert not (work / relative).exists()
shutil.copytree(oldwork / relative, work / relative)
binding = {'rtl_origin_source_sha': 'b8e62666de08752a96fd27b838a2fcd79536d3da',
           'new_source_sha': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
           'design_and_cvfpu_git_diff': [], 'original_and_snapshot_sources_verified': len(manifest),
           'reuse_directory': str(work / relative), 'generator_rerun': False}
(evidence / 'core-rtl-reuse.json').write_text(json.dumps(binding, indent=2) + '\n')
PY
stage=prepare
test ! -e "$output"
taskset -c 6-9 nice -n 10 python fpga/kcu105/target.py \
  --cpu-type breeze-tiny --debug --with-sdcard --sys-clk-freq 100000000 \
  --vivado-threads 4 --output-dir "$output"
git rev-parse HEAD > "$output/source-commit.txt"
git status --porcelain --untracked-files=no > "$output/source-status.txt"
cp design/build/rtl/axi-cluster/single/gshare/linux/tandem/fpga-debug/coherent-dma/cluster-profile.txt "$output/cluster-profile.txt"
stage=vivado
cd "$output/gateware"
taskset -c 6-9 nice -n 10 vivado -mode batch -source xilinx_kcu105.tcl \
  -log vivado.log -journal vivado.jou
test -s xilinx_kcu105.bit
test -s xilinx_kcu105.ltx
sha256sum xilinx_kcu105.bit xilinx_kcu105.ltx ../software/bios/bios.bin > ../product-sha256.txt
