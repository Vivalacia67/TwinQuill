# TwinQuill

TwinQuill is a GPL Android runtime that will manage ONScripter and
Kirikiri/Kirikiri Z games in one launcher and execute each engine in an
isolated Android process.

The project is currently in milestone M0. It has source-built runtime probes,
not a general-purpose playable release:

- ONScripterYuri runs a self-authored minimal `0.txt` fixture.
- Kirikiroid2's TJS2 core runs loose `startup.tjs` and an unprotected,
  uncompressed XP3 fixture.
- Both engine entry points run in app-private Android processes.

Game compatibility, SAF-backed storage, launcher UI, save redirection, full
KAG/Cocos rendering, compressed XP3 support, media, and plugin handling remain
future milestones. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

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

Create an ignored `local.properties` that points at the Android SDK, then run:

```shell
./gradlew assembleDebug
```

On Windows, use `gradlew.bat`. A clean build compiles every packaged native
library from the pinned source snapshots; generated `.so` files remain ignored
build products.

Repository and build-output gates can be run with:

```shell
python scripts/check_repository_hygiene.py
python scripts/verify_source_snapshots.py
python -m unittest discover -s tests -v
python scripts/check_apk_native_libraries.py <debug.apk> <release.apk>
```

## License

TwinQuill-authored code is licensed under GPL-2.0-or-later. Imported upstream
source remains under the license recorded alongside that source. See
[`docs/SOURCE_POLICY.md`](docs/SOURCE_POLICY.md) and
[`third_party/sources.toml`](third_party/sources.toml).
