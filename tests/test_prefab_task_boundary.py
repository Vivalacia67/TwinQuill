# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILD_SCRIPT = ROOT / "native-vfs" / "build.gradle.kts"
CMAKE_SCRIPT = ROOT / "native-vfs" / "src" / "main" / "cpp" / "CMakeLists.txt"
EXPECTED = {
    "prefabDebugConfigurePackage": "externalNativeBuildDebug",
    "prefabReleaseConfigurePackage": "externalNativeBuildRelease",
}


class PrefabTaskBoundaryTests(unittest.TestCase):
    def setUp(self) -> None:
        self.script = BUILD_SCRIPT.read_text(encoding="utf-8")

    def _task_body(self, task_name: str) -> str:
        marker = f'tasks.named("{task_name}").configure {{'
        start = self.script.index(marker) + len(marker)
        depth = 1
        for index in range(start, len(self.script)):
            if self.script[index] == "{":
                depth += 1
            elif self.script[index] == "}":
                depth -= 1
                if depth == 0:
                    return self.script[start:index]
        self.fail(f"unclosed task configuration: {task_name}")

    def _prefab_module_body(self) -> str:
        marker = 'create("twinquill_native_vfs") {'
        start = self.script.index(marker) + len(marker)
        depth = 1
        for index in range(start, len(self.script)):
            if self.script[index] == "{":
                depth += 1
            elif self.script[index] == "}":
                depth -= 1
                if depth == 0:
                    return self.script[start:index]
        self.fail("unclosed Prefab module configuration")

    def test_boundary_is_registered_after_evaluation(self) -> None:
        self.assertEqual(self.script.count("afterEvaluate {"), 1)
        self.assertNotIn("getByName", self.script)
        self.assertNotIn("engine-krkr", self.script)

    def test_exact_lazy_variant_provider_mappings(self) -> None:
        for variant in ("Debug", "Release"):
            producer = f"externalNativeBuild{variant}"
            prefab = f"prefab{variant}ConfigurePackage"
            provider_match = re.search(
                rf'val\s+(?P<provider>[A-Za-z_]\w*)\s*=\s*'
                rf'tasks\.named\("{producer}"\)',
                self.script,
            )
            self.assertIsNotNone(provider_match, producer)
            assert provider_match is not None
            provider = provider_match.group("provider")
            self.assertRegex(
                self._task_body(prefab),
                rf"dependsOn\(\s*{provider}\s*\)",
            )

    def test_prefab_inputs_track_matching_native_provider(self) -> None:
        for variant in ("Debug", "Release"):
            producer = f"externalNativeBuild{variant}"
            prefab = f"prefab{variant}ConfigurePackage"
            provider_match = re.search(
                rf'val\s+(?P<provider>[A-Za-z_]\w*)\s*=\s*'
                rf'tasks\.named\("{producer}"\)',
                self.script,
            )
            self.assertIsNotNone(provider_match, producer)
            assert provider_match is not None
            provider = provider_match.group("provider")
            self.assertRegex(
                self._task_body(prefab),
                rf"inputs\.files\(\s*{provider}\s*\)",
            )

    def test_prefab_module_name_matches_shared_library_target(self) -> None:
        body = self._prefab_module_body()
        cmake_script = CMAKE_SCRIPT.read_text(encoding="utf-8")
        prefab_match = re.search(
            r'create\("(?P<name>[^"]+)"\)\s*\{',
            self.script,
        )
        target_match = re.search(
            r"add_library\(\s*(?P<name>[A-Za-z_]\w*)\s+SHARED\b",
            cmake_script,
        )
        self.assertIsNotNone(prefab_match)
        self.assertIsNotNone(target_match)
        assert prefab_match is not None
        assert target_match is not None
        self.assertEqual(prefab_match.group("name"), target_match.group("name"))
        library_matches = re.findall(
            r'^\s*libraryName\s*=\s*"(?P<name>[^"]+)"\s*$',
            body,
            flags=re.MULTILINE,
        )
        self.assertEqual(library_matches, ["libtwinquill_native_vfs"])
        self.assertEqual(library_matches, [f"lib{target_match.group('name')}"])

    def test_prefab_refresh_is_bounded_to_configuration_tasks(self) -> None:
        self.assertEqual(self.script.count("outputs.upToDateWhen { false }"), 2)
        for prefab in EXPECTED:
            body = self._task_body(prefab)
            self.assertEqual(body.count("outputs.upToDateWhen { false }"), 1)

    def test_only_two_prefab_producer_edges_exist(self) -> None:
        prefabs = re.findall(
            r'tasks\.named\("(prefab(?:Debug|Release)ConfigurePackage)"\)',
            self.script,
        )
        producers = re.findall(
            r'tasks\.named\("(externalNativeBuild(?:Debug|Release))"\)',
            self.script,
        )
        self.assertEqual(sorted(prefabs), sorted(EXPECTED))
        self.assertEqual(sorted(producers), sorted(EXPECTED.values()))
        self.assertEqual(self.script.count("dependsOn("), 2)

    def test_debug_and_release_have_symmetric_wiring(self) -> None:
        for variant in ("Debug", "Release"):
            provider_match = re.search(
                re.compile(
                    rf'val\s+(?P<provider>[A-Za-z_]\w*)\s*=\s*'
                    rf'tasks\.named\("externalNativeBuild{variant}"\)'
                ),
                self.script,
            )
            self.assertIsNotNone(provider_match, variant)
            assert provider_match is not None
            provider = provider_match.group("provider")
            prefab = f"prefab{variant}ConfigurePackage"
            body = self._task_body(prefab)
            self.assertRegex(body, rf"dependsOn\(\s*{provider}\s*\)")
            self.assertRegex(body, rf"inputs\.files\(\s*{provider}\s*\)")


if __name__ == "__main__":
    unittest.main()
