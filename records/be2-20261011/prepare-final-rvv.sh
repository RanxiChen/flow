#!/usr/bin/env bash
set -euo pipefail
output=/home/chen/FUN/flow-runs/be2-final-d902fb5-sd-single-rvv
trap 'status=$?; printf "%s\n" "$status" > "${output}.exit"' EXIT
cd /home/chen/FUN/flow-be2-b3-8ea5db5
hostname
git rev-parse HEAD
riscv64-unknown-elf-gcc --version
dtc --version
date -u +%FT%TZ
python3 fpga/kcu105/prepare_sd_boot.py \
  --profile single-rvv \
  --hardware-dir /home/chen/FUN/flow-runs/be2-d902fb5-rvv-clock-resume-batch-sd \
  --images /home/chen/FUN/flow-runs/be2-4e1f86d-linux/images \
  --output "$output"
