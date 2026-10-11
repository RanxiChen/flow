#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: render_drawio.sh INPUT.drawio OUTPUT.svg" >&2
  exit 2
fi

input_path=$(realpath "$1")
output_path=$(realpath -m "$2")
output_dir=$(dirname "$output_path")
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
powershell_exe=/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe
wsl_init=/init
export_script="$script_dir/export_drawio.ps1"
staging_root=/mnt/d/tongminghu/charlib/727/hermes_drawio_tools/omp-render-staging

if [[ ! -f "$input_path" ]]; then
  echo "input does not exist: $input_path" >&2
  exit 1
fi

if [[ -e "$output_path" ]]; then
  echo "refusing to overwrite existing preview: $output_path" >&2
  exit 1
fi

if [[ ! -x "$powershell_exe" ]]; then
  echo "Windows PowerShell is unavailable from WSL: $powershell_exe" >&2
  exit 1
fi

if [[ ! -x "$wsl_init" ]]; then
  echo "WSL interop launcher is unavailable: $wsl_init" >&2
  exit 1
fi

mkdir -p "$output_dir"
mkdir -p "$staging_root"

staging_dir=$(mktemp -d "$staging_root/run-XXXXXX")
trap 'rm -rf -- "$staging_dir"' EXIT

staging_input="$staging_dir/input.drawio"
staging_output="$staging_dir/preview.svg"
cp -- "$input_path" "$staging_input"

win_input=$(wslpath -w "$staging_input")
win_output=$(wslpath -w "$staging_output")
win_script=$(wslpath -w "$export_script")

"$wsl_init" "$powershell_exe" \
  -NoLogo \
  -NoProfile \
  -NonInteractive \
  -ExecutionPolicy Bypass \
  -File "$win_script" \
  -InputPath "$win_input" \
  -OutputPath "$win_output"

if [[ ! -s "$staging_output" ]]; then
  echo "draw.io CLI did not produce a non-empty staged preview: $staging_output" >&2
  exit 1
fi

cp -- "$staging_output" "$output_path"

if [[ ! -s "$output_path" ]]; then
  echo "failed to copy the rendered preview back to WSL: $output_path" >&2
  exit 1
fi

printf '{"status":"PASS","output":"%s","output_bytes":%s}\n' \
  "$output_path" \
  "$(stat -c %s "$output_path")"
