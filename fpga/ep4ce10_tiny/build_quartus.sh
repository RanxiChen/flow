#!/usr/bin/env bash

set -euo pipefail

readonly PROJECT=flow_tiny_ep4ce10
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly FLOW_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
readonly GATEWARE_DIR="$FLOW_ROOT/build/ep4ce10-tiny/gateware"
readonly CMD_EXE=/mnt/c/Windows/System32/cmd.exe
readonly QUARTUS_SH_LINUX=/mnt/d/quartus/quarus/quartus/bin64/quartus_sh.exe
readonly QUARTUS_SH_WIN='D:\quartus\quarus\quartus\bin64\quartus_sh.exe'
readonly QUARTUS_LOG="$GATEWARE_DIR/$PROJECT.quartus.log"

for input_file in "$PROJECT.qsf" "$PROJECT.sdc" "$PROJECT.v"; do
    if [[ ! -f "$GATEWARE_DIR/$input_file" ]]; then
        echo "ERROR: missing $GATEWARE_DIR/$input_file; run generate_gateware.sh first" >&2
        exit 1
    fi
done
if [[ ! -x "$CMD_EXE" ]] || [[ ! -x "$QUARTUS_SH_LINUX" ]]; then
    echo "ERROR: Windows Quartus toolchain is unavailable" >&2
    exit 1
fi

readonly WINDOWS_DIR="$(wslpath -w "$GATEWARE_DIR")"
cd /tmp
set +e
"$CMD_EXE" /d /c \
    "pushd $WINDOWS_DIR && $QUARTUS_SH_WIN --flow compile $PROJECT" \
    2>&1 | tee "$QUARTUS_LOG"
readonly QUARTUS_STATUS="${PIPESTATUS[0]}"
set -e

readonly FLOW_REPORT="$GATEWARE_DIR/$PROJECT.flow.rpt"
readonly FIT_REPORT="$GATEWARE_DIR/$PROJECT.fit.summary"
readonly STA_REPORT="$GATEWARE_DIR/$PROJECT.sta.summary"
readonly SOF="$GATEWARE_DIR/$PROJECT.sof"
if [[ "$QUARTUS_STATUS" -ne 0 ]]; then
    if grep -q "Error (292028)" "$QUARTUS_LOG"; then
        echo "ERROR: Quartus license is not valid for this machine (292028)" >&2
        echo "The design did not reach synthesis; no resource conclusion is possible." >&2
    else
        echo "ERROR: Quartus exited with status $QUARTUS_STATUS" >&2
    fi
    exit "$QUARTUS_STATUS"
fi
if [[ ! -f "$FLOW_REPORT" ]]; then
    echo "ERROR: Quartus did not produce $FLOW_REPORT" >&2
    exit 1
fi
if ! grep -q "Flow Status[[:space:]]*:[[:space:]]*Successful" "$FLOW_REPORT"; then
    echo "ERROR: Quartus flow was not successful" >&2
    tail -80 "$FLOW_REPORT" >&2
    exit 1
fi
for output_file in "$FIT_REPORT" "$STA_REPORT" "$SOF"; do
    if [[ ! -s "$output_file" ]]; then
        echo "ERROR: Quartus did not produce $output_file" >&2
        exit 1
    fi
done
if grep -Eq 'Slack[[:space:]]*:[[:space:]]*-' "$STA_REPORT"; then
    echo "ERROR: Quartus reports negative timing slack" >&2
    cat "$STA_REPORT" >&2
    exit 1
fi

echo "QUARTUS SUCCESS"
echo "REPORT: build/ep4ce10-tiny/gateware/$PROJECT.flow.rpt"
echo "BITSTREAM: build/ep4ce10-tiny/gateware/$PROJECT.sof"
cat "$FIT_REPORT"
cat "$STA_REPORT"
