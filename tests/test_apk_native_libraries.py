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


def elf_fixture(elf_class: int, machine: int, alignment: int) -> bytes:
    if elf_class == 2:
        data = bytearray(128)
        data[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", data, 18, machine)
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<H", data, 54, 56)
        struct.pack_into("<H", data, 56, 1)
        struct.pack_into("<I", data, 64, 1)
        struct.pack_into("<Q", data, 64 + 48, alignment)
        return bytes(data)
    if elf_class == 1:
        data = bytearray(84)
        data[:6] = b"\x7fELF\x01\x01"
        struct.pack_into("<H", data, 18, machine)
        struct.pack_into("<I", data, 28, 52)
        struct.pack_into("<H", data, 42, 32)
        struct.pack_into("<H", data, 44, 1)
        struct.pack_into("<I", data, 52, 1)
        struct.pack_into("<I", data, 52 + 28, alignment)
        return bytes(data)
    raise ValueError(f"unsupported fixture ELF class: {elf_class}")


class ApkNativeLibraryTests(unittest.TestCase):
    def make_apk(
        self,
        alignment: int = 0x4000,
        extra: str | None = None,
        overrides: dict[tuple[str, str], int] | None = None,
        identity_overrides: dict[tuple[str, str], tuple[int, int]] | None = None,
    ) -> Path:
        temporary = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
        temporary.close()
        path = Path(temporary.name)
        with zipfile.ZipFile(path, "w") as apk:
            for abi in ("arm64-v8a", "armeabi-v7a"):
                for library in REQUIRED_LIBRARIES:
                    value = (overrides or {}).get((abi, library), alignment)
                    default_identity = (2, 183) if abi == "arm64-v8a" else (1, 40)
                    elf_class, machine = (identity_overrides or {}).get(
                        (abi, library), default_identity
                    )
                    apk.writestr(
                        f"lib/{abi}/{library}",
                        elf_fixture(elf_class, machine, value),
                    )
                if extra is not None:
                    elf_class, machine = (2, 183) if abi == "arm64-v8a" else (1, 40)
                    apk.writestr(
                        f"lib/{abi}/{extra}",
                        elf_fixture(elf_class, machine, alignment),
                    )
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

    def test_armeabi_v7a_libcxx_4k_alignment_allowed(self) -> None:
        apk = self.make_apk(
            overrides={("armeabi-v7a", "libc++_shared.so"): 0x1000}
        )
        self.assertEqual([], verify_apk(apk))

    def test_arm64_libcxx_4k_alignment_fails(self) -> None:
        apk = self.make_apk(
            overrides={("arm64-v8a", "libc++_shared.so"): 0x1000}
        )
        failures = verify_apk(apk)
        self.assertTrue(any("arm64-v8a/libc++_shared.so" in failure for failure in failures))

    def test_other_armeabi_v7a_library_4k_alignment_fails(self) -> None:
        apk = self.make_apk(
            overrides={("armeabi-v7a", "libtwinquill_engine_krkr.so"): 0x1000}
        )
        failures = verify_apk(apk)
        self.assertTrue(
            any("armeabi-v7a/libtwinquill_engine_krkr.so" in failure for failure in failures)
        )

    def test_wrong_elf_identity_fails(self) -> None:
        apk = self.make_apk(
            identity_overrides={("arm64-v8a", "libtwinquill_engine_krkr.so"): (1, 40)}
        )
        failures = verify_apk(apk)
        self.assertTrue(any("ELF identity" in failure for failure in failures))

    def test_wrong_elf_machine_fails(self) -> None:
        apk = self.make_apk(
            identity_overrides={("armeabi-v7a", "libtwinquill_engine_krkr.so"): (1, 183)}
        )
        failures = verify_apk(apk)
        self.assertTrue(any("ELF identity" in failure for failure in failures))

    def test_missing_libcxx_shared_fails(self) -> None:
        apk = self.make_apk()
        rewritten = Path(str(apk) + ".missing-libcxx.apk")
        self.addCleanup(rewritten.unlink, missing_ok=True)
        with zipfile.ZipFile(apk) as source, zipfile.ZipFile(rewritten, "w") as target:
            for entry in source.infolist():
                if (
                    entry.filename.endswith("/libc++_shared.so")
                    and entry.filename.startswith("lib/arm64-v8a/")
                ):
                    continue
                target.writestr(entry, source.read(entry))
        failures = verify_apk(rewritten)
        self.assertTrue(any("arm64-v8a missing libraries" in failure for failure in failures))


if __name__ == "__main__":
    unittest.main()
