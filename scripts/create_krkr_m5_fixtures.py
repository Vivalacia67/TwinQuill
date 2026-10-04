# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors
"""Build authored M5 save/load games using the pinned, complete KAG3 framework."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

try:
    from . import create_krkr_m4_fixtures as m4
except ImportError:
    import create_krkr_m4_fixtures as m4

ROOT = Path(__file__).resolve().parents[1]
SCENE = ROOT / "tests/fixtures/krkr-m5/scene"
SAVE_IDS = ("e6db9148-b467-4ee7-b7fb-000000000501", "e6db9148-b467-4ee7-b7fb-000000000502")


def game_entries(identity: str = SAVE_IDS[0]) -> dict[str, bytes]:
    entries = m4.game_entries()
    config = entries["system/Config.tjs"].decode("utf-8")
    for old, new in (
        (";readOnlyMode = true;", ";readOnlyMode = false;"),
        (";numBookMarks = 0;", ";numBookMarks = 2;"),
        (';saveDataID = "00000000-0000-0000-0000-000000000000";', f';saveDataID = "{identity}";'),
        (";defaultFontSize = 24;", ";defaultFontSize = 20;"),
        (";autoRecordPageShowing = false;", ";autoRecordPageShowing = true;"),
    ):
        if config.count(old) != 1:
            raise ValueError(f"Pinned configuration changed: {old}")
        config = config.replace(old, new)
    entries["system/Config.tjs"] = config.encode("utf-8")
    entries.update({path.relative_to(SCENE).as_posix(): path.read_bytes()
                    for path in SCENE.rglob("*") if path.is_file()})
    entries["bgimage/menu.png"] = m4.png(640, 480, (70, 50, 95, 255))
    entries["TWINQUILL-FIXTURE.md"] = (ROOT / "tests/fixtures/krkr-m5/README.md").read_bytes()
    return dict(sorted(entries.items()))


def generate(output: Path) -> dict:
    entries = game_entries()
    m4.write_tree(output / "m5-loose", entries)
    packed = m4.xp3(list(entries.items()), compressed=True)
    m4.write_tree(output / "m5-compressed", {
        "data.xp3": packed, "KAG3-LICENSE.md": entries["KAG3-LICENSE.md"],
        "TWINQUILL-FIXTURE.md": entries["TWINQUILL-FIXTURE.md"],
    })
    other = dict(entries)
    other["system/Config.tjs"] = entries["system/Config.tjs"].replace(SAVE_IDS[0].encode(), SAVE_IDS[1].encode())
    m4.write_tree(output / "m5-other", other)
    broker = dict(entries)
    broker["scenario/first.ks"] = (ROOT / "tests/fixtures/krkr-m5/broker.ks").read_bytes()
    m4.write_tree(output / "m5-broker", broker)
    killed = dict(broker)
    killed["scenario/first.ks"] = broker["scenario/first.ks"].replace(
        b"[wait time=300]", b'[if exp="sf.visits===1"][wait time=60000][endif]')
    m4.write_tree(output / "m5-kill", killed)
    manifest = {"framework": m4.PIN, "save_ids": SAVE_IDS,
                "entries": {name: hashlib.sha256(data).hexdigest() for name, data in entries.items()},
                "xp3_sha256": hashlib.sha256(packed).hexdigest()}
    output.mkdir(parents=True, exist_ok=True)
    (output / "M5-FIXTURES.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / ".agent-work/m5-acceptance/games")
    args = parser.parse_args()
    result = generate(args.output.resolve())
    print(f"Generated M5 games in {args.output.resolve()}; {len(result['entries'])} entries; {result['xp3_sha256']}")
