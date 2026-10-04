# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors
"""Audit the full framework and independent identities used by save fixtures."""
import unittest

from scripts import create_krkr_m5_fixtures as builder


class KrkrKagSaveFixtureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.entries = builder.game_entries()

    def test_save_games_retain_the_pinned_framework_bytes(self):
        entries = self.entries
        original = builder.m4.FRAMEWORK / "data"
        for path in original.rglob("*"):
            if not path.is_file():
                continue
            name = path.relative_to(original).as_posix()
            if name in {"system/Config.tjs", "scenario/first.ks"}:
                continue
            with self.subTest(path=name):
                self.assertEqual(entries[name], path.read_bytes())
        self.assertEqual(entries["KAG3-LICENSE.md"], (builder.m4.FRAMEWORK / "README.md").read_bytes())

    def test_games_enable_plain_slots_and_distinct_format_identities(self):
        config = self.entries["system/Config.tjs"].decode("utf-8")
        configs = [config.replace(builder.SAVE_IDS[0], identity) for identity in builder.SAVE_IDS]
        self.assertNotEqual(configs[0], configs[1])
        for config, identity in zip(configs, builder.SAVE_IDS):
            self.assertIn(identity, config)
            for setting in (';readOnlyMode = false;', ';saveThumbnail = false;',
                            ';saveDataMode = "";', ';numBookMarks = 2;', ';saveMacros = true;',
                            ';autoRecordPageShowing = true;'):
                self.assertIn(setting, config)


if __name__ == "__main__":
    unittest.main()
