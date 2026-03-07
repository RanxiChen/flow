cmake_minimum_required(VERSION 3.20)
project(flow_lib LANGUAGES CXX)

add_library(flow_vmem STATIC
    src/virtual_memory_model.cpp
)

add_library(flow::vmem ALIAS flow_vmem)

target_include_directories(flow_vmem
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_features(flow_vmem PUBLIC cxx_std_20)

option(FLOW_VMEM_BUILD_SMOKE_TEST "Build a tiny smoke test for flow_vmem" ON)
if(FLOW_VMEM_BUILD_SMOKE_TEST)
    add_executable(flow_vmem_smoke
        tests/vmem_smoke.cpp
    )
    target_link_libraries(flow_vmem_smoke PRIVATE flow::vmem)

    add_executable(flow_vmem_layout_smoke
        tests/vmem_layout_smoke.cpp
    )
    target_link_libraries(flow_vmem_layout_smoke PRIVATE flow::vmem)
endif()
