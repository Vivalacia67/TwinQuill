# TwinQuill architecture

## Product boundary

TwinQuill is a GPL Android launcher and source-built runtime for standard ONS
and Kirikiri/Kirikiri Z games. It does not promise support for proprietary
plugins, unknown DRM, or runtime-loaded native libraries.

M0 establishes a reproducible source and toolchain baseline. Its runtime code
is intentionally narrow: the ONS probe runs a minimal script, while the Krkr
probe executes self-authored ASCII TJS from a loose file, a raw, unprotected
XP3 archive, or a read-only SAF tree via `tqsaf`/`native-vfs`. These probes
prove the difficult native integration path; they are not the M2 or M3
compatibility implementation.

## Module graph

- `launcher-app` packages the application and will own the Compose/Room game
  library in M1.
- `engine-api` will define detection, launch requests, and structured results.
- `native-vfs` currently provides a read-only Krkr SAF probe via
  `tqsaf`/`native-vfs`; generic media registration and writable/save
  capabilities remain future work.
- `engine-ons` builds ONScripterYuri, SDL2, FreeType, Lua, bzip2, and selected
  codecs from pinned source.
- `engine-krkr` currently builds the Kirikiroid2 TJS2 core and the exact krkrz
  Oniguruma dependency from pinned source. Cocos/KAG/rendering/media follow in
  M3.

The launcher depends on both engine libraries, but the engines do not depend on
each other.

## Process isolation

`OnsEngineActivity` runs in the application-private `:ons` process and
`KrkrEngineActivity` runs in `:krkr`. This separates native global state,
rendering and audio stacks, crash domains, and memory reclamation. Both
activities are non-exported in release builds. A debug-only manifest override
allows explicit ADB launches for legal M0 smoke fixtures.

The current Krkr probe accepts explicit loose-file, raw XP3, and read-only SAF
launch targets. `EngineLaunchRequest` currently routes content into those
probe targets; it is not the future M1 shared request. Game content remains
read-only. Writable Krkr save-media, save redirection, and broader KAG/Cocos
rendering, media, and playable support remain future work.

## Source and binary policy

Imported source snapshots live under `vendor/` and are locked by repository,
commit, tree, archive SHA-256, license metadata, and per-file checksums. Upstream
snapshots contain no nested Git metadata or precompiled native libraries.
TwinQuill-specific compatibility changes are recorded under `vendor/patches/`
and applied to ignored build copies.

The APK may contain only libraries produced by the current build, except for
the selected C++ runtime that the pinned NDK injects into ignored build/APK
outputs. CI rejects tracked native binaries, the accidental generic Krkr
dependency `libonig.so`, missing engine libraries, missing planned ABIs, and
ABI-mismatched ELF files. Every TwinQuill-built ELF and the arm64-v8a NDK
runtime must have PT_LOAD alignment at least 16 KiB (0x4000); the only 4 KiB
(0x1000) exception is a validated ELF32/EM_ARM
`lib/armeabi-v7a/libc++_shared.so` from that pinned NDK.

## M0 XP3 boundary

The M0 XP3 reader accepts the standard header and `File`, `info`, `segm`, and
`adlr` chunk layout with strict bounds and size limits. It locates only a root
`startup.tjs` in raw, unprotected segments. It explicitly rejects compressed
indices or segments, protected files, malformed chunk sizes, oversized startup
scripts, and unsupported names. Full upstream-compatible archive search,
compression, filters, and plugin behavior belong to M3.
