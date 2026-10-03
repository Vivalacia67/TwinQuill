# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors
"""Run the M3 storage executable and authored matrix in an owned emulator directory."""
from __future__ import annotations
import argparse
from pathlib import Path
import subprocess
import uuid
ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb",type=Path,required=True)
    parser.add_argument("--binary",type=Path,required=True)
    parser.add_argument("--serial",required=True)
    args = parser.parse_args()
    if not args.adb.is_file() or not args.binary.is_file():
        parser.error("adb and native test binary must be existing files")
    # All remote shell arguments are generated, fixed ASCII paths.
    remote = "/data/local/tmp/twinquill-m3-storage-"+uuid.uuid4().hex
    adb = [str(args.adb),"-s",args.serial]
    result = 1
    try:
        operations = [
            [*adb,"shell","mkdir","-p",remote+"/fixtures",remote+"/saves"],
            [*adb,"push",str(args.binary),remote+"/storage-test"],
            [*adb,"push",str(ROOT/"tests/fixtures/krkr-m3")+ "/.",remote+"/fixtures/"],
            [*adb,"shell","chmod","755",remote+"/storage-test"],
            [*adb,"shell",remote+"/storage-test",remote+"/fixtures",remote+"/saves"],
        ]
        for operation in operations:
            completed = subprocess.run(operation,check=False,timeout=60)
            if completed.returncode:
                result = completed.returncode
                break
        else:
            result = 0
    except (OSError,subprocess.TimeoutExpired) as error:
        print(error)
        result = 1
    finally:
        # Delete only this invocation's generated UUID directory, never a caller path.
        try:
            cleanup = subprocess.run([*adb,"shell","rm","-rf","--",remote],check=False,timeout=30)
            if result == 0 and cleanup.returncode: result = cleanup.returncode
        except (OSError,subprocess.TimeoutExpired) as error:
            print("Native test cleanup failed:",error)
            if result == 0: result = 1
    return result

if __name__ == "__main__":
    raise SystemExit(main())
