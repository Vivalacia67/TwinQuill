#!/usr/bin/env python3
"""Integration tests for the repository hygiene gate."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


CHECKER = Path(__file__).resolve().parents[1] / "scripts" / "check_repository_hygiene.py"


class RepositoryHygieneTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.run_git("init", "-b", "main")
        self.run_git("config", "user.name", "TwinQuill CI")
        self.run_git("config", "user.email", "ci@twinquill.invalid")

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def run_git(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["git", *args],
            cwd=self.root,
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )

    def run_checker(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(CHECKER)],
            cwd=self.root,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )

    def write(self, relative_path: str, content: bytes = b"fixture") -> Path:
        target = self.root / relative_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        return target

    def test_clean_tracked_source_passes(self) -> None:
        self.write("README.md", b"# Test repository\n")
        self.run_git("add", "README.md")

        result = self.run_checker()

        self.assertEqual(0, result.returncode, result.stderr)

    def test_force_added_agent_file_fails(self) -> None:
        self.write("AGENTS.md")
        self.run_git("add", "-f", "AGENTS.md")

        result = self.run_checker()

        self.assertNotEqual(0, result.returncode)
        self.assertIn("agent-generated working file", result.stderr)

    def test_force_added_native_library_fails(self) -> None:
        self.write("vendor/example/libexample.so")
        self.run_git("add", "-f", "vendor/example/libexample.so")

        result = self.run_checker()

        self.assertNotEqual(0, result.returncode)
        self.assertIn("prebuilt native binary", result.stderr)

    def test_untracked_native_library_in_source_tree_fails(self) -> None:
        self.write("vendor/example/libexample.a")

        result = self.run_checker()

        self.assertNotEqual(0, result.returncode)
        self.assertIn("native binary present in source tree", result.stderr)

    def test_native_build_output_is_ignored(self) -> None:
        self.write("engine-ons/build/intermediates/libexample.so")

        result = self.run_checker()

        self.assertEqual(0, result.returncode, result.stderr)

    def test_nested_git_metadata_fails(self) -> None:
        (self.root / "vendor" / "upstream" / ".git").mkdir(parents=True)

        result = self.run_checker()

        self.assertNotEqual(0, result.returncode)
        self.assertIn("nested Git metadata", result.stderr)


if __name__ == "__main__":
    unittest.main()
