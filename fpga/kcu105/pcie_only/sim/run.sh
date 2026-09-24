#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")/../../../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
iverilog -g2012 -Wall -s decerr_tb -o "$work/decerr_tb" \
    "$root/fpga/kcu105/pcie_only/sim/decerr_tb.sv" \
    "$root/fpga/kcu105/pcie_only/rtl/PcieAxiDecerr.sv"
vvp "$work/decerr_tb"
