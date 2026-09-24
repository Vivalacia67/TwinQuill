# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors

"""Strict post-configure link-boundary checker for the Krkr Cocos closure.

The source-ceiling unit test checks the declarative CMake blocks.  This script
checks the generated final-engine LINK_LIBRARIES line, where transitive static
archives and toolchain libraries are actually resolved.
"""

from __future__ import annotations

import argparse
import re
import subprocess
from pathlib import Path
from typing import Iterable


ALLOWED_SYSTEM_LIBS = {"GLESv2", "android", "log", "dl", "m"}
REQUIRED_ARCHIVES = {
    "libtwinquill_krkr_cocos_core.a",
    "libtwinquill_krkr_freetype.a",
    "libtwinquill_krkr_libjpeg.a",
    "libtwinquill_krkr_libpng.a",
    "libtwinquill_krkr_zlib.a",
}
ALLOWED_ARCHIVES = REQUIRED_ARCHIVES | {
    "libtwinquill_krkr_tjs2.a",
    "libonig.a",
}
ALLOWED_WHOLE_ARCHIVE_FLAGS = {
    "-Wl,--whole-archive",
    "-Wl,--no-whole-archive",
}
ALLOWED_DT_NEEDED = {
    "libtwinquill_native_vfs.so",
    "libGLESv2.so",
    "libandroid.so",
    "liblog.so",
    "libm.so",
    "libc++_shared.so",
    "libdl.so",
    "libc.so",
}


class LinkBoundaryError(AssertionError):
    """Raised when generated linker inputs exceed the frozen ceiling."""


def _link_line(build_text: str, target: str) -> str:
    lines = build_text.splitlines()
    target_rule = re.compile(
        rf":\s*CXX_SHARED_LIBRARY_LINKER__{re.escape(target)}(?:[_\s]|$)"
    )
    output_candidates = []
    for index, line in enumerate(lines):
        if not line.startswith("build ") or f"{target}.so:" not in line:
            continue
        rule_match = re.search(r":\s*([^\s]+)", line)
        if rule_match and rule_match.group(1) != "phony":
            output_candidates.append(index)
    if not output_candidates:
        raise LinkBoundaryError(
            f"missing CXX_SHARED_LIBRARY_LINKER edge for {target}.so"
        )
    if len(output_candidates) != 1:
        raise LinkBoundaryError(
            f"expected one CXX_SHARED_LIBRARY_LINKER edge for {target}.so; "
            f"found {len(output_candidates)}"
        )

    index = output_candidates[0]
    if not target_rule.search(lines[index]):
        raise LinkBoundaryError(
            f"missing CXX_SHARED_LIBRARY_LINKER edge for {target}.so"
        )
    values: list[str] | None = None
    cursor = index + 1
    while cursor < len(lines):
        line = lines[cursor]
        if line.startswith("build "):
            break
        if line.startswith("  LINK_LIBRARIES ="):
            if values is not None:
                raise LinkBoundaryError(
                    f"duplicate LINK_LIBRARIES variable for {target}.so"
                )
            values = [line.split("=", 1)[1].rstrip()]
            continuation = cursor + 1
            while _ninja_continues(values[-1]):
                if continuation >= len(lines) or lines[continuation].startswith("build "):
                    raise LinkBoundaryError(
                        f"unterminated LINK_LIBRARIES continuation for {target}.so"
                    )
                values[-1] = values[-1][:-1]
                values.append(lines[continuation].lstrip())
                continuation += 1
            cursor = continuation
            continue
        if line.startswith("  "):
            cursor += 1
            continue
        break
    if values is None:
        raise LinkBoundaryError(
            f"missing adjacent LINK_LIBRARIES variable for {target}.so"
        )
    return " ".join(values).strip()


def _ninja_continues(value: str) -> bool:
    """Return whether a Ninja variable line uses a trailing ``$`` newline escape."""
    trailing = len(value) - len(value.rstrip("$"))
    return trailing % 2 == 1


def _tokens(link_line: str) -> list[str]:
    """Decode Ninja escapes and split LINK_LIBRARIES without shell semantics.

    Ninja uses ``$ `` for an escaped space, ``$:`` for an escaped colon and
    ``$$`` for a literal dollar.  Backslashes are ordinary Windows path bytes
    and must never be globally replaced or consumed.
    """
    tokens: list[str] = []
    current: list[str] = []
    index = 0

    def flush() -> None:
        if current:
            tokens.append("".join(current))
            current.clear()

    while index < len(link_line):
        value = link_line[index]
        if value.isspace():
            flush()
            index += 1
            continue
        if value != "$":
            current.append(value)
            index += 1
            continue
        if index + 1 >= len(link_line):
            raise LinkBoundaryError("unterminated Ninja escape in LINK_LIBRARIES")
        escaped = link_line[index + 1]
        if escaped == " ":
            current.append(" ")
        elif escaped == ":":
            current.append(":")
        elif escaped == "$":
            current.append("$")
        elif escaped in {"\r", "\n"}:
            index += 2
            continue
        else:
            raise LinkBoundaryError(f"unsupported Ninja escape $ {escaped!r}")
        index += 2
    flush()
    return tokens


def _archive_name(token: str) -> str | None:
    if not token.lower().endswith(".a"):
        return None
    return re.split(r"[\\/]", token)[-1]


def _basename(token: str) -> str:
    return re.split(r"[\\/]", token)[-1]


def parse_link_items(build_dir: Path, target: str = "twinquill_engine_krkr") -> list[str]:
    build_file = build_dir / "build.ninja"
    if not build_file.is_file():
        raise LinkBoundaryError(f"missing generated build.ninja: {build_file}")
    return _tokens(_link_line(build_file.read_text(encoding="utf-8", errors="replace"), target))


def verify_needed_names(names: Iterable[str]) -> None:
    unknown = sorted(set(names) - ALLOWED_DT_NEEDED)
    if unknown:
        raise LinkBoundaryError(f"DT_NEEDED outside Android ceiling: {', '.join(unknown)}")


def _readelf_needed(final_so: Path, readelf: Path | str) -> set[str]:
    result = subprocess.run(
        [str(readelf), "-d", str(final_so)],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise LinkBoundaryError(
            f"llvm-readelf failed for {final_so}: {result.stderr.strip()}"
        )
    return set(re.findall(r"Shared library: \[([^\]]+)\]", result.stdout))


def verify_link_items(items: Iterable[str]) -> None:
    tokens = list(items)
    errors: list[str] = []
    for token in tokens:
        if token.startswith("-l"):
            library = token[2:]
            if library not in ALLOWED_SYSTEM_LIBS:
                errors.append(f"unknown system library {token}")
        elif token.startswith("-Wl,"):
            if token not in ALLOWED_WHOLE_ARCHIVE_FLAGS:
                errors.append(f"unknown linker flag {token}")
        elif token.startswith("-"):
            errors.append(f"unknown linker flag {token}")
        elif token.startswith("@"):
            errors.append(f"response file is outside link ceiling: {token}")
        elif token.lower().endswith(".so"):
            if _basename(token) != "libtwinquill_native_vfs.so":
                errors.append(f"unknown shared object {token}")
        elif token.lower().endswith((".o", ".obj")):
            errors.append(f"object input is outside link ceiling: {token}")
        elif token.lower().endswith(".a"):
            archive = _archive_name(token)
            if archive not in ALLOWED_ARCHIVES:
                errors.append(f"unknown static archive {token}")
        else:
            errors.append(f"bare token is outside link ceiling: {token}")

    archive_names = [_archive_name(token) for token in tokens]
    archives = {name for name in archive_names if name is not None}
    duplicate_archives = sorted(
        name for name in archives if archive_names.count(name) > 1
    )
    if duplicate_archives:
        errors.append(
            "duplicate archive input(s): " + ", ".join(duplicate_archives)
        )
    missing = sorted(REQUIRED_ARCHIVES - archives)
    if missing:
        errors.append(f"missing approved archive(s): {', '.join(missing)}")
    unknown_archives = sorted(archives - ALLOWED_ARCHIVES)
    if unknown_archives:
        errors.append(f"unknown static archive(s): {', '.join(unknown_archives)}")

    core = "libtwinquill_krkr_cocos_core.a"
    try:
        core_index = archive_names.index(core)
    except ValueError:
        core_index = -1
    if core_index < 1 or tokens[core_index - 1] != "-Wl,--whole-archive":
        errors.append("Cocos core archive is not immediately whole-archived")
    elif core_index + 1 >= len(tokens) or tokens[core_index + 1] != "-Wl,--no-whole-archive":
        errors.append("Cocos core whole-archive is not closed")

    if errors:
        raise LinkBoundaryError("; ".join(errors))


def verify_build(build_dir: Path, final_so: Path | None = None,
                 readelf: Path | str = "llvm-readelf") -> list[str]:
    items = parse_link_items(build_dir)
    verify_link_items(items)
    needed: set[str] = set()
    if final_so is not None:
        needed = _readelf_needed(final_so, readelf)
        verify_needed_names(needed)
    return items


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--final-so", type=Path, required=True)
    parser.add_argument("--llvm-readelf", type=Path, required=True)
    args = parser.parse_args()
    try:
        items = verify_build(args.build_dir, args.final_so, args.llvm_readelf)
    except LinkBoundaryError as error:
        parser.error(str(error))
    print(
        "Krkr Cocos link boundary OK: "
        f"{len(items)} linker items; final={args.final_so}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
