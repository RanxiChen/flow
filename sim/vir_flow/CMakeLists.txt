cmake_minimum_required(VERSION 3.28)
project(vir_flow LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(verilator HINTS $ENV{VERILATOR_ROOT})

# Reuse shared memory model library from sibling project.
set(FLOW_VMEM_BUILD_SMOKE_TEST OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../flow_lib
                 ${CMAKE_CURRENT_BINARY_DIR}/flow_lib)

add_executable(vir_flow_sim
    tb_main.cpp
)
target_link_libraries(vir_flow_sim PRIVATE flow::vmem)
target_compile_definitions(vir_flow_sim PRIVATE
    VIR_FLOW_SOURCE_DIR="${CMAKE_CURRENT_SOURCE_DIR}"
)

# Read RTL sources from rtl/filelist.f and prepend absolute path.
file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/rtl/filelist.f" RTL_FILELIST)
list(FILTER RTL_FILELIST EXCLUDE REGEX "^[ \t]*($|#|//)")
list(TRANSFORM RTL_FILELIST PREPEND "${CMAKE_CURRENT_SOURCE_DIR}/rtl/")

verilate(vir_flow_sim
    TOP_MODULE VirFlow
    PREFIX VVirFlow
    SOURCES ${RTL_FILELIST}
    TRACE
)
