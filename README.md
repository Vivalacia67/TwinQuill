# TwinQuill

TwinQuill is a GPL Android runtime that will manage ONScripter and
Kirikiri/Kirikiri Z games in one launcher and execute each engine in an
isolated Android process.

M0 and M1 task 3 (formal TJS entry) were accepted on 2026-10-02, with M1
validated on the Android 16 / API 36 simulator. M1 provides a persistent TJS2
startup and callback session, basic TVP interfaces, and a script-controlled test surface:

- ONScripterYuri runs a self-authored minimal `0.txt` fixture.
- Kirikiroid2's TJS2 core executes `startup.tjs` from loose storage, raw
  unprotected XP3, or read-only SAF via `tqsaf`/`native-vfs`. Startup text accepts
  UTF-8 (including ASCII, with optional BOM) and BOM-marked UTF-16LE/BE.
- Scripts can load additional read-only game scripts, retain variables across
  input, Home/task return, and Activity recreation, change the proof quad color,
  and request exit.
- Both engine entry points run in app-private Android processes.

The Compose/Room launcher imports game directories and routes engine requests.
Game compatibility, save redirection, full KAG/Cocos rendering,
compressed XP3 support, media, and plugin handling remain future milestones.
See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), the accepted
[`M0 handoff`](docs/KRKR_M0_HANDOFF.md), and the
[`M1 TJS entry handoff and acceptance`](docs/KRKR_M1_TJS_ENTRY.md).

The [Krkr integration roadmap](docs/ROADMAP.md) defines the remaining M2–M8
work, dependencies, and acceptance gates through game execution and unified
game/save management. ONS integration remains the existing regression baseline.

M2 implements host-ready startup, the pinned Window/Layer/Font, Timer and
AsyncTrigger bindings, and an Android backend for basic PNG/JPEG, text,
composition and input. Script cancellation and lifecycle recovery are bounded.
The pinned KAG3 framework remains an audit source until M4. M2 implementation
was accepted on the simulator on 2026-10-03; supported members, limits and instructions
are in the [M2 handoff](docs/KRKR_M2_HANDOFF.md).

## Modules

- `launcher-app`: Android launcher application.
- `engine-api`: shared engine detection and launch contracts.
- `native-vfs`: Storage Access Framework to native filesystem bridge.
- `engine-ons`: ONScripter engine integration.
- `engine-krkr`: Kirikiri/Kirikiri Z engine integration.

## Build baseline

- JDK 17
- Gradle 9.4.1 and Android Gradle Plugin 9.2.1
- Android compile/target SDK 36 and minimum SDK 26
- NDK 28.2.13676358 and CMake 3.22.1
- `arm64-v8a` and `armeabi-v7a`

Set `JAVA_HOME` to your JDK 17 installation. The daemon criteria in
`gradle/gradle-daemon-jvm.properties` also require a local JDK 17, keeping
Android Studio and command-line builds on the same Java version.

Create an ignored `local.properties` that points at the Android SDK, then run:

```shell
./gradlew assembleDebug
```

On Windows, use `gradlew.bat`. A clean build compiles every TwinQuill native
component from pinned source snapshots; the only additional packaged native
binary is the selected `libc++_shared.so` injected by the pinned NDK into
ignored build/APK outputs.

Repository and build-output gates can be run with:

```shell
python scripts/check_repository_hygiene.py
python scripts/verify_source_snapshots.py
python -m unittest discover -s tests -v
python scripts/check_apk_native_libraries.py <debug.apk> <release.apk>
```

## Windows emulator testing

Run `gradlew.bat --no-daemon :launcher-app:connectedDebugAndroidTest` for the
device smoke tests. Repeat with `-PtwinquillKrkrRuntimeInstrumentation=true`
for the Krkr process tests. Functional launches allow 60 seconds for cold
starts and ARM translation; each test removes its task and waits for the
ONS runtime process to exit.

If an AVD stalls in `HardwareRenderer` or reports graphics memory errors,
use a cold boot with SwiftShader and Vulkan disabled (for example, `Medium_Phone`):

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Medium_Phone -gpu swiftshader -feature -Vulkan -no-snapshot-load
```

This keeps the GLES paths used by both engines enabled. See the Android
[emulator troubleshooting guide](https://developer.android.com/studio/run/emulator-troubleshooting).

## License

TwinQuill-authored code is licensed under GPL-2.0-or-later. Imported upstream
source remains under the license recorded alongside that source. See
[`docs/SOURCE_POLICY.md`](docs/SOURCE_POLICY.md) and
[`third_party/sources.toml`](third_party/sources.toml).
