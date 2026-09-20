# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors
# Source-pinned modern dependency snapshots for the Krkr engine.
#
# These targets remain private static archives, but are linked transitively
# through twinquill_krkr_cocos_core into twinquill_engine_krkr.  The explicit
# target edges below therefore participate in the final unresolved-symbol
# closure.

set(KRKR_DEPS_ROOT "${TWINQUILL_ROOT}/vendor/deps/krkr")
set(KRKR_ZLIB_DIR "${KRKR_DEPS_ROOT}/zlib-1.3.2")
set(KRKR_LIBPNG_DIR "${KRKR_DEPS_ROOT}/libpng-1.6.58")
set(KRKR_FREETYPE_DIR "${KRKR_DEPS_ROOT}/freetype-2.14.3")
set(KRKR_LIBJPEG_DIR "${KRKR_DEPS_ROOT}/libjpeg-turbo-3.1.4.1")

# zlib is intentionally listed explicitly so no host/system package lookup is
# possible.  The source tree's zconf.h is used as-is for this static archive,
# which is linked transitively by the Cocos core.
set(KRKR_ZLIB_SOURCES
    "${KRKR_ZLIB_DIR}/adler32.c"
    "${KRKR_ZLIB_DIR}/compress.c"
    "${KRKR_ZLIB_DIR}/crc32.c"
    "${KRKR_ZLIB_DIR}/deflate.c"
    "${KRKR_ZLIB_DIR}/gzclose.c"
    "${KRKR_ZLIB_DIR}/gzlib.c"
    "${KRKR_ZLIB_DIR}/gzread.c"
    "${KRKR_ZLIB_DIR}/gzwrite.c"
    "${KRKR_ZLIB_DIR}/inflate.c"
    "${KRKR_ZLIB_DIR}/infback.c"
    "${KRKR_ZLIB_DIR}/inftrees.c"
    "${KRKR_ZLIB_DIR}/inffast.c"
    "${KRKR_ZLIB_DIR}/trees.c"
    "${KRKR_ZLIB_DIR}/uncompr.c"
    "${KRKR_ZLIB_DIR}/zutil.c"
)
add_library(twinquill_krkr_zlib STATIC ${KRKR_ZLIB_SOURCES})
target_include_directories(twinquill_krkr_zlib PUBLIC "${KRKR_ZLIB_DIR}")
target_compile_definitions(twinquill_krkr_zlib PRIVATE
    ZLIB_BUILD
    HAVE_STDARG_H=1
    HAVE_UNISTD_H=1
)

# libpng is admitted with the explicit decoder-relevant source list.  Copy the
# upstream configuration into the
# build tree and apply the numbered decoder-only patch before exposing it as
# pnglibconf.h; the immutable dependency snapshot is never edited in place.
set(KRKR_LIBPNG_GENERATED_DIR
    "${CMAKE_BINARY_DIR}/generated/krkr/libpng")
set(KRKR_LIBPNG_CONFIG_BUILD_DIR
    "${CMAKE_BINARY_DIR}/generated/krkr/libpng-config")
set(KRKR_LIBPNG_CONFIG_PATCH
    "${TWINQUILL_ROOT}/vendor/patches/libpng-1.6.58-krkr/0001-decoder-only-config.patch")
file(REMOVE_RECURSE "${KRKR_LIBPNG_CONFIG_BUILD_DIR}")
file(MAKE_DIRECTORY "${KRKR_LIBPNG_CONFIG_BUILD_DIR}")
file(COPY
    "${KRKR_LIBPNG_DIR}/scripts/pnglibconf.h.prebuilt"
    DESTINATION "${KRKR_LIBPNG_CONFIG_BUILD_DIR}")
file(RELATIVE_PATH KRKR_LIBPNG_CONFIG_RELATIVE_DIR
    "${TWINQUILL_ROOT}" "${KRKR_LIBPNG_CONFIG_BUILD_DIR}")
if(NOT EXISTS "${KRKR_LIBPNG_CONFIG_PATCH}")
    message(FATAL_ERROR "Missing libpng decoder-only patch: ${KRKR_LIBPNG_CONFIG_PATCH}")
endif()
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${KRKR_LIBPNG_DIR}/scripts/pnglibconf.h.prebuilt"
    "${KRKR_LIBPNG_CONFIG_PATCH}")
execute_process(
        COMMAND "${GIT_EXECUTABLE}" -c core.fsmonitor=false apply --check
            --directory=${KRKR_LIBPNG_CONFIG_RELATIVE_DIR}
            "${KRKR_LIBPNG_CONFIG_PATCH}"
    WORKING_DIRECTORY "${TWINQUILL_ROOT}"
    RESULT_VARIABLE KRKR_LIBPNG_PATCH_CHECK
    OUTPUT_VARIABLE KRKR_LIBPNG_PATCH_STDOUT
    ERROR_VARIABLE KRKR_LIBPNG_PATCH_STDERR
)
if(NOT KRKR_LIBPNG_PATCH_CHECK EQUAL 0)
    message(FATAL_ERROR
        "libpng decoder-only patch check failed: ${KRKR_LIBPNG_PATCH_STDERR}")
endif()
execute_process(
        COMMAND "${GIT_EXECUTABLE}" -c core.fsmonitor=false apply
            --directory=${KRKR_LIBPNG_CONFIG_RELATIVE_DIR}
            "${KRKR_LIBPNG_CONFIG_PATCH}"
    WORKING_DIRECTORY "${TWINQUILL_ROOT}"
    RESULT_VARIABLE KRKR_LIBPNG_PATCH_APPLY
    OUTPUT_VARIABLE KRKR_LIBPNG_APPLY_STDOUT
    ERROR_VARIABLE KRKR_LIBPNG_APPLY_STDERR
)
if(NOT KRKR_LIBPNG_PATCH_APPLY EQUAL 0)
    message(FATAL_ERROR
        "libpng decoder-only patch application failed: ${KRKR_LIBPNG_APPLY_STDERR}")
endif()
file(MAKE_DIRECTORY "${KRKR_LIBPNG_GENERATED_DIR}")
configure_file(
    "${KRKR_LIBPNG_CONFIG_BUILD_DIR}/pnglibconf.h.prebuilt"
    "${KRKR_LIBPNG_GENERATED_DIR}/pnglibconf.h"
    COPYONLY
)
set(KRKR_LIBPNG_SOURCES
    "${KRKR_LIBPNG_DIR}/png.c"
    "${KRKR_LIBPNG_DIR}/pngerror.c"
    "${KRKR_LIBPNG_DIR}/pngget.c"
    "${KRKR_LIBPNG_DIR}/pngmem.c"
    "${KRKR_LIBPNG_DIR}/pngpread.c"
    "${KRKR_LIBPNG_DIR}/pngread.c"
    "${KRKR_LIBPNG_DIR}/pngrio.c"
    "${KRKR_LIBPNG_DIR}/pngrtran.c"
    "${KRKR_LIBPNG_DIR}/pngrutil.c"
    "${KRKR_LIBPNG_DIR}/pngset.c"
    "${KRKR_LIBPNG_DIR}/pngtrans.c"
)
add_library(twinquill_krkr_libpng STATIC ${KRKR_LIBPNG_SOURCES})
target_include_directories(twinquill_krkr_libpng
    PUBLIC
        "${KRKR_LIBPNG_DIR}"
        "${KRKR_LIBPNG_GENERATED_DIR}"
)
target_compile_definitions(twinquill_krkr_libpng PRIVATE
    # The upstream guard documents this scalar opt-out; no ARM/NEON source
    # units are admitted, and armv7 therefore remains scalar-safe.
    PNG_ARM_NEON_OPT=0
)
target_link_libraries(twinquill_krkr_libpng PRIVATE twinquill_krkr_zlib)

# FreeType is admitted as one direct static target.  The generated include
# directory is first so each ABI gets its own ftconfig.h and ftoption.h, while
# the source include directory remains the fallback for public headers.
include(CheckIncludeFile)
include(CheckTypeSize)
include(CheckCSourceCompiles)
include(CheckCSourceRuns)

set(KRKR_FREETYPE_GENERATED_INCLUDE_DIR
    "${CMAKE_BINARY_DIR}/generated/krkr/freetype/include")
set(KRKR_FREETYPE_GENERATED_CONFIG_DIR
    "${KRKR_FREETYPE_GENERATED_INCLUDE_DIR}/freetype/config")
file(MAKE_DIRECTORY "${KRKR_FREETYPE_GENERATED_CONFIG_DIR}")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${KRKR_FREETYPE_DIR}/builds/unix/ftconfig.h.in")
check_include_file("unistd.h" KRKR_FREETYPE_HAVE_UNISTD_H)
check_include_file("fcntl.h" KRKR_FREETYPE_HAVE_FCNTL_H)
file(READ "${KRKR_FREETYPE_DIR}/builds/unix/ftconfig.h.in" KRKR_FREETYPE_CONFIG)
if(KRKR_FREETYPE_HAVE_UNISTD_H)
    string(REGEX REPLACE
        "#undef +HAVE_UNISTD_H" "#define HAVE_UNISTD_H 1"
        KRKR_FREETYPE_CONFIG "${KRKR_FREETYPE_CONFIG}")
endif()
if(KRKR_FREETYPE_HAVE_FCNTL_H)
    string(REGEX REPLACE
        "#undef +HAVE_FCNTL_H" "#define HAVE_FCNTL_H 1"
        KRKR_FREETYPE_CONFIG "${KRKR_FREETYPE_CONFIG}")
endif()
file(WRITE
    "${KRKR_FREETYPE_GENERATED_CONFIG_DIR}/ftconfig.h"
    "${KRKR_FREETYPE_CONFIG}")
configure_file(
    "${KRKR_FREETYPE_DIR}/include/freetype/config/ftoption.h"
    "${KRKR_FREETYPE_GENERATED_CONFIG_DIR}/ftoption.h"
    COPYONLY)

set(KRKR_FREETYPE_SOURCES
    "${KRKR_FREETYPE_DIR}/src/autofit/autofit.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftbase.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftbbox.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftbdf.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftbitmap.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftcid.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftfstype.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftgasp.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftglyph.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftgxval.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftinit.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftmm.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftotval.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftpatent.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftpfr.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftstroke.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftsynth.c"
    "${KRKR_FREETYPE_DIR}/src/base/fttype1.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftwinfnt.c"
    "${KRKR_FREETYPE_DIR}/src/bdf/bdf.c"
    "${KRKR_FREETYPE_DIR}/src/bzip2/ftbzip2.c"
    "${KRKR_FREETYPE_DIR}/src/cache/ftcache.c"
    "${KRKR_FREETYPE_DIR}/src/cff/cff.c"
    "${KRKR_FREETYPE_DIR}/src/cid/type1cid.c"
    "${KRKR_FREETYPE_DIR}/src/gzip/ftgzip.c"
    "${KRKR_FREETYPE_DIR}/src/lzw/ftlzw.c"
    "${KRKR_FREETYPE_DIR}/src/pcf/pcf.c"
    "${KRKR_FREETYPE_DIR}/src/pfr/pfr.c"
    "${KRKR_FREETYPE_DIR}/src/psaux/psaux.c"
    "${KRKR_FREETYPE_DIR}/src/pshinter/pshinter.c"
    "${KRKR_FREETYPE_DIR}/src/psnames/psnames.c"
    "${KRKR_FREETYPE_DIR}/src/raster/raster.c"
    "${KRKR_FREETYPE_DIR}/src/sdf/sdf.c"
    "${KRKR_FREETYPE_DIR}/src/sfnt/sfnt.c"
    "${KRKR_FREETYPE_DIR}/src/smooth/smooth.c"
    "${KRKR_FREETYPE_DIR}/src/svg/svg.c"
    "${KRKR_FREETYPE_DIR}/src/truetype/truetype.c"
    "${KRKR_FREETYPE_DIR}/src/type1/type1.c"
    "${KRKR_FREETYPE_DIR}/src/type42/type42.c"
    "${KRKR_FREETYPE_DIR}/src/winfonts/winfnt.c"
    "${KRKR_FREETYPE_DIR}/builds/unix/ftsystem.c"
    "${KRKR_FREETYPE_DIR}/src/base/ftdebug.c"
)
add_library(twinquill_krkr_freetype STATIC ${KRKR_FREETYPE_SOURCES})
target_compile_definitions(twinquill_krkr_freetype PRIVATE FT2_BUILD_LIBRARY)
set_target_properties(twinquill_krkr_freetype PROPERTIES
    C_VISIBILITY_PRESET hidden)
target_include_directories(twinquill_krkr_freetype
    PUBLIC
        "${KRKR_FREETYPE_GENERATED_INCLUDE_DIR}"
        "${KRKR_FREETYPE_DIR}/include"
    PRIVATE
        "${KRKR_FREETYPE_GENERATED_CONFIG_DIR}")

# libjpeg-turbo is admitted as one direct static target.  The three generated
# headers are configured in the active CMake binary directory, so ABI probes
# cannot leak between arm64-v8a and armeabi-v7a.
set(KRKR_LIBJPEG_GENERATED_DIR
    "${CMAKE_BINARY_DIR}/generated/krkr/libjpeg")
file(MAKE_DIRECTORY "${KRKR_LIBJPEG_GENERATED_DIR}")

function(krkr_configure_libjpeg_headers)
    set(CMAKE_PROJECT_NAME "libjpeg-turbo")
    set(VERSION "3.1.4.1")
    set(COPYRIGHT_YEAR "1991-2026")
    set(LIBJPEG_TURBO_VERSION_NUMBER 3001004)
    set(JPEG_LIB_VERSION 62)
    string(TIMESTAMP BUILD "%Y%m%d" UTC)
    check_type_size("size_t" SIZE_T)
    check_type_size("unsigned long" UNSIGNED_LONG)
    if(SIZE_T STREQUAL UNSIGNED_LONG)
        check_c_source_compiles(
            "int main(void) { unsigned long value = 1; return __builtin_ctzl(value); }"
            HAVE_BUILTIN_CTZL)
    endif()
    check_c_source_compiles(
        "extern const int table[1]; const int __attribute__((visibility(\"hidden\"))) table[1] = { 0 }; int main(void) { return table[0]; }"
        KRKR_LIBJPEG_HIDDEN_WORKS)
    if(KRKR_LIBJPEG_HIDDEN_WORKS)
        set(HIDDEN "__attribute__((visibility(\"hidden\")))")
    endif()
    check_c_source_compiles(
        "__inline__ __attribute__((always_inline)) static int foo(void) { return 0; } int main(void) { return foo(); }"
        KRKR_LIBJPEG_INLINE_WORKS)
    if(KRKR_LIBJPEG_INLINE_WORKS)
        set(INLINE "__inline__ __attribute__((always_inline))")
    else()
        set(INLINE "inline")
    endif()
    check_c_source_compiles(
        "static __thread int value; int main(void) { value = 0; return value; }"
        KRKR_LIBJPEG_THREAD_LOCAL_WORKS)
    if(KRKR_LIBJPEG_THREAD_LOCAL_WORKS)
        set(THREAD_LOCAL "__thread")
    endif()
    if(CMAKE_CROSSCOMPILING)
        set(RIGHT_SHIFT_IS_UNSIGNED 0)
    else()
        check_c_source_runs(
            [=[
#include <stdio.h>
#include <stdlib.h>
static int is_shifting_signed(long value) {
    long result = value >> 4;
    if (result == -0x7F7E80CL)
        return 1;
    result |= 0xFFFFFFFFL << (32 - 4);
    if (result == -0x7F7E80CL)
        return 0;
    printf("Right shift is not acting as expected.\n");
    printf("JPEG software may not work correctly.\n\n");
    return 0;
}
int main(void) {
    exit(is_shifting_signed(-0x7F7E80B1L));
}
]=]
            RIGHT_SHIFT_IS_UNSIGNED)
    endif()
    configure_file(
        "${KRKR_LIBJPEG_DIR}/src/jconfig.h.in"
        "${KRKR_LIBJPEG_GENERATED_DIR}/jconfig.h"
        @ONLY)
    configure_file(
        "${KRKR_LIBJPEG_DIR}/src/jconfigint.h.in"
        "${KRKR_LIBJPEG_GENERATED_DIR}/jconfigint.h"
        @ONLY)
    configure_file(
        "${KRKR_LIBJPEG_DIR}/src/jversion.h.in"
        "${KRKR_LIBJPEG_GENERATED_DIR}/jversion.h"
        @ONLY)
endfunction()
krkr_configure_libjpeg_headers()

set(KRKR_LIBJPEG_SOURCES
    "${KRKR_LIBJPEG_DIR}/src/jcapimin.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jcapistd-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jcapistd-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jcapistd-16.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jccoefct-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jccoefct-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jccolor-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jccolor-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jccolor-16.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jcdctmgr-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jcdctmgr-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jcdiffct-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jcdiffct-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jcdiffct-16.c"
    "${KRKR_LIBJPEG_DIR}/src/jchuff.c"
    "${KRKR_LIBJPEG_DIR}/src/jcicc.c"
    "${KRKR_LIBJPEG_DIR}/src/jcinit.c"
    "${KRKR_LIBJPEG_DIR}/src/jclhuff.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jclossls-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jclossls-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jclossls-16.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jcmainct-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jcmainct-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jcmainct-16.c"
    "${KRKR_LIBJPEG_DIR}/src/jcmarker.c"
    "${KRKR_LIBJPEG_DIR}/src/jcmaster.c"
    "${KRKR_LIBJPEG_DIR}/src/jcomapi.c"
    "${KRKR_LIBJPEG_DIR}/src/jcparam.c"
    "${KRKR_LIBJPEG_DIR}/src/jcphuff.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jcprepct-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jcprepct-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jcprepct-16.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jcsample-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jcsample-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jcsample-16.c"
    "${KRKR_LIBJPEG_DIR}/src/jctrans.c"
    "${KRKR_LIBJPEG_DIR}/src/jdapimin.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdapistd-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdapistd-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdapistd-16.c"
    "${KRKR_LIBJPEG_DIR}/src/jdatadst.c"
    "${KRKR_LIBJPEG_DIR}/src/jdatasrc.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdcoefct-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdcoefct-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdcolor-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdcolor-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdcolor-16.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jddctmgr-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jddctmgr-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jddiffct-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jddiffct-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jddiffct-16.c"
    "${KRKR_LIBJPEG_DIR}/src/jdhuff.c"
    "${KRKR_LIBJPEG_DIR}/src/jdicc.c"
    "${KRKR_LIBJPEG_DIR}/src/jdinput.c"
    "${KRKR_LIBJPEG_DIR}/src/jdlhuff.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdlossls-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdlossls-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdlossls-16.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdmainct-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdmainct-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdmainct-16.c"
    "${KRKR_LIBJPEG_DIR}/src/jdmarker.c"
    "${KRKR_LIBJPEG_DIR}/src/jdmaster.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdmerge-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdmerge-12.c"
    "${KRKR_LIBJPEG_DIR}/src/jdphuff.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdpostct-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdpostct-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdpostct-16.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdsample-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdsample-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jdsample-16.c"
    "${KRKR_LIBJPEG_DIR}/src/jdtrans.c"
    "${KRKR_LIBJPEG_DIR}/src/jerror.c"
    "${KRKR_LIBJPEG_DIR}/src/jfdctflt.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jfdctfst-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jfdctfst-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jfdctint-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jfdctint-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jidctflt-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jidctflt-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jidctfst-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jidctfst-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jidctint-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jidctint-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jidctred-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jidctred-12.c"
    "${KRKR_LIBJPEG_DIR}/src/jmemmgr.c"
    "${KRKR_LIBJPEG_DIR}/src/jmemnobs.c"
    "${KRKR_LIBJPEG_DIR}/src/jpeg_nbits.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jquant1-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jquant1-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jquant2-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jquant2-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jutils-8.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jutils-12.c"
    "${KRKR_LIBJPEG_DIR}/src/wrapper/jutils-16.c"
)
add_library(twinquill_krkr_libjpeg STATIC ${KRKR_LIBJPEG_SOURCES})
target_include_directories(twinquill_krkr_libjpeg
    PUBLIC
        "${KRKR_LIBJPEG_GENERATED_DIR}"
        "${KRKR_LIBJPEG_DIR}/src")
