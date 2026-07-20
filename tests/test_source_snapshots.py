"""Tests for audited third-party source and asset metadata."""

from __future__ import annotations

import hashlib
import importlib.util
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "verify_source_snapshots",
    ROOT / "scripts" / "verify_source_snapshots.py",
)
assert SPEC is not None and SPEC.loader is not None
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class FontAssetSourceTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.destination = Path(self.temporary.name)
        self.asset = self.destination / "fallback.otf"
        self.asset.write_bytes(b"audited-font")
        (self.destination / "OFL.txt").write_text(
            "SIL Open Font License 1.1",
            encoding="utf-8",
        )
        self.source = {
            "source_type": "font-asset",
            "url": "https://example.invalid/fallback.otf",
            "version": "1.0",
            "asset_file": "fallback.otf",
            "asset_sha256": hashlib.sha256(b"audited-font").hexdigest(),
            "license": "OFL-1.1",
            "license_files": ["OFL.txt"],
        }

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_complete_font_asset_metadata_passes(self) -> None:
        VERIFY.validate_source_files("fixture-font", self.destination, self.source)

    def test_font_asset_checksum_mismatch_fails(self) -> None:
        self.source["asset_sha256"] = "0" * 64

        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            VERIFY.validate_source_files(
                "fixture-font",
                self.destination,
                self.source,
            )

    def test_missing_font_version_fails(self) -> None:
        del self.source["version"]

        with self.assertRaisesRegex(ValueError, "missing version"):
            VERIFY.validate_source_files(
                "fixture-font",
                self.destination,
                self.source,
            )

    def test_missing_license_file_fails(self) -> None:
        (self.destination / "OFL.txt").unlink()

        with self.assertRaisesRegex(FileNotFoundError, "missing license"):
            VERIFY.validate_source_files(
                "fixture-font",
                self.destination,
                self.source,
            )


if __name__ == "__main__":
    unittest.main()
