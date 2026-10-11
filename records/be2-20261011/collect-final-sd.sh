#!/usr/bin/env bash
set -euo pipefail
root=/home/chen/FUN/flow-runs
output="$root/be2-release-three-sd-20261012"
trap 'status=$?; printf "%s\n" "$status" > "${output}.exit"' EXIT
cd /home/chen/FUN/flow-be2-b3-8ea5db5
hostname
date -u +%FT%TZ
python3 "$root/collect_sd_products.py" \
  --source-sha 7f7f186b9326fa7329890013ba578e68b68211ba \
  --rvv-source-sha d902fb518f67a00aa1bf7f8af0dd18e43150f201 \
  --four "$root/be2-7f7f186-breeze-sd" \
  --four-software "$root/be2-final-7f7f186-sd-four" \
  --single "$root/be2-7f7f186-single-parallel-sd" \
  --single-software "$root/be2-final-7f7f186-sd-single" \
  --single-rvv "$root/be2-d902fb5-rvv-clock-resume-batch-sd" \
  --single-rvv-software "$root/be2-final-d902fb5-sd-single-rvv" \
  --output "$output"
