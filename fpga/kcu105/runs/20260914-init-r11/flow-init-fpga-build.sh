#!/bin/bash
set -euo pipefail
root=/home/chen/FUN/flow
out=$root/build/fpga/kcu105-single-init-ila-100mhz-20260914-r11
mkdir "$out"
trap 'rc=$?; echo "$rc" > "$out/exit-code.txt"; date -Is > "$out/finished-at.txt"' EXIT
export PATH=/home/chen/miniforge3/envs/flow/bin:/home/chen/.sdkman/candidates/sbt/current/bin:/home/chen/RISCV/bin:$PATH
cd "$root"
date -Is > "$out/started-at.txt"
git rev-parse HEAD > "$out/source-commit.txt"
git diff --binary > "$out/source.patch"
cp config/breeze_mcu_platform.json "$out/platform.json"
cd design
sbt 'testOnly flow.platform.BreezeLinuxPmaSpec' > "$out/pma-tests.log" 2>&1
cd "$root"
python fpga/kcu105/target.py --cpu-type breeze-tiny --debug --sys-clk-freq 100000000 --output-dir "$out" > "$out/prepare.log" 2>&1
cp design/build/rtl/cluster/single/gshare/linux/fpga-debug/cluster-profile.txt "$out/cluster-profile.txt"
echo 0 > "$out/prepare-exit-code.txt"
set +u
source /home/chen/Tool/FPGA/Vivado/2022.2/settings64.sh
set -u
cd "$out/gateware"
bash build_xilinx_kcu105.sh > "$out/vivado-console.log" 2>&1
test -s xilinx_kcu105.bit
test -s xilinx_kcu105.ltx
sha256sum xilinx_kcu105.bit xilinx_kcu105.ltx > "$out/gateware-sha256.txt"
