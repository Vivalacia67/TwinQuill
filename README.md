# TwinQuill

TwinQuill is a GPL-2.0-or-later Android launcher and source-built runtime for
standard ONScripter and Kirikiri/Kirikiri Z games. It keeps each engine in an
isolated app-private process and builds every packaged native component from
pinned, auditable source.

> [!IMPORTANT]
> TwinQuill is under active development, not a general-purpose compatibility
> release. The ONS runtime has completed its M2 acceptance milestone; the
> Kirikiri runtime is still progressing through M3 and does not yet provide a
> complete Cocos scene, rendering, media, or title-compatibility stack.

## Project status

- **Launcher and storage:** game detection, launch flow, Storage Access
  Framework integration, read-only native VFS access, and per-game private
  runtime state are implemented.
- **ONScripter:** the accepted M2 runtime includes SAF-backed launches,
  ONScripterYuri built from source, common script encodings, NSA/SAR archives,
  CJK font fallback, private saves, Lua, PCM audio, Android platform video,
  audio focus, and an immersive runtime window.
- **Kirikiri/Kirikiri Z:** the active M3 runtime builds its TJS2, Oniguruma,
  KAG parser, XP3, and selected Cocos2d-x 3.6 boundaries from pinned source.
  Local and SAF storage, loose and standard unprotected XP3 startup, script-
  driven KAG parsing, bounded KAG runtime state, and a real Cocos
  geometry/affine-transform bridge are covered by Android tests.
- **Still in progress:** Cocos `Node`/`Scene`/`Layer`, visible rendering, input,
  lifecycle, Krkr media and save integration, plugins and filters, protected
  or title-specific XP3 behavior, and a broad real-game/device compatibility
  matrix.

TwinQuill does not promise support for proprietary plugins, unknown DRM, or
runtime-loaded native libraries. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
and [`docs/SOURCE_POLICY.md`](docs/SOURCE_POLICY.md) for the architecture and
source-integrity boundaries.

## Modules

- `launcher-app`: Android game library, detection, and launch application.
- `engine-api`: shared engine detection and launch contracts.
- `native-vfs`: Storage Access Framework to native filesystem bridge.
- `engine-ons`: source-built ONScripterYuri engine integration.
- `engine-krkr`: staged source-built Kirikiri/Kirikiri Z engine integration.

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
