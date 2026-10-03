# TwinQuill

TwinQuill is a GPL Android runtime that will manage ONScripter and
Kirikiri/Kirikiri Z games in one launcher and execute each engine in an
isolated Android process.

The Compose/Room launcher imports game directories and routes engine requests
into private `:ons` and `:krkr` processes. ONS integration is the existing
regression baseline. Krkr M0–M4 have passed simulator acceptance.

M1 provides a persistent Unicode TJS2 session and lifecycle callbacks. M2 adds
Window/Layer/Font, Timer/AsyncTrigger, basic PNG/JPEG, Noto text, composition and
input. M3 unifies loose/SAF/XP3 resources, raw/zlib and chained indexes,
patch/search-path ordering, strict Unicode or explicit CP932, and atomic private
writes under `filesDir/saves/<game-id>/krkr/`. ONS retains its parent-directory
save layout. See the [M2 handoff](docs/KRKR_M2_HANDOFF.md) and
[M3 storage contract](docs/KRKR_M3_STORAGE_CONTRACT.md).

M4 executes the complete pinned KAG3 startup and framework with a bounded
Android host, native KAGParser and real PCM16 WAV output. Its authored scene
covers text/pages, images, two branches, macros, embedded TJS, cross-file
call/return, Home recovery and normal/error exits from loose SAF or compressed
XP3. The selected configuration uses plain horizontal text and direct image
changes. M4 passed user acceptance on 2026-10-04; supported interfaces and emulator
instructions are in the [M4 audit](docs/KRKR_M4_INTERFACE_AUDIT.md) and
[M4 handoff](docs/KRKR_M4_HANDOFF.md).

Game-state save/load, wider media/plugins and unified save management remain
M5–M7 work; representative-game compatibility is verified in M8. The
[roadmap](docs/ROADMAP.md) defines dependencies and acceptance gates. Self-authored
fixtures passing does not establish arbitrary commercial-game compatibility.
The [M5 development plan](docs/KRKR_M5_PLAN.md) defines the next save/load tasks;
implementation has not started.
Architecture and earlier acceptance records are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md),
[the M0 handoff](docs/KRKR_M0_HANDOFF.md) and
[the M1 handoff](docs/KRKR_M1_TJS_ENTRY.md).

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

When preserving existing emulator data, use the rebuild/overwrite-install/direct
instrumentation commands in the [M4 handoff](docs/KRKR_M4_HANDOFF.md).
Gradle connected tests uninstall the target application and remove its private data.

On a disposable emulator, run
`gradlew.bat --no-daemon :launcher-app:connectedDebugAndroidTest` for the
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
