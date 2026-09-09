#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
act_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
upstream_dir=${ACT4_UPSTREAM_DIR:-$act_dir/.upstream}
work_dir=${ACT4_WORK_DIR:-$act_dir/.work}
extensions=${ACT4_EXTENSIONS:-I}
config_file=$act_dir/config/breeze-rv64gc/test_config.yaml
toolchain_root=${ACT4_TOOLCHAIN_ROOT:-/home/chen/opt/act4/gcc-2026.07.15}
sail_root=${ACT4_SAIL_ROOT:-/home/chen/opt/act4/sail-0.13.1}
mise_root=${ACT4_MISE_ROOT:-/home/chen/.local/bin}

export PATH="$toolchain_root/bin:$sail_root/bin:$mise_root:$PATH"

test -d "$upstream_dir/.git" || {
    echo "ACT4 checkout missing; run scripts/fetch_act4.sh first" >&2
    exit 2
}
preflight_status=0
if ! command -v mise >/dev/null; then
    echo "missing mise (required for pinned ACT4 Python and Ruby dependencies)" >&2
    preflight_status=2
fi
if ! command -v riscv64-unknown-elf-gcc >/dev/null; then
    echo "missing riscv64-unknown-elf-gcc (ACT4 requires GCC 15 or newer)" >&2
    preflight_status=2
else
    gcc_version=$(riscv64-unknown-elf-gcc -dumpfullversion)
    gcc_major=${gcc_version%%.*}
    case "$gcc_major" in
        ''|*[!0-9]*)
            echo "cannot parse RISC-V GCC version: $gcc_version" >&2
            preflight_status=2
            ;;
        *)
            if [ "$gcc_major" -lt 15 ]; then
                echo "RISC-V GCC $gcc_version is too old; pinned ACT4 requires GCC 15 or newer" >&2
                preflight_status=2
            fi
            ;;
    esac
fi

if ! command -v riscv64-unknown-elf-nm >/dev/null; then
    echo "missing riscv64-unknown-elf-nm" >&2
    preflight_status=2
fi

if ! command -v sail_riscv_sim >/dev/null; then
    echo "missing sail_riscv_sim (pinned ACT4 requires Sail 0.13.1)" >&2
    preflight_status=2
else
    sail_version=$(sail_riscv_sim --version 2>&1)
    case "$sail_version" in
        *0.13.1*) ;;
        *)
            echo "unexpected Sail version: $sail_version" >&2
            echo "pinned ACT4 requires Sail 0.13.1" >&2
            preflight_status=2
            ;;
    esac
fi

test "$preflight_status" -eq 0 || exit "$preflight_status"

make -C "$upstream_dir" \
    CONFIG_FILES="$config_file" \
    WORKDIR="$work_dir" \
    EXTENSIONS="$extensions"

echo "ACT4_BUILD_DONE extensions=$extensions work=$work_dir"
