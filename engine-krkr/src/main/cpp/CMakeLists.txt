cmake_minimum_required(VERSION 3.22.1)
project(twinquill_engine_krkr LANGUAGES CXX)

add_library(twinquill_engine_krkr SHARED krkr_build_probe.cpp)
target_compile_features(twinquill_engine_krkr PRIVATE cxx_std_17)
target_compile_options(twinquill_engine_krkr PRIVATE -fvisibility=hidden)
target_link_options(twinquill_engine_krkr PRIVATE -Wl,-z,max-page-size=16384)
