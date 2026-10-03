# Authored M3 storage fixtures

Reproduce with python scripts/create_krkr_m3_fixtures.py; GPL-2.0-or-later.
No commercial game data. Display/image assets come from the authored M2 fixture.
m3-vectors is shared by native and Java regression tests.

- m3-loose / m3-compressed: identical M2 display and subdirectory images.
- m3-patches: green verifies patch/loose precedence, Unicode, auto-path and listing.
- m3-cp932: purple verifies explicitly configured CP932 scripts and Japanese names.
- m3-save: blue first run, green subsequent runs for the same imported game.
  Writes UTF-8 text/structured data in the game's private krkr directory. Enter exits.

These exercise M3 storage foundations; KAG and game-state saving are later phases.
