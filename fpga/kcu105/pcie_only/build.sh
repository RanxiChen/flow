#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "$0")/../../.." && pwd)
output=${1:?usage: build.sh ABSOLUTE_FRESH_OUTPUT}
case "$output" in
    /*) ;;
    *) echo "output must be absolute" >&2; exit 2 ;;
esac
test ! -e "$output" || { echo "refusing to reuse $output" >&2; exit 2; }
mkdir -p "$output"

git -C "$root" rev-parse HEAD > "$output/source-commit.txt"
git -C "$root" status --short > "$output/source-status.txt"
git -C "$root" diff --binary > "$output/source.patch"

stage=simulation
finish() {
    result=$?
    trap - EXIT
    echo "$result" > "$output/$stage-exit-code.txt"
    echo "FLOW_PCIE_ONLY_EXIT stage=$stage code=$result"
    exit "$result"
}
trap finish EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

cd "$root"
bash fpga/kcu105/pcie_only/sim/run.sh | tee "$output/simulation.log"

stage=vivado
set +u
source /home/chen/Tool/FPGA/Vivado/2022.2/settings64.sh
set -u
vivado -mode batch -nolog -nojournal \
    -source fpga/kcu105/pcie_only/create_project.tcl \
    -tclargs "$output" 2>&1 | tee "$output/vivado-console.log"

test -s "$output/pcie_only.bit"
test -s "$output/pcie_only.ltx"
sha256sum "$output/pcie_only.bit" > "$output/pcie_only.bit.sha256"
