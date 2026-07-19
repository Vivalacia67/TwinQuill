cmake_minimum_required(VERSION 3.22.1)
project(twinquill_engine_ons LANGUAGES C CXX)

set(TWINQUILL_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../..")
set(ONSYURI_DIR "${TWINQUILL_ROOT}/vendor/onscripter-yuri")
set(ONS_DEPS_DIR "${TWINQUILL_ROOT}/vendor/deps/ons")

set(SDL2_DIR "${ONS_DEPS_DIR}/SDL2-2.26.3")
set(SDL2IMAGE_DIR "${ONS_DEPS_DIR}/SDL2_image-2.6.3")
set(SDL2TTF_DIR "${ONS_DEPS_DIR}/SDL2_ttf-2.20.2")
set(SDL2MIXER_DIR "${ONS_DEPS_DIR}/SDL2_mixer-2.6.3")
set(LUA_DIR "${ONS_DEPS_DIR}/lua-5.4.4")
set(BZ2_DIR "${ONS_DEPS_DIR}/bzip2-1.0.8")

# Match the upstream Android build while keeping every output under Gradle's
# ignored CMake directory.
set(ANDROID TRUE)
set(CMAKE_SYSTEM_NAME Linux)

add_subdirectory("${SDL2_DIR}" "${CMAKE_BINARY_DIR}/third_party/sdl2")
target_compile_options(
    SDL2
    PRIVATE "-include${CMAKE_CURRENT_LIST_DIR}/ndk28_compat.h"
)
target_link_options(SDL2 PRIVATE "-Wl,-z,max-page-size=16384")

set(SDL2IMAGE_INSTALL OFF)
set(SDL2IMAGE_SAMPLES OFF)
add_subdirectory("${SDL2IMAGE_DIR}" "${CMAKE_BINARY_DIR}/third_party/sdl2_image")
target_link_options(SDL2_image PRIVATE "-Wl,-z,max-page-size=16384")

set(SDL2TTF_INSTALL OFF)
set(SDL2TTF_SAMPLES OFF)
set(SDL2TTF_VENDORED ON)
add_subdirectory("${SDL2TTF_DIR}" "${CMAKE_BINARY_DIR}/third_party/sdl2_ttf")
target_link_options(SDL2_ttf PRIVATE "-Wl,-z,max-page-size=16384")

set(SDL2MIXER_INSTALL OFF)
set(SDL2MIXER_SAMPLES OFF)
set(SDL2MIXER_OPUS OFF)
set(SDL2MIXER_FLAC OFF)
set(SDL2MIXER_MOD OFF)
set(SDL2MIXER_VORBIS_STB ON)
set(SDL2MIXER_VENDORED ON)
add_subdirectory("${SDL2MIXER_DIR}" "${CMAKE_BINARY_DIR}/third_party/sdl2_mixer")
target_link_options(SDL2_mixer PRIVATE "-Wl,-z,max-page-size=16384")

# SDL_image 2.6.3 uses its source-tree stb backend for JPEG and PNG in this
# configuration. ONS links the historical `jpeg` target even though its runtime
# sources do not call libjpeg directly, so retain a source-only interface target.
add_library(jpeg INTERFACE)

set(LUA_SRC_DIR "${LUA_DIR}/src")
set(LUA_SOURCES
    "${LUA_SRC_DIR}/lapi.c" "${LUA_SRC_DIR}/lcode.c" "${LUA_SRC_DIR}/lctype.c"
    "${LUA_SRC_DIR}/ldebug.c" "${LUA_SRC_DIR}/ldo.c" "${LUA_SRC_DIR}/ldump.c"
    "${LUA_SRC_DIR}/lfunc.c" "${LUA_SRC_DIR}/lgc.c" "${LUA_SRC_DIR}/llex.c"
    "${LUA_SRC_DIR}/lmem.c" "${LUA_SRC_DIR}/lobject.c" "${LUA_SRC_DIR}/lopcodes.c"
    "${LUA_SRC_DIR}/lparser.c" "${LUA_SRC_DIR}/lstate.c" "${LUA_SRC_DIR}/lstring.c"
    "${LUA_SRC_DIR}/ltable.c" "${LUA_SRC_DIR}/ltm.c" "${LUA_SRC_DIR}/lundump.c"
    "${LUA_SRC_DIR}/lvm.c" "${LUA_SRC_DIR}/lzio.c" "${LUA_SRC_DIR}/lauxlib.c"
    "${LUA_SRC_DIR}/lbaselib.c" "${LUA_SRC_DIR}/lcorolib.c" "${LUA_SRC_DIR}/ldblib.c"
    "${LUA_SRC_DIR}/liolib.c" "${LUA_SRC_DIR}/lmathlib.c" "${LUA_SRC_DIR}/loadlib.c"
    "${LUA_SRC_DIR}/loslib.c" "${LUA_SRC_DIR}/lstrlib.c" "${LUA_SRC_DIR}/ltablib.c"
    "${LUA_SRC_DIR}/lutf8lib.c" "${LUA_SRC_DIR}/linit.c"
)
add_library(twinquill_ons_lua SHARED ${LUA_SOURCES})
target_compile_options(twinquill_ons_lua PRIVATE -Wno-deprecated-declarations)
target_link_options(twinquill_ons_lua PRIVATE "-Wl,-z,max-page-size=16384")
add_library(lua ALIAS twinquill_ons_lua)

set(BZIP2_SOURCES
    "${BZ2_DIR}/blocksort.c" "${BZ2_DIR}/huffman.c" "${BZ2_DIR}/crctable.c"
    "${BZ2_DIR}/randtable.c" "${BZ2_DIR}/compress.c" "${BZ2_DIR}/decompress.c"
    "${BZ2_DIR}/bzlib.c"
)
add_library(twinquill_ons_bz2 SHARED ${BZIP2_SOURCES})
target_compile_options(twinquill_ons_bz2 PRIVATE -D_FILE_OFFSET_BITS=64)
target_link_options(twinquill_ons_bz2 PRIVATE "-Wl,-z,max-page-size=16384")
add_library(bz2 ALIAS twinquill_ons_bz2)

set(CMAKE_SYSTEM_NAME Android)
include_directories("${BZ2_DIR}" "${LUA_SRC_DIR}")
add_subdirectory("${ONSYURI_DIR}" "${CMAKE_BINARY_DIR}/onscripter-yuri")

include("${CMAKE_CURRENT_LIST_DIR}/ApplyOnsPatches.cmake")
twinquill_prepare_ons_patched_sources(
    "${ONSYURI_DIR}"
    "${CMAKE_CURRENT_BINARY_DIR}/patched/onscripter-yuri/src/onsyuri"
    TWINQUILL_ONS_COMMAND_SOURCE
    TWINQUILL_ONS_MAIN_SOURCE
)
get_target_property(TWINQUILL_ONS_SOURCES onsyuri SOURCES)
set(TWINQUILL_ONS_PATCHED_SOURCES)
foreach(SOURCE_PATH IN LISTS TWINQUILL_ONS_SOURCES)
    if(SOURCE_PATH MATCHES "ONScripter_command\\.cpp$")
        list(APPEND TWINQUILL_ONS_PATCHED_SOURCES
            "${TWINQUILL_ONS_COMMAND_SOURCE}"
        )
    elseif(SOURCE_PATH MATCHES "onscripter_main\\.cpp$")
        list(APPEND TWINQUILL_ONS_PATCHED_SOURCES
            "${TWINQUILL_ONS_MAIN_SOURCE}"
        )
    else()
        list(APPEND TWINQUILL_ONS_PATCHED_SOURCES "${SOURCE_PATH}")
    endif()
endforeach()
set_property(TARGET onsyuri PROPERTY SOURCES
    "${TWINQUILL_ONS_PATCHED_SOURCES}"
)
target_include_directories(onsyuri PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}"
    "${ONSYURI_DIR}/src/onsyuri"
)

# ONScripterYuri's JNI entry points use a configurable package prefix but its
# upstream Android package is embedded in the pinned snapshot's CMake file.
# Replace only that build definition; the vendor source remains byte-for-byte
# identical to the recorded upstream snapshot.
get_target_property(ONSYURI_COMPILE_DEFINITIONS onsyuri COMPILE_DEFINITIONS)
list(REMOVE_ITEM ONSYURI_COMPILE_DEFINITIONS
    "SDL_JAVA_PACKAGE_PATH=com_yuri_onscripter"
)
set_property(TARGET onsyuri PROPERTY
    COMPILE_DEFINITIONS "${ONSYURI_COMPILE_DEFINITIONS}"
)
target_compile_definitions(onsyuri PRIVATE
    SDL_JAVA_PACKAGE_PATH=io_github_twinquill_engine_ons
)

set_target_properties(
    onsyuri
    PROPERTIES
        OUTPUT_NAME twinquill_engine_ons
        CXX_STANDARD 17
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES
)
target_link_options(onsyuri PRIVATE "-Wl,-z,max-page-size=16384")
