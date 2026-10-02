# TwinQuill KRKR — M1 Task 3: TJS Entry

Status as of 2026-10-02: M0 accepted and committed as `6bd353b`; M1 task 3 (formal TJS entry) is complete and accepted by the user on the configured simulator after the background-return correction below. It includes persistent state, basic TVP bindings, and script-driven host callbacks, and passes the engineering checks on `refactor/krkr-direct-integration`. This completes the M1 scope recorded in this handoff; the later-work limits remain explicit below.

## Startup baseline (`001ddc6`)

- Route loose files, raw unprotected XP3, and read-only SAF startup scripts through `krkr_tjs_entry.cpp`.
- Execute ordinary TJS2 scripts without requiring `global.twinQuillM0Result` or a fixed return value.
- Decode ASCII/UTF-8 with optional BOM and BOM-marked UTF-16LE/BE. Reject malformed sequences, unpaired surrogates, embedded NULs, and source files over 8 MiB.
- Serialize TJS2 engine operations and preserve Unicode diagnostics in logcat. The initial one-shot lifetime is superseded by the persistent session described below for normal launcher requests.
- Return native diagnostic 20 / `SCRIPT_ERROR` for source-text, syntax, or script exceptions. Keep existing permission, request, VFS, and native-failure categories.
- Preserve the validated broker, isolated `:krkr` process, and existing GLES runtime lifecycle.
- Apply recorded patch `0002-fix-tjs-free-null.patch` to the generated TJS2 build copy. It fixes an invalid free when a syntax error destroys an engine before its variant stack has allocated storage. The patch and SHA-256 are recorded in `third_party/sources.toml`; upstream snapshot bytes are preserved.

## Persistent session and native interfaces

The broker executes startup once on its worker, then transfers a process-private opaque session handle to the GLES host. Variables, loaded functions, and callbacks remain alive until exit; surface/context replacement and Activity recreation preserve the VM. Failed startup, canceled broker work, invalid recreated requests, and host exit release the session. Stale handles cannot access or close a newer session. Only one live TJS engine is admitted in `:krkr`, protecting the imported core's process-global caches.

Callbacks run on one separate worker with at most 64 pending events. The renderer polls atomic status/color without executing scripts on the UI or GL thread. Overflow is counted and dropped; close discards pending callbacks and schedules VM release after the active callback returns. The existing 11-value GL counter contract and its bounded input queue are preserved. The host explicitly takes keyboard focus and ignores callbacks from destroyed/recreated views.

| Registered native class | Supported surface |
| --- | --- |
| `Scripts` | `exec(text, name?, lineOffset?, context?)`, `eval(...)`, `execStorage(name, mode?, context?)`, and `evalStorage(...)`. |
| `Storages` | `isExistentStorage(name)` for read-only files in the current game root. |
| `System` | Monotonic millisecond `getTickCount()`; `exit()` / `exit(0)` requests normal exit after the script returns. |
| `Debug` | `message(...)` logs Unicode arguments to `TwinQuill/Krkr`. |
| `TwinQuillHost` | First-party `setColor(red, green, blue)` changes the proof quad; components must be 0–255. |

Assign functions to `TwinQuillHost.onTouch(action, pointerId, x, y, time)`, `onKey(down, keyCode, unicode, meta, repeat, time)`, `onSurfaceChanged(width, height)`, `onPause()`, `onResume()`, and `onLowMemory()`. Android action/key values are forwarded; times are uptime milliseconds. Unset/void callbacks are ignored. Callback exceptions return diagnostic 20 / `SCRIPT_ERROR`, and a subsequent launch creates a fresh engine.

Storage names such as `辅助.tjs` or `scripts/helper.tjs` are relative to the game root; absolute paths, schemes, traversal, NULs, and symlinks escaping a loose root are rejected. SAF reads use `TqSafMedia` and `native-vfs`, including Unicode file names. Each script is limited to 8 MiB; nested `Scripts` calls are limited to 32. Storage encoding mode must be omitted, void, or empty; text is decoded by the startup rules. XP3 access retains the raw root `startup.tjs` boundary; additional scripts may be loose siblings of the archive. There is no general XP3 member lookup or writable storage API.

## Regression coverage

The production text decoder has an Android-native executable target, `twinquill_krkr_tjs_text_test`, covering Unicode round trips, supplementary characters, malformed UTF-8/UTF-16, NUL rejection, and the source-size boundary. Debug builds compile it for both supported ABIs; it is not packaged in the APK.

Launcher instrumentation covers ASCII/Unicode encodings, SAF secondary-script execution/evaluation and reads from callbacks, loose/raw XP3 startup, startup and callback exit, error mapping, and recovery. Isolated-process tests check persistent globals/colors after real Activity recreation and real Home/task return, pause/resume/low-memory callbacks, invalid recreated-request cleanup, traversal/symlink/recursion rejection, NUL in raw source and JNI names, stale handles, single-engine admission, and release behind a full callback queue. Fixtures are authored in test source and need no game assets.

Run from the repository root:

```powershell
python -m unittest discover -s tests -v
python scripts/check_repository_hygiene.py
python scripts/verify_source_snapshots.py
.\gradlew.bat --no-daemon :launcher-app:assembleDebug :launcher-app:assembleRelease :launcher-app:lintDebug
.\gradlew.bat --no-daemon :engine-api:testDebugUnitTest :engine-ons:testDebugUnitTest
python scripts/check_apk_native_libraries.py launcher-app/build/outputs/apk/debug/launcher-app-debug.apk launcher-app/build/outputs/apk/release/launcher-app-release-unsigned.apk
.\gradlew.bat --no-daemon :launcher-app:connectedDebugAndroidTest
.\gradlew.bat --no-daemon :launcher-app:connectedDebugAndroidTest -PtwinquillKrkrRuntimeInstrumentation=true
```

Corrected-build verification logs belong under ignored `.agent-work/m1-background-error/`; persistent-session baseline evidence is under `.agent-work/m1-tjs-session/`, and startup-baseline evidence is under `.agent-work/m1-tjs-entry/`. Device results must identify API, ABI, and emulator/device configuration. The Cocos patch test sets a Git discovery ceiling so it also works when Python falls back to a temporary directory inside the checkout.

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

## Startup-baseline validation (`001ddc6`, 2026-10-02)

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

## Persistent-session baseline validation (`ed42b76`, 2026-10-02)

| Check | Result |
| --- | --- |
| Repository regression | 89/89 Python tests passed, including temporary-directory Git isolation. |
| Repository/source gates | Hygiene, `git diff --check`, and all 7,218 pinned source checksums passed. |
| Build/APK | Debug and Release built for both ABIs; both APK native-library gates passed. |
| Android lint | Passed: 0 errors, 7 warnings; no warnings were promoted or suppressed. |
| JVM tests | `engine-api`: 5/5; `engine-ons`: 4/4. |
| Full default instrumentation | 21/21 passed: 20 process/protocol tests and one persistence test. |
| Isolated Krkr instrumentation | 6/6 passed: five runtime/session tests and one broker recreation test. |

Device: `emulator-5554` / `Medium_Phone`, Android 16 / API 36, x86_64 with ARM64 translation, SwiftShader, Vulkan disabled. Both ARM ABIs build; this is not physical ARMv7 or API 26 execution evidence.

Baseline Debug APK SHA-256: `7070f35a18eed724599a9ac32f026b3b2d98032eeacd50bc69971703b2302aa4`. Baseline unsigned Release APK SHA-256: `f7ac3a0f77223c1b99ae4ee325b8964ba29d8c7a3c1cdf73d0add52f3e52e5bf`.

Local evidence under ignored `.agent-work/m1-tjs-session/`: `final-validation.txt`, `full-results.xml`, `krkr-verified.txt`, `krkr-results.xml`, `python-final.txt`, and `apk-check.txt`. These baseline results supersede the startup-baseline APK hashes and test counts above. The standalone decoder result remains the baseline proof for the unchanged text decoder.

## Background task return correction (2026-10-02)

The user's simulator acceptance found that returning from Home through recent tasks exited with `SCRIPT_ERROR`. The new regression reproduced runtime diagnostic 12 (`kRuntimeSurfaceNotReady`). Earlier tests called pause/resume directly or recreated the Activity; they did not cover destruction of the window surface while EGL retained its context.

`KrkrRuntimeRenderer` now stops drawing before native surface release and rebuilds released GL resources from `onSurfaceChanged` when a retained context receives a replacement window surface without `onSurfaceCreated`. The TJS session and variables stay alive. The runtime logs its final native diagnostic under `TwinQuill/KrkrRuntime`.

`preservesScriptStateAcrossHomeAndTaskReturn` presses the real Home key, waits for native surface loss, and brings the existing task forward twice, as recent-task selection does. It checks yellow pixels, advancing frames, one startup, and a live session before a second touch exits and releases it. Pixel copying retries a temporarily invalid surface during task return. The test failed before the renderer correction and passed afterward; all 7 isolated Krkr tests and all 89 Python tests passed. Evidence belongs under ignored `.agent-work/m1-background-error/`.

| Corrected-build check | Result |
| --- | --- |
| Default instrumentation | 21/21 passed. |
| Isolated Krkr instrumentation | 7/7 passed, including real Home/task return. |
| JVM tests | 9/9 passed across `engine-api` and `engine-ons`. |
| Repository checks | Python 89/89; hygiene, all 7,218 source checksums, and diff whitespace passed. |
| Build/APK/lint | Debug and Release for both ABIs; final APK checks passed; lint has 0 errors and 7 warnings. |

Corrected Debug APK SHA-256: `9dcb08989f684d8158962aff5dc0961b66f74fc8c6855adcb907cd30199c79a0`. Corrected unsigned Release APK SHA-256: `c90c46a8ce94192a7fecdbb39b838ca8957d73653aea03400b9288631aaa5253`. These hashes and test counts supersede the persistent-session baseline. Logs include `home-test-before.txt`, `home-test-after.txt`, `krkr-full.txt`, `final-validation.txt`, `python-tests.txt`, `apk-check.txt`, and the copied `krkr-*.xml` / `default-*.xml` reports. The corrected Debug APK has been installed on `emulator-5554` for repeat acceptance.

The user repeated the failed manual item with the corrected APK and confirmed it now passes. The simulator acceptance below closes M1 task 3.

## M1 device acceptance

This acceptance run uses only `Medium_Phone`, Android 16 / API 36, x86_64 with ARM64 translation, SwiftShader, and Vulkan disabled. Sign-off is recorded as simulator acceptance.

Create a new game directory with the following two UTF-8 files. Add that directory in TwinQuill and launch with Kirikiri.

`helper.tjs`:
```javascript
global.greeting = "中文";
```

`startup.tjs`:
```javascript
Scripts.execStorage("helper.tjs");
if (!Storages.isExistentStorage("helper.tjs")) throw new Exception("缺少文件");
Debug.message(greeting);
var count = 0;
TwinQuillHost.setColor(20, 210, 40);
TwinQuillHost.onTouch = function(action, id, x, y, time) {
    if (action == 0) {
        count++;
        if (count == 1) TwinQuillHost.setColor(220, 170, 30);
        else System.exit();
    }
};
```

1. Launch: the centered quad is green, and logcat contains `中文` under `TwinQuill/Krkr`.
2. Tap once: the quad becomes yellow. Press Home, return to the app, and confirm it remains yellow.
3. Tap again: the script exits and the launcher receives `NORMAL_EXIT` (diagnostic 0). Launch again: the initial green state is restored.
4. Launch and press Back: expect `NORMAL_EXIT`. Replace the callback body with `throw new Exception("回调错误");`: tapping should return `SCRIPT_ERROR` (diagnostic 20).
5. Restore the valid scripts: another launch succeeds. Replacing startup with `Scripts.execStorage("../outside.tjs");` must return `SCRIPT_ERROR`.

Record device model, API, ABI, and the tested APK hash for future acceptance runs.

### User sign-off (2026-10-02)

After installing the corrected Debug APK identified above and restoring the three SAF test directories, the user reported: “目前正常，其他全部验收ok，提交代码。” This confirms the repeated Home/recent-task return now preserves yellow and the remaining documented acceptance checks passed: startup/helper loading and Unicode output, touch-driven color and normal exit, Back and fresh-session restart, callback error and traversal rejection, and successful normal launch after either error. The user authorized committing the correction and acceptance record.

M1 task 3 is accepted on this simulator. The acceptance does not expand the implementation beyond the scope below.

## Scope and later work

Task 3's engineering scope is Unicode startup plus persistent TJS state, the listed native-class/storage subset, host callbacks, deterministic result mapping, and lifecycle cleanup for returning scripts. `TwinQuillHost` is a proof interface, not Kirikiri `Window`/`Layer` or KAG. Scripts must return; the admitted VM has no instruction deadline or interruption for non-terminating loops. A dead process invalidates session handles; it does not replay startup automatically.

Legacy codepages, complete TVP/media registration, KAG, compressed/protected XP3, save media, full game rendering/audio/video, API 26 runtime proof, and physical ARMv7 runtime proof remain later work. Imported snapshots and their per-file checksums are unchanged.
