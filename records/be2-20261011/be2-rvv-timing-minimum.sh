#!/usr/bin/env bash
set -e
expected=${1:?exact source SHA required}
shift
source /home/chen/FUN/flow-runs/be2-env.sh
cd /home/chen/FUN/flow-be2-b3-8ea5db5
root=/home/chen/FUN/flow-runs
label=${expected:0:7}
trap 'rc=$?; echo "$rc" > "$root/be2-$label-rvv-system.exit"' EXIT
{
 test "$(git rev-parse HEAD)" = "$expected"
 git rev-parse HEAD
 printf 'HOST=%s CWD=%s\n' "$(hostname)" "$PWD"
 set -x
 mkdir -p design/build/cluster-tests
 for suite in rv64uf rv64ud; do
  for test in ldst fadd; do
   taskset -c 6-7 nice -n 10 riscv64-unknown-elf-gcc -march=rv64g -mabi=lp64d \
    -static -mcmodel=medany -fvisibility=hidden -nostdlib -nostartfiles -Wl,--build-id=none \
    -I third_party/riscv-tests/env/p -I third_party/riscv-tests/isa/macros/scalar \
    -T third_party/riscv-tests/env/p/link.ld third_party/riscv-tests/isa/$suite/$test.S \
    -o design/build/riscv-tests/$suite-p-$test
  done
 done
 taskset -c 6-7 nice -n 10 riscv64-unknown-elf-gcc -march=rv64gcv_zicsr -mabi=lp64d \
  -nostdlib -nostartfiles -Wl,--no-relax -T tests/cluster/link.ld \
  tests/cluster/rvv_memory.S -o design/build/cluster-tests/rvv_memory
 taskset -c 6-7 nice -n 10 riscv64-unknown-elf-gcc -march=rv64gcv_zicsr -mabi=lp64d \
  -DBE2_RVV_SV39 -nostdlib -nostartfiles -Wl,--no-relax -T tests/cluster/link.ld \
  tests/cluster/rvv_memory.S -o design/build/cluster-tests/rvv_memory_sv39
 cd design
 JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 taskset -c 6-7 nice -n 10 java -XX:ActiveProcessorCount=2 -Xmx6G \
  -jar "$root/be2-tools/sbt-launch-1.9.7.jar" 'set Test / parallelExecution := false' \
  'testOnly flow.rvv.RvvAxiArbiterSpec flow.rvv.RvvRegisterFileSpec flow.rvv.RvvAluCarrySpec' \
  'testOnly flow.cluster.RvvSystemSpec'
} > "$root/be2-$label-rvv-system.log" 2>&1
