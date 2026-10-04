# Authored KAG3 M5 save/load scene

SPDX-License-Identifier: GPL-2.0-or-later

The original scenes use the full pinned KAG3 framework and M4's audited display
configuration, original PNG artwork and PCM tones. The generator enables writes,
two bookmark slots and distinct save format identities for two game fixtures.
Framework scripts remain byte-identical to the pinned source; Config.tjs and
first.ks are the explicit game configuration/scenario replacements.

Named KAG labels are the save checkpoints. Slot 0 restores the station before
the choice; slot 1 restores the called chapter with its call stack. KAG replays
the checkpoint's text/links after restoring images, variables and BGM. The
purple title menu exposes New, Load 0, Load 1 and Quit. Saves live in the selected
game's private krkr directory, with system data separate from slot snapshots.

BGM plays at 40% in the station and is paused inside the chapter. RETURN
resumes it after validating the restored call stack. Automatic read records,
the text-speed setting and the system launch/save counters persist separately.
The private m5-ready.tjs marker is emitted only after each real page and its
links finish; instrumentation uses it to avoid touching stale links during
asynchronous KAG loading, in addition to checking rendered pixels and audio.
Before loading slot 0, the scene replaces its live statusline macro with an
invalid definition; the valid saved macro must restore before text can appear.
