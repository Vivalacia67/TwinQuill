# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors
"""Build authored M4 games around the complete, license-pinned KAG3 framework."""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path
try:
    from .create_krkr_m3_fixtures import chunk, deflate, xp3
except ImportError:
    from create_krkr_m3_fixtures import chunk, deflate, xp3

ROOT = Path(__file__).resolve().parents[1]
FRAMEWORK = ROOT / "vendor/kag3-1f3ab309"
SCENE = ROOT / "tests/fixtures/krkr-m4/scene"
PIN = "krkrz/kag3@1f3ab309106d210e3169bbbe0fb4e066ae463b42"


def png(width: int, height: int, color: tuple[int, int, int, int]) -> bytes:
    """Author uniform RGBA images with canonical compressed scanlines."""
    import zlib
    def part(tag, content):
        return struct.pack(">I", len(content)) + tag + content + struct.pack(">I", zlib.crc32(tag + content))
    pixels = b"".join(b"\0" + b"".join(bytes(color(x,y)) for x in range(width)) for y in range(height)) if callable(color) else (b"\0" + bytes(color) * width) * height
    return b"\x89PNG\r\n\x1a\n" + part(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) + part(b"IDAT", deflate(pixels)) + part(b"IEND", b"")


def guide_pixel(x: int, y: int) -> tuple[int, int, int, int]:
    """An original station guide silhouette, authored with integer geometry."""
    if 12 <= x < 68 and 4 <= y < 22: return (34, 48, 66, 255)
    if 24 <= x < 56 and 22 <= y < 58:
        if y in range(34,39) and (30 <= x < 34 or 46 <= x < 50): return (34,48,66,255)
        return (216,192,120,255)
    if 12 <= x < 68 and 58 <= y < 132:
        if 37 <= x < 43: return (240,210,70,255)
        return (58,100,140,255)
    if y >= 132 and (18 <= x < 36 or 44 <= x < 62): return (34,48,66,255)
    return (0,0,0,0)


def wave(period: int, frames: int) -> bytes:
    """Integer triangle tones: 16 kHz, mono PCM16, no platform float rounding."""
    samples = bytearray()
    for index in range(frames):
        phase = index % period
        value = -4000 + (16000 * min(phase, period - phase) // period)
        samples.extend(struct.pack("<h", value))
    fmt = struct.pack("<HHIIHH", 1, 1, 16000, 32000, 2, 16)
    body = b"WAVEfmt " + struct.pack("<I", len(fmt)) + fmt + b"data" + struct.pack("<I", len(samples)) + samples
    return b"RIFF" + struct.pack("<I", len(body)) + body


def game_entries() -> dict[str, bytes]:
    entries = {path.relative_to(FRAMEWORK / "data").as_posix(): path.read_bytes()
               for path in (FRAMEWORK / "data").rglob("*") if path.is_file()}
    entries.update({path.relative_to(SCENE).as_posix(): path.read_bytes()
                    for path in SCENE.rglob("*") if path.is_file()})
    entries["bgimage/station.png"] = png(640, 480, (40, 80, 64, 255))
    entries["bgimage/blue.png"] = png(640, 480, (32, 64, 176, 255))
    entries["bgimage/amber.png"] = png(640, 480, (176, 96, 32, 255))
    entries["fgimage/guide.png"] = png(80, 180, guide_pixel)
    entries["image/M4LineBreak.png"] = png(12, 12, (240, 210, 70, 255))
    entries["image/M4PageBreak.png"] = png(12, 12, (240, 210, 70, 255))
    entries["bgm/tone.wav"] = wave(80, 16000)
    entries["sound/chime.wav"] = wave(40, 4000)
    entries["KAG3-LICENSE.md"] = (FRAMEWORK / "README.md").read_bytes()
    entries["TWINQUILL-FIXTURE.md"] = (ROOT / "tests/fixtures/krkr-m4/README.md").read_bytes()
    return dict(sorted(entries.items()))


def write_tree(directory: Path, entries: dict[str, bytes]) -> None:
    for name, data in entries.items():
        destination = directory / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)


def generate(output: Path) -> dict:
    """Overwrite only known authored paths; preserve unrelated local files."""
    entries = game_entries()
    write_tree(output / "m4-loose", entries)
    packed = xp3(list(entries.items()), compressed=True)
    write_tree(output / "m4-compressed", {"data.xp3": packed, "KAG3-LICENSE.md": entries["KAG3-LICENSE.md"],
                                        "TWINQUILL-FIXTURE.md": entries["TWINQUILL-FIXTURE.md"]})
    for name, scenario in {
        "m4-broker": b'*start\n[image layer=base page=fore storage="station.png"]\n[nowait]Production KAG broker.[r]\n[playse storage="chime.wav"]\n[wait time=400]\n[close ask=false]\n',
        "m4-missing": b'*start\n[image layer=base page=fore storage="missing.png"]\n',
        "m4-script-error": b'*start\n[iscript]\nthrow new Exception("Authored M4 script error");\n[endscript]\n',
    }.items():
        broken = dict(entries)
        broken["scenario/first.ks"] = scenario
        write_tree(output / name, broken)
    manifest = {"framework": PIN,
                "entries": {name: hashlib.sha256(data).hexdigest() for name, data in entries.items()},
                "xp3_sha256": hashlib.sha256(packed).hexdigest()}
    output.mkdir(parents=True, exist_ok=True)
    (output / "M4-FIXTURES.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / ".agent-work/m4-acceptance/games")
    args = parser.parse_args()
    result = generate(args.output.resolve())
    print(f"Generated M4 games in {args.output.resolve()}; {len(result['entries'])} entries; {result['xp3_sha256']}")
