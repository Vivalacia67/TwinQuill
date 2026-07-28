#!/usr/bin/env python3
"""Generate or verify the file-level checksums of vendored source snapshots."""

from __future__ import annotations

import argparse
import fnmatch
import hashlib
import sys
import tomllib
from pathlib import Path, PurePosixPath
from typing import Any


MANIFEST_PATH = Path("third_party/source_files.sha256")
SOURCES_PATH = Path("third_party/sources.toml")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def source_destinations(root: Path) -> dict[str, tuple[Path, dict[str, Any]]]:
    configuration = tomllib.loads((root / SOURCES_PATH).read_text(encoding="utf-8"))
    destinations: dict[str, tuple[Path, dict[str, Any]]] = {}
    for source in configuration.get("sources", []):
        source_id = source["id"]
        destination = (root / source["destination"]).resolve()
        if source_id in destinations:
            raise ValueError(f"duplicate source id: {source_id}")
        if not destination.is_relative_to(root):
            raise ValueError(f"source destination escapes repository: {destination}")
        destinations[source_id] = (destination, source)
    return destinations


def snapshot_file(destination: Path, relative: str, label: str) -> Path:
    path = PurePosixPath(relative)
    if (
        not relative
        or "\\" in relative
        or "\0" in relative
        or path.is_absolute()
        or ".." in path.parts
        or any(":" in part for part in path.parts)
        or path.as_posix() != relative
    ):
        raise ValueError(f"unsafe {label}: {relative}")
    return destination.joinpath(*path.parts)


def validate_repository_license_files(
    root: Path,
    source_id: str,
    source: dict[str, Any],
) -> None:
    repository_license_files = source.get("repository_license_files")
    if repository_license_files is None:
        return
    if not isinstance(repository_license_files, list) or not repository_license_files:
        raise ValueError(f"invalid repository license files for source: {source_id}")
    for relative in repository_license_files:
        if not isinstance(relative, str):
            raise ValueError(f"invalid repository license file for source: {source_id}")
        license_path = snapshot_file(root, relative, "repository license path")
        resolved = license_path.resolve()
        if not resolved.is_relative_to(root.resolve()):
            raise ValueError(f"unsafe repository license path: {relative}")
        if license_path.is_symlink() or not license_path.is_file():
            raise FileNotFoundError(f"missing repository license file: {license_path}")


def snapshot_files(
    source_id: str,
    destination: Path,
    source: dict[str, Any],
) -> list[Path]:
    candidates: list[Path] = []
    for candidate in destination.rglob("*"):
        if candidate.is_symlink():
            raise ValueError(f"symbolic links are not allowed in snapshots: {candidate}")
        if candidate.is_file():
            candidates.append(candidate)

    included_paths = source.get("included_paths")
    if included_paths is None:
        return candidates
    if not isinstance(included_paths, list) or not included_paths:
        raise ValueError(f"invalid included paths for source: {source_id}")

    relative_candidates = {
        candidate.relative_to(destination).as_posix(): candidate
        for candidate in candidates
    }
    covered: set[str] = set()
    for pattern in included_paths:
        if not isinstance(pattern, str):
            raise ValueError(f"invalid included path for source: {source_id}")
        snapshot_file(destination, pattern, "included path pattern")
        matches = {
            relative
            for relative in relative_candidates
            if fnmatch.fnmatchcase(relative, pattern)
        }
        if not matches:
            raise ValueError(
                f"included path pattern matches no files for source {source_id}: {pattern}"
            )
        covered.update(matches)

    unexpected = sorted(relative_candidates.keys() - covered)
    if unexpected:
        raise ValueError(
            f"snapshot file is not covered by included paths for source {source_id}: "
            f"{unexpected[0]}"
        )
    return candidates


def validate_source_files(
    source_id: str,
    destination: Path,
    source: dict[str, Any],
) -> None:
    license_files = source.get("license_files")
    if not isinstance(license_files, list) or not license_files:
        raise ValueError(f"missing license files for source: {source_id}")
    for relative in license_files:
        if not isinstance(relative, str):
            raise ValueError(f"invalid license file for source: {source_id}")
        license_path = snapshot_file(destination, relative, "license path")
        if not license_path.is_file():
            raise FileNotFoundError(f"missing license file: {license_path}")

    if source.get("source_type") != "font-asset":
        return

    for field in ("url", "version", "asset_file", "asset_sha256", "license"):
        if not isinstance(source.get(field), str) or not source[field]:
            raise ValueError(f"missing {field} for font asset: {source_id}")
    expected_sha256 = source["asset_sha256"]
    if len(expected_sha256) != 64 or any(
        character not in "0123456789abcdef" for character in expected_sha256
    ):
        raise ValueError(f"invalid asset SHA-256 for font asset: {source_id}")
    asset_path = snapshot_file(destination, source["asset_file"], "font asset path")
    if not asset_path.is_file():
        raise FileNotFoundError(f"missing font asset: {asset_path}")
    if sha256(asset_path) != expected_sha256:
        raise ValueError(f"font asset checksum mismatch: {source_id}")


def collect(root: Path) -> dict[str, str]:
    entries: dict[str, str] = {}
    for source_id, (destination, source) in source_destinations(root).items():
        if not destination.is_dir():
            raise FileNotFoundError(f"missing source snapshot: {destination}")
        validate_repository_license_files(root, source_id, source)
        validate_source_files(source_id, destination, source)
        for candidate in snapshot_files(source_id, destination, source):
            relative = candidate.relative_to(destination).as_posix()
            manifest_path = f"{source_id}/{relative}"
            entries[manifest_path] = sha256(candidate)
    return entries


def load_manifest(path: Path) -> dict[str, str]:
    entries: dict[str, str] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw_line or raw_line.startswith("#"):
            continue
        try:
            digest, relative = raw_line.split("  ", 1)
        except ValueError as error:
            raise ValueError(f"invalid checksum line {line_number}") from error
        if len(digest) != 64 or any(character not in "0123456789abcdef" for character in digest):
            raise ValueError(f"invalid SHA-256 on line {line_number}")
        path = PurePosixPath(relative)
        if path.is_absolute() or ".." in path.parts:
            raise ValueError(f"unsafe path on line {line_number}: {relative}")
        if relative in entries:
            raise ValueError(f"duplicate path on line {line_number}: {relative}")
        entries[relative] = digest
    return entries


def write_manifest(path: Path, entries: dict[str, str]) -> None:
    lines = [
        "# Generated by scripts/verify_source_snapshots.py --generate.",
        "# Format: SHA-256, two spaces, source-id/path.",
    ]
    lines.extend(f"{digest}  {relative}" for relative, digest in sorted(entries.items()))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def verify(expected: dict[str, str], actual: dict[str, str]) -> list[str]:
    failures: list[str] = []
    for relative in sorted(expected.keys() - actual.keys()):
        failures.append(f"missing: {relative}")
    for relative in sorted(actual.keys() - expected.keys()):
        failures.append(f"unexpected: {relative}")
    for relative in sorted(expected.keys() & actual.keys()):
        if expected[relative] != actual[relative]:
            failures.append(f"checksum mismatch: {relative}")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--generate",
        action="store_true",
        help="replace the checksum manifest with the current source snapshot hashes",
    )
    arguments = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    manifest_path = root / MANIFEST_PATH
    actual = collect(root)

    if arguments.generate:
        write_manifest(manifest_path, actual)
        print(f"Wrote {len(actual)} source file checksums to {manifest_path}.")
        return 0

    if not manifest_path.is_file():
        print(f"Missing checksum manifest: {manifest_path}", file=sys.stderr)
        return 1

    failures = verify(load_manifest(manifest_path), actual)
    if failures:
        print("Source snapshot verification failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1

    print(f"Source snapshot verification passed ({len(actual)} files checked).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
