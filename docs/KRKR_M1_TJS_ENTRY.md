# TwinQuill KRKR — M1 Task 3: TJS Entry

Status as of 2026-10-02: M0 accepted and committed as `6bd353b`; M1 task 3 is in progress on `refactor/krkr-direct-integration`. The first implementation increment of the formal TJS entry is implemented and validated as recorded below. M1 formal acceptance remains pending.

## First increment

- Route loose files, raw unprotected XP3, and read-only SAF startup scripts through `krkr_tjs_entry.cpp`.
- Execute ordinary TJS2 scripts without requiring `global.twinQuillM0Result` or a fixed return value.
- Decode ASCII/UTF-8 with optional BOM and BOM-marked UTF-16LE/BE. Reject malformed sequences, unpaired surrogates, embedded NULs, and source files over 8 MiB.
- Serialize TJS2 engine lifetimes, release each engine on both success and failure, and preserve Unicode diagnostics in logcat.
- Return native diagnostic 20 / `SCRIPT_ERROR` for source-text, syntax, or script exceptions. Keep existing permission, request, VFS, and native-failure categories.
- Preserve the validated broker, isolated `:krkr` process, and existing GLES runtime lifecycle.
- Apply recorded patch `0002-fix-tjs-free-null.patch` to the generated TJS2 build copy. It fixes an invalid free when a syntax error destroys an engine before its variant stack has allocated storage. The patch and SHA-256 are recorded in `third_party/sources.toml`; upstream snapshot bytes are preserved.

## Regression coverage

The production text decoder has an Android-native executable target, `twinquill_krkr_tjs_text_test`, covering Unicode round trips, supplementary characters, malformed UTF-8/UTF-16, NUL rejection, and the source-size boundary. Debug builds compile it for both supported ABIs; it is not packaged in the APK.

Launcher instrumentation covers the accepted ASCII script, ordinary Unicode scripts without the M0 sentinel, UTF-8/BOM/UTF-16 variants through SAF, loose startup, raw XP3 startup, syntax/script/text errors, and a successful launch after those errors. The fixtures are authored in the test source and need no game assets.

Run from the repository root:

```powershell
python -m unittest discover -s tests -v
python scripts/check_repository_hygiene.py
python scripts/verify_source_snapshots.py
.\gradlew.bat --no-daemon :launcher-app:assembleDebug :launcher-app:assembleRelease :launcher-app:lintDebug
python scripts/check_apk_native_libraries.py launcher-app/build/outputs/apk/debug/launcher-app-debug.apk launcher-app/build/outputs/apk/release/launcher-app-release-unsigned.apk
.\gradlew.bat --no-daemon :launcher-app:connectedDebugAndroidTest
.\gradlew.bat --no-daemon :launcher-app:connectedDebugAndroidTest -PtwinquillKrkrRuntimeInstrumentation=true
```

Local verification logs belong under ignored `.agent-work/m1-tjs-entry/`. Device results must identify API, ABI, and emulator/device configuration.

For the standalone text test on this x86_64 emulator, compile the same production decoder with the pinned NDK and a static C++ runtime. This avoids the emulator's standalone ARM executable/library-loading restriction; app instrumentation still exercises the packaged ARM64 engine through translation.

```powershell
New-Item -ItemType Directory -Force .agent-work/m1-tjs-entry | Out-Null
$ndkBin = "$env:LOCALAPPDATA\Android\Sdk\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& "$ndkBin\clang++.exe" --target=x86_64-linux-android26 -std=c++17 -static-libstdc++ -Wall -Wextra -Werror -Iengine-krkr/src/main/cpp tests/native/krkr_tjs_text_test.cpp engine-krkr/src/main/cpp/krkr_tjs_text.cpp -o .agent-work/m1-tjs-entry/tjs-text-x86_64
& $adb -s emulator-5554 push .agent-work/m1-tjs-entry/tjs-text-x86_64 /data/local/tmp/twinquill-m1-tjs-text
& $adb -s emulator-5554 shell chmod 755 /data/local/tmp/twinquill-m1-tjs-text
& $adb -s emulator-5554 shell /data/local/tmp/twinquill-m1-tjs-text
& $adb -s emulator-5554 shell rm -f /data/local/tmp/twinquill-m1-tjs-text
```

The executable should print `TJS text checks passed` and exit 0. ARMv7 device execution has not been established.

## Validation results (2026-10-02)

| Check | Result |
| --- | --- |
| Repository regression | 89/89 Python tests passed, including application/provenance checks for both TJS2 patches. |
| Repository/source gates | Hygiene passed; all 7,218 pinned source files matched their checksums; `git diff --check` passed. |
| Build/APK | Debug and Release built for arm64-v8a and armeabi-v7a; Android lint and both APK native-library checks passed. |
| Focused startup regression | 5/5 passed, covering ASCII, four Unicode SAF encodings, loose/XP3 startup, error categories, and recovery. |
| Full default instrumentation | 19/19 passed (18 process/protocol tests and one persistence test). |
| Isolated Krkr instrumentation | 2/2 runtime-host/broker lifecycle tests passed. |
| Standalone production decoder | NDK-built x86_64 executable exited 0; Unicode round trips, 21 malformed inputs, and the 8 MiB boundary passed. |

Device instrumentation used `emulator-5554` / `Medium_Phone`, Android 16 / API 36, x86_64 with ARM64 translation, SwiftShader and Vulkan disabled. The standalone decoder check ran as native x86_64. There is no recorded API 26 or physical ARMv7 runtime result for this increment.

Debug APK SHA-256: `9302c04aaab7baec6587ad78550be903a620df8eaef2f3e372cdceec7195a7b2`. Release APK SHA-256: `4431a2490c38b264b9b46268e8dfdb88d4b5543b063cecdd245d5567ca922b5a`. Local evidence includes `focused-3.txt`, `full-release.txt`, `full-results.xml`, `krkr-runtime-results.xml`, `python-tests-2.txt`, and `native-text-x86_64.txt` under `.agent-work/m1-tjs-entry/`; these files are ignored.

## Manual check

Save the following as UTF-8 `startup.tjs` in a new game directory, add the directory in TwinQuill, and launch with Kirikiri:

```javascript
var greeting = "中文";
function add(a, b) { return a + b; }
if (add(2, 5) != 7) throw new Exception("计算错误");
global.twinQuillM1Value = greeting;
```

The basic test surface should open; Back should return `NORMAL_EXIT`. Replacing the script with `throw new Exception("脚本错误");` should return `SCRIPT_ERROR`; restoring the valid script should allow another successful launch.

## Remaining M1 work

The current entry executes startup once and disposes the engine before displaying the test surface. Persistent TJS state, TVP native-class/storage registration, and script-driven host behavior need further integration and acceptance criteria. Legacy codepages, KAG, compressed/protected XP3, save media, and broader game compatibility remain outside this increment. Imported snapshots and their per-file checksums are unchanged.
