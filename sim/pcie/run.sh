#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
for spec in 'memory FlowPcieMemory' 'control FlowPcieControl'; do
    read -r tb rtl <<< "$spec"
    iverilog -g2012 -s "${tb}_tb" -o "$out/$tb" \
        "sim/pcie/${tb}_tb.sv" "litex_wrapper/flow/rtl/$rtl.sv"
    vvp "$out/$tb"
done
python3 sim/pcie/test_arbiter.py
