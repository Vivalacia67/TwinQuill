# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors

"""Focused TJS2 source-admission and patch-provenance tests."""

from __future__ import annotations

import hashlib
import re
import shutil
import subprocess
import tempfile
import tomllib
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CMAKE = ROOT / "engine-krkr" / "src" / "main" / "cpp" / "CMakeLists.txt"
TJS2_DIR = ROOT / "vendor" / "kirikiroid2" / "src" / "core" / "tjs2"
PATCH_RELATIVE = Path(
    "vendor/patches/kirikiroid2/0001-fix-tjs-vector-pop-back.patch"
)
PATCH = ROOT / PATCH_RELATIVE
PATCH_RELATIVES = (
    PATCH_RELATIVE,
    Path("vendor/patches/kirikiroid2/0002-fix-tjs-free-null.patch"),
)


class KrkrTjs2SourceAdmissionTests(unittest.TestCase):
    def test_tjs2_sources_are_explicit_and_complete(self) -> None:
        cmake = CMAKE.read_text(encoding="utf-8")
        self.assertNotRegex(cmake, r"(?is)file\s*\(\s*glob\b[^)]*tjs2")
        source_block = re.search(
            r"set\s*\(\s*TJS2_SOURCES(?P<body>.*?)\n\)",
            cmake,
            re.DOTALL,
        )
        self.assertIsNotNone(source_block)
        assert source_block is not None
        admitted = re.findall(
            r"\$\{TJS2_BUILD_DIR\}/([A-Za-z0-9_.-]+\.cpp)",
            source_block.group("body"),
        )
        expected = sorted(path.name for path in TJS2_DIR.glob("*.cpp"))
        self.assertEqual(sorted(admitted), expected)
        self.assertEqual(len(admitted), len(set(admitted)))
        self.assertIn("add_library(twinquill_krkr_tjs2 STATIC ${TJS2_SOURCES})", cmake)

    def test_tjs2_uses_generated_copy_and_numbered_patch_commands(self) -> None:
        cmake = CMAKE.read_text(encoding="utf-8")
        source_start = cmake.index("set(TJS2_SOURCES")
        patch_start = cmake.index(PATCH_RELATIVE.name)
        patch_block = cmake[patch_start:source_start]
        self.assertIn("TJS2_BUILD_DIR", patch_block)
        check = re.search(r"(?i)apply\s+--check", patch_block)
        self.assertIsNotNone(check)
        assert check is not None
        applied = re.search(r"(?i)apply(?!\s+--check)\b", patch_block[check.end() :])
        self.assertIsNotNone(applied)

    def test_inline_tjs2_patch_is_absent(self) -> None:
        cmake = CMAKE.read_text(encoding="utf-8")
        self.assertNotRegex(
            cmake,
            r"(?is)file\s*\(\s*read\s+[^)]*tjsUtils\.h.*?"
            r"string\s*\(\s*replace.*?UnusedIndexStack\.pop_back.*?"
            r"file\s*\(\s*write\s+[^)]*tjsUtils\.h",
        )

    def test_kirikiroid2_manifest_records_patch_and_digest(self) -> None:
        manifest = tomllib.loads(
            (ROOT / "third_party" / "sources.toml").read_text(encoding="utf-8")
        )
        record = next(item for item in manifest["sources"] if item["id"] == "kirikiroid2")
        patches = record.get("patches", [])
        digests = record.get("patch_sha256", [])
        for relative in PATCH_RELATIVES:
            patch_name = relative.as_posix()
            with self.subTest(patch=patch_name):
                self.assertIn(patch_name, patches)
                patch_index = patches.index(patch_name)
                self.assertEqual(
                    digests[patch_index],
                    hashlib.sha256((ROOT / relative).read_bytes()).hexdigest(),
                )

    def test_numbered_tjs2_patch_records_focused_how_tested(self) -> None:
        for relative in PATCH_RELATIVES:
            description = (ROOT / relative).read_text(encoding="utf-8").split("\n--- ", 1)[0]
            with self.subTest(patch=relative):
                self.assertRegex(
                    description,
                    r"(?im)^Tested-by:.*tests/test_krkr_tjs2_source_admission\.py"
                    r".*focused git-apply unittest.*forced Android CMake configure",
                )

    def test_numbered_tjs2_patch_applies_to_generated_layout(self) -> None:
        git = shutil.which("git")
        self.assertIsNotNone(git)
        assert git is not None
        with tempfile.TemporaryDirectory(prefix="tjs2-patch-") as temporary:
            generated_root = Path(temporary)
            generated_tjs2 = generated_root / "src" / "core" / "tjs2"
            generated_tjs2.mkdir(parents=True)
            shutil.copy2(TJS2_DIR / "tjsUtils.h", generated_tjs2 / "tjsUtils.h")
            shutil.copy2(TJS2_DIR / "tjsConfig.cpp", generated_tjs2 / "tjsConfig.cpp")
            subprocess.run(
                [git, "init", "--quiet"],
                cwd=generated_root,
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            for relative in PATCH_RELATIVES:
                with self.subTest(patch=relative):
                    result = subprocess.run(
                        [
                            git,
                            "-c",
                            "core.fsmonitor=false",
                            "apply",
                            "--check",
                            "--unsafe-paths",
                            str(ROOT / relative),
                        ],
                        cwd=generated_root,
                        check=False,
                        capture_output=True,
                        encoding="utf-8",
                    )
                    self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
