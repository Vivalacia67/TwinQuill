# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors

"""Keep the initial TVP closure and audited KAG framework pin explicit."""

from __future__ import annotations

import re
import tomllib
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class KrkrTvpSourceAdmissionTests(unittest.TestCase):
    def test_tvp_translation_units_are_explicit_and_patched(self) -> None:
        cmake = (ROOT / "engine-krkr/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
        block = re.search(r"set\(TVP_HOST_SOURCES(?P<body>.*?)\n\)", cmake, re.DOTALL)
        self.assertIsNotNone(block)
        assert block is not None
        self.assertEqual(re.findall(r'"([^"\n]+)"', block["body"]),
                         ["${TVP_TIMER_BUILD_DIR}/TimerIntf.cpp",
                          "${TVP_VISUAL_BUILD_DIR}/WindowIntf.cpp",
                          "${TVP_VISUAL_BUILD_DIR}/LayerIntf.cpp",
                          "${TVP_EVENT_BUILD_DIR}/EventIntf.cpp",
                          "${TVP_TIMER_BUILD_DIR}/KAGParser.cpp"])
        self.assertNotRegex(block["body"], r"(?i)glob|\.\.\.")
        self.assertIn("${TVP_HOST_SOURCES}", cmake)
        self.assertIn("0004-tvp-timer-interval-bounds.patch", cmake)
        self.assertIn('"${CMAKE_CURRENT_LIST_DIR}/tvp"', cmake)
        self.assertIn("TWINQUILL_ANDROID_BASIC_TVP=1", cmake)
        self.assertIn("0006-android-basic-visual-bindings.patch", cmake)
        self.assertIn("0007-tvp-async-worker-admission.patch", cmake)

    def test_standard_scene_uses_real_objects_and_audited_font(self) -> None:
        scene = (ROOT / "tests/fixtures/krkr-m2/visual/startup.tjs").read_text(encoding="utf-8")
        self.assertNotIn("TwinQuillHost.", scene)
        for operation in ("new Window()", "new Layer(", ".loadImages(", ".drawText(", "new Timer("):
            self.assertIn(operation, scene)
        gradle = (ROOT / "engine-krkr/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('rootProject.file("third_party/fonts")', gradle)
        self.assertIn("addStaticSourceDirectory", gradle)
        visual = (ROOT / "engine-krkr/src/main/cpp/krkr_tvp_visual.cpp").read_text(encoding="utf-8")
        self.assertIn("NotoSansCJKsc-Regular.otf", visual)
        self.assertIn("FT_New_Memory_Face", visual)
        self.assertIn("image.initWithImageData", visual)

    def test_m2_unsupported_operations_are_explicit_errors(self) -> None:
        patch = (ROOT / "vendor/patches/kirikiroid2/0006-android-basic-visual-bindings.patch").read_text(
            encoding="latin1")
        self.assertIn("Unsupported M2 method: showModal", patch)
        self.assertIn("Unsupported M2 property: mainImageBuffer", patch)
        self.assertIn("TJS_eTJSError", patch)

    def test_kag_framework_pin_and_license_are_recorded(self) -> None:
        configuration = tomllib.loads((ROOT / "third_party/sources.toml").read_text(encoding="utf-8"))
        source = next(item for item in configuration["sources"] if item["id"] == "kag3-1f3ab309")
        self.assertEqual(source["commit"], "1f3ab309106d210e3169bbbe0fb4e066ae463b42")
        self.assertEqual(source["tree"], "8b310028c0b481e44631771424a1ebd9fb769d38")
        self.assertEqual(source["archive_sha256"],
                         "797c21a16240f0518989d19a673036605d164831a20be00cfc1761db1a394a66")
        snapshot = ROOT / source["destination"]
        self.assertEqual(sum(path.is_file() for path in snapshot.rglob("*")), source["file_count"])
        self.assertEqual(source["file_count"], 39)
        readme = (snapshot / "README.md").read_text(encoding="utf-8")
        self.assertIn("KAG3 License", readme)
        self.assertIn("改変・配布は自由です", readme)
        initialize = (snapshot / "data/system/Initialize.tjs").read_text(encoding="utf-8")
        self.assertIn('var kagVersion = "3.32 stable rev. 2";', initialize)

    def test_framework_assets_are_complete_and_test_only(self) -> None:
        production = (ROOT / "engine-krkr/build.gradle.kts").read_text(encoding="utf-8")
        self.assertNotIn("vendor/kag3-1f3ab309", production)
        gradle = (ROOT / "launcher-app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("vendor/kag3-1f3ab309", gradle)
        self.assertIn("variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory", gradle)
        session = (ROOT / "engine-krkr/src/main/cpp/krkr_tjs_session.cpp").read_text(encoding="utf-8")
        self.assertIn('kag_host_ = resources_.exists("system/Initialize.tjs")', session)
        self.assertIn("TVPRegisterAndroidKagHost(engine_)", session)
        cmake = (ROOT / "engine-krkr/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
        self.assertNotIn("vendor/kag3-1f3ab309", cmake)

    def test_kag_parser_and_recursive_calls_share_execution_budget(self) -> None:
        parser = (ROOT / "vendor/patches/kirikiroid2/0009-android-kag-parser.patch").read_text(encoding="latin1")
        depth = (ROOT / "vendor/patches/kirikiroid2/0011-tjs-call-depth-budget.patch").read_text(encoding="latin1")
        self.assertIn("TJSCheckExecutionBudget", parser)
        self.assertIn("TJSHostCallFrame host_frame", depth)


if __name__ == "__main__":
    unittest.main()
