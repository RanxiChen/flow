#!/usr/bin/env bash
# Alan build profile. Never program the board or overwrite another build.
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")/../.." && pwd)
output=${1:?usage: build_pcie.sh ABSOLUTE_FRESH_OUTPUT}
case "$output" in /*) ;; *) echo 'Output must be absolute' >&2; exit 2;; esac
test ! -e "$output" || { echo "Refusing to reuse $output" >&2; exit 2; }
mkdir -p "$output"
export PATH="/home/chen/miniforge3/envs/flow/bin:/home/chen/.sdkman/candidates/sbt/current/bin:/home/chen/RISCV/bin:$PATH"
stage=tests
finish() {
    result=$?
    trap - EXIT
    if [[ "$stage" == vivado && "$result" == 0 && ! -s "$output/gateware/xilinx_kcu105.bit" ]]; then
        result=1
    fi
    echo "$result" > "$output/$stage-exit-code.txt"
    echo "FLOW_PCIE_EXIT stage=$stage code=$result"
    exit "$result"
}
trap finish EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
git -C "$root" rev-parse HEAD > "$output/source-commit.txt"
git -C "$root" status --short > "$output/source-status.txt"
git -C "$root" diff --binary > "$output/source.patch"
cd "$root/design"
sbt 'testOnly flow.cache.BreezeCoherentDmaSpec' 2>&1 | tee "$output/coherent-dma-tests.log"
echo 0 > "$output/tests-exit-code.txt"
stage=prepare
cd "$root"
python fpga/kcu105/target.py --cpu-type breeze-tiny --with-fase --with-sdcard --with-pcie \
    --sys-clk-freq 100000000 --output-dir "$output" 2>&1 | tee "$output/prepare.log"
echo 0 > "$output/prepare-exit-code.txt"
touch "$output/prepared"
stage=vivado
set +u
source /home/chen/Tool/FPGA/Vivado/2022.2/settings64.sh
set -u
cd "$output/gateware"
bash build_xilinx_kcu105.sh 2>&1 | tee "$output/vivado-console.log"
test -s xilinx_kcu105.bit
sha256sum xilinx_kcu105.bit > "$output/gateware-sha256.txt"
# A generated bitstream is NOT a timing pass. Inspect timing_summary separately.
