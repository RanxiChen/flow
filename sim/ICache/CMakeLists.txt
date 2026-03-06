cmake_minimum_required(VERSION 3.28)
project(ICache)

set(CMAKE_CXX_STANDARD 20)

find_package(verilator HINTS $ENV{VERILATOR_ROOT})
add_executable(icache_sim
        tb_main.cpp
        memory_model.cpp
)

# Read RTL sources from filelist.f under rtl/
file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/rtl/filelist.f" RTL_FILELIST)
list(FILTER RTL_FILELIST EXCLUDE REGEX "^[ \t]*($|#|//)")
list(TRANSFORM RTL_FILELIST PREPEND "${CMAKE_CURRENT_SOURCE_DIR}/rtl/")

verilate(icache_sim
        TOP_MODULE ICache
        PREFIX VICache
        SOURCES ${RTL_FILELIST}
        TRACE
)
