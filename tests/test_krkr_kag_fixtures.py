# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors
"""Verify complete framework provenance and real media in the authored KAG game."""
import io
import struct
import unittest
import wave
import zlib
from pathlib import Path
from scripts import create_krkr_m4_fixtures as builder

ROOT = Path(__file__).resolve().parents[1]


def chunks(data):
    offset = 0
    while offset < len(data):
        tag = data[offset:offset+4]
        size = struct.unpack_from("<Q", data, offset+4)[0]
        yield tag, data[offset+12:offset+12+size]
        offset += 12+size
    if offset != len(data): raise AssertionError("truncated archive chunk")


class KrkrKagFixtureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.entries = builder.game_entries()

    def test_all_framework_scripts_are_retained_byte_for_byte(self):
        original = builder.FRAMEWORK / "data"
        for path in original.rglob("*"):
            if not path.is_file(): continue
            name = path.relative_to(original).as_posix()
            if name in {"system/Config.tjs", "scenario/first.ks"}: continue
            with self.subTest(path=name): self.assertEqual(self.entries[name], path.read_bytes())
        self.assertEqual(self.entries["KAG3-LICENSE.md"], (builder.FRAMEWORK/"README.md").read_bytes())

    def test_compressed_archive_restores_every_loose_resource(self):
        archive = builder.xp3(list(self.entries.items()), compressed=True)
        offset = struct.unpack_from("<Q", archive, 11)[0]
        self.assertEqual(archive[offset], 1)
        size, unpacked = struct.unpack_from("<QQ", archive, offset+1)
        index = zlib.decompress(archive[offset+17:offset+17+size])
        self.assertEqual(len(index), unpacked)
        restored = {}
        for tag, body in chunks(index):
            self.assertEqual(tag,b"File")
            fields = dict(chunks(body))
            count = struct.unpack_from("<H", fields[b"info"], 20)[0]
            name = fields[b"info"][22:22+count*2].decode("utf-16le")
            result = bytearray()
            for segment in range(0,len(fields[b"segm"]),28):
                flag, location, original, packed = struct.unpack_from("<IQQQ",fields[b"segm"],segment)
                part = archive[location:location+packed]
                if flag: part=zlib.decompress(part)
                self.assertEqual(len(part),original); result.extend(part)
            self.assertEqual(zlib.adler32(result),struct.unpack("<I",fields[b"adlr"])[0])
            restored[name]=bytes(result)
        self.assertEqual(restored,self.entries)

    def test_pcm_tones_are_non_silent_valid_mono_16bit_wav(self):
        for name, frames in [("bgm/tone.wav",16000),("sound/chime.wav",4000)]:
            with self.subTest(name=name), wave.open(io.BytesIO(self.entries[name])) as audio:
                self.assertEqual((audio.getnchannels(),audio.getsampwidth(),audio.getframerate(),audio.getnframes()),(1,2,16000,frames))
                samples=struct.unpack("<"+"h"*frames,audio.readframes(frames))
                self.assertLess(min(samples),-1000); self.assertGreater(max(samples),1000)

    def test_game_covers_wait_choices_calls_and_explicit_unsupported_boundaries(self):
        first=self.entries["scenario/first.ks"].decode("utf-8")
        for tag in ("[macro", "[p]", "[link", "[jump", "[call", "[iscript]", "[playbgm", "[playse", "[close"):
            self.assertIn(tag,first)
        self.assertIn("[return]", self.entries["scenario/chapter.ks"].decode("utf-8"))
        config=self.entries["system/Config.tjs"].decode("utf-8")
        for setting in (";numMovies = 0;", ";layerType = ltAlpha;", ";defaultBold = false;", "Noto Sans CJK SC"):
            self.assertIn(setting,config)


if __name__ == "__main__": unittest.main()
