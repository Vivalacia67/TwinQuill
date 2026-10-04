#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors

"""Tests for the bounded Kirikiroid2 TJS2 source admission policy."""

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
VENDOR_TJS2 = ROOT / "vendor" / "kirikiroid2" / "src" / "core" / "tjs2"
SOURCES = ROOT / "third_party" / "sources.toml"
PATCH = ROOT / "vendor" / "patches" / "kirikiroid2" / "0001-fix-tjs-vector-pop-back.patch"


class KrkrSourceNormalizationTests(unittest.TestCase):
    def test_tjs2_admission_is_explicit_and_matches_vendor(self) -> None:
        cmake = CMAKE.read_text(encoding="utf-8")
        source_block = re.search(
            r"set\(TJS2_SOURCES(?P<body>.*?)\n\)", cmake, re.DOTALL
        )
        self.assertIsNotNone(source_block, "TJS2 source list must be explicit")
        assert source_block is not None
        admitted = sorted(Path(path).name for path in re.findall(
            r"\$\{TJS2_BUILD_DIR\}/([^\s)]+\.cpp)", source_block.group("body")
        ))
        expected = sorted(path.name for path in VENDOR_TJS2.glob("*.cpp"))
        self.assertEqual(expected, admitted)
        self.assertEqual(38, len(expected))
        self.assertNotRegex(cmake, r"file\(GLOB[^\n]*TJS2_SOURCES")

    def test_registered_patch_applies_and_preserves_two_step_pop(self) -> None:
        with SOURCES.open("rb") as source_file:
            manifest = tomllib.load(source_file)
        record = next(
            source for source in manifest["sources"] if source["id"] == "kirikiroid2"
        )
        patch_name = "vendor/patches/kirikiroid2/0001-fix-tjs-vector-pop-back.patch"
        self.assertIn(patch_name, record["patches"])
        patch_index = record["patches"].index(patch_name)
        self.assertEqual(
            record["patch_sha256"][patch_index],
            hashlib.sha256(PATCH.read_bytes()).hexdigest(),
        )

        with tempfile.TemporaryDirectory() as directory:
            generated = Path(directory) / "generated" / "kirikiroid2"
            shutil.copytree(
                ROOT / "vendor" / "kirikiroid2" / "src" / "core",
                generated / "src" / "core",
            )
            source = generated / "src" / "core" / "tjs2" / "tjsUtils.h"
            before = source.read_bytes()
            self.assertEqual(
                2,
                before.count(b"int currentIndex = UnusedIndexStack.pop_back();"),
            )
            before_back_calls = before.count(b"int currentIndex = UnusedIndexStack.back();")
            before_pop_calls = before.count(b"UnusedIndexStack.pop_back();")
            self._apply_patch(generated, check=True)
            self._apply_patch(generated)
            after = source.read_bytes()
            self.assertEqual(
                0,
                after.count(b"int currentIndex = UnusedIndexStack.pop_back();"),
            )
            self.assertEqual(
                before_back_calls + 2,
                after.count(b"int currentIndex = UnusedIndexStack.back();"),
            )
            self.assertEqual(before_pop_calls, after.count(b"UnusedIndexStack.pop_back();"))

    def test_cmake_uses_numbered_git_patch_without_string_mutation(self) -> None:
        cmake = CMAKE.read_text(encoding="utf-8")
        self.assertIn("core.fsmonitor=false apply --check", cmake)
        self.assertIn("core.fsmonitor=false apply", cmake)
        self.assertNotIn("string(REPLACE", cmake)
        reads = re.findall(r'file\(READ\s+"([^"\n]+)"', cmake)
        self.assertEqual(reads, ["${CMAKE_CURRENT_LIST_DIR}/krkr_kag_host.tjs",
                                 "${CMAKE_CURRENT_LIST_DIR}/krkr_kag_recovery.tjs"])
        self.assertIn("configure_file(krkr_kag_host_script.h.in", cmake)
        self.assertNotIn("file(WRITE", cmake)

    @staticmethod
    def _apply_patch(generated: Path, check: bool = False) -> None:
        command = ["git", "apply"]
        if check:
            command.append("--check")
        subprocess.run(
            [*command, str(PATCH)],
            cwd=generated,
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
