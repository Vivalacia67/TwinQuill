cmake_minimum_required(VERSION 3.22.1)
project(twinquill_engine_ons LANGUAGES CXX)

add_library(twinquill_engine_ons SHARED ons_build_probe.cpp)
target_compile_features(twinquill_engine_ons PRIVATE cxx_std_17)
target_compile_options(twinquill_engine_ons PRIVATE -fvisibility=hidden)
target_link_options(twinquill_engine_ons PRIVATE -Wl,-z,max-page-size=16384)
