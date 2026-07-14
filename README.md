# TwinQuill

TwinQuill is a GPL Android runtime that will manage ONScripter and
Kirikiri/Kirikiri Z games in one launcher and execute each engine in an
isolated Android process.

The project is currently in milestone M0. The checked-in native libraries are
source-build probes only; no game engine is playable yet.

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

## License

TwinQuill-authored code is licensed under GPL-2.0-or-later. Imported upstream
source remains under the license recorded alongside that source. See
[`docs/SOURCE_POLICY.md`](docs/SOURCE_POLICY.md) and
[`third_party/sources.toml`](third_party/sources.toml).
