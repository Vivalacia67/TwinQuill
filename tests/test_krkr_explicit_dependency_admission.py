# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors

"""Admission tests for direct, source-pinned Krkr dependency targets."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEPENDENCIES = ROOT / "engine-krkr/src/main/cpp/krkr_dependencies.cmake"
ENGINE = ROOT / "engine-krkr/src/main/cpp/CMakeLists.txt"
CORE = ROOT / "engine-krkr/src/main/cpp/krkr_cocos_core.cmake"
FREETYPE = ROOT / "vendor/deps/krkr/freetype-2.14.3"
LIBJPEG = ROOT / "vendor/deps/krkr/libjpeg-turbo-3.1.4.1"

EXPECTED_FREETYPE_SOURCES = """
src/autofit/autofit.c src/base/ftbase.c src/base/ftbbox.c src/base/ftbdf.c
src/base/ftbitmap.c src/base/ftcid.c src/base/ftfstype.c src/base/ftgasp.c
src/base/ftglyph.c src/base/ftgxval.c src/base/ftinit.c src/base/ftmm.c
src/base/ftotval.c src/base/ftpatent.c src/base/ftpfr.c src/base/ftstroke.c
src/base/ftsynth.c src/base/fttype1.c src/base/ftwinfnt.c src/bdf/bdf.c
src/bzip2/ftbzip2.c src/cache/ftcache.c src/cff/cff.c src/cid/type1cid.c
src/gzip/ftgzip.c src/lzw/ftlzw.c src/pcf/pcf.c src/pfr/pfr.c
src/psaux/psaux.c src/pshinter/pshinter.c src/psnames/psnames.c
src/raster/raster.c src/sdf/sdf.c src/sfnt/sfnt.c src/smooth/smooth.c
src/svg/svg.c src/truetype/truetype.c src/type1/type1.c src/type42/type42.c
src/winfonts/winfnt.c builds/unix/ftsystem.c src/base/ftdebug.c
""".split()

EXPECTED_LIBJPEG_SOURCES = """
src/jcapimin.c src/wrapper/jcapistd-8.c src/wrapper/jcapistd-12.c
src/wrapper/jcapistd-16.c src/wrapper/jccoefct-8.c src/wrapper/jccoefct-12.c
src/wrapper/jccolor-8.c src/wrapper/jccolor-12.c src/wrapper/jccolor-16.c
src/wrapper/jcdctmgr-8.c src/wrapper/jcdctmgr-12.c
src/wrapper/jcdiffct-8.c src/wrapper/jcdiffct-12.c src/wrapper/jcdiffct-16.c
src/jchuff.c src/jcicc.c src/jcinit.c src/jclhuff.c
src/wrapper/jclossls-8.c src/wrapper/jclossls-12.c src/wrapper/jclossls-16.c
src/wrapper/jcmainct-8.c src/wrapper/jcmainct-12.c src/wrapper/jcmainct-16.c
src/jcmarker.c src/jcmaster.c src/jcomapi.c src/jcparam.c src/jcphuff.c
src/wrapper/jcprepct-8.c src/wrapper/jcprepct-12.c src/wrapper/jcprepct-16.c
src/wrapper/jcsample-8.c src/wrapper/jcsample-12.c src/wrapper/jcsample-16.c
src/jctrans.c src/jdapimin.c src/wrapper/jdapistd-8.c
src/wrapper/jdapistd-12.c src/wrapper/jdapistd-16.c src/jdatadst.c src/jdatasrc.c
src/wrapper/jdcoefct-8.c src/wrapper/jdcoefct-12.c src/wrapper/jdcolor-8.c
src/wrapper/jdcolor-12.c src/wrapper/jdcolor-16.c src/wrapper/jddctmgr-8.c
src/wrapper/jddctmgr-12.c src/wrapper/jddiffct-8.c src/wrapper/jddiffct-12.c
src/wrapper/jddiffct-16.c src/jdhuff.c src/jdicc.c src/jdinput.c src/jdlhuff.c
src/wrapper/jdlossls-8.c src/wrapper/jdlossls-12.c src/wrapper/jdlossls-16.c
src/wrapper/jdmainct-8.c src/wrapper/jdmainct-12.c src/wrapper/jdmainct-16.c
src/jdmarker.c src/jdmaster.c src/wrapper/jdmerge-8.c src/wrapper/jdmerge-12.c
src/jdphuff.c src/wrapper/jdpostct-8.c src/wrapper/jdpostct-12.c
src/wrapper/jdpostct-16.c src/wrapper/jdsample-8.c src/wrapper/jdsample-12.c
src/wrapper/jdsample-16.c src/jdtrans.c src/jerror.c src/jfdctflt.c
src/wrapper/jfdctfst-8.c src/wrapper/jfdctfst-12.c src/wrapper/jfdctint-8.c
src/wrapper/jfdctint-12.c src/wrapper/jidctflt-8.c src/wrapper/jidctflt-12.c
src/wrapper/jidctfst-8.c src/wrapper/jidctfst-12.c src/wrapper/jidctint-8.c
src/wrapper/jidctint-12.c src/wrapper/jidctred-8.c src/wrapper/jidctred-12.c
src/jmemmgr.c src/jmemnobs.c src/jpeg_nbits.c src/wrapper/jquant1-8.c
src/wrapper/jquant1-12.c src/wrapper/jquant2-8.c src/wrapper/jquant2-12.c
src/wrapper/jutils-8.c src/wrapper/jutils-12.c src/wrapper/jutils-16.c
""".split()


def cmake_sources(text: str, variable: str, directory: str) -> list[str]:
    match = re.search(rf"set\({variable}(?P<body>.*?)\n\)", text, re.DOTALL)
    if match is None:
        return []
    prefix = f"${{{directory}_DIR}}/"
    return [value.removeprefix(prefix) for value in re.findall(r'"([^"]+)"', match.group("body"))]


def block(text: str, command: str, target: str) -> str:
    match = re.search(
        rf"{command}\({re.escape(target)}(?P<body>.*?)\s*\)",
        text,
        re.DOTALL,
    )
    return "" if match is None else match.group("body")


class KrkrExplicitDependencyAdmissionTests(unittest.TestCase):
    def test_exact_ordered_source_lists_and_snapshot_membership(self) -> None:
        dependencies = DEPENDENCIES.read_text(encoding="utf-8")
        actual_freetype = cmake_sources(dependencies, "KRKR_FREETYPE_SOURCES", "KRKR_FREETYPE")
        actual_libjpeg = cmake_sources(dependencies, "KRKR_LIBJPEG_SOURCES", "KRKR_LIBJPEG")
        self.assertEqual(actual_freetype, EXPECTED_FREETYPE_SOURCES)
        self.assertEqual(actual_libjpeg, EXPECTED_LIBJPEG_SOURCES)
        self.assertTrue(all((FREETYPE / path).is_file() for path in actual_freetype))
        self.assertTrue(all((LIBJPEG / path).is_file() for path in actual_libjpeg))

    def test_aggregate_and_archive_admission_is_absent(self) -> None:
        dependencies = DEPENDENCIES.read_text(encoding="utf-8")
        self.assertNotRegex(
            dependencies,
            r"(?i)\b(?:add_subdirectory|ExternalProject_Add|jpeg-static|"
            r"BUILD_BYPRODUCTS|IMPORTED_LOCATION|IMPORTED)\b",
        )
        self.assertNotRegex(dependencies, r"(?i)(?:libjpeg|freetype)[^\n]*\.(?:a|so)\b")

    def test_generated_headers_and_compile_contracts_are_explicit(self) -> None:
        dependencies = DEPENDENCIES.read_text(encoding="utf-8")
        engine = ENGINE.read_text(encoding="utf-8")
        self.assertIn("check_include_file", dependencies)
        self.assertIn("ftconfig.h.in", dependencies)
        self.assertIn("ftoption.h", dependencies)
        self.assertIn("FT2_BUILD_LIBRARY", dependencies)
        self.assertIn("C_VISIBILITY_PRESET hidden", dependencies)
        self.assertIn("check_type_size", dependencies)
        self.assertIn("check_c_source_compiles", dependencies)
        self.assertIn("check_c_source_runs", dependencies)
        for header in ("jconfig.h.in", "jconfigint.h.in", "jversion.h.in"):
            self.assertIn(header, dependencies)
        freetype_includes = block(dependencies, "target_include_directories", "twinquill_krkr_freetype")
        jpeg_includes = block(dependencies, "target_include_directories", "twinquill_krkr_libjpeg")
        self.assertLess(
            freetype_includes.index("KRKR_FREETYPE_GENERATED_INCLUDE_DIR"),
            freetype_includes.index("KRKR_FREETYPE_DIR"),
        )
        self.assertLess(
            jpeg_includes.index("KRKR_LIBJPEG_GENERATED_DIR"),
            jpeg_includes.index("KRKR_LIBJPEG_DIR"),
        )
        self.assertIn("CMAKE_POSITION_INDEPENDENT_CODE ON", engine)

    def test_duplicate_configure_file_inputs_do_not_generate_multiple_ninja_rules(self) -> None:
        dependencies = DEPENDENCIES.read_text(encoding="utf-8")
        configure_file_inputs = set(
            re.findall(r"configure_file\(\s*\"([^\"]+)\"", dependencies, re.DOTALL)
        )
        manual_dependency_inputs = set(
            input_path
            for body in re.findall(
                r"set_property\(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS(?P<body>.*?)\)",
                dependencies,
                re.DOTALL,
            )
            for input_path in re.findall(r'"([^\"]+)"', body)
        )
        overlapping_inputs = sorted(configure_file_inputs & manual_dependency_inputs)
        overlapping_templates = [Path(input_path).name for input_path in overlapping_inputs]
        self.assertEqual(
            overlapping_inputs,
            [],
            "configure_file inputs are manually registered too and can create duplicate Ninja rules: "
            + ", ".join(overlapping_templates),
        )
        self.assertIn(
            "${KRKR_FREETYPE_DIR}/builds/unix/ftconfig.h.in",
            manual_dependency_inputs,
            "ftconfig.h.in is consumed with file(READ) and must remain manually tracked",
        )

    def test_forbidden_libjpeg_features_and_stale_targets_are_absent(self) -> None:
        dependencies = DEPENDENCIES.read_text(encoding="utf-8")
        jpeg_sources = block(dependencies, "set", "KRKR_LIBJPEG_SOURCES")
        for token in ("jaricom.c", "jcarith.c", "jdarith.c", "/simd/", "turbojpeg", "/tools/", "/tests/", "/fuzz/", "/java/"):
            self.assertNotIn(token, jpeg_sources.lower())
        self.assertNotIn("FT_CONFIG_OPTION_SYSTEM_ZLIB", dependencies)
        self.assertNotRegex(dependencies, r"(?i)ANDROID_ABI.*(?:SIZE_T|SIZEOF_SIZE_T)")
        engine = ENGINE.read_text(encoding="utf-8")
        core = CORE.read_text(encoding="utf-8")
        for text in (engine, core):
            self.assertIn("twinquill_krkr_freetype", text)
            self.assertIn("twinquill_krkr_libjpeg", text)
            self.assertNotIn("twinquill_krkr_libjpeg_static", text)
        self.assertNotRegex(core, r"(?m)^\s*freetype\s*$")


if __name__ == "__main__":
    unittest.main()
