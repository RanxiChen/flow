#!/bin/bash
set -e
source /home/chen/miniforge3/bin/activate flow
export PATH=/home/chen/Tool/FPGA/Vivado/2022.2/bin:$PATH
export CPATH=/home/chen/FUN/flow-soc2-deps-20261007/sysroot/usr/include:${CPATH:-}
export LIBRARY_PATH=/home/chen/FUN/flow-soc2-deps-20261007/sysroot/usr/lib/x86_64-linux-gnu:${LIBRARY_PATH:-}
export LD_LIBRARY_PATH=/home/chen/FUN/flow-soc2-deps-20261007/sysroot/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}
export http_proxy=http://127.0.0.1:18897
export https_proxy=http://127.0.0.1:18897
export MAKEFLAGS=-j4
exec "$@"
