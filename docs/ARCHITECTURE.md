# TwinQuill architecture

## Product boundary

TwinQuill is a GPL Android launcher and source-built runtime for standard ONS
and Kirikiri/Kirikiri Z games. It does not promise support for proprietary
plugins, unknown DRM, or runtime-loaded native libraries.

M0 established the source/toolchain baseline and was accepted on 2026-10-02.
M1 task 3 was accepted on the Android 16 / API 36 simulator on 2026-10-02.
It replaces the fixed-value Krkr script probe with a common TJS2 startup
entry. It executes ordinary scripts from a loose file, a raw,
unprotected XP3 archive, or a read-only SAF tree via `tqsaf`/`native-vfs`.
The entry accepts UTF-8 and BOM-marked UTF-16LE/BE, rejects malformed text and
embedded NULs, and bounds each script to 8 MiB. A persistent session registers
the admitted `Scripts`, `Storages`, `System`, and `Debug` native-class subset.
`TwinQuillHost` is a first-party callback/color extension for the existing GLES
test surface; KAG/game rendering is later work.

## Module graph

- `launcher-app` packages the application and owns the Compose/Room game
  library, directory grants, and engine routing.
- `engine-api` defines detection, launch requests, and structured results.
- `native-vfs` provides read-only Krkr SAF startup and subsequent script reads via
  `tqsaf`/`native-vfs`; generic media registration and writable/save
  capabilities remain future work.
- `engine-ons` builds ONScripterYuri, SDL2, FreeType, Lua, bzip2, and selected
  codecs from pinned source.
- `engine-krkr` currently builds the Kirikiroid2 TJS2 core and the exact krkrz
  Oniguruma dependency from pinned source. It also admits the source-pinned
  Cocos image/math core and two GLES proof shaders; full KAG/rendering/media
  integration remains later work.

The launcher depends on both engine libraries, but the engines do not depend on
each other.

## Process isolation

`OnsEngineActivity` runs in the application-private `:ons` process and
`KrkrEngineActivity` runs in `:krkr`. This separates native global state,
rendering and audio stacks, crash domains, and memory reclamation. Both
activities are non-exported in release builds. A debug-only manifest override
allows explicit ADB launches for legal M0 smoke fixtures.

The Krkr broker validates `EngineLaunchRequest`, executes `startup.tjs` on a
worker, and opens the private runtime host only on successful script completion.
One TJS2 session is admitted per `:krkr` process because the imported core owns
process-global caches. The broker executes startup once and transfers a private
opaque handle to the runtime. Globals and callbacks survive Home/task return,
surface/context replacement, and Activity recreation. A replaced window surface
rebuilds its GL resources even when Android retains the EGL context. Closing,
failed startup, or invalid
recreated requests release the VM; process death invalidates the handle.

Callbacks run on a separate worker with 64 pending slots. The UI and GL thread
poll atomic status/color; they do not execute scripts. Teardown drops pending
callbacks and releases the VM after the active callback returns. Script, syntax,
and text errors return `SCRIPT_ERROR`; SAF permission/VFS errors retain their
existing result categories. Storage names are relative to the game root; traversal,
absolute paths, NULs, and canonical paths escaping through symlinks are rejected.
Writable Krkr save-media, broader KAG/Cocos rendering, media, and playable support
remain future work. See `KRKR_M1_TJS_ENTRY.md` for the API subset and checks.

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
