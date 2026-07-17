#!/usr/bin/env python3
"""Tests for the APK native library release gate."""

from __future__ import annotations

import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

from check_apk_native_libraries import REQUIRED_LIBRARIES, verify_apk


def elf64(alignment: int) -> bytes:
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<H", data, 54, 56)
    struct.pack_into("<H", data, 56, 1)
    struct.pack_into("<I", data, 64, 1)
    struct.pack_into("<Q", data, 64 + 48, alignment)
    return bytes(data)


class ApkNativeLibraryTests(unittest.TestCase):
    def make_apk(self, alignment: int = 0x4000, extra: str | None = None) -> Path:
        temporary = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
        temporary.close()
        path = Path(temporary.name)
        with zipfile.ZipFile(path, "w") as apk:
            for abi in ("arm64-v8a", "armeabi-v7a"):
                for library in REQUIRED_LIBRARIES:
                    apk.writestr(f"lib/{abi}/{library}", elf64(alignment))
                if extra is not None:
                    apk.writestr(f"lib/{abi}/{extra}", elf64(alignment))
        self.addCleanup(path.unlink, missing_ok=True)
        return path

    def test_expected_libraries_and_alignment_pass(self) -> None:
        self.assertEqual([], verify_apk(self.make_apk()))

    def test_generic_oniguruma_library_fails(self) -> None:
        failures = verify_apk(self.make_apk(extra="libonig.so"))
        self.assertTrue(any("forbidden native library" in failure for failure in failures))

    def test_four_kilobyte_load_alignment_fails(self) -> None:
        failures = verify_apk(self.make_apk(alignment=0x1000))
        self.assertTrue(any("below 0x4000" in failure for failure in failures))


if __name__ == "__main__":
    unittest.main()
