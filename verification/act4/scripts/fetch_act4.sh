#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
act_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
. "$act_dir/ACT4_REV"

upstream_dir=${ACT4_UPSTREAM_DIR:-$act_dir/.upstream}

if [ ! -d "$upstream_dir/.git" ]; then
    git clone --filter=blob:none --branch "$ACT4_BRANCH" "$ACT4_URL" "$upstream_dir"
fi

configured_url=$(git -C "$upstream_dir" remote get-url origin)
if [ "$configured_url" != "$ACT4_URL" ]; then
    echo "ACT4 origin mismatch: expected $ACT4_URL, got $configured_url" >&2
    exit 2
fi

git -C "$upstream_dir" fetch origin "$ACT4_COMMIT"
git -C "$upstream_dir" checkout --detach "$ACT4_COMMIT"
actual_commit=$(git -C "$upstream_dir" rev-parse HEAD)
test "$actual_commit" = "$ACT4_COMMIT"
echo "ACT4_READY commit=$actual_commit path=$upstream_dir"
