# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors

"""Prevent Git text filters from corrupting authored binary fixtures."""

from pathlib import Path
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]


class FixtureAssetCheckoutTests(unittest.TestCase):
    def test_fixture_binaries_keep_identical_bytes_through_git_filters(self):
        git = shutil.which("git")
        self.assertIsNotNone(git)
        images = sorted((ROOT / "tests/fixtures").rglob("*.png"))
        images += sorted((ROOT / "tests/fixtures").rglob("*.jpg"))
        images += sorted((ROOT / "tests/fixtures").rglob("*.xp3"))
        images += sorted((ROOT / "tests/fixtures").rglob("*.bin"))
        self.assertGreaterEqual(len(images), 2)
        for image in images:
            with self.subTest(image=image.relative_to(ROOT)):
                raw = subprocess.check_output([git, "hash-object", "--no-filters", str(image)], cwd=ROOT)
                filtered = subprocess.check_output(
                    [git, "hash-object", "--path=" + image.relative_to(ROOT).as_posix(), str(image)], cwd=ROOT)
                self.assertEqual(raw, filtered, "Git must preserve fixture binary bytes on checkout")


if __name__ == "__main__":
    unittest.main()
