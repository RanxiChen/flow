#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 2 ]]; then
  echo "usage: rasterize_svg_for_review.sh INPUT.svg /tmp/OUTPUT.png" >&2
  exit 2
fi
input=$(realpath "$1")
output=$(realpath -m "$2")
case "$output" in
  /tmp/*) ;;
  *) echo "ERROR: review raster must be under /tmp" >&2; exit 2 ;;
esac
if [[ -e "$output" ]]; then
  echo "ERROR: refusing to overwrite $output" >&2
  exit 1
fi
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
read -r width height < <(python3 "$script_dir/svg_dimensions.py" "$input")
scale=${RASTER_SCALE:-1}
if [[ ! "$scale" =~ ^[1-4]$ ]]; then
  echo "ERROR: RASTER_SCALE must be an integer from 1 to 4" >&2
  exit 2
fi
chrome=${CHROME_BIN:-}
if [[ -z "$chrome" ]]; then
  for candidate in /home/chen/.cache/ms-playwright/chromium-*/chrome-linux64/chrome /usr/bin/google-chrome /usr/bin/chromium; do
    if [[ -x "$candidate" ]]; then chrome=$candidate; break; fi
  done
fi
if [[ -z "$chrome" || ! -x "$chrome" ]]; then
  echo "ERROR: headless Chromium not found" >&2
  exit 127
fi
"$chrome" --headless --no-sandbox --disable-gpu --disable-dev-shm-usage \
  --disable-crashpad --hide-scrollbars --screenshot="$output" \
  --force-device-scale-factor="$scale" \
  --window-size="$width,$height" "file://$input"
test -s "$output"
printf 'PASS temporary_review=%s bytes=%s viewport=%sx%s scale=%s\n' \
  "$output" "$(stat -c %s "$output")" "$width" "$height" "$scale"
