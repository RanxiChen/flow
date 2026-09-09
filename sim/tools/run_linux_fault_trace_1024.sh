#!/usr/bin/env bash
set -euo pipefail

cd /home/chen/FUN/flow
export JAVA_HOME=/home/chen/.sdkman/candidates/java/current
export SBT=/home/chen/.sdkman/candidates/sbt/current/bin/sbt
export LITEX_SIM_NO_TRACE=1

fault_trace_gateware=/home/chen/FUN/flow/build/linux-init-fault-trace-1024-novcd-3725ccc/gateware
mkdir -p "${fault_trace_gateware}"
if [[ -e "${fault_trace_gateware}/sim.vcd" || -L "${fault_trace_gateware}/sim.vcd" ]]; then
  rm -- "${fault_trace_gateware}/sim.vcd"
fi

exec /home/chen/miniforge3/envs/pure_litex/bin/python \
  sim/tools/rolling_debug_capture.py \
  --output build/linux-init-fault-trace-1024-novcd-3725ccc/runtime-heartbeat \
  --cwd /home/chen/FUN/flow \
  --cycle-lines 512 \
  --retire-lines 4096 \
  --bus-lines 2048 \
  --stall-cycles 5000000 \
  --flush-seconds 5 \
  -- \
  /home/chen/miniforge3/envs/pure_litex/bin/python sim/litex/linux_sim.py \
  --opensbi /home/chen/FUN/flow-linux-work/output-flow/images/fw_jump.bin \
  --kernel /home/chen/FUN/flow-linux-work/output-flow/images/Image \
  --dtb software/breeze-linux/build/flow-small.dtb \
  --bootrom software/breeze-linux/build/bootrom.bin \
  --profile small \
  --core-preset gshare \
  --output-dir build/linux-init-fault-trace-1024-novcd-3725ccc \
  --rtl-mode debug \
  --elaborate \
  --build \
  --non-interactive \
  --cycle-debug \
  --cycle-debug-interval 1000000 \
  --fault-retire-trace \
  --fault-retire-depth 1024 \
  --fault-retire-pc 0xffffffff92bffbfe
