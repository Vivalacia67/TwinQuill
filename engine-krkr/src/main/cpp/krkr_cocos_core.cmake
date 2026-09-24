# Curated Cocos2d-x 3.17.2 source-only core.
#
# The immutable source subset lives under vendor/cocos2d-x-3.17.2-krkr.  We
# copy it into the CMake build tree and apply only numbered, reviewable
# patches; the tracked vendor snapshot is never edited in place.  Every
# translation unit below is written out explicitly so a new dependency cannot
# enter through a directory glob or an upstream aggregate target.

set(TWINQUILL_COCOS_VENDOR_DIR
    "${TWINQUILL_ROOT}/vendor/cocos2d-x-3.17.2-krkr")
set(TWINQUILL_COCOS_BUILD_DIR
    "${CMAKE_BINARY_DIR}/generated/cocos2d-x-3.17.2-krkr")

file(REMOVE_RECURSE "${TWINQUILL_COCOS_BUILD_DIR}")
file(MAKE_DIRECTORY "${TWINQUILL_COCOS_BUILD_DIR}")
file(COPY "${TWINQUILL_COCOS_VENDOR_DIR}/"
     DESTINATION "${TWINQUILL_COCOS_BUILD_DIR}")
file(RELATIVE_PATH TWINQUILL_COCOS_BUILD_RELATIVE_DIR
     "${TWINQUILL_ROOT}" "${TWINQUILL_COCOS_BUILD_DIR}")

# This dependency-only glob invalidates configure when any admitted snapshot
# file changes.  It never feeds a target source list: CORE_SOURCES below is an
# explicit 17-TU list, so source admission remains reviewable and non-globbed.
file(GLOB_RECURSE TWINQUILL_COCOS_SNAPSHOT_INPUTS CONFIGURE_DEPENDS
     "${TWINQUILL_COCOS_VENDOR_DIR}/*")

set(TWINQUILL_COCOS_PATCH_DIR
    "${TWINQUILL_ROOT}/vendor/patches/cocos2d-x-3.17.2-krkr")
set(TWINQUILL_COCOS_PATCHES
    "${TWINQUILL_COCOS_PATCH_DIR}/0001-cocos-platform-boundary.patch"
    "${TWINQUILL_COCOS_PATCH_DIR}/0002-cocos-image-decode-only.patch"
    "${TWINQUILL_COCOS_PATCH_DIR}/0004-cocos-macros-no-console.patch"
    "${TWINQUILL_COCOS_PATCH_DIR}/0005-cocos-math-armv7-scalar.patch"
    "${TWINQUILL_COCOS_PATCH_DIR}/0006-cocos-value-bounded-atof.patch"
    "${TWINQUILL_COCOS_PATCH_DIR}/0007-cocos-data-no-console.patch"
    "${TWINQUILL_COCOS_PATCH_DIR}/0008-cocos-data-macros.patch")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    ${TWINQUILL_COCOS_SNAPSHOT_INPUTS}
    ${TWINQUILL_COCOS_PATCHES})

foreach(TWINQUILL_COCOS_PATCH IN LISTS TWINQUILL_COCOS_PATCHES)
    if(NOT EXISTS "${TWINQUILL_COCOS_PATCH}")
        message(FATAL_ERROR "Missing Cocos boundary patch: ${TWINQUILL_COCOS_PATCH}")
    endif()
    execute_process(
        COMMAND "${GIT_EXECUTABLE}" -c core.fsmonitor=false apply --check
                --ignore-space-change
                --directory=${TWINQUILL_COCOS_BUILD_RELATIVE_DIR}
                "${TWINQUILL_COCOS_PATCH}"
        WORKING_DIRECTORY "${TWINQUILL_ROOT}"
        RESULT_VARIABLE TWINQUILL_COCOS_PATCH_CHECK
        OUTPUT_VARIABLE TWINQUILL_COCOS_PATCH_STDOUT
        ERROR_VARIABLE TWINQUILL_COCOS_PATCH_STDERR
    )
    if(NOT TWINQUILL_COCOS_PATCH_CHECK EQUAL 0)
        message(FATAL_ERROR
            "Cocos patch check failed for ${TWINQUILL_COCOS_PATCH}: "
            "${TWINQUILL_COCOS_PATCH_STDERR}")
    endif()
    execute_process(
        COMMAND "${GIT_EXECUTABLE}" -c core.fsmonitor=false apply
                --ignore-space-change
                --directory=${TWINQUILL_COCOS_BUILD_RELATIVE_DIR}
                "${TWINQUILL_COCOS_PATCH}"
        WORKING_DIRECTORY "${TWINQUILL_ROOT}"
        RESULT_VARIABLE TWINQUILL_COCOS_PATCH_APPLY
        OUTPUT_VARIABLE TWINQUILL_COCOS_APPLY_STDOUT
        ERROR_VARIABLE TWINQUILL_COCOS_APPLY_STDERR
    )
    if(NOT TWINQUILL_COCOS_PATCH_APPLY EQUAL 0)
        message(FATAL_ERROR
            "Cocos patch application failed for ${TWINQUILL_COCOS_PATCH}: "
            "${TWINQUILL_COCOS_APPLY_STDERR}")
    endif()
endforeach()

# The source ceiling is intentionally broader than the first link proof.  The
# initial host needs the platform image seam and deterministic math/base
# runtime; this link proof compiles exactly 17 explicit TUs.  Additional
# 2D/renderer units remain admitted in the snapshot and can only be added to
# the compiled closure after an explicit unresolved-symbol audit.  No blocked
# source/dependency is pulled transitively.
set(TWINQUILL_COCOS_CORE_SOURCES
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos/math/CCAffineTransform.cpp"
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos/math/CCGeometry.cpp"
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos/math/CCVertex.cpp"
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos/math/Mat4.cpp"
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos/math/MathUtil.cpp"
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos/math/Quaternion.cpp"
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos/math/TransformUtils.cpp"
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos/math/Vec2.cpp"
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos/math/Vec3.cpp"
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos/math/Vec4.cpp"
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos/base/CCAutoreleasePool.cpp"
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos/base/CCData.cpp"
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos/base/CCRef.cpp"
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos/base/CCValue.cpp"
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos/base/ccCArray.cpp"
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos/base/ccTypes.cpp"
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos/platform/CCImage.cpp"
)

add_library(twinquill_krkr_cocos_core STATIC ${TWINQUILL_COCOS_CORE_SOURCES})
set_target_properties(twinquill_krkr_cocos_core PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    OUTPUT_NAME twinquill_krkr_cocos_core
)
target_compile_features(twinquill_krkr_cocos_core PUBLIC cxx_std_17)
target_compile_definitions(twinquill_krkr_cocos_core
    PUBLIC CC_ENABLE_SCRIPT_BINDING=0
    PRIVATE
    CC_USE_PNG=1
    CC_USE_JPEG=1
    CC_USE_TIFF=0
    CC_USE_WEBP=0
    CC_USE_PHYSICS=0
    CC_USE_3D_PHYSICS=0
    CC_USE_NAVMESH=0
)
target_include_directories(twinquill_krkr_cocos_core
    PUBLIC
        "${TWINQUILL_COCOS_BUILD_DIR}"
        "${TWINQUILL_COCOS_BUILD_DIR}/cocos"
        "${KRKR_DEPS_ROOT}/libpng-1.6.58"
        "${KRKR_LIBPNG_GENERATED_DIR}"
        "${KRKR_LIBJPEG_GENERATED_DIR}"
        "${KRKR_LIBJPEG_DIR}/src"
        "${KRKR_FREETYPE_GENERATED_INCLUDE_DIR}"
        "${KRKR_FREETYPE_DIR}/include"
)
target_link_libraries(twinquill_krkr_cocos_core
    PRIVATE
        twinquill_krkr_libpng
        twinquill_krkr_zlib
        twinquill_krkr_freetype
        twinquill_krkr_libjpeg
)

# Device-executable decoder seam.  It is deliberately EXCLUDE_FROM_ALL: the
# normal engine target remains the authoritative production link, while this
# executable links the same patched core and four approved static dependency
# targets for an eventual adb/instrumentation run.
add_executable(twinquill_krkr_cocos_image_decode_test EXCLUDE_FROM_ALL
    "${TWINQUILL_ROOT}/tests/native/krkr_cocos_image_decode_test.cpp")
target_link_options(twinquill_krkr_cocos_image_decode_test PRIVATE -static-libstdc++)
target_compile_features(twinquill_krkr_cocos_image_decode_test PRIVATE cxx_std_17)
target_compile_definitions(twinquill_krkr_cocos_image_decode_test PRIVATE
    CC_USE_PNG=1
    CC_USE_JPEG=1
    CC_USE_TIFF=0
    CC_USE_WEBP=0
)
target_include_directories(twinquill_krkr_cocos_image_decode_test PRIVATE
    "${TWINQUILL_COCOS_BUILD_DIR}"
    "${TWINQUILL_COCOS_BUILD_DIR}/cocos"
    "${KRKR_DEPS_ROOT}/libpng-1.6.58"
    "${KRKR_LIBPNG_GENERATED_DIR}"
    "${KRKR_LIBJPEG_GENERATED_DIR}"
    "${KRKR_LIBJPEG_DIR}/src"
    "${KRKR_FREETYPE_GENERATED_INCLUDE_DIR}"
    "${KRKR_FREETYPE_DIR}/include"
)
target_link_libraries(twinquill_krkr_cocos_image_decode_test PRIVATE
    twinquill_krkr_cocos_core
    twinquill_krkr_libpng
    twinquill_krkr_zlib
    twinquill_krkr_freetype
    twinquill_krkr_libjpeg
    android
    log
)

# The first-party shared engine links this archive as a real whole archive.
# This is deliberately not add_dependencies(): every admitted object is
# presented to the final linker, making undefined-symbol closure auditable.
target_link_libraries(twinquill_engine_krkr PRIVATE
    "-Wl,--whole-archive"
    twinquill_krkr_cocos_core
    "-Wl,--no-whole-archive"
)

# B1 is a first-party host seam. It consumes only the admitted shader source
# files and direct GLES2 calls; no upstream lifecycle or renderer translation
# unit enters the final shared library.
target_sources(twinquill_engine_krkr PRIVATE
    "${TWINQUILL_ROOT}/engine-krkr/src/main/cpp/krkr_runtime_state.cpp"
    "${TWINQUILL_ROOT}/engine-krkr/src/main/cpp/krkr_cocos_runtime.cpp"
    "${TWINQUILL_ROOT}/engine-krkr/src/main/cpp/krkr_runtime_jni.cpp"
)
target_include_directories(twinquill_engine_krkr PRIVATE
    "${TWINQUILL_ROOT}/engine-krkr/src/main/cpp"
    "${TWINQUILL_COCOS_BUILD_DIR}"
)
target_link_libraries(twinquill_engine_krkr PRIVATE GLESv2)

# The decoder seam stays EXCLUDE_FROM_ALL, but a normal Debug engine build must
# still compile/link it so the approved decoder closure cannot silently go
# stale.  Release configurations remain unchanged and do not pull this seam.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_dependencies(twinquill_engine_krkr
        twinquill_krkr_cocos_image_decode_test)
endif()
