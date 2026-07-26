cmake_minimum_required(VERSION 3.22.1)
project(twinquill_engine_krkr LANGUAGES C CXX)

set(TWINQUILL_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../..")
set(KRKR2_CORE "${TWINQUILL_ROOT}/vendor/kirikiroid2/src/core")
set(TJS2_DIR "${KRKR2_CORE}/tjs2")
set(KAG_DIR "${KRKR2_CORE}/utils")
set(ONIG_DIR "${TWINQUILL_ROOT}/vendor/deps/krkr/oniguruma")
set(ZLIB_DIR "${TWINQUILL_ROOT}/vendor/deps/krkr/zlib")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build Krkr dependencies statically" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory("${ONIG_DIR}" "${CMAKE_BINARY_DIR}/third_party/oniguruma")

add_library(twinquill_krkr_zlib STATIC
    "${ZLIB_DIR}/adler32.c"
    "${ZLIB_DIR}/compress.c"
    "${ZLIB_DIR}/crc32.c"
    "${ZLIB_DIR}/deflate.c"
    "${ZLIB_DIR}/infback.c"
    "${ZLIB_DIR}/inffast.c"
    "${ZLIB_DIR}/inflate.c"
    "${ZLIB_DIR}/inftrees.c"
    "${ZLIB_DIR}/trees.c"
    "${ZLIB_DIR}/uncompr.c"
    "${ZLIB_DIR}/zutil.c"
)
set_target_properties(twinquill_krkr_zlib PROPERTIES
    C_VISIBILITY_PRESET hidden
    POSITION_INDEPENDENT_CODE ON
)
target_compile_options(twinquill_krkr_zlib PRIVATE -fvisibility=hidden)
target_include_directories(twinquill_krkr_zlib PUBLIC "${ZLIB_DIR}")

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

function(twinquill_required_replace input pattern replacement output label)
    string(REPLACE "${pattern}" "${replacement}" patched "${input}")
    if("${input}" STREQUAL "${patched}")
        message(FATAL_ERROR "Kirikiroid2 ${label} patch did not apply")
    endif()
    set(${output} "${patched}" PARENT_SCOPE)
endfunction()

set(KAG_PARSER_BUILD_DIR "${CMAKE_BINARY_DIR}/generated/kag")
file(REMOVE_RECURSE "${KAG_PARSER_BUILD_DIR}")
file(MAKE_DIRECTORY "${KAG_PARSER_BUILD_DIR}")
file(COPY
    "${KAG_DIR}/KAGParser.h"
    "${KAG_DIR}/KAGParser.cpp"
    DESTINATION "${KAG_PARSER_BUILD_DIR}"
)
file(READ "${KAG_PARSER_BUILD_DIR}/KAGParser.h" KAG_PARSER_HEADER_CONTENT)
twinquill_required_replace(
    "${KAG_PARSER_HEADER_CONTENT}"
    "#include \"StorageImpl.h\""
    "#include \"StorageIntf.h\""
    KAG_PARSER_HEADER_PATCHED
    "KAGParser StorageIntf"
)
twinquill_required_replace(
    "${KAG_PARSER_HEADER_PATCHED}"
    "#include \"MsgIntf.h\""
    "#include \"krkr_kag_platform.h\""
    KAG_PARSER_HEADER_PATCHED
    "KAGParser message shim"
)
twinquill_required_replace(
    "${KAG_PARSER_HEADER_PATCHED}"
    "#include \"PluginImpl.h\""
    ""
    KAG_PARSER_HEADER_PATCHED
    "KAGParser plugin include removal"
)
twinquill_required_replace(
    "${KAG_PARSER_HEADER_PATCHED}"
    "#include \"CharacterSet.h\""
    ""
    KAG_PARSER_HEADER_PATCHED
    "KAGParser charset include removal"
)
twinquill_required_replace(
    "${KAG_PARSER_HEADER_PATCHED}"
    "#include \"TransIntf.h\""
    ""
    KAG_PARSER_HEADER_PATCHED
    "KAGParser transition include removal"
)
file(WRITE "${KAG_PARSER_BUILD_DIR}/KAGParser.h" "${KAG_PARSER_HEADER_PATCHED}")
file(READ "${KAG_PARSER_BUILD_DIR}/KAGParser.cpp" KAG_PARSER_SOURCE_CONTENT)
twinquill_required_replace(
    "${KAG_PARSER_SOURCE_CONTENT}"
    "#include \"EventIntf.h\""
    "#include \"krkr_kag_platform.h\""
    KAG_PARSER_SOURCE_PATCHED
    "KAGParser compact event shim"
)
file(WRITE "${KAG_PARSER_BUILD_DIR}/KAGParser.cpp" "${KAG_PARSER_SOURCE_PATCHED}")

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
    "${KRKR2_CORE}/base"
    "${KRKR2_CORE}/environ"
    "${KRKR2_CORE}/utils"
    "${ONIG_DIR}/src"
)
target_link_libraries(twinquill_krkr_tjs2 PUBLIC onig)

add_library(twinquill_engine_krkr SHARED
    krkr_jni.cpp
    krkr_kag_platform.cpp
    krkr_kag_probe.cpp
    krkr_kag_runtime.cpp
    krkr_storage.cpp
    krkr_storage_registry.cpp
    krkr_tjs_probe.cpp
    krkr_xp3_archive.cpp
    krkr_xp3.cpp
    "${KAG_PARSER_BUILD_DIR}/KAGParser.cpp"
)
target_compile_features(twinquill_engine_krkr PRIVATE cxx_std_17)
target_compile_definitions(twinquill_engine_krkr PRIVATE
    _LIBCPP_ENABLE_CXX17_REMOVED_UNARY_BINARY_FUNCTION
)
target_compile_options(twinquill_engine_krkr PRIVATE
    -fexceptions
    -fvisibility=hidden
)
target_include_directories(twinquill_engine_krkr PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}"
    "${KAG_PARSER_BUILD_DIR}"
    "${TJS2_BUILD_DIR}"
    "${KRKR2_CORE}/base"
    "${KRKR2_CORE}/environ"
    "${KRKR2_CORE}/utils"
    "${ONIG_DIR}/src"
)
target_link_libraries(twinquill_engine_krkr PRIVATE
    twinquill_krkr_tjs2
    twinquill_krkr_zlib
    android
    log
)
target_link_options(twinquill_engine_krkr PRIVATE -Wl,-z,max-page-size=16384)