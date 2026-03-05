cmake_minimum_required(VERSION 3.28)
project(ICache)

set(CMAKE_CXX_STANDARD 20)

find_package(verilator HINTS $ENV{VERILATOR_ROOT})
add_executable(icache_sim
        tb_main.cpp
        memory_model.cpp
)
verilate(icache_sim
        TOP_MODULE ICache
        PREFIX VICache
        SOURCES rtl/ICache.sv
        TRACE
)
