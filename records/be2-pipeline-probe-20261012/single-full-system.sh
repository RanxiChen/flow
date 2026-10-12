#!/usr/bin/env bash
# Fresh host preflight is required before this compilation/simulation batch.
set -euo pipefail
expected=${1:?exact source SHA}
work=${2:?isolated source worktree}
evidence=${3:?fresh evidence directory}
test ! -e "$evidence"
mkdir -p "$evidence"
trap 'rc=$?; printf "%s\n" "$rc" > "$evidence/run.exit"' EXIT
cd "$work"
test "$(git rev-parse HEAD)" = "$expected"
test -z "$(git status --porcelain --untracked-files=no)"
git rev-parse HEAD > "$evidence/source-sha.txt"
hostname > "$evidence/host.txt"
pwd > "$evidence/source-cwd.txt"
date -u +%FT%TZ > "$evidence/start-utc.txt"
java -version > "$evidence/java.txt" 2>&1
verilator --version > "$evidence/verilator.txt"
riscv64-unknown-elf-gcc --version > "$evidence/compiler.txt"
git submodule status --recursive > "$evidence/submodules.txt"
test -f third_party/riscv-tests/env/p/link.ld
mkdir -p design/build/riscv-tests design/build/cluster-tests
for name in rv64ui-p-add rv64ui-p-lw rv64ui-p-sw rv64ui-p-beq \
            rv64um-p-mul rv64um-p-div rv64ua-p-amoadd_d rv64ua-p-lrsc \
            rv64uc-p-rvc rv64mi-p-csr rv64uf-p-ldst rv64uf-p-fadd rv64ud-p-ldst rv64ud-p-fadd; do
  suite=${name%%-p-*}; testname=${name#*-p-}
  taskset -c 6-7 nice -n 10 riscv64-unknown-elf-gcc -march=rv64g -mabi=lp64d \
    -static -mcmodel=medany -fvisibility=hidden -nostdlib -nostartfiles -Wl,--build-id=none \
    -I third_party/riscv-tests/env/p -I third_party/riscv-tests/isa/macros/scalar \
    -T third_party/riscv-tests/env/p/link.ld "third_party/riscv-tests/isa/$suite/$testname.S" \
    -o "design/build/riscv-tests/$name"
done
taskset -c 6-7 nice -n 10 make -C tests/cluster -j2
sha256sum design/build/riscv-tests/rv64*-p-* tests/cluster/build/*.elf > "$evidence/elf-sha256.txt"
bash records/be2-pipeline-probe-20261012/run.sh "$expected" "$work" "$evidence/throughput"
cp design/target/test-reports/TEST-flow.backend.BackendPipelineThroughputSpec.xml "$evidence/throughput/"
cp design/target/test-reports/TEST-flow.cluster.ClusterPipelineThroughputSpec.xml "$evidence/throughput/"
cd design
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4
export MAKEFLAGS=-j4
taskset -c 6-9 nice -n 10 java -XX:ActiveProcessorCount=4 -Xmx6G \
  -jar /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar \
  'set Test / parallelExecution := false' \
  'testOnly flow.backend.BackendSoc3cSpec' \
  'testOnly flow.cluster.BE2MinimumSpec -- -z "(single)"' \
  'testOnly flow.cluster.ClusterProgramSpec -- -z "single-hart"'
for suite in flow.backend.BackendSoc3cSpec flow.cluster.BE2MinimumSpec flow.cluster.ClusterProgramSpec; do
  cp "target/test-reports/TEST-$suite.xml" "$evidence/"
done
