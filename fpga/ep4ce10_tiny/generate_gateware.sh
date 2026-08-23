#!/usr/bin/env bash

set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly FLOW_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly FIRMWARE_DIR="$FLOW_ROOT/software/breeze-tiny-ep4ce10"
readonly FIRMWARE_BIN="$FIRMWARE_DIR/build/breeze-tiny-ep4ce10.bin"
readonly FIRMWARE_ELF="$FIRMWARE_DIR/build/breeze-tiny-ep4ce10.elf"
readonly FIRMWARE_DIS="$FIRMWARE_DIR/build/breeze-tiny-ep4ce10.dis"

CCACHE_DISABLE=1 make -C "$FIRMWARE_DIR"

readonly ENTRY_ADDR="$(riscv64-linux-gnu-readelf -h "$FIRMWARE_ELF" \
    | awk '/Entry point address:/ {print $4}')"
if [[ "$ENTRY_ADDR" != "0x10000000" ]]; then
    echo "ERROR: firmware entry is $ENTRY_ADDR, expected 0x10000000" >&2
    exit 1
fi

readonly FORBIDDEN_MNEMONICS="$(awk '/^[[:space:]]*[0-9a-f]+:/ {print $3}' \
    "$FIRMWARE_DIS" \
    | grep -E '^(mul|mulh|mulhsu|mulhu|div|divu|rem|remu|lr\.|sc\.|amo|f[a-z]|c\.)' \
    || true)"
if [[ -n "$FORBIDDEN_MNEMONICS" ]]; then
    echo "ERROR: firmware contains instructions outside RV64I/Zicsr/Zifencei:" >&2
    echo "$FORBIDDEN_MNEMONICS" >&2
    exit 1
fi

cd "$FLOW_ROOT/design"
sbt -batch -mem 2048 "runMain flow.top.GenerateBreezeTinyFpga"

cd "$FLOW_ROOT"
python3 fpga/ep4ce10_tiny/target.py --firmware "$FIRMWARE_BIN"

readonly GATEWARE_DIR="$FLOW_ROOT/build/ep4ce10-tiny/gateware"
readonly QSF="$GATEWARE_DIR/flow_tiny_ep4ce10.qsf"
readonly SDC="$GATEWARE_DIR/flow_tiny_ep4ce10.sdc"
if rg -n '/home/|/mnt/' "$QSF"; then
    echo "ERROR: Quartus project contains host-specific absolute paths" >&2
    exit 1
fi
if ! rg -q 'create_clock .*period 20\.0 .*clk50' "$SDC"; then
    echo "ERROR: generated SDC is missing the 50 MHz clk50 constraint" >&2
    exit 1
fi
for pin in E1 M1 N5 M7; do
    if ! rg -q "Pin_$pin" "$QSF"; then
        echo "ERROR: generated QSF is missing required board pin $pin" >&2
        exit 1
    fi
done

echo "GATEWARE GENERATED: build/ep4ce10-tiny/gateware"
echo "FIRMWARE ENTRY: $ENTRY_ADDR"
