#!/usr/bin/env bash
set -eu
source /home/chen/miniforge3/bin/activate flow
work=/home/chen/FUN/flow-mmu-extraction-20261019-f058e3b
logs=/home/chen/FUN/flow-runs/mmu-extraction-20261019-f058e3b
cd "$work"
exec > "$logs/system.txt" 2>&1
trap 'rc=$?; printf "%s\n" "$rc" > "$logs/system.exit"' EXIT
mkdir -p design/build/riscv-tests design/build/cluster-tests
riscv64-unknown-elf-gcc --version
for name in rv64ui-p-add rv64ui-p-lw rv64ui-p-sw rv64ui-p-beq rv64ui-p-ld rv64ui-p-sd rv64ui-p-sb rv64ui-p-lbu \
            rv64um-p-mul rv64um-p-div rv64ua-p-amoadd_d rv64ua-p-lrsc \
            rv64uc-p-rvc rv64mi-p-csr rv64si-p-csr rv64uf-p-ldst rv64uf-p-fadd rv64ud-p-ldst rv64ud-p-fadd rv64ud-p-fmadd; do
  suite=${name%%-p-*}; testname=${name#*-p-}
  riscv64-unknown-elf-gcc -march=rv64g -mabi=lp64d -static -mcmodel=medany -fvisibility=hidden \
    -nostdlib -nostartfiles -Wl,--build-id=none \
    -I third_party/riscv-tests/env/p -I third_party/riscv-tests/isa/macros/scalar \
    -T third_party/riscv-tests/env/p/link.ld "third_party/riscv-tests/isa/$suite/$testname.S" \
    -o "design/build/riscv-tests/$name"
done
make -C tests/cluster -j4
make -C tests/cluster build/zve_smoke.elf
for name in rvv_memory rvv_memory_sv39 rvv_arithmetic; do
  source=${name%_sv39}; options=()
  if [[ "$name" == rvv_memory_sv39 ]]; then options+=(-DBE2_RVV_SV39); fi
  riscv64-unknown-elf-gcc -march=rv64gcv_zicsr -mabi=lp64d "${options[@]}" \
    -nostdlib -nostartfiles -Wl,--no-relax -T tests/cluster/link.ld \
    "tests/cluster/$source.S" -o "design/build/cluster-tests/$name"
done
sha256sum design/build/riscv-tests/rv64*-p-* design/build/cluster-tests/rvv_* tests/cluster/build/*.elf > "$logs/elf-sha256.txt"
cd design
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4
export MAKEFLAGS=-j4
java -Xmx10G -XX:ActiveProcessorCount=4 -Dsbt.supershell=false -jar "$logs/sbt-launch.jar" \
  'set Test / parallelExecution := false' \
  'testOnly flow.cluster.ClusterProgramSpec flow.cluster.ZveSystemSmokeSpec flow.cluster.RvvSystemSpec'
