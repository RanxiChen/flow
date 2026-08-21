#!/usr/bin/env bash
set -euo pipefail

flow_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
build_dir=${FLOW_PLIC_BUILD_DIR:-"${flow_root}/build/flow-plic-unit"}

mkdir -p "${build_dir}"
verilator \
  --binary \
  --timing \
  -Wall \
  -Wno-fatal \
  -Wno-UNUSEDSIGNAL \
  --top-module flow_plic_tb \
  --Mdir "${build_dir}/obj_dir" \
  -o FlowPlicTest \
  "${flow_root}/litex_wrapper/flow/rtl/FlowPlic.sv" \
  "${flow_root}/sim/rtl/flow_plic_tb.sv"

"${build_dir}/obj_dir/FlowPlicTest" | tee "${build_dir}/run.log"
rg -F '[FLOW-PLIC-PASS]' "${build_dir}/run.log"
