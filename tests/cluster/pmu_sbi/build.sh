#!/usr/bin/env bash
set -eu
repo=$(cd -- "$(dirname -- "$0")/../../.." && pwd)
: "${OPENSBI_SOURCE:?Set OPENSBI_SOURCE to the unmodified OpenSBI 1.9 source tree}"
out=${PMU_SBI_OUT:-$repo/tests/cluster/build/pmu-sbi}
# OpenSBI 1.9 requires a PIE-capable linker; the bare-metal ELF linker may
# not support it. The payload itself still links without libc.
prefix=${RISCV_PREFIX:-riscv64-linux-gnu-}
mkdir -p "$out"
python3 - "$OPENSBI_SOURCE/include/sbi/sbi_ecall_interface.h" "$out/sbi-pmu-abi.h" <<'PY'
from pathlib import Path
import re,sys
names={'SBI_EXT_PMU','SBI_EXT_PMU_COUNTER_CFG_MATCH','SBI_EXT_PMU_COUNTER_GET_INFO',
       'SBI_EXT_PMU_COUNTER_START','SBI_EXT_PMU_COUNTER_STOP','SBI_PMU_CFG_FLAG_CLEAR_VALUE',
       'SBI_PMU_START_FLAG_SET_INIT_VALUE','SBI_PMU_STOP_FLAG_RESET'}
lines=[]
for line in Path(sys.argv[1]).read_text().splitlines():
    m=re.match(r'#define\s+(\w+)\s+',line)
    if m and m[1] in names: lines.append(line)
assert len(lines)==len(names), 'incomplete OpenSBI PMU ABI'
Path(sys.argv[2]).write_text('/* Generated from OpenSBI sbi_ecall_interface.h (BSD-2-Clause). */\n'+'\n'.join(lines)+'\n')
PY
"${prefix}gcc" -march=rv64imac_zicsr_zifencei -mabi=lp64 -mcmodel=medany \
  -O2 -g -ffreestanding -fno-builtin -msmall-data-limit=0 -fno-pie -no-pie \
  -nostdlib -nostartfiles -Wl,--build-id=none -Wl,--no-relax \
  -I "$repo/software/breeze-mcu/include" -I "$out" -T "$repo/tests/cluster/pmu_sbi/link.ld" \
  "$repo/tests/cluster/pmu_sbi/start.S" "$repo/tests/cluster/pmu_sbi/main.c" -o "$out/payload.elf"
"${prefix}objcopy" -O binary "$out/payload.elf" "$out/payload.bin"
dtc -I dts -O dtb -i "$repo/linux/buildroot-external/board/flow/dts/flow" \
  "$repo/linux/buildroot-external/board/flow/dts/flow/flow-kcu105-tiny-perf.dts" -o "$out/perf.dtb"
make -C "$OPENSBI_SOURCE" O="$out/opensbi" -j4 PLATFORM=generic \
  CROSS_COMPILE="$prefix" FW_TEXT_START=0x80000000 FW_PAYLOAD=y FW_DYNAMIC=n FW_JUMP=n \
  FW_PAYLOAD_OFFSET=0x200000 FW_FDT_PATH="$out/perf.dtb" FW_OPTIONS=1 \
  FW_PAYLOAD_PATH="$out/payload.bin"
"${prefix}objcopy" --add-symbol tohost=0x803ff000,global \
  "$out/opensbi/platform/generic/firmware/fw_payload.elf" "$out/pmu_sbi.elf"
sha256sum "$out/payload.elf" "$out/payload.bin" "$out/perf.dtb" "$out/pmu_sbi.elf"
