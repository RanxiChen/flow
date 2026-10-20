#!/usr/bin/env bash
set -eu
source /home/chen/miniforge3/bin/activate flow
work=/home/chen/FUN/flow-mmu-extraction-20261019-f058e3b
logs=/home/chen/FUN/flow-runs/mmu-extraction-20261019-f058e3b
tar -xzf "$logs/supplement.tgz" -C "$work"
cd "$work/design"
export MAKEFLAGS=-j4
export JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4
set +e
java -Xmx10G -XX:ActiveProcessorCount=4 -Dsbt.supershell=false -jar "$logs/sbt-launch.jar" 'set Test / parallelExecution := false' 'testOnly flow.top.MmuExtractionElabSpec flow.frontend.BreezeFrontendPipelineSpec flow.frontend.BreezeResolutionReservationSpec flow.memsys.L1IRegisteredReturnSpec flow.mmu.sv39.Sv39MmuSpec flow.mmu.sv39.Sv39PmpCandidateSpec' > "$logs/focused.txt" 2>&1
rc=$?
printf '%s\n' "$rc" > "$logs/focused.exit"
tail -70 "$logs/focused.txt"
exit "$rc"
