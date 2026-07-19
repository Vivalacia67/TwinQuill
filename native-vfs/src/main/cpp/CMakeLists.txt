cmake_minimum_required(VERSION 3.22.1)
project(twinquill_native_vfs LANGUAGES CXX)

add_library(twinquill_native_vfs SHARED vfs_bridge.cpp)
target_compile_features(twinquill_native_vfs PRIVATE cxx_std_17)
target_compile_options(twinquill_native_vfs PRIVATE -fvisibility=hidden)
target_link_options(twinquill_native_vfs PRIVATE -Wl,-z,max-page-size=16384)
target_include_directories(
    twinquill_native_vfs
    PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)
target_link_libraries(twinquill_native_vfs PRIVATE log)
