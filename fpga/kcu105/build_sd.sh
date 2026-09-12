#!/usr/bin/env bash
# Build only. Never invoke a programmer or write an SD card.
set -euo pipefail
flow_root=$(cd -- "$(dirname -- "$0")/../.." && pwd)
output=${1:?usage: build_sd.sh ABSOLUTE_FRESH_OUTPUT [--prepare-only|--vivado-only]}
mode=${2:---all}
case "$output" in /*) ;; *) echo 'Output must be absolute' >&2; exit 2;; esac
case "$mode" in --all|--prepare-only|--vivado-only) ;; *) exit 2;; esac
export PATH="/home/chen/.sdkman/candidates/sbt/current/bin:/home/chen/RISCV/bin:$PATH"
python=/home/chen/miniforge3/envs/flow/bin/python
stage=prepare
finish() {
    result=$?
    trap - EXIT
    echo "$result" > "$output/$stage-exit-code.txt"
    echo "FLOW_BUILD_EXIT stage=$stage code=$result output=$output"
    exit "$result"
}
if [[ "$mode" != --vivado-only ]]; then
    if [[ -e "$output" ]]; then echo "Refusing to reuse build directory: $output" >&2; exit 2; fi
    mkdir -p "$output"
    trap finish EXIT
    git -C "$flow_root" rev-parse HEAD > "$output/source-commit.txt"
    git -C "$flow_root" status --short > "$output/source-status.txt"
    git -C "$flow_root" diff --binary > "$output/source.patch"
    cp "$flow_root/config/breeze_mcu_platform.json" "$output/platform.json"
    echo 'FLOW_STAGE directed hardware tests'
    cd "$flow_root/design"
    sbt 'testOnly flow.cache.BreezeCoherentDmaSpec flow.cache.BreezeL2HomeSpec flow.cache.BreezeL2HomeSmallSpec flow.cache.BreezeDCacheCoherentSpec flow.platform.BreezeLinuxPmaSpec' \
        2>&1 | tee "$output/directed-tests.log"
    echo 'FLOW_STAGE generate single/coherent-dma FPGA RTL and BIOS'
    cd "$flow_root"
    "$python" fpga/kcu105/target.py --cpu-type breeze-tiny --debug --with-sdcard \
        --sys-clk-freq 100000000 --output-dir "$output" 2>&1 | tee "$output/prepare.log"
    cp design/build/rtl/cluster/single/gshare/linux/fpga-debug/coherent-dma/cluster-profile.txt "$output/cluster-profile.txt"
    touch "$output/prepared"
    echo 0 > "$output/prepare-exit-code.txt"
    [[ "$mode" == --prepare-only ]] && exit 0
fi
test -f "$output/prepared"
stage=vivado
trap finish EXIT
echo 'FLOW_STAGE Vivado synthesis, implementation and bitstream'
set +u
source /home/chen/Tool/FPGA/Vivado/2022.2/settings64.sh
set -u
cd "$output/gateware"
bash build_xilinx_kcu105.sh
test -s xilinx_kcu105.bit
test -s xilinx_kcu105.ltx
sha256sum xilinx_kcu105.bit xilinx_kcu105.ltx > "$output/gateware-sha256.txt"
