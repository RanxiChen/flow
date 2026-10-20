#!/usr/bin/env bash
set -eu
source /home/chen/miniforge3/bin/activate flow
work=/home/chen/FUN/flow-mmu-extraction-20261019-f058e3b
logs=/home/chen/FUN/flow-runs/mmu-extraction-20261019-f058e3b
mkdir -p "$work" "$logs"
tar -xzf "$logs/source.tgz" -C "$work"
cp /home/chen/FUN/flow-runs/breeze-cleanup-20261019-90bd6f35/sbt-launch-1.9.7.jar "$logs/sbt-launch.jar"
cd "$work/design"
export MAKEFLAGS=-j4
set +e
java -Xmx10G -XX:ActiveProcessorCount=4 -Dsbt.supershell=false -jar "$logs/sbt-launch.jar" 'Compile / compile' 'Test / compile' > "$logs/compile.txt" 2>&1
rc=$?
printf '%s\n' "$rc" > "$logs/compile.exit"
tail -50 "$logs/compile.txt"
exit "$rc"
