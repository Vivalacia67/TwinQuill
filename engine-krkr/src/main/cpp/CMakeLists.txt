cmake_minimum_required(VERSION 3.22.1)
project(twinquill_engine_krkr LANGUAGES C CXX)

set(TWINQUILL_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../..")
set(KRKR2_CORE "${TWINQUILL_ROOT}/vendor/kirikiroid2/src/core")
set(TJS2_DIR "${KRKR2_CORE}/tjs2")
set(ONIG_DIR "${TWINQUILL_ROOT}/vendor/deps/krkr/oniguruma")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build Krkr dependencies statically" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory("${ONIG_DIR}" "${CMAKE_BINARY_DIR}/third_party/oniguruma")

# Apply the small, recorded libc++ compatibility patch to a generated copy.
# The source snapshot itself remains identical to the pinned upstream tree.
set(TJS2_BUILD_DIR "${CMAKE_BINARY_DIR}/generated/tjs2")
file(REMOVE_RECURSE "${TJS2_BUILD_DIR}")
file(MAKE_DIRECTORY "${TJS2_BUILD_DIR}")
file(COPY "${TJS2_DIR}/" DESTINATION "${TJS2_BUILD_DIR}")
file(READ "${TJS2_BUILD_DIR}/tjsUtils.h" TJS_UTILS_CONTENT)
string(REPLACE
    "int currentIndex = UnusedIndexStack.pop_back();"
    "int currentIndex = UnusedIndexStack.back();\n\t\tUnusedIndexStack.pop_back();"
    TJS_UTILS_PATCHED
    "${TJS_UTILS_CONTENT}"
)
if(TJS_UTILS_CONTENT STREQUAL TJS_UTILS_PATCHED)
    message(FATAL_ERROR "Kirikiroid2 tjsUtils compatibility patch did not apply")
endif()
file(WRITE "${TJS2_BUILD_DIR}/tjsUtils.h" "${TJS_UTILS_PATCHED}")

file(GLOB TJS2_SOURCES CONFIGURE_DEPENDS "${TJS2_BUILD_DIR}/*.cpp")
add_library(twinquill_krkr_tjs2 STATIC ${TJS2_SOURCES})
target_compile_features(twinquill_krkr_tjs2 PUBLIC cxx_std_17)
target_compile_definitions(twinquill_krkr_tjs2 PRIVATE
    TJS_TEXT_OUT_CRLF
    USE_UNICODE_FSTRING
    _LIBCPP_ENABLE_CXX17_REMOVED_UNARY_BINARY_FUNCTION
)
target_compile_options(twinquill_krkr_tjs2 PRIVATE
    -fexceptions
    -fsigned-char
)
target_include_directories(twinquill_krkr_tjs2 PUBLIC
    "${TJS2_BUILD_DIR}"
    "${KRKR2_CORE}/environ"
    "${KRKR2_CORE}/utils"
    "${ONIG_DIR}/src"
)
target_link_libraries(twinquill_krkr_tjs2 PUBLIC onig)

add_library(twinquill_engine_krkr SHARED
    krkr_jni.cpp
    krkr_tjs_probe.cpp
)
target_compile_features(twinquill_engine_krkr PRIVATE cxx_std_17)
target_compile_options(twinquill_engine_krkr PRIVATE -fvisibility=hidden)
target_link_libraries(twinquill_engine_krkr PRIVATE
    twinquill_krkr_tjs2
    android
    log
)
target_link_options(twinquill_engine_krkr PRIVATE -Wl,-z,max-page-size=16384)
