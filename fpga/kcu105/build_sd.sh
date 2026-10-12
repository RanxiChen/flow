#!/usr/bin/env bash
# Run on the selected, preflighted hardware host. Build only; no programmer/SD writes.
set -Eeuo pipefail
flow_root=$(cd -- "$(dirname -- "$0")/../.." && pwd)
profile=${1:?usage: build_sd.sh four|single|single-rvv ABSOLUTE_FRESH_OUTPUT [--prepare-only|--vivado-only] [--debug]}
output=${2:?absolute fresh output required}
mode=${3:---all}
debug=${4:-}
case "$profile" in
    four) rtl_profile=small; cpu=breeze;;
    single) rtl_profile=single; cpu=breeze-tiny;;
    single-rvv) rtl_profile=single-rvv; cpu=breeze-rvv;;
    *) echo "Unknown product: $profile" >&2; exit 2;;
esac
case "$output" in /*) ;; *) echo 'Output must be absolute' >&2; exit 2;; esac
case "$mode" in --all|--prepare-only|--vivado-only) ;; *) exit 2;; esac
case "$debug" in ''|--debug) ;; *) exit 2;; esac
if [[ -n "$debug" && "$profile" != single ]]; then
    echo '--debug requires the single product' >&2; exit 2
fi
features=coherent-dma
rtl_variant=production/cpu
target_options=()
if [[ "$debug" == --debug ]]; then
    features='tandem fpga-debug coherent-dma'
    rtl_variant=tandem/fpga-debug
    target_options+=(--debug)
fi
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
    printf '%s\n' "$profile" > "$output/product-profile.txt"
    cd "$flow_root/design"
    "${SBT:-sbt}" "runMain flow.top.GenerateBreezeCluster $rtl_profile gshare linux $features" \
        2>&1 | tee "$output/rtl-generation.log"
    cd "$flow_root"
    "${PYTHON:-python3}" fpga/kcu105/target.py --cpu-type "$cpu" --with-sdcard \
        "${target_options[@]}" \
        --sys-clk-freq 100000000 --vivado-threads "${VIVADO_THREADS:-4}" \
        --output-dir "$output" 2>&1 | tee "$output/prepare.log"
    cp "design/build/rtl/axi-cluster/$rtl_profile/gshare/linux/$rtl_variant/coherent-dma/cluster-profile.txt" \
        "$output/cluster-profile.txt"
    touch "$output/prepared"
    echo 0 > "$output/prepare-exit-code.txt"
    [[ "$mode" == --prepare-only ]] && exit 0
fi
test -f "$output/prepared"
test "$(cat "$output/product-profile.txt")" = "$profile"
stage=vivado
trap finish EXIT
cd "$output/gateware"
bash build_xilinx_kcu105.sh 2>&1 | tee "$output/vivado.log"
test -s xilinx_kcu105.bit
sha256sum xilinx_kcu105.bit ../software/bios/bios.bin > "$output/product-sha256.txt"
