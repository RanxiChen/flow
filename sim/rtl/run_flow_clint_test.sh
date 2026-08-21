#!/usr/bin/env bash
set -euo pipefail

flow_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
build_dir=${FLOW_CLINT_BUILD_DIR:-"${flow_root}/build/flow-clint-unit"}

mkdir -p "${build_dir}"
verilator \
  --binary \
  --timing \
  -Wall \
  -Wno-fatal \
  -Wno-UNUSEDSIGNAL \
  --top-module flow_clint_tb \
  --Mdir "${build_dir}/obj_dir" \
  -o FlowClintTest \
  "${flow_root}/litex_wrapper/flow/rtl/FlowClint.sv" \
  "${flow_root}/sim/rtl/flow_clint_tb.sv"

"${build_dir}/obj_dir/FlowClintTest" | tee "${build_dir}/run.log"
rg -F '[FLOW-CLINT-PASS]' "${build_dir}/run.log"
