#!/usr/bin/env bash

set -euo pipefail

readonly PROJECT=flow_tiny_ep4ce10
readonly EXPECTED_JTAG_ID=020F10DD
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly FLOW_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly GATEWARE_DIR="$FLOW_ROOT/build/ep4ce10-tiny/gateware"
readonly SOF="$GATEWARE_DIR/$PROJECT.sof"
readonly CMD_EXE=/mnt/c/Windows/System32/cmd.exe
readonly QUARTUS_BIN_LINUX=/mnt/d/quartus/quarus/quartus/bin64
readonly QUARTUS_BIN_WIN='D:\quartus\quarus\quartus\bin64'

if [[ ! -s "$SOF" ]]; then
    echo "ERROR: SOF is missing or empty: $SOF" >&2
    echo "Run fpga/ep4ce10_tiny/build_quartus.sh first." >&2
    exit 1
fi
for tool in jtagconfig.exe quartus_pgm.exe; do
    if [[ ! -x "$QUARTUS_BIN_LINUX/$tool" ]]; then
        echo "ERROR: Quartus tool not found: $QUARTUS_BIN_LINUX/$tool" >&2
        exit 1
    fi
done

readonly JTAG_CHAIN="$(
    cd /tmp
    "$CMD_EXE" /d /c \
        "cd /d $QUARTUS_BIN_WIN && jtagconfig.exe" | tr -d '\r'
)"
printf '%s\n' "$JTAG_CHAIN"
if [[ "$(grep -c 'USB-Blaster' <<<"$JTAG_CHAIN")" -ne 1 ]]; then
    echo "ERROR: expected exactly one USB-Blaster" >&2
    exit 1
fi
if ! grep -q "$EXPECTED_JTAG_ID" <<<"$JTAG_CHAIN"; then
    echo "ERROR: expected FPGA JTAG ID $EXPECTED_JTAG_ID was not found" >&2
    exit 1
fi

readonly WINDOWS_DIR="$(wslpath -w "$GATEWARE_DIR")"
cd /tmp
"$CMD_EXE" /d /c \
    "pushd $WINDOWS_DIR && $QUARTUS_BIN_WIN\quartus_pgm.exe -m JTAG -o p;$PROJECT.sof"

echo "PROGRAM SUCCESS"
