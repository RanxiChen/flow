#!/usr/bin/env bash
set -Eeuo pipefail
source /home/chen/miniforge3/bin/activate flow
export CPATH=/home/chen/FUN/flow-soc2-deps-20261007/sysroot/usr/include:${CPATH:-}
export LIBRARY_PATH=/home/chen/FUN/flow-soc2-deps-20261007/sysroot/usr/lib/x86_64-linux-gnu:${LIBRARY_PATH:-}
export LD_LIBRARY_PATH=/home/chen/FUN/flow-soc2-deps-20261007/sysroot/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}
export http_proxy=http://127.0.0.1:18897 https_proxy=http://127.0.0.1:18897
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 MAKEFLAGS=-j4
work=/home/chen/FUN/flow-f2-owner-ce-20261012
evidence=/home/chen/FUN/flow-runs/f2-wholecore-v2-7820bf6-20261012/tests
mkdir -p "$evidence"
trap 'rc=$?; echo "$rc" > "$evidence/run.exit"; date -u +%FT%TZ > "$evidence/end-utc.txt"' EXIT
cd "$work"
test "$(git rev-parse HEAD)" = 7820bf6eb16eaef40337c97e0a59ac8369b35d79
test -z "$(git status --porcelain --untracked-files=no)"
git rev-parse HEAD > "$evidence/source-sha.txt"
git submodule status > "$evidence/submodules.txt"
hostname > "$evidence/host.txt"; pwd > "$evidence/source-cwd.txt"
date -u +%FT%TZ > "$evidence/start-utc.txt"
java -version > "$evidence/java.txt" 2>&1
verilator --version > "$evidence/verilator.txt"
git ls-files -z design/src design/build.sbt design/project config | xargs -0 sha256sum > "$evidence/source-sha256.txt"
cd design
command=(java -XX:ActiveProcessorCount=4 -Xmx6G -jar /home/chen/FUN/flow-runs/be2-tools/sbt-launch-1.9.7.jar
  'set Test / parallelExecution := false'
  'testOnly flow.frontend.* flow.memsys.L1IRegisteredReturnSpec flow.memsys.L1IClientSpec flow.util.RegisteredPairQueueSpec')
printf '%q ' "${command[@]}" > "$evidence/command.txt"
printf '\n' >> "$evidence/command.txt"
set +e
taskset -c 14-17 nice -n 10 "${command[@]}" 2>&1 | tee "$evidence/console.log"
rc=${PIPESTATUS[0]}
set -e
mkdir -p "$evidence/xml"
cp target/test-reports/TEST-flow.frontend.*.xml "$evidence/xml/"
cp target/test-reports/TEST-flow.memsys.L1IRegisteredReturnSpec.xml target/test-reports/TEST-flow.memsys.L1IClientSpec.xml target/test-reports/TEST-flow.util.RegisteredPairQueueSpec.xml "$evidence/xml/"
exit "$rc"
