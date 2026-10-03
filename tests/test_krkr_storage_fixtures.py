# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors
"""Keep the authored M3 inputs reproducible across native and Android tests."""
import importlib.util
import json
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("m3_fixtures", ROOT/"scripts/create_krkr_m3_fixtures.py")
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)

class KrkrStorageFixtureTests(unittest.TestCase):
    def test_checked_in_fixtures_match_generator_byte_for_byte(self):
        generated = BUILDER.generated()
        self.assertGreater(len(generated), 20)
        for name, content in generated.items():
            with self.subTest(name=name):
                self.assertEqual((ROOT/"tests/fixtures/krkr-m3"/name).read_bytes(), content)

    def test_archive_matrix_covers_success_and_failure_boundaries(self):
        vectors = json.loads(BUILDER.generated()["m3-vectors/vectors.json"])
        names = {vector["file"] for vector in vectors}
        self.assertTrue({"raw.xp3", "compressed.xp3", "chained.xp3", "cycle.xp3",
            "index-bomb.xp3", "segment-bomb.xp3", "traversal.xp3",
            "protected.xp3", "bad-resource-zlib.xp3"}.issubset(names))
        self.assertTrue(any(vector["status"] == 35 and not vector["detect"] for vector in vectors))
        # Detection inspects metadata. A corrupt non-startup segment is diagnosed
        # only when the runtime actually requests that resource.
        corrupt = next(v for v in vectors if v["file"] == "bad-resource-zlib.xp3")
        self.assertTrue(corrupt["detect"])

    def test_loose_display_preserves_accepted_m2_scene(self):
        source = (ROOT/"tests/fixtures/krkr-m2/visual/startup.tjs").read_bytes()
        expected = source.replace(b"checker.png", b"images/checker.png").replace(b"sample.jpg",b"images/sample.jpg")
        self.assertEqual(BUILDER.generated()["m3-loose/startup.tjs"], expected)

if __name__ == "__main__":
    unittest.main()
