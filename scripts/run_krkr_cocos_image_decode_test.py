"""Push and execute the Android-native Cocos image decoder seam.

Every adb operation is an argv list (never a shell string). The executable and
matching libc++ runtime are staged in a fixed ABI-specific directory and all
three remote cleanup operations are attempted exactly once.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


SUPPORTED_ABIS = ("arm64-v8a", "armeabi-v7a")
REMOTE_ROOT = "/data/local/tmp/twinquill_krkr_cocos_image_decode_test_"
REMOTE_EXECUTABLE_NAME = "decoder"
REMOTE_LIBCXX_NAME = "libc++_shared.so"
CLEANUP_FAILURE_CODE = 125
PRIMARY_OSERROR_CODE = 127


def remote_paths_for_abi(abi: str) -> tuple[str, str, str]:
    if abi not in SUPPORTED_ABIS:
        raise ValueError(f"unsupported ABI: {abi}")
    directory = REMOTE_ROOT + abi
    return (
        directory,
        f"{directory}/{REMOTE_EXECUTABLE_NAME}",
        f"{directory}/{REMOTE_LIBCXX_NAME}",
    )


def remote_path_for_abi(abi: str) -> str:
    """Return the fixed remote executable path (kept for focused tests)."""
    return remote_paths_for_abi(abi)[1]


def _adb_prefix(adb: Path, serial: str | None) -> list[str]:
    command = [str(adb)]
    if serial:
        command.extend(["-s", serial])
    return command


def _validate_local_file(path: Path, label: str) -> None:
    if not path.is_file():
        raise ValueError(f"{label} must be an existing local file: {path}")


def run_decoder_test(
    adb: Path,
    binary: Path,
    libcxx_shared: Path,
    abi: str,
    serial: str | None = None,
) -> int:
    _validate_local_file(binary, "decoder binary")
    _validate_local_file(libcxx_shared, "libc++_shared.so")
    if libcxx_shared.name != REMOTE_LIBCXX_NAME:
        raise ValueError(
            f"--libcxx-shared must name {REMOTE_LIBCXX_NAME}: {libcxx_shared}"
        )

    remote_dir, remote_executable, remote_libcxx = remote_paths_for_abi(abi)
    prefix = _adb_prefix(adb, serial)
    primary_result = 0
    primary_error: OSError | None = None
    cleanup_errors: list[str] = []
    try:
        operations = (
            [*prefix, "shell", "mkdir", "-p", remote_dir],
            [*prefix, "push", str(binary), remote_executable],
            [*prefix, "push", str(libcxx_shared), remote_libcxx],
            [*prefix, "shell", "chmod", "755", remote_executable],
            [
                *prefix,
                "shell",
                "env",
                f"LD_LIBRARY_PATH={remote_dir}",
                remote_executable,
            ],
        )
        for operation in operations:
            try:
                completed = subprocess.run(operation, check=False)
            except OSError as error:
                primary_error = error
                primary_result = PRIMARY_OSERROR_CODE
                break
            primary_result = completed.returncode
            if primary_result != 0:
                break
    finally:
        cleanup_operations = (
            [*prefix, "shell", "rm", "-f", remote_executable],
            [*prefix, "shell", "rm", "-f", remote_libcxx],
            [*prefix, "shell", "rmdir", remote_dir],
        )
        for operation in cleanup_operations:
            try:
                completed = subprocess.run(operation, check=False)
            except OSError as error:
                cleanup_errors.append(f"{operation[-1]}: {error}")
                continue
            if completed.returncode != 0:
                cleanup_errors.append(
                    f"{operation[-1]} exited with {completed.returncode}"
                )

    if primary_error is not None:
        print(f"adb primary operation failed: {primary_error}", file=sys.stderr)
    if cleanup_errors:
        print(
            "adb cleanup failed (primary result preserved): "
            + "; ".join(cleanup_errors),
            file=sys.stderr,
        )
        if primary_result == 0:
            primary_result = CLEANUP_FAILURE_CODE
    return primary_result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--libcxx-shared", type=Path, required=True)
    parser.add_argument("--abi", choices=SUPPORTED_ABIS, required=True)
    parser.add_argument("--serial")
    args = parser.parse_args()
    try:
        return run_decoder_test(
            args.adb,
            args.binary,
            args.libcxx_shared,
            args.abi,
            args.serial,
        )
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
