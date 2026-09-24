# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors

"""Focused source-admission and link-boundary checks for Slice04 Cocos."""

from __future__ import annotations

import subprocess
import shutil
import hashlib
import re
import tempfile
import tomllib
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "third_party" / "sources.toml"
CORE_CMAKE = ROOT / "engine-krkr" / "src" / "main" / "cpp" / "krkr_cocos_core.cmake"
ENGINE_CMAKE = ROOT / "engine-krkr" / "src" / "main" / "cpp" / "CMakeLists.txt"
DEPENDENCIES_CMAKE = ROOT / "engine-krkr" / "src" / "main" / "cpp" / "krkr_dependencies.cmake"
NATIVE_IMAGE_TEST = ROOT / "tests" / "native" / "krkr_cocos_image_decode_test.cpp"
LINK_BOUNDARY_SCRIPT = ROOT / "scripts" / "check_krkr_cocos_link_boundary.py"
IMAGE_RUNNER_SCRIPT = ROOT / "scripts" / "run_krkr_cocos_image_decode_test.py"

SOURCE_ID = "cocos2d-x-3.17.2-krkr"
FORBIDDEN_FAMILIES = {
    "3d",
    "physics",
    "physics3d",
    "navmesh",
    "vr",
    "audio",
    "network",
    "storage",
    "ui",
    "editor-support",
    "scripting",
    "extensions",
    "deprecated",
    "tests",
    "templates",
    "tools",
    "external",
}
FORBIDDEN_SUFFIXES = {
    ".a",
    ".so",
    ".dll",
    ".dylib",
    ".lib",
    ".o",
    ".obj",
    ".jar",
    ".aar",
}
ALLOWED_SYSTEM_LIBS = {"GLESv2", "android", "log", "dl", "m"}
REQUIRED_PATCHES = (
    "0001-cocos-platform-boundary.patch",
    "0002-cocos-image-decode-only.patch",
    "0004-cocos-macros-no-console.patch",
    "0005-cocos-math-armv7-scalar.patch",
    "0006-cocos-value-bounded-atof.patch",
    "0007-cocos-data-no-console.patch",
    "0008-cocos-data-macros.patch",
)
LIBPNG_PATCH = (
    ROOT
    / "vendor"
    / "patches"
    / "libpng-1.6.58-krkr"
    / "0001-decoder-only-config.patch"
)
LIBPNG_CONFIG_SOURCE = (
    ROOT
    / "vendor"
    / "deps"
    / "krkr"
    / "libpng-1.6.58"
    / "scripts"
    / "pnglibconf.h.prebuilt"
)


def cocos_record() -> dict[str, object]:
    parsed = tomllib.loads(MANIFEST.read_text(encoding="utf-8"))
    return next(record for record in parsed["sources"] if record["id"] == SOURCE_ID)


def cmake_link_block(text: str, target: str) -> str:
    match = re.search(
        rf"target_link_libraries\(\s*{re.escape(target)}\b", text,
    )
    if match is None:
        raise AssertionError(f"missing target_link_libraries block for {target}")
    end = text.find(")", match.end())
    if end < 0:
        raise AssertionError(f"unterminated target_link_libraries block for {target}")
    return text[match.end():end]


def cmake_link_items(text: str, target: str) -> set[str]:
    block = cmake_link_block(text, target)
    return {
        token.strip('"')
        for token in re.findall(r'"[^"]+"|[^\s]+', block)
        if token not in {"PRIVATE", "PUBLIC", "INTERFACE"}
    }


def assert_strict_link_items(test: unittest.TestCase, text: str, target: str, expected: set[str]) -> None:
    test.assertEqual(cmake_link_items(text, target), expected, target)


class CocosSourceCeilingTests(unittest.TestCase):
    def test_identity_source_type_and_file_count(self) -> None:
        record = cocos_record()
        self.assertEqual(record["source_type"], "git-archive-subset")
        self.assertEqual(record["tag"], "cocos2d-x-3.17.2")
        self.assertEqual(record["tag_type"], "lightweight")
        self.assertEqual(record["commit"], "1528ea01d2749b4ef65e97f79cffa6135fe13c4d")
        self.assertEqual(record["tree"], "ddcc1e3a9878bc2f81ab391b35de8b901d5caf50")
        self.assertEqual(
            record["archive_sha256"],
            "7f5b53bd136e146fd64b25fbccfc49434bc30a513ab6ffb6b47e303bf7882287",
        )
        destination = ROOT / str(record["destination"])
        self.assertTrue(destination.is_dir())
        actual_paths = {
            path.relative_to(destination).as_posix()
            for path in destination.rglob("*")
            if path.is_file()
        }
        file_count = len(actual_paths)
        self.assertEqual(record["file_count"], 191)
        self.assertEqual(record["compiled_tu_count"], 17)
        self.assertEqual(file_count, record["file_count"])
        inventory = list(record["included_paths"]) + list(
            record["included_support_paths"]
        )
        self.assertEqual(len(inventory), len(set(inventory)))
        self.assertEqual(set(inventory), actual_paths)
        self.assertEqual(record["license_files"], ["licenses/LICENSE_cocos2d-x.txt"])
        self.assertTrue((destination / "licenses/LICENSE_cocos2d-x.txt").is_file())

    def test_patch_inventory_headers_and_digests(self) -> None:
        parsed = tomllib.loads(MANIFEST.read_text(encoding="utf-8"))
        seen: set[str] = set()
        for record in parsed["sources"]:
            patches = record.get("patches", [])
            digests = record.get("patch_sha256", [])
            if not patches:
                self.assertEqual(digests, [], record.get("id"))
                continue
            self.assertEqual(len(patches), len(digests), record["id"])
            self.assertEqual(len(patches), len(set(patches)), record["id"])
            for patch_path, expected in zip(patches, digests):
                self.assertNotIn(patch_path, seen)
                seen.add(patch_path)
                patch = ROOT / patch_path
                self.assertTrue(patch.is_file(), patch)
                actual = hashlib.sha256(patch.read_bytes()).hexdigest()
                self.assertEqual(actual, expected, patch)
                if patch_path.startswith(
                    (
                        "vendor/patches/cocos2d-x-3.17.2-krkr/",
                        "vendor/patches/libpng-1.6.58-krkr/",
                    )
                ):
                    text = patch.read_text(encoding="utf-8")
                    self.assertIn("TwinQuill rationale:", text, patch)
                    self.assertIn("Verification:", text, patch)
        self.assertEqual(
            tuple(Path(path).name for path in cocos_record()["patches"]),
            REQUIRED_PATCHES,
        )

    def test_allowlist_exclusions_suffixes_symlinks_and_nested_git(self) -> None:
        record = cocos_record()
        destination = ROOT / str(record["destination"])
        for path in destination.rglob("*"):
            self.assertFalse(path.is_symlink(), path)
            self.assertNotIn(".git", path.parts, path)
            if not path.is_file():
                continue
            self.assertNotIn(path.suffix.lower(), FORBIDDEN_SUFFIXES, path)
            relative_parts = set(path.relative_to(destination).parts)
            self.assertFalse(relative_parts & FORBIDDEN_FAMILIES, path)
        for relative in record["excluded_files"]:
            self.assertFalse((ROOT / "vendor" / "cocos2d-x-3.17.2-krkr" / relative).exists())

    def test_translation_unit_allowlist_is_explicit_and_no_glob(self) -> None:
        record = cocos_record()
        included = record["included_paths"]
        self.assertIsInstance(included, list)
        self.assertTrue(included)
        self.assertTrue(all("*" not in path and "?" not in path for path in included))
        self.assertTrue(all(path.endswith((".cpp", ".h", ".vert", ".frag", ".txt")) for path in included))

        core = CORE_CMAKE.read_text(encoding="utf-8")
        self.assertIn("add_library(twinquill_krkr_cocos_core STATIC", core)
        self.assertNotRegex(
            core,
            r"file\s*\(\s*GLOB(?!_RECURSE\s+TWINQUILL_COCOS_SNAPSHOT_INPUTS)",
        )
        self.assertNotIn("add_subdirectory(cocos", core)
        self.assertNotIn("Android.mk", core)
        for unit in (
            "math/CCAffineTransform.cpp",
            "math/MathUtil.cpp",
            "base/CCRef.cpp",
            "platform/CCImage.cpp",
        ):
            self.assertIn(unit, core)
        compiled_units = set(re.findall(r"cocos/[^\"\s)]+\.cpp", core))
        self.assertTrue(compiled_units)
        self.assertEqual(len(compiled_units), record["compiled_tu_count"])
        self.assertTrue(compiled_units <= set(included))
        self.assertNotRegex(core, r"(?:tinyxml2|minizip|xxhash|ConvertUTF|edtaa|cpu-features)")

    def test_patch_application_and_decode_only_semantics(self) -> None:
        destination = ROOT / str(cocos_record()["destination"])
        patch_dir = ROOT / "vendor" / "patches" / SOURCE_ID
        for name in REQUIRED_PATCHES:
            patch = patch_dir / name
            self.assertTrue(patch.is_file(), patch)
            result = subprocess.run(
                ["git", "apply", "--check", "--ignore-space-change", str(patch)],
                cwd=destination,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)

        # `--check` alone can report success while a repository-root Git
        # invocation skips files in an untracked generated tree.  Apply the
        # numbered set to an isolated copy and inspect the resulting build
        # sources so the boundary is exercised, not merely parsed.
        with tempfile.TemporaryDirectory(prefix="twinquill-cocos-patch-") as temporary:
            applied = Path(temporary) / "snapshot"
            shutil.copytree(destination, applied)
            for name in REQUIRED_PATCHES:
                result = subprocess.run(
                    [
                        "git",
                        "apply",
                        "--unsafe-paths",
                        "--ignore-space-change",
                        str(patch_dir / name),
                    ],
                    cwd=applied,
                    capture_output=True,
                    text=True,
                    check=False,
                )
                self.assertEqual(result.returncode, 0, result.stderr)

            macros = (applied / "cocos" / "base" / "ccMacros.h").read_text(encoding="utf-8")
            self.assertNotIn("base/CCConsole.h", macros)
            self.assertNotIn("base/ccRandom.h", macros)
            image = (applied / "cocos" / "platform" / "CCImage.cpp").read_text(encoding="utf-8")
            image_header = (applied / "cocos" / "platform" / "CCImage.h").read_text(encoding="utf-8")
            self.assertIn("Permission is hereby granted", image)
            self.assertNotIn("decode-only boundary", image)
            forbidden_image_tokens = (
                "TIFF",
                "Tiff",
                "WebP",
                "WEBP",
                "TGA",
                "PVR",
                "ETC",
                "S3TC",
                "ATITC",
                "CCZ",
                "GZIP",
                "ZipUtils",
                "tiff",
                "webp",
                "tga",
                "pvr",
                "etc",
                "s3tc",
                "atitc",
                "ccz",
                "gzip",
                "ziputils",
            )
            for token in forbidden_image_tokens:
                self.assertNotIn(token, image, token)
                self.assertNotIn(token, image_header, token)
            self.assertIn("kMaxImageDimension = 16384U", image)
            self.assertIn("kMaxDecodedBytes = 256U * 1024U * 1024U", image)
            self.assertNotIn("MAX_IMAGE_DIMENSION", image)
            self.assertNotIn("MAX_DECODED_BYTES", image)
            self.assertIn("bool checked_mul", image)
            self.assertNotIn("checkedMul", image)
            self.assertRegex(
                image,
                r"if \(left == 0U \|\| right == 0U\)\s*\{\s*\*result = 0U;\s*return true;",
            )
            self.assertGreaterEqual(len(re.findall(r"checked_mul", image)), 8)
            self.assertIn("numeric_limits<size_t>::max()", image)
            self.assertIn("numeric_limits<ssize_t>::max()", image)
            self.assertRegex(image, r"static_cast<size_t>\(INT_MAX\)")
            self.assertIn("png_set_palette_to_rgb", image)
            self.assertIn("png_set_expand_gray_1_2_4_to_8", image)
            self.assertIn("png_set_tRNS_to_alpha", image)
            self.assertIn("png_set_strip_16", image)
            self.assertIn("png_set_gray_to_rgb", image)
            self.assertIn("png_set_add_alpha", image)
            self.assertIn("PNG_COLOR_TYPE_RGBA", image)
            self.assertIn("std::malloc", image)
            self.assertNotIn("std::vector", image)
            self.assertIn("struct PngDecodeState", image)
            self.assertIn("struct JpegDecodeState", image)
            self.assertIn("std::calloc(1U, sizeof(PngDecodeState))", image)
            self.assertIn("std::calloc(1U, sizeof(JpegDecodeState))", image)
            self.assertIn("void cleanup_png_state", image)
            self.assertIn("void cleanup_jpeg_state", image)
            self.assertIn("state->decoded = nullptr;\n    cleanup_png_state(state);", image)
            self.assertIn("state->decoded = nullptr;\n    cleanup_jpeg_state(state);", image)
            self.assertIn("state->created = false;", image)
            self.assertNotRegex(image, r"png_bytep\* rows\s*=\s*nullptr")
            self.assertNotRegex(image, r"jpeg_decompress_struct decoder\{\}")
            self.assertLess(
                image.index("state->decoder.image_width == 0U"),
                image.index("jpeg_start_decompress(&state->decoder)"),
            )
            self.assertRegex(
                image,
                r"state->row_offset = 0U;\s*"
                r"if \(!checked_mul\(static_cast<size_t>\(state->decoder\.output_scanline - 1U\)",
            )
            for region in re.findall(
                r"setjmp\([^\n]+\).*?return false;", image, flags=re.DOTALL
            ):
                self.assertNotIn("std::vector", region)
                self.assertNotIn("std::string", region)
            self.assertIn("bool Image::saveToFile(const std::string&, bool) { return false; }", image)
            self.assertIn("bool Image::saveImageToPNG(const std::string&, bool) { return false; }", image)
            self.assertIn("bool Image::saveImageToJPG(const std::string&) { return false; }", image)
            self.assertNotIn("png_write", image)
            self.assertNotIn("jpeg_start_compress", image)
            self.assertNotIn("_mipmaps[0] = {", image)
            self.assertIn("_mipmaps[0].address", image)
            self.assertNotRegex(
                image_header,
                r"\b(?:TIFF|WEBP|PVR|ETC|S3TC|ATITC|TGA|initWithTiff|initWithWebp|"
                r"initWithPVR|initWithETC|initWithS3TC|initWithATITC|initWithTGA|"
                r"isTiff|isWebp|isPvr|isEtc|isS3TC|isATITC)\b",
            )
            math = (applied / "cocos" / "math" / "MathUtil.cpp").read_text(encoding="utf-8")
            self.assertNotIn("android_getCpuFamily", math)
            self.assertNotIn("android_getCpuFeatures", math)
            android_math = math.split(
                "#elif (CC_TARGET_PLATFORM == CC_PLATFORM_ANDROID)", 1
            )[1].split("#else", 1)[0]
            self.assertNotIn("INCLUDE_NEON32", android_math)
            value = (applied / "cocos" / "base" / "CCValue.cpp").read_text(encoding="utf-8")
            self.assertNotIn('"base/ccUtils.h"', value)
            self.assertIn("std::atof", value)
            data = (applied / "cocos" / "base" / "CCData.cpp").read_text(encoding="utf-8")
            self.assertNotIn('"base/CCConsole.h"', data)
            self.assertIn('#include "base/ccMacros.h"', data)

            for shader in (
                "cocos/renderer/ccShaders.cpp",
                "cocos/renderer/ccShaders.h",
            ):
                self.assertEqual(
                    (applied / shader).read_bytes(),
                    (destination / shader).read_bytes(),
                    shader,
                )

        image_patch = (patch_dir / "0002-cocos-image-decode-only.patch").read_text(encoding="utf-8")
        self.assertIn("png_create_read_struct", image_patch)
        self.assertIn("jpeg_create_decompress", image_patch)
        self.assertIn("bool Image::saveToFile(const std::string&, bool) { return false; }", image_patch)
        self.assertIn("bool Image::saveImageToPNG(const std::string&, bool) { return false; }", image_patch)
        self.assertIn("bool Image::saveImageToJPG(const std::string&) { return false; }", image_patch)
        patched_lines = "\n".join(
            line[1:]
            for line in image_patch.splitlines()
            if line.startswith("+") and not line.startswith("+++")
        )
        self.assertNotIn("png_write", patched_lines)
        self.assertNotIn("jpeg_start_compress", patched_lines)
        self.assertNotRegex(patched_lines, r"#include .*?(tiff|decode\.h|ZipUtils|pvr|TGAlib|etc1|s3tc|atitc)")
        macros_patch = (patch_dir / "0004-cocos-macros-no-console.patch").read_text(encoding="utf-8")
        self.assertNotIn('+#include "base/CCConsole.h"', macros_patch)
        self.assertNotIn('+#include "base/ccRandom.h"', macros_patch)

    def test_dependency_targets_and_whole_archive_link_boundary(self) -> None:
        core = CORE_CMAKE.read_text(encoding="utf-8")
        engine = ENGINE_CMAKE.read_text(encoding="utf-8")
        core_block = cmake_link_block(core, "twinquill_krkr_cocos_core")
        first_party = {
            token
            for token in re.findall(r"[A-Za-z_][A-Za-z0-9_:-]*", core_block)
            if token.startswith("twinquill_krkr_")
        }
        self.assertEqual(
            first_party,
            {
                "twinquill_krkr_zlib",
                "twinquill_krkr_libpng",
                "twinquill_krkr_freetype",
                "twinquill_krkr_libjpeg",
            },
        )
        self.assertIn('"-Wl,--whole-archive"', core)
        self.assertIn("twinquill_krkr_cocos_core", core)
        self.assertIn('"-Wl,--no-whole-archive"', core)
        self.assertIn("include(krkr_cocos_core.cmake)", engine)
        self.assertRegex(engine, r"find_package\(Git\s+2\.30\s+REQUIRED\)")
        self.assertNotIn("separate_arguments", engine)
        self.assertNotIn("list(REMOVE_ITEM", engine)
        self.assertIsNotNone(
            re.search(r"string\(REGEX\s+REPLACE.*-latomic", engine, re.DOTALL)
        )
        self.assertNotIn("CMAKE_C_STANDARD_LIBRARIES", engine)
        self.assertNotRegex(core, r"COMMAND\s+git(?:\s|\")")
        self.assertNotRegex(DEPENDENCIES_CMAKE.read_text(encoding="utf-8"), r"COMMAND\s+git(?:\s|\")")
        self.assertNotIn("add_subdirectory(cocos", engine)
        self.assertNotIn("Android.mk", engine)
        dependencies = DEPENDENCIES_CMAKE.read_text(encoding="utf-8")
        self.assertIn("linked transitively", dependencies)
        self.assertIn('"${KRKR_LIBPNG_DIR}/scripts/pnglibconf.h.prebuilt"', dependencies)
        self.assertIn("CMAKE_CONFIGURE_DEPENDS", dependencies)
        self.assertNotRegex(dependencies, r"(?i)compile-only|never linked|never links")
        assert_strict_link_items(
            self,
            core,
            "twinquill_krkr_cocos_core",
            {
                "twinquill_krkr_libpng",
                "twinquill_krkr_zlib",
                "twinquill_krkr_freetype",
                "twinquill_krkr_libjpeg",
            },
        )
        assert_strict_link_items(
            self,
            core,
            "twinquill_krkr_cocos_image_decode_test",
            {
                "twinquill_krkr_cocos_core",
                "twinquill_krkr_libpng",
                "twinquill_krkr_zlib",
                "twinquill_krkr_freetype",
                "twinquill_krkr_libjpeg",
                "android",
                "log",
            },
        )
        assert_strict_link_items(
            self,
            core,
            "twinquill_engine_krkr",
            {"-Wl,--whole-archive", "twinquill_krkr_cocos_core", "-Wl,--no-whole-archive"},
        )
        assert_strict_link_items(
            self,
            engine,
            "twinquill_engine_krkr",
            {"twinquill_krkr_tjs2", "native-vfs::twinquill_native_vfs", "android", "log"},
        )
        assert_strict_link_items(self, engine, "twinquill_krkr_tjs2", {"onig"})
        assert_strict_link_items(
            self, dependencies, "twinquill_krkr_libpng", {"twinquill_krkr_zlib"}
        )
        declared_engine_items = cmake_link_items(engine, "twinquill_engine_krkr")
        declared_system_libs = {
            token for token in declared_engine_items if token in ALLOWED_SYSTEM_LIBS
        }
        self.assertEqual(declared_system_libs, {"android", "log"})
        self.assertTrue(declared_system_libs <= ALLOWED_SYSTEM_LIBS)
        self.assertNotIn("vulkan", declared_engine_items)
        self.assertNotIn("OpenSLES", declared_engine_items)

    def test_link_parser_rejects_unknown_item(self) -> None:
        engine = ENGINE_CMAKE.read_text(encoding="utf-8")
        altered = engine.replace("    log\n)", "    log\n    vulkan\n)", 1)
        with self.assertRaises(AssertionError):
            assert_strict_link_items(
                self,
                altered,
                "twinquill_engine_krkr",
                {"twinquill_krkr_tjs2", "native-vfs::twinquill_native_vfs", "android", "log"},
            )

    def test_snapshot_inputs_are_configure_dependencies(self) -> None:
        record = cocos_record()
        destination = ROOT / str(record["destination"])
        core = CORE_CMAKE.read_text(encoding="utf-8")
        self.assertIn("TWINQUILL_COCOS_SNAPSHOT_INPUTS", core)
        self.assertRegex(
            core,
            r"file\(GLOB_RECURSE TWINQUILL_COCOS_SNAPSHOT_INPUTS CONFIGURE_DEPENDS",
        )
        self.assertRegex(
            core,
            r"set_property\(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS\s+"
            r"\$\{TWINQUILL_COCOS_SNAPSHOT_INPUTS\}",
        )
        self.assertIn('file(REMOVE_RECURSE "${TWINQUILL_COCOS_BUILD_DIR}")', core)
        self.assertIn('file(COPY "${TWINQUILL_COCOS_VENDOR_DIR}/"', core)
        source_block = core.split("set(TWINQUILL_COCOS_CORE_SOURCES", 1)[1]
        self.assertNotIn("TWINQUILL_COCOS_SNAPSHOT_INPUTS", source_block)
        actual = {
            path.relative_to(destination).as_posix()
            for path in destination.rglob("*")
            if path.is_file()
        }
        self.assertEqual(len(actual), record["file_count"])

    def test_native_decoder_executable_target_is_declared(self) -> None:
        self.assertTrue(NATIVE_IMAGE_TEST.is_file(), NATIVE_IMAGE_TEST)
        core = CORE_CMAKE.read_text(encoding="utf-8")
        self.assertIn("twinquill_krkr_cocos_image_decode_test", core)
        self.assertIn("EXCLUDE_FROM_ALL", core)
        self.assertIn(str(NATIVE_IMAGE_TEST.relative_to(ROOT)).replace("\\", "/"), core)
        self.assertIn("target_link_libraries(twinquill_krkr_cocos_image_decode_test", core)
        self.assertIsNotNone(
            re.search(
                r"if\s*\(\s*CMAKE_BUILD_TYPE\s+STREQUAL\s+\"Debug\"\s*\).*?"
                r"add_dependencies\(\s*twinquill_engine_krkr\s+"
                r"twinquill_krkr_cocos_image_decode_test\s*\)",
                core,
                re.DOTALL,
            )
        )
        self.assertNotRegex(
            core,
            r"if\s*\(\s*CMAKE_BUILD_TYPE\s+STREQUAL\s+\"Release\"",
        )

    def test_dynamic_link_boundary_checker_is_declared(self) -> None:
        self.assertTrue(LINK_BOUNDARY_SCRIPT.is_file(), LINK_BOUNDARY_SCRIPT)
        checker = LINK_BOUNDARY_SCRIPT.read_text(encoding="utf-8")
        self.assertRegex(checker, r"--final-so[^\n]+required=True")
        self.assertRegex(checker, r"--llvm-readelf[^\n]+required=True")
        core = CORE_CMAKE.read_text(encoding="utf-8")
        engine = ENGINE_CMAKE.read_text(encoding="utf-8")
        self.assertIn("find_package(Python3 COMPONENTS Interpreter REQUIRED)", engine)
        self.assertRegex(engine, r"if\s*\(\s*NOT\s+CMAKE_READELF\s+OR\s+NOT\s+EXISTS")
        self.assertRegex(
            engine,
            r"add_custom_command\(\s*TARGET\s+twinquill_engine_krkr\s+POST_BUILD",
        )
        self.assertIn("${Python3_EXECUTABLE}", engine)
        self.assertIn("--build-dir", engine)
        self.assertIn("--final-so", engine)
        self.assertIn("--llvm-readelf", engine)
        self.assertIn("# Every engine link runs", engine)
        self.assertIn("normal Debug engine build must", core)
        self.assertTrue(IMAGE_RUNNER_SCRIPT.is_file(), IMAGE_RUNNER_SCRIPT)

    def test_atomic_removal_preserves_raw_standard_library_bytes(self) -> None:
        engine = ENGINE_CMAKE.read_text(encoding="utf-8")
        self.assertNotIn("separate_arguments", engine)
        self.assertNotIn("list(REMOVE_ITEM", engine)
        self.assertNotIn("list(JOIN", engine)
        self.assertRegex(
            engine,
            r"string\(REGEX\s+REPLACE\s+\"\(\^\|\[ \\t\]\)-latomic"
            r"\(\[ \\t\]\|\$\)\"",
        )
        with tempfile.TemporaryDirectory(prefix="twinquill-cmake-atomic-") as temporary:
            script = Path(temporary) / "check.cmake"
            script.write_text(
                "set(INPUT \"PRE \\\"quoted spaced arg\\\" -latomic -lm POST\")\n"
                "string(REGEX REPLACE \"(^|[ \\t])-latomic([ \\t]|$)\" \"\\\\1\\\\2\" OUTPUT \"${INPUT}\")\n"
                "if(NOT OUTPUT STREQUAL \"PRE \\\"quoted spaced arg\\\"  -lm POST\")\n"
                "  message(FATAL_ERROR \"atomic token rewrite changed unrelated bytes: [${OUTPUT}]\")\n"
                "endif()\n",
                encoding="utf-8",
            )
            cmake = shutil.which("cmake")
            if cmake is None:
                sdk_cmake = Path("C:/Users/80473/AppData/Local/Android/Sdk/cmake")
                candidates = sorted(sdk_cmake.glob("*/bin/cmake.exe"))
                if not candidates:
                    self.skipTest("cmake executable is unavailable")
                cmake = str(candidates[-1])
            result = subprocess.run(
                [cmake, "-P", str(script)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_native_fixture_base64_is_unsigned_and_validated(self) -> None:
        image = NATIVE_IMAGE_TEST.read_text(encoding="utf-8")
        self.assertIn("std::uint32_t accumulator", image)
        self.assertIn("static_cast<std::uint32_t>", image)
        self.assertIn("jpeg_bytes[0] == 0xff", image)
        self.assertIn("jpeg_bytes[jpeg_size - 2U] == 0xff", image)
        self.assertIn("jpeg_bytes[jpeg_size - 1U] == 0xd9", image)

    def test_libpng_decoder_config_patch_and_scalar_boundary(self) -> None:
        self.assertTrue(LIBPNG_PATCH.is_file(), LIBPNG_PATCH)
        self.assertTrue(LIBPNG_CONFIG_SOURCE.is_file(), LIBPNG_CONFIG_SOURCE)
        with tempfile.TemporaryDirectory(prefix="twinquill-libpng-config-") as temporary:
            config_root = Path(temporary)
            config = config_root / "pnglibconf.h.prebuilt"
            shutil.copy2(LIBPNG_CONFIG_SOURCE, config)
            for check_only in (True, False):
                command = ["git", "apply"]
                if check_only:
                    command.append("--check")
                command.append(str(LIBPNG_PATCH))
                result = subprocess.run(
                    command,
                    cwd=config_root,
                    capture_output=True,
                    text=True,
                    check=False,
                )
                self.assertEqual(result.returncode, 0, result.stderr)

            patched = config.read_text(encoding="utf-8")
            self.assertNotRegex(
                patched,
                r"^#define PNG_(?:WRITE_|SIMPLIFIED_WRITE_|SAVE_INT_32_SUPPORTED)",
                patched,
            )
            self.assertIn("#define PNG_READ_SUPPORTED", patched)
            self.assertIn("#define PNG_SIMPLIFIED_READ_SUPPORTED", patched)

        dependencies = DEPENDENCIES_CMAKE.read_text(encoding="utf-8")
        self.assertIn("KRKR_LIBPNG_CONFIG_PATCH", dependencies)
        self.assertIn("PNG_ARM_NEON_OPT=0", dependencies)
        self.assertNotIn("pngwutil.c", dependencies)
        self.assertNotIn("pngwrite.c", dependencies)
        self.assertNotIn("arm/arm_init.c", dependencies)


if __name__ == "__main__":
    unittest.main()
