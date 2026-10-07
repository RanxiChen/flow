#!/usr/bin/env bash
# Build the riscv-tests physical-memory (-p-) ISA programs used by
# flow.cluster.ClusterIsaSpec (docs/tasks/CLUSTER-sim-abi.md §3).
#
#   tools/build_riscv_tests.sh            # default prefix riscv64-unknown-elf-
#   RISCV_PREFIX=riscv64-linux-gnu- tools/build_riscv_tests.sh
#
# Output: design/build/riscv-tests/<suite>-p-<test> (ELF, no extension) and
# NOT_BUILT.txt listing programs the toolchain could not assemble (for
# example amocas_* when the assembler lacks Zacas). Only the -p- environment
# is built: -v- needs PIC support that the Linux cross toolchain rejects, and
# the cluster tests do not use virtual-memory test environments.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/third_party/riscv-tests"
OUT="$ROOT/design/build/riscv-tests"
SUITES=(rv64ui rv64um rv64ua rv64uc rv64mi rv64si)

if [[ ! -f "$SRC/isa/Makefile" || ! -f "$SRC/env/p/link.ld" ]]; then
  echo "riscv-tests submodule missing: git submodule update --init --recursive third_party/riscv-tests" >&2
  exit 1
fi

if [[ -z "${RISCV_PREFIX:-}" ]]; then
  for p in riscv64-unknown-elf- riscv64-linux-gnu-; do
    if command -v "${p}gcc" >/dev/null 2>&1; then RISCV_PREFIX="$p"; break; fi
  done
fi
GCC="${RISCV_PREFIX:-riscv64-unknown-elf-}gcc"
command -v "$GCC" >/dev/null 2>&1 || { echo "cannot find $GCC; set RISCV_PREFIX" >&2; exit 1; }
READELF="${RISCV_PREFIX}readelf"

# Same flags as the riscv-tests isa/Makefile p-environment compile_template.
# --build-id=none: the Linux toolchain otherwise places a build-id note at
# 0x80000000 and shifts _start; the harness resets at 0x80000000.
OPTS=(-static -mcmodel=medany -fvisibility=hidden -nostdlib -nostartfiles -Wl,--build-id=none
  -I"$SRC/env/p" -I"$SRC/isa/macros/scalar" -T"$SRC/env/p/link.ld")

march_of() {
  case "$1" in
    rv64ua) echo "-march=rv64g_zacas_zabha -mabi=lp64d" ;;
    *) echo "-march=rv64g -mabi=lp64d" ;;
  esac
}
supports() { "$GCC" $1 -c -x c /dev/null -o /dev/null >/dev/null 2>&1; }

# Test names come from each suite's Makefrag (<suite>_sc_tests).
tests_of() {
  sed -n "/^$1_sc_tests *=/,/^\$/p" "$SRC/isa/$1/Makefrag" |
    sed -e "s/^$1_sc_tests *=//" -e 's/\\//g' | tr -s ' \t' '\n' | sed '/^$/d'
}

rm -rf "$OUT"
mkdir -p "$OUT"
: > "$OUT/NOT_BUILT.txt"
built=0
for suite in "${SUITES[@]}"; do
  march="$(march_of "$suite")"
  if ! supports "$march"; then
    echo "note: $GCC does not support '$march'; using -march=rv64g -mabi=lp64d for $suite" >&2
    march="-march=rv64g -mabi=lp64d"
  fi
  for t in $(tests_of "$suite"); do
    elf="$OUT/$suite-p-$t"
    if "$GCC" $march "${OPTS[@]}" "$SRC/isa/$suite/$t.S" -o "$elf" 2>"$elf.err"; then
      rm -f "$elf.err"; built=$((built + 1))
    else
      echo "$suite-p-$t" >> "$OUT/NOT_BUILT.txt"
      echo "not built: $suite-p-$t ($(head -1 "$elf.err"))" >&2
      rm -f "$elf"
    fi
  done
done

# Every built program must expose tohost and start at 0x80000000.
if command -v "$READELF" >/dev/null 2>&1; then
  for elf in "$OUT"/rv64*-p-*; do
    [[ "$elf" == *.err ]] && continue
    syms="$("$READELF" -s "$elf")"
    grep -qw tohost <<<"$syms" || { echo "$elf has no tohost symbol" >&2; exit 1; }
    entry="$("$READELF" -h "$elf" | awk '/Entry point/ {print $4}')"
    [[ "$entry" == 0x80000000 ]] || { echo "$elf entry $entry, expected 0x80000000" >&2; exit 1; }
  done
fi
{
  echo "prefix=${RISCV_PREFIX:-riscv64-unknown-elf-}"
  "$GCC" --version | head -1
  echo "riscv-tests=$(git -C "$SRC" rev-parse HEAD 2>/dev/null || echo unknown)"
} > "$OUT/BUILD_INFO.txt"
echo "built $built programs into $OUT; not built: $(wc -l < "$OUT/NOT_BUILT.txt")"
