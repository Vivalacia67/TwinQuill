#!/usr/bin/env python3
"""Verify TwinQuill APK native library names, ABIs, and ELF page alignment."""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from pathlib import Path, PurePosixPath


EXPECTED_ABIS = {"arm64-v8a", "armeabi-v7a"}
REQUIRED_LIBRARIES = {
    "libtwinquill_engine_krkr.so",
    "libtwinquill_engine_ons.so",
    "libtwinquill_native_vfs.so",
    "libc++_shared.so",
}
FORBIDDEN_LIBRARIES = {"libonig.so"}
MINIMUM_LOAD_ALIGNMENT = 0x4000
ARMEABI_V7A_LIBCXX_MINIMUM_LOAD_ALIGNMENT = 0x1000
PT_LOAD = 1
EM_ARM = 40
EM_AARCH64 = 183


def elf_identity(data: bytes) -> tuple[int, int]:
    if len(data) < 20 or data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError("not a supported little-endian ELF file")
    elf_class = data[4]
    if elf_class not in (1, 2):
        raise ValueError(f"unsupported ELF class: {elf_class}")
    machine = struct.unpack_from("<H", data, 18)[0]
    return elf_class, machine


def load_alignments(data: bytes) -> list[int]:
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError("not a supported little-endian ELF file")
    elf_class = data[4]
    if elf_class == 2:
        phoff = struct.unpack_from("<Q", data, 32)[0]
        phentsize = struct.unpack_from("<H", data, 54)[0]
        phnum = struct.unpack_from("<H", data, 56)[0]
        alignment_offset = 48
        alignment_format = "<Q"
    elif elf_class == 1:
        phoff = struct.unpack_from("<I", data, 28)[0]
        phentsize = struct.unpack_from("<H", data, 42)[0]
        phnum = struct.unpack_from("<H", data, 44)[0]
        alignment_offset = 28
        alignment_format = "<I"
    else:
        raise ValueError(f"unsupported ELF class: {elf_class}")

    if phentsize < alignment_offset + struct.calcsize(alignment_format):
        raise ValueError("invalid ELF program header size")
    table_end = phoff + phentsize * phnum
    if table_end > len(data):
        raise ValueError("ELF program header table exceeds file")

    alignments: list[int] = []
    for index in range(phnum):
        header = phoff + index * phentsize
        if struct.unpack_from("<I", data, header)[0] == PT_LOAD:
            alignments.append(struct.unpack_from(alignment_format, data, header + alignment_offset)[0])
    if not alignments:
        raise ValueError("ELF has no PT_LOAD segments")
    return alignments


def verify_apk(path: Path) -> list[str]:
    failures: list[str] = []
    libraries: dict[str, set[str]] = {}
    with zipfile.ZipFile(path) as apk:
        for entry in apk.infolist():
            parts = PurePosixPath(entry.filename).parts
            if len(parts) != 3 or parts[0] != "lib" or not parts[2].endswith(".so"):
                continue
            abi, library = parts[1], parts[2]
            libraries.setdefault(abi, set()).add(library)
            if library in FORBIDDEN_LIBRARIES:
                failures.append(f"{path}: forbidden native library: {entry.filename}")
            try:
                data = apk.read(entry)
                elf_class, machine = elf_identity(data)
                alignments = load_alignments(data)
            except (KeyError, ValueError, struct.error) as error:
                failures.append(f"{path}: invalid native library {entry.filename}: {error}")
                continue
            expected_identity = (
                (2, EM_AARCH64) if abi == "arm64-v8a" else (1, EM_ARM)
            )
            if (elf_class, machine) != expected_identity:
                failures.append(
                    f"{path}: {entry.filename} has ELF identity "
                    f"class={elf_class}, machine={machine}; expected "
                    f"class={expected_identity[0]}, machine={expected_identity[1]}"
                )
            minimum_alignment = MINIMUM_LOAD_ALIGNMENT
            identity_valid = (elf_class, machine) == expected_identity
            if (
                identity_valid
                and abi == "armeabi-v7a"
                and library == "libc++_shared.so"
            ):
                minimum_alignment = ARMEABI_V7A_LIBCXX_MINIMUM_LOAD_ALIGNMENT
            for alignment in alignments:
                if alignment < minimum_alignment:
                    failures.append(
                        f"{path}: {entry.filename} PT_LOAD alignment "
                        f"0x{alignment:x} is below 0x{minimum_alignment:x}"
                    )

    actual_abis = set(libraries)
    if actual_abis != EXPECTED_ABIS:
        failures.append(
            f"{path}: expected ABIs {sorted(EXPECTED_ABIS)}, found {sorted(actual_abis)}"
        )
    for abi in sorted(EXPECTED_ABIS):
        missing = REQUIRED_LIBRARIES - libraries.get(abi, set())
        if missing:
            failures.append(f"{path}: {abi} missing libraries: {sorted(missing)}")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apks", nargs="+", type=Path)
    arguments = parser.parse_args()

    failures: list[str] = []
    for apk in arguments.apks:
        if not apk.is_file():
            failures.append(f"missing APK: {apk}")
            continue
        failures.extend(verify_apk(apk))
    if failures:
        print("APK native library verification failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print(f"APK native library verification passed ({len(arguments.apks)} APKs checked).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
