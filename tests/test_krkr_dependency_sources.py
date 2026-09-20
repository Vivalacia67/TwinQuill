# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors

"""Focused admission and dependency-boundary tests for modern Krkr sources."""

from __future__ import annotations

import re
import tomllib
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "third_party" / "sources.toml"
DEPENDENCIES_CMAKE = ROOT / "engine-krkr" / "src" / "main" / "cpp" / "krkr_dependencies.cmake"
ENGINE_CMAKE = ROOT / "engine-krkr" / "src" / "main" / "cpp" / "CMakeLists.txt"
CORE_CMAKE = ROOT / "engine-krkr" / "src" / "main" / "cpp" / "krkr_cocos_core.cmake"


EXPECTED = {
    "freetype-2.14.3-krkr": {
        "destination": "vendor/deps/krkr/freetype-2.14.3",
        "archive_sha256": "bd37baed5d6a1f2b8c26993d1e06618b41fa384e6f183e78460dae31435fe840",
        "license": "FTL",
        "license_files": ["LICENSE.TXT", "docs/FTL.TXT"],
        "file_count": 744,
    },
    "libpng-1.6.58-krkr": {
        "destination": "vendor/deps/krkr/libpng-1.6.58",
        "archive_sha256": "8c9b05b675ca7301a458df2c2e46f26e1d41ff36b8863f8c33530bc58c2e6225",
        "license": "libpng-2.0",
        "license_files": ["LICENSE"],
        "file_count": 610,
    },
    "zlib-1.3.2-krkr": {
        "destination": "vendor/deps/krkr/zlib-1.3.2",
        "archive_sha256": "bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16",
        "license": "Zlib",
        "license_files": ["LICENSE"],
        "file_count": 254,
    },
    "libjpeg-turbo-3.1.4.1-krkr": {
        "destination": "vendor/deps/krkr/libjpeg-turbo-3.1.4.1",
        "archive_sha256": "ecae8008e2cc9ade2f2c1bb9d5e6d4fb73e7c433866a056bd82980741571a022",
        "license": "LicenseRef-libjpeg-turbo-composite",
        "license_files": ["LICENSE.md", "README.ijg"],
        "file_count": 633,
    },
}


def source_records() -> dict[str, dict[str, object]]:
    parsed = tomllib.loads(MANIFEST.read_text(encoding="utf-8"))
    return {record["id"]: record for record in parsed["sources"]}


class KrkrDependencySourceTests(unittest.TestCase):
    def test_manifest_identities_and_licenses(self) -> None:
        records = source_records()
        for source_id, expected in EXPECTED.items():
            with self.subTest(source_id=source_id):
                record = records[source_id]
                for field in ("destination", "archive_sha256", "license", "license_files"):
                    self.assertEqual(record[field], expected[field])
                destination = ROOT / str(record["destination"])
                self.assertTrue(destination.is_dir())
                self.assertEqual(
                    sum(path.is_file() for path in destination.rglob("*")),
                    expected["file_count"],
                )
                for relative in record["license_files"]:
                    self.assertTrue((destination / relative).is_file())

        freetype = records["freetype-2.14.3-krkr"]
        self.assertEqual(freetype["tag"], "VER-2-14-3")
        self.assertEqual(
            freetype["tag_object"],
            "c740f0fda4274d6ffd2e5b64a25b06ef69803a07",
        )
        self.assertEqual(
            freetype["commit"],
            "0a0221a1347e2f1e07c395263540026e9a0aa7c7",
        )
        self.assertEqual(
            freetype["tree"],
            "589225074ab1eb876682820c482069693c251e88",
        )

    def test_snapshots_have_no_links_nested_git_or_native_binaries(self) -> None:
        native_suffixes = {".a", ".dll", ".dylib", ".lib", ".o", ".obj", ".so"}
        for expected in EXPECTED.values():
            destination = ROOT / str(expected["destination"])
            for path in destination.rglob("*"):
                self.assertFalse(path.is_symlink(), path)
                self.assertNotIn(".git", path.parts, path)
                if path.is_file():
                    self.assertNotIn(path.suffix.lower(), native_suffixes, path)

    def test_dependency_targets_link_transitively_without_standalone_runtime_so(self) -> None:
        engine = ENGINE_CMAKE.read_text(encoding="utf-8")
        dependencies = DEPENDENCIES_CMAKE.read_text(encoding="utf-8")
        core = CORE_CMAKE.read_text(encoding="utf-8")
        self.assertIn("include(krkr_dependencies.cmake)", engine)
        dependency_block = re.search(
            r"add_dependencies\(twinquill_engine_krkr(?P<body>.*?)\n\)",
            engine,
            re.DOTALL,
        )
        self.assertIsNotNone(dependency_block)
        assert dependency_block is not None
        for target in (
            "twinquill_krkr_zlib",
            "twinquill_krkr_libpng",
            "twinquill_krkr_freetype",
            "twinquill_krkr_libjpeg",
        ):
            self.assertIn(target, dependency_block.group("body"))

        final_link = re.search(
            r"target_link_libraries\(twinquill_engine_krkr(?P<body>.*?)\n\)",
            engine,
            re.DOTALL,
        )
        self.assertIsNotNone(final_link)
        assert final_link is not None
        self.assertNotRegex(
            final_link.group("body"),
            r"twinquill_krkr_(?:zlib|libpng|libjpeg)|\bfreetype\b",
        )
        core_link = re.search(
            r"target_link_libraries\(twinquill_krkr_cocos_core(?P<body>.*?)\n\)",
            core,
            re.DOTALL,
        )
        self.assertIsNotNone(core_link)
        assert core_link is not None
        for target in (
            "twinquill_krkr_zlib",
            "twinquill_krkr_libpng",
            "twinquill_krkr_freetype",
            "twinquill_krkr_libjpeg",
        ):
            self.assertIn(target, core_link.group("body"))
        self.assertIn("linked transitively", dependencies)
        self.assertNotRegex(dependencies, r"(?i)compile-only|never linked|never links")
        # The dependency targets are static and do not emit standalone runtime
        # .so files; their approved archives are consumed by the Cocos core link.
        self.assertNotIn("target_link_libraries(twinquill_engine_krkr", dependencies)

    def test_direct_static_targets_replace_aggregate_admission(self) -> None:
        dependencies = DEPENDENCIES_CMAKE.read_text(encoding="utf-8")
        for target in ("twinquill_krkr_freetype", "twinquill_krkr_libjpeg"):
            self.assertRegex(dependencies, rf"add_library\({target} STATIC")
        for forbidden in (
            "add_subdirectory(",
            "ExternalProject_Add(",
            "jpeg-static",
            "BUILD_BYPRODUCTS",
            "IMPORTED_LOCATION",
            "IMPORTED",
        ):
            self.assertNotIn(forbidden, dependencies)
        self.assertIn("check_type_size", dependencies)
        self.assertIn("check_c_source_compiles", dependencies)
        self.assertIn("check_c_source_runs", dependencies)
        self.assertNotIn("find_package(", dependencies)
        self.assertNotIn("PNG_HARDWARE_OPTIMIZATIONS", dependencies)
        self.assertNotIn("PNG_NO_WRITE_SUPPORTED", dependencies)
        for writer_source in ("pngwrite.c", "pngwio.c", "pngwtran.c", "pngwutil.c"):
            self.assertNotIn(writer_source, dependencies)


if __name__ == "__main__":
    unittest.main()
