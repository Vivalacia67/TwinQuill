# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors

"""Unit tests for the explicit adb native decoder runner."""

from __future__ import annotations

import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from io import StringIO
from pathlib import Path
from unittest.mock import call, patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from run_krkr_cocos_image_decode_test import (  # noqa: E402
    remote_path_for_abi,
    remote_paths_for_abi,
    run_decoder_test,
)


class KrkrCocosImageDecodeRunnerTests(unittest.TestCase):
    def _files(self, libcxx_name: str = "libc++_shared.so"):
        temporary = tempfile.TemporaryDirectory(prefix="twinquill-runner-")
        root = Path(temporary.name)
        binary = root / "decoder"
        libcxx = root / libcxx_name
        binary.write_bytes(b"decoder")
        libcxx.write_bytes(b"libcxx")
        return temporary, binary, libcxx

    def test_success_push_chmod_exec_and_cleanup(self) -> None:
        temporary, binary, libcxx = self._files()
        try:
            with patch("run_krkr_cocos_image_decode_test.subprocess.run") as run:
                run.side_effect = [type("Result", (), {"returncode": 0})() for _ in range(8)]
                self.assertEqual(
                    run_decoder_test(
                        Path("adb.exe"), binary, libcxx, "arm64-v8a", serial="SERIAL"
                    ),
                    0,
                )
                remote_dir, remote_exe, remote_lib = remote_paths_for_abi("arm64-v8a")
                self.assertEqual(
                    run.call_args_list,
                    [
                        call(["adb.exe", "-s", "SERIAL", "shell", "mkdir", "-p", remote_dir], check=False),
                        call(["adb.exe", "-s", "SERIAL", "push", str(binary), remote_exe], check=False),
                        call(["adb.exe", "-s", "SERIAL", "push", str(libcxx), remote_lib], check=False),
                        call(["adb.exe", "-s", "SERIAL", "shell", "chmod", "755", remote_exe], check=False),
                        call(["adb.exe", "-s", "SERIAL", "shell", "env", f"LD_LIBRARY_PATH={remote_dir}", remote_exe], check=False),
                        call(["adb.exe", "-s", "SERIAL", "shell", "rm", "-f", remote_exe], check=False),
                        call(["adb.exe", "-s", "SERIAL", "shell", "rm", "-f", remote_lib], check=False),
                        call(["adb.exe", "-s", "SERIAL", "shell", "rmdir", remote_dir], check=False),
                    ],
                )
        finally:
            temporary.cleanup()

    def test_nonzero_exec_is_propagated_and_cleanup_runs(self) -> None:
        temporary, binary, libcxx = self._files()
        try:
            with patch("run_krkr_cocos_image_decode_test.subprocess.run") as run:
                run.side_effect = [
                    *[type("Result", (), {"returncode": 0})() for _ in range(4)],
                    type("Result", (), {"returncode": 17})(),
                    *[type("Result", (), {"returncode": 0})() for _ in range(3)],
                ]
                self.assertEqual(
                    run_decoder_test(Path("adb"), binary, libcxx, "armeabi-v7a"),
                    17,
                )
                self.assertEqual(run.call_count, 8)
        finally:
            temporary.cleanup()

    def test_push_failure_still_cleans_up_and_returns_failure(self) -> None:
        temporary, binary, libcxx = self._files()
        try:
            with patch("run_krkr_cocos_image_decode_test.subprocess.run") as run:
                run.side_effect = [
                    type("Result", (), {"returncode": 0})(),
                    type("Result", (), {"returncode": 23})(),
                    *[type("Result", (), {"returncode": 0})() for _ in range(3)],
                ]
                self.assertEqual(
                    run_decoder_test(Path("adb"), binary, libcxx, "arm64-v8a"),
                    23,
                )
                self.assertEqual(run.call_count, 5)
        finally:
            temporary.cleanup()

    def test_missing_libcxx_is_rejected_before_adb(self) -> None:
        temporary, binary, _ = self._files()
        try:
            with patch("run_krkr_cocos_image_decode_test.subprocess.run") as run:
                with self.assertRaises(ValueError):
                    run_decoder_test(Path("adb"), binary, binary.parent / "missing.so", "arm64-v8a")
                run.assert_not_called()
        finally:
            temporary.cleanup()

    def test_cleanup_oserror_preserves_primary_failure(self) -> None:
        temporary, binary, libcxx = self._files()
        try:
            with patch("run_krkr_cocos_image_decode_test.subprocess.run") as run:
                run.side_effect = [
                    type("Result", (), {"returncode": 0})(),
                    type("Result", (), {"returncode": 23})(),
                    OSError("rm exe unavailable"),
                    type("Result", (), {"returncode": 0})(),
                    type("Result", (), {"returncode": 0})(),
                ]
                stderr = StringIO()
                with redirect_stderr(stderr):
                    result = run_decoder_test(Path("adb"), binary, libcxx, "arm64-v8a")
                self.assertEqual(result, 23)
                self.assertIn("cleanup", stderr.getvalue().lower())
        finally:
            temporary.cleanup()

    def test_cleanup_oserror_after_success_is_independent_failure(self) -> None:
        temporary, binary, libcxx = self._files()
        try:
            with patch("run_krkr_cocos_image_decode_test.subprocess.run") as run:
                run.side_effect = [
                    *[type("Result", (), {"returncode": 0})() for _ in range(5)],
                    OSError("rmdir unavailable"),
                    type("Result", (), {"returncode": 0})(),
                    type("Result", (), {"returncode": 0})(),
                ]
                stderr = StringIO()
                with redirect_stderr(stderr):
                    result = run_decoder_test(Path("adb"), binary, libcxx, "arm64-v8a")
                self.assertEqual(result, 125)
                self.assertIn("cleanup", stderr.getvalue().lower())
        finally:
            temporary.cleanup()

    def test_abi_is_explicit_and_remote_name_is_deterministic(self) -> None:
        self.assertEqual(
            remote_path_for_abi("arm64-v8a"),
            "/data/local/tmp/twinquill_krkr_cocos_image_decode_test_arm64-v8a/decoder",
        )
        with self.assertRaises(ValueError):
            remote_path_for_abi("x86")


if __name__ == "__main__":
    unittest.main()
