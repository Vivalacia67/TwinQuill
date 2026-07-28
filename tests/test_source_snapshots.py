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


class IncludedPathSourceTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.destination = Path(self.temporary.name)
        (self.destination / "src").mkdir()
        (self.destination / "src" / "math.cpp").write_text(
            "fixture",
            encoding="utf-8",
        )
        (self.destination / "LICENSE").write_text("license", encoding="utf-8")
        self.source = {
            "license_files": ["LICENSE"],
            "included_paths": ["src/**", "LICENSE"],
        }

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_complete_included_paths_pass(self) -> None:
        files = VERIFY.snapshot_files("fixture", self.destination, self.source)

        self.assertEqual(
            {"LICENSE", "src/math.cpp"},
            {path.relative_to(self.destination).as_posix() for path in files},
        )

    def test_source_without_included_paths_remains_valid(self) -> None:
        del self.source["included_paths"]

        files = VERIFY.snapshot_files("fixture", self.destination, self.source)

        self.assertEqual(2, len(files))

    def test_empty_included_paths_fails(self) -> None:
        self.source["included_paths"] = []

        with self.assertRaisesRegex(ValueError, "invalid included paths"):
            VERIFY.snapshot_files("fixture", self.destination, self.source)

    def test_unsafe_included_path_pattern_fails(self) -> None:
        for pattern in ("", "../outside", "..\\outside", "/outside", "C:/outside"):
            with self.subTest(pattern=pattern):
                self.source["included_paths"] = [pattern]
                with self.assertRaisesRegex(ValueError, "unsafe included path pattern"):
                    VERIFY.snapshot_files("fixture", self.destination, self.source)

    def test_included_path_pattern_must_match_a_file(self) -> None:
        self.source["included_paths"] = ["src/**", "missing.txt"]

        with self.assertRaisesRegex(ValueError, "matches no files"):
            VERIFY.snapshot_files("fixture", self.destination, self.source)

    def test_uncovered_snapshot_file_fails(self) -> None:
        self.source["included_paths"] = ["src/**"]

        with self.assertRaisesRegex(ValueError, "not covered"):
            VERIFY.snapshot_files("fixture", self.destination, self.source)


class RepositoryLicenseSourceTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.license_path = self.root / "third_party" / "licenses" / "Apache-2.0.txt"
        self.license_path.parent.mkdir(parents=True)
        self.license_path.write_text("Apache License 2.0", encoding="utf-8")
        self.source = {
            "repository_license_files": [
                "third_party/licenses/Apache-2.0.txt",
            ],
        }

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_existing_repository_license_file_passes(self) -> None:
        VERIFY.validate_repository_license_files(self.root, "fixture", self.source)

    def test_missing_repository_license_file_fails(self) -> None:
        self.license_path.unlink()

        with self.assertRaisesRegex(FileNotFoundError, "missing repository license"):
            VERIFY.validate_repository_license_files(self.root, "fixture", self.source)

    def test_unsafe_repository_license_path_fails(self) -> None:
        for relative in ("../LICENSE", "..\\LICENSE", "/LICENSE", "C:/LICENSE"):
            with self.subTest(relative=relative):
                self.source["repository_license_files"] = [relative]
                with self.assertRaisesRegex(ValueError, "unsafe repository license path"):
                    VERIFY.validate_repository_license_files(
                        self.root,
                        "fixture",
                        self.source,
                    )

    def test_empty_repository_license_files_fails(self) -> None:
        self.source["repository_license_files"] = []

        with self.assertRaisesRegex(ValueError, "invalid repository license files"):
            VERIFY.validate_repository_license_files(self.root, "fixture", self.source)


if __name__ == "__main__":
    unittest.main()
