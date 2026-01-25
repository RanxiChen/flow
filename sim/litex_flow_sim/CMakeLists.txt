cmake_minimum_required(VERSION 3.28)
project(litex_flow_sim)

set(CMAKE_CXX_STANDARD 20)


get_filename_component(
        FLOW_ROOT
        ${CMAKE_SOURCE_DIR}/../..
        REALPATH
)
set(RTL_DIR ${FLOW_ROOT}/generated)
message(STATUS "=== CMake Path ===")
message(STATUS "CMAKE_SOURCE_DIR  ${CMAKE_SOURCE_DIR}")
message(STATUS "FLOW_ROOT ${FLOW_ROOT}")
message(STATUS "RTL DIR ${RTL_DIR}")
find_package(verilator HINTS $ENV{VERILATOR_ROOT})
set(SV_FILELIST ${RTL_DIR}/filelist.f)

file(STRINGS ${SV_FILELIST} RTL_SOURCES)
list(TRANSFORM RTL_SOURCES PREPEND "${RTL_DIR}/" )
message(STATUS "RTL SOURCES:")
foreach (f ${RTL_SOURCES})
    message(STATUS "  ${f}")
endforeach ()
add_executable(litex_flow_sim main.cpp
        wbmem.cpp
        wbmem.h
        wbif.cpp
        wbif.h
        misc.h)
verilate(litex_flow_sim
        TOP_MODULE litex_flow_top
        PREFIX VLitexFlowTop
        SOURCES ${RTL_SOURCES}
        TRACE
)
