#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 2 ]]; then
  echo "usage: render_excalidraw.sh INPUT.excalidraw OUTPUT.svg" >&2
  exit 2
fi
input=$1
output_svg=$2
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
python3 "$script_dir/validate_excalidraw.py" "$input"
python3 "$script_dir/render_excalidraw.py" "$input" "$output_svg"
test -s "$output_svg"
printf 'PASS svg=%s bytes=%s\n' "$output_svg" "$(stat -c %s "$output_svg")"
