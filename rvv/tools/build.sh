#!/usr/bin/env bash
set -euo pipefail
set -x
R01_RUN=$(realpath "$1")
R01_ROOT=$(cd "$(dirname "$0")/.." && pwd)
R01_GCC=${R01_GCC:-$HOME/Tool/RISCV/bin/riscv64-unknown-linux-gnu-gcc}
R01_GXX=${R01_GXX:-$HOME/Tool/RISCV/bin/riscv64-unknown-linux-gnu-g++}
for mode in native rvv scalar; do
    args=()
    if [[ $mode != native ]]; then
        args+=(-DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=riscv64
               -DCMAKE_C_COMPILER="$R01_GCC" -DCMAKE_CXX_COMPILER="$R01_GXX"
               -DCMAKE_EXE_LINKER_FLAGS=-static)
        if [[ $mode == rvv ]]; then
            args+=(-DGGML_RVV=ON -DCMAKE_C_FLAGS=-march=rv64gcv -DCMAKE_CXX_FLAGS=-march=rv64gcv)
        else
            args+=(-DGGML_RVV=OFF -DCMAKE_C_FLAGS=-march=rv64gc -DCMAKE_CXX_FLAGS=-march=rv64gc)
        fi
    fi
    nice -n 10 cmake -S "$R01_ROOT/runner" -B "$R01_RUN/build/$mode" -G Ninja \
        -DR01_LLAMA_SOURCE="$R01_RUN/third_party/llama.cpp" -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_BLAS=OFF \
        -DGGML_RV_ZFH=OFF -DGGML_RV_ZVFH=OFF -DGGML_RV_ZICBOP=OFF -DGGML_RV_ZIHINTPAUSE=OFF \
        -DLLAMA_BUILD_COMMON=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
        -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_APP=OFF "${args[@]}"
    nice -n 10 cmake --build "$R01_RUN/build/$mode" --target r01-runner -j 4
done
