# Source and dependency policy

TwinQuill builds every native component from auditable source. Precompiled
`.so`, `.a`, `.o`, `.dll`, `.dylib`, and `.lib` files are prohibited from the
source tree and from `vendor/`. Generated native outputs may exist only in
ignored build directories or CI artifacts.

## Source snapshots

Upstream repositories and official release archives are imported under
`vendor/` as source-only snapshots. Repository `.git` directories and local
untracked files are never copied. Every snapshot entry in
`third_party/sources.toml` records:

- the canonical upstream URL;
- the exact commit and Git tree object for a repository snapshot;
- the SHA-256 of `git archive --format=tar <commit>` or of the official release
  archive;
- the destination path and applicable license files.

If an official release archive bundles precompiled libraries, the manifest
must list those exclusions and the files must be omitted during import.

Git submodules are not treated as implicitly trusted content. Each gitlink is
listed separately and must receive its own source snapshot, checksum, and
license audit before an engine build may depend on it.

## Local patches

TwinQuill changes to imported code are maintained as reviewable patches under
`vendor/patches/<source-id>/`. Snapshot directories should otherwise match the
recorded upstream revision. Patch descriptions must state why the change is
required and how it was tested.

## Updating a source

1. Review the upstream diff and license changes.
2. Scan the candidate tree for native binaries and nested Git metadata.
3. Record the new commit, tree, and deterministic archive SHA-256.
4. Import the source-only archive.
5. Rebase TwinQuill patches and run repository hygiene, license, and build
   gates.

An unaudited license, missing source dependency, checksum mismatch, or native
binary blocks release.
