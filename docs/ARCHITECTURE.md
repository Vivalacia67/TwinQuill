# TwinQuill architecture

## Product boundary

TwinQuill is a GPL Android launcher and source-built runtime for standard ONS
and Kirikiri/Kirikiri Z games. It does not promise support for proprietary
plugins, unknown DRM, or runtime-loaded native libraries.

M0 establishes a reproducible source and toolchain baseline. Its runtime code
is intentionally narrow: the ONS probe runs a minimal script, while the Krkr
probe executes ASCII TJS from a loose file or a raw, unprotected XP3 archive.
These probes prove the difficult native integration path; they are not the M2
or M3 compatibility implementation.

## Module graph

- `launcher-app` packages the application and will own the Compose/Room game
  library in M1.
- `engine-api` will define detection, launch requests, and structured results.
- `native-vfs` will bridge Android Storage Access Framework documents to native
  stream operations in M1.
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

M1 will replace direct filesystem test paths with a shared launch request and
SAF-backed VFS. Game content remains read-only; writable state is redirected to
a per-game private save directory.

## Source and binary policy

Imported source snapshots live under `vendor/` and are locked by repository,
commit, tree, archive SHA-256, license metadata, and per-file checksums. Upstream
snapshots contain no nested Git metadata or precompiled native libraries.
TwinQuill-specific compatibility changes are recorded under `vendor/patches/`
and applied to ignored build copies.

The APK may contain only libraries produced by the current build. CI rejects
tracked native binaries, the accidental generic Krkr dependency `libonig.so`,
missing engine libraries, missing planned ABIs, and ELF load alignment below
16 KB.

## M0 XP3 boundary

The M0 XP3 reader accepts the standard header and `File`, `info`, `segm`, and
`adlr` chunk layout with strict bounds and size limits. It locates only a root
`startup.tjs` in raw, unprotected segments. It explicitly rejects compressed
indices or segments, protected files, malformed chunk sizes, oversized startup
scripts, and unsupported names. Full upstream-compatible archive search,
compression, filters, and plugin behavior belong to M3.
