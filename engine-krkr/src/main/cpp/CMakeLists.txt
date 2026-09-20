cmake_minimum_required(VERSION 3.22.1)
project(twinquill_engine_krkr LANGUAGES C CXX)
find_package(Git 2.30 REQUIRED)
find_package(Python3 COMPONENTS Interpreter REQUIRED)
if(NOT CMAKE_READELF OR NOT EXISTS "${CMAKE_READELF}")
    message(FATAL_ERROR
        "CMAKE_READELF must name the NDK llvm-readelf executable for the "
        "post-build Cocos link-boundary gate")
endif()

# NDK 28's Android toolchain injects -latomic into the normal C++ standard
# library list.  The admitted Cocos/dependency closure has no __atomic*
# references on either ABI, and the link boundary permits no unproven library;
# remove only that exact token while retaining all other toolchain defaults
# (notably -lm).  C standard libraries are intentionally untouched.  This
# regex preserves every byte outside the token, including quoted arguments and
# their spacing; list-based tokenization would re-emit unrelated bytes.
string(REGEX REPLACE
    "(^|[ \t])-latomic([ \t]|$)"
    "\\1\\2"
    CMAKE_CXX_STANDARD_LIBRARIES
    "${CMAKE_CXX_STANDARD_LIBRARIES}")

set(TWINQUILL_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../..")
set(KRKR2_CORE "${TWINQUILL_ROOT}/vendor/kirikiroid2/src/core")
set(TJS2_DIR "${KRKR2_CORE}/tjs2")
set(ONIG_DIR "${TWINQUILL_ROOT}/vendor/deps/krkr/oniguruma")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build Krkr dependencies statically" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory("${ONIG_DIR}" "${CMAKE_BINARY_DIR}/third_party/oniguruma")

# Build the four source-pinned image/text dependencies before the explicit
# Cocos core target consumes them.  This file never imports the upstream
# aggregate or a prebuilt archive.
include(krkr_dependencies.cmake)

# Consume native-vfs through the AGP-generated Prefab package. The package
# exports only its public C ABI and shared library; no VFS sources are copied
# into this module.
find_package(native-vfs CONFIG REQUIRED)

# Apply the recorded libc++ compatibility patch to a generated copy.
# The source snapshot itself remains identical to the pinned upstream tree.
set(KIRIKIROID2_BUILD_DIR "${CMAKE_BINARY_DIR}/generated/kirikiroid2")
set(TJS2_BUILD_DIR "${KIRIKIROID2_BUILD_DIR}/src/core/tjs2")
set(TJS2_PATCH
    "${TWINQUILL_ROOT}/vendor/patches/kirikiroid2/0001-fix-tjs-vector-pop-back.patch")
file(REMOVE_RECURSE "${KIRIKIROID2_BUILD_DIR}")
file(MAKE_DIRECTORY "${TJS2_BUILD_DIR}")
file(COPY "${TJS2_DIR}/" DESTINATION "${TJS2_BUILD_DIR}")
file(RELATIVE_PATH KIRIKIROID2_BUILD_RELATIVE_DIR
    "${TWINQUILL_ROOT}" "${KIRIKIROID2_BUILD_DIR}")
if(NOT EXISTS "${TJS2_PATCH}")
    message(FATAL_ERROR "Missing Kirikiroid2 TJS2 patch: ${TJS2_PATCH}")
endif()
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${TJS2_PATCH}")
execute_process(
    COMMAND "${GIT_EXECUTABLE}" -c core.fsmonitor=false apply --check
        --directory=${KIRIKIROID2_BUILD_RELATIVE_DIR}
        "${TJS2_PATCH}"
    WORKING_DIRECTORY "${TWINQUILL_ROOT}"
    RESULT_VARIABLE TJS2_PATCH_CHECK
    OUTPUT_VARIABLE TJS2_PATCH_CHECK_STDOUT
    ERROR_VARIABLE TJS2_PATCH_CHECK_STDERR
)
if(NOT TJS2_PATCH_CHECK EQUAL 0)
    message(FATAL_ERROR
        "Kirikiroid2 TJS2 patch check failed: ${TJS2_PATCH_CHECK_STDERR}")
endif()
execute_process(
    COMMAND "${GIT_EXECUTABLE}" -c core.fsmonitor=false apply
        --directory=${KIRIKIROID2_BUILD_RELATIVE_DIR}
        "${TJS2_PATCH}"
    WORKING_DIRECTORY "${TWINQUILL_ROOT}"
    RESULT_VARIABLE TJS2_PATCH_APPLY
    OUTPUT_VARIABLE TJS2_PATCH_APPLY_STDOUT
    ERROR_VARIABLE TJS2_PATCH_APPLY_STDERR
)
if(NOT TJS2_PATCH_APPLY EQUAL 0)
    message(FATAL_ERROR
        "Kirikiroid2 TJS2 patch application failed: ${TJS2_PATCH_APPLY_STDERR}")
endif()

set(TJS2_SOURCES
    "${TJS2_BUILD_DIR}/tjs.cpp"
    "${TJS2_BUILD_DIR}/tjs.tab.cpp"
    "${TJS2_BUILD_DIR}/tjsArray.cpp"
    "${TJS2_BUILD_DIR}/tjsBinarySerializer.cpp"
    "${TJS2_BUILD_DIR}/tjsByteCodeLoader.cpp"
    "${TJS2_BUILD_DIR}/tjsCompileControl.cpp"
    "${TJS2_BUILD_DIR}/tjsConfig.cpp"
    "${TJS2_BUILD_DIR}/tjsConstArrayData.cpp"
    "${TJS2_BUILD_DIR}/tjsDate.cpp"
    "${TJS2_BUILD_DIR}/tjsdate.tab.cpp"
    "${TJS2_BUILD_DIR}/tjsDateParser.cpp"
    "${TJS2_BUILD_DIR}/tjsDebug.cpp"
    "${TJS2_BUILD_DIR}/tjsDictionary.cpp"
    "${TJS2_BUILD_DIR}/tjsDisassemble.cpp"
    "${TJS2_BUILD_DIR}/tjsError.cpp"
    "${TJS2_BUILD_DIR}/tjsException.cpp"
    "${TJS2_BUILD_DIR}/tjsGlobalStringMap.cpp"
    "${TJS2_BUILD_DIR}/tjsInterCodeExec.cpp"
    "${TJS2_BUILD_DIR}/tjsInterCodeGen.cpp"
    "${TJS2_BUILD_DIR}/tjsInterface.cpp"
    "${TJS2_BUILD_DIR}/tjsLex.cpp"
    "${TJS2_BUILD_DIR}/tjsMT19937ar-cok.cpp"
    "${TJS2_BUILD_DIR}/tjsMath.cpp"
    "${TJS2_BUILD_DIR}/tjsMessage.cpp"
    "${TJS2_BUILD_DIR}/tjsNamespace.cpp"
    "${TJS2_BUILD_DIR}/tjsNative.cpp"
    "${TJS2_BUILD_DIR}/tjsObject.cpp"
    "${TJS2_BUILD_DIR}/tjsObjectExtendable.cpp"
    "${TJS2_BUILD_DIR}/tjsOctPack.cpp"
    "${TJS2_BUILD_DIR}/tjspp.tab.cpp"
    "${TJS2_BUILD_DIR}/tjsRandomGenerator.cpp"
    "${TJS2_BUILD_DIR}/tjsRegExp.cpp"
    "${TJS2_BUILD_DIR}/tjsScriptBlock.cpp"
    "${TJS2_BUILD_DIR}/tjsScriptCache.cpp"
    "${TJS2_BUILD_DIR}/tjsString.cpp"
    "${TJS2_BUILD_DIR}/tjsUtils.cpp"
    "${TJS2_BUILD_DIR}/tjsVariant.cpp"
    "${TJS2_BUILD_DIR}/tjsVariantString.cpp"
)
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
    "${KRKR2_CORE}/base"
    "${ONIG_DIR}/src"
)
target_link_libraries(twinquill_krkr_tjs2 PUBLIC onig)

add_library(twinquill_engine_krkr SHARED
    krkr_jni.cpp
    krkr_tjs_probe.cpp
    krkr_xp3.cpp
    krkr_vfs_storage.cpp
)
target_compile_features(twinquill_engine_krkr PRIVATE cxx_std_17)
target_compile_options(twinquill_engine_krkr PRIVATE -fvisibility=hidden)
target_link_libraries(twinquill_engine_krkr PRIVATE
    twinquill_krkr_tjs2
    native-vfs::twinquill_native_vfs
    android
    log
)
target_link_options(twinquill_engine_krkr PRIVATE -Wl,-z,max-page-size=16384)

include(krkr_cocos_core.cmake)

# Every engine link runs the dynamic boundary checker against the exact
# generated Ninja closure and final shared object.  The executable path and
# readelf tool are explicit so an ambient host tool cannot silently bypass it.
add_custom_command(TARGET twinquill_engine_krkr POST_BUILD
    COMMAND "${Python3_EXECUTABLE}"
        "${TWINQUILL_ROOT}/scripts/check_krkr_cocos_link_boundary.py"
        --build-dir "${CMAKE_BINARY_DIR}"
        --final-so "$<TARGET_FILE:twinquill_engine_krkr>"
        --llvm-readelf "${CMAKE_READELF}"
    COMMENT "Checking Krkr Cocos link boundary"
    VERBATIM
)

add_dependencies(twinquill_engine_krkr
    twinquill_krkr_zlib
    twinquill_krkr_libpng
    twinquill_krkr_freetype
    twinquill_krkr_libjpeg
)
