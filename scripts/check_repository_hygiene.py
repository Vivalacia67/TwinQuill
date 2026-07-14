#!/usr/bin/env python3
"""Fail when local/agent state or prebuilt native libraries enter the repo."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path, PurePosixPath


FORBIDDEN_BASENAMES = {
    "agent.md",
    "agents.md",
    "project_plan.md",
    "task_plan.md",
    "findings.md",
    "progress.md",
}
FORBIDDEN_DIRECTORIES = {
    ".agent-work",
    ".agents",
    ".codex",
    ".codegraph",
    ".omo",
}
NATIVE_BINARY_SUFFIXES = {
    ".a",
    ".dll",
    ".dylib",
    ".lib",
    ".o",
    ".obj",
    ".so",
}
GENERATED_DIRECTORIES = {
    ".cxx",
    ".externalnativebuild",
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    "build",
    "out",
}


def git_lines(root: Path, *args: str) -> list[str]:
    result = subprocess.run(
        ["git", *args],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return [line.strip().replace("\\", "/") for line in result.stdout.splitlines() if line.strip()]


def native_binary_name(path: PurePosixPath) -> bool:
    lower_name = path.name.lower()
    return lower_name.endswith(".so") or ".so." in lower_name or path.suffix.lower() in NATIVE_BINARY_SUFFIXES


def forbidden_project_path(raw_path: str) -> str | None:
    path = PurePosixPath(raw_path)
    lower_parts = tuple(part.lower() for part in path.parts)
    if path.name.lower() in FORBIDDEN_BASENAMES:
        return "agent-generated working file"
    if any(part in FORBIDDEN_DIRECTORIES for part in lower_parts):
        return "agent/local-state directory"
    if native_binary_name(path):
        return "prebuilt native binary"
    return None


def scan_worktree(root: Path) -> list[str]:
    violations: list[str] = []
    for candidate in root.rglob("*"):
        relative = candidate.relative_to(root)
        lower_sequence = tuple(part.lower() for part in relative.parts)
        lower_parts = set(lower_sequence)
        if lower_sequence[0] == ".git":
            continue
        if candidate.is_dir() and candidate.name.lower() == ".git":
            violations.append(f"{relative.as_posix()}: nested Git metadata")
            continue
        if lower_parts & (GENERATED_DIRECTORIES - {".git"}):
            continue
        if candidate.is_file() and native_binary_name(PurePosixPath(relative.as_posix())):
            violations.append(f"{relative.as_posix()}: native binary present in source tree")
    return violations


def main() -> int:
    root = Path(git_lines(Path.cwd(), "rev-parse", "--show-toplevel")[0])
    candidates = set(git_lines(root, "ls-files"))
    candidates.update(git_lines(root, "diff", "--cached", "--name-only", "--diff-filter=ACMR"))

    violations = []
    for raw_path in sorted(candidates, key=str.casefold):
        reason = forbidden_project_path(raw_path)
        if reason:
            violations.append(f"{raw_path}: {reason}")
    violations.extend(scan_worktree(root))

    if violations:
        print("Repository hygiene check failed:", file=sys.stderr)
        for violation in sorted(set(violations), key=str.casefold):
            print(f"  - {violation}", file=sys.stderr)
        return 1

    print(f"Repository hygiene check passed ({len(candidates)} tracked/staged paths checked).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
